package com.pdfpaper.engine.compress

import kotlin.math.roundToInt

/**
 * Small, dependency-free image helpers for the compressor: box-filter
 * downscaling and grey conversion on 32-bit RGBA buffers.
 *
 * The engine has no imaging library (and must not grow one), so the resample
 * is deliberately simple: every target pixel averages the source pixels of the
 * box it covers. That is enough to stop aliasing when an image is scaled down,
 * and it is deterministic, which keeps the compression reproducible.
 */
internal object ImageResampler {

    /**
     * Scales [rgba] down to [targetWidth] x [targetHeight] with a box filter.
     * Returns the input unchanged when it is already small enough.
     */
    fun downscale(
        width: Int,
        height: Int,
        rgba: ByteArray,
        targetWidth: Int,
        targetHeight: Int
    ): ByteArray {
        if (targetWidth >= width && targetHeight >= height) return rgba
        if (targetWidth < 1 || targetHeight < 1) return rgba
        if (rgba.size < width * height * 4) return rgba

        val out = ByteArray(targetWidth * targetHeight * 4)
        val xRatio = width.toDouble() / targetWidth
        val yRatio = height.toDouble() / targetHeight
        for (ty in 0 until targetHeight) {
            val y0 = (ty * yRatio).toInt().coerceIn(0, height - 1)
            val y1 = ((ty + 1) * yRatio).roundToInt().coerceIn(y0 + 1, height)
            for (tx in 0 until targetWidth) {
                val x0 = (tx * xRatio).toInt().coerceIn(0, width - 1)
                val x1 = ((tx + 1) * xRatio).roundToInt().coerceIn(x0 + 1, width)
                var r = 0L
                var g = 0L
                var b = 0L
                var a = 0L
                var count = 0
                for (y in y0 until y1) {
                    var index = (y * width + x0) * 4
                    for (x in x0 until x1) {
                        r += rgba[index].toInt() and 0xFF
                        g += rgba[index + 1].toInt() and 0xFF
                        b += rgba[index + 2].toInt() and 0xFF
                        a += rgba[index + 3].toInt() and 0xFF
                        index += 4
                        count++
                    }
                }
                if (count == 0) count = 1
                val target = (ty * targetWidth + tx) * 4
                out[target] = (r / count).toByte()
                out[target + 1] = (g / count).toByte()
                out[target + 2] = (b / count).toByte()
                out[target + 3] = (a / count).toByte()
            }
        }
        return out
    }

    /** Converts RGBA to grey (ITU-R BT.601 luma), keeping the alpha channel. */
    fun toGrayscale(rgba: ByteArray): ByteArray {
        val out = ByteArray(rgba.size)
        var i = 0
        while (i + 3 < rgba.size) {
            val r = rgba[i].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            out[i] = luma.toByte()
            out[i + 1] = luma.toByte()
            out[i + 2] = luma.toByte()
            out[i + 3] = rgba[i + 3]
            i += 4
        }
        return out
    }
}
