package com.pdfpaper.app.ui.crop

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.mark.PageSelection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The pure geometry and page-selection helpers of the crop screen (T46).
 *
 * Everything here is plain Kotlin so the handle maths, margin presets and
 * screen-to-page mapping can be unit-tested without an Android device. The
 * Compose code only turns these values into pixels.
 */

/** Which pages the crop screen changes. */
enum class CropPageMode(val label: String) {
    ALL("All pages"),
    ODD("Odd pages"),
    EVEN("Even pages"),
    CUSTOM("Custom")
}

/** The page selection the engine should receive; zero-based inside. */
fun parseCropPages(mode: CropPageMode, customText: String): PageSelection = when (mode) {
    CropPageMode.ALL -> PageSelection.All
    CropPageMode.ODD -> PageSelection.Odd
    CropPageMode.EVEN -> PageSelection.Even
    CropPageMode.CUSTOM -> {
        val trimmed = customText.trim()
        if (trimmed.isEmpty()) PageSelection.Custom(emptySet())
        else PageSelection.parse(trimmed)
    }
}

/** The four crop margins in points, measured inward from the page's CropBox. */
data class CropMargins(
    val left: Double,
    val bottom: Double,
    val right: Double,
    val top: Double
) {
    val isZero: Boolean
        get() = left <= 0.0 && bottom <= 0.0 && right <= 0.0 && top <= 0.0

    /** "10 · 10 · 10 · 10 pt"; one decimal only when needed. */
    fun describe(): String =
        listOf(left, bottom, right, top).joinToString(" · ") { formatPoints(it) } + " pt"

    companion object {
        val ZERO = CropMargins(0.0, 0.0, 0.0, 0.0)

        fun uniform(value: Double): CropMargins =
            CropMargins(value, value, value, value)
    }
}

/**
 * The new crop box for [base] after removing [margins]. Null when the margins
 * are negative, not finite, or leave less than one point on a side.
 *
 * [base] is the page's current CropBox, matching the engine: a second crop
 * trims the already visible page rather than silently revealing the MediaBox.
 */
fun cropBoxFromMargins(base: PdfRect, margins: CropMargins): PdfRect? {
    val values = doubleArrayOf(margins.left, margins.bottom, margins.right, margins.top)
    if (values.any { !it.isFinite() || it < 0.0 }) return null
    val left = base.left + margins.left
    val bottom = base.bottom + margins.bottom
    val right = base.right - margins.right
    val top = base.top - margins.top
    if (right - left < 1.0 || top - bottom < 1.0) return null
    return PdfRect(left, bottom, right, top)
}

/** The margins that turn [base] into [box]; null when [box] is not inside it. */
fun marginsFor(base: PdfRect, box: PdfRect): CropMargins? {
    val left = box.left - base.left
    val bottom = box.bottom - base.bottom
    val right = base.right - box.right
    val top = base.top - box.top
    val values = doubleArrayOf(left, bottom, right, top)
    if (values.any { !it.isFinite() || it < -0.01 }) return null
    if (box.width < 1.0 || box.height < 1.0) return null
    return CropMargins(max(0.0, left), max(0.0, bottom), max(0.0, right), max(0.0, top))
}

/** The four draggable corners, named in unrotated PDF user space. */
enum class CropHandle { LEFT_BOTTOM, RIGHT_BOTTOM, RIGHT_TOP, LEFT_TOP }

/**
 * Moves one corner of [box] to [x]/[y] (PDF user space), keeping the box inside
 * [bounds] and at least [minSize] points wide and high. Other corners stay put.
 * The result is normalised for a rotated page because the handle is identified
 * in user space, not in screen space.
 */
fun dragCrop(
    box: PdfRect,
    handle: CropHandle,
    x: Double,
    y: Double,
    bounds: PdfRect,
    minSize: Double = 8.0
): PdfRect {
    if (!x.isFinite() || !y.isFinite()) return box
    if (bounds.width <= 0.0 || bounds.height <= 0.0) return box
    val minimum = min(minSize, min(bounds.width, bounds.height))
    var left = max(box.left, bounds.left)
    var bottom = max(box.bottom, bounds.bottom)
    var right = min(box.right, bounds.right)
    var top = min(box.top, bounds.top)
    if (right - left < minimum) {
        right = min(bounds.right, left + minimum)
        left = max(bounds.left, right - minimum)
    }
    if (top - bottom < minimum) {
        top = min(bounds.top, bottom + minimum)
        bottom = max(bounds.bottom, top - minimum)
    }
    val px = x.coerceIn(bounds.left, bounds.right)
    val py = y.coerceIn(bounds.bottom, bounds.top)
    when (handle) {
        CropHandle.LEFT_BOTTOM -> {
            left = clamp(px, bounds.left, right - minimum)
            bottom = clamp(py, bounds.bottom, top - minimum)
        }
        CropHandle.RIGHT_BOTTOM -> {
            right = clamp(px, left + minimum, bounds.right)
            bottom = clamp(py, bounds.bottom, top - minimum)
        }
        CropHandle.RIGHT_TOP -> {
            right = clamp(px, left + minimum, bounds.right)
            top = clamp(py, bottom + minimum, bounds.top)
        }
        CropHandle.LEFT_TOP -> {
            left = clamp(px, bounds.left, right - minimum)
            top = clamp(py, bottom + minimum, bounds.top)
        }
    }
    return PdfRect(left, bottom, right, top)
}

