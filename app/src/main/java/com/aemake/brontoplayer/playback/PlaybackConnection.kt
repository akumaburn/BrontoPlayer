package com.aemake.brontoplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.aemake.brontoplayer.data.db.BookEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Immutable snapshot of playback state surfaced to the UI. */
data class PlayerUiState(
    val isConnected: Boolean = false,
    val bookId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val sleepActive: Boolean = false,
    val sleepRemainingMs: Long = 0L,
    val sleepEndOfChapter: Boolean = false,
    val title: String? = null,
    val author: String? = null,
    /** Non-null when the current item failed to play; surfaced to the user. */
    val error: String? = null,
)

/**
 * Client-side bridge to [PlaybackService]. Holds a [MediaController], mirrors its state into
 * a [StateFlow], and exposes intent-style methods used by the ViewModels. Connect/release are
 * tied to the host Activity lifecycle so the service isn't kept bound while the UI is gone.
 */
class PlaybackConnection(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controller: MediaController? = null
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var positionJob: Job? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    fun connect() {
        if (future != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val f = MediaController.Builder(appContext, token)
            .setListener(controllerListener)
            .buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(playerListener)
            _state.update { it.copy(isConnected = true) }
            syncFromController()
            startPolling()
            while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun release() {
        positionJob?.cancel()
        positionJob = null
        controller?.removeListener(playerListener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        pending.clear()
        _state.update { it.copy(isConnected = false) }
    }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else pending.addLast(action)
    }

    // ----- Commands -----

    /** Loads [book] (resuming at its saved position) and starts playing. */
    fun playBook(book: BookEntity) = withController { c -> loadInto(c, book, play = true) }

    /**
     * Seeks to [positionMs] within [book]. If [book] is not the controller's current item it is
     * loaded *directly at* [positionMs]. Loading at the target avoids a separate seekTo that can be
     * dropped while a large file is still being prepared — which made the first chapter tap on a
     * freshly imported book appear to do nothing (it had to be tapped twice).
     */
    fun seekInBook(book: BookEntity, positionMs: Long, play: Boolean) = withController { c ->
        val target = positionMs.coerceAtLeast(0L)
        if (c.currentMediaItem?.mediaId == book.id) {
            c.seekTo(target)
            if (play && !c.isPlaying) c.play()
            refreshPosition()
        } else {
            loadInto(c, book, play = play, startPositionMs = target)
        }
    }

    private fun loadInto(
        c: MediaController,
        book: BookEntity,
        play: Boolean,
        startPositionMs: Long = book.positionMs,
    ) {
        if (c.currentMediaItem?.mediaId == book.id) {
            if (play) c.play()
            return
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author ?: book.narrator)
            .setAlbumTitle(book.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .apply { book.coverPath?.let { setArtworkUri(Uri.fromFile(File(it))) } }
            .build()
        val item = MediaItem.Builder()
            .setMediaId(book.id)
            .setUri(book.id)
            .setMediaMetadata(metadata)
            .build()
        val start = startPositionMs.coerceIn(0L, if (book.durationMs > 0) book.durationMs else Long.MAX_VALUE)
        c.setMediaItem(item, start)
        c.setPlaybackSpeed(if (book.speed > 0f) book.speed else 1f)
        c.prepare()
        if (play) c.play()
    }

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) c.pause() else c.play()
    }

    fun play() = withController { it.play() }
    fun pause() = withController { it.pause() }

    fun seekTo(positionMs: Long) = withController { c ->
        c.seekTo(positionMs.coerceAtLeast(0L))
        refreshPosition()
    }

    fun seekBy(deltaMs: Long) = withController { c ->
        val target = (c.currentPosition + deltaMs).coerceIn(0L, c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        c.seekTo(target)
        refreshPosition()
    }

    fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed) }

    fun setSkipSilence(enabled: Boolean) = sendCustom(
        PlaybackCommands.SET_SKIP_SILENCE,
        Bundle().apply { putBoolean(PlaybackCommands.ARG_SKIP_SILENCE, enabled) },
    )

    fun setSleepTimer(durationMs: Long, endOfChapter: Boolean) = sendCustom(
        PlaybackCommands.SET_SLEEP_TIMER,
        Bundle().apply {
            putLong(PlaybackCommands.ARG_SLEEP_DURATION_MS, durationMs)
            putBoolean(PlaybackCommands.ARG_SLEEP_END_OF_CHAPTER, endOfChapter)
        },
    )

    fun cancelSleepTimer() = sendCustom(PlaybackCommands.CANCEL_SLEEP_TIMER, Bundle.EMPTY)

    private fun sendCustom(action: String, args: Bundle) = withController { c ->
        c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }

    // ----- State mirroring -----

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromController()
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                if (player.isPlaying) startPolling() else positionJob?.cancel()
            }
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            _state.update {
                it.copy(
                    skipSilence = extras.getBoolean(PlaybackCommands.EXTRA_SKIP_SILENCE, it.skipSilence),
                    sleepActive = extras.getBoolean(PlaybackCommands.EXTRA_SLEEP_ACTIVE, it.sleepActive),
                    sleepRemainingMs = extras.getLong(PlaybackCommands.EXTRA_SLEEP_REMAINING_MS, it.sleepRemainingMs),
                    sleepEndOfChapter = extras.getBoolean(PlaybackCommands.EXTRA_SLEEP_END_OF_CHAPTER, it.sleepEndOfChapter),
                )
            }
        }

        override fun onDisconnected(controller: MediaController) {
            _state.update { it.copy(isConnected = false, isPlaying = false) }
        }
    }

    private fun syncFromController() {
        val c = controller ?: return
        val meta = c.mediaMetadata
        _state.update {
            it.copy(
                isConnected = true,
                bookId = c.currentMediaItem?.mediaId,
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = c.duration.takeIf { d -> d > 0L } ?: it.durationMs,
                bufferedPositionMs = c.bufferedPosition.coerceAtLeast(0L),
                speed = c.playbackParameters.speed,
                title = meta.title?.toString() ?: it.title,
                author = meta.artist?.toString() ?: it.author,
                error = c.playerError?.let(::friendlyPlaybackError),
            )
        }
    }

    private fun friendlyPlaybackError(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "Can't access this file anymore. Remove it and add it again."
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            "This audio format isn't supported on your device."
        else -> "Couldn't play this audiobook (${e.errorCodeName})."
    }

    private fun refreshPosition() {
        val c = controller ?: return
        _state.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0L)) }
    }

    private fun startPolling() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                val c = controller ?: break
                if (c.isPlaying) {
                    _state.update {
                        it.copy(
                            positionMs = c.currentPosition.coerceAtLeast(0L),
                            bufferedPositionMs = c.bufferedPosition.coerceAtLeast(0L),
                            durationMs = c.duration.takeIf { d -> d > 0L } ?: it.durationMs,
                        )
                    }
                }
                delay(500L)
            }
        }
    }
}
