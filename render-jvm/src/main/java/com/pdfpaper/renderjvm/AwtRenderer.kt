package com.pdfpaper.renderjvm

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.graphics.ClosePath
import com.pdfpaper.engine.graphics.CurveTo
import com.pdfpaper.engine.graphics.LineStyle
import com.pdfpaper.engine.graphics.LineTo
import com.pdfpaper.engine.graphics.MoveTo
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.graphics.PdfPath
import com.pdfpaper.engine.image.RasterImage
import com.pdfpaper.engine.render.Renderer
import com.pdfpaper.engine.text.PdfTextRun
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * [Renderer] on top of `java.awt.Graphics2D`, used by the JVM export CLI.
 *
 * The engine hands each primitive over in the user space of the CTM that was
 * in effect when it was issued (§8.3.3); this renderer composes that with the
 * page→pixel matrix [base] and draws in device coordinates, so stroke widths,
 * clips and the image unit square scale exactly as they do in the app.
 *
 * Text is drawn with the JDK's logical fonts mapped from the PDF base font
 * name (Serif / SansSerif / Monospaced plus bold and italic), at the effective
 * size and baseline angle, stretched to the document's advance so widths match
 * /Widths. Embedded font programs are not loaded (the same limitation the
 * Android renderer documents for Type1/CFF). Blend modes other than Normal
 * fall back to source-over and are counted in [blendModesIgnored].
 */
