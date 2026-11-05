package com.pdfpaper.app.ui.viewer.ink

import com.pdfpaper.engine.geom.PdfRect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure geometry for the T54 ink tools. Nothing here touches Android, so the
 * simplification, curve fitting, hit-testing, lasso and shape-snap rules are
 * unit-tested on the JVM.
 */
object InkGeometry {

    /** Removes consecutive duplicates and non-finite samples. */
    fun clean(samples: List<InkSample>): List<InkSample> {
        val out = ArrayList<InkSample>(samples.size)
        for (sample in samples) {
            if (!sample.x.isFinite() || !sample.y.isFinite()) continue
            val previous = out.lastOrNull()
            if (previous != null && hypot(sample.x - previous.x, sample.y - previous.y) < 1e-6) {
                continue
            }
            out += sample.copy(
                pressure = sample.pressure.coerceIn(0.0, 1.0),
                tilt = sample.tilt.coerceIn(0.0, 90.0)
            )
        }
        return out
    }

    /**
     * Catmull-Rom curve fitting. It passes through every sampled point but
     * produces a visibly smoother path than straight `lineTo` segments. Pressure
     * and tilt are interpolated linearly so the width follows the curve.
     */
    fun catmullRom(samples: List<InkSample>, subdivisions: Int = 3): List<InkSample> =
        catmullRomClean(clean(samples), subdivisions)

    private fun catmullRomClean(points: List<InkSample>, subdivisions: Int): List<InkSample> {
        if (points.size < 3 || subdivisions < 1) return points
        val out = ArrayList<InkSample>((points.size - 1) * subdivisions + 1)
        for (i in 0 until points.size - 1) {
            val p0 = points[if (i == 0) 0 else i - 1]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[if (i + 2 < points.size) i + 2 else points.size - 1]
            for (step in 0 until subdivisions) {
                val t = step.toDouble() / subdivisions
                out += interpolate(p0, p1, p2, p3, t)
            }
        }
        out += points.last()
        return out
    }

