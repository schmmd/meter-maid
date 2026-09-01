package com.schmmd.drumroll

import com.schmmd.drumroll.ocr.Fragment
import com.schmmd.drumroll.ocr.Vote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Transcribed from ML Kit's actual output on two photographs of the meter, taken
 * 2026-08-31 through the corrected crop. The register reads 045694 with one painted
 * zero; the numbers below (text, left, height) are exactly what the recognizer returned.
 *
 * Both frames were being discarded before this, for two separate reasons, and neither
 * reason was "the crop was bad".
 */
class RegisterFragmentsTest {

    /** 456x152 crop. The register came back as one 65px-tall element; the rest is dial. */
    private val frameA = listOf(
        Fragment("8", 61, 17),        // dial numeral
        Fragment("6", 82, 65),        // the register, read as 'lölASI6E'
        Fragment("589", 286, 27),     // the "5/8" pipe marking, read as '5/8\\9L'
        Fragment("1", 311, 14),       // dial numeral
        Fragment("2", 365, 16),       // dial numeral
        Fragment("0", 378, 58),       // the painted zero
    )

    /** Same crop, cleaner frame: the whole register in one 56px element. */
    private val frameB = listOf(
        Fragment("1251680", 106, 56), // the register, read as 'lö1251680'
        Fragment("58", 282, 24),      // the "5/8" pipe marking
    )

    @Test
    fun `dial numerals and pipe markings are dropped instead of joining the reading`() {
        // Was "86589120" — eight digits of register plus scenery, discarded as unreadable
        // for the wrong reason. Height alone separates the register from the face.
        assertEquals("60", Vote.assemble(frameA))
    }

    @Test
    fun `the register survives as the only tall thing in the frame`() {
        assertEquals("1251680", Vote.assemble(frameB))
    }

    @Test
    fun `a read that includes the painted zero is not thrown away`() {
        // Seven digits off a six-drum meter is what a *correct* read looks like when the
        // guide box includes the painted zero, which it always does.
        assertEquals("045694", Vote.rotatingDrumsOf("0456940", drumCount = 6, trailingZeros = 1))
    }

    @Test
    fun `an over-long read that does not end in the painted zeros is a misread`() {
        assertNull(Vote.rotatingDrumsOf("0456941", drumCount = 6, trailingZeros = 1))
    }

    @Test
    fun `exactly the drum count is still accepted untouched`() {
        assertEquals("045694", Vote.rotatingDrumsOf("045694", drumCount = 6, trailingZeros = 1))
    }

    @Test
    fun `neither length means the frame says nothing`() {
        assertNull(Vote.rotatingDrumsOf("60", drumCount = 6, trailingZeros = 1))
    }

    @Test
    fun `a meter with no painted zeros only ever accepts the drum count`() {
        assertNull(Vote.rotatingDrumsOf("0456940", drumCount = 6, trailingZeros = 0))
    }

    @Test
    fun `one legible frame out of two is not enough to prefill anything`() {
        // frameA yields nothing usable and frameB yields one reading. Reporting a digit
        // from a single surviving frame is the false confidence this vote exists to stop.
        assertNull(Vote.tally(listOf(frameA, frameB), drumCount = 6, trailingZeros = 1))
    }
}
