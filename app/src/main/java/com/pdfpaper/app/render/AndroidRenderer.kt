package com.pdfpaper.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.Xfermode
import com.pdfpaper.engine.eink.EinkPipeline
import com.pdfpaper.engine.eink.EinkProfile
import com.pdfpaper.engine.eink.EinkSettings
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.graphics.ClosePath
import com.pdfpaper.engine.graphics.CurveTo
import com.pdfpaper.engine.graphics.LineStyle
import com.pdfpaper.engine.graphics.LineTo
import com.pdfpaper.engine.graphics.MoveTo
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.graphics.PdfPath
import com.pdfpaper.engine.image.RasterImage
import com.pdfpaper.engine.render.ColorTransform
import com.pdfpaper.engine.render.PageTheme
import com.pdfpaper.engine.render.Renderer
import com.pdfpaper.engine.text.PdfTextRun
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * [Renderer] on top of `android.graphics.Canvas` (T9). The engine hands over
 * primitives in the user space of the CTM in effect when they were issued
 * (§8.3.3), so each primitive is drawn inside a save/concat/restore bracket
 * and stroke widths, dash lengths and the image unit square scale with it.
 *
 * Images arrive as decoded RGBA ([RasterImage]); soft masks are multiplied
 * into the alpha here and stencil masks are tinted with the fill colour
 * (§8.9.6.2). Text uses an embedded font program when the platform can load
 * it, otherwise a system font standing in for the standard-14 families.
 *
 * [theme] (T32/T32b) is applied to each primitive as it is drawn, so a dark
 * or true-black page is composited from themed colours rather than an inverted
 * bitmap: anti-aliased edges blend mapped ink into the mapped background and
 * photographs pass through untouched.
 */
