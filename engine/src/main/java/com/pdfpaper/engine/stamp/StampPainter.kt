package com.pdfpaper.engine.stamp

import com.pdfpaper.engine.edit.ImageData
import com.pdfpaper.engine.edit.ImageXObjects
import com.pdfpaper.engine.edit.Standard14
import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.edit.WinAnsi
import com.pdfpaper.engine.edit.addStandardFont
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfSerializer

/**
 * Paints a [StampDesign] into a self-contained appearance stream (§12.5.5):
 * the banner, certificate frame, signature arrow or picture, the border and
 * colours, the opacity as an /ExtGState, and the quarter-turn rotation as a
 * coordinate transform — so any viewer renders the stamp without guessing.
 *
 * All text is measured with the standard-14 AFM metrics and the font
 * dictionaries carry the same /Widths, so the painted layout is exactly the
 * layout a viewer computes.
 */
internal object StampPainter {

    /** A painted appearance: its box, content and resources, rotation included. */
    class Appearance(
        /** Width and height of the placed box (swapped by a quarter turn). */
        val width: Double,
        val height: Double,
        val content: String,
        val resources: PdfDict?
    )

    /**
     * Paints [design] at [size] points wide (the unrotated width; the height
     * follows the design's aspect), turned by [rotation] degrees and painted
     * at [opacity]. [lines] are a dynamic stamp's small by/at lines; [image]
     * is required for [StampArt.IMAGE]. Returns null when there is nothing
     * paintable, so a bad spec writes no annotation instead of failing.
     */
    fun paint(
        builder: PdfDocumentBuilder,
        design: StampDesign,
        size: Double,
        rotation: Int,
        opacity: Double,
        lines: List<String>,
        image: ImageData?
    ): Appearance? {
        if (!size.isFinite() || size <= 0.0) return null
        if (design.art == StampArt.IMAGE && image == null) return null
        val aspect = design.aspect.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val w = size
        val h = size / aspect
        if (!h.isFinite() || h <= 0.0) return null

        val quarter = quarterTurn(rotation)
        val swapped = quarter == 90 || quarter == 270
        val painting = Painting(builder)
        val art = when (design.art) {
            StampArt.BANNER -> banner(painting, design, w, h, lines)
            StampArt.CERTIFICATE -> certificate(painting, design, w, h)
            StampArt.ARROW -> arrow(painting, design, w, h)
            StampArt.IMAGE -> imageArt(painting, image!!, w, h)
        }

        val alpha = if (opacity.isFinite()) opacity.coerceIn(0.0, 1.0) else 1.0
        val out = StringBuilder("q ")
        if (alpha < 1.0) out.append("/GS0 gs ")
        when (quarter) {
            90 -> out.append("0 1 -1 0 ").append(num(h)).append(" 0 cm ")
            180 -> out.append("-1 0 0 -1 ").append(num(w)).append(' ').append(num(h)).append(" cm ")
            270 -> out.append("0 -1 1 0 0 ").append(num(w)).append(" cm ")
        }
        out.append(art).append(" Q")
        return Appearance(
            if (swapped) h else w,
            if (swapped) w else h,
            out.toString(),
            painting.resources(alpha)
        )
    }

    // -- art ------------------------------------------------------------------

    /**
     * The banner: a ruled box with the word, plus — for a dynamic stamp — a
     * rule and the "By <author>" / date line under it.
     */
    private fun banner(p: Painting, design: StampDesign, w: Double, h: Double, lines: List<String>): String {
        val out = StringBuilder()
        background(out, design, w, h)
        out.append(border(design.borderColor, (minOf(w, h) * 0.045).coerceIn(1.2, 3.0), w, h, 0.0))

        val pad = maxOf(4.0, minOf(w, h) * 0.09)
        val name = p.font(StandardFont.HELVETICA_BOLD)
        val dynamic = lines.isNotEmpty()
        val limit = if (dynamic) h * 0.40 else h * 0.52
        val size = fitted(StandardFont.HELVETICA_BOLD, design.word, limit, w - 2.0 * pad).coerceAtLeast(4.0)
        val textWidth = width(StandardFont.HELVETICA_BOLD, design.word, size)
        val baseline = if (dynamic) h * 0.62 else (h - size * 0.72) / 2.0
        out.append(text(name, size, design.textColor, (w - textWidth) / 2.0, baseline, design.word))

        if (dynamic) {
            val ruleY = h * 0.42
            out.append("q ").append(strokeColor(design.borderColor)).append(" 0.7 w ")
                .append(num(pad)).append(' ').append(num(ruleY)).append(" m ")
                .append(num(w - pad)).append(' ').append(num(ruleY)).append(" l S Q ")
            out.append(smallLines(p, design, w, h, pad, lines))
        }
        return out.toString()
    }

