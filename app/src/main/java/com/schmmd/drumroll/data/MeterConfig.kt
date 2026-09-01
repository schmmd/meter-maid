package com.schmmd.drumroll.data

/**
 * The shape of a particular register.
 *
 * Michael's Rockwell 3/4 T-04 has six rotating drums and one painted zero that never
 * moves, so a reading of 3244440 is six OCR'd digits with a zero appended.
 */
data class MeterConfig(
    val meterId: String = ROCKWELL,
    val drumCount: Int = 6,
    val trailingZeros: Int = 1,
    /** Plausible gallons/day; anything outside this warns but does not block. */
    val minPerDay: Double = 50.0,
    val maxPerDay: Double = 5000.0,
) {
    val totalDigits: Int get() = drumCount + trailingZeros

    /** Drums as typed ("324444") to the stored value (3244440). */
    fun toValue(drums: String): Long =
        drums.toLong() * POWERS[trailingZeros]

    /** Stored value back to the drum digits, zero-padded. */
    fun toDrums(value: Long): String =
        (value / POWERS[trailingZeros]).toString().padStart(drumCount, '0')

    /** The full register as it reads on the face, e.g. "3244440". */
    fun display(value: Long): String =
        value.toString().padStart(totalDigits, '0')

    companion object {
        const val ROCKWELL = "rockwell-t04"
        private val POWERS = longArrayOf(1, 10, 100, 1000)
    }
}