class AndroidRenderer(
    private val canvas: Canvas,
    /** Where embedded font programs are cached as files; null disables them. */
    private val fontDir: File? = null,
    /** Page theme; [PageTheme.OFF] paints the document as authored. */
    private val theme: PageTheme = PageTheme.OFF,
    /**
     * T62: the reader's colour look. It is applied to the colours handed to the
     * canvas (and to image pixels), so a sepia or inverted page is composited
     * from mapped colours rather than being a filtered bitmap.
     */
    private val transform: ColorTransform = ColorTransform.NONE,
    /**
     * T63: the E-Ink profile. Profile 2 (`1-bit text`) turns anti-aliasing off
     * and paints text, fills and strokes in black or white at the reader's
     * threshold, so a glyph edge cannot become a grey smudge; pictures are
     * dithered on their own pixels. Profile 3 is reduced after painting.
     */
    private val eink: EinkSettings = EinkSettings.OFF,
    /** Device pixels per user unit of the page plan (T63 minimum line width). */
    private val baseScale: Double = 1.0
) : Renderer {

    /** Profile 2 paints primitives without anti-aliasing (T63). */
    private val crisp = eink.profile == EinkProfile.TEXT_BITONAL

    private val fillPaint = Paint(if (crisp) 0 else Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(if (crisp) 0 else Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val imagePaint = Paint(if (crisp) 0 else Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        // A dithered picture must not be smoothed back into greys when it is
        // scaled, so profile 2 draws it with nearest-neighbour sampling.
        isFilterBitmap = !crisp
    }
    private val textPaint = Paint(if (crisp) 0 else Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        val ink = theme.text()
        color = if (crisp) Color.BLACK else transform.grade(ink.color).toArgb(ink.alpha)
    }

    /** Raster dimming for photographs (T32b); null while it is a no-op. */
    private val dimFilter: ColorMatrixColorFilter? = run {
        val dim = theme.imageDim()
        if (dim >= 0.999) {
            null
        } else {
            val scale = dim.toFloat().coerceIn(0.0f, 1.0f)
            ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        scale, 0f, 0f, 0f, 0f,
                        0f, scale, 0f, 0f, 0f,
                        0f, 0f, scale, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
    }

    /** Embedded font programs keyed by identity; a null value caches failure. */
    private val embeddedTypefaces = HashMap<Int, Typeface?>()
    private val systemTypefaces = HashMap<Pair<String, Int>, Typeface>()

    /** Reused to avoid one Matrix per primitive; concat() copies the values. */
    private val scratch = Matrix()

    /** Blend mode in effect (ExtGState /BM); null = Normal / replace. */
    private var xfermode: Xfermode? = null
    private val blendStack = ArrayList<Xfermode?>()

    override fun saveState() {
        canvas.save()
        blendStack.add(xfermode)
    }

    override fun restoreState() {
        canvas.restore()
        xfermode = if (blendStack.isEmpty()) null else blendStack.removeAt(blendStack.size - 1)
    }

    override fun setBlendMode(mode: String?) {
        val porterDuff = when (mode?.lowercase()) {
            "multiply" -> PorterDuff.Mode.MULTIPLY
            "screen" -> PorterDuff.Mode.SCREEN
            "darken" -> PorterDuff.Mode.DARKEN
            "lighten" -> PorterDuff.Mode.LIGHTEN
            "overlay" -> PorterDuff.Mode.OVERLAY
            else -> null // Normal and everything unsupported (blend modes are a T14 candidate)
        }
        xfermode = porterDuff?.let { PorterDuffXfermode(it) }
    }

    override fun clip(path: PdfPath, matrix: PdfMatrix, evenOdd: Boolean) {
        withMatrix(matrix) { canvas.clipPath(path.toAndroidPath(evenOdd)) }
    }

    override fun fillPath(
        path: PdfPath,
        matrix: PdfMatrix,
        color: PdfColor,
        alpha: Double,
        evenOdd: Boolean
    ) {
        val paint = theme.fill(color, alpha)
        withMatrix(matrix) {
            fillPaint.xfermode = xfermode
            if (crisp) {
                fillPaint.color = eink.binaryFill(paint.color).toArgb(1.0)
            } else {
                fillPaint.color = transform.grade(paint.color).toArgb(paint.alpha)
            }
            canvas.drawPath(path.toAndroidPath(evenOdd), fillPaint)
        }
    }

    override fun strokePath(
        path: PdfPath,
        matrix: PdfMatrix,
        color: PdfColor,
        alpha: Double,
        lineStyle: LineStyle
    ) {
        val paint = theme.stroke(color, alpha)
        withMatrix(matrix) {
            applyLineStyle(lineStyle, matrix)
            strokePaint.xfermode = xfermode
            if (crisp) {
                strokePaint.color = eink.binaryStroke(paint.color).toArgb(1.0)
            } else {
                strokePaint.color = transform.grade(paint.color).toArgb(paint.alpha)
            }
            canvas.drawPath(path.toAndroidPath(false), strokePaint)
        }
    }

    override fun drawImage(image: RasterImage, matrix: PdfMatrix, fillColor: PdfColor, alpha: Double) {
        val tint = if (image.stencil) theme.stencil(fillColor).color else null
        val bitmap = bitmapFrom(image, tint)
        if (bitmap != null) {
            withMatrix(matrix) {
                imagePaint.xfermode = xfermode
                imagePaint.alpha = alphaByte(alpha)
                // A photograph keeps its own colours unless dim is set (T32b).
                imagePaint.colorFilter = dimFilter
                canvas.drawBitmap(bitmap, null, UNIT_RECT, imagePaint)
            }
            bitmap.recycle()
        }
    }

    override fun drawText(run: PdfTextRun, matrix: PdfMatrix) {
        if (run.text.isEmpty()) return
        val size = run.fontSize * matrix.meanScale()
        if (!size.isFinite() || size <= 0.0) return

        textPaint.typeface = typefaceFor(run)
        textPaint.textSize = size.toFloat()

        val dx = run.endX - run.originX
        val dy = run.endY - run.originY
        val advance = hypot(dx, dy)
        val measured = textPaint.measureText(run.text).toDouble()

        canvas.save()
        canvas.translate(run.originX.toFloat(), run.originY.toFloat())
        if (advance > 0.0) {
            // Baseline direction in page space (y up); the page matrix already
            // flipped y, so the screen-clockwise angle is the negated one.
            val degrees = Math.toDegrees(atan2(dy, dx)).toFloat()
            if (degrees != 0.0f) canvas.rotate(-degrees)
        }
        canvas.scale(1.0f, -1.0f) // glyphs draw upright despite the y flip
        if (advance > 0.0 && measured > 0.0) {
            // Stretch to the document's advance so widths match /Widths even
            // when the system font's metrics differ.
            canvas.scale((advance / measured).toFloat().coerceIn(0.25f, 4.0f), 1.0f)
        }
        canvas.drawText(run.text, 0.0f, 0.0f, textPaint)
        canvas.restore()
    }

    // ------------------------------------------------------------- helpers

    private inline fun withMatrix(matrix: PdfMatrix, block: () -> Unit) {
        canvas.save()
        matrix.intoAndroid(scratch)
        canvas.concat(scratch)
        block()
        canvas.restore()
    }

    private fun applyLineStyle(style: LineStyle, matrix: PdfMatrix) {
        // T63: a one-bit profile thickens a hairline to at least one device
        // pixel, or the threshold erases a 0.2 pt rule entirely. Profile 3 does
        // this too, because it is the final threshold that would lose the line.
        val width = if (eink.bitonal) {
            eink.strokeWidth(style.width, baseScale * matrix.meanScale())
        } else {
            style.width
        }
        strokePaint.strokeWidth = width.toFloat().coerceAtLeast(0.0f)
        strokePaint.strokeCap = when (style.cap) {
            1 -> Paint.Cap.ROUND
            2 -> Paint.Cap.SQUARE
            else -> Paint.Cap.BUTT
        }
        strokePaint.strokeJoin = when (style.join) {
            1 -> Paint.Join.ROUND
            2 -> Paint.Join.BEVEL
            else -> Paint.Join.MITER
        }
        strokePaint.strokeMiter = style.miterLimit.toFloat().coerceIn(1.0f, 100.0f)
        val dashes = style.dashArray
        strokePaint.pathEffect =
            if (dashes.size >= 2 && dashes.none { it < 0.0 } && dashes.any { it > 0.0 }) {
                // PDF dash lengths are user space and path effects run before
                // the canvas transform, so the pattern scales with the CTM.
                DashPathEffect(FloatArray(dashes.size) { dashes[it].toFloat() }, style.dashPhase.toFloat())
            } else {
                null
            }
    }

    private fun typefaceFor(run: PdfTextRun): Typeface {
        val program = run.fontProgram
        val format = run.fontFormat
        if (program != null && program.isNotEmpty() && (format == "truetype" || format == "opentype")) {
            embeddedTypeface(program, format)?.let { return it }
        }
        val name = (run.fontName ?: "").lowercase()
        val family = when {
            "courier" in name || "mono" in name -> "monospace"
            "times" in name || "serif" in name || "georgia" in name -> "serif"
            "symbol" in name || "zapf" in name -> "sans-serif"
            else -> "sans-serif"
        }
        val bold = "bold" in name || "black" in name || "heavy" in name
        val italic = "italic" in name || "oblique" in name
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return systemTypefaces.getOrPut(family to style) { Typeface.create(family, style) }
    }

    /**
     * Typeface for an embedded font program (§9.8). Android loads fonts from
     * files, so the program is written once into [fontDir] and kept there for
     * the session; a null result (no directory, unsupported format, bad data)
     * falls back to a system font.
     */
    private fun embeddedTypeface(program: ByteArray, format: String?): Typeface? {
        val dir = fontDir ?: return null
        val key = System.identityHashCode(program)
        if (embeddedTypefaces.containsKey(key)) return embeddedTypefaces[key]
        val created: Typeface? = try {
            if (!dir.isDirectory && !dir.mkdirs()) {
                embeddedTypefaces[key] = null
                return null
            }
            val suffix = if (format == "opentype") "otf" else "ttf"
            val file = File(dir, "embedded-$key.$suffix")
            if (!file.isFile || file.length() != program.size.toLong()) file.writeBytes(program)
            Typeface.Builder(file).build()
        } catch (t: Throwable) {
            null
        }
        embeddedTypefaces[key] = created
        return created
    }

    /** RGBA bytes → ARGB bitmap, applying [tint] for stencils and the soft mask. */
    private fun bitmapFrom(image: RasterImage, tint: PdfColor?): Bitmap? {
        val pixels = image.pixels ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0 || pixels.size < width * height * 4) return null
        val soft = softMaskValues(image.softMask)
        val tintR = tint?.red?.channel() ?: 0
        val tintG = tint?.green?.channel() ?: 0
        val tintB = tint?.blue?.channel() ?: 0
        val argb = IntArray(width * height)
        for (i in argb.indices) {
            val o = i * 4
            var r = pixels[o].toInt() and 0xFF
            var g = pixels[o + 1].toInt() and 0xFF
            var b = pixels[o + 2].toInt() and 0xFF
            var a = pixels[o + 3].toInt() and 0xFF
            if (tint != null) {
                r = tintR
                g = tintG
                b = tintB
            }
            if (soft != null) a = a * soft[i] / 255
            argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        // T62: the look maps image pixels too - sepia tints a photograph and
        // inverted monochrome inverts it - so a filtered page keeps no
        // original colour. One in-place pass, one buffer per thread.
        transform.apply(argb)
        // T63: profile 2 dithers the picture itself, so the text around it can
        // stay a crisp one-bit glyph while a photo keeps its tones.
        if (eink.ditheredImages) EinkPipeline.ditherImage(argb, width, height, eink)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Soft-mask alpha (0..255 per pixel of the image being drawn). A soft
     * mask is a grayscale image whose value is opacity (§11.6.5.3, §8.9.5.4);
     * different sizes are sampled nearest-neighbour. Nested masks are ignored.
     */
    private fun softMaskValues(mask: RasterImage?): IntArray? {
        val pixels = mask?.pixels ?: return null
        val mw = mask.width
        val mh = mask.height
        if (mw <= 0 || mh <= 0 || pixels.size < mw * mh * 4) return null
        val out = IntArray(mw * mh)
        for (i in out.indices) {
            val o = i * 4
            out[i] = if (mask.stencil) pixels[o + 3].toInt() and 0xFF else pixels[o].toInt() and 0xFF
        }
        return out
    }

    private fun alphaByte(alpha: Double): Int = (alpha * 255.0).roundToInt().coerceIn(0, 255)

    private fun Double.channel(): Int = (this * 255.0).roundToInt().coerceIn(0, 255)

    private companion object {
        val UNIT_RECT = RectF(0.0f, 0.0f, 1.0f, 1.0f)
    }
}

/** PDF path → android Path, with the fill rule PDF asked for (§8.5.3.3.2). */
fun PdfPath.toAndroidPath(evenOdd: Boolean): Path {
    val path = Path()
    for (segment in segments) {
        when (segment) {
            is MoveTo -> path.moveTo(segment.x.toFloat(), segment.y.toFloat())
            is LineTo -> path.lineTo(segment.x.toFloat(), segment.y.toFloat())
            is CurveTo -> path.cubicTo(
                segment.x1.toFloat(), segment.y1.toFloat(),
                segment.x2.toFloat(), segment.y2.toFloat(),
                segment.x3.toFloat(), segment.y3.toFloat()
            )
            is ClosePath -> path.close()
        }
    }
    path.fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
    return path
}

/** PdfMatrix [a b c d e f] → Android's 3×3 row-major layout. */
fun PdfMatrix.intoAndroid(target: Matrix = Matrix()): Matrix {
    target.setValues(
        floatArrayOf(
            a.toFloat(), c.toFloat(), e.toFloat(),
            b.toFloat(), d.toFloat(), f.toFloat(),
            0.0f, 0.0f, 1.0f
        )
    )
    return target
}

/** Device colour int; [alpha] multiplies the colour's own constant alpha. */
fun PdfColor.toArgb(alpha: Double): Int {
    val a = (this.alpha * alpha * 255.0).roundToInt().coerceIn(0, 255)
    val r = (red * 255.0).roundToInt().coerceIn(0, 255)
    val g = (green * 255.0).roundToInt().coerceIn(0, 255)
    val b = (blue * 255.0).roundToInt().coerceIn(0, 255)
    return Color.argb(a, r, g, b)
}
