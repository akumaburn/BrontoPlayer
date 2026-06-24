package com.aemake.brontoplayer.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Reads/writes [AppSettings] backed by Preferences DataStore. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val REWIND = intPreferencesKey("rewind_seconds")
        val FORWARD = intPreferencesKey("forward_seconds")
        val AUTO_REWIND = intPreferencesKey("auto_rewind_seconds")
        val SORT = stringPreferencesKey("sort_order")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
            defaultSpeed = prefs[Keys.DEFAULT_SPEED] ?: 1.0f,
            skipSilence = prefs[Keys.SKIP_SILENCE] ?: false,
            rewindSeconds = prefs[Keys.REWIND] ?: 10,
            forwardSeconds = prefs[Keys.FORWARD] ?: 30,
            autoRewindSeconds = prefs[Keys.AUTO_REWIND] ?: 5,
            sortOrder = prefs[Keys.SORT]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.RECENT,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setDefaultSpeed(speed: Float) = edit { it[Keys.DEFAULT_SPEED] = speed }
    suspend fun setSkipSilence(enabled: Boolean) = edit { it[Keys.SKIP_SILENCE] = enabled }
    suspend fun setRewindSeconds(seconds: Int) = edit { it[Keys.REWIND] = seconds }
    suspend fun setForwardSeconds(seconds: Int) = edit { it[Keys.FORWARD] = seconds }
    suspend fun setAutoRewindSeconds(seconds: Int) = edit { it[Keys.AUTO_REWIND] = seconds }
    suspend fun setSortOrder(order: SortOrder) = edit { it[Keys.SORT] = order.name }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
