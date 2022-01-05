package com.sample.barcode

import android.app.Application
import timber.log.Timber

class BaseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }
}