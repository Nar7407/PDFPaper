package com.pdfpaper.engine.eink

import kotlin.math.roundToInt

/**
 * Task T63: the pixel work of the E-Ink profiles.
 *
 * Everything here is pure Kotlin over an ARGB buffer, so a profile is applied
 * by a host exactly the same way on a phone, an emulator or a JVM test. The
 * buffer is modified in place; the alpha byte is carried through, because a
 * picture drawn with a soft mask must keep its shape.
 */
object EinkPipeline {

    /** A 4x4 Bayer matrix, the classic ordered-dither pattern. */
    private val BAYER = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5
    )

    /**
     * Applies [settings] to a whole page buffer: the grey curve of profile 1,
     * the threshold of profile 3. Profile 2 is handled while drawing (text
     * anti-aliasing off, pictures dithered), so it has nothing left to do here.
     */
    fun apply(pixels: IntArray, settings: EinkSettings) {
        if (!settings.active) return
        when (settings.profile) {
            EinkProfile.GRAYSCALE -> grayscale(pixels, settings)
            EinkProfile.FULL_BITONAL -> binarize(pixels, settings)
            EinkProfile.TEXT_BITONAL, EinkProfile.OFF -> Unit
        }
    }

    /** Profile 1: one grey channel, stretched around the reader's threshold. */
    fun grayscale(pixels: IntArray, settings: EinkSettings) {
        val out = settings.normalized()
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val l = luminance(pixel)
            val g = (out.grayscaleValue(l) * 255.0).roundToInt().coerceIn(0, 255)
            pixels[i] = (pixel and ALPHA_MASK) or (g shl 16) or (g shl 8) or g
        }
    }

    /**
     * Profile 3: every pixel is pure black or pure white at the threshold.
     * There is no anti-aliasing left anywhere, which is exactly what makes the
     * output stable on a 1-bit panel.
     */
    fun binarize(pixels: IntArray, settings: EinkSettings) {
        val out = settings.normalized()
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val l = luminance(pixel)
            pixels[i] = (pixel and ALPHA_MASK) or if (out.isBlack(l)) BLACK_RGB else WHITE_RGB
        }
    }

    /**
     * Profile 2: reduces one picture to black and white with [EinkSettings.dither].
     * Called by the renderer on the image's own pixels, before it is drawn, so
     * a photograph is dithered while the text around it stays crisp.
     */
    fun ditherImage(pixels: IntArray, width: Int, height: Int, settings: EinkSettings) {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return
        when (settings.dither) {
            EinkDither.ORDERED -> ordered(pixels, width, height, settings.normalized())
            EinkDither.FLOYD_STEINBERG -> floydSteinberg(pixels, width, height, settings.normalized())
        }
    }

    /** Ordered dithering against the 4x4 Bayer matrix. */
    private fun ordered(pixels: IntArray, width: Int, height: Int, settings: EinkSettings) {
        val scale = (settings.threshold * 2.0).coerceIn(0.05, 1.9)
        for (y in 0 until height) {
            val row = y and 3
            for (x in 0 until width) {
                val i = y * width + x
                val pixel = pixels[i]
                val l = luminance(pixel)
                val cut = ((BAYER[row * 4 + (x and 3)] + 0.5) / 16.0) * scale
                pixels[i] = (pixel and ALPHA_MASK) or if (l < cut) BLACK_RGB else WHITE_RGB
            }
        }
    }

    /**
     * Floyd–Steinberg error diffusion with two row buffers: the error is spread
     * 7/16 to the next pixel, 3/16 down-left, 5/16 down and 1/16 down-right.
     * Two DoubleArrays keep a full-page photo at O(width), not O(width*height).
     */
    private fun floydSteinberg(pixels: IntArray, width: Int, height: Int, settings: EinkSettings) {
        val threshold = settings.threshold
        var current = DoubleArray(width + 2)
        var next = DoubleArray(width + 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val pixel = pixels[i]
                val value = luminance(pixel) + current[x + 1]
                val black = value < threshold
                pixels[i] = (pixel and ALPHA_MASK) or if (black) BLACK_RGB else WHITE_RGB
                val error = value - if (black) 0.0 else 1.0
                current[x + 2] += error * 7.0 / 16.0
                next[x] += error * 3.0 / 16.0
                next[x + 1] += error * 5.0 / 16.0
                next[x + 2] += error * 1.0 / 16.0
            }
            val swap = current
            current = next
            next = swap
            next.fill(0.0)
        }
    }

    private fun luminance(pixel: Int): Double =
        (0.2126 * ((pixel shr 16) and 0xFF) +
            0.7152 * ((pixel shr 8) and 0xFF) +
            0.0722 * (pixel and 0xFF)) / 255.0

    private const val BLACK_RGB = 0x000000
    private const val WHITE_RGB = 0xFFFFFF
    private const val ALPHA_MASK = 0xFF000000.toInt()
}
