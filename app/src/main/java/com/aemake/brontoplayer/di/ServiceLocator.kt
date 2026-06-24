package com.aemake.brontoplayer.di

import android.content.Context
import com.aemake.brontoplayer.billing.BillingManager
import com.aemake.brontoplayer.data.prefs.SettingsRepository
import com.aemake.brontoplayer.data.repository.LibraryRepository
import com.aemake.brontoplayer.playback.PlaybackConnection

/**
 * Minimal manual dependency container. Initialized once from [com.aemake.brontoplayer.BrontoApplication];
 * dependencies are created lazily and shared process-wide.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val libraryRepository: LibraryRepository by lazy { LibraryRepository(appContext) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val playbackConnection: PlaybackConnection by lazy { PlaybackConnection(appContext) }
    val billingManager: BillingManager by lazy { BillingManager(appContext) }
}
