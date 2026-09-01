package com.schmmd.drumroll.ocr

/**
 * Positional majority vote across frames.
 *
 * Two details here are load-bearing, both found by testing against real photos of the
 * meter rather than by reasoning:
 *
 *  1. Frames arrive fragmented. A frame that came back as "324" + "442" is a *good*
 *     frame; sorting fragments by x and concatenating recovers roughly half of what a
 *     naive length filter throws away.
 *
 *  2. Agreement divides by frames *attempted*, not frames that survived the length
 *     filter. Dividing by survivors is how you get "2 of 2 agree, therefore certain"
 *     out of a burst where 8 of 10 frames were unreadable — which is exactly the
 *     false confidence that made the earlier version of this report a wrong digit at
 *     full agreement.
 */
object Vote {

    const val MIN_USABLE_FRAMES = 3

    /** Fraction of the tallest fragment's height a fragment must reach to count. */
    const val MIN_HEIGHT_RATIO = 0.6f

    fun tally(
        frames: List<List<Fragment>>,
        drumCount: Int,
        trailingZeros: Int = 0,
        engine: String = "mlkit-v2",
        minUsableFrames: Int = MIN_USABLE_FRAMES,
    ): RecognizedReading? {
        val attempted = frames.size
        if (attempted == 0) return null

        val usable = frames
            .map { frame -> assemble(frame) }
            .mapNotNull { rotatingDrumsOf(it, drumCount, trailingZeros) }

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
     * Rebuild one frame's fragments into a single digit string, left to right.
     *
     * Only fragments close to the tallest are kept. Measured on the real crop, the
     * register comes back 56-65px tall while the dial numerals, "GALLON" and the "5/8"
     * pipe marking are all 14-27px — so height separates the register from the face it
     * is printed on almost perfectly, and without it those get concatenated into the
     * reading and push every frame past the length check.
     */
    fun assemble(frame: List<Fragment>): String {
        if (frame.isEmpty()) return ""
        val tallest = frame.maxOf { it.boxHeight }
        return frame
            .filter { tallest == 0 || it.boxHeight >= tallest * MIN_HEIGHT_RATIO }
            .sortedBy { it.boxLeft }
            .joinToString("") { it.text }
            .filter(Char::isDigit)
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
