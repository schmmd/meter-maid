package com.schmmd.drumroll.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.schmmd.drumroll.data.DrumrollDatabase
import com.schmmd.drumroll.data.SettingsStore
import com.schmmd.drumroll.data.SyncState
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Drains the queue of unsynced readings. Runs only when there's a connection, and
 * WorkManager's own backoff handles the flaky-signal case — no retry code here.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = DrumrollDatabase.get(applicationContext).readings()
        val settings = SettingsStore(applicationContext).settings.first()
        if (settings.endpoint.isBlank()) return Result.success()

        val client = SheetsClient(settings.endpoint, settings.secret)
        var retryNeeded = false

        for (reading in dao.pending()) {
            dao.update(reading.copy(sync = SyncState.SYNCING))
            when (val result = client.post(reading)) {
                is PostResult.Ok ->
                    dao.update(reading.copy(sync = SyncState.SYNCED, syncAttempts = reading.syncAttempts + 1))

                is PostResult.Retry -> {
                    dao.update(reading.copy(sync = SyncState.QUEUED, syncAttempts = reading.syncAttempts + 1))
                    retryNeeded = true
                }

                is PostResult.Permanent ->
                    dao.update(reading.copy(sync = SyncState.FAILED, syncAttempts = reading.syncAttempts + 1))
            }
        }

        return if (retryNeeded) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "drumroll-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
