package com.pdfpaper.convert

import com.pdfpaper.engine.geom.PdfMatrix
import kotlin.math.abs
import kotlin.math.hypot

/**
 * An axis-aligned placement of the unit square an image is drawn into
 * (§8.9.5.2), expressed in the target space: [x]/[y] are the top-left corner
 * and [width]/[height] the size, all in whatever unit [matrix] produced.
 */
class Placement(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val flipH: Boolean,
    val flipV: Boolean
)

/**
 * Image unit square → placement rectangle. The square is read exactly as the
 * display list defines it: sample column 0 sits at unit x=0 and the first
 * sample row at unit y=0, which is the rule the renderers apply
 * (`drawBitmap(bitmap, null, UNIT_RECT)`), so a picture is flipped only when
 * the matrix itself flipped the square. Returns null when the placement is
 * rotated or skewed, which neither DrawingML nor WordprocessingML can express
 * without a transform we do not compute.
 */
fun placementOf(matrix: PdfMatrix): Placement? {
    val p00 = matrix.apply(0.0, 0.0)
    val p10 = matrix.apply(1.0, 0.0)
    val p01 = matrix.apply(0.0, 1.0)
    val p11 = matrix.apply(1.0, 1.0)
    val width = hypot(p10[0] - p00[0], p10[1] - p00[1])
    val height = hypot(p01[0] - p00[0], p01[1] - p00[1])
    if (width < 1e-6 || height < 1e-6) return null
    if (abs(p10[1] - p00[1]) > 0.001 * width || abs(p01[0] - p00[0]) > 0.001 * height) return null
    val xs = doubleArrayOf(p00[0], p10[0], p01[0], p11[0])
    val ys = doubleArrayOf(p00[1], p10[1], p01[1], p11[1])
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()
    return Placement(
        x = minX,
        y = minY,
        width = maxX - minX,
        height = maxY - minY,
        flipH = p00[0] > p10[0],
        flipV = p00[1] > p01[1]
    )
}
