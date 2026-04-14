package com.stealthcalc.core.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receiver for BOOT_COMPLETED so the app can re-arm any pending state
 * (auto-lock timers, WorkManager-backed periodic tasks, etc.) after the
 * device restarts. Kept minimal for now — Hilt/WorkManager-driven jobs
 * are automatically rescheduled by the system when they're enqueued with
 * a unique work name, so there's nothing to actively re-schedule here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Nothing to do right now. Placeholder for future re-scheduling.
    }
}
