package com.aemake.brontoplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aemake.brontoplayer.data.prefs.AppSettings
import com.aemake.brontoplayer.data.prefs.SettingsRepository
import com.aemake.brontoplayer.data.prefs.ThemeMode
import com.aemake.brontoplayer.di.ServiceLocator
import com.aemake.brontoplayer.playback.PlaybackConnection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val connection: PlaybackConnection,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setThemeMode(mode: ThemeMode) = launch { settingsRepo.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = launch { settingsRepo.setDynamicColor(enabled) }
    fun setDefaultSpeed(speed: Float) = launch { settingsRepo.setDefaultSpeed(speed) }

    fun setSkipSilence(enabled: Boolean) {
        connection.setSkipSilence(enabled)
        launch { settingsRepo.setSkipSilence(enabled) }
    }

    fun setRewindSeconds(seconds: Int) = launch { settingsRepo.setRewindSeconds(seconds) }
    fun setForwardSeconds(seconds: Int) = launch { settingsRepo.setForwardSeconds(seconds) }
    fun setAutoRewindSeconds(seconds: Int) = launch { settingsRepo.setAutoRewindSeconds(seconds) }

    private inline fun launch(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                SettingsViewModel(
                    ServiceLocator.settingsRepository,
                    ServiceLocator.playbackConnection,
                )
            }
        }
    }
}