    private fun smallLines(
        p: Painting,
        design: StampDesign,
        w: Double,
        h: Double,
        pad: Double,
        lines: List<String>
    ): String {
        val name = p.font(StandardFont.HELVETICA)
        val available = w - 2.0 * pad
        val base = minOf(h * 0.17, 9.5)
        val baseline = h * 0.16
        val out = StringBuilder()
        if (lines.size == 1) {
            val size = fitted(StandardFont.HELVETICA, lines[0], base, available).coerceAtLeast(4.0)
            val textWidth = width(StandardFont.HELVETICA, lines[0], size)
            out.append(text(name, size, design.textColor, (w - textWidth) / 2.0, baseline, lines[0]))
        } else {
            val left = lines[0]
            val right = lines[1]
            val gap = minOf(8.0, available * 0.08)
            val unit = unitWidth(StandardFont.HELVETICA, left) + unitWidth(StandardFont.HELVETICA, right)
            val size = if (unit > 0.0 && unit * base + gap > available) {
                ((available - gap) / unit).coerceAtLeast(4.0)
            } else {
                base
            }
            out.append(text(name, size, design.textColor, pad, baseline, left))
            out.append(
                text(name, size, design.textColor, w - pad - width(StandardFont.HELVETICA, right, size), baseline, right)
            )
        }
        return out.toString()
    }

    /** The certificate: two ruled frames and the word in a spaced serif face. */
    private fun certificate(p: Painting, design: StampDesign, w: Double, h: Double): String {
        val out = StringBuilder()
        background(out, design, w, h)
        val outer = (h * 0.10).coerceIn(2.0, 4.0)
        out.append(border(design.borderColor, outer, w, h, 0.0))
        val inset = outer + 3.0
        out.append(border(design.borderColor, 0.8, w, h, inset))

        val name = p.font(StandardFont.TIMES_BOLD)
        val pad = maxOf(6.0, minOf(w, h) * 0.12)
        val tracking = 0.08
        val unit = unitWidth(StandardFont.TIMES_BOLD, design.word) + tracking * maxOf(0, design.word.length - 1)
        var size = h * 0.46
        if (unit > 0.0) size = minOf(size, (w - 2.0 * (inset + pad)) / unit)
        size = size.coerceAtLeast(4.0)
        val textWidth = width(StandardFont.TIMES_BOLD, design.word, size) + tracking * size * maxOf(0, design.word.length - 1)
        out.append(
            text(name, size, design.textColor, (w - textWidth) / 2.0, h / 2.0 - size * 0.35, design.word, tracking * size)
        )
        return out.toString()
    }

    /** The signature stamp: the word above a heavy arrow pointing right. */
    private fun arrow(p: Painting, design: StampDesign, w: Double, h: Double): String {
        val out = StringBuilder()
        background(out, design, w, h)
        out.append(border(design.borderColor, (h * 0.06).coerceIn(1.5, 2.8), w, h, 0.0))

        val pad = maxOf(4.0, minOf(w, h) * 0.09)
        val name = p.font(StandardFont.HELVETICA_BOLD)
        val size = fitted(StandardFont.HELVETICA_BOLD, design.word, h * 0.36, w - 2.0 * pad).coerceAtLeast(4.0)
        val textWidth = width(StandardFont.HELVETICA_BOLD, design.word, size)
        out.append(text(name, size, design.textColor, (w - textWidth) / 2.0, h * 0.66, design.word))

        val y = h * 0.28
        val head = minOf(h * 0.20, w * 0.07)
        val thick = maxOf(1.4, h * 0.05)
        val x0 = pad
        val x1 = w - pad
        out.append("q ").append(strokeColor(design.textColor)).append(' ').append(num(thick))
            .append(" w 1 J ").append(num(x0)).append(' ').append(num(y)).append(" m ")
            .append(num(x1 - head * 0.8)).append(' ').append(num(y)).append(" l S Q ")
        out.append(fillColor(design.textColor)).append(' ')
            .append(num(x1)).append(' ').append(num(y)).append(" m ")
            .append(num(x1 - head)).append(' ').append(num(y + head * 0.45)).append(" l ")
            .append(num(x1 - head)).append(' ').append(num(y - head * 0.45)).append(" l h f")
        return out.toString()
    }

