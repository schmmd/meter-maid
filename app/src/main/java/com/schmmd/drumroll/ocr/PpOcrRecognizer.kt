package com.schmmd.drumroll.ocr

import android.content.Context
import android.graphics.Bitmap
import com.schmmd.drumroll.ocr.ppocr.DigitRecognizer
import com.schmmd.drumroll.ocr.ppocr.DrumAssignment
import com.schmmd.drumroll.ocr.ppocr.PpOcrModels
import com.schmmd.drumroll.ocr.ppocr.RegisterDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PP-OCRv4, on device, through ONNX Runtime.
 *
 * Chosen after measuring three engines against photographs of this meter. ML Kit read
 * `045694` as `lölASI6E`; Tesseract with a digit whitelist returned `560` from a clean,
 * isolated, upscaled register. PP-OCRv4 returned every digit from both frames.
 *
 * Per frame: find the register, read it as a line, then decide which characters are
 * drums. The last step is not optional — the black bars between drums come back as `1`s,
 * so the recognizer emits ten characters for a seven-digit face.
 */
class PpOcrRecognizer(context: Context) : MeterRecognizer {

    private val appContext = context.applicationContext
    private val models: PpOcrModels? by lazy {
        runCatching { PpOcrModels.load(appContext) }.getOrNull()
    }
    private val detector: RegisterDetector? by lazy { models?.let(::RegisterDetector) }
    private val recognizer: DigitRecognizer? by lazy { models?.let(::DigitRecognizer) }

    override suspend fun read(
        frames: List<Bitmap>,
        drumCount: Int,
        trailingZeros: Int,
    ): RecognizedReading? = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext null
        val detect = detector ?: return@withContext null
        val recognise = recognizer ?: return@withContext null

        // Every digit on the face, painted zeros included: the guide box always contains
        // them and the recognizer has no way to know they never turn.
        val cells = drumCount + trailingZeros

        val readings = frames.mapNotNull { frame ->
            val box = detect.detect(frame) ?: return@mapNotNull null
            val strip = runCatching {
                Bitmap.createBitmap(frame, box.left, box.top, box.width(), box.height())
            }.getOrNull() ?: return@mapNotNull null

            val decoded = try {
                recognise.read(strip)
            } finally {
                if (strip !== frame) strip.recycle()
            } ?: return@mapNotNull null

            DrumAssignment.assign(decoded.hits, decoded.timesteps, cells)
                ?.let { Vote.rotatingDrumsOf(it, drumCount, trailingZeros) }
        }

        Vote.tallyReadings(readings, frames.size, drumCount, engine = ENGINE)
    }

    fun close() {
        runCatching { models?.close() }
    }

    private companion object {
        const val ENGINE = "ppocrv4"
    }
}
