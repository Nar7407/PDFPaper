package com.pdfpaper.convert.raster

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.graphics.ClosePath
import com.pdfpaper.engine.graphics.CurveTo
import com.pdfpaper.engine.graphics.LineTo
import com.pdfpaper.engine.graphics.MoveTo
import com.pdfpaper.engine.graphics.PdfPath
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Flattens a [PdfPath] into device-space polylines for the scanline filler.
 *
 * Curves are subdivided a fixed number of times derived from the length of
 * their control polygon, which is plenty for a background plate at the raster
 * resolutions we render. Subpaths are returned as flat `[x0,y0,x1,y1,…]`
 * arrays; a closed subpath repeats its first point so stroking has no gap.
 */
object PathFlattener {

    /** [path] transformed by [matrix]; each entry is one flat device-space subpath. */
    fun flatten(path: PdfPath, matrix: PdfMatrix, tolerance: Double = 0.35): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var current = ArrayList<Double>()
        var startX = 0.0
        var startY = 0.0
        var lastX = 0.0
        var lastY = 0.0
        var haveSubpath = false

        fun emit() {
            if (current.size >= 4) out += current.toDoubleArray()
            current = ArrayList()
            haveSubpath = false
        }

        fun move(x: Double, y: Double) {
            emit()
            current.add(x); current.add(y)
            startX = x; startY = y; lastX = x; lastY = y
            haveSubpath = true
        }

        fun line(x: Double, y: Double) {
            if (!haveSubpath) { move(x, y); return }
            current.add(x); current.add(y)
            lastX = x; lastY = y
        }

        for (segment in path.segments) {
            when (segment) {
                is MoveTo -> {
                    val p = matrix.apply(segment.x, segment.y)
                    move(p[0], p[1])
                }
                is LineTo -> {
                    val p = matrix.apply(segment.x, segment.y)
                    line(p[0], p[1])
                }
                is CurveTo -> {
                    val p1 = matrix.apply(segment.x1, segment.y1)
                    val p2 = matrix.apply(segment.x2, segment.y2)
                    val p3 = matrix.apply(segment.x3, segment.y3)
                    if (!haveSubpath) move(lastX, lastY)
                    val steps = curveSteps(lastX, lastY, p1[0], p1[1], p2[0], p2[1], p3[0], p3[1], tolerance)
                    for (i in 1..steps) {
                        val t = i.toDouble() / steps
                        current.add(cubic(lastX, p1[0], p2[0], p3[0], t))
                        current.add(cubic(lastY, p1[1], p2[1], p3[1], t))
                    }
                    lastX = p3[0]; lastY = p3[1]
                }
                is ClosePath -> {
                    if (haveSubpath && (abs(lastX - startX) > 1e-9 || abs(lastY - startY) > 1e-9)) {
                        current.add(startX); current.add(startY)
                    }
                    lastX = startX; lastY = startY
                }
            }
        }
        emit()
        return out
    }

    private fun cubic(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val u = 1.0 - t
        return u * u * u * p0 + 3.0 * u * u * t * p1 + 3.0 * u * t * t * p2 + t * t * t * p3
    }

    /** Step count from the control-polygon length, clamped to a sane range. */
    private fun curveSteps(
        x0: Double, y0: Double, x1: Double, y1: Double,
        x2: Double, y2: Double, x3: Double, y3: Double, tolerance: Double
    ): Int {
        val len = dist(x0, y0, x1, y1) + dist(x1, y1, x2, y2) + dist(x2, y2, x3, y3)
        val steps = ceil(len / max(tolerance, 1e-3) / 3.0).toInt()
        return min(64, max(2, steps))
    }

    private fun dist(x0: Double, y0: Double, x1: Double, y1: Double): Double =
        kotlin.math.hypot(x1 - x0, y1 - y0)
}
