package com.pdfpaper.convert.word

import com.pdfpaper.convert.pdf.PdfColor
import com.pdfpaper.convert.pdf.PdfImage
import com.pdfpaper.convert.raster.PngImage

/**
 * The part of WordprocessingML (ISO 29500-1 §17) the converter understands,
 * after style resolution: a document is a list of sections, a section is a list
 * of blocks, a block is a paragraph or a table, and a paragraph is a list of
 * runs. Nothing here knows about PDF — [WordLayout] turns it into pages.
 *
 * Everything is in points and in reading order. Word's own units (twips, half
 * points, EMUs) are converted by [DocxReader]; a field left null means "the
 * source did not say", which is what style inheritance resolves against.
 */

/** 914400 EMU per inch, so one point is 12700 EMU (§20.1.2.3). */
const val EMU_PER_POINT = 12700.0

/** One point in twentieths of a point (`w:ind`, `w:spacing`). */
const val TWIPS_PER_PT = 20.0

/** Word's colours are the writer's colours: this type is shared with the PDF side. */
internal typealias WordColor = PdfColor

internal enum class WordAlign(val id: String) {
    LEFT("left"), CENTER("center"), RIGHT("right"), JUSTIFY("both");

    companion object {
        fun of(value: String?): WordAlign? = when (value?.trim()?.lowercase()) {
            "left", "start" -> LEFT
            "center" -> CENTER
            "right", "end" -> RIGHT
            "both", "distribute" -> JUSTIFY
            else -> null
        }
    }
}

/** Line spacing (`w:spacing/@w:line` and `@w:lineRule`, §17.3.1.33). */
internal sealed interface WordLineHeight {
    /** `auto`: a multiple of single spacing (`w:line`/240). */
    class Multiple(val factor: Double) : WordLineHeight

    /** `exact`: the line box is exactly this.

     */
    class Exact(val points: Double) : WordLineHeight

    /** `atLeast`: at least this, more when the text needs it. */
    class AtLeast(val points: Double) : WordLineHeight
}

/**
 * A character format as the source states it. Every property is nullable so
 * that the style chain (docDefaults → paragraph style → character style →
 * direct `w:rPr`) can be merged in one direction: [over] lets the more specific
 * format win and keeps the rest.
 */
internal data class WordRunFormat(
    val family: String? = null,
    val size: Double? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val color: WordColor? = null,
    val allCaps: Boolean? = null,
    val smallCaps: Boolean? = null,
    val superscript: Boolean? = null,
    val subscript: Boolean? = null,
    /** `w:rPr/w:spacing/@w:val`: extra advance per character, in points. */
    val tracking: Double? = null
) {
    fun over(base: WordRunFormat?): WordRunFormat = if (base == null) {
        this
    } else {
        WordRunFormat(
            family = family ?: base.family,
            size = size ?: base.size,
            bold = bold ?: base.bold,
            italic = italic ?: base.italic,
            underline = underline ?: base.underline,
            strike = strike ?: base.strike,
            color = color ?: base.color,
            allCaps = allCaps ?: base.allCaps,
            smallCaps = smallCaps ?: base.smallCaps,
            superscript = superscript ?: base.superscript,
            subscript = subscript ?: base.subscript,
            tracking = tracking ?: base.tracking
        )
    }
}

/**
 * A paragraph format as the source states it (`w:pPr` plus the paragraph style
 * chain). Lengths are points; [indentFirst] is signed like `w:ind`:
 * `w:firstLine` positive, `w:hanging` negative.
 */
internal data class WordParaFormat(
    val styleId: String? = null,
    val alignment: WordAlign? = null,
    val indentLeft: Double? = null,
    val indentRight: Double? = null,
    val indentFirst: Double? = null,
    val spaceBefore: Double? = null,
    val spaceAfter: Double? = null,
    val lineHeight: WordLineHeight? = null,
    val pageBreakBefore: Boolean? = null,
    val keepWithNext: Boolean? = null,
    val keepLines: Boolean? = null,
    val outlineLevel: Int? = null,
    val numbering: WordListRef? = null,
    val runDefaults: WordRunFormat? = null
) {
    fun over(base: WordParaFormat?): WordParaFormat = if (base == null) {
        this
    } else {
        WordParaFormat(
            styleId = styleId ?: base.styleId,
            alignment = alignment ?: base.alignment,
            indentLeft = indentLeft ?: base.indentLeft,
            indentRight = indentRight ?: base.indentRight,
            indentFirst = indentFirst ?: base.indentFirst,
            spaceBefore = spaceBefore ?: base.spaceBefore,
            spaceAfter = spaceAfter ?: base.spaceAfter,
            lineHeight = lineHeight ?: base.lineHeight,
            pageBreakBefore = pageBreakBefore ?: base.pageBreakBefore,
            keepWithNext = keepWithNext ?: base.keepWithNext,
            keepLines = keepLines ?: base.keepLines,
            outlineLevel = outlineLevel ?: base.outlineLevel,
            numbering = numbering ?: base.numbering,
            runDefaults = runDefaults?.over(base.runDefaults) ?: base.runDefaults
        )
    }
}

