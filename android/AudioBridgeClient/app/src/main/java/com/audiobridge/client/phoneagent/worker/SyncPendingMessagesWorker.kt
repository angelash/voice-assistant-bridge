package com.audiobridge.client.phoneagent.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.audiobridge.client.phoneagent.data.repository.MessageRepository
import com.audiobridge.client.phoneagent.data.settings.SettingsRepository
import java.util.concurrent.TimeUnit

class SyncPendingMessagesWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val outcome = MessageRepository.get(applicationContext).syncDuePending()
        return when {
            outcome.attempted == 0 && outcome.pendingCount > 0 -> Result.retry()
            outcome.attempted == 0 -> Result.success()
            outcome.failed > 0 -> Result.retry()
            else -> Result.success()
        }
    }
}

object PhoneAgentSyncScheduler {
    private const val UNIQUE_WORK_NAME = "phone-agent-sync-pending"

    fun enqueue(context: Context, initialDelayMs: Long = 0L) {
        val settings = SettingsRepository.get(context).load()
        val networkType = if (settings.allowMobileNetworkSync) {
            NetworkType.CONNECTED
        } else {
            NetworkType.UNMETERED
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncPendingMessagesWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .apply {
                if (initialDelayMs > 0L) {
                    setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                }
            }
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
