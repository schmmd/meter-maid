package com.schmmd.drumroll.ocr

import android.graphics.Bitmap

/**
 * One text fragment the recognizer returned, with enough geometry to put it back in
 * order. ML Kit routinely splits a register into pieces ("324" + "442"); [boxLeft]
 * is what lets us reassemble rather than discard.
 */
data class Fragment(val text: String, val boxLeft: Int)

data class RecognizedReading(
    /** Best digit per position. Positions below threshold are still filled here; */
    /** [agreement] is what decides whether the UI actually prefills them. */
    val digits: String,
    /** Per position, votes for the winning digit divided by *frames attempted*. */
    val agreement: List<Float>,
    val engine: String,
    val framesAttempted: Int,
    val framesUsable: Int,
)

interface MeterRecognizer {
    /**
     * @param frames already cropped to the register, rotated upright and upscaled.
     * @return null when too few frames were legible to say anything honest.
     */
    suspend fun read(frames: List<Bitmap>, drumCount: Int): RecognizedReading?
}

/** Positions at or above this get prefilled; the rest are left blank for the user. */
const val PREFILL_THRESHOLD = 0.8f
