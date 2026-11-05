package com.pdfpaper.engine.crop

import com.pdfpaper.engine.export.PageRaster
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.render.PageRenderPlan

/**
 * White-margin detection for auto-crop (task T46): scans a rendered page
 * raster and proposes the CropBox that would trim the empty border.
 *
 * Pure pixel maths, no platform code: the raster comes from whichever
 * [com.pdfpaper.engine.export.PageRasterizer] the host supplies (JVM
 * [com.pdfpaper.renderjvm.JvmPageRasterizer] for the CLI, the Android
 * rasterizer for the app), so the engine module itself stays portable.
 */
object CropDetection {

    /**
     * The four border sides of a raster in device pixels, in the order the
     * crop code uses for margins: left, top, right, bottom (y grows down in
     * device space).
     */
    class PixelBorders(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val isEmpty: Boolean get() = right < left || bottom < top
    }

    /**
     * Scans [raster] for content and returns the border margins, in device
     * pixels (the raster's origin is top-left, y growing down).
     *
     * A pixel counts as content when any of its RGB channels differs from
     * white (255) by more than [tolerance]; the alpha channel is ignored. A
     * fully white raster yields [PixelBorders.isEmpty].
     */
    fun findContentBorders(raster: PageRaster, tolerance: Int): PixelBorders? {
        val width = raster.width
        val height = raster.height
        if (width <= 0 || height <= 0) return null
        val data = raster.rgba
        val limit = tolerance.coerceIn(0, 255)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var row = 0
        while (row < height) {
            var hasContent = false
            val rowStart = row * width * 4
            var col = 0
            while (col < width) {
                val i = rowStart + col * 4
                // Raster is RGBA (device space): alpha is ignored, the page
                // background is white (§11.4.7).
                val r = data[i].toInt() and 0xFF
                val g = data[i + 1].toInt() and 0xFF
                val b = data[i + 2].toInt() and 0xFF
                if (255 - minOf(r, g, b) > limit) {
                    hasContent = true
                    if (col < minX) minX = col
                    if (col > maxX) maxX = col
                }
                col++
            }
            if (hasContent) {
                if (row < minY) minY = row
                if (row > maxY) maxY = row
            }
            row++
        }
        if (maxX < 0) return null
        // An inclusive pixel run is one pixel wider than the difference.
        return PixelBorders(minX, minY, maxX + 1, maxY + 1)
    }

    /**
     * Maps a device-pixel rectangle back to page user space (y up) through
     * [plan]'s matrix, which maps user space to device pixels. Corners are
     * mapped and bounded, so rotated and flipped pages come out right.
     */
    fun toUserRect(borders: PixelBorders, plan: PageRenderPlan): PdfRect? {
        val inverse: PdfMatrix = plan.matrix.inverse() ?: return null
        val corners = doubleArrayOf(
            borders.left.toDouble(), borders.top.toDouble(),
            borders.right.toDouble(), borders.bottom.toDouble()
        )
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until 4) {
            val x = corners[(i / 2) * 2]
            val y = corners[(i % 2) * 2 + 1]
            val p = inverse.apply(x, y)
            if (p[0] < minX) minX = p[0]
            if (p[0] > maxX) maxX = p[0]
            if (p[1] < minY) minY = p[1]
            if (p[1] > maxY) maxY = p[1]
        }
        if (!minX.isFinite() || !minY.isFinite()) return null
        return PdfRect(minX, minY, maxX, maxY)
    }
}
