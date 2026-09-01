package com.schmmd.drumroll.data

import android.content.Context
import com.schmmd.drumroll.backup.ParsedReading
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.UUID

class Repository(private val context: Context) {

    private val dao = DrumrollDatabase.get(context).readings()

    val readings: Flow<List<Reading>> = dao.observeAll()

    /**
      * The reading a new one is measured against. [excluding] drops the row being
      * edited, which would otherwise be its own predecessor and compare to itself.
      */
    suspend fun previousFor(meterId: String, date: LocalDate, excluding: String? = null): Reading? =
        if (excluding == null) dao.latestBefore(meterId, date.toString())
        else dao.latestBeforeExcluding(meterId, date.toString(), excluding)

    suspend fun byId(id: String): Reading? = dao.byId(id)

    /**
      * Saves locally, marked as not yet in the file. The export watches [readings] and
      * picks the change up on its own, so the save never waits on a file write.
      */
    suspend fun save(reading: Reading) {
        dao.upsert(reading.copy(sync = SyncState.QUEUED))
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun allOnce(): List<Reading> = dao.allOnce()

    /**
      * Adds rows read from a spreadsheet, skipping any already held.
      *
      * The export carries no id — nothing downstream needed one — so identity here is
      * the meter plus the capture timestamp, which is exactly what distinguishes two
      * readings taken on the same day. Importing the same file twice is therefore safe,
      * and importing a file that overlaps the log adds only what is missing.
      *
      * @return how many rows were new.
      */
    suspend fun importReadings(parsed: List<ParsedReading>): Int {
        val known = dao.allOnce().map { it.meterId to it.capturedAtUtc }.toHashSet()
        var added = 0
        for (row in parsed) {
            if (!known.add(row.meterId to row.capturedAtUtc)) continue
            dao.upsert(
                Reading(
                    id = UUID.randomUUID().toString(),
                    readingDate = row.readingDate,
                    capturedAtUtc = row.capturedAtUtc,
                    value = row.value,
                    meterId = row.meterId,
                    ocrRaw = "Imported from a spreadsheet.",
                    minAgreement = null,
                    corrected = row.corrected,
                    // The name is kept even though the file is not here: it still ties
                    // the row to its frame if the photos are ever restored alongside.
                    photoPath = row.photoName,
                    note = row.note,
                    // It came out of the file, so as far as the file is concerned it is
                    // already there. A later export rewrites the whole thing regardless.
                    sync = SyncState.SYNCED,
                )
            )
            added++
        }
        return added
    }

    suspend fun markAll(state: SyncState) = dao.markAll(state)
}