    private fun imageArt(p: Painting, image: ImageData, w: Double, h: Double): String {
        val name = p.image(image)
        val out = StringBuilder("q ")
        out.append(num(w)).append(" 0 0 ").append(num(h)).append(" 0 0 cm /").append(name).append(" Do Q")
        return out.toString()
    }

    // -- pieces ---------------------------------------------------------------

    private fun background(out: StringBuilder, design: StampDesign, w: Double, h: Double) {
        val fill = design.fillColor ?: return
        out.append(fillColor(fill)).append(" 0 0 ").append(num(w)).append(' ').append(num(h)).append(" re f ")
    }

    private fun border(color: PdfColor, lineWidth: Double, w: Double, h: Double, inset: Double): String {
        val half = lineWidth / 2.0 + inset
        return buildString {
            append("q ").append(strokeColor(color)).append(' ').append(num(lineWidth)).append(" w ")
            append(num(half)).append(' ').append(num(half)).append(' ')
            append(num(w - 2.0 * half)).append(' ').append(num(h - 2.0 * half)).append(" re S Q ")
        }
    }

    private fun text(
        name: String,
        size: Double,
        color: PdfColor,
        x: Double,
        baseline: Double,
        value: String,
        tracking: Double = 0.0
    ): String = buildString {
        append("BT /").append(name).append(' ').append(num(size)).append(" Tf ")
        if (tracking != 0.0) append(num(tracking)).append(" Tc ")
        append(fillColor(color)).append(" 1 0 0 1 ").append(num(x)).append(' ').append(num(baseline)).append(" Tm (")
        append(escape(value)).append(") Tj ET ")
    }

    /** Header/member fonts the appearance needs, collected while painting. */
    private class Painting(private val builder: PdfDocumentBuilder) {

        private val fonts = LinkedHashMap<String, PdfRef>()
        private var imageRef: PdfRef? = null

        fun font(font: StandardFont): String {
            val name = "F${fonts.size + 1}"
            fonts[name] = addStandardFont(builder, font)
            return name
        }

        fun image(data: ImageData): String {
            imageRef = ImageXObjects.create(builder, data)
            return "Im0"
        }

        fun resources(alpha: Double): PdfDict? {
            val entries = LinkedHashMap<PdfName, PdfObject>()
            if (fonts.isNotEmpty()) {
                entries[PdfName("Font")] = PdfDict(fonts.entries.associate { PdfName(it.key) to it.value })
            }
            val image = imageRef
            if (image != null) {
                entries[PdfName("XObject")] = PdfDict(mapOf(PdfName("Im0") to image))
            }
            if (alpha < 1.0) {
                entries[PdfName("ExtGState")] = PdfDict(
                    mapOf(
                        PdfName("GS0") to PdfDict(
                            mapOf(PdfName("ca") to PdfReal(alpha), PdfName("CA") to PdfReal(alpha))
                        )
                    )
                )
            }
            return if (entries.isEmpty()) null else PdfDict(entries)
        }
    }

    // -- helpers --------------------------------------------------------------

    /** WinAnsi bytes as a PDF literal string (§7.3.4.2); other characters become '?'. */
    private fun escape(value: String): String {
        val bytes = WinAnsi.encode(value)
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            when (val code = byte.toInt() and 0xFF) {
                '('.code -> out.append("\\(")
                ')'.code -> out.append("\\)")
                '\\'.code -> out.append("\\\\")
                else -> out.append(code.toChar())
            }
        }
        return out.toString()
    }

    /** [text] at size 1 in 1/1000 em units — the fit denominator. */
    private fun unitWidth(font: StandardFont, text: String): Double = Standard14.width(font, text, 1.0)

    private fun width(font: StandardFont, text: String, size: Double): Double =
        Standard14.width(font, text, size)

    /** The largest size up to [limit] whose text fits [available] points. */
    private fun fitted(font: StandardFont, text: String, limit: Double, available: Double): Double {
        val unit = unitWidth(font, text)
        return if (unit <= 0.0) limit else minOf(limit, available / unit)
    }

    /** The rotation as whole quarter turns, rounding to the nearest one. */
    private fun quarterTurn(rotation: Int): Int {
        val normalised = ((rotation % 360) + 360) % 360
        return ((normalised + 45) / 90 * 90) % 360
    }

    private fun fillColor(c: PdfColor): String = "${num(c.red)} ${num(c.green)} ${num(c.blue)} rg"

    private fun strokeColor(c: PdfColor): String = "${num(c.red)} ${num(c.green)} ${num(c.blue)} RG"

    private fun num(value: Double): String = PdfSerializer.formatReal(value)
}
