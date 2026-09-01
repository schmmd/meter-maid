package com.schmitztech.metermaid.ocr.ppocr

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Finds the register within the guide crop.
 *
 * The recognizer reads a line of text and degrades badly when the dial face, the needle
 * and "GALLON" are still in shot — measured, it goes from reading the register exactly
 * to returning half of it. Detection is what guarantees it sees only the register.
 *
 * PP-OCR's own post-processing fits polygons and unclips them with Vatti clipping. None
 * of that is needed here: there is one region of interest and an axis-aligned box around
 * the largest blob above threshold reproduces the library's answer closely enough to
 * read correctly on every frame tested.
 */
class RegisterDetector(private val models: PpOcrModels) {

    fun detect(source: Bitmap): Rect? {
        val scaled = scaleForDetection(source) ?: return null
        val probability = try {
            models.tensorOf(scaled).use { input ->
                models.detector.run(mapOf(models.detectorInput to input)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    (output[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MeterMaidOcr", "detector failed", e)
            return null
        } finally {
            if (scaled !== source) scaled.recycle()
        }

        val mapH = probability.size
        val mapW = probability[0].size
        var maxProb = 0f
        var above = 0
        for (row in probability) for (v in row) {
            if (v > maxProb) maxProb = v
            if (v > THRESHOLD) above++
        }
        val box = largestBlob(probability)
        android.util.Log.i(
            "MeterMaidOcr",
            "det map ${mapW}x${mapH} maxProb=$maxProb above=$above blob=$box",
        )
        if (box == null) {
            android.util.Log.w("MeterMaidOcr", "no blob above threshold in det map")
            return null
        }

        // DB is trained on shrunk regions, so the blob is tighter than the text. Grow it
        // back, more vertically than horizontally: clipping a digit's top or bottom costs
        // far more than a little extra background at the ends.
        val growX = (box.width() + 1) * GROW_X
        val growY = (box.height() + 1) * GROW_Y
        val scaleX = source.width.toFloat() / mapW
        val scaleY = source.height.toFloat() / mapH
        val mapped = Rect(
            ((box.left - growX) * scaleX).toInt().coerceIn(0, source.width - 1),
            ((box.top - growY) * scaleY).toInt().coerceIn(0, source.height - 1),
            ((box.right + growX) * scaleX).toInt().coerceIn(1, source.width),
            ((box.bottom + growY) * scaleY).toInt().coerceIn(1, source.height),
        )
        if (mapped.width() < 16 || mapped.height() < 8) {
            android.util.Log.w("MeterMaidOcr", "box too small after mapping: $mapped")
            return null
        }
        return mapped
    }

    /** Resize so the short side reaches [MIN_SIDE], with both sides a multiple of 32. */
    private fun scaleForDetection(source: Bitmap): Bitmap? {
        if (source.width < 8 || source.height < 8) return null
        val factor = MIN_SIDE.toFloat() / minOf(source.width, source.height)
        val w = (Math.round(source.width * factor / 32f) * 32).coerceAtLeast(32)
        val h = (Math.round(source.height * factor / 32f) * 32).coerceAtLeast(32)
        if (w == source.width && h == source.height) return source
        return runCatching { Bitmap.createScaledBitmap(source, w, h, true) }.getOrNull()
    }

    /**
     * Largest connected run of above-threshold pixels, by flood fill over an explicit
     * stack — recursion here would be a stack overflow on a large map.
     */
    private fun largestBlob(map: Array<FloatArray>): Rect? {
        val h = map.size
        val w = map[0].size
        val seen = BooleanArray(h * w)
        val stack = ArrayDeque<Int>()
        var best: Rect? = null
        var bestArea = 0

        for (start in 0 until h * w) {
            if (seen[start] || map[start / w][start % w] <= THRESHOLD) continue
            seen[start] = true
            stack.addLast(start)
            var left = start % w
            var right = left
            var top = start / w
            var bottom = top
            var area = 0

            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                val y = index / w
                val x = index % w
                area++
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y

                if (x > 0) push(map, seen, stack, y, x - 1, w)
                if (x < w - 1) push(map, seen, stack, y, x + 1, w)
                if (y > 0) push(map, seen, stack, y - 1, x, w)
                if (y < h - 1) push(map, seen, stack, y + 1, x, w)
            }

            if (area > bestArea) {
                bestArea = area
                best = Rect(left, top, right, bottom)
            }
        }
        return best
    }

    private fun push(
        map: Array<FloatArray>,
        seen: BooleanArray,
        stack: ArrayDeque<Int>,
        y: Int,
        x: Int,
        w: Int,
    ) {
        val index = y * w + x
        if (!seen[index] && map[y][x] > THRESHOLD) {
            seen[index] = true
            stack.addLast(index)
        }
    }

    private companion object {
        /** PP-OCR's own binarisation threshold. */
        const val THRESHOLD = 0.3f

        /**
         * Short side of the detector input. Measured on real frames, 320 and 736 both
         * read correctly and 480 does not; 320 is a quarter of the pixels of 736 and the
         * burst runs five frames.
         */
        const val MIN_SIDE = 320

        const val GROW_X = 0.06f
        const val GROW_Y = 0.25f
    }
}
