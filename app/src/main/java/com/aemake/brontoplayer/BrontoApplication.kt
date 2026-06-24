package com.aemake.brontoplayer

import android.app.Application
import com.aemake.brontoplayer.di.ServiceLocator

class BrontoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
