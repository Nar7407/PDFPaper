package com.pdfpaper.convert.raster

import java.util.Arrays

/**
 * An opaque ARGB (0xFFRRGGBB, row 0 first) pixel buffer.
 *
 * The page raster always starts as opaque white and every primitive composites
 * onto it, so the buffer never needs an alpha channel of its own. Image
 * transparency is folded into the source colour before blending.
 */
class Raster(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {

    init {
        require(width > 0 && height > 0) { "raster must be non-empty" }
        require(pixels.size == width * height) { "pixel count does not match size" }
    }

    fun fillWhite() = Arrays.fill(pixels, WHITE)

    fun fill(argb: Int) = Arrays.fill(pixels, argb)

    /**
     * Source-over blend of [r],[g],[b] at coverage [alpha] (0..1) onto pixel
     * ([x],[y]). Out-of-range coordinates are ignored, so callers can paint
     * spans without clamping first.
     */
    fun blend(x: Int, y: Int, r: Int, g: Int, b: Int, alpha: Double) {
        if (x < 0 || y < 0 || x >= width || y >= height || alpha <= 0.0) return
        val index = y * width + x
        if (alpha >= 1.0) {
            pixels[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            return
        }
        val dst = pixels[index]
        val inv = 1.0 - alpha
        val dr = (dst shr 16) and 0xFF
        val dg = (dst shr 8) and 0xFF
        val db = dst and 0xFF
        val nr = (r * alpha + dr * inv + 0.5).toInt().coerceIn(0, 255)
        val ng = (g * alpha + dg * inv + 0.5).toInt().coerceIn(0, 255)
        val nb = (b * alpha + db * inv + 0.5).toInt().coerceIn(0, 255)
        pixels[index] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
