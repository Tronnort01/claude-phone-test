package com.stealthagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.stealthagent.data.AgentRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: AgentRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!repository.isSetupDone || !repository.isPaired) return
        AgentForegroundService.start(context)
    }
}
