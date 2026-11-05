package com.pdfpaper.engine.graphics

/**
 * Path geometry (§8.5.2): subpaths built from straight and cubic Bézier
 * segments in the user space current when the path operators ran. The display
 * list stores the raw geometry plus the transform, so a renderer can draw it
 * with its own stroker at full fidelity (stroke widths scale with the CTM).
 */
sealed interface PathSegment

/** `m` (§8.5.2.1): starts a new subpath. */
data class MoveTo(val x: Double, val y: Double) : PathSegment

/** `l` (§8.5.2.1). */
data class LineTo(val x: Double, val y: Double) : PathSegment

/** `c`/`v`/`y` (§8.5.2.2): two control points and the end point. */
data class CurveTo(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val x3: Double,
    val y3: Double
) : PathSegment

/** `h` (§8.5.2.1): closes the current subpath. */
object ClosePath : PathSegment

/** A sequence of [PathSegment]s; empty paths are never painted. */
class PdfPath(val segments: List<PathSegment>) {

    val isEmpty: Boolean get() = segments.isEmpty()

    fun bounds(): DoubleArray? {
        if (segments.isEmpty()) return null
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        fun include(x: Double, y: Double) {
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        for (segment in segments) {
            when (segment) {
                is MoveTo -> include(segment.x, segment.y)
                is LineTo -> include(segment.x, segment.y)
                is CurveTo -> {
                    include(segment.x1, segment.y1)
                    include(segment.x2, segment.y2)
                    include(segment.x3, segment.y3)
                }
                ClosePath -> {}
            }
        }
        return doubleArrayOf(minX, minY, maxX, maxY)
    }

    override fun toString(): String = "PdfPath(${segments.joinToString(", ")})"

    companion object {
        /** Closed rectangle path used by `re` (§8.5.2.1) and form /BBox clipping. */
        fun rectangle(x0: Double, y0: Double, x1: Double, y1: Double): PdfPath {
            val lowX = minOf(x0, x1)
            val lowY = minOf(y0, y1)
            val highX = maxOf(x0, x1)
            val highY = maxOf(y0, y1)
            return PdfPath(
                listOf(
                    MoveTo(lowX, lowY),
                    LineTo(highX, lowY),
                    LineTo(highX, highY),
                    LineTo(lowX, highY),
                    ClosePath
                )
            )
        }
    }
}
