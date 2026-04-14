package com.stealthcalc

import android.app.Application
import com.stealthcalc.core.logging.AppLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StealthCalcApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
    }
}
