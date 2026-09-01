package com.schmmd.drumroll.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * On-device recognition via ML Kit's bundled Latin model.
 *
 * Deliberately does no preprocessing. Greyscale, contrast stretching, thresholding and
 * inversion were all tested against photographs of this meter and every one of them
 * made the recognizer return *nothing*: the register's leading drums are dark-on-light
 * and its trailing drums are light-on-dark, so any threshold that rescues one half
 * destroys the other. Cropping and a modest upscale are done by the caller.
 */
class MlKitRecognizer : MeterRecognizer {

    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun read(
        frames: List<Bitmap>,
        drumCount: Int,
        trailingZeros: Int,
    ): RecognizedReading? {
        if (frames.isEmpty()) return null
        val perFrame = frames.map { recognizeFragments(it) }
        return Vote.tally(perFrame, drumCount, trailingZeros)
    }

    private suspend fun recognizeFragments(bitmap: Bitmap): List<Fragment> =
        suspendCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            client.process(image)
                .addOnSuccessListener { text ->
                    val fragments = text.textBlocks
                        .asSequence()
                        .flatMap { it.lines.asSequence() }
                        .flatMap { it.elements.asSequence() }
                        .mapNotNull { element ->
                            val digits = element.text.filter(Char::isDigit)
                            val box = element.boundingBox
                            if (digits.isEmpty() || box == null) null
                            else Fragment(digits, box.left, box.height())
                        }
                        .toList()
                    cont.resume(fragments)
                }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    fun close() = client.close()
}
