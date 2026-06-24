package com.aemake.brontoplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aemake.brontoplayer.R
import androidx.compose.ui.res.stringResource
import com.aemake.brontoplayer.data.prefs.AppSettings
import com.aemake.brontoplayer.util.formatRemaining
import com.aemake.brontoplayer.util.formatSpeed

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeedSheet(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                stringResource(R.string.playback_speed),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                formatSpeed(current),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.SPEED_OPTIONS.forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(speed - current) < 0.001f,
                        onClick = { onSelect(speed) },
                        label = { Text(formatSpeed(speed)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    active: Boolean,
    remainingMs: Long,
    onPick: (durationMs: Long, endOfChapter: Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val minuteOptions = listOf(5, 10, 15, 30, 45, 60)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                stringResource(R.string.sleep_timer),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (active) {
                Text(
                    stringResource(R.string.sleep_timer_active, formatRemaining(remainingMs)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                AssistChip(
                    onClick = onCancel,
                    label = { Text(stringResource(R.string.sleep_off)) },
                    leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.sleep_end_of_chapter)) },
                leadingContent = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable { onPick(0L, true) },
            )
            minuteOptions.forEach { minutes ->
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sleep_minutes, minutes)) },
                    leadingContent = { Icon(Icons.Filled.Bedtime, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { onPick(minutes * 60_000L, false) },
                )
            }
        }
    }
}
