package com.schmitztech.metermaid.data

import android.content.Context
import com.schmitztech.metermaid.backup.ParsedReading
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.UUID

class Repository(private val context: Context) {

    private val dao = MeterMaidDatabase.get(context).readings()

    val readings: Flow<List<Reading>> = dao.observeAll()

    /**
      * The reading a new one is measured against. [excluding] drops the row being
      * edited, which would otherwise be its own predecessor and compare to itself.
      */
    suspend fun previousFor(meterId: String, date: LocalDate, excluding: String? = null): Reading? =
        dao.latestBefore(meterId, date.toString(), excluding)

    suspend fun byId(id: String): Reading? = dao.byId(id)

    /**
      * Saves locally. The export watches [readings] and picks the change up on its
      * own, so the save never waits on a file write.
      */
    suspend fun save(reading: Reading) {
        dao.upsert(reading)
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun allOnce(): List<Reading> = dao.allOnce()

    /**
      * Adds rows read from a spreadsheet, skipping any already held.
      *
      * The export carries no id — nothing downstream needed one — so identity here is
      * the meter, the reading date, and the value. The capture timestamp is deliberately
      * left out: it does not survive the CSV round-trip intact (the column drops
      * milliseconds and is written as zoneless local time), so keying on it re-imported
      * a log's own export as all-new rows. Date and value round-trip exactly, and a row
      * that repeats all three adds nothing worth keeping. Importing the same file twice
      * is therefore safe, and importing a file that overlaps the log adds only what is
      * missing.
      *
      * @return how many rows were new.
      */
    suspend fun importReadings(parsed: List<ParsedReading>): Int {
        val known = dao.allOnce().map { Triple(it.meterId, it.readingDate, it.value) }.toHashSet()
        var added = 0
        for (row in parsed) {
            if (!known.add(Triple(row.meterId, row.readingDate, row.value))) continue
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
                )
            )
            added++
        }
        return added
    }
}
