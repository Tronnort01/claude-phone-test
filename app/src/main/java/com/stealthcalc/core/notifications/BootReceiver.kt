package com.stealthcalc.core.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.stealthcalc.recorder.service.RecordingRecovery
import dagger.hilt.EntryPoints

/**
 * Receiver for BOOT_COMPLETED. Round 4 Feature C uses this to catch
 * the one scenario Application.onCreate recovery can't: the user
 * reboots the device mid-recording and then never re-opens the
 * calculator before going to bed — a plaintext recording would sit in
 * filesDir/recordings until someone launched the app. By running
 * recovery on BOOT_COMPLETED, we encrypt + vault it as soon as the
 * device comes back up.
 *
 * Hilt doesn't field-inject BroadcastReceivers by default, so we reach
 * RecordingRecovery via the EntryPoints accessor exposed on the class.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            EntryPoints.get(context.applicationContext, RecordingRecovery.Accessor::class.java)
                .recordingRecovery()
                .scanAndRecover()
        }
    }
}
