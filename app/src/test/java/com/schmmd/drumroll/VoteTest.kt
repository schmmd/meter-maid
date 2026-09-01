package com.schmmd.drumroll

import com.schmmd.drumroll.ocr.Fragment
import com.schmmd.drumroll.ocr.Vote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These cases are transcribed from real OCR output on photographs of the meter, which
 * is why they look so specific. The jitter case in particular is the one that caught a
 * bug in the first version of this vote.
 */
class VoteTest {

    private fun frame(vararg parts: Pair<String, Int>) =
        parts.map { (text, left) -> Fragment(text, left) }

    @Test
    fun `unanimous frames give full agreement`() {
        val frames = List(5) { frame("324444" to 0) }
        val result = Vote.tally(frames, drumCount = 6)!!
        assertEquals("324444", result.digits)
        assertTrue(result.agreement.all { it == 1.0f })
    }

    @Test
    fun `fragments are reassembled in x order, not discarded`() {
        // A frame that came back split is a good frame.
        val frames = List(5) { frame("442" to 120, "324" to 10) }
        val result = Vote.tally(frames, drumCount = 6)!!
        assertEquals("324442", result.digits)
        assertEquals(5, result.framesUsable)
    }

    @Test
    fun `disagreement on one drum lowers only that position`() {
        val frames = listOf(
            frame("324442" to 0),
            frame("324440" to 0),
            frame("324442" to 0),
            frame("324442" to 0),
            frame("324445" to 0),
        )
        val result = Vote.tally(frames, drumCount = 6)!!
        assertEquals("324442", result.digits)
        assertEquals(1.0f, result.agreement[0])
        assertEquals(1.0f, result.agreement[4])
        assertEquals(0.6f, result.agreement[5], 0.001f)
    }

    @Test
    fun `too few legible frames returns null rather than a confident guess`() {
        // The real jitter burst: 8 of 10 frames unusable, the 2 survivors agreeing.
        // Dividing by survivors would report certainty in a wrong digit.
        val frames = listOf(
            frame("1324442" to 0),   // bezel edge read as a leading 1
            frame("32444" to 0),     // dropped a drum
            frame("324442" to 0),
            frame("441" to 0),
            frame("324442" to 0),
        )
        assertNull(Vote.tally(frames, drumCount = 6, minUsableFrames = 3))
    }

    @Test
    fun `agreement divides by frames attempted, not frames surviving`() {
        val frames = listOf(
            frame("324442" to 0),
            frame("324442" to 0),
            frame("324442" to 0),
            frame("441" to 0),       // unusable
            frame("32" to 0),        // unusable
        )
        val result = Vote.tally(frames, drumCount = 6, minUsableFrames = 3)!!
        // Three of five frames agreed — 0.6, not 1.0.
        assertEquals(0.6f, result.agreement[0], 0.001f)
        assertEquals(3, result.framesUsable)
        assertEquals(5, result.framesAttempted)
    }

    @Test
    fun `non-digit noise is stripped`() {
        // One real frame came back containing a Thai baht sign.
        val frames = List(4) { frame("32444฿2" to 0) }
        val result = Vote.tally(frames, drumCount = 6)!!
        assertEquals("324442", result.digits)
    }

    @Test
    fun `empty burst returns null`() {
        assertNull(Vote.tally(emptyList(), drumCount = 6))
    }
}
