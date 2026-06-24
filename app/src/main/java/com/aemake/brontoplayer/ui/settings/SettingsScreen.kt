package com.aemake.brontoplayer.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aemake.brontoplayer.BuildConfig
import com.aemake.brontoplayer.R
import com.aemake.brontoplayer.data.prefs.AppSettings
import com.aemake.brontoplayer.data.prefs.ThemeMode
import com.aemake.brontoplayer.ui.player.SpeedSheet
import com.aemake.brontoplayer.util.BackgroundPermissions
import com.aemake.brontoplayer.util.formatSpeed
import android.os.Build

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory()),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permission/exemption status, refreshed whenever the screen resumes.
    var refreshKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val hasNotifications = remember(refreshKey) { BackgroundPermissions.hasNotificationPermission(context) }
    val ignoringBattery = remember(refreshKey) { BackgroundPermissions.isIgnoringBatteryOptimizations(context) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshKey++ }
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshKey++ }

    var showSpeedSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
          Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // ---- Background playback ----
            SectionHeader(stringResource(R.string.settings_background))
            PermissionRow(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.perm_notifications_title),
                summary = stringResource(R.string.perm_notifications_summary),
                granted = hasNotifications,
                grantedLabel = stringResource(R.string.perm_granted),
                actionLabel = stringResource(R.string.perm_notifications_action),
                onAction = {
                    if (BackgroundPermissions.needsNotificationPermission()) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
            PermissionRow(
                icon = Icons.Filled.BatteryChargingFull,
                title = stringResource(R.string.perm_battery_title),
                summary = stringResource(R.string.perm_battery_summary),
                granted = ignoringBattery,
                grantedLabel = stringResource(R.string.perm_battery_done),
                actionLabel = stringResource(R.string.perm_battery_action),
                onAction = {
                    runCatching {
                        batteryLauncher.launch(BackgroundPermissions.requestIgnoreBatteryOptimizationsIntent(context))
                    }.onFailure {
                        batteryLauncher.launch(BackgroundPermissions.batteryOptimizationSettingsIntent())
                    }
                },
            )

            HorizontalDivider()

            // ---- Playback ----
            SectionHeader(stringResource(R.string.settings_playback))
            ListItem(
                modifier = Modifier.clickable { showSpeedSheet = true },
                headlineContent = { Text(stringResource(R.string.setting_default_speed)) },
                trailingContent = { Text(formatSpeed(settings.defaultSpeed), style = MaterialTheme.typography.titleMedium) },
            )
            SwitchRow(
                title = stringResource(R.string.setting_skip_silence),
                summary = stringResource(R.string.setting_skip_silence_summary),
                checked = settings.skipSilence,
                onCheckedChange = viewModel::setSkipSilence,
            )
            SecondsRow(
                title = stringResource(R.string.setting_rewind_seconds),
                current = settings.rewindSeconds,
                options = AppSettings.SEEK_OPTIONS,
                onSelect = viewModel::setRewindSeconds,
            )
            SecondsRow(
                title = stringResource(R.string.setting_forward_seconds),
                current = settings.forwardSeconds,
                options = AppSettings.SEEK_OPTIONS,
                onSelect = viewModel::setForwardSeconds,
            )
            SecondsRow(
                title = stringResource(R.string.setting_auto_rewind),
                summary = stringResource(R.string.setting_auto_rewind_summary),
                current = settings.autoRewindSeconds,
                options = listOf(0, 2, 3, 5, 10, 15),
                onSelect = viewModel::setAutoRewindSeconds,
            )

            HorizontalDivider()

            // ---- Appearance ----
            SectionHeader(stringResource(R.string.settings_appearance))
            ThemeRow(current = settings.themeMode, onSelect = viewModel::setThemeMode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(
                    title = stringResource(R.string.setting_dynamic_color),
                    summary = stringResource(R.string.setting_dynamic_color_summary),
                    checked = settings.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }

            HorizontalDivider()

            // ---- About ----
            SectionHeader(stringResource(R.string.settings_about))
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenAbout),
                leadingContent = { Icon(Icons.Filled.Favorite, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.about_title)) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.settings_about_summary,
                            BuildConfig.VERSION_NAME,
                        ),
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
            )
          }
        }
    }

    if (showSpeedSheet) {
        SpeedSheet(
            current = settings.defaultSpeed,
            onSelect = { viewModel.setDefaultSpeed(it) },
            onDismiss = { showSpeedSheet = false },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    granted: Boolean,
    grantedLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = {
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = grantedLabel, tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SecondsRow(
    title: String,
    summary: String? = null,
    current: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        summary?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { seconds ->
                FilterChip(
                    selected = seconds == current,
                    onClick = { onSelect(seconds) },
                    label = { Text(if (seconds == 0) stringResource(R.string.sleep_off) else "${seconds}s") },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeRow(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.setting_theme), style = MaterialTheme.typography.bodyLarge)
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val items = listOf(
                ThemeMode.SYSTEM to R.string.theme_system,
                ThemeMode.LIGHT to R.string.theme_light,
                ThemeMode.DARK to R.string.theme_dark,
            )
            items.forEach { (mode, label) ->
                FilterChip(
                    selected = mode == current,
                    onClick = { onSelect(mode) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
}