class AwtRenderer(
    private val g: Graphics2D,
    private val base: PdfMatrix
) : Renderer {

    var blendModesIgnored: Int = 0
        private set

    private val stack = ArrayList<Graphics2D>()

    init {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    }

    /** The graphics the renderer currently draws into (the top of the q/Q stack). */
    private var graphics: Graphics2D = g

    override fun saveState() {
        val saved = graphics.create() as Graphics2D
        stack.add(graphics)
        graphics = saved
    }

    override fun restoreState() {
        if (stack.isEmpty()) return
        graphics.dispose()
        graphics = stack.removeAt(stack.size - 1)
    }

    override fun setBlendMode(mode: String?) {
        if (mode != null && !mode.equals("Normal", ignoreCase = true)) blendModesIgnored++
    }

    override fun clip(path: PdfPath, matrix: PdfMatrix, evenOdd: Boolean) {
        val shape = shapeOf(path, base * matrix, evenOdd) ?: return
        graphics.clip(shape)
    }

    override fun fillPath(
        path: PdfPath,
        matrix: PdfMatrix,
        color: PdfColor,
        alpha: Double,
        evenOdd: Boolean
    ) {
        val shape = shapeOf(path, base * matrix, evenOdd) ?: return
        val previous = graphics.composite
        graphics.composite = AlphaComposite.getInstance(
            AlphaComposite.SRC_OVER,
            (alpha * color.alpha).coerceIn(0.0, 1.0).toFloat()
        )
        graphics.color = awtColor(color)
        graphics.fill(shape)
        graphics.composite = previous
    }

    override fun strokePath(
        path: PdfPath,
        matrix: PdfMatrix,
        color: PdfColor,
        alpha: Double,
        lineStyle: LineStyle
    ) {
        val device = base * matrix
        val shape = shapeOf(path, device, evenOdd = false) ?: return
        val previous = graphics.composite
        graphics.composite = AlphaComposite.getInstance(
            AlphaComposite.SRC_OVER,
            (alpha * color.alpha).coerceIn(0.0, 1.0).toFloat()
        )
        graphics.color = awtColor(color)
        graphics.stroke = BasicStroke(
            (lineStyle.width * device.meanScale()).toFloat().coerceAtLeast(0.01f),
            lineStyle.cap,
            lineStyle.join,
            lineStyle.miterLimit.toFloat(),
            if (lineStyle.dashArray.isEmpty()) null else FloatArray(lineStyle.dashArray.size) {
                (lineStyle.dashArray[it] * device.meanScale()).toFloat()
            },
            (lineStyle.dashPhase * device.meanScale()).toFloat()
        )
        graphics.draw(shape)
        graphics.composite = previous
    }

    override fun drawImage(image: RasterImage, matrix: PdfMatrix, fillColor: PdfColor, alpha: Double) {
        val pixels = image.pixels ?: return
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0 || pixels.size < width * height * 4) return
        val device = base * matrix
        val composite = if (alpha >= 1.0) {
            AlphaComposite.SrcOver
        } else {
            AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.toFloat())
        }
        val previous = graphics.composite
        graphics.composite = composite
        graphics.drawImage(
            bufferedImage(image, pixels, fillColor),
            AffineTransform(device.toAffine()).apply {
                concatenate(AffineTransform.getScaleInstance(1.0 / width, 1.0 / height))
            },
            null
        )
        graphics.composite = previous
    }

    override fun drawText(run: PdfTextRun, matrix: PdfMatrix) {
        if (run.text.isEmpty()) return
        val device = base * matrix
        val size = run.fontSize * device.meanScale()
        if (!size.isFinite() || size <= 0.0) return
        val font = fontFor(run, size)
        val origin = device.apply(run.originX, run.originY)
        val end = device.apply(run.endX, run.endY)
        val angle = atan2(end[1] - origin[1], end[0] - origin[0])
        val advance = hypot(end[0] - origin[0], end[1] - origin[1])

        val previous = graphics.composite
        graphics.composite = AlphaComposite.SrcOver
        graphics.color = Color.BLACK
        graphics.font = font
        val transform = graphics.transform
        try {
            graphics.translate(origin[0], origin[1])
            if (angle.isFinite()) graphics.rotate(angle)
            if (advance > 0.0) {
                val measured = graphics.fontMetrics.stringWidth(run.text).toDouble()
                if (measured > 0.0) graphics.scale(advance / measured, 1.0)
            }
            graphics.drawString(run.text, 0f, 0f)
        } finally {
            graphics.transform = transform
            graphics.composite = previous
        }
    }

    // ------------------------------------------------------------- helpers

    private fun shapeOf(path: PdfPath, device: PdfMatrix, evenOdd: Boolean): java.awt.Shape? {
        if (path.isEmpty) return null
        val result = Path2D.Double(if (evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO)
        var open = false
        for (segment in path.segments) {
            when (segment) {
                is MoveTo -> {
                    val point = device.apply(segment.x, segment.y)
                    result.moveTo(point[0], point[1])
                    open = true
                }
                is LineTo -> {
                    val point = device.apply(segment.x, segment.y)
                    if (!open) {
                        result.moveTo(point[0], point[1])
                        open = true
                    } else {
                        result.lineTo(point[0], point[1])
                    }
                }
                is CurveTo -> {
                    val c1 = device.apply(segment.x1, segment.y1)
                    val c2 = device.apply(segment.x2, segment.y2)
                    val end = device.apply(segment.x3, segment.y3)
                    if (!open) {
                        result.moveTo(c1[0], c1[1])
                        open = true
                    }
                    result.curveTo(c1[0], c1[1], c2[0], c2[1], end[0], end[1])
                }
                ClosePath -> if (open) result.closePath()
            }
        }
        return result
    }

    private fun bufferedImage(image: RasterImage, pixels: ByteArray, fillColor: PdfColor): BufferedImage {
        val width = image.width
        val height = image.height
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val argb = IntArray(width * height)
        val mask = image.softMask
        val maskPixels = mask?.pixels
        val tintR = (fillColor.red * 255.0).roundToInt().coerceIn(0, 255)
        val tintG = (fillColor.green * 255.0).roundToInt().coerceIn(0, 255)
        val tintB = (fillColor.blue * 255.0).roundToInt().coerceIn(0, 255)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val o = (y * width + x) * 4
                val r: Int
                val g: Int
                val b: Int
                if (image.stencil) {
                    r = tintR; g = tintG; b = tintB
                } else {
                    r = pixels[o].toInt() and 0xFF
                    g = pixels[o + 1].toInt() and 0xFF
                    b = pixels[o + 2].toInt() and 0xFF
                }
                var a = pixels[o + 3].toInt() and 0xFF
                if (maskPixels != null && mask.width > 0 && mask.height > 0) {
                    val mx = (x.toLong() * mask.width / width).toInt().coerceIn(0, mask.width - 1)
                    val my = (y.toLong() * mask.height / height).toInt().coerceIn(0, mask.height - 1)
                    val mo = (my * mask.width + mx) * 4
                    val mv = if (mask.stencil) {
                        maskPixels[mo + 3].toInt() and 0xFF
                    } else {
                        ((maskPixels[mo].toInt() and 0xFF) * 299 +
                            (maskPixels[mo + 1].toInt() and 0xFF) * 587 +
                            (maskPixels[mo + 2].toInt() and 0xFF) * 114) / 1000
                    }
                    a = a * mv / 255
                }
                argb[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        out.setRGB(0, 0, width, height, argb, 0, width)
        return out
    }

    private fun fontFor(run: PdfTextRun, size: Double): Font {
        val name = (run.fontName ?: "").lowercase()
        val family = when {
            name.contains("courier") || name.contains("mono") -> Font.MONOSPACED
            name.contains("times") || name.contains("serif") || name.contains("roman") -> Font.SERIF
            else -> Font.SANS_SERIF
        }
        var style = Font.PLAIN
        if (name.contains("bold")) style = style or Font.BOLD
        if (name.contains("italic") || name.contains("oblique")) style = style or Font.ITALIC
        return Font(family, style, size.roundToInt().coerceAtLeast(1))
    }

    private fun awtColor(color: PdfColor): Color = Color(
        (color.red * 255.0).roundToInt().coerceIn(0, 255),
        (color.green * 255.0).roundToInt().coerceIn(0, 255),
        (color.blue * 255.0).roundToInt().coerceIn(0, 255)
    )

    private fun PdfMatrix.toAffine(): AffineTransform = AffineTransform(a, b, c, d, e, f)
}
