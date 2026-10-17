package com.pdfpaper.engine.geom

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal

/**
 * Axis-aligned rectangle in PDF user space with y growing up (ISO 32000-1,
 * §7.9.5 rectangle). The constructor normalises the corners, so [left] is
 * never greater than [right] no matter how the four numbers were ordered.
 *
 * Annotations (§12.5.2), widget rectangles (§12.7.4.3) and quad points
 * (§12.5.6.10) all reduce to this shape.
 */
class PdfRect(x0: Double, y0: Double, x1: Double, y1: Double) {

    val left: Double = minOf(x0, x1)
    val bottom: Double = minOf(y0, y1)
    val right: Double = maxOf(x0, x1)
    val top: Double = maxOf(y0, y1)

    val width: Double get() = right - left
    val height: Double get() = top - bottom

    /** True when the rectangle has no area (a flat or inverted box). */
    val isEmpty: Boolean get() = width <= 0.0 || height <= 0.0

    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (bottom + top) / 2.0

    fun contains(x: Double, y: Double): Boolean = x in left..right && y in bottom..top

    /** Smallest rectangle containing both; useful to build an annotation /Rect. */
    fun union(other: PdfRect): PdfRect = PdfRect(
        minOf(left, other.left),
        minOf(bottom, other.bottom),
        maxOf(right, other.right),
        maxOf(top, other.top)
    )

    override fun equals(other: Any?): Boolean = other is PdfRect &&
        left == other.left && bottom == other.bottom && right == other.right && top == other.top

    override fun hashCode(): Int {
        var h = left.hashCode()
        h = 31 * h + bottom.hashCode()
        h = 31 * h + right.hashCode()
        h = 31 * h + top.hashCode()
        return h
    }

    override fun toString(): String = "PdfRect($left,$bottom,$right,$top)"

    companion object {

        /** Reads a `[llx lly urx ury]` array (§7.9.5); null when unusable. */
        fun of(array: PdfArray?): PdfRect? {
            val items = array?.items ?: return null
            if (items.size < 4) return null
            val values = DoubleArray(4) { number(items[it]) ?: return null }
            if (values.any { !it.isFinite() }) return null
            return PdfRect(values[0], values[1], values[2], values[3])
        }

        /** Bounding rectangle of [rects]; null for an empty list. */
        fun union(rects: List<PdfRect>): PdfRect? {
            val first = rects.firstOrNull() ?: return null
            var result = first
            for (i in 1 until rects.size) result = result.union(rects[i])
            return result
        }

        private fun number(obj: PdfObject?): Double? = when (obj) {
            is PdfInteger -> obj.value.toDouble()
            is PdfReal -> obj.value
            else -> null
        }
    }
}
