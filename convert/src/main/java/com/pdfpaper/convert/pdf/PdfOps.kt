package com.pdfpaper.convert.pdf

import com.pdfpaper.convert.raster.PngImage

/**
 * What every front end of the PDF writer produces: laid-out pages of drawable
 * items. Coordinates are in points and measured from the page's **top-left**,
 * y growing downwards; the writer flips them into PDF user space.
 *
 * The DOCX and PPTX readers both build [LayoutPage]s, so the writer has no
 * idea which kind of office file it is writing.
 */

/** A colour in the layouts' space: three 0..255 components. */
internal class PdfColor(val red: Int, val green: Int, val blue: Int) {
    override fun equals(other: Any?): Boolean =
        other is PdfColor && other.red == red && other.green == green && other.blue == blue

    override fun hashCode(): Int = (red shl 16) or (green shl 8) or blue

    override fun toString(): String = "#%02X%02X%02X".format(red, green, blue)

    companion object {
        val BLACK = PdfColor(0, 0, 0)
        val WHITE = PdfColor(0xFF, 0xFF, 0xFF)

        /** Six hex digits; "auto" and anything malformed mean "not a colour". */
        fun ofHex(value: String?): PdfColor? {
            val text = value?.trim() ?: return null
            val hex = if (text.startsWith("#")) text.substring(1) else text
            if (hex.length != 6) return null
            val number = hex.toIntOrNull(16) ?: return null
            return PdfColor((number shr 16) and 0xFF, (number shr 8) and 0xFF, number and 0xFF)
        }
    }
}

/**
 * A picture the writer can embed: either the original JPEG bytes (written
 * verbatim as `/DCTDecode`) or decoded pixels. Identity matters — the same
 * picture used twice is embedded once — so a model object keeps one [PdfImage]
 * alive instead of making a new one per placement.
 */
internal class PdfImage(
    val jpeg: ByteArray? = null,
    val raster: PngImage? = null,
    /** Part name in the source package, for deterministic naming and reports. */
    val name: String = "picture"
) {
    val usable: Boolean get() = jpeg != null || raster != null
    override fun toString(): String =
        when {
            jpeg != null -> "$name (JPEG ${jpeg.size} bytes)"
            raster != null -> "$name (raster ${raster.width}x${raster.height})"
            else -> "$name (empty)"
        }
}

internal sealed interface PdfOp

internal class TextOp(
    val x: Double,
    /** Baseline, from the top of the page. */
    val baseline: Double,
    val text: String,
    val font: StandardFont,
    val size: Double,
    val color: PdfColor,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Extra advance after every character, in points. */
    val tracking: Double = 0.0,
    /** Extra advance after every space, for justified lines, in points. */
    val wordSpacing: Double = 0.0
) : PdfOp

internal class RuleOp(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val color: PdfColor
) : PdfOp

internal class ImageOp(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val image: PdfImage
) : PdfOp

/**
 * An ellipse inscribed in the box. `null` fill or outline means "not drawn".
 * (PowerPoint shapes need it; WordprocessingML only ever draws rules.)
 */
internal class EllipseOp(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val fill: PdfColor?,
    val line: PdfColor? = null,
    val lineWidth: Double = 1.0
) : PdfOp

/** A straight stroked segment, in top-left coordinates. */
internal class LineOp(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val color: PdfColor,
    val width: Double
) : PdfOp

internal fun PdfOp.shifted(dx: Double, dy: Double): PdfOp = when (this) {
    is TextOp -> TextOp(x + dx, baseline + dy, text, font, size, color, underline, strike, tracking, wordSpacing)
    is RuleOp -> RuleOp(x + dx, y + dy, width, height, color)
    is ImageOp -> ImageOp(x + dx, y + dy, width, height, image)
    is EllipseOp -> EllipseOp(x + dx, y + dy, width, height, fill, line, lineWidth)
    is LineOp -> LineOp(x1 + dx, y1 + dy, x2 + dx, y2 + dy, color, width)
}

/** One finished PDF page: its box and the ops in paint order (back to front). */
internal class LayoutPage(val width: Double, val height: Double, val ops: List<PdfOp>)
