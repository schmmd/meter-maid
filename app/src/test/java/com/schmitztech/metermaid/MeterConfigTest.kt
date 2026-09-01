package com.schmitztech.metermaid

import com.schmitztech.metermaid.data.MeterConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class MeterConfigTest {

    private val rockwell = MeterConfig()   // 6 drums, 1 painted zero

    @Test
    fun `drums become the full register value`() {
        // 24 Oct 2021: drums read 324444, painted zero makes it 3244440 gallons.
        assertEquals(3_244_440L, rockwell.toValue("324444"))
    }

    @Test
    fun `value round-trips back to drums`() {
        assertEquals("324444", rockwell.toDrums(3_244_440L))
    }

    @Test
    fun `display pads to the full register width`() {
        assertEquals("0299860", rockwell.display(299_860L))
    }

    @Test
    fun `a meter with no painted zeros is just its drums`() {
        val plain = MeterConfig(drumCount = 7, trailingZeros = 0)
        assertEquals(1_234_567L, plain.toValue("1234567"))
        assertEquals("1234567", plain.toDrums(1_234_567L))
    }
}