/** Canvas-space rectangle; y grows down, like Compose's Canvas. */
data class DeviceRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
}

/** Maps a PDF user-space rectangle through [matrix] and bounds the corners. */
fun deviceRect(rect: PdfRect, matrix: PdfMatrix): DeviceRect {
    val a = matrix.apply(rect.left, rect.bottom)
    val b = matrix.apply(rect.right, rect.bottom)
    val c = matrix.apply(rect.right, rect.top)
    val d = matrix.apply(rect.left, rect.top)
    return DeviceRect(
        left = min(min(a[0], b[0]), min(c[0], d[0])),
        top = min(min(a[1], b[1]), min(c[1], d[1])),
        right = max(max(a[0], b[0]), max(c[0], d[0])),
        bottom = max(max(a[1], b[1]), max(c[1], d[1]))
    )
}

/**
 * How a rendered bitmap is placed inside the preview canvas: centred with
 * [scale] applied, [left]/[top] in canvas pixels.
 */
data class CropFit(
    val scale: Double,
    val left: Double,
    val top: Double
) {
    fun toCanvas(device: DeviceRect): DeviceRect = DeviceRect(
        left + device.left * scale,
        top + device.top * scale,
        left + device.right * scale,
        top + device.bottom * scale
    )
}

/** Contains [bitmapWidth] × [bitmapHeight] inside the canvas, preserving aspect. */
fun fitBitmap(bitmapWidth: Int, bitmapHeight: Int, canvasWidth: Double, canvasHeight: Double): CropFit {
    if (bitmapWidth <= 0 || bitmapHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
        return CropFit(1.0, 0.0, 0.0)
    }
    val scale = min(canvasWidth / bitmapWidth, canvasHeight / bitmapHeight)
    return CropFit(
        scale = scale,
        left = (canvasWidth - bitmapWidth * scale) / 2.0,
        top = (canvasHeight - bitmapHeight * scale) / 2.0
    )
}

/** Canvas point → PDF user point, using the render plan's inverse matrix. */
fun canvasToUser(x: Double, y: Double, fit: CropFit, planMatrix: PdfMatrix): Pair<Double, Double>? {
    if (fit.scale <= 0.0) return null
    val inverse = planMatrix.inverse() ?: return null
    val px = (x - fit.left) / fit.scale
    val py = (y - fit.top) / fit.scale
    val user = inverse.apply(px, py)
    if (!user[0].isFinite() || !user[1].isFinite()) return null
    return user[0] to user[1]
}

/** One corner of the crop box in user space, transformed to canvas pixels. */
fun canvasCorner(box: PdfRect, handle: CropHandle, matrix: PdfMatrix, fit: CropFit): Pair<Double, Double> {
    val x: Double
    val y: Double
    when (handle) {
        CropHandle.LEFT_BOTTOM -> {
            x = box.left
            y = box.bottom
        }
        CropHandle.RIGHT_BOTTOM -> {
            x = box.right
            y = box.bottom
        }
        CropHandle.RIGHT_TOP -> {
            x = box.right
            y = box.top
        }
        CropHandle.LEFT_TOP -> {
            x = box.left
            y = box.top
        }
    }
    val device = matrix.apply(x, y)
    return fit.left + device[0] * fit.scale to fit.top + device[1] * fit.scale
}

/** An integer pixel rectangle inside a rendered bitmap. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * The bitmap pixels covered by [box], clamped to the bitmap. Used by the
 * "After" thumbnail, which draws only the new crop out of the full page render.
 */
fun sourcePixels(box: PdfRect, matrix: PdfMatrix, bitmapWidth: Int, bitmapHeight: Int): PixelRect {
    val device = deviceRect(box, matrix)
    val left = floor(device.left).toInt().coerceIn(0, max(0, bitmapWidth - 1))
    val top = floor(device.top).toInt().coerceIn(0, max(0, bitmapHeight - 1))
    val right = ceil(device.right).toInt().coerceIn(left + 1, max(left + 1, bitmapWidth))
    val bottom = ceil(device.bottom).toInt().coerceIn(top + 1, max(top + 1, bitmapHeight))
    return PixelRect(left, top, right, bottom)
}

/** "report.pdf" → "report-cropped.pdf"; a blank name becomes "cropped.pdf". */
fun suggestedCropName(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "cropped.pdf"
    val stem = if (trimmed.endsWith(".pdf", ignoreCase = true)) {
        trimmed.dropLast(4)
    } else {
        trimmed.substringBeforeLast('.', trimmed)
    }
    return if (stem.isBlank()) "cropped.pdf" else "$stem-cropped.pdf"
}

/** "No margins" or "10 · 10 · 10 · 10 pt". */
fun describeCropMargins(margins: CropMargins): String =
    if (margins.isZero) "No margins" else margins.describe()

internal fun formatPoints(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(java.util.Locale.US, "%.1f", value)

private fun clamp(value: Double, low: Double, high: Double): Double =
    if (high < low) low else value.coerceIn(low, high)