    private fun interpolate(
        p0: InkSample,
        p1: InkSample,
        p2: InkSample,
        p3: InkSample,
        t: Double
    ): InkSample {
        val t2 = t * t
        val t3 = t2 * t
        fun value(a: Double, b: Double, c: Double, d: Double): Double =
            0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 +
                (-a + 3 * b - 3 * c + d) * t3)
        return InkSample(
            x = value(p0.x, p1.x, p2.x, p3.x),
            y = value(p0.y, p1.y, p2.y, p3.y),
            pressure = p1.pressure + (p2.pressure - p1.pressure) * t,
            tilt = p1.tilt + (p2.tilt - p1.tilt) * t
        )
    }

    /**
     * Ramer–Douglas–Peucker simplification. It keeps endpoints and whichever
     * interior points are farther than [epsilon] from the chord, so the saved
     * stroke stays small without visibly changing the line.
     */
    fun simplify(samples: List<InkSample>, epsilon: Double = 0.65): List<InkSample> =
        simplifyClean(clean(samples), epsilon)

    private fun simplifyClean(points: List<InkSample>, epsilon: Double): List<InkSample> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Int>()
        stack.addLast(0)
        stack.addLast(points.size - 1)
        val tolerance = max(0.01, epsilon)
        val toleranceSquared = tolerance * tolerance
        while (stack.isNotEmpty()) {
            val end = stack.removeLast()
            val start = stack.removeLast()
            if (end <= start + 1) continue
            val ax = points[start].x
            val ay = points[start].y
            val bx = points[end].x
            val by = points[end].y
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            var index = -1
            var farthestSquared = toleranceSquared
            for (i in start + 1 until end) {
                val px = points[i].x
                val py = points[i].y
                val distanceSquared = if (lengthSquared < 1e-18) {
                    val ex = px - ax
                    val ey = py - ay
                    ex * ex + ey * ey
                } else {
                    val cross = dy * px - dx * py + bx * ay - by * ax
                    cross * cross / lengthSquared
                }
                if (distanceSquared > farthestSquared) {
                    farthestSquared = distanceSquared
                    index = i
                }
            }
            if (index >= 0) {
                keep[index] = true
                stack.addLast(start)
                stack.addLast(index)
                stack.addLast(index)
                stack.addLast(end)
            }
        }
        return points.filterIndexed { index, _ -> keep[index] }
    }

    /** Curve-fit and then simplify; this is what a finished stroke stores. */
    fun forSaving(samples: List<InkSample>, epsilon: Double = 0.65): List<InkSample> {
        // A coarse pass first keeps a very long stylus stroke cheap: the raw
        // sample stream can hold hundreds of points at 240 Hz, while the fitted
        // curve only needs the points that shape it. The coarse tolerance is
        // half the final one, so the second pass still owns the visible result.
        val coarse = simplifyClean(clean(samples), epsilon * 0.5)
        if (coarse.size < 3) return coarse
        return simplifyClean(catmullRomClean(coarse, subdivisions = 2), epsilon)
    }

    private fun perpendicularDistance(point: InkSample, a: InkSample, b: InkSample): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = hypot(dx, dy)
        if (length < 1e-9) return hypot(point.x - a.x, point.y - a.y)
        return abs(dy * point.x - dx * point.y + b.x * a.y - b.y * a.x) / length
    }

    fun pathLength(samples: List<InkSample>): Double {
        if (samples.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until samples.size - 1) {
            total += hypot(samples[i + 1].x - samples[i].x, samples[i + 1].y - samples[i].y)
        }
        return total
    }

    fun bounds(samples: List<InkSample>, width: Double = 0.0): PdfRect? {
        if (samples.isEmpty()) return null
        var left = Double.MAX_VALUE
        var bottom = Double.MAX_VALUE
        var right = -Double.MAX_VALUE
        var top = -Double.MAX_VALUE
        for (sample in samples) {
            left = min(left, sample.x)
            bottom = min(bottom, sample.y)
            right = max(right, sample.x)
            top = max(top, sample.y)
        }
        val pad = width / 2.0
        return PdfRect(left - pad, bottom - pad, right + pad, top + pad)
    }

    fun distanceToStroke(stroke: InkStroke, x: Double, y: Double): Double {
        val samples = stroke.samples
        if (samples.isEmpty()) return Double.MAX_VALUE
        if (samples.size == 1) return hypot(x - samples[0].x, y - samples[0].y)
        var best = Double.MAX_VALUE
        for (i in 0 until samples.size - 1) {
            best = min(best, distanceToSegment(x, y, samples[i], samples[i + 1]))
        }
        return best
    }

    fun distanceToSegment(
        x: Double,
        y: Double,
        a: InkSample,
        b: InkSample
    ): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared < 1e-12) return hypot(x - a.x, y - a.y)
        val t = (((x - a.x) * dx + (y - a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(x - (a.x + t * dx), y - (a.y + t * dy))
    }

    /** True when [stroke] comes within [tolerance] of any point of [path]. */
    fun strokeHitsPath(
        stroke: InkStroke,
        path: List<InkSample>,
        tolerance: Double
    ): Boolean {
        if (path.isEmpty()) return false
        for (point in path) {
            if (distanceToStroke(stroke, point.x, point.y) <= tolerance + stroke.width / 2.0) {
                return true
            }
        }
        // Also catch a very short path that sits between two samples.
        for (sample in stroke.samples) {
            for (i in 0 until path.size - 1) {
                if (distanceToSegment(sample.x, sample.y, path[i], path[i + 1]) <=
                    tolerance + stroke.width / 2.0
                ) {
                    return true
                }
            }
        }
        return false
    }

    /** Even-odd ray casting for the lasso polygon. The closing edge is implied. */
    fun insidePolygon(polygon: List<InkSample>, x: Double, y: Double): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].x
            val yi = polygon[i].y
            val xj = polygon[j].x
            val yj = polygon[j].y
            val crosses = (yi > y) != (yj > y) &&
                x < (xj - xi) * (y - yi) / ((yj - yi).takeIf { abs(it) > 1e-12 } ?: 1e-12) + xi
            if (crosses) inside = !inside
            j = i
        }
        return inside
    }

    fun lassoSelect(strokes: List<InkStroke>, polygon: List<InkSample>): Set<Long> {
        if (polygon.size < 3) return emptySet()
        return strokes.filter { stroke ->
            stroke.samples.any { insidePolygon(polygon, it.x, it.y) }
        }.map { it.id }.toSet()
    }

    fun move(stroke: InkStroke, dx: Double, dy: Double): InkStroke =
        stroke.copy(samples = stroke.samples.map { it.copy(x = it.x + dx, y = it.y + dy) })

    /**
     * Turns a held freehand stroke into a clean line, circle or rectangle. Only
     * a deliberate hold (the gesture layer passes [held] = true) snaps; a
     * quick stroke is always kept exactly as drawn.
     */
    fun detectShape(samples: List<InkSample>, held: Boolean): ShapeSnap? {
        if (!held) return null
        val points = simplify(samples, epsilon = 0.8)
        if (points.size < 2) return null
        val length = pathLength(points)
        if (length < 6.0) return null
        val first = points.first()
        val last = points.last()
        val closing = hypot(last.x - first.x, last.y - first.y)
        val closed = closing <= max(12.0, length * 0.16)
        if (!closed) {
            val maxDeviation = points.maxOf { perpendicularDistance(it, first, last) }
            return if (maxDeviation / length <= 0.08) {
                ShapeSnap(ShapeKind.LINE, listOf(first, last))
            } else {
                null
            }
        }
        val box = bounds(points) ?: return null
        if (box.width < 2.0 || box.height < 2.0) return null
        val centerX = (box.left + box.right) / 2.0
        val centerY = (box.bottom + box.top) / 2.0
        val radii = points.map { hypot(it.x - centerX, it.y - centerY) }
        val mean = radii.average()
        if (mean <= 0.5) return null
        val deviation = radii.map { abs(it - mean) }.average() / mean
        val aspect = min(box.width, box.height) / max(box.width, box.height)
        if (deviation <= 0.24 && aspect >= 0.70) {
            val radius = mean
            val circle = (0..72).map { step ->
                val angle = 2.0 * Math.PI * step / 72.0
                InkSample(
                    centerX + cos(angle) * radius,
                    centerY + sin(angle) * radius,
                    averagePressure(points),
                    averageTilt(points)
                )
            }
            return ShapeSnap(ShapeKind.CIRCLE, circle)
        }
        val rectangle = listOf(
            InkSample(box.left, box.bottom, averagePressure(points), averageTilt(points)),
            InkSample(box.right, box.bottom, averagePressure(points), averageTilt(points)),
            InkSample(box.right, box.top, averagePressure(points), averageTilt(points)),
            InkSample(box.left, box.top, averagePressure(points), averageTilt(points)),
            InkSample(box.left, box.bottom, averagePressure(points), averageTilt(points))
        )
        return ShapeSnap(ShapeKind.RECTANGLE, rectangle)
    }

    private fun averagePressure(points: List<InkSample>): Double =
        points.map { it.pressure }.average()

    private fun averageTilt(points: List<InkSample>): Double =
        points.map { it.tilt }.average()

    /** Rotation-independent angle of the first→last segment (debug/tests). */
    fun directionDegrees(samples: List<InkSample>): Double {
        if (samples.size < 2) return 0.0
        val a = samples.first()
        val b = samples.last()
        return Math.toDegrees(atan2(b.y - a.y, b.x - a.x))
    }
}
