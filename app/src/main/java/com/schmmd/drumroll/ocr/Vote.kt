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

    fun tally(
        frames: List<List<Fragment>>,
        drumCount: Int,
        engine: String = "mlkit-v2",
        minUsableFrames: Int = MIN_USABLE_FRAMES,
    ): RecognizedReading? {
        val attempted = frames.size
        if (attempted == 0) return null

        val usable = frames
            .map { frame -> assemble(frame) }
            .filter { it.length == drumCount }

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

    /** Rebuild one frame's fragments into a single digit string, left to right. */
    fun assemble(frame: List<Fragment>): String =
        frame.sortedBy { it.boxLeft }
            .joinToString("") { it.text }
            .filter(Char::isDigit)
}
