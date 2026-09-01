package com.schmmd.drumroll

import com.schmmd.drumroll.backup.ReadingsCsv
import com.schmmd.drumroll.data.Reading
import com.schmmd.drumroll.data.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The derived columns used to be ARRAYFORMULAs in the spreadsheet, where they were the
 * sheet's problem. They are the app's problem now, so they are pinned here.
 */
class ReadingsCsvTest {

    private val utc = ZoneId.of("UTC")

    private fun reading(
        id: String,
        date: String,
        value: Long,
        at: String = "${date}T12:00:00Z",
        meter: String = "rockwell",
        note: String = "",
    ) = Reading(
        id = id,
        readingDate = date,
        capturedAtUtc = Instant.parse(at).toEpochMilli(),
        value = value,
        meterId = meter,
        ocrRaw = null,
        minAgreement = null,
        corrected = false,
        photoPath = "",
        note = note,
        sync = SyncState.QUEUED,
    )

    private fun rows(csv: String) = csv.trim().lines().map { it.split(",") }

    @Test
    fun `first reading on a meter has no baseline to compare against`() {
        val rows = rows(ReadingsCsv.toCsv(listOf(reading("a", "2026-01-01", 100)), utc))
        assertEquals("new meter", rows[1][7])
        assertEquals("", rows[1][8])
        assertEquals("", rows[1][9])
    }

    @Test
    fun `change days and rate are computed against the previous reading`() {
        val csv = ReadingsCsv.toCsv(
            listOf(reading("a", "2026-01-01", 1000), reading("b", "2026-01-11", 2000)),
            utc,
        )
        val row = rows(csv)[2]
        assertEquals("1000", row[7])
        assertEquals("10", row[8])
        assertEquals("100.0", row[9])
    }

    @Test
    fun `two readings on one day are both kept and the rate is left blank`() {
        val csv = ReadingsCsv.toCsv(
            listOf(
                reading("a", "2026-01-01", 1000, at = "2026-01-01T08:00:00Z"),
                reading("b", "2026-01-01", 1050, at = "2026-01-01T17:30:00Z"),
            ),
            utc,
        )
        val rows = rows(csv)
        assertEquals(3, rows.size)
        assertEquals("50", rows[2][7])
        assertEquals("0", rows[2][8])
        // Dividing by a zero-day gap would be an infinity, not a measurement.
        assertEquals("", rows[2][9])
        assertEquals("2026-01-01 08:00:00", rows[1][1])
        assertEquals("2026-01-01 17:30:00", rows[2][1])
    }

    @Test
    fun `a different meter starts a fresh series rather than a huge negative jump`() {
        val csv = ReadingsCsv.toCsv(
            listOf(
                reading("a", "2026-01-01", 9_000_000, meter = "old"),
                reading("b", "2026-01-05", 10, meter = "new"),
            ),
            utc,
        )
        assertEquals("new meter", rows(csv)[2][7])
    }

    @Test
    fun `rows are ordered chronologically however they arrive`() {
        val csv = ReadingsCsv.toCsv(
            listOf(reading("late", "2026-03-01", 3000), reading("early", "2026-01-01", 1000)),
            utc,
        )
        val rows = rows(csv)
        assertEquals("early", rows[1][0])
        assertEquals("late", rows[2][0])
    }

    @Test
    fun `a note containing a comma cannot shift the columns`() {
        val csv = ReadingsCsv.toCsv(
            listOf(reading("a", "2026-01-01", 100, note = "pump ran long, check belt")),
            utc,
        )
        assertTrue(csv.contains("\"pump ran long, check belt\""))
        assertEquals(HEADER_COUNT, rows(csv)[0].size)
    }

    private companion object {
        const val HEADER_COUNT = 10
    }
}
