package com.schmmd.drumroll.data

import android.content.Context
import com.schmmd.drumroll.sync.SyncWorker
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class Repository(private val context: Context) {

    private val dao = DrumrollDatabase.get(context).readings()

    val readings: Flow<List<Reading>> = dao.observeAll()

    suspend fun previousFor(meterId: String, date: LocalDate): Reading? =
        dao.latestBefore(meterId, date.toString())

    suspend fun existingOn(date: LocalDate, meterId: String): Reading? =
        dao.onDate(date.toString(), meterId)

    /** Saves locally and queues the sheet write. The save never waits on the network. */
    suspend fun save(reading: Reading) {
        dao.upsert(reading.copy(sync = SyncState.QUEUED))
        SyncWorker.enqueue(context)
    }

    suspend fun delete(id: String) = dao.delete(id)

    fun retrySync() = SyncWorker.enqueue(context)
}
