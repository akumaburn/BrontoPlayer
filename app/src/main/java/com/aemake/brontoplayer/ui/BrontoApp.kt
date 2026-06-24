package com.aemake.brontoplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aemake.brontoplayer.data.db.BookEntity
import com.aemake.brontoplayer.di.ServiceLocator
import com.aemake.brontoplayer.ui.about.AboutScreen
import com.aemake.brontoplayer.ui.bookmarks.BookmarksScreen
import com.aemake.brontoplayer.ui.library.LibraryScreen
import com.aemake.brontoplayer.ui.player.MiniPlayer
import com.aemake.brontoplayer.ui.player.PlayerScreen
import com.aemake.brontoplayer.ui.settings.SettingsScreen

private sealed interface Route {
    data object Library : Route
    data class Player(val bookId: String) : Route
    data class Bookmarks(val bookId: String) : Route
    data object Settings : Route
    data object About : Route
}

@Composable
fun BrontoApp() {
    val backStack = remember { mutableStateListOf<Route>(Route.Library) }
    val current = backStack.last()
    fun push(route: Route) { backStack.add(route) }
    fun pop() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    val connection = ServiceLocator.playbackConnection
    val playback by connection.state.collectAsStateWithLifecycle()

    val activeBook by produceState<BookEntity?>(initialValue = null, playback.bookId) {
        val id = playback.bookId
        value = null
        if (id != null) {
            ServiceLocator.libraryRepository.observeBook(id).collect { value = it }
        }
    }

    val showMiniPlayer = activeBook != null && current !is Route.Player
    val bottomInset = if (showMiniPlayer) 76.dp else 0.dp

    BackHandler(enabled = backStack.size > 1) { pop() }

    Box(Modifier.fillMaxSize()) {
        when (val route = current) {
            Route.Library -> LibraryScreen(
                onOpenBook = { push(Route.Player(it.id)) },
                onOpenSettings = { push(Route.Settings) },
                bottomContentPadding = bottomInset,
            )
            is Route.Player -> PlayerScreen(
                bookId = route.bookId,
                onBack = { pop() },
                onOpenBookmarks = { push(Route.Bookmarks(route.bookId)) },
            )
            is Route.Bookmarks -> BookmarksScreen(
                bookId = route.bookId,
                onBack = { pop() },
            )
            Route.Settings -> SettingsScreen(
                onBack = { pop() },
                onOpenAbout = { push(Route.About) },
            )
            Route.About -> AboutScreen(onBack = { pop() })
        }

        val book = activeBook
        if (showMiniPlayer && book != null) {
            MiniPlayer(
                book = book,
                isPlaying = playback.isPlaying,
                isBuffering = playback.isBuffering,
                positionMs = playback.positionMs,
                durationMs = book.durationMs.takeIf { it > 0 } ?: playback.durationMs,
                onClick = { push(Route.Player(book.id)) },
                onPlayPause = { connection.togglePlayPause() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }
    }
}
