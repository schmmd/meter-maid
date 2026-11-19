package com.schmitztech.metermaid

import com.schmitztech.metermaid.ocr.ppocr.CtcHit
import com.schmitztech.metermaid.ocr.ppocr.DrumAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The timesteps below are PP-OCRv4's actual output for the two register photographs
 * taken 2026-08-31, measured through the ONNX recogniser with its charset masked to
 * digits. Both frames decode to `1014516940`; the register reads `0456940`.
 */
class DrumAssignmentTest {

    private fun hits(vararg spec: Triple<Int, Char, Float>) =
        spec.map { (t, c, p) -> CtcHit(t, c, p) }

    /** File 1788199307706.jpg, T=22. */
    private val frameA = hits(
        Triple(1, '1', 0.16f),   // left border bar
        Triple(2, '0', 0.20f),
        Triple(4, '1', 0.15f),   // separator
        Triple(5, '4', 0.21f),
        Triple(8, '5', 0.21f),
        Triple(10, '1', 0.20f),  // separator
        Triple(11, '6', 0.17f),
        Triple(14, '9', 0.21f),
        Triple(17, '4', 0.21f),
        Triple(20, '0', 0.21f),  // painted zero
    )

    /** File 1788199257009.jpg, T=21. */
    private val frameB = hits(
        Triple(1, '1', 0.20f),
        Triple(2, '0', 0.21f),
        Triple(4, '1', 0.17f),
        Triple(5, '4', 0.21f),
        Triple(8, '5', 0.21f),
        Triple(9, '1', 0.21f),
        Triple(11, '6', 0.20f),
        Triple(13, '9', 0.21f),
        Triple(16, '4', 0.21f),
        Triple(18, '0', 0.21f),
    )

    @Test
    fun `separator bars lose to the digits beside them`() {
        assertEquals("0456940", DrumAssignment.assign(frameA, timesteps = 22, cells = 7))
    }

    @Test
    fun `the second frame reads the same register the same way`() {
        assertEquals("0456940", DrumAssignment.assign(frameB, timesteps = 21, cells = 7))
    }

    @Test
    fun `a drum genuinely showing a one is kept`() {
        // Same geometry, but the third drum really is a 1 rather than a separator: it
        // sits on a cell centre instead of between two, which is what saves it.
        val onDrum = hits(
            Triple(2, '0', 0.9f), Triple(5, '4', 0.9f), Triple(8, '1', 0.9f),
            Triple(11, '6', 0.9f), Triple(14, '9', 0.9f), Triple(17, '4', 0.9f),
            Triple(20, '0', 0.9f),
        )
        assertEquals("0416940", DrumAssignment.assign(onDrum, timesteps = 22, cells = 7))
    }

    @Test
    fun `fewer characters than drums says nothing`() {
        assertNull(DrumAssignment.assign(frameA.take(4), timesteps = 22, cells = 7))
    }

    @Test
    fun `an empty frame says nothing`() {
        assertNull(DrumAssignment.assign(emptyList(), timesteps = 22, cells = 7))
    }

    @Test
    fun `the reading never comes out of order`() {
        val result = DrumAssignment.assign(frameA, timesteps = 22, cells = 7)!!
        // Reconstructing from the same hits must preserve emission order.
        assertEquals(7, result.length)
    }
}

/**
 * Timesteps are PP-OCRv4's output for 1795045396605.jpg, taken 2026-11-18 at 1080p. The
 * tighter detector box no longer reaches the dial's blue zero, so the strip holds exactly
 * the six drums and nothing stands in for the painted zero.
 */
class RotatingDrumsFromHitsTest {

    private fun hits(vararg spec: Triple<Int, Char, Float>) =
        spec.map { (t, c, p) -> CtcHit(t, c, p) }

    private val tight = hits(
        Triple(4, '0', 0.04f), Triple(12, '4', 0.08f), Triple(20, '7', 0.08f),
        Triple(27, '4', 0.09f), Triple(35, '3', 0.08f), Triple(42, '0', 0.09f),
    )

    @Test
    fun `a strip without the painted zero still yields the drums`() {
        assertEquals("047430", DrumAssignment.rotatingDrums(tight, timesteps = 48, drumCount = 6, trailingZeros = 1))
    }

    @Test
    fun `a strip with the painted zero is read as before`() {
        val loose = hits(
            Triple(1, '1', 0.16f), Triple(2, '0', 0.20f), Triple(4, '1', 0.15f), Triple(5, '4', 0.21f),
            Triple(8, '5', 0.21f), Triple(10, '1', 0.20f), Triple(11, '6', 0.17f), Triple(14, '9', 0.21f),
            Triple(17, '4', 0.21f), Triple(20, '0', 0.21f),
        )
        assertEquals("045694", DrumAssignment.rotatingDrums(loose, timesteps = 22, drumCount = 6, trailingZeros = 1))
    }

    @Test
    fun `too few characters for even the drums says nothing`() {
        assertNull(DrumAssignment.rotatingDrums(tight.take(5), timesteps = 48, drumCount = 6, trailingZeros = 1))
    }
}
