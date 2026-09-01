package com.schmitztech.metermaid

import com.schmitztech.metermaid.ocr.Vote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The burst-agreement rules. These predate PP-OCR and are engine-independent: each frame
 * arrives already reduced to a digit string. The jitter case caught a real bug in the
 * first version of this vote and is kept as written.
 */
class VoteTest {

    private fun tally(usable: List<String>, attempted: Int = usable.size, min: Int = 3) =
        Vote.tallyReadings(usable, attempted, drumCount = 6, minUsableFrames = min)

    @Test
    fun `unanimous frames give full agreement`() {
        val result = tally(List(5) { "324444" })!!
        assertEquals("324444", result.digits)
        assertTrue(result.agreement.all { it == 1.0f })
    }

    @Test
    fun `disagreement on one drum lowers only that position`() {
        val result = tally(listOf("324442", "324440", "324442", "324442", "324445"))!!
        assertEquals("324442", result.digits)
        assertEquals(1.0f, result.agreement[0])
        assertEquals(1.0f, result.agreement[4])
        assertEquals(0.6f, result.agreement[5], 0.001f)
    }

    @Test
    fun `too few legible frames returns null rather than a confident guess`() {
        // The real jitter burst: only two of five frames survived, and they agreed.
        // Reporting that as certainty is what this guard exists to prevent.
        assertNull(tally(listOf("324442", "324442"), attempted = 5))
    }

    @Test
    fun `agreement divides by frames attempted, not frames surviving`() {
        val result = tally(listOf("324442", "324442", "324442"), attempted = 5)!!
        assertEquals(0.6f, result.agreement[0], 0.001f)
        assertEquals(3, result.framesUsable)
        assertEquals(5, result.framesAttempted)
    }

    @Test
    fun `the majority digit wins each position independently`() {
        val result = tally(listOf("324442", "324542", "324442", "324442"))!!
        assertEquals("324442", result.digits)
        assertEquals(0.75f, result.agreement[3], 0.001f)
    }

    @Test
    fun `empty burst returns null`() {
        assertNull(tally(emptyList(), attempted = 0))
    }
}
