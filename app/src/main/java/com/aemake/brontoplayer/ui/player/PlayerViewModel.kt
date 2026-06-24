package com.aemake.brontoplayer.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aemake.brontoplayer.data.db.BookEntity
import com.aemake.brontoplayer.data.db.ChapterEntity
import com.aemake.brontoplayer.data.prefs.AppSettings
import com.aemake.brontoplayer.data.prefs.SettingsRepository
import com.aemake.brontoplayer.data.repository.LibraryRepository
import com.aemake.brontoplayer.di.ServiceLocator
import com.aemake.brontoplayer.playback.PlaybackConnection
import com.aemake.brontoplayer.playback.PlayerUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlayerScreenState(
    val book: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val playback: PlayerUiState = PlayerUiState(),
    val settings: AppSettings = AppSettings(),
    val isActive: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val currentChapterIndex: Int = 0,
) {
    val currentChapter: ChapterEntity? get() = chapters.getOrNull(currentChapterIndex)
}

class PlayerViewModel(
    private val library: LibraryRepository,
    private val settingsRepo: SettingsRepository,
    private val connection: PlaybackConnection,
    private val bookId: String,
) : ViewModel() {

    val state: StateFlow<PlayerScreenState> = combine(
        library.observeBook(bookId),
        library.observeChapters(bookId),
        connection.state,
        settingsRepo.settings,
    ) { book, chapters, playback, settings ->
        val isActive = playback.bookId == bookId
        val position = if (isActive) playback.positionMs else (book?.positionMs ?: 0L)
        val duration = book?.durationMs?.takeIf { it > 0 } ?: playback.durationMs
        val chapterIndex = chapters.indexOfLast { position >= it.startMs }.coerceAtLeast(0)
        PlayerScreenState(
            book = book,
            chapters = chapters,
            playback = playback,
            settings = settings,
            isActive = isActive,
            positionMs = position,
            durationMs = duration,
            currentChapterIndex = chapterIndex,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerScreenState())

    private val book: BookEntity? get() = state.value.book

    fun playPause() {
        val s = state.value
        val b = s.book ?: return
        if (s.isActive) connection.togglePlayPause() else connection.playBook(b)
    }

    // True only when THIS book is the loaded item and is playing — so a position adjustment on a
    // not-yet-loaded book doesn't auto-start it (or interrupt a different book that's playing).
    private fun keepPlaying(s: PlayerScreenState) = s.isActive && s.playback.isPlaying

    fun rewind() = withBook { b ->
        val s = state.value
        connection.seekInBook(b, s.positionMs - s.settings.rewindSeconds * 1000L, play = keepPlaying(s))
    }

    fun fastForward() = withBook { b ->
        val s = state.value
        connection.seekInBook(b, s.positionMs + s.settings.forwardSeconds * 1000L, play = keepPlaying(s))
    }

    fun seekTo(positionMs: Long) = withBook { b ->
        connection.seekInBook(b, positionMs, play = keepPlaying(state.value))
    }

    fun nextChapter() = withBook { b ->
        val s = state.value
        val next = s.chapters.getOrNull(s.currentChapterIndex + 1) ?: return@withBook
        connection.seekInBook(b, next.startMs, play = keepPlaying(s))
    }

    fun previousChapter() = withBook { b ->
        val s = state.value
        val current = s.currentChapter ?: return@withBook
        // If we're more than 3s into the chapter, restart it; otherwise go to the previous one.
        val target = if (s.positionMs - current.startMs > 3_000L) {
            current.startMs
        } else {
            s.chapters.getOrNull(s.currentChapterIndex - 1)?.startMs ?: current.startMs
        }
        connection.seekInBook(b, target, play = keepPlaying(s))
    }

    fun jumpToChapter(chapter: ChapterEntity) = withBook { b ->
        // Tapping a chapter jumps there and starts playing (loads the book at that position if
        // it isn't loaded yet).
        connection.seekInBook(b, chapter.startMs, play = true)
    }

    fun setSpeed(speed: Float) = withBook { b ->
        connection.setSpeed(speed)
        viewModelScope.launch { library.setSpeed(b.id, speed) }
    }

    fun toggleSkipSilence() {
        val enabled = !state.value.playback.skipSilence
        connection.setSkipSilence(enabled)
        viewModelScope.launch { settingsRepo.setSkipSilence(enabled) }
    }

    fun setSleepTimer(durationMs: Long, endOfChapter: Boolean) =
        connection.setSleepTimer(durationMs, endOfChapter)

    fun cancelSleepTimer() = connection.cancelSleepTimer()

    fun addBookmark(note: String?) {
        val s = state.value
        val b = s.book ?: return
        viewModelScope.launch {
            library.addBookmark(
                bookId = b.id,
                positionMs = s.positionMs,
                chapterIndex = s.currentChapterIndex,
                chapterTitle = s.currentChapter?.title ?: b.title,
                note = note,
            )
        }
    }

    private inline fun withBook(action: (BookEntity) -> Unit) {
        book?.let(action)
    }

    companion object {
        fun factory(bookId: String) = viewModelFactory {
            initializer {
                PlayerViewModel(
                    ServiceLocator.libraryRepository,
                    ServiceLocator.settingsRepository,
                    ServiceLocator.playbackConnection,
                    bookId,
                )
            }
        }
    }
}