/** `w:numPr`: which numbering definition a paragraph belongs to. */
internal class WordListRef(val numId: Int, val level: Int)

/** A list marker worked out by the reader (counter value, format, indents). */
internal class WordMarker(
    val text: String,
    val indentLeft: Double,
    val hanging: Double,
    val align: WordAlign,
    val family: String?
)

internal sealed interface WordRun

internal class WordTextRun(val text: String, val format: WordRunFormat) : WordRun

internal class WordTabRun(val format: WordRunFormat) : WordRun

/** `w:br` / `w:cr`; [page] is `w:type="page"`. */
internal class WordBreakRun(val page: Boolean, val format: WordRunFormat) : WordRun

/**
 * A picture (`w:drawing`). [anchor] is null for an inline picture; a floating
 * one carries its offsets and whether it sits behind the text.
 */
internal class WordImageRun(
    val image: WordImage,
    val width: Double,
    val height: Double,
    val anchor: WordAnchor?,
    val format: WordRunFormat
) : WordRun

internal class WordAnchor(
    val offsetX: Double?,
    val offsetY: Double?,
    /** `wp:align` when the source used an alignment instead of an offset. */
    val alignX: WordAlign?,
    /** `wp:align` of `wp:positionV`: "top", "center" or "bottom". */
    val alignY: String?,
    /** True when the offset is measured from the page edge, not the text area. */
    val fromPageX: Boolean,
    val fromPageY: Boolean,
    val behindText: Boolean
)

/** One picture part of the package, decoded once when the part is first used. */
internal class WordImage(
    val partName: String,
    val format: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val jpeg: ByteArray? = null,
    val raster: PngImage? = null
) {
    /** The writer's view of this picture; one instance per part, so it is embedded once. */
    val pdf: PdfImage by lazy { PdfImage(jpeg, raster, partName) }
}

internal sealed interface WordBlock

internal class WordParagraphBlock(val paragraph: WordParagraph) : WordBlock

internal class WordTableBlock(val table: WordTable) : WordBlock

/** A paragraph with its style chain already resolved. */
internal class WordParagraph(
    val runs: List<WordRun>,
    /** The paragraph mark's format: the base for empty lines and list markers. */
    val baseFormat: WordRunFormat,
    val alignment: WordAlign,
    val indentLeft: Double,
    val indentRight: Double,
    /** Signed: positive is a first-line indent, negative a hanging one. */
    val indentFirst: Double,
    val spaceBefore: Double,
    val spaceAfter: Double,
    val lineHeight: WordLineHeight?,
    val marker: WordMarker?,
    /** 0 for Heading 1 … 5 for Heading 6, or null for body text. */
    val headingLevel: Int?,
    val pageBreakBefore: Boolean,
    val keepWithNext: Boolean,
    val sectionEnd: WordSection?
) {
    val empty: Boolean get() = runs.isEmpty()
}

internal class WordTable(
    val rows: List<WordTableRow>,
    /** Twips-equivalent column widths in points; length ≥ every row's cells. */
    val columnWidths: List<Double>,
    val cellMargin: Double,
    val indentLeft: Double,
    /** False only when the source states there are no borders at all. */
    val borders: Boolean
)

internal class WordTableRow(val cells: List<WordTableCell>, val height: Double?)

internal class WordTableCell(
    val blocks: List<WordBlock>,
    val width: Double,
    val shading: WordColor?,
    /** `w:gridSpan`: how many grid columns the cell covers (1 when absent). */
    val spanAcross: Int,
    /** `w:vMerge`: the cell continues the one above. */
    val mergedDown: Boolean
)

/**
 * Section properties (`w:sectPr`, §17.6.18): the page box the content of one
 * section is laid out in. Content up to the next section's `w:sectPr` belongs
 * to this section.
 */
internal class WordSection(
    val pageWidth: Double,
    val pageHeight: Double,
    val marginLeft: Double,
    val marginRight: Double,
    val marginTop: Double,
    val marginBottom: Double,
    /** `w:type`: false only for a continuous section break. */
    val startsNewPage: Boolean,
    /** `w:cols/@w:num`; > 1 means a multi-column section. */
    val columns: Int,
    val columnSpace: Double
) {
    val textWidth: Double get() = (pageWidth - marginLeft - marginRight).coerceAtLeast(36.0)
    val textHeight: Double get() = (pageHeight - marginTop - marginBottom).coerceAtLeast(36.0)

    companion object {
        /** What Word uses for a document without `w:sectPr`: Letter, 1-inch margins. */
        fun default(): WordSection = WordSection(
            pageWidth = 612.0, pageHeight = 792.0,
            marginLeft = 72.0, marginRight = 72.0, marginTop = 72.0, marginBottom = 72.0,
            startsNewPage = true, columns = 1, columnSpace = 36.0
        )
    }
}

internal class WordSectionContent(val section: WordSection, val blocks: List<WordBlock>)

internal class WordDocument(
    val sections: List<WordSectionContent>,
    val defaultSize: Double,
    val defaultFamily: String?,
    val title: String?,
    /** `w:defaultTabStop` from settings.xml, in points. */
    val tabStop: Double,
    /** Everything the reader skipped or could not map, said once each. */
    val notes: List<String>
)

