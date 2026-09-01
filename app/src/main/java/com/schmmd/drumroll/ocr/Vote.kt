package com.schmmd.drumroll.ocr

/**
 * Cross-frame agreement, and the painted-zero arithmetic that goes with it.
 *
 * Nothing here knows which engine produced the readings: each frame arrives already
 * reduced to a digit string, and what survives is what several independent frames of the
 * same burst agreed on.
 */
object Vote {

    const val MIN_USABLE_FRAMES = 3

    /**
     * Positional majority vote across the frames of one burst.
     *
     * [attempted] is the size of the whole burst, not of [usable]. Dividing agreement by
     * the frames that survived is how you get "2 of 2 agree, therefore certain" out of a
     * burst where 8 of 10 were unreadable — exactly the false confidence that made an
     * earlier version of this report a wrong digit at full agreement.
     */
    fun tallyReadings(
        usable: List<String>,
        attempted: Int,
        drumCount: Int,
        engine: String,
        minUsableFrames: Int = MIN_USABLE_FRAMES,
    ): RecognizedReading? {

        // Too few legible frames means the framing failed. Saying nothing is the
        // honest answer; the user types six digits and moves on.
        if (usable.size < minUsableFrames) return null

        val digits = StringBuilder(drumCount)
        val agreement = ArrayList<Float>(drumCount)

        for (i in 0 until drumCount) {
            val counts = usable.groupingBy { it[i] }.eachCount()
            val winner = counts.maxByOrNull { it.value }!!
            digits.append(winner.key)
            agreement += winner.value.toFloat() / attempted
        }

        return RecognizedReading(
            digits = digits.toString(),
            agreement = agreement,
            engine = engine,
            framesAttempted = attempted,
            framesUsable = usable.size,
        )
    }

    /**
     * The rotating drums alone, or null if this frame cannot supply them.
     *
     * The guide box naturally includes the painted zeros — they are part of the register
     * face, and asking someone to frame around them in a crawlspace is not realistic —
     * so a *correct* read of a six-drum meter with one painted zero returns seven
     * digits. Requiring exactly six threw those away as unreadable.
     */
    fun rotatingDrumsOf(assembled: String, drumCount: Int, trailingZeros: Int): String? = when {
        assembled.length == drumCount -> assembled
        trailingZeros > 0 && assembled.length == drumCount + trailingZeros ->
            // Only when the tail really is the painted zeros; anything else is a misread.
            assembled.takeIf { it.endsWith("0".repeat(trailingZeros)) }?.dropLast(trailingZeros)
        else -> null
    }
}
