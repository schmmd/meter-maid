package com.schmitztech.metermaid.ocr

data class RecognizedReading(
    /** Best digit per position. Positions below threshold are still filled here; */
    /** [agreement] is what decides whether the UI actually prefills them. */
    val digits: String,
    /** Per position, votes for the winning digit divided by *frames attempted*. */
    val agreement: List<Float>,
    val framesAttempted: Int,
    val framesUsable: Int,
)

/** Positions at or above this get prefilled; the rest are left blank for the user. */
const val PREFILL_THRESHOLD = 0.8f
