package com.pdfpaper.convert.pptx

import com.pdfpaper.convert.pdf.PdfColor
import com.pdfpaper.convert.pdf.PdfImage

/**
 * The part of PresentationML (ISO 29500-1 §19) the converter understands: a
 * deck is a slide size and a list of slides, a slide is a background and a list
 * of items in paint order (first item at the back).
 *
 * Everything here is already resolved and in points: DrawingML's EMUs, its
 * hundredths of a point and its theme colours are turned into plain numbers and
 * [PdfColor]s by [PptxReader], so the layout never looks at XML again. A text
 * body always arrives with concrete run formats: inheritance (theme, master,
 * layout, paragraph, run) is the reader's job, not the layout's.
 */

internal class SlideDeck(
    /** Slide size in points: the PDF pages are exactly this big. */
    val width: Double,
    val height: Double,
    val slides: List<SlideContent>,
    /** Everything the reader skipped or approximated, said once each. */
    val notes: List<String>
)

internal class SlideContent(
    /** 1-based position in the deck, in slide order. */
    val number: Int,
    val hidden: Boolean,
    val background: SlideBackground?,
    val items: List<SlideItem>
)

/** A slide background: a solid colour, a picture stretched over the slide, or both. */
internal class SlideBackground(val color: PdfColor?, val image: PdfImage?)

internal sealed interface SlideItem {
    val x: Double
    val y: Double
    val width: Double
    val height: Double
}

internal enum class SlideFigureKind { RECT, ROUND_RECT, ELLIPSE, LINE }

/**
 * A shape or connector. A connector's geometry is a straight line from one
 * corner of the box to the opposite one: [flipH]/[flipV] say which corners.
 */
internal class SlideFigure(
    override val x: Double,
    override val y: Double,
    override val width: Double,
    override val height: Double,
    val kind: SlideFigureKind,
    /** Resolved solid fill; null when the shape has no fill. */
    val fill: PdfColor?,
    val line: PdfColor?,
    val lineWidth: Double,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val text: SlideText? = null
) : SlideItem

internal class SlidePicture(
    override val x: Double,
    override val y: Double,
    override val width: Double,
    override val height: Double,
    val image: PdfImage
) : SlideItem

/** A text body with its insets and vertical anchor ("t", "ctr" or "b"). */
internal class SlideText(
    val leftInset: Double,
    val topInset: Double,
    val rightInset: Double,
    val bottomInset: Double,
    val anchor: String,
    /** False for `wrap="none"`: paragraphs stay on one line and may overflow. */
    val wrap: Boolean,
    val paragraphs: List<SlideParagraph>
)

internal class SlideParagraph(
    /** 0-based outline level (`a:pPr/@lvl`). */
    val level: Int,
    val align: SlideAlign,
    /** Text margin from the box's left edge (`a:pPr/@marL`), in points. */
    val marginLeft: Double,
    /** First-line indent (`a:pPr/@indent`), in points; negative for bullets. */
    val indent: Double,
    val spaceBefore: Double,
    val spaceAfter: Double,
    /** Line spacing as a multiple of the font's line height, when given. */
    val lineSpacingMultiple: Double?,
    /** Line spacing as a fixed height in points, when given. */
    val lineSpacingPoints: Double?,
    /** The bullet or number already formatted ("•", "2."), or null. */
    val marker: String?,
    /** The paragraph's own format: what a marker or an empty line is drawn with. */
    val base: SlideRunFormat,
    /** The tallest run's size; an empty line and a percentage spacing are that tall. */
    val size: Double,
    val runs: List<SlideRun>
)

internal enum class SlideAlign { LEFT, CENTER, RIGHT, JUSTIFY }

internal class SlideRun(val text: String, val format: SlideRunFormat)

/**
 * A run's formatting after inheritance, so every field is concrete. [family] is
 * the name the deck asks for (theme fonts are resolved to their typeface) or
 * null when neither the run nor the theme names one; whether a named font is
 * one of the PDF standard fonts is the layout's problem.
 */
internal class SlideRunFormat(
    val family: String?,
    val size: Double,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
    val color: PdfColor
)
