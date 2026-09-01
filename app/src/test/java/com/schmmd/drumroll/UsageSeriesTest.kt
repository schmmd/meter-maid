package com.schmmd.drumroll

import com.schmmd.drumroll.data.Reading
import com.schmmd.drumroll.data.SyncState
import com.schmmd.drumroll.data.UsageSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class UsageSeriesTest {

    private fun reading(date: String, value: Long, at: String = "${date}T12:00:00Z", meter: String = "well") =
        Reading(
            id = "$date-$value-$at", readingDate = date,
            capturedAtUtc = Instant.parse(at).toEpochMilli(), value = value, meterId = meter,
            ocrRaw = null, minAgreement = null, corrected = false, photoPath = "",
            note = "", sync = SyncState.SYNCED,
        )

    @Test
    fun `usage is the average draw between consecutive readings`() {
        val points = UsageSeries.from(
            listOf(reading("2026-01-01", 1000), reading("2026-01-11", 2000)),
        )
        assertEquals(1, points.size)
        // Plotted on the later date: that is the day the average is known as of.
        assertEquals(LocalDate.parse("2026-01-11"), points[0].date)
        assertEquals(100.0, points[0].gallonsPerDay, 0.001)
        assertEquals(10L, points[0].spanDays)
    }

    @Test
    fun `the first reading has nothing to measure against`() {
        assertTrue(UsageSeries.from(listOf(reading("2026-01-01", 1000))).isEmpty())
    }

    @Test
    fun `several readings on one day collapse to the last one taken`() {
        val points = UsageSeries.from(
            listOf(
                reading("2026-01-01", 1000, at = "2026-01-01T08:00:00Z"),
                reading("2026-01-11", 1800, at = "2026-01-11T08:00:00Z"),
                reading("2026-01-11", 2000, at = "2026-01-11T18:00:00Z"),
            ),
        )
        assertEquals(1, points.size)
        // 2000, not 1800: the evening read supersedes the morning one.
        assertEquals(100.0, points[0].gallonsPerDay, 0.001)
        assertEquals(2000L, points[0].reading)
    }

    @Test
    fun `replacing the meter starts a new run instead of a spike`() {
        val points = UsageSeries.from(
            listOf(
                reading("2026-01-01", 9_000_000, meter = "old"),
                reading("2026-01-11", 10, meter = "new"),
                reading("2026-01-21", 1010, meter = "new"),
            ),
        )
        assertEquals(1, points.size)
        assertEquals(100.0, points[0].gallonsPerDay, 0.001)
    }

    @Test
    fun `readings arriving out of order still produce a chronological series`() {
        val points = UsageSeries.from(
            listOf(
                reading("2026-03-01", 3000),
                reading("2026-01-01", 1000),
                reading("2026-02-01", 2000),
            ),
        )
        assertEquals(2, points.size)
        assertTrue(points[0].date < points[1].date)
    }

    @Test
    fun `a same-day pair on different meters does not divide by a zero-day gap`() {
        val points = UsageSeries.from(
            listOf(reading("2026-01-01", 1000), reading("2026-01-01", 1000, at = "2026-01-01T20:00:00Z")),
        )
        assertTrue(points.isEmpty())
    }
}
