package com.pdfpaper.engine.table

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.CurveTo
import com.pdfpaper.engine.graphics.FillPath
import com.pdfpaper.engine.graphics.FillStrokePath
import com.pdfpaper.engine.graphics.LineTo
import com.pdfpaper.engine.graphics.MoveTo
import com.pdfpaper.engine.graphics.PathSegment
import com.pdfpaper.engine.graphics.StrokePath
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.ocr.OcrImage
import com.pdfpaper.engine.ocr.OcrWord
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.text.extractText
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Detects a table inside a page rectangle and reads its cells (T35/T60).
 *
 * Rows and columns come from two independent signals: the ruling lines the page
 * draws (borders between cells) and the alignment of the text itself, so a
 * borderless table is still found. A scan has no text layer, so the caller
 * passes OCR word boxes instead and the same structure code runs on them.
 *
 * Everything is a heuristic. A wrong guess moves a cell boundary; it never
 * changes a value and nothing is written back into the document.
 */
object TableExtractor {

    /**
     * Detects a table in [region] of [page] using the page's text layer and its
     * ruling lines. [ocrWords], when given, replaces the text layer (a scan).
     */
    fun extract(
        page: PdfPage,
        region: PdfRect,
        options: TableOptions = TableOptions.DEFAULT,
        ocrWords: List<TableWord>? = null,
        decoder: ExternalImageDecoder? = null
    ): Result<TableResult> = runCatching {
        val words = ocrWords ?: textWords(page, region)
        val rules = rulesOf(page, region, options, decoder)
        val source = if (ocrWords != null) TableSource.OCR else TableSource.TEXT
        extractFromWords(region, words, rules, options, source)
    }

    /** Maps OCR words into page space through the image's placement matrix. */
    fun fromOcrWords(
        words: List<OcrWord>,
        imageWidth: Int,
        imageHeight: Int,
        ctm: PdfMatrix
    ): List<TableWord> {
        if (imageWidth <= 0 || imageHeight <= 0) return emptyList()
        return words.map { word ->
            val left = word.bounds.left / imageWidth
            val right = word.bounds.right / imageWidth
            val top = word.bounds.top / imageHeight
            val bottom = word.bounds.bottom / imageHeight
            val corners = listOf(
                ctm.apply(left, top), ctm.apply(right, top),
                ctm.apply(left, bottom), ctm.apply(right, bottom)
            )
            TableWord(
                text = word.text,
                x0 = corners.minOf { it[0] },
                y0 = corners.minOf { it[1] },
                x1 = corners.maxOf { it[0] },
                y1 = corners.maxOf { it[1] },
                confidence = word.confidence,
                fontSize = 0.0
            )
        }
    }

    /** The structure step, shared by the text and OCR paths. */
    fun extractFromWords(
        region: PdfRect,
        allWords: List<TableWord>,
        rules: List<TableRule>,
        options: TableOptions,
        source: TableSource
    ): TableResult {
        val notes = ArrayList<String>()
        val words = allWords.filter {
            it.text.isNotBlank() && it.centerX in region.left..region.right &&
                it.centerY in region.bottom..region.top
        }.take(if (source == TableSource.OCR) 5000 else 20000)
        if (words.size < 4) {
            return TableResult(null, 0.0, source, listOf("too little text in the selection"), words)
        }

        val mh = median(words.map { it.height }.filter { it > 0.0 }) ?: 1.0
        val mw = median(words.map { it.width }.filter { it > 0.0 }) ?: 1.0

        val rows = rowClusters(words, rules, region, mh, options)
        if (rows.size < 2) {
            return TableResult(null, 0.0, source, listOf("no table detected: fewer than two rows"), words)
        }

        val boundaries = columnBoundaries(words, rules, region, mw, options)
        if (boundaries.size < 1) {
            return TableResult(null, 0.0, source, listOf("no table detected: a single column"), words)
        }
        val columns = boundaries.size + 1
        if (columns > options.maxColumns || rows.size > options.maxRows) {
            return TableResult(null, 0.0, source, listOf("too large to be a table"), words)
        }

        // Grid of word lists, top row first.
        val grid = Array(rows.size) { Array(columns) { ArrayList<TableWord>() } }
        for ((r, row) in rows.withIndex()) {
            for (word in row) {
                val c = boundaries.count { it < word.centerX }
                grid[r][c].add(word)
            }
        }

        val cells: Array<Array<TableCell?>> = Array(rows.size) { Array(columns) { null } }
        for (r in rows.indices) {
            for (c in 0 until columns) {
                cells[r][c] = makeCell(grid[r][c], options)
            }
        }

        mergeMultiLine(cells, options)
        val spans = if (rules.isNotEmpty()) spansOf(cells, rows, boundaries, rules, region) else null
        val merged = if (spans != null) applySpans(cells, spans, rows.size, columns) else collapse(cells)

        var tableRows = merged.filterNot { it.cells.all { cell -> cell.isEmpty } }
        if (tableRows.size < 2) {
            return TableResult(null, 0.0, source, listOf("no table detected: fewer than two rows"), words)
        }

        val bodySize = median(words.map { it.fontSize }.filter { it > 0.0 })
        val headerStrong = isHeaderRow(tableRows.first(), rows.first(), bodySize)
        val hasHeader = options.headerRow && tableRows.size >= 2
        if (hasHeader) {
            tableRows = tableRows.mapIndexed { index, row ->
                if (index == 0) TableRow(row.cells.map { it.copy(isHeader = true) }) else row
            }
        }

        val table = Table(tableRows, hasHeader)
        val confidence = confidence(table, rules, headerStrong, hasHeader, source, words, notes)
        val rowBoundaries = (0 until rows.size - 1).map { r ->
            (rows[r].minOf { it.bottom } + rows[r + 1].maxOf { it.top }) / 2.0
        }.sorted()
        return TableResult(
            table, confidence, source, notes, words,
            columnBoundaries = boundaries,
            rowBoundaries = rowBoundaries
        )
    }

