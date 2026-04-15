package com.stealthcalc

import android.app.Application
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.recorder.service.RecordingRecovery
import dagger.hilt.EntryPoints
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StealthCalcApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        // Round 4 Feature C: on every process start, scan for orphan
        // recordings that were left behind by a service reap / crash /
        // device reboot. Hilt is ready by the time onCreate is past
        // super — we reach the singleton via EntryPoints because the
        // Application class itself is not field-injectable.
        // scanAndRecover is cheap (lists one directory) and non-blocking
        // (dispatches its work on Dispatchers.IO), so it's safe on the
        // main thread here.
        runCatching {
            EntryPoints.get(this, RecordingRecovery.Accessor::class.java)
                .recordingRecovery()
                .scanAndRecover()
        }
    }
}
