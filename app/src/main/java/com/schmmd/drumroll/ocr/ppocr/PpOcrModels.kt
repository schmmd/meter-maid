package com.schmmd.drumroll.ocr.ppocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer

/**
 * The two PP-OCRv4 models, loaded from assets and kept for the life of the recognizer.
 *
 * Both take the same normalisation — (x/255 - 0.5) / 0.5, channels-first — and the
 * recogniser carries its own character table in the ONNX metadata rather than a
 * side-file, so nothing else has to be shipped alongside them.
 */
class PpOcrModels private constructor(
    private val env: OrtEnvironment,
    val detector: OrtSession,
    val recognizer: OrtSession,
    val charset: List<String>,
) : AutoCloseable {

    val detectorInput: String = detector.inputNames.first()
    val recognizerInput: String = recognizer.inputNames.first()

    fun tensorOf(bitmap: Bitmap): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        // NCHW: every red, then every green, then every blue.
        val buffer = FloatBuffer.allocate(3 * h * w)
        val plane = h * w
        for (c in 0 until 3) {
            val shift = 16 - c * 8 // red, green, blue
            for (i in 0 until plane) {
                val channel = (pixels[i] shr shift) and 0xFF
                buffer.put(c * plane + i, (channel / 255f - 0.5f) / 0.5f)
            }
        }
        buffer.rewind()
        return OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, h.toLong(), w.toLong()))
    }

    override fun close() {
        runCatching { detector.close() }
        runCatching { recognizer.close() }
    }

    companion object {
        private const val DET_ASSET = "ppocr_det.onnx"
        private const val REC_ASSET = "ppocr_rec.onnx"

        fun load(context: Context): PpOcrModels {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            val det = env.createSession(context.assets.open(DET_ASSET).use { it.readBytes() }, options)
            val rec = env.createSession(context.assets.open(REC_ASSET).use { it.readBytes() }, options)
            android.util.Log.i(
                "DrumrollOcr",
                "models loaded det_in=${det.inputNames} det_out=${det.outputInfo.keys} " +
                    "rec_in=${rec.inputNames} rec_out=${rec.outputInfo.keys}",
            )
            // Index 0 is the CTC blank; the trailing space is PaddleOCR's convention.
            val chars = rec.metadata.customMetadata["character"]
                ?.split("\n")
                ?.filter { it.isNotEmpty() || true }
                .orEmpty()
            val charset = buildList {
                add("")
                addAll(chars)
                add(" ")
            }
            return PpOcrModels(env, det, rec, charset)
        }
    }
}
