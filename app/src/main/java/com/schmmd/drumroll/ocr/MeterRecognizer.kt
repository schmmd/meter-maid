package com.schmmd.drumroll.ocr

import android.graphics.Bitmap

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
     * @param frames already cropped to the guide box, rotated upright and upscaled.
     * @param trailingZeros painted zeros on the face, which the recognizer sees but
     *   which never turn, so they are stripped rather than reported.
     * @return null when too few frames were legible to say anything honest.
     */
    suspend fun read(frames: List<Bitmap>, drumCount: Int, trailingZeros: Int): RecognizedReading?
}

/** Positions at or above this get prefilled; the rest are left blank for the user. */
const val PREFILL_THRESHOLD = 0.8f
