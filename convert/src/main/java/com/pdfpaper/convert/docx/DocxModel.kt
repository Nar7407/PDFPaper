package com.pdfpaper.convert.docx

import kotlin.math.roundToInt

/**
 * Length units. WordprocessingML measures in twentieths of a point ("twips",
 * §17.3.2.37 `ST_TwipsMeasure`), so one point is 20 twips and one inch 1440.
 */
const val TWIPS_PER_POINT = 20
const val TWIPS_PER_INCH = 1440

/** EMU per twip (914400 / 1440). */
const val EMU_PER_TWIP = 635L

object Twips {
    fun ofPoints(points: Double): Int = (points * TWIPS_PER_POINT).roundToInt()
    fun ofInches(inches: Double): Int = (inches * TWIPS_PER_INCH).roundToInt()
    fun ofEmu(emu: Long): Int = (emu / EMU_PER_TWIP).toInt()
}

/** `w:jc` — paragraph alignment. */
enum class DocAlign(val value: String) {
    LEFT("left"), CENTER("center"), RIGHT("right"), JUSTIFY("both")
}

/** How a PDF page becomes a document. */
enum class DocxMode(val id: String) {
    /** Editable paragraphs, lists and tables in reading order; pages reflow. */
    FLOW("flow"),

    /** Every line keeps its page position (`w:framePr`); pages stay as they were. */
    EXACT("exact");

    companion object {
        fun of(value: String?): DocxMode? = entries.firstOrNull { it.id.equals(value, ignoreCase = true) }
    }
}

/**
 * One formatted span of text (`w:r`). [sizeHalfPoints] is `w:sz` (half-points);
 * null leaves the size to the paragraph style.
 */
class DocRun(
    val text: String,
    val family: String? = null,
    val sizeHalfPoints: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** `w:val` attribute, six hex digits, or null for automatic. */
    val colorHex: String? = null
)

/** A bullet (`numId` of a bullet abstract) or numbered list reference `w:numPr`. */
class DocListRef(val numId: Int, val level: Int)

/**
 * `w:framePr`: the paragraph is positioned like a text frame, anchored to the
 * page ([x]/[y] in twips from the page's top-left). Exact mode uses one per
 * line so the original geometry survives.
 */
class DocFrame(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * A paragraph (`w:p`). [sectionEnd] carries the `w:sectPr` of the section that
 * ends with this paragraph (ISO 29500-1 §17.6.18).
 */
class DocParagraph(
    val runs: List<DocRun>,
    val styleId: String? = null,
    val list: DocListRef? = null,
    val alignment: DocAlign? = null,
    /** Twips from the left margin, `w:ind/@w:left`. */
    val indentLeft: Int = 0,
    /** Signed twips: positive is a first-line indent, negative a hanging one. */
    val indentFirstLine: Int = 0,
    val spaceBefore: Int = 0,
    val spaceAfter: Int = 0,
    /** Exact line height `w:spacing/@w:line`, or null for the style's spacing. */
    val lineHeight: Int? = null,
    /**
     * True to write `w:after`/`w:before` even when zero, so a paragraph can
     * suppress the built-in spacing instead of inheriting it.
     */
    val spacingExplicit: Boolean = false,
    val frame: DocFrame? = null,
    val keepWithNext: Boolean = false,
    val sectionEnd: DocSection? = null
) {
    fun withSectionEnd(section: DocSection): DocParagraph = DocParagraph(
        runs, styleId, list, alignment, indentLeft, indentFirstLine, spaceBefore, spaceAfter,
        lineHeight, spacingExplicit, frame, keepWithNext, section
    )
}

sealed class DocBlock

class DocParagraphBlock(val paragraph: DocParagraph) : DocBlock()

/** `w:tbl`; every cell holds exactly one paragraph. */
class DocTableBlock(
    val rows: List<List<DocParagraph>>,
    /** Twips, one per grid column. */
    val columnWidths: List<Int>
) : DocBlock()

/** A picture in its own paragraph, inline or anchored to the page. */
class DocImageBlock(val images: List<DocImage>) : DocBlock() {
    constructor(image: DocImage) : this(listOf(image))
}

class DocImage(
    val partName: String,
    val widthTwips: Int,
    val heightTwips: Int,
    val anchor: DocImageAnchor? = null,
    val flipH: Boolean = false,
    val flipV: Boolean = false
)

/** `wp:anchor` placement: [x]/[y] in twips from the page's top-left corner. */
class DocImageAnchor(val x: Int, val y: Int, val behindText: Boolean = false)

/**
 * A document section (`w:sectPr`): page size, margins and column layout.
 * Flow mode emits one per PDF page, which also supplies the page breaks.
 */
class DocSection(
    val pageWidth: Int,
    val pageHeight: Int,
    val marginLeft: Int,
    val marginRight: Int,
    val marginTop: Int,
    val marginBottom: Int,
    val columns: Int = 1,
    val columnSpace: Int = 708
)

/** One `w:num`: a numbering definition used by at least one paragraph. */
class DocNumInstance(val numId: Int, val abstractNumId: Int, val startOverrides: Map<Int, Int> = emptyMap())

class DocNumbering(val instances: List<DocNumInstance>) {
    companion object {
        val EMPTY = DocNumbering(emptyList())
    }
}

/**
 * A complete document ready to be written. [media] maps part names to bytes
 * (written verbatim) and [blocks] is the body in order.
 */
class DocxDocument(
    val blocks: List<DocBlock>,
    val media: Map<String, ByteArray>,
    val numbering: DocNumbering = DocNumbering.EMPTY,
    /** Used when no paragraph carries a section. */
    val finalSection: DocSection,
    val title: String = "Converted by PDFPaper",
    val pageCount: Int = 1
)
