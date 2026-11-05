package com.pdfpaper.engine.table

/**
 * Turns a detected [Table] into Markdown, CSV, TSV or plain text (T35/T60).
 *
 * Values are copied verbatim: a number with leading zeros or a thousands
 * separator keeps exactly the characters the page showed. Merged cells are
 * flattened by [TableOptions.mergeHandling], because none of these formats has
 * a merged-cell syntax.
 */
object TableFormatter {

    /**
     * Flattens [table]'s merged cells into a rectangular grid of strings, one
     * list per row. A spanned slot repeats the value or stays empty according to
     * [TableOptions.mergeHandling].
     */
    fun grid(table: Table, options: TableOptions = TableOptions.DEFAULT): List<List<String>> {
        val placed = ArrayList<Pair<TableCell, Pair<Int, Int>>>() // cell to (row, col)
        val occupied = HashSet<Long>()
        var maxColumn = 0
        var rowIndex = 0
        for (row in table.rows) {
            var column = 0
            for (cell in row.cells) {
                while (occupied.contains(key(rowIndex, column))) column++
                val colSpan = cell.colSpan.coerceAtLeast(1)
                val rowSpan = cell.rowSpan.coerceAtLeast(1)
                for (dr in 0 until rowSpan) {
                    for (dc in 0 until colSpan) occupied.add(key(rowIndex + dr, column + dc))
                }
                placed += cell to (rowIndex to column)
                column += colSpan
                maxColumn = maxOf(maxColumn, column)
            }
            rowIndex++
        }
        val width = maxColumn
        if (width == 0) return emptyList()
        val grid = ArrayList<MutableList<String>>(rowIndex)
        for (r in 0 until rowIndex) grid += MutableList(width) { "" }
        for ((cell, at) in placed) {
            val (r, c) = at
            val repeat = options.mergeHandling == MergeHandling.REPEAT_VALUE
            for (dr in 0 until cell.rowSpan.coerceAtLeast(1)) {
                for (dc in 0 until cell.colSpan.coerceAtLeast(1)) {
                    val value = if (repeat || (dr == 0 && dc == 0)) cell.text else ""
                    val row = grid.getOrNull(r + dr) ?: continue
                    if (c + dc < row.size) row[c + dc] = value
                }
            }
        }
        return grid
    }

    /** Formats [table] in [format] with [options]. */
    fun format(
        table: Table,
        format: TableFormat,
        options: TableOptions = TableOptions.DEFAULT
    ): String {
        val grid = grid(table, options)
        if (grid.isEmpty()) return ""
        return when (format) {
            TableFormat.MARKDOWN -> markdown(grid, table.hasHeader)
            TableFormat.CSV -> delimited(grid, options.delimiter.char.toString(), quote = true)
            TableFormat.TSV -> delimited(grid, "\t", quote = false)
            TableFormat.TEXT -> text(grid, table.hasHeader)
        }
    }

    /** Plain text for a region that had no table: words grouped by baseline. */
    fun plainText(words: List<TableWord>): String {
        if (words.isEmpty()) return ""
        val sorted = words.sortedWith(compareByDescending<TableWord> { it.centerY }.thenBy { it.left })
        val out = StringBuilder()
        var line: MutableList<TableWord>? = null
        var lastY = Double.NaN
        val lines = ArrayList<List<TableWord>>()
        for (word in sorted) {
            val tolerance = maxOf(2.0, word.height * 0.6)
            if (line == null || kotlin.math.abs(word.centerY - lastY) > tolerance) {
                line = ArrayList()
                lines += line
                lastY = word.centerY
            }
            line!!.add(word)
        }
        for (l in lines) {
            val text = l.sortedBy { it.left }.joinToString(" ") { it.text }.trim()
            out.append(text).append('\n')
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ formats

    private fun markdown(grid: List<List<String>>, hasHeader: Boolean): String {
        val width = grid.maxOf { it.size }
        val out = StringBuilder()
        fun row(values: List<String>) {
            out.append('|')
            for (c in 0 until width) {
                out.append(' ').append(escapeMarkdown(values.getOrElse(c) { "" })).append(" |")
            }
            out.append('\n')
        }
        val header: List<String>
        val body: List<List<String>>
        if (hasHeader) {
            header = grid.first()
            body = grid.drop(1)
        } else {
            // Markdown has no table without a header row; emit a blank one.
            header = List(width) { "" }
            body = grid
        }
        row(header)
        out.append('|')
        for (c in 0 until width) out.append(" --- |")
        out.append('\n')
        for (r in body) row(r)
        return out.toString()
    }

    private fun escapeMarkdown(value: String): String =
        value.replace("|", "\\|").replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>")

    private fun delimited(grid: List<List<String>>, delimiter: String, quote: Boolean): String {
        val out = StringBuilder()
        for (row in grid) {
            for (c in row.indices) {
                if (c > 0) out.append(delimiter)
                out.append(field(row[c], delimiter, quote))
            }
            out.append('\n')
        }
        return out.toString()
    }

    private fun field(value: String, delimiter: String, quote: Boolean): String {
        if (!quote) {
            // TSV: a tab or newline would break the grid, so flatten it.
            return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        }
        val normalized = value.replace("\r\n", "\n").replace("\r", "\n")
        val needsQuote = normalized.contains(delimiter) ||
            normalized.contains('"') ||
            normalized.contains('\n')
        if (!needsQuote) return normalized
        return "\"" + normalized.replace("\"", "\"\"") + "\""
    }

    private fun text(grid: List<List<String>>, hasHeader: Boolean): String {
        val width = grid.maxOf { it.size }
        val columns = ArrayList<List<String>>(width)
        for (c in 0 until width) {
            columns += grid.map { it.getOrElse(c) { "" }.replace('\n', ' ') }
        }
        val rightAligned = columns.map { isNumericColumn(it, if (hasHeader) 1 else 0) }
        val sizes = columns.map { column -> column.maxOfOrNull { it.length } ?: 0 }
        val out = StringBuilder()
        for (r in grid.indices) {
            val cells = ArrayList<String>(width)
            for (c in 0 until width) {
                val value = grid[r].getOrElse(c) { "" }.replace('\n', ' ')
                cells += if (rightAligned[c]) value.padStart(sizes[c]) else value.padEnd(sizes[c])
            }
            val line = cells.joinToString("  ").trimEnd()
            out.append(line).append('\n')
        }
        return out.toString()
    }

    private val NUMBER_CHARS = Regex("^[\\s+\\-()]*[0-9][0-9.,\\s%]*[\\s%)\\u00A4€£¥$]*$")

    /**
     * True when every counted value in [column] looks like a number. [skipFirst]
     * skips the header row, so a "Qty" heading does not turn a numeric column
     * back into text.
     */
    private fun isNumericColumn(column: List<String>, skipFirst: Int): Boolean {
        var seen = false
        for (i in column.indices) {
            if (i < skipFirst) continue
            val v = column[i].trim()
            if (v.isEmpty()) continue
            seen = true
            if (!NUMBER_CHARS.matches(v)) return false
        }
        return seen
    }

    private fun key(row: Int, column: Int): Long = row.toLong() * 1_000_000L + column
}
