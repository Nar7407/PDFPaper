package com.pdfpaper.engine.sign

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
 * Paints a [SignatureArtwork] into one self-contained form XObject appearance
 * stream (ISO 32000-1, §12.5.5).
 *
 * All artwork is laid out in a box [width] points wide. Caption lines take the
 * bottom strip; the signature itself fills the rest. The completed appearance
 * is turned by a quarter turn with a `cm`, exactly like [StampPainter], so the
 * annotation /BBox and /Rect stay the visual box in every viewer.
 */
internal object VisualSignaturePainter {

    class Appearance(
        /** The placed box after rotation. */
        val width: Double,
        val height: Double,
        val content: String,
        val resources: PdfDict?,
        /** Drawn strokes in final box coordinates, for an /InkList. */
        val inkLists: List<List<Double>>
    )

    fun paint(
        builder: PdfDocumentBuilder,
        artwork: SignatureArtwork,
        width: Double,
        rotation: Int,
        caption: SignatureCaption
    ): Appearance {
        require(width.isFinite() && width > 0.0) { "signature width must be a positive finite number" }
        val painting = Painting(builder)
        val captionLines = caption.lines()
        val layout = layout(painting, artwork, width, captionLines)
        val quarter = quarterTurn(rotation)

        val body = StringBuilder()
        body.append(layout.content)
        body.append(captionText(painting, captionLines, width, layout.captionSize, layout.captionHeight))

        if (body.isEmpty()) throw IllegalArgumentException("there is nothing to paint")
        val out = StringBuilder("q ")
        when (quarter) {
            90 -> out.append("0 1 -1 0 ").append(num(layout.height)).append(" 0 cm ")
            180 -> out.append("-1 0 0 -1 ").append(num(width)).append(' ')
                .append(num(layout.height)).append(" cm ")
            270 -> out.append("0 -1 1 0 0 ").append(num(width)).append(" cm ")
        }
        out.append(body).append(" Q")

        val swapped = quarter == 90 || quarter == 270
        val ink = layout.inkLists.map { stroke ->
            ArrayList<Double>(stroke.size).also { points ->
                var i = 0
                while (i + 1 < stroke.size) {
                    val x = stroke[i]
                    val y = stroke[i + 1]
                    val turned = when (quarter) {
                        90 -> doubleArrayOf(layout.height - y, x)
                        180 -> doubleArrayOf(width - x, layout.height - y)
                        270 -> doubleArrayOf(y, width - x)
                        else -> doubleArrayOf(x, y)
                    }
                    points += turned[0]
                    points += turned[1]
                    i += 2
                }
            }
        }
        return Appearance(
            if (swapped) layout.height else width,
            if (swapped) width else layout.height,
            out.toString(),
            painting.resources(),
            ink
        )
    }

    // -- Layout ---------------------------------------------------------------

    private class Layout(
        val content: String,
        val captionSize: Double,
        val captionHeight: Double,
        /** Height of the unrotated box, artwork plus captions. */
        val height: Double,
        /** Points in final (unrotated) box coordinates, in stroke order. */
        val inkLists: List<List<Double>>
    )

