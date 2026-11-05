package com.pdfpaper.engine.table

import com.pdfpaper.engine.geom.PdfRect

/**
 * How a cell that spans more than one grid slot is flattened when the target
 * format cannot represent the merge (T35). Markdown and CSV have no merged
 * cells, so the caller chooses what the spanned slots hold.
 */
enum class MergeHandling {
    /** Copy the value into every spanned slot. */
    REPEAT_VALUE,

    /** The first slot keeps the value, the rest stay empty. */
    LEAVE_BLANK
}

/** The separator [TableFormat.CSV] uses (T35). */
enum class CsvDelimiter(val char: Char) {
    COMMA(','),

    /** For locales where the comma is the decimal separator. */
    SEMICOLON(';')
}

/** Output written by [TableFormatter] (T35/T60). */
enum class TableFormat {
    MARKDOWN,
    CSV,
    TSV,

    /** Plain text, columns aligned with spaces. */
    TEXT
}

/** Where a detected table's words came from (T35). */
enum class TableSource {
    /** A real text layer, read from the page content. */
    TEXT,

    /** OCR word boxes (T40/T25), so the result is less certain. */
    OCR
}

/**
 * Options for table detection and formatting (T35).
 *
 * Detection is a heuristic: [rowTolerance] and [columnTolerance] are fractions
 * of the median word height/width and control how aggressively baselines and
 * gaps are grouped.
 */
data class TableOptions(
    /** Treats the first detected row as a header for Markdown and [Table] metadata. */
    val headerRow: Boolean = true,
    val mergeHandling: MergeHandling = MergeHandling.REPEAT_VALUE,
    val delimiter: CsvDelimiter = CsvDelimiter.COMMA,
    /** Collapses runs of whitespace inside a cell and trims its edges. */
    val trimSpaces: Boolean = true,
    /** Grouping tolerance for baselines, as a fraction of the median word height. */
    val rowTolerance: Double = 0.6,
    /** Minimum gutter width between columns, as a fraction of the median word width. */
    val columnTolerance: Double = 0.9,
    /** A vertical/horizontal rule must be at most this thin (points) to count. */
    val maxRuleThickness: Double = 3.0,
    /** Recognizes equations when installed; null keeps them as plain text. */
    val formulaRecognizer: FormulaRecognizer? = null,
    /** Hard cap on detected rows, so a pathological page cannot run away. */
    val maxRows: Int = 2000,
    /** Hard cap on detected columns. */
    val maxColumns: Int = 200
) {
    companion object {
        val DEFAULT: TableOptions = TableOptions()
    }
}

/**
 * A box of words handed to [TableExtractor] (T35). It is the format a text run
 * or an OCR word is normalised into before detection, so the same structure
 * code runs for a text layer and for a scan.
 *
 * Coordinates are page space (PDF user units, y up), already mapped through the
 * image placement for OCR words.
 */
data class TableWord(
    val text: String,
    val x0: Double,
    val y0: Double,
    val x1: Double,
    val y1: Double,
    /** *Provider confidence in 0..1; 1.0 for text runs and exact boxes. */
    val confidence: Double = 1.0,
    /** Font size in points; 0 when unknown (then it is derived from the height). */
    val fontSize: Double = 0.0,
    /** True when the word's font is bold (a heading hint). */
    val bold: Boolean = false,
    /** True when the word is a superscript or subscript (a footnote-marker hint). */
    val raised: Boolean = false
) {
    val left: Double get() = minOf(x0, x1)
    val bottom: Double get() = minOf(y0, y1)
    val right: Double get() = maxOf(x0, x1)
    val top: Double get() = maxOf(y0, y1)
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (bottom + top) / 2.0
    val height: Double get() = top - bottom
    val width: Double get() = right - left
}

/**
 * One ruling line found in the page content (T35). Coordinates are page space.
 * [horizontal] lines run along x at y = [position]; vertical lines run along y
 * at x = [position]. [start]/[end] bound the line on its long axis.
 */
data class TableRule(
    val horizontal: Boolean,
    val position: Double,
    val start: Double,
    val end: Double,
    val thickness: Double
) {
    /** True when [position] lies inside [a]..[b] (inclusive, with slack). */
    fun covers(position: Double, a: Double, b: Double, slack: Double = 0.0): Boolean =
        position >= minOf(a, b) - slack && position <= maxOf(a, b) + slack
}

/**
 * One cell of a detected table (T35). [colSpan]/[rowSpan] are 1 for an ordinary
 * cell and the number of grid slots the cell covers when a ruling line is
 * missing. [footnotes] holds markers pulled out of the value (superscripts).
 */
data class TableCell(
    val text: String,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val isHeader: Boolean = false,
    val footnotes: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = text.isEmpty()
}

/** One row of a detected table (T35). */
data class TableRow(val cells: List<TableCell>) {
    val isEmpty: Boolean get() = cells.all { it.isEmpty }
}

/**
 * A detected table (T35). [rows] is a grid in reading order (top row first);
 * merged cells are stored once with their spans and the spanned slots of the
 * other rows are simply absent from the flattened [TableFormatter] grid.
 */
data class Table(
    val rows: List<TableRow>,
    /** True when the first row was detected (or chosen) as a header. */
    val hasHeader: Boolean = false
) {
    /** Number of grid columns, counting each cell's [TableCell.colSpan]. */
    val columns: Int get() = rows.maxOfOrNull { row -> row.cells.sumOf { it.colSpan } } ?: 0

    val isEmpty: Boolean get() = rows.isEmpty()
}

/**
 * Result of [TableExtractor] (T35). [table] is null when nothing looked like a
 * table, in which case the app offers plain text instead. [confidence] is 0..1
 * and [notes] explains what lowered it.
 */
class TableResult(
    val table: Table?,
    val confidence: Double,
    val source: TableSource,
    val notes: List<String> = emptyList(),
    /** The words actually used, so a caller can show a plain-text fallback. */
    val words: List<TableWord> = emptyList(),
    /** Interior column separators in page space, for a live overlay. */
    val columnBoundaries: List<Double> = emptyList(),
    /** Interior row separators in page space, for a live overlay. */
    val rowBoundaries: List<Double> = emptyList()
) {
    val detected: Boolean get() = table != null
}

/**
 * Recognizes an equation from plain text and its page box, returning its
 * Unicode (or set) form. Left null on [TableOptions] so equations stay as the
 * plain text the extractor read. T35's seam for a future formula engine.
 */
fun interface FormulaRecognizer {
    fun recognize(plainText: String, bounds: PdfRect): String?
}
