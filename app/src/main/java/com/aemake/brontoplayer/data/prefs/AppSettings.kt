package com.aemake.brontoplayer.data.prefs

/** Theme preference. SYSTEM follows the device's light/dark setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Library sort orders. */
enum class SortOrder { RECENT, ADDED, TITLE, AUTHOR, PROGRESS }

/** All user-configurable settings, with sensible audiobook-friendly defaults. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // Default off so the warm brand palette is used; users can opt into wallpaper-based
    // dynamic color from Settings.
    val dynamicColor: Boolean = false,
    val defaultSpeed: Float = 1.0f,
    val skipSilence: Boolean = false,
    val rewindSeconds: Int = 10,
    val forwardSeconds: Int = 30,
    val autoRewindSeconds: Int = 5,
    val sortOrder: SortOrder = SortOrder.RECENT,
) {
    companion object {
        val SPEED_OPTIONS = listOf(0.5f, 0.75f, 0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
        val SEEK_OPTIONS = listOf(5, 10, 15, 20, 30, 45, 60, 90)
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
    }
}