    // ------------------------------------------------------------------- words

    private fun textWords(page: PdfPage, region: PdfRect): List<TableWord> {
        val runs = page.extractText().runs
        return runs.mapNotNull { run ->
            if (run.text.isBlank()) return@mapNotNull null
            val left = min(run.x0, run.x1)
            val right = max(run.x0, run.x1)
            val bottom = min(run.y0, run.y1)
            val top = max(run.y0, run.y1)
            if (right < region.left || left > region.right) return@mapNotNull null
            if (top < region.bottom || bottom > region.top) return@mapNotNull null
            val bold = run.fontName?.contains("Bold", ignoreCase = true) == true
            TableWord(run.text, left, bottom, right, top, fontSize = run.fontSize, bold = bold)
        }
    }

    // ------------------------------------------------------------------- rules

    private fun rulesOf(
        page: PdfPage,
        region: PdfRect,
        options: TableOptions,
        decoder: ExternalImageDecoder?
    ): List<TableRule> {
        val display = page.buildDisplayList(decoder)
        val rules = ArrayList<TableRule>()
        for (command in display.paths) {
            val path = when (command) {
                is StrokePath -> command.path
                is FillPath -> command.path
                is FillStrokePath -> command.path
                else -> null
            } ?: continue
            val points = transformedBounds(path.segments, command.matrix) ?: continue
            val minX = points[0]
            val minY = points[1]
            val maxX = points[2]
            val maxY = points[3]
            val w = maxX - minX
            val h = maxY - minY
            if (w <= 0.0 && h <= 0.0) continue
            val thickness = options.maxRuleThickness
            val horizontal = h <= thickness && w >= 5.0
            val vertical = w <= thickness && h >= 5.0
            if (horizontal) {
                val position = (minY + maxY) / 2.0
                if (position in region.bottom..region.top && maxX >= region.left && minX <= region.right) {
                    rules += TableRule(true, position, max(minX, region.left), min(maxX, region.right), h)
                }
            } else if (vertical) {
                val position = (minX + maxX) / 2.0
                if (position in region.left..region.right && maxY >= region.bottom && minY <= region.top) {
                    rules += TableRule(false, position, max(minY, region.bottom), min(maxY, region.top), w)
                }
            }
        }
        return rules
    }

