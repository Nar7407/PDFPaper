package com.pdfpaper.engine.geom

import kotlin.math.abs

/**
 * 2-D affine transformation matrix [a b c d e f] (ISO 32000-1, §8.3.3):
 *
 * ```
 * x' = a·x + c·y + e
 * y' = b·x + d·y + f
 * ```
 *
 * Composition follows the spec's "operand applied before the current matrix"
 * rule: `current * operand` maps a point through [operand] first, then through
 * `current` (column-vector convention).
 */
class PdfMatrix(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double
) {

    /** this·[other]: [other] applies first. */
    operator fun times(other: PdfMatrix): PdfMatrix = PdfMatrix(
        a * other.a + c * other.b,
        b * other.a + d * other.b,
        a * other.c + c * other.d,
        b * other.c + d * other.d,
        a * other.e + c * other.f + e,
        b * other.e + d * other.f + f
    )

    /** Transforms a point; returns [x', y']. */
    fun apply(x: Double, y: Double): DoubleArray =
        doubleArrayOf(a * x + c * y + e, b * x + d * y + f)

    val determinant: Double get() = a * d - b * c

    /** Inverse matrix, or null for a singular matrix (zero scaling). */
    fun inverse(): PdfMatrix? {
        val det = determinant
        if (abs(det) < 1e-12) return null
        return PdfMatrix(
            d / det,
            -b / det,
            -c / det,
            a / det,
            (c * f - d * e) / det,
            (b * e - a * f) / det
        )
    }

    /** Average linear scale factor, used where a single number is needed
     *  (e.g. effective stroke width in device space). */
    fun meanScale(): Double {
        val sx = kotlin.math.hypot(a, b)
        val sy = kotlin.math.hypot(c, d)
        return (sx + sy) / 2.0
    }

    override fun equals(other: Any?): Boolean = other is PdfMatrix &&
        a == other.a && b == other.b && c == other.c && d == other.d && e == other.e && f == other.f

    override fun hashCode(): Int {
        var h = a.hashCode()
        h = 31 * h + b.hashCode()
        h = 31 * h + c.hashCode()
        h = 31 * h + d.hashCode()
        h = 31 * h + e.hashCode()
        h = 31 * h + f.hashCode()
        return h
    }

    override fun toString(): String = "[$a $b $c $d $e $f]"

    companion object {
        val IDENTITY = PdfMatrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun translation(tx: Double, ty: Double): PdfMatrix = PdfMatrix(1.0, 0.0, 0.0, 1.0, tx, ty)

        fun scaling(sx: Double, sy: Double): PdfMatrix = PdfMatrix(sx, 0.0, 0.0, sy, 0.0, 0.0)

        /** Rotation by [degrees] counter-clockwise (PDF user space, y up). */
        fun rotation(degrees: Double): PdfMatrix {
            val r = Math.toRadians(degrees)
            val cos = kotlin.math.cos(r)
            val sin = kotlin.math.sin(r)
            return PdfMatrix(cos, sin, -sin, cos, 0.0, 0.0)
        }
    }
}
