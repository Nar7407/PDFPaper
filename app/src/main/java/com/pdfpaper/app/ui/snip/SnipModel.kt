package com.pdfpaper.app.ui.snip

import com.pdfpaper.app.ui.viewer.pageFractionToUserSpace
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.table.CsvDelimiter
import com.pdfpaper.engine.table.MergeHandling
import com.pdfpaper.engine.table.Table
import com.pdfpaper.engine.table.TableCell
import com.pdfpaper.engine.table.TableFormat
import com.pdfpaper.engine.table.TableFormatter
import com.pdfpaper.engine.table.TableOptions
import com.pdfpaper.engine.table.TableResult
import com.pdfpaper.engine.table.TableRow
import com.pdfpaper.engine.table.TableSource
import kotlin.math.roundToInt

/**
 * T60: pure logic behind the marquee "snip a table" tool. No Compose or Android
 * types, so every rule here is unit-tested on the JVM.
 */

/** The four outputs the snip tool offers. */
enum class SnipFormat(
    val label: String,
    val extension: String,
    val mime: String,
    val engine: TableFormat
) {
    MARKDOWN("Markdown", "md", "text/markdown", TableFormat.MARKDOWN),
    CSV("CSV", "csv", "text/csv", TableFormat.CSV),
    TSV("TSV", "tsv", "text/tab-separated-values", TableFormat.TSV),
    TEXT("Text", "txt", "text/plain", TableFormat.TEXT);

    companion object {
        fun fromName(name: String?): SnipFormat =
            entries.firstOrNull { it.name == name } ?: MARKDOWN
    }
}

/** How a merged cell is flattened into Markdown/CSV/TSV/text (T35). */
enum class MergedCells(val label: String) {
    REPEAT("Repeat the value"),
    BLANK("Leave blank");

    fun engine(): MergeHandling =
        if (this == REPEAT) MergeHandling.REPEAT_VALUE else MergeHandling.LEAVE_BLANK

    companion object {
        fun fromName(name: String?): MergedCells =
            entries.firstOrNull { it.name == name } ?: REPEAT
    }
}

/** The user's snip options (T60). */
data class SnipOptions(
    val headerRow: Boolean = true,
    val merged: MergedCells = MergedCells.REPEAT,
    val trimSpaces: Boolean = true,
    val delimiter: CsvDelimiter = CsvDelimiter.COMMA
) {
    fun engine(): TableOptions = TableOptions(
        headerRow = headerRow,
        mergeHandling = merged.engine(),
        delimiter = delimiter,
        trimSpaces = trimSpaces
    )
}

/** The last format and options, remembered across sessions. */
data class SnipSettings(
    val format: SnipFormat = SnipFormat.MARKDOWN,
    val options: SnipOptions = SnipOptions()
)

/** One page rectangle the user drew. */
data class SnipSelection(val page: Int, val rect: PdfRect)

/**
 * Normalises the drag from ([x0], [y0]) to ([x1], [y1]) in fractional page
 * coordinates (0..1, origin top-left) into a page-space rectangle. Returns null
 * when the drag is too small to be a box.
 */
fun snipRectFromDrag(
    plan: PageRenderPlan,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    minimumFraction: Float = 0.01f
): PdfRect? {
    if (kotlin.math.abs(x1 - x0) < minimumFraction || kotlin.math.abs(y1 - y0) < minimumFraction) {
        return null
    }
    val a = pageFractionToUserSpace(plan, x0.coerceIn(0f, 1f), y0.coerceIn(0f, 1f)) ?: return null
    val b = pageFractionToUserSpace(plan, x1.coerceIn(0f, 1f), y1.coerceIn(0f, 1f)) ?: return null
    val rect = PdfRect(a[0], a[1], b[0], b[1])
    return if (rect.width < 2.0 || rect.height < 2.0) null else rect
}

/** Maps a page-space point to a fractional position on the drawn page. */
fun snipFractionOf(plan: PageRenderPlan, x: Double, y: Double): Pair<Float, Float> {
    val point = plan.matrix.apply(x, y)
    val width = plan.pixelWidth.toFloat().coerceAtLeast(1f)
    val height = plan.pixelHeight.toFloat().coerceAtLeast(1f)
    return (point[0].toFloat() / width).coerceIn(0f, 1f) to (point[1].toFloat() / height).coerceIn(0f, 1f)
}

/** Combines per-page tables into one, dropping a repeated header row (T60). */
fun stitchTables(tables: List<Table>, dropRepeatedHeader: Boolean = true): Table? {
    if (tables.isEmpty()) return null
    if (tables.size == 1) return tables.first()
    val header = tables.first().rows.firstOrNull()?.cells?.map { it.text }
    val rows = ArrayList<TableRow>()
    for ((index, table) in tables.withIndex()) {
        var pageRows = table.rows
        if (index > 0 && dropRepeatedHeader && header != null && table.hasHeader) {
            val candidate = pageRows.firstOrNull()?.cells?.map { it.text }
            if (candidate == header) pageRows = pageRows.drop(1)
        }
        rows += pageRows
    }
    return Table(rows, tables.first().hasHeader)
}

/** Formats every stitched table in [settings]. */
fun formatSnip(tables: List<Table>, settings: SnipSettings): String {
    val stitched = stitchTables(tables) ?: return ""
    return TableFormatter.format(stitched, settings.format.engine, settings.options.engine())
}

/** Formats a single detection result. */
fun formatSnip(result: TableResult, settings: SnipSettings): String {
    val table = result.table ?: return TableFormatter.plainText(result.words)
    return TableFormatter.format(table, settings.format.engine, settings.options.engine())
}

/** The rows×columns of a table. */
fun tableSize(table: Table): Pair<Int, Int> = table.rows.size to table.columns

/** A short, honest confidence label. */
fun confidenceLabel(confidence: Double, source: TableSource, threshold: Double = 0.75): String {
    val percent = (confidence.coerceIn(0.0, 1.0) * 100).roundToInt()
    val sourceText = if (source == TableSource.OCR) "OCR" else "text"
    val flag = if (confidence < threshold) " · check the hard cases" else ""
    return "$percent% confident ($sourceText)$flag"
}

/** The line above the preview. */
fun describeSnip(result: TableResult): String {
    val table = result.table
    if (table == null) {
        return "No table detected. Plain text is offered instead."
    }
    val (rows, columns) = tableSize(table)
    return "Table detected · $rows rows × $columns columns · " +
        confidenceLabel(result.confidence, result.source)
}

/** The "hard case" notes that lowered the confidence. */
fun snipNotes(result: TableResult): List<String> = result.notes

/** A suggested file name for a saved snip. */
fun snipFileName(documentName: String, format: SnipFormat): String {
    val base = documentName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .removeSuffix(".pdf")
        .ifBlank { "table" }
    return "$base.${format.extension}"
}

/** Plain text of a table, used by Copy when there is no table. */
fun plainTextOf(result: TableResult): String = TableFormatter.plainText(result.words)

/** Formats a single cell for the preview grid, keeping line breaks visible. */
fun previewCell(cell: TableCell): String = cell.text

/**
 * A cell's row/column span, when a merge exists, so the preview can draw it as
 * one wider cell. Returns null for an ordinary cell.
 */
fun mergedSpan(cell: TableCell): Pair<Int, Int>? {
    if (cell.colSpan <= 1 && cell.rowSpan <= 1) return null
    return cell.colSpan to cell.rowSpan
}
