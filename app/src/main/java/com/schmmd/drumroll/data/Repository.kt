package com.schmmd.drumroll.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class Repository(private val context: Context) {

    private val dao = DrumrollDatabase.get(context).readings()

    val readings: Flow<List<Reading>> = dao.observeAll()

    suspend fun previousFor(meterId: String, date: LocalDate): Reading? =
        dao.latestBefore(meterId, date.toString())

    suspend fun existingOn(date: LocalDate, meterId: String): Reading? =
        dao.onDate(date.toString(), meterId)

    /**
      * Saves locally, marked as not yet in the file. The export watches [readings] and
      * picks the change up on its own, so the save never waits on a file write.
      */
    suspend fun save(reading: Reading) {
        dao.upsert(reading.copy(sync = SyncState.QUEUED))
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun allOnce(): List<Reading> = dao.allOnce()

    suspend fun markAll(state: SyncState) = dao.markAll(state)
}