    private fun transformedBounds(segments: List<PathSegment>, matrix: PdfMatrix): DoubleArray? {
        if (segments.isEmpty()) return null
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        fun include(x: Double, y: Double) {
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        for (segment in segments) {
            when (segment) {
                is MoveTo -> matrix.apply(segment.x, segment.y).let { include(it[0], it[1]) }
                is LineTo -> matrix.apply(segment.x, segment.y).let { include(it[0], it[1]) }
                is CurveTo -> {
                    matrix.apply(segment.x1, segment.y1).let { include(it[0], it[1]) }
                    matrix.apply(segment.x2, segment.y2).let { include(it[0], it[1]) }
                    matrix.apply(segment.x3, segment.y3).let { include(it[0], it[1]) }
                }
                else -> {}
            }
        }
        if (minX > maxX) return null
        return doubleArrayOf(minX, minY, maxX, maxY)
    }

    // -------------------------------------------------------------- row clusters

    private fun rowClusters(
        words: List<TableWord>,
        rules: List<TableRule>,
        region: PdfRect,
        mh: Double,
        options: TableOptions
    ): List<List<TableWord>> {
        val tolerance = max(1.0, options.rowTolerance * mh)
        val sorted = words.sortedByDescending { it.centerY }
        val clusters = ArrayList<MutableList<TableWord>>()
        var reference = Double.NaN
        for (word in sorted) {
            if (clusters.isEmpty() || abs(word.centerY - reference) > tolerance) {
                clusters += mutableListOf(word)
                reference = word.centerY
            } else {
                clusters.last().add(word)
                reference = (reference * (clusters.last().size - 1) + word.centerY) / clusters.last().size
            }
        }
        val hRules = rules.filter { it.horizontal }
        if (hRules.isEmpty()) return clusters
        // Split any cluster a horizontal rule crosses, so tight ruled rows separate.
        val out = ArrayList<List<TableWord>>()
        for (cluster in clusters) {
            var parts: ArrayList<List<TableWord>> = ArrayList()
            parts.add(cluster)
            for (rule in hRules) {
                val next = ArrayList<List<TableWord>>()
                for (part in parts) {
                    val inPart = part.filter { it.centerY > rule.position }
                    val below = part.filter { it.centerY <= rule.position }
                    if (inPart.isNotEmpty() && below.isNotEmpty()) {
                        next.add(inPart)
                        next.add(below)
                    } else {
                        next.add(part)
                    }
                }
                parts = next
            }
            out.addAll(parts)
        }
        return out
    }

    // ------------------------------------------------------------- column bounds

    private fun columnBoundaries(
        words: List<TableWord>,
        rules: List<TableRule>,
        region: PdfRect,
        mw: Double,
        options: TableOptions
    ): List<Double> {
        val vRules = rules.filter { !it.horizontal }
        if (vRules.isNotEmpty()) {
            val positions = vRules.map { it.position }.sorted()
            val clustered = clusterPositions(positions, max(1.5, mw * 0.5))
            val contentLeft = words.minOf { it.left }
            val contentRight = words.maxOf { it.right }
            return clustered.filter { it > contentLeft + 0.01 && it < contentRight - 0.01 }
        }
        return textBoundaries(words, mw, options)
    }

    private fun textBoundaries(words: List<TableWord>, mw: Double, options: TableOptions): List<Double> {
        val minGap = max(2.0, options.columnTolerance * mw)
        // Group words per baseline first, then collect the gaps inside each row.
        val sorted = words.sortedByDescending { it.centerY }
        val rows = ArrayList<MutableList<TableWord>>()
        var reference = Double.NaN
        val tolerance = max(1.0, 0.6 * (median(words.map { it.height }.filter { it > 0.0 }) ?: 1.0))
        for (word in sorted) {
            if (rows.isEmpty() || abs(word.centerY - reference) > tolerance) {
                rows += mutableListOf(word)
                reference = word.centerY
            } else {
                rows.last().add(word)
            }
        }
        if (rows.size < 2) return emptyList()
        val candidates = ArrayList<Pair<Int, Double>>()
        for ((r, row) in rows.withIndex()) {
            val ordered = row.sortedBy { it.left }
            for (i in 1 until ordered.size) {
                val gap = ordered[i].left - ordered[i - 1].right
                if (gap >= minGap) candidates += r to (ordered[i].left + ordered[i - 1].right) / 2.0
            }
        }
        if (candidates.isEmpty()) return emptyList()
        val clusters = ArrayList<MutableList<Pair<Int, Double>>>()
        for (candidate in candidates.sortedBy { it.second }) {
            val last = clusters.lastOrNull()
            if (last != null && abs(candidate.second - last.last().second) <= max(3.0, mw)) {
                last += candidate
            } else {
                clusters += mutableListOf(candidate)
            }
        }
        val support = max(2, (rows.size + 1) / 2)
        return clusters
            .filter { cluster -> cluster.map { it.first }.distinct().size >= support }
            .map { cluster -> cluster.map { it.second }.average() }
            .sorted()
    }

    private fun clusterPositions(sorted: List<Double>, tolerance: Double): List<Double> {
        if (sorted.isEmpty()) return emptyList()
        val out = ArrayList<Double>()
        var current = sorted.first()
        var count = 1
        for (i in 1 until sorted.size) {
            if (sorted[i] - current <= tolerance) {
                current = (current * count + sorted[i]) / (count + 1)
                count++
            } else {
                out += current
                current = sorted[i]
                count = 1
            }
        }
        out += current
        return out
    }

    // ------------------------------------------------------------------- cells

    private fun makeCell(words: List<TableWord>, options: TableOptions): TableCell {
        if (words.isEmpty()) return TableCell("")
        val body = words.maxOf { if (it.fontSize > 0.0) it.fontSize else it.height }
        val footnotes = ArrayList<String>()
        val kept = ArrayList<TableWord>()
        for (word in words) {
            val size = if (word.fontSize > 0.0) word.fontSize else word.height
            val marker = word.raised || (size < body * 0.8 && isFootnoteMarker(word.text))
            if (marker && footnotes.size < 12) {
                footnotes += word.text.trim()
            } else {
                kept += word
            }
        }
        val text = joinWords(if (kept.isEmpty()) words else kept, options.trimSpaces)
        return TableCell(text, footnotes = footnotes)
    }

    private fun joinWords(words: List<TableWord>, trim: Boolean): String {
        if (words.isEmpty()) return ""
        val ordered = words.sortedWith(compareBy({ it.centerY }, { it.left }))
        val sb = StringBuilder()
        var previous: TableWord? = null
        for (word in ordered) {
            val prev = previous
            if (prev != null) {
                val separated = if (abs(prev.centerY - word.centerY) > prev.height * 0.6) "\n" else " "
                sb.append(separated)
            }
            sb.append(word.text)
            previous = word
        }
        var text = sb.toString()
        if (trim) {
            text = text.split('\n').joinToString("\n") { line ->
                line.replace(Regex("\\s+"), " ").trim()
            }.trim('\n')
        }
        return text
    }

    /**
     * Merges a wrapped continuation line into the row above. A continuation is
     * a row with a single filled cell whose row above filled more than one and
     * left the continuation's column as one of them: the classic "a long cell
     * wraps onto a second line while the other columns stay empty" case. A row
     * with its own content in several columns is a real row (which is what lets
     * a vertical merge be told apart from a wrapped cell).
     */
    private fun mergeMultiLine(cells: Array<Array<TableCell?>>, options: TableOptions) {
        val rows = cells.size
        val columns = cells[0].size
        for (r in 1 until rows) {
            val above = cells[r - 1]
            val here = cells[r]
            val aboveFilled = above.count { it != null && !it.isEmpty }
            val hereFilled = here.count { it != null && !it.isEmpty }
            if (hereFilled != 1 || hereFilled >= aboveFilled) continue
            val emptied = (0 until columns).count { c ->
                above[c]?.isEmpty == false && (here[c] == null || here[c]!!.isEmpty)
            }
            if (emptied == 0) continue
            for (c in 0 until columns) {
                val cell = here[c] ?: continue
                if (cell.isEmpty) continue
                val target = above[c] ?: continue
                above[c] = target.copy(
                    text = if (target.isEmpty) cell.text else target.text + "\n" + cell.text,
                    footnotes = target.footnotes + cell.footnotes
                )
                here[c] = makeEmpty()
            }
        }
    }

    private fun makeEmpty(): TableCell = TableCell("")

    // ------------------------------------------------------------------- spans

    private class Span(val row: Int, val column: Int, val colSpan: Int, val rowSpan: Int)

    private fun spansOf(
        cells: Array<Array<TableCell?>>,
        rows: List<List<TableWord>>,
        boundaries: List<Double>,
        rules: List<TableRule>,
        region: PdfRect
    ): List<Span> {
        val rowBands = rows.map { row ->
            val bottom = row.minOf { it.bottom }
            val top = row.maxOf { it.top }
            bottom to top
        }
        val out = ArrayList<Span>()
        val columns = cells[0].size
        // Column spans: no vertical rule separates c and c+1 over this row's band.
        for (r in cells.indices) {
            var c = 0
            while (c < columns) {
                var span = 1
                while (c + span < columns) {
                    val x = boundaries.getOrNull(c + span - 1) ?: break
                    val band = rowBands.getOrNull(r) ?: break
                    if (verticalRuleBetween(rules, x, band.first, band.second)) break
                    span++
                }
                if (span > 1 && cells[r][c]?.isEmpty == false) out += Span(r, c, span, 1)
                c += span
            }
        }
        // Row spans: no horizontal rule separates r and r+1 in this column, the
        // value sits in the upper cell and the lower one is empty.
        for (r in 0 until cells.size - 1) {
            val top = rowBands.getOrNull(r) ?: continue
            val bottom = rowBands.getOrNull(r + 1) ?: continue
            val between = (top.first + bottom.second) / 2.0
            for (c in 0 until columns) {
                if (cells[r][c]?.isEmpty != false) continue
                if (cells[r + 1][c]?.isEmpty != true) continue
                if (horizontalRuleBetween(rules, between)) continue
                out += Span(r, c, 1, 2)
            }
        }
        return out
    }

    private fun verticalRuleBetween(
        rules: List<TableRule>,
        x: Double,
        bottom: Double,
        top: Double
    ): Boolean {
        val band = top - bottom
        if (band <= 0.0) return false
        for (rule in rules) {
            if (rule.horizontal) continue
            if (abs(rule.position - x) > 3.0) continue
            val lo = max(rule.start, bottom)
            val hi = min(rule.end, top)
            if (hi - lo >= band * 0.6) return true
        }
        return false
    }

    private fun horizontalRuleBetween(rules: List<TableRule>, y: Double): Boolean {
        for (rule in rules) {
            if (!rule.horizontal) continue
            if (abs(rule.position - y) > 4.0) continue
            return true
        }
        return false
    }

    private fun applySpans(
        cells: Array<Array<TableCell?>>,
        spans: List<Span>,
        rows: Int,
        columns: Int
    ): List<TableRow> {
        val consumed = Array(rows) { BooleanArray(columns) }
        val placed = Array<Array<TableCell?>>(rows) { arrayOfNulls(columns) }
        for (span in spans.sortedWith(compareBy({ it.row }, { it.column }))) {
            if (span.colSpan > 1) {
                val cell = cells[span.row][span.column] ?: continue
                if (cell.isEmpty) continue
                placed[span.row][span.column] = cell.copy(colSpan = span.colSpan)
                for (dc in 1 until span.colSpan) {
                    if (span.column + dc < columns) consumed[span.row][span.column + dc] = true
                }
            } else if (span.rowSpan > 1) {
                val cell = cells[span.row][span.column] ?: continue
                if (cell.isEmpty) continue
                placed[span.row][span.column] = cell.copy(rowSpan = 2)
                if (span.row + 1 < rows) consumed[span.row + 1][span.column] = true
            }
        }
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                if (placed[r][c] != null || consumed[r][c]) continue
                placed[r][c] = cells[r][c]
            }
        }
        return (0 until rows).map { r ->
            TableRow((0 until columns).mapNotNull { c -> placed[r][c] })
        }
    }

    private fun collapse(cells: Array<Array<TableCell?>>): List<TableRow> =
        cells.map { row -> TableRow(row.map { it ?: makeEmpty() }) }

    // --------------------------------------------------------------- confidence

    private fun isHeaderRow(
        row: TableRow,
        words: List<TableWord>,
        bodySize: Double?
    ): Boolean {
        if (words.isEmpty()) return false
        val bold = words.count { it.bold }
        if (bold >= words.size / 2 && bold > 0) return true
        if (bodySize != null && bodySize > 0.0) {
            val size = words.map { it.fontSize }.filter { it > 0.0 }.maxOrNull() ?: 0.0
            if (size > bodySize * 1.08) return true
        }
        return false
    }

    private fun confidence(
        table: Table,
        rules: List<TableRule>,
        headerStrong: Boolean,
        hasHeader: Boolean,
        source: TableSource,
        words: List<TableWord>,
        notes: MutableList<String>
    ): Double {
        var c = 1.0
        if (rules.isEmpty()) {
            c -= 0.2
            notes += "borderless table detected from text alignment"
        } else if (rules.none { !it.horizontal }) {
            c -= 0.05
            notes += "no vertical rules; columns follow the text"
        }
        val total = table.rows.sumOf { it.cells.size }.coerceAtLeast(1)
        val empty = table.rows.sumOf { row -> row.cells.count { it.isEmpty } }
        c -= 0.15 * (empty.toDouble() / total)
        if (hasHeader && !headerStrong) {
            c -= 0.03
            notes += "header row assumed"
        }
        if (source == TableSource.OCR) {
            c -= 0.25
            val mean = words.map { it.confidence }.average()
            if (mean.isFinite() && mean < 0.8) {
                c -= 0.1
                notes += "low OCR confidence"
            }
            notes += "structure read from OCR word boxes"
        }
        return c.coerceIn(0.05, 1.0)
    }

    // ------------------------------------------------------------------ helpers

    private fun isFootnoteMarker(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (Regex("^[\\[(\uFF08]?[0-9]{1,3}[\\])。\uFF09]?$").matches(t)) return true
        if (Regex("^[*†‡§¶#+]+$").matches(t)) return true
        return false
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}