    private fun layout(
        painting: Painting,
        artwork: SignatureArtwork,
        width: Double,
        lines: List<String>
    ): Layout {
        val captionSize = captionSize(width, lines)
        val lineHeight = captionSize * 1.25
        val captionHeight = if (lines.isEmpty()) 0.0 else lines.size * lineHeight + captionSize * 0.25
        val content = StringBuilder()
        val inkLists = ArrayList<List<Double>>()

        when (artwork) {
            is SignatureArtwork.Drawn -> {
                if (artwork.width <= 0.0 || !artwork.width.isFinite()) {
                    throw IllegalArgumentException("stroke width must be a positive finite number")
                }
                val strokes = artwork.strokes.map { stroke -> stroke.points }
                    .filter { it.isNotEmpty() }
                if (strokes.isEmpty()) throw IllegalArgumentException("a drawn signature needs at least one stroke")
                var minX = Double.POSITIVE_INFINITY
                var minY = Double.POSITIVE_INFINITY
                var maxX = Double.NEGATIVE_INFINITY
                var maxY = Double.NEGATIVE_INFINITY
                for (stroke in strokes) {
                    for (point in stroke) {
                        require(point.x.isFinite() && point.y.isFinite()) {
                            "signature points must be finite"
                        }
                        minX = minOf(minX, point.x)
                        maxX = maxOf(maxX, point.x)
                        minY = minOf(minY, point.y)
                        maxY = maxOf(maxY, point.y)
                    }
                }
                val sourceWidth = maxX - minX
                val sourceHeight = maxY - minY
                if (sourceWidth <= 0.0 && sourceHeight <= 0.0) {
                    throw IllegalArgumentException("a drawn signature needs an area")
                }
                val pad = artwork.width / 2.0 + 1.0
                val available = (width - 2.0 * pad).coerceAtLeast(width * 0.5)
                val scale = if (sourceWidth > 0.0) {
                    available / sourceWidth
                } else {
                    available / maxOf(sourceHeight, 1.0)
                }
                val artHeight = if (sourceHeight > 0.0) {
                    sourceHeight * scale + 2.0 * pad
                } else {
                    maxOf(artwork.width * 2.0, width * 0.04)
                }
                val height = artHeight + captionHeight
                val base = captionHeight + pad

                content.append("q ").append(strokeColor(artwork.color)).append(" 1 J 1 j ")
                for (stroke in strokes) {
                    if (stroke.size == 1) {
                        val point = stroke[0]
                        val x = pad + (point.x - minX) * scale
                        val y = base + (point.y - minY) * scale
                        val pressure = pressure(point.pressure)
                        content.append(num(strokeWidth(artwork.width, pressure))).append(" w ")
                        content.append(num(x)).append(' ').append(num(y)).append(" m ")
                        content.append(num(x)).append(' ').append(num(y)).append(" l S ")
                        continue
                    }
                    for (i in 1 until stroke.size) {
                        val a = stroke[i - 1]
                        val b = stroke[i]
                        val x0 = pad + (a.x - minX) * scale
                        val y0 = base + (a.y - minY) * scale
                        val x1 = pad + (b.x - minX) * scale
                        val y1 = base + (b.y - minY) * scale
                        val pressure = pressure((a.pressure + b.pressure) / 2.0)
                        content.append(num(strokeWidth(artwork.width, pressure))).append(" w ")
                        content.append(num(x0)).append(' ').append(num(y0)).append(" m ")
                        content.append(num(x1)).append(' ').append(num(y1)).append(" l S ")
                    }
                }
                content.append("Q ")

                for (stroke in strokes) {
                    if (stroke.isEmpty()) continue
                    val points = ArrayList<Double>(stroke.size * 2)
                    for (point in stroke) {
                        points += pad + (point.x - minX) * scale
                        points += base + (point.y - minY) * scale
                    }
                    inkLists += points
                }
                return Layout(content.toString(), captionSize, captionHeight, height, inkLists)
            }

            is SignatureArtwork.Typed -> {
                val text = artwork.name
                if (text.isBlank()) throw IllegalArgumentException("a typed signature needs a name")
                if (!artwork.font.winAnsi) {
                    throw IllegalArgumentException("${artwork.font.baseFont} is not a text font for signatures")
                }
                val unitWidth = Standard14.width(artwork.font, text, 1.0)
                if (unitWidth <= 0.0) throw IllegalArgumentException("the typed name has no visible glyphs")
                val size = width * 0.96 / unitWidth
                val ascent = Standard14.ascent(artwork.font, size)
                val descent = Standard14.descent(artwork.font, size)
                val artHeight = (ascent - descent).coerceAtLeast(size * 0.2)
                val height = artHeight + captionHeight
                val textWidth = Standard14.width(artwork.font, text, size)
                val baseline = captionHeight + (artHeight - ascent + descent) / 2.0 - descent
                val fontName = painting.font(artwork.font)
                content.append(text(fontName, size, artwork.color, (width - textWidth) / 2.0, baseline, text))
                return Layout(content.toString(), captionSize, captionHeight, height, inkLists)
            }

            is SignatureArtwork.Image -> {
                val image = artwork.image
                if (image.width <= 0 || image.height <= 0) {
                    throw IllegalArgumentException("the signature image has no pixels")
                }
                val aspect = image.width.toDouble() / image.height.toDouble()
                if (!aspect.isFinite() || aspect <= 0.0) {
                    throw IllegalArgumentException("the signature image has an invalid aspect ratio")
                }
                val artHeight = width / aspect
                if (!artHeight.isFinite() || artHeight <= 0.0) {
                    throw IllegalArgumentException("the signature image has an invalid size")
                }
                val height = artHeight + captionHeight
                val imageName = painting.image(image)
                content.append("q 1 0 0 1 0 ").append(num(captionHeight)).append(" cm ")
                content.append(num(width)).append(" 0 0 ").append(num(artHeight)).append(" 0 0 cm /")
                content.append(imageName).append(" Do Q ")
                return Layout(content.toString(), captionSize, captionHeight, height, inkLists)
            }
        }
    }

