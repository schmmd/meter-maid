package com.schmitztech.metermaid.ocr.ppocr

import com.schmitztech.metermaid.ocr.Vote

/** One character the recogniser emitted, at the CTC timestep it was emitted on. */
data class CtcHit(val timestep: Int, val digit: Char, val confidence: Float)

/**
 * Turns the recogniser's raw character sequence into one digit per drum.
 *
 * The recogniser reads the register as a line of text and cannot know that the black
 * bars *between* drums are not content: measured on this meter it returns ten characters
 * for a seven-digit register, `1014516940` for `0456940`, with a spurious `1` at each
 * separator. Simply dropping `1`s would also drop a drum genuinely showing a one.
 *
 * What separates them is position. The drums are evenly spaced across the register, so
 * the correct reading is the run of characters whose CTC timesteps land closest to
 * equally spaced cell centres — a separator sits *between* two centres and loses to the
 * digit beside it, while a real `1` sits on a centre and wins.
 */
object DrumAssignment {

    /** Weight on even spacing relative to recogniser confidence. Tuned on real frames. */
    const val SPACING_WEIGHT = 6.0

    /**
     * The rotating drums of one frame, whether or not the painted zeros made it into the
     * strip. At 640x480 the detector box was loose enough to take in the dial's blue zero
     * beside the register, which stood in for the painted zero; the tight box at 1080p
     * holds the drums alone, and demanding one more character threw every frame away.
     */
    fun rotatingDrums(hits: List<CtcHit>, timesteps: Int, drumCount: Int, trailingZeros: Int): String? =
        assign(hits, timesteps, drumCount + trailingZeros)
            ?.let { Vote.rotatingDrumsOf(it, drumCount, trailingZeros) }
            ?: assign(hits, timesteps, drumCount)

    /**
     * @param hits characters in emission order.
     * @param timesteps total CTC timesteps, i.e. the width the register spans.
     * @param cells digits expected on the face, painted zeros included.
     * @return one digit per cell, or null if the frame did not supply enough characters.
     */
    fun assign(hits: List<CtcHit>, timesteps: Int, cells: Int): String? {
        if (cells <= 0 || timesteps <= 0 || hits.size < cells) return null

        val centres = DoubleArray(cells) { timesteps * (it + 0.5) / cells }
        val n = hits.size
        val neg = Double.NEGATIVE_INFINITY

        fun score(cell: Int, hit: Int): Double {
            val offset = (hits[hit].timestep - centres[cell]) / timesteps
            return hits[hit].confidence - SPACING_WEIGHT * offset * offset * cells
        }

        // best[c][j] = best total score for filling cells 0..c with hit j in cell c.
        val best = Array(cells) { DoubleArray(n) { neg } }
        val from = Array(cells) { IntArray(n) { -1 } }
        for (j in 0 until n) best[0][j] = score(0, j)
        for (c in 1 until cells) {
            for (j in 0 until n) {
                // Cells are filled left to right, so cell c must take a later character
                // than cell c-1: the reading cannot come out of order.
                for (k in 0 until j) {
                    if (best[c - 1][k] == neg) continue
                    val total = best[c - 1][k] + score(c, j)
                    if (total > best[c][j]) {
                        best[c][j] = total
                        from[c][j] = k
                    }
                }
            }
        }

        var end = -1
        var endScore = neg
        for (j in 0 until n) {
            if (best[cells - 1][j] > endScore) { endScore = best[cells - 1][j]; end = j }
        }
        if (end < 0 || endScore == neg) return null

        val picked = CharArray(cells)
        var j = end
        for (c in cells - 1 downTo 0) {
            if (j < 0) return null
            picked[c] = hits[j].digit
            j = from[c][j]
        }
        return String(picked)
    }
}
