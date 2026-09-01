package com.schmmd.drumroll

import com.schmmd.drumroll.backup.ReadingsCsv
import com.schmmd.drumroll.data.Reading
import com.schmmd.drumroll.data.SyncState
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
            corrected = false, photoPath = "1756654321.jpg", note = note, sync = SyncState.QUEUED,
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
