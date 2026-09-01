package com.schmitztech.metermaid

import com.schmitztech.metermaid.ocr.Vote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The painted zeros are on the face and inside the guide box, so every engine reads them.
 * They never turn, so they are not part of what the user confirms.
 */
class RotatingDrumsTest {

    @Test
    fun `a read that includes the painted zero is not thrown away`() {
        // Seven digits off a six-drum meter is what a *correct* read looks like: framing
        // around the painted zero in a crawlspace is not realistic, so it is stripped.
        assertEquals("045694", Vote.rotatingDrumsOf("0456940", drumCount = 6, trailingZeros = 1))
    }

    @Test
    fun `an over-long read that does not end in the painted zeros is a misread`() {
        assertNull(Vote.rotatingDrumsOf("0456941", drumCount = 6, trailingZeros = 1))
    }

    @Test
    fun `exactly the drum count is accepted untouched`() {
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
}
