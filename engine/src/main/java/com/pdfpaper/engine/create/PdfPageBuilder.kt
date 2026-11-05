package com.pdfpaper.engine.create

import com.pdfpaper.engine.edit.ImageData
import com.pdfpaper.engine.edit.Standard14
import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.graphics.PdfColor

/**
 * One page of a [PdfBuilder].
 *
 * Coordinates put the origin at the top-left of the page and grow y downward,
 * which matches how text and images are usually laid out. Text is positioned
 * at its baseline. Content is recorded as small operations and written to a
 * single content stream when [PdfBuilder.build] runs.
 */
class PdfPageBuilder internal constructor(
    val size: PageSize,
    val orientation: PageOrientation
) {

    private val box = size.oriented(orientation)

    val width: Double get() = box.width
    val height: Double get() = box.height

    internal val ops = ArrayList<DrawOp>()

    /**
     * Draws one line of [text] with its baseline at [baselineY].
     *
     * Newlines are rejected: use [PdfFromText] for wrapped, paginated text.
     * [StandardFont.winAnsi] must be true (the two symbol fonts are not
     * supported by the WinAnsi content writer).
     */
    fun drawText(
        text: String,
        x: Double,
        baselineY: Double,
        size: Double = 12.0,
        font: StandardFont = StandardFont.HELVETICA,
        color: PdfColor = PdfColor.BLACK
    ) {
        if (text.isEmpty()) return
        require('\n' !in text && '\r' !in text) { "drawText accepts a single line; split newlines first" }
        require(size.isFinite() && size > 0.0) { "text size must be finite and positive" }
        require(font.winAnsi) { "${font.baseFont} does not use WinAnsi encoding" }
        requireFinite(x, "x")
        requireFinite(baselineY, "baselineY")
        ops += DrawOp.Text(text, x, baselineY, size, font, color)
    }

    /**
     * Draws [image] at ([x], [y]) with the given placement size. The image is
     * scaled to the box; its samples keep their aspect ratio only if the
     * caller chooses a matching width/height.
     *
     * [clip] restricts painting to a top-left rectangle (used by "fill"
     * placement to crop the overflow). It does not scale or move the image.
     */
    fun drawImage(
        image: ImageData,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        clip: PdfBox? = null
    ) {
        requireFinite(x, "x")
        requireFinite(y, "y")
        require(width.isFinite() && width > 0.0) { "image width must be finite and positive" }
        require(height.isFinite() && height > 0.0) { "image height must be finite and positive" }
        ops += DrawOp.Image(image, x, y, width, height, clip)
    }

    /** Draws a stroked line with round caps. */
    fun drawLine(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        width: Double = 1.0,
        color: PdfColor = PdfColor.BLACK
    ) {
        requireFinite(x1, "x1")
        requireFinite(y1, "y1")
        requireFinite(x2, "x2")
        requireFinite(y2, "y2")
        require(width.isFinite() && width > 0.0) { "line width must be finite and positive" }
        ops += DrawOp.Line(x1, y1, x2, y2, width, color)
    }

    /**
     * Draws a rectangle. [fill] defaults to black; pass null for an outline
     * only. A zero width or height draws nothing.
     */
    fun drawRect(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        fill: PdfColor? = PdfColor.BLACK,
        stroke: PdfColor? = null,
        strokeWidth: Double = 1.0
    ) {
        requireFinite(x, "x")
        requireFinite(y, "y")
        require(width.isFinite() && width >= 0.0) { "rectangle width must be finite and non-negative" }
        require(height.isFinite() && height >= 0.0) { "rectangle height must be finite and non-negative" }
        require(fill != null || stroke != null) { "a rectangle needs a fill or a stroke" }
        if (width == 0.0 || height == 0.0) return
        if (stroke != null) {
            require(strokeWidth.isFinite() && strokeWidth > 0.0) {
                "stroke width must be finite and positive"
            }
        }
        ops += DrawOp.Rect(x, y, width, height, fill, stroke, strokeWidth)
    }

    /** Width of [text] in points with [font] at [size]. */
    fun textWidth(
        text: String,
        size: Double = 12.0,
        font: StandardFont = StandardFont.HELVETICA
    ): Double = Standard14.width(font, text, size)

    private fun requireFinite(value: Double, name: String) {
        require(value.isFinite()) { "$name must be finite" }
    }
}

/** Drawing operations recorded by [PdfPageBuilder] and serialised at build time. */
internal sealed interface DrawOp {
    class Text(
        val text: String,
        val x: Double,
        val baselineY: Double,
        val size: Double,
        val font: StandardFont,
        val color: PdfColor
    ) : DrawOp

    class Image(
        val image: ImageData,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
        val clip: PdfBox?
    ) : DrawOp

    class Line(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double,
        val width: Double,
        val color: PdfColor
    ) : DrawOp

    class Rect(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
        val fill: PdfColor?,
        val stroke: PdfColor?,
        val strokeWidth: Double
    ) : DrawOp
}
