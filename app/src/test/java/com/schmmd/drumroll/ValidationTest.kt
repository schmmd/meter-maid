package com.schmmd.drumroll

import com.schmmd.drumroll.data.MeterConfig
import com.schmmd.drumroll.data.Reading
import com.schmmd.drumroll.data.SyncState
import com.schmmd.drumroll.util.Severity
import com.schmmd.drumroll.util.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ValidationTest {

    private val config = MeterConfig()

    private fun reading(date: String, value: Long) = Reading(
        id = "x", readingDate = date, capturedAtUtc = 0L, value = value,
        meterId = config.meterId, ocrRaw = null, minAgreement = null, corrected = false,
        photoPath = "", note = "", sync = SyncState.SYNCED,
    )

    @Test
    fun `an incomplete reading blocks saving`() {
        val issues = Validation.check(
            drums = "3244", readingDate = LocalDate.parse("2021-10-24"),
            capturedAt = LocalDate.parse("2021-10-24"), previous = null,
            config = config, duplicateOnDate = false,
        )
        assertTrue(Validation.blocking(issues))
    }

    @Test
    fun `a plausible reading passes clean`() {
        // 19 Oct 3239080 to 24 Oct 3244440 is 1072 gal/day — inside the default band.
        val issues = Validation.check(
            drums = "324444", readingDate = LocalDate.parse("2021-10-24"),
            capturedAt = LocalDate.parse("2021-10-24"),
            previous = reading("2021-10-19", 3_239_080L),
            config = config, duplicateOnDate = false,
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `going backwards warns but does not block`() {
        val issues = Validation.check(
            drums = "323000", readingDate = LocalDate.parse("2021-10-24"),
            capturedAt = LocalDate.parse("2021-10-24"),
            previous = reading("2021-10-19", 3_239_080L),
            config = config, duplicateOnDate = false,
        )
        assertEquals(1, issues.size)
        assertEquals(Severity.WARN, issues.first().severity)
        assertTrue(!Validation.blocking(issues))
    }

    @Test
    fun `a misread leading digit trips the rate check`() {
        // The exact failure mode measured on these photos: one wrong leading drum.
        val issues = Validation.check(
            drums = "424444", readingDate = LocalDate.parse("2021-10-24"),
            capturedAt = LocalDate.parse("2021-10-24"),
            previous = reading("2021-10-19", 3_239_080L),
            config = config, duplicateOnDate = false,
        )
        assertTrue(issues.any { it.message.contains("gal/day") })
    }

    @Test
    fun `a date far from the capture date warns`() {
        val issues = Validation.check(
            drums = "324444", readingDate = LocalDate.parse("2021-09-01"),
            capturedAt = LocalDate.parse("2021-10-24"),
            previous = null, config = config, duplicateOnDate = false,
        )
        assertTrue(issues.any { it.message.contains("days from when the photo") })
    }
}
