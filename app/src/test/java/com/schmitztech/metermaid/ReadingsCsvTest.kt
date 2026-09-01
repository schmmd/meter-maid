package com.schmitztech.metermaid

import com.schmitztech.metermaid.backup.ReadingsCsv
import com.schmitztech.metermaid.data.Reading
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
        photoPath: String = "",
    ) = Reading(
        id = id,
        readingDate = date,
        capturedAtUtc = Instant.parse(at).toEpochMilli(),
        value = value,
        meterId = meter,
        ocrRaw = null,
        minAgreement = null,
        corrected = false,
        photoPath = photoPath,
        note = note,
    )

    private fun rows(csv: String) = csv.trim().lines().map { it.split(",") }

    @Test
    fun `first reading on a meter has no baseline to compare against`() {
        val rows = rows(ReadingsCsv.toCsv(listOf(reading("a", "2026-01-01", 100)), utc))
        assertEquals("new meter", rows[1][CHANGE])
        assertEquals("", rows[1][DAYS])
        assertEquals("", rows[1][RATE])
    }

    @Test
    fun `change days and rate are computed against the previous reading`() {
        val csv = ReadingsCsv.toCsv(
            listOf(reading("a", "2026-01-01", 1000), reading("b", "2026-01-11", 2000)),
            utc,
        )
        val row = rows(csv)[2]
        assertEquals("1000", row[CHANGE])
        assertEquals("10", row[DAYS])
        assertEquals("100.0", row[RATE])
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
        assertEquals("50", rows[2][CHANGE])
        assertEquals("0", rows[2][DAYS])
        // Dividing by a zero-day gap would be an infinity, not a measurement.
        assertEquals("", rows[2][RATE])
        assertEquals("2026-01-01 08:00:00", rows[1][TIMESTAMP])
        assertEquals("2026-01-01 17:30:00", rows[2][TIMESTAMP])
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
        assertEquals("new meter", rows(csv)[2][CHANGE])
        assertEquals("old", rows(csv)[1][METER])
        assertEquals("new", rows(csv)[2][METER])
    }

    @Test
    fun `rows are ordered chronologically however they arrive`() {
        val csv = ReadingsCsv.toCsv(
            listOf(reading("late", "2026-03-01", 3000), reading("early", "2026-01-01", 1000)),
            utc,
        )
        val rows = rows(csv)
        assertEquals("2026-01-01", rows[1][DATE])
        assertEquals("2026-03-01", rows[2][DATE])
    }

    @Test
    fun `the photo column carries the filename, not the app-private path`() {
        val csv = ReadingsCsv.toCsv(
            listOf(
                reading("a", "2026-01-01", 100, photoPath = "/data/user/0/com.schmitztech.metermaid/files/photos/1756654321.jpg"),
                reading("b", "2026-01-02", 200),
            ),
            utc,
        )
        val rows = rows(csv)
        assertEquals("1756654321.jpg", rows[1][PHOTO])
        // A reading saved before photos existed, or whose capture failed, stays blank
        // rather than pointing at a file that is not there.
        assertEquals("", rows[2][PHOTO])
    }

    @Test
    fun `the file looks the same from a comma-decimal locale`() {
        // The CSV is data shared between devices, not prose for this one: a German
        // phone writing "104,3" hands spreadsheets text instead of a number.
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            val csv = ReadingsCsv.toCsv(
                listOf(reading("a", "2026-01-01", 1000), reading("b", "2026-01-11", 2043)),
                utc,
            )
            assertEquals("104.3", rows(csv)[2][RATE])
            assertEquals("2026-01-01 12:00:00", rows(csv)[1][TIMESTAMP])
        } finally {
            java.util.Locale.setDefault(before)
        }
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
        const val METER = 0
        const val TIMESTAMP = 1
        const val DATE = 2
        const val CHANGE = 6
        const val DAYS = 7
        const val RATE = 8
        const val PHOTO = 9
        const val HEADER_COUNT = 10
    }
}
