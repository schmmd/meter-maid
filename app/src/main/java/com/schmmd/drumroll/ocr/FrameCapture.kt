package com.schmmd.drumroll.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import com.schmmd.drumroll.data.Roi

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

    fun cropToRoi(source: Bitmap, roi: Roi): Bitmap? {
        val x = (roi.left * source.width).toInt().coerceIn(0, source.width - 1)
        val y = (roi.top * source.height).toInt().coerceIn(0, source.height - 1)
        val w = (roi.width * source.width).toInt().coerceAtLeast(1)
            .coerceAtMost(source.width - x)
        val h = (roi.height * source.height).toInt().coerceAtLeast(1)
            .coerceAtMost(source.height - y)
        if (w < 8 || h < 8) return null

        val matrix = Matrix().apply {
            postRotate(roi.rotationDegrees.toFloat())
            postScale(UPSCALE, UPSCALE)
        }
        return Bitmap.createBitmap(source, x, y, w, h, matrix, true)
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
