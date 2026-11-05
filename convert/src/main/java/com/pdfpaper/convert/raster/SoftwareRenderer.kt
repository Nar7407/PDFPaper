package com.pdfpaper.convert.raster

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.graphics.LineStyle
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.graphics.PdfPath
import com.pdfpaper.engine.image.RasterImage
import com.pdfpaper.engine.render.Renderer
import com.pdfpaper.engine.text.PdfTextRun
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A small software [Renderer] used to rasterize the non-text parts of a page
 * for the PPTX "fidelity" mode (§8 painting model). It covers solid fills,
 * strokes, rectangular and path clips, and affine image blits with nearest
 * sampling — everything the engine hands over except glyphs.
 *
 * Glyphs cannot be rasterized here: the pure-JVM engine exposes advance widths
 * but no outline rasterizer, and this module may not use `java.awt`. Text runs
 * are counted in [textRunsSkipped] so the caller can report the gap instead of
 * pretending the picture is complete.
 *
 * The page→device transform ([baseMatrix]) is composed with each command's own
 * CTM, exactly as the Canvas renderer in :app does with `canvas.concat`. Image
 * sampling likewise follows the display list's unit square verbatim — unit x=0
 * is sample column 0 and unit y=0 is the first sample row, the same rule
 * `drawBitmap(bitmap, null, UNIT_RECT)` applies on Android — so this renderer
 * stays consistent with the engine's own renderers whatever their image-space
 * convention happens to be.
 */
