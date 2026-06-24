package com.aemake.brontoplayer.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aemake.brontoplayer.R
import com.aemake.brontoplayer.data.db.BookEntity
import com.aemake.brontoplayer.ui.chapters.ChaptersSheet
import com.aemake.brontoplayer.ui.components.CoverImage
import com.aemake.brontoplayer.util.formatDuration
import com.aemake.brontoplayer.util.formatRemaining
import com.aemake.brontoplayer.util.formatSpeed
import kotlinx.coroutines.launch

/** Width breakpoint past which (in landscape) the player switches to a side-by-side layout. */
private val TWO_PANE_MIN_WIDTH = 600.dp

/** Caps the controls column width so it doesn't stretch across wide screens / tablets. */
private val CONTENT_MAX_WIDTH = 480.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    bookId: String,
    onBack: () -> Unit,
    onOpenBookmarks: () -> Unit,
    viewModel: PlayerViewModel = viewModel(
        key = "player_$bookId",
        factory = PlayerViewModel.factory(bookId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val bookmarkAddedText = stringResource(R.string.bookmark_added)

    // Surface playback failures (e.g. unsupported/corrupt file) instead of failing silently.
    LaunchedEffect(state.playback.error) {
        state.playback.error?.let { snackbarHostState.showSnackbar(it) }
    }

    var showChapters by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }

    val book = state.book
    val currentSpeed = if (state.isActive) state.playback.speed else (book?.speed ?: 1f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.now_playing)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bookmarks)) },
                                leadingIcon = { Icon(Icons.Filled.Bookmarks, contentDescription = null) },
                                onClick = { overflowOpen = false; onOpenBookmarks() },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.setting_skip_silence) +
                                            if (state.playback.skipSilence) "  ✓" else "",
                                    )
                                },
                                onClick = { overflowOpen = false; viewModel.toggleSkipSilence() },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val currentBook = book
        if (currentBook == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.now_playing))
            }
            return@Scaffold
        }

        val scheme = MaterialTheme.colorScheme
        val backgroundBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to lerp(scheme.primaryContainer, scheme.surface, 0.25f),
                0.55f to scheme.surface,
                1f to scheme.surface,
            ),
        )

        val controls: @Composable (Modifier) -> Unit = { m ->
            PlayerControls(
                book = currentBook,
                state = state,
                viewModel = viewModel,
                currentSpeed = currentSpeed,
                onShowChapters = { showChapters = true },
                onShowSpeed = { showSpeed = true },
                onShowSleep = { showSleep = true },
                onAddBookmark = {
                    viewModel.addBookmark(null)
                    scope.launch { snackbarHostState.showSnackbar(bookmarkAddedText) }
                },
                onOpenBookmarks = onOpenBookmarks,
                modifier = m,
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
                .padding(padding),
        ) {
            val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH && maxWidth > maxHeight
            if (twoPane) {
                // Landscape / wide screens: cover beside the controls.
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerCover(
                        coverPath = currentBook.coverPath,
                        title = currentBook.title,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(vertical = 16.dp),
                    )
                    Spacer(Modifier.width(28.dp))
                    controls(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .widthIn(max = CONTENT_MAX_WIDTH)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            } else {
                // Portrait (phones & tablets): cover flexes above width-capped controls.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PlayerCover(
                        coverPath = currentBook.coverPath,
                        title = currentBook.title,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    controls(
                        Modifier
                            .widthIn(max = CONTENT_MAX_WIDTH)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showChapters) {
        ChaptersSheet(
            chapters = state.chapters,
            currentIndex = state.currentChapterIndex,
            onSelect = { viewModel.jumpToChapter(it); showChapters = false },
            onDismiss = { showChapters = false },
        )
    }
    if (showSpeed) {
        SpeedSheet(
            current = currentSpeed,
            onSelect = { viewModel.setSpeed(it) },
            onDismiss = { showSpeed = false },
        )
    }
    if (showSleep) {
        SleepTimerSheet(
            active = state.playback.sleepActive,
            remainingMs = state.playback.sleepRemainingMs,
            onPick = { durationMs, endOfChapter ->
                viewModel.setSleepTimer(durationMs, endOfChapter)
                showSleep = false
            },
            onCancel = { viewModel.cancelSleepTimer(); showSleep = false },
            onDismiss = { showSleep = false },
        )
    }
}

/** The cover art, sized to the largest square that fits in [modifier]'s constraints. */
@Composable
private fun PlayerCover(coverPath: String?, title: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight, 380.dp)
        CoverImage(
            coverPath = coverPath,
            title = title,
            modifier = Modifier
                .size(side)
                .shadow(20.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp)),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerControls(
    book: BookEntity,
    state: PlayerScreenState,
    viewModel: PlayerViewModel,
    currentSpeed: Float,
    onShowChapters: () -> Unit,
    onShowSpeed: () -> Unit,
    onShowSleep: () -> Unit,
    onAddBookmark: () -> Unit,
    onOpenBookmarks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    // The scrubber is scoped to the CURRENT CHAPTER, not the whole book.
    val chapter = state.currentChapter
    val chapterStart = chapter?.startMs ?: 0L
    val chapterEnd = (chapter?.endMs ?: state.durationMs).coerceAtLeast(chapterStart)
    val chapterLength = (chapterEnd - chapterStart).coerceAtLeast(0L)
    val positionInChapter = (state.positionMs - chapterStart).coerceIn(0L, chapterLength)
    val liveFraction = if (chapterLength > 0L) positionInChapter.toFloat() / chapterLength else 0f
    val sliderFraction = if (scrubbing) scrubFraction else liveFraction
    val shownInChapter = if (scrubbing) (scrubFraction * chapterLength).toLong() else positionInChapter
    val isPlaying = state.isActive && state.playback.isPlaying
    val isBuffering = state.isActive && state.playback.isBuffering

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        // Title scrolls (marquee) when too long, keeping a fixed single-line height.
        Text(
            text = book.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().basicMarquee(),
        )
        book.author?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().basicMarquee(),
            )
        }

        Spacer(Modifier.height(20.dp))
        // Tappable chapter chip
        Surface(
            onClick = onShowChapters,
            shape = RoundedCornerShape(percent = 50),
            color = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.currentChapter?.title ?: stringResource(R.string.chapters),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    if (state.chapters.isNotEmpty()) {
                        Text(
                            text = "Chapter ${state.currentChapterIndex + 1} of ${state.chapters.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSecondaryContainer.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        // Per-chapter scrubber: position & remaining time are within the current chapter.
        Slider(
            value = sliderFraction,
            onValueChange = { scrubbing = true; scrubFraction = it },
            onValueChangeFinished = {
                viewModel.seekTo(chapterStart + (scrubFraction * chapterLength).toLong())
                scrubbing = false
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatDuration(shownInChapter),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Text(
                "-" + formatDuration(chapterLength - shownInChapter),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(14.dp))
        // Transport controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::previousChapter, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.previous_chapter),
                    modifier = Modifier.size(34.dp),
                )
            }
            SeekButton(
                icon = Icons.Filled.FastRewind,
                seconds = state.settings.rewindSeconds,
                contentDescription = stringResource(R.string.rewind),
                onClick = viewModel::rewind,
            )
            FilledIconButton(
                onClick = viewModel::playPause,
                modifier = Modifier.size(84.dp),
                shape = CircleShape,
            ) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = LocalContentColor.current,
                        strokeWidth = 3.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            SeekButton(
                icon = Icons.Filled.FastForward,
                seconds = state.settings.forwardSeconds,
                contentDescription = stringResource(R.string.fast_forward),
                onClick = viewModel::fastForward,
            )
            IconButton(onClick = viewModel::nextChapter, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.next_chapter),
                    modifier = Modifier.size(34.dp),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        // Secondary actions grouped in a soft surface
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = scheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                LabeledAction(
                    icon = Icons.Filled.Speed,
                    label = formatSpeed(currentSpeed),
                    contentDescription = stringResource(R.string.playback_speed),
                    onClick = onShowSpeed,
                )
                LabeledAction(
                    icon = Icons.Filled.Bedtime,
                    label = if (state.playback.sleepActive) {
                        if (state.playback.sleepEndOfChapter) stringResource(R.string.sleep_end_of_chapter)
                        else formatRemaining(state.playback.sleepRemainingMs)
                    } else {
                        stringResource(R.string.sleep_timer)
                    },
                    contentDescription = stringResource(R.string.sleep_timer),
                    onClick = onShowSleep,
                )
                LabeledAction(
                    icon = Icons.Filled.BookmarkAdd,
                    label = stringResource(R.string.add_bookmark),
                    contentDescription = stringResource(R.string.add_bookmark),
                    onClick = onAddBookmark,
                )
                LabeledAction(
                    icon = Icons.Filled.Bookmarks,
                    label = stringResource(R.string.bookmarks),
                    contentDescription = stringResource(R.string.bookmarks),
                    onClick = onOpenBookmarks,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SeekButton(
    icon: ImageVector,
    seconds: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(58.dp)) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "${seconds}s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabeledAction(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(max = 88.dp),
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
