package com.schmitztech.metermaid

import com.schmitztech.metermaid.backup.ReadingsCsv
import com.schmitztech.metermaid.data.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Import has to survive what a spreadsheet does to a file people have opened. */
class ReadingsCsvParseTest {

    private val utc = ZoneId.of("UTC")

    private fun reading(date: String, value: Long, at: String, note: String = "", meter: String = "well") =
        Reading(
            id = "ignored", readingDate = date, capturedAtUtc = Instant.parse(at).toEpochMilli(),
            value = value, meterId = meter, ocrRaw = null, minAgreement = null,
            corrected = false, photoPath = "1756654321.jpg", note = note,
        )

    @Test
    fun `a file this app wrote survives the round trip`() {
        val original = listOf(
            reading("2026-01-01", 1000, "2026-01-01T08:00:00Z"),
            reading("2026-01-11", 2000, "2026-01-11T09:30:00Z", note = "after the thaw"),
        )
        val back = ReadingsCsv.parse(ReadingsCsv.toCsv(original, utc), utc)
        assertEquals(2, back.size)
        assertEquals("2026-01-01", back[0].readingDate)
        assertEquals(1000L, back[0].value)
        assertEquals("well", back[0].meterId)
        assertEquals("after the thaw", back[1].note)
        assertEquals("1756654321.jpg", back[1].photoName)
        // The timestamp is what separates two readings on one day, so it must survive.
        assertEquals(original[0].capturedAtUtc, back[0].capturedAtUtc)
    }

    @Test
    fun `two readings on one day keep their separate times`() {
        val original = listOf(
            reading("2026-01-01", 1000, "2026-01-01T08:00:00Z"),
            reading("2026-01-01", 1050, "2026-01-01T17:30:00Z"),
        )
        val back = ReadingsCsv.parse(ReadingsCsv.toCsv(original, utc), utc)
        assertEquals(2, back.size)
        assertTrue(back[0].capturedAtUtc != back[1].capturedAtUtc)
    }

    @Test
    fun `import identity survives round trips that mangle the timestamp`() {
        // The timestamp column drops milliseconds, and a file exported in one zone can
        // be parsed in another. Both mangle capturedAtUtc, which is why import dedupes
        // on (meter, date, value) instead — that triple has to come back exact.
        val original = listOf(reading("2026-01-01", 1000, "2026-01-01T08:00:00.789Z"))
        val csv = ReadingsCsv.toCsv(original, utc)
        for (zone in listOf(utc, ZoneId.of("America/Los_Angeles"))) {
            val back = ReadingsCsv.parse(csv, zone)
            assertEquals(
                Triple(original[0].meterId, original[0].readingDate, original[0].value),
                Triple(back[0].meterId, back[0].readingDate, back[0].value),
            )
        }
        // Same-zone round trip alone already loses the milliseconds.
        assertTrue(original[0].capturedAtUtc != ReadingsCsv.parse(csv, utc)[0].capturedAtUtc)
    }

    @Test
    fun `a note containing a comma does not shift the columns`() {
        val original = listOf(reading("2026-01-01", 1000, "2026-01-01T08:00:00Z",
            note = "pump ran long, check belt"))
        val back = ReadingsCsv.parse(ReadingsCsv.toCsv(original, utc), utc)
        assertEquals("pump ran long, check belt", back[0].note)
        assertEquals(1000L, back[0].value)
    }

    @Test
    fun `columns are matched by name, not position`() {
        val csv = "date,reading,meter\n2026-02-02,4242,well\n"
        val back = ReadingsCsv.parse(csv, utc)
        assertEquals(1, back.size)
        assertEquals(4242L, back[0].value)
        assertEquals("well", back[0].meterId)
    }

    @Test
    fun `a row with no timestamp falls back to noon on its date`() {
        val back = ReadingsCsv.parse("date,reading\n2026-02-02,4242\n", utc)
        assertEquals(Instant.parse("2026-02-02T12:00:00Z").toEpochMilli(), back[0].capturedAtUtc)
    }

    @Test
    fun `a csv downloaded from the old google sheet still imports`() {
        // The Apps Script wrote the date cell as a real Date, which Sheets renders as
        // "10/24/2021 12:00:00" on download — the one migration path for old data.
        val csv = "id,date,reading\nabc123,10/24/2021 12:00:00,3244440\n"
        val back = ReadingsCsv.parse(csv, utc)
        assertEquals(1, back.size)
        assertEquals("2021-10-24", back[0].readingDate)
        assertEquals(3244440L, back[0].value)
    }

    @Test
    fun `a byte order mark does not swallow the first column`() {
        val back = ReadingsCsv.parse("﻿date,reading\n2026-02-02,4242\n", utc)
        assertEquals(4242L, back[0].value)
    }

    @Test
    fun `a file that is not a readings export is refused`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            ReadingsCsv.parse("family,vegetable\ncucurbit,cucumber\n", utc)
        }
        assertTrue(e.message!!.contains("does not look like"))
    }

    @Test
    fun `a header with no rows under it is refused rather than importing nothing`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReadingsCsv.parse("date,reading\n", utc)
        }
    }

    @Test
    fun `unparseable rows are dropped without taking the file down`() {
        val csv = "date,reading\n2026-02-02,4242\nnot-a-date,nope\n2026-02-03,4300\n"
        assertEquals(2, ReadingsCsv.parse(csv, utc).size)
    }
}
