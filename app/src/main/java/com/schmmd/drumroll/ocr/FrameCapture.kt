package com.schmmd.drumroll.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import com.schmmd.drumroll.data.Roi
import kotlin.math.roundToInt

/**
 * Turns a full preview frame into the thing the recognizer actually wants: just the
 * register, upright, at about twice its captured size.
 *
 * The upscale factor matters. Measured against photographs of this meter, 1.5–3x read
 * five of six drums correctly while 5x degraded to reading two. Bigger is not better.
 */
object FrameCapture {

    const val UPSCALE = 2.0f
    const val BUFFER_SIZE = 5

    /**
     * [source] is the analyzer buffer, straight off the sensor: landscape, and rotated
     * relative to a portrait-locked screen by [sensorRotationDegrees]. [viewAspect] is
     * the preview's width/height. Both are needed because the guide rectangle the user
     * aims with is drawn in *view* coordinates, and [roi] is a fraction of that view —
     * not of the buffer.
     */
    fun cropToRoi(
        source: Bitmap,
        roi: Roi,
        sensorRotationDegrees: Int = 0,
        viewAspect: Float = 0f,
    ): Bitmap? {
        val upright = upright(source, sensorRotationDegrees) ?: return null

        // PreviewView is FILL_CENTER: it scales the frame to *cover* the view and clips
        // the overflow, so what the user sees is a centred slice, not the whole frame.
        // The ROI has to be resolved inside that same slice.
        var visibleW = upright.width
        var visibleH = upright.height
        if (viewAspect > 0f) {
            val frameAspect = upright.width.toFloat() / upright.height
            if (frameAspect > viewAspect) {
                visibleW = (upright.height * viewAspect).roundToInt().coerceIn(1, upright.width)
            } else {
                visibleH = (upright.width / viewAspect).roundToInt().coerceIn(1, upright.height)
            }
        }
        val offsetX = (upright.width - visibleW) / 2
        val offsetY = (upright.height - visibleH) / 2

        val x = (offsetX + roi.left * visibleW).toInt().coerceIn(0, upright.width - 1)
        val y = (offsetY + roi.top * visibleH).toInt().coerceIn(0, upright.height - 1)
        val w = (roi.width * visibleW).toInt().coerceAtLeast(1).coerceAtMost(upright.width - x)
        val h = (roi.height * visibleH).toInt().coerceAtLeast(1).coerceAtMost(upright.height - y)
        if (w < 8 || h < 8) {
            if (upright !== source) upright.recycle()
            return null
        }

        val matrix = Matrix().apply {
            postRotate(roi.rotationDegrees.toFloat())
            postScale(UPSCALE, UPSCALE)
        }
        return Bitmap.createBitmap(upright, x, y, w, h, matrix, true)
            .also { if (upright !== source) upright.recycle() }
    }

    /** Turns the sensor buffer the right way up. Returns [source] itself when it already is. */
    private fun upright(source: Bitmap, degrees: Int): Bitmap? {
        val normalised = ((degrees % 360) + 360) % 360
        if (normalised == 0) return source
        val matrix = Matrix().apply { postRotate(normalised.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrNull()
    }
}

/** A fixed-size ring of the most recent frames, so the shutter has a burst to vote on. */
class FrameBuffer(private val capacity: Int = FrameCapture.BUFFER_SIZE) {
    private val frames = ArrayDeque<Bitmap>()

    @Synchronized
    fun offer(bitmap: Bitmap) {
        frames.addLast(bitmap)
        while (frames.size > capacity) frames.removeFirst().recycle()
    }

    @Synchronized
    fun snapshot(): List<Bitmap> = frames.toList()

    @Synchronized
    fun clear() {
        frames.forEach { it.recycle() }
        frames.clear()
    }
}
