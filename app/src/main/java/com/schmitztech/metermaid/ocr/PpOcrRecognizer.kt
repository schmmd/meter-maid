package com.schmitztech.metermaid.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.schmitztech.metermaid.ocr.ppocr.DigitRecognizer
import com.schmitztech.metermaid.ocr.ppocr.DrumAssignment
import com.schmitztech.metermaid.ocr.ppocr.PpOcrModels
import com.schmitztech.metermaid.ocr.ppocr.RegisterDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PP-OCR on device through ONNX Runtime: the v4 mobile detector and the v5 mobile recogniser.
 *
 * Chosen after measuring three engines against photographs of this meter. ML Kit read
 * `045694` as `lölASI6E`; Tesseract with a digit whitelist returned `560` from a clean,
 * isolated, upscaled register. PP-OCRv4 returned every digit from both frames.
 *
 * Per frame: find the register, read it as a line, then decide which characters are
 * drums. The last step is not optional — the black bars between drums come back as `1`s,
 * so the recognizer emits ten characters for a seven-digit face.
 */
class PpOcrRecognizer(context: Context) {

    private val appContext = context.applicationContext
    private val lazyModels = lazy {
        runCatching { PpOcrModels.load(appContext) }
            .onFailure { Log.w(TAG, "could not load the PP-OCR models", it) }
            .getOrNull()
    }
    private val models: PpOcrModels? by lazyModels
    private val detector: RegisterDetector? by lazy { models?.let(::RegisterDetector) }
    private val recognizer: DigitRecognizer? by lazy { models?.let(::DigitRecognizer) }

    /**
     * @param frames already cropped to the guide box, rotated upright and upscaled.
     * @param trailingZeros painted zeros on the face, which the recognizer sees but
     *   which never turn, so they are stripped rather than reported.
     * @return null when too few frames were legible to say anything honest.
     */
    suspend fun read(
        frames: List<Bitmap>,
        drumCount: Int,
        trailingZeros: Int,
    ): RecognizedReading? = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext null
        val detect = detector
        val recognise = recognizer
        if (detect == null || recognise == null) {
            Log.w(TAG, "models unavailable — see the load failure above")
            return@withContext null
        }

        val readings = frames.mapNotNull { frame ->
            val box = detect.detect(frame)
            if (box == null) {
                Log.w(TAG, "frame ${frame.width}x${frame.height}: no register detected")
                return@mapNotNull null
            }
            val strip = runCatching {
                Bitmap.createBitmap(frame, box.left, box.top, box.width(), box.height())
            }.getOrNull() ?: return@mapNotNull null

            val decoded = try {
                recognise.read(strip)
            } finally {
                if (strip !== frame) strip.recycle()
            }
            if (decoded == null) {
                Log.w(TAG, "frame: recognizer returned nothing for box $box")
                return@mapNotNull null
            }

            val drums = DrumAssignment.rotatingDrums(
                decoded.hits, decoded.timesteps, drumCount, trailingZeros,
            )
            Log.i(
                TAG,
                "box=$box strip=${box.width()}x${box.height()} T=${decoded.timesteps} " +
                    "raw='${decoded.raw}' drums='$drums'",
            )
            drums
        }

        Log.i(TAG, "burst: ${readings.size} of ${frames.size} frames usable $readings")
        Vote.tallyReadings(readings, frames.size, drumCount)
    }

    fun close() {
        // onCleared() calls this on the main thread. Touching a cold lazy here would
        // read ~15 MB of ONNX models off disk purely to close them — only sessions
        // that actually exist need freeing.
        if (lazyModels.isInitialized()) runCatching { models?.close() }
    }

    private companion object {
        const val TAG = "MeterMaidOcr"
    }
}