    private fun captionSize(width: Double, lines: List<String>): Double {
        if (lines.isEmpty()) return 0.0
        val base = (width * 0.055).coerceIn(7.0, 10.0)
        var size = base
        for (line in lines) {
            val unit = Standard14.width(StandardFont.HELVETICA, line, 1.0)
            if (unit > 0.0) size = minOf(size, width * 0.94 / unit)
        }
        return size.coerceAtLeast(1.0)
    }

    private fun captionText(
        painting: Painting,
        lines: List<String>,
        width: Double,
        size: Double,
        captionHeight: Double
    ): String {
        if (lines.isEmpty()) return ""
        val font = painting.font(StandardFont.HELVETICA)
        val lineHeight = size * 1.25
        val out = StringBuilder()
        for ((index, line) in lines.withIndex()) {
            val textWidth = Standard14.width(StandardFont.HELVETICA, line, size)
            val baseline = captionHeight - (index + 1) * lineHeight +
                (lineHeight - size) / 2.0 - Standard14.descent(StandardFont.HELVETICA, size)
            out.append(text(font, size, PdfColor.BLACK, (width - textWidth) / 2.0, baseline, line))
        }
        return out.toString()
    }

    // -- Painting -------------------------------------------------------------

    private class Painting(private val builder: PdfDocumentBuilder) {

        private val fonts = LinkedHashMap<StandardFont, FontResource>()
        private var imageRef: PdfRef? = null

        private class FontResource(val name: String, val ref: PdfRef)

        fun font(font: StandardFont): String = fonts.getOrPut(font) {
            FontResource("F${fonts.size + 1}", addStandardFont(builder, font))
        }.name

        fun image(data: ImageData): String {
            if (imageRef == null) imageRef = ImageXObjects.create(builder, data)
            return "Im0"
        }

        fun resources(): PdfDict? {
            if (fonts.isEmpty() && imageRef == null) return null
            val entries = LinkedHashMap<PdfName, PdfObject>()
            if (fonts.isNotEmpty()) {
                val fontDict = LinkedHashMap<PdfName, PdfObject>()
                for (resource in fonts.values) fontDict[PdfName(resource.name)] = resource.ref
                entries[PdfName("Font")] = PdfDict(fontDict)
            }
            imageRef?.let { entries[PdfName("XObject")] = PdfDict(mapOf(PdfName("Im0") to it)) }
            return PdfDict(entries)
        }
    }

    // -- Helpers --------------------------------------------------------------

    private fun text(
        fontName: String,
        size: Double,
        color: PdfColor,
        x: Double,
        baseline: Double,
        value: String
    ): String = buildString {
        append("BT /").append(fontName).append(' ').append(num(size)).append(" Tf ")
        append(fillColor(color)).append(" 1 0 0 1 ").append(num(x)).append(' ')
            .append(num(baseline)).append(" Tm (")
        append(escape(value)).append(") Tj ET ")
    }

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

    private fun strokeWidth(base: Double, pressure: Double): Double =
        (base * pressure).coerceIn(0.05, base * 2.0)

    private fun pressure(value: Double): Double =
        if (value.isFinite()) value.coerceIn(0.15, 2.0) else 1.0

    /** The rotation as whole quarter turns, rounding to the nearest one. */
    private fun quarterTurn(rotation: Int): Int {
        val normalised = ((rotation % 360) + 360) % 360
        return ((normalised + 45) / 90 * 90) % 360
    }

    private fun fillColor(c: PdfColor): String =
        "${num(c.red.coerceIn(0.0, 1.0))} ${num(c.green.coerceIn(0.0, 1.0))} ${num(c.blue.coerceIn(0.0, 1.0))} rg"

    private fun strokeColor(c: PdfColor): String =
        "${num(c.red.coerceIn(0.0, 1.0))} ${num(c.green.coerceIn(0.0, 1.0))} ${num(c.blue.coerceIn(0.0, 1.0))} RG"

    private fun num(value: Double): String = PdfSerializer.formatReal(value)
}
