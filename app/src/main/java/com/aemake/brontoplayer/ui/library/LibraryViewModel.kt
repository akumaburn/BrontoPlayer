package com.aemake.brontoplayer.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aemake.brontoplayer.data.db.BookEntity
import com.aemake.brontoplayer.data.prefs.SettingsRepository
import com.aemake.brontoplayer.data.prefs.SortOrder
import com.aemake.brontoplayer.data.repository.ImportResult
import com.aemake.brontoplayer.data.repository.LibraryRepository
import com.aemake.brontoplayer.di.ServiceLocator
import com.aemake.brontoplayer.playback.PlaybackConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val sortOrder: SortOrder = SortOrder.RECENT,
    val isImporting: Boolean = false,
    val activeBookId: String? = null,
    val message: String? = null,
)

class LibraryViewModel(
    private val library: LibraryRepository,
    private val settingsRepo: SettingsRepository,
    private val connection: PlaybackConnection,
) : ViewModel() {

    private val importing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<LibraryUiState> = combine(
        library.observeBooks(),
        settingsRepo.settings,
        connection.state,
        importing,
        message,
    ) { books, settings, playback, isImporting, msg ->
        LibraryUiState(
            books = sortBooks(books, settings.sortOrder),
            sortOrder = settings.sortOrder,
            isImporting = isImporting,
            activeBookId = playback.bookId,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private fun sortBooks(books: List<BookEntity>, order: SortOrder): List<BookEntity> = when (order) {
        SortOrder.RECENT -> books.sortedWith(compareByDescending<BookEntity> { it.lastPlayedAt }.thenByDescending { it.addedAt })
        SortOrder.ADDED -> books.sortedByDescending { it.addedAt }
        SortOrder.TITLE -> books.sortedBy { it.title.lowercase() }
        SortOrder.AUTHOR -> books.sortedBy { (it.author ?: "￿").lowercase() }
        SortOrder.PROGRESS -> books.sortedByDescending { it.progress }
    }

    fun importDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            importing.value = true
            val result = library.importDocuments(uris)
            importing.value = false
            message.value = summarize(result)
        }
    }

    fun importFolder(uri: Uri) {
        viewModelScope.launch {
            importing.value = true
            val result = library.importFolder(uri)
            importing.value = false
            message.value = summarize(result)
        }
    }

    private fun summarize(result: ImportResult): String {
        val parts = mutableListOf<String>()
        if (result.imported > 0) parts += "${result.imported} added"
        if (result.skipped > 0) parts += "${result.skipped} already in library"
        if (result.failures.isNotEmpty()) parts += "${result.failures.size} failed"
        return if (parts.isEmpty()) "No audiobooks found" else parts.joinToString(", ")
    }

    fun setSortOrder(order: SortOrder) {
        viewModelScope.launch { settingsRepo.setSortOrder(order) }
    }

    fun removeBook(book: BookEntity) {
        viewModelScope.launch { library.removeBook(book) }
    }

    fun toggleFinished(book: BookEntity) {
        viewModelScope.launch { library.setFinished(book.id, !book.finished) }
    }

    fun play(book: BookEntity) = connection.playBook(book)

    fun consumeMessage() {
        message.value = null
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                LibraryViewModel(
                    ServiceLocator.libraryRepository,
                    ServiceLocator.settingsRepository,
                    ServiceLocator.playbackConnection,
                )
            }
        }
    }
}
