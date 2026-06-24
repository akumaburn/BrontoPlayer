package com.aemake.brontoplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.aemake.brontoplayer.MainActivity
import com.aemake.brontoplayer.R
import com.aemake.brontoplayer.data.db.ChapterEntity
import com.aemake.brontoplayer.di.ServiceLocator
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Background-capable audio service built on Media3. While playing it is promoted to a
 * foreground service of type `mediaPlayback` (declared in the manifest), so the OS keeps
 * audiobooks running with the screen off and the app in the background.
 *
 * Adds audiobook-specific behavior on top of ExoPlayer: per-book resume position saving,
 * chapter tracking, skip-silence, and a sleep timer (fixed duration or end-of-chapter).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val library get() = ServiceLocator.libraryRepository
    private val settings get() = ServiceLocator.settingsRepository

    private var currentBookId: String? = null
    private var chapters: List<ChapterEntity> = emptyList()
    private var autoRewindMs: Long = 5_000L
    private var rewindMs: Long = 10_000L
    private var forwardMs: Long = 30_000L
    private var wasPausedByUser = false

    private var sleepJob: Job? = null

    /** Rewind / fast-forward buttons shown in the notification & on the lock screen. */
    private var mediaButtons: ImmutableList<CommandButton> = ImmutableList.of()

    /** Locally-held copy of the session extras so we can merge updates without a getter. */
    private val liveExtras = Bundle()

    override fun onCreate() {
        super.onCreate()

        val initial = runBlocking { settings.settings.first() }
        autoRewindMs = initial.autoRewindSeconds * 1000L
        rewindMs = initial.rewindSeconds * 1000L
        forwardMs = initial.forwardSeconds * 1000L

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(initial.rewindSeconds * 1000L)
            .setSeekForwardIncrementMs(initial.forwardSeconds * 1000L)
            .build()
            .apply {
                skipSilenceEnabled = initial.skipSilence
                addListener(playerListener)
            }

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Audiobook-appropriate notification / lock-screen controls: rewind, play/pause, forward
        // (instead of the default skip-to-previous/next, which are meaningless for a single file).
        val rewindButton = CommandButton.Builder(CommandButton.ICON_REWIND)
            .setDisplayName(getString(R.string.rewind))
            .setSessionCommand(SessionCommand(PlaybackCommands.REWIND, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val forwardButton = CommandButton.Builder(CommandButton.ICON_FAST_FORWARD)
            .setDisplayName(getString(R.string.fast_forward))
            .setSessionCommand(SessionCommand(PlaybackCommands.FAST_FORWARD, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
        mediaButtons = ImmutableList.of(rewindButton, forwardButton)

        session = MediaSession.Builder(this, player)
            .setId("BrontoPlayerSession")
            .setCallback(SessionCallback())
            .setSessionActivity(sessionActivity)
            .setMediaButtonPreferences(mediaButtons)
            .build()

        // Keep seek intervals in sync with settings changes.
        scope.launch {
            settings.settings.collect {
                autoRewindMs = it.autoRewindSeconds * 1000L
                rewindMs = it.rewindSeconds * 1000L
                forwardMs = it.forwardSeconds * 1000L
            }
        }
        // Periodic progress persistence.
        scope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                persistProgress()
            }
        }
        publishSkipSilence()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistProgressBlocking()
        sleepJob?.cancel()
        session?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        session = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Player listener: chapter loading, auto-rewind, progress saves
    // ---------------------------------------------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            if (id != currentBookId) {
                currentBookId = id
                chapters = emptyList()
                if (id != null) {
                    scope.launch { chapters = library.getChapters(id) }
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                // Auto-rewind a few seconds when resuming after a pause.
                if (wasPausedByUser && autoRewindMs > 0L && player.isCurrentMediaItemSeekable) {
                    val target = (player.currentPosition - autoRewindMs).coerceAtLeast(0L)
                    player.seekTo(target)
                }
                wasPausedByUser = false
            } else {
                wasPausedByUser = true
                persistProgress()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) persistProgress()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Session callback: connection, custom commands, URI resolution
    // ---------------------------------------------------------------------------------------------

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(PlaybackCommands.SET_SKIP_SILENCE, Bundle.EMPTY))
                .add(SessionCommand(PlaybackCommands.SET_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(PlaybackCommands.CANCEL_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(PlaybackCommands.REWIND, Bundle.EMPTY))
                .add(SessionCommand(PlaybackCommands.FAST_FORWARD, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setMediaButtonPreferences(mediaButtons)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                PlaybackCommands.SET_SKIP_SILENCE -> {
                    player.skipSilenceEnabled = args.getBoolean(PlaybackCommands.ARG_SKIP_SILENCE)
                    publishSkipSilence()
                    return success()
                }
                PlaybackCommands.SET_SLEEP_TIMER -> {
                    val durationMs = args.getLong(PlaybackCommands.ARG_SLEEP_DURATION_MS, 0L)
                    val endOfChapter = args.getBoolean(PlaybackCommands.ARG_SLEEP_END_OF_CHAPTER, false)
                    startSleepTimer(durationMs, endOfChapter)
                    return success()
                }
                PlaybackCommands.CANCEL_SLEEP_TIMER -> {
                    cancelSleepTimer()
                    return success()
                }
                PlaybackCommands.REWIND -> {
                    player.seekTo((player.currentPosition - rewindMs).coerceAtLeast(0L))
                    return success()
                }
                PlaybackCommands.FAST_FORWARD -> {
                    val target = player.currentPosition + forwardMs
                    val duration = player.duration
                    player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                    return success()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            // MediaController strips the URI (localConfiguration) when crossing the binder,
            // so rebuild each item's URI from its mediaId (which we set to the content URI).
            val resolved = mediaItems.map { item ->
                if (item.localConfiguration != null) item
                else item.buildUpon().setUri(item.mediaId).build()
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    private fun success(): ListenableFuture<SessionResult> =
        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))

    // ---------------------------------------------------------------------------------------------
    // Sleep timer
    // ---------------------------------------------------------------------------------------------

    private fun startSleepTimer(durationMs: Long, endOfChapter: Boolean) {
        cancelSleepTimer()
        sleepJob = scope.launch {
            if (endOfChapter) {
                publishSleep(active = true, remainingMs = 0L, endOfChapter = true)
                while (isActive) {
                    delay(500L)
                    val end = currentChapterEndMs()
                    if (end != null && player.currentPosition >= end - 200L) break
                    if (!player.isPlaying && player.playbackState == Player.STATE_ENDED) break
                }
                if (isActive) finishSleep()
            } else {
                var remaining = durationMs
                while (isActive && remaining > 0L) {
                    publishSleep(active = true, remainingMs = remaining, endOfChapter = false)
                    delay(1_000L)
                    if (player.isPlaying) remaining -= 1_000L
                }
                if (isActive) finishSleep()
            }
        }
    }

    private fun finishSleep() {
        player.pause()
        cancelSleepTimer()
    }

    private fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        publishSleep(active = false, remainingMs = 0L, endOfChapter = false)
    }

    private fun currentChapterEndMs(): Long? {
        val pos = player.currentPosition
        return chapters.firstOrNull { pos >= it.startMs && pos < it.endMs }?.endMs
            ?: chapters.lastOrNull()?.endMs
    }

    // ---------------------------------------------------------------------------------------------
    // Session extras broadcasts
    // ---------------------------------------------------------------------------------------------

    private fun pushExtras() {
        session?.setSessionExtras(Bundle(liveExtras))
    }

    private fun publishSleep(active: Boolean, remainingMs: Long, endOfChapter: Boolean) {
        liveExtras.putBoolean(PlaybackCommands.EXTRA_SLEEP_ACTIVE, active)
        liveExtras.putLong(PlaybackCommands.EXTRA_SLEEP_REMAINING_MS, remainingMs)
        liveExtras.putBoolean(PlaybackCommands.EXTRA_SLEEP_END_OF_CHAPTER, endOfChapter)
        pushExtras()
    }

    private fun publishSkipSilence() {
        liveExtras.putBoolean(PlaybackCommands.EXTRA_SKIP_SILENCE, player.skipSilenceEnabled)
        pushExtras()
    }

    // ---------------------------------------------------------------------------------------------
    // Progress persistence
    // ---------------------------------------------------------------------------------------------

    private fun chapterIndexFor(position: Long): Int {
        if (chapters.isEmpty()) return 0
        val idx = chapters.indexOfLast { position >= it.startMs }
        return if (idx < 0) 0 else idx
    }

    private fun persistProgress() {
        val id = currentBookId ?: return
        if (player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0) return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val chapterIndex = chapterIndexFor(pos)
        scope.launch(Dispatchers.IO) { library.saveProgress(id, pos, chapterIndex) }
    }

    private fun persistProgressBlocking() {
        val id = currentBookId ?: return
        if (player.mediaItemCount == 0) return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val chapterIndex = chapterIndexFor(pos)
        runCatching { runBlocking { library.saveProgress(id, pos, chapterIndex) } }
    }

    private companion object {
        const val PROGRESS_SAVE_INTERVAL_MS = 10_000L
    }
}