class SoftwareRenderer(
    val width: Int,
    val height: Int,
    private val baseMatrix: PdfMatrix
) : Renderer {

    val raster = Raster(width, height)

    /** Text runs the rasterizer could not draw (no outline engine). */
    var textRunsSkipped: Int = 0

    /** Non-Normal blend modes seen; compositing falls back to source-over. */
    var blendModesIgnored: Int = 0

    private var clipMask: ByteArray? = null
    private val clipStack = ArrayList<ByteArray?>()

    init {
        raster.fillWhite()
    }

    override fun saveState() {
        clipStack.add(clipMask)
    }

    override fun restoreState() {
        if (clipStack.isNotEmpty()) clipMask = clipStack.removeAt(clipStack.size - 1)
    }

    override fun setBlendMode(mode: String?) {
        if (mode != null && !mode.equals("Normal", ignoreCase = true)) blendModesIgnored++
    }

    override fun clip(path: PdfPath, matrix: PdfMatrix, evenOdd: Boolean) {
        val device = baseMatrix * matrix
        val subpaths = PathFlattener.flatten(path, device)
        if (subpaths.isEmpty()) return
        val mask = ByteArray(width * height)
        scanFill(subpaths, evenOdd) { x, y ->
            if (allowed(x, y)) mask[y * width + x] = 1
        }
        clipMask = mask
    }

    override fun fillPath(
        path: PdfPath,
        matrix: PdfMatrix,
        color: PdfColor,
        alpha: Double,
        evenOdd: Boolean
    ) {
        val device = baseMatrix * matrix
        val subpaths = PathFlattener.flatten(path, device)
        if (subpaths.isEmpty()) return
        val r = channel(color.red)
        val g = channel(color.green)
        val b = channel(color.blue)
        val a = (alpha * color.alpha).coerceIn(0.0, 1.0)
        scanFill(subpaths, evenOdd) { x, y ->
            if (allowed(x, y)) raster.blend(x, y, r, g, b, a)
        }
    }

    override fun strokePath(
        path: PdfPath,
        matrix: PdfMatrix,
        color: PdfColor,
        alpha: Double,
        lineStyle: LineStyle
    ) {
        val device = baseMatrix * matrix
        val subpaths = PathFlattener.flatten(path, device)
        if (subpaths.isEmpty()) return
        val half = max(0.5, lineStyle.width * device.meanScale()) / 2.0
        val quads = ArrayList<DoubleArray>()
        for (sub in subpaths) {
            val points = sub.size / 2
            if (points < 2) {
                // A lone move with round caps would still paint a dot; skip.
                continue
            }
            for (i in 0 until points - 1) {
                addSegmentQuad(quads, sub[i * 2], sub[i * 2 + 1], sub[(i + 1) * 2], sub[(i + 1) * 2 + 1], half)
            }
        }
        if (quads.isEmpty()) return
        val r = channel(color.red)
        val g = channel(color.green)
        val b = channel(color.blue)
        val a = (alpha * color.alpha).coerceIn(0.0, 1.0)
        // Non-zero fill of all quads: consistently oriented quads overlap
        // without cancelling, which is what a thick polyline should do.
        scanFill(quads, evenOdd = false) { x, y ->
            if (allowed(x, y)) raster.blend(x, y, r, g, b, a)
        }
    }

    override fun drawImage(image: RasterImage, matrix: PdfMatrix, fillColor: PdfColor, alpha: Double) {
        val pixels = image.pixels ?: return
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0 || pixels.size < w * h * 4) return
        val device = baseMatrix * matrix
        val inverse = device.inverse() ?: return

        val minX = floor(minOf(
            device.apply(0.0, 0.0)[0], device.apply(1.0, 0.0)[0],
            device.apply(1.0, 1.0)[0], device.apply(0.0, 1.0)[0]
        )).toInt()
        val maxX = ceil(maxOf(
            device.apply(0.0, 0.0)[0], device.apply(1.0, 0.0)[0],
            device.apply(1.0, 1.0)[0], device.apply(0.0, 1.0)[0]
        )).toInt()
        val minY = floor(minOf(
            device.apply(0.0, 0.0)[1], device.apply(1.0, 0.0)[1],
            device.apply(1.0, 1.0)[1], device.apply(0.0, 1.0)[1]
        )).toInt()
        val maxY = ceil(maxOf(
            device.apply(0.0, 0.0)[1], device.apply(1.0, 0.0)[1],
            device.apply(1.0, 1.0)[1], device.apply(0.0, 1.0)[1]
        )).toInt()

        val soft = image.softMask
        val softPixels = soft?.pixels
        val softW = soft?.width ?: 0
        val softH = soft?.height ?: 0
        val tintR = channel(fillColor.red)
        val tintG = channel(fillColor.green)
        val tintB = channel(fillColor.blue)

        val x0 = max(0, minX)
        val x1 = min(width - 1, maxX)
        val y0 = max(0, minY)
        val y1 = min(height - 1, maxY)
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (!allowed(x, y)) continue
                val uv = inverse.apply(x + 0.5, y + 0.5)
                val u = uv[0]
                val v = uv[1]
                if (u < 0.0 || u >= 1.0 || v < 0.0 || v >= 1.0) continue
                val sx = (u * w).toInt().coerceIn(0, w - 1)
                val sy = (v * h).toInt().coerceIn(0, h - 1)
                val o = (sy * w + sx) * 4
                var sa = (pixels[o + 3].toInt() and 0xFF) / 255.0
                if (sa <= 0.0) continue
                if (softPixels != null && softW > 0 && softH > 0 && softPixels.size >= softW * softH * 4) {
                    val mx = (u * softW).toInt().coerceIn(0, softW - 1)
                    val my = (v * softH).toInt().coerceIn(0, softH - 1)
                    val mo = (my * softW + mx) * 4
                    val mv = if (soft.stencil) softPixels[mo + 3].toInt() and 0xFF
                    else softPixels[mo].toInt() and 0xFF
                    sa *= mv / 255.0
                }
                if (image.stencil) {
                    raster.blend(x, y, tintR, tintG, tintB, sa * alpha)
                } else {
                    raster.blend(
                        x, y,
                        pixels[o].toInt() and 0xFF,
                        pixels[o + 1].toInt() and 0xFF,
                        pixels[o + 2].toInt() and 0xFF,
                        sa * alpha
                    )
                }
            }
        }
    }

    override fun drawText(run: PdfTextRun, matrix: PdfMatrix) {
        textRunsSkipped++
    }

    // ------------------------------------------------------------- internals

    private fun allowed(x: Int, y: Int): Boolean {
        val mask = clipMask ?: return true
        return mask[y * width + x].toInt() != 0
    }

    private fun addSegmentQuad(
        out: MutableList<DoubleArray>,
        x0: Double, y0: Double, x1: Double, y1: Double, half: Double
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < 1e-9) return
        val nx = -dy / len * half
        val ny = dx / len * half
        out.add(
            doubleArrayOf(
                x0 + nx, y0 + ny,
                x1 + nx, y1 + ny,
                x1 - nx, y1 - ny,
                x0 - nx, y0 - ny
            )
        )
    }

    /** Scanline fill of device-space polylines (§8.5.3.3 fill rules). */
    private fun scanFill(subpaths: List<DoubleArray>, evenOdd: Boolean, paint: (Int, Int) -> Unit) {
        val edges = ArrayList<DoubleArray>() // [x0,y0,x1,y1]
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (sub in subpaths) {
            val points = sub.size / 2
            if (points < 2) continue
            for (i in 0 until points) {
                val j = if (i + 1 == points) 0 else i + 1 // fill closes every subpath
                val ax = sub[i * 2]
                val ay = sub[i * 2 + 1]
                val bx = sub[j * 2]
                val by = sub[j * 2 + 1]
                if (ay == by) continue
                edges.add(doubleArrayOf(ax, ay, bx, by))
                if (ay < minY) minY = ay
                if (by < minY) minY = by
                if (ay > maxY) maxY = ay
                if (by > maxY) maxY = by
            }
        }
        if (edges.isEmpty()) return
        val yStart = max(0, floor(minY).toInt())
        val yEnd = min(height - 1, ceil(maxY).toInt())
        if (yStart > yEnd) return

        val crossX = ArrayList<Double>()
        val crossDir = ArrayList<Int>()
        for (y in yStart..yEnd) {
            val sample = y + 0.5
            crossX.clear()
            crossDir.clear()
            for (edge in edges) {
                val ax = edge[0]
                val ay = edge[1]
                val bx = edge[2]
                val by = edge[3]
                val downward = by > ay
                if ((ay <= sample && sample < by) || (by <= sample && sample < ay)) {
                    val t = (sample - ay) / (by - ay)
                    crossX.add(ax + t * (bx - ax))
                    crossDir.add(if (downward) 1 else -1)
                }
            }
            if (crossX.size < 2) continue
            sortPairs(crossX, crossDir)
            if (evenOdd) {
                var i = 0
                while (i + 1 < crossX.size) {
                    paintSpan(crossX[i], crossX[i + 1], y, paint)
                    i += 2
                }
            } else {
                var winding = 0
                var spanStart = 0.0
                for (i in crossX.indices) {
                    if (winding == 0) spanStart = crossX[i]
                    winding += crossDir[i]
                    if (winding == 0) paintSpan(spanStart, crossX[i], y, paint)
                }
            }
        }
    }

    private fun paintSpan(xa: Double, xb: Double, y: Int, paint: (Int, Int) -> Unit) {
        var x0 = ceil(xa - 0.5).toInt()
        var x1 = floor(xb - 0.5).toInt()
        if (x0 < 0) x0 = 0
        if (x1 > width - 1) x1 = width - 1
        for (x in x0..x1) paint(x, y)
    }

    private fun sortPairs(xs: ArrayList<Double>, dirs: ArrayList<Int>) {
        for (i in 1 until xs.size) {
            val x = xs[i]
            val d = dirs[i]
            var j = i - 1
            while (j >= 0 && xs[j] > x) {
                xs[j + 1] = xs[j]
                dirs[j + 1] = dirs[j]
                j--
            }
            xs[j + 1] = x
            dirs[j + 1] = d
        }
    }

    private fun channel(value: Double): Int = (value * 255.0).roundToInt().coerceIn(0, 255)
}
