package com.aemake.brontoplayer.ui.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aemake.brontoplayer.data.db.BookEntity
import com.aemake.brontoplayer.data.db.BookmarkEntity
import com.aemake.brontoplayer.data.repository.LibraryRepository
import com.aemake.brontoplayer.di.ServiceLocator
import com.aemake.brontoplayer.playback.PlaybackConnection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookmarksUiState(
    val book: BookEntity? = null,
    val bookmarks: List<BookmarkEntity> = emptyList(),
)

class BookmarksViewModel(
    private val library: LibraryRepository,
    private val connection: PlaybackConnection,
    bookId: String,
) : ViewModel() {

    val state: StateFlow<BookmarksUiState> = combine(
        library.observeBook(bookId),
        library.observeBookmarks(bookId),
    ) { book, bookmarks ->
        BookmarksUiState(book, bookmarks)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookmarksUiState())

    fun jumpTo(bookmark: BookmarkEntity) {
        val book = state.value.book ?: return
        connection.seekInBook(book, bookmark.positionMs, play = true)
    }

    fun delete(bookmark: BookmarkEntity) {
        viewModelScope.launch { library.deleteBookmark(bookmark.id) }
    }

    fun updateNote(bookmark: BookmarkEntity, note: String?) {
        viewModelScope.launch { library.updateBookmarkNote(bookmark.id, note) }
    }

    companion object {
        fun factory(bookId: String) = viewModelFactory {
            initializer {
                BookmarksViewModel(
                    ServiceLocator.libraryRepository,
                    ServiceLocator.playbackConnection,
                    bookId,
                )
            }
        }
    }
}
