package com.aemake.brontoplayer

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aemake.brontoplayer.data.prefs.AppSettings
import com.aemake.brontoplayer.di.ServiceLocator
import com.aemake.brontoplayer.ui.BrontoApp
import com.aemake.brontoplayer.ui.theme.BrontoTheme
import com.aemake.brontoplayer.util.BackgroundPermissions

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* status read on demand */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by ServiceLocator.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            BrontoTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    BrontoApp()
                }
            }
        }
        maybeRequestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        ServiceLocator.playbackConnection.connect()
    }

    override fun onStop() {
        super.onStop()
        ServiceLocator.playbackConnection.release()
    }

    private fun maybeRequestNotificationPermission() {
        if (BackgroundPermissions.needsNotificationPermission() &&
            !BackgroundPermissions.hasNotificationPermission(this)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
