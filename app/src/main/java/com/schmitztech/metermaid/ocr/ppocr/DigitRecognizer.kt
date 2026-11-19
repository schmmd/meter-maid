package com.schmitztech.metermaid.ocr.ppocr

import android.graphics.Bitmap
import kotlin.math.exp

/** What one frame's register strip decoded to, before the drums are picked out. */
data class RecognizedStrip(val hits: List<CtcHit>, val timesteps: Int) {
    val raw: String get() = hits.joinToString("") { it.digit.toString() }
}

/**
 * Reads the register strip with PP-OCRv5 and returns each character with the CTC timestep
 * it was emitted at, which is what [DrumAssignment] needs to tell drums from separators.
 *
 * The character table is masked to digits before decoding. This is the whitelist ML Kit
 * has no way to express, and it is why the same register that came back as "lölASI6E"
 * from ML Kit cannot come back as letters here at all.
 */
class DigitRecognizer(private val models: PpOcrModels) {

    private val digitClasses: IntArray = models.charset
        .withIndex()
        .filter { (_, c) -> c.length == 1 && c[0].isDigit() }
        .map { it.index }
        .toIntArray()

    fun read(strip: Bitmap): RecognizedStrip? {
        val prepared = resize(strip) ?: return null
        val logits = try {
            models.tensorOf(prepared).use { input ->
                models.recognizer.run(mapOf(models.recognizerInput to input)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    (output[0].value as Array<Array<FloatArray>>)[0]
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MeterMaidOcr", "recognizer failed", e)
            return null
        } finally {
            if (prepared !== strip) prepared.recycle()
        }
        if (logits.isEmpty()) return null

        val hits = ArrayList<CtcHit>()
        var previous = -1
        for (t in logits.indices) {
            val step = logits[t]
            // Blank (index 0) always stays in play; it is what separates repeated digits.
            var bestIndex = 0
            var bestLogit = step[0]
            for (index in digitClasses) {
                if (step[index] > bestLogit) {
                    bestLogit = step[index]
                    bestIndex = index
                }
            }
            // Standard CTC collapse: emit on a change to a non-blank class.
            if (bestIndex != 0 && bestIndex != previous) {
                val character = models.charset[bestIndex].firstOrNull()
                if (character != null && character.isDigit()) {
                    hits += CtcHit(t, character, softmax(step, bestIndex))
                }
            }
            previous = bestIndex
        }
        return RecognizedStrip(hits, logits.size)
    }

    /** Confidence over the digits-and-blank alphabet, matching what the decode saw. */
    private fun softmax(step: FloatArray, index: Int): Float {
        var max = step[0]
        for (i in digitClasses) if (step[i] > max) max = step[i]
        var sum = exp((step[0] - max).toDouble())
        for (i in digitClasses) sum += exp((step[i] - max).toDouble())
        return (exp((step[index] - max).toDouble()) / sum).toFloat()
    }

    private fun resize(strip: Bitmap): Bitmap? {
        if (strip.width < 4 || strip.height < 4) return null
        val width = (strip.width * HEIGHT / strip.height).coerceAtLeast(16)
        if (width == strip.width && HEIGHT == strip.height) return strip
        return runCatching { Bitmap.createScaledBitmap(strip, width, HEIGHT, true) }.getOrNull()
    }

    private companion object {
        /** PP-OCR recognises a line at 48px tall, aspect preserved. */
        const val HEIGHT = 48
    }
}
