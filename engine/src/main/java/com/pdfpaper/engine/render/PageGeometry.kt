package com.pdfpaper.engine.render

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.page.PdfPage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One page's box and rotation as needed for layout (§7.7.3.3, §14.11.2).
 * [left]..[top] is the crop box in PDF user space (y up, un-rotated).
 */
class PageBox(
    val left: Double,
    val bottom: Double,
    val right: Double,
    val top: Double,
    /** /Rotate normalized to 0/90/180/270, clockwise (§14.11.2). */
    val rotate: Int
) {
    val width: Double get() = right - left
    val height: Double get() = top - bottom

    /** Width after applying /Rotate, i.e. the on-screen width. */
    val rotatedWidth: Double get() = if (rotate % 180 == 0) width else height

    /** Height after applying /Rotate, i.e. the on-screen height. */
    val rotatedHeight: Double get() = if (rotate % 180 == 0) height else width
}

/**
 * Bitmap layout for one page at a zoom. [matrix] maps crop-box user space to
 * bitmap pixels with the origin at the top-left corner and y growing down, so
 * a renderer can set it once and then replay the display list.
 */
class PageRenderPlan(
    val box: PageBox,
    /** Zoom actually used (PDF user unit → pixel); may be below the request. */
    val scale: Double,
    val pixelWidth: Int,
    val pixelHeight: Int,
    /** True when [scale] was reduced to respect the pixel budget. */
    val downscaled: Boolean,
    val matrix: PdfMatrix
)

/**
 * Page-box extraction and raster layout maths (ISO 32000-1, §7.7.3.3 crop
 * box, §14.11.2 page rotation and display). Pure geometry so it is testable
 * without a platform.
 */
object PageGeometry {

    /** Default pixel budget per page (~12 MP ≈ 48 MB RGBA, §8.9.5.4 guard spirit). */
    const val DEFAULT_MAX_PIXELS: Int = 12_000_000

    /** US Letter fallback when a page has no usable box at all. */
    const val DEFAULT_WIDTH: Double = 612.0
    const val DEFAULT_HEIGHT: Double = 792.0

    /** The page's crop box (falls back to media box, then Letter, §7.7.3.3). */
    fun boxOf(page: PdfPage): PageBox {
        val raw = numbers(page.cropBox)
        if (raw.size < 4 || raw.any { !it.isFinite() }) {
            return PageBox(0.0, 0.0, DEFAULT_WIDTH, DEFAULT_HEIGHT, page.rotate)
        }
        val x0 = min(raw[0], raw[2])
        val x1 = max(raw[0], raw[2])
        val y0 = min(raw[1], raw[3])
        val y1 = max(raw[1], raw[3])
        if (x1 - x0 <= 0.0 || y1 - y0 <= 0.0) {
            return PageBox(0.0, 0.0, DEFAULT_WIDTH, DEFAULT_HEIGHT, page.rotate)
        }
        return PageBox(x0, y0, x1, y1, page.rotate)
    }

    /** Zoom that makes the rotated page [pixelWidth] pixels wide. */
    fun scaleForPixelWidth(box: PageBox, pixelWidth: Int): Double =
        if (box.rotatedWidth <= 0.0) 1.0 else pixelWidth / box.rotatedWidth

    /**
     * Pixel size + user-space-to-pixel matrix for [box] at [scale]. The zoom
     * is reduced when the result would exceed [maxPixels] pixels so a viewer
     * can never blow up memory on one page (the caller sees [PageRenderPlan.downscaled]).
     */
    fun plan(box: PageBox, scale: Double, maxPixels: Int = DEFAULT_MAX_PIXELS): PageRenderPlan {
        var s = if (scale.isFinite() && scale > 0.0) scale else 1.0
        val rotatedWidth = max(box.rotatedWidth, 1e-9)
        val rotatedHeight = max(box.rotatedHeight, 1e-9)
        var downscaled = false
        val budget = if (maxPixels > 0) maxPixels.toDouble() else Double.MAX_VALUE
        val requested = rotatedWidth * s * rotatedHeight * s
        if (requested > budget) {
            s *= sqrt(budget / requested)
            downscaled = true
        }
        val pixelWidth = max(1, (rotatedWidth * s).roundToInt())
        val pixelHeight = max(1, (rotatedHeight * s).roundToInt())
        return PageRenderPlan(box, s, pixelWidth, pixelHeight, downscaled, matrixFor(box, s, s))
    }

    /**
     * Crop box → device matrix: translate the box origin, flip y (device y
     * grows down) and apply /Rotate clockwise (§14.11.2). [scaleX]/[scaleY]
     * are equal for the normal case; both exist so a caller can render at a
     * non-square zoom without rebuilding the maths.
     */
    fun matrixFor(box: PageBox, scaleX: Double, scaleY: Double): PdfMatrix {
        // Un-rotated device space: (0,0) top-left, y down, size width×height.
        val base = PdfMatrix.scaling(scaleX, -scaleY) *
            PdfMatrix.translation(-box.left, -(box.bottom + box.height))
        val w = box.width * scaleX
        val h = box.height * scaleY
        val rotation = when (box.rotate) {
            // (X, Y) → (H - Y, X): the PDF top-left corner becomes device top-right.
            90 -> PdfMatrix(0.0, 1.0, -1.0, 0.0, h, 0.0)
            // (X, Y) → (W - X, H - Y).
            180 -> PdfMatrix(-1.0, 0.0, 0.0, -1.0, w, h)
            // (X, Y) → (Y, W - X).
            270 -> PdfMatrix(0.0, -1.0, 1.0, 0.0, 0.0, w)
            else -> PdfMatrix.IDENTITY
        }
        return rotation * base
    }

    /** Crop box corner order tolerated: any two opposite corners (§7.9.5). */
    private fun numbers(array: PdfArray?): DoubleArray {
        val items = array?.items ?: return DoubleArray(0)
        return DoubleArray(items.size) { i -> number(items[i]) ?: Double.NaN }
    }

    private fun number(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }

    /** True when the box is usable (all four values finite, positive area). */
    fun isUsable(box: PageBox): Boolean =
        box.width.isFinite() && box.height.isFinite() && abs(box.width) > 0.0 && abs(box.height) > 0.0
}
