package com.pdfpaper.engine.reflow

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.ClosePath
import com.pdfpaper.engine.graphics.CurveTo
import com.pdfpaper.engine.graphics.DisplayList
import com.pdfpaper.engine.graphics.FillPath
import com.pdfpaper.engine.graphics.FillStrokePath
import com.pdfpaper.engine.graphics.LineTo
import com.pdfpaper.engine.graphics.MoveTo
import com.pdfpaper.engine.graphics.PdfPath
import com.pdfpaper.engine.graphics.StrokePath
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.text.PdfTextRun
import com.pdfpaper.engine.text.TextLine
import com.pdfpaper.engine.text.bodyFontSize
import com.pdfpaper.engine.text.box
import com.pdfpaper.engine.text.extractText
import com.pdfpaper.engine.text.pageLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns one page into reflow blocks (T33): paragraphs, headings, list items,
 * pictures and table regions, in reading order.
 *
 * The text comes from the engine's column-aware reading order (T11), so a
 * two-column paper reads down one column and then the other. Pictures and
 * table regions keep their page box, because the app paints those from the
 * page itself instead of inventing a rendering of them.
 *
 * Everything is a heuristic: a wrong guess moves a paragraph boundary or turns
 * a picture into a region, it never loses the page, and nothing is written
 * back into the document.
 */
object ReflowExtractor {

    /** Extracts [page]; a page that cannot be read becomes an empty reflow page. */
    fun extract(
        page: PdfPage,
        options: ReflowOptions = ReflowOptions.DEFAULT,
        decoder: ExternalImageDecoder? = null
    ): Result<ReflowPage> = runCatching {
        val box = PageGeometry.boxOf(page)
        val pageRect = PdfRect(box.left, box.bottom, box.right, box.top)
        val pageText = page.extractText()
        val layout = pageLayout(pageText)
        val display = page.buildDisplayList(decoder)

        val images = imageBoxes(display)
        val pageArea = pageRect.width * pageRect.height

        if (layout.isEmpty) {
            val scanned = images.any { coverage(it, pageRect) >= options.backgroundCoverage }
            if (scanned) {
                return@runCatching ReflowPage(
                    index = page.index,
                    blocks = emptyList(),
                    text = "",
                    scanned = true,
                    box = box,
                    pageText = ""
                )
            }
            val painted = images.any { area(it) >= options.minImageArea * pageArea } ||
                hasPaintedContent(display, pageRect)
            val blocks = if (painted) listOf(regionBlock(ReflowBlockKind.IMAGE, pageRect)) else emptyList()
            return@runCatching ReflowPage(
                index = page.index,
                blocks = blocks,
                text = "",
                scanned = false,
                box = box,
                pageText = ""
            )
        }

        val lines = layout.lines
        val bodySize = layout.bodyFontSize()
        val tables = if (options.detectTables) findTableRegions(pageText.runs, pageRect, options) else emptyList()

        val kept = ArrayList<TextLine>(lines.size)
        for (line in lines) {
            if (options.hidePageNumbers && isPageNumber(line, pageRect, options)) continue
            if (options.hideFootnotes && isFootnote(line, pageRect, bodySize, options)) continue
            if (tables.any { table ->
                    table.contains(
                        (line.bounds.x0 + line.bounds.x1) / 2.0,
                        (line.bounds.y0 + line.bounds.y1) / 2.0
                    )
                }
            ) {
                continue
            }
            kept += line
        }

        val columnRights = columnRights(kept)
        val textBlocks = ArrayList<ReflowBlock>()
        var index = 0
        var run: ParagraphRun? = null
        while (index < kept.size) {
            val line = kept[index]
            val current = run
            if (current == null || breaksParagraph(current, line, columnRights[index])) {
                current?.let { textBlocks += paragraphBlock(it, bodySize) }
                run = ParagraphRun(line)
            } else {
                current.lines += line
                current.left = min(current.left, line.bounds.x0)
            }
            index++
        }
        run?.let { textBlocks += paragraphBlock(it, bodySize) }

        // Pictures and tables keep the reading order of the text: each one goes
        // before the first block that starts below it.
        val regions = ArrayList<ReflowBlock>()
        for (picture in images) {
            if (area(picture) < options.minImageArea * pageArea) continue
            if (coverage(picture, pageRect) >= options.backgroundCoverage && kept.isNotEmpty()) continue
            regions += regionBlock(ReflowBlockKind.IMAGE, picture)
        }
        for (table in tables) regions += regionBlock(ReflowBlockKind.TABLE, table)
        regions.sortByDescending { it.bounds.top }

        val blocks = textBlocks
        for (region in regions) {
            var insert = blocks.size
            for (i in blocks.indices) {
                if (blocks[i].bounds.top <= region.bounds.bottom) {
                    insert = i
                    break
                }
            }
            blocks.add(insert, region)
        }

        // Number the text blocks inside the reflow string.
        val text = StringBuilder()
        val numbered = ArrayList<ReflowBlock>(blocks.size)
        for (block in blocks) {
            if (!block.isText) {
                numbered += block
                continue
            }
            if (text.isNotEmpty()) text.append('\n')
            val start = text.length
            text.append(block.text)
            numbered += block.copy(textStart = start, textEnd = start + block.text.length)
        }

        ReflowPage(
            index = page.index,
            blocks = numbered,
            text = text.toString(),
            scanned = false,
            box = box,
            pageText = layout.text
        )
    }

    // ------------------------------------------------------------ paragraphs

    private class ParagraphRun(first: TextLine) {
        val lines = arrayListOf(first)
        var left: Double = first.bounds.x0
    }

    private fun breaksParagraph(
        run: ParagraphRun,
        line: TextLine,
        columnRight: Double
    ): Boolean {
        val previous = run.lines.last()
        val size = lineSize(line)
        val previousSize = lineSize(previous)
        val scale = max(max(size, previousSize), 1.0)
        val rise = line.runs.first().originY - previous.runs.first().originY

        // A new column or region starts above the previous line.
        if (rise > max(1.0, 0.5 * scale)) return true
        // A wider line gap than normal line spacing.
        if (-rise > max(1.8 * scale, 1.0)) return true
        // A different size is a different block (body text next to a caption).
        if (previousSize > 0.0 && abs(size - previousSize) / previousSize > 0.12) return true
        // A marker starts a list item; its continuation lines have none.
        if (markerOf(line.text).isNotEmpty()) return true
        // The previous line stopped well short of the column edge. A line that
        // ends in a hyphen is a broken word, not the end of a paragraph.
        val brokenWord = previous.text.trimEnd().endsWith("-")
        if (!brokenWord && columnRight - previous.bounds.x1 > max(2.5 * previousSize, 12.0)) return true
        // A first-line indent starts a new paragraph.
        if (line.bounds.x0 - run.left > max(size, 6.0)) return true
        return false
    }

    private fun paragraphBlock(run: ParagraphRun, bodySize: Double): ReflowBlock {
        val lines = run.lines
        val bounds = PdfRect.union(lines.map { toRect(it) }) ?: toRect(lines.first())
        val size = lines.maxOf { lineSize(it) }
        val heading = lines.size <= MAX_HEADING_LINES && bodySize > 0.0 &&
            (size >= bodySize * HEADING_RATIO ||
                (size >= bodySize * BOLD_HEADING_RATIO && lines.all { isBoldLine(it) }))
        val marker = markerOf(lines.first().text)
        val kind = when {
            heading -> ReflowBlockKind.HEADING
            marker.isNotEmpty() -> ReflowBlockKind.LIST_ITEM
            else -> ReflowBlockKind.PARAGRAPH
        }
        val level = if (!heading) 0 else when {
            size >= bodySize * HEADING_LEVEL_1 -> 1
            size >= bodySize * HEADING_LEVEL_2 -> 2
            else -> 3
        }
        return ReflowBlock(
            kind = kind,
            text = joinLines(lines),
            textStart = -1,
            textEnd = -1,
            pageTextStart = lines.first().start,
            pageTextEnd = lines.last().start + lines.last().text.length,
            bounds = bounds,
            level = level,
            marker = if (kind == ReflowBlockKind.LIST_ITEM) marker else ""
        )
    }

    /**
     * Joins the lines of one paragraph. A hyphen at the end of a line is
     * dropped when the next line continues the same word, so the text reads
     * like the paragraph and not like the page.
     */
    private fun joinLines(lines: List<TextLine>): String {
        val out = StringBuilder()
        for (line in lines) {
            val piece = line.text
            if (out.isEmpty()) {
                out.append(piece)
                continue
            }
            val last = out[out.length - 1]
            val beforeLast = if (out.length >= 2) out[out.length - 2] else ' '
            if (last == '-' && beforeLast.isLetter() && piece.isNotEmpty() && piece[0].isLowerCase()) {
                out.setLength(out.length - 1)
                out.append(piece)
            } else {
                out.append(' ').append(piece)
            }
        }
        return out.toString().trim()
    }

    // ---------------------------------------------------------------- tables

    /**
     * Page regions that look like a table: at least [MIN_TABLE_ROWS] adjacent
     * rows of cells, each row with a wide gap, the gaps aligned down the rows,
     * and at least one row with three or more cells.
     *
     * The three-cell rule is what keeps a two-column page from being taken for
     * a table: a column of text is two cells wide, a table usually is not. A
     * two-column table is therefore read as two columns of text, which is a
     * documented limit rather than a wrong region.
     */
    private fun findTableRegions(
        runs: List<PdfTextRun>,
        page: PdfRect,
        options: ReflowOptions
    ): List<PdfRect> {
        if (runs.size < 6) return emptyList()
        val rows = groupRows(runs, page, options)
        val out = ArrayList<PdfRect>()
        var index = 0
        while (index < rows.size) {
            if (!isTableRow(rows[index])) {
                index++
                continue
            }
            var end = index
            while (end + 1 < rows.size && isTableRow(rows[end + 1]) &&
                aligned(rows[end].gaps, rows[end + 1].gaps, rows[end].size) &&
                adjacent(rows[end], rows[end + 1])
            ) {
                end++
            }
            val cells = (index..end).maxOf { rows[it].runs.size }
            if (end - index + 1 >= MIN_TABLE_ROWS && cells >= MIN_TABLE_CELLS) {
                var box = rows[index].bounds
                for (i in index + 1..end) box = box.union(rows[i].bounds)
                out += PdfRect(box.left - 2.0, box.bottom - 2.0, box.right + 2.0, box.top + 2.0)
                index = end + 1
            } else {
                index++
            }
        }
        return out
    }

    private class Row(
        val runs: List<PdfTextRun>,
        val bounds: PdfRect,
        val gaps: List<Double>,
        val size: Double
    )

    /** Groups runs into rows by baseline, top to bottom, and measures the gaps. */
    private fun groupRows(
        runs: List<PdfTextRun>,
        page: PdfRect,
        options: ReflowOptions
    ): List<Row> {
        val usable = runs
            .filter { it.text.isNotBlank() }
            .filter { !isPageNumberRun(it, page, options) }
            .filter { !isFootnoteRun(it, page, options) }
            .sortedWith(compareByDescending<PdfTextRun> { it.originY }.thenBy { it.originX })
        val rows = ArrayList<Row>()
        var index = 0
        while (index < usable.size) {
            val first = usable[index]
            val group = arrayListOf(first)
            var next = index + 1
            while (next < usable.size && sameRow(first, usable[next])) {
                group += usable[next]
                next++
            }
            group.sortBy { it.originX }
            val bounds = PdfRect.union(group.map { toRect(it) }) ?: toRect(first)
            rows += Row(group, bounds, gapsOf(group), group.maxOf { it.fontSize })
            index = next
        }
        return rows
    }

    private fun sameRow(first: PdfTextRun, other: PdfTextRun): Boolean {
        val size = max(max(first.fontSize, other.fontSize), 1.0)
        return abs(first.originY - other.originY) <= max(1.0, 0.4 * size)
    }

    private fun gapsOf(runs: List<PdfTextRun>): List<Double> {
        if (runs.size < 2) return emptyList()
        val out = ArrayList<Double>(runs.size)
        for (i in 1 until runs.size) {
            val previous = runs[i - 1]
            val current = runs[i]
            val gap = min(current.x0, current.x1) - max(previous.x0, previous.x1)
            val size = max(max(previous.fontSize, current.fontSize), 1.0)
            if (gap >= max(1.5 * size, 8.0)) {
                out += (max(previous.x0, previous.x1) + min(current.x0, current.x1)) / 2.0
            }
        }
        return out
    }

    private fun isTableRow(row: Row): Boolean = row.runs.size >= 2 && row.gaps.isNotEmpty()

    private fun aligned(a: List<Double>, b: List<Double>, size: Double): Boolean {
        val tolerance = max(2.0 * size, 10.0)
        return a.any { x -> b.any { y -> abs(x - y) <= tolerance } }
    }

    /** True when the rows follow each other without a paragraph-sized gap. */
    private fun adjacent(upper: Row, lower: Row): Boolean {
        val distance = upper.bounds.bottom - lower.bounds.top
        return distance <= max(2.5 * upper.size, 24.0)
    }

    private fun regionBlock(kind: ReflowBlockKind, rect: PdfRect): ReflowBlock = ReflowBlock(
        kind = kind,
        text = "",
        textStart = -1,
        textEnd = -1,
        pageTextStart = -1,
        pageTextEnd = -1,
        bounds = rect
    )

    // ---------------------------------------------------------------- images

    private fun imageBoxes(display: DisplayList): List<PdfRect> =
        display.images.mapNotNull { image ->
            unitSquare(image.matrix)?.takeIf { it.width > 0.5 && it.height > 0.5 }
        }

    /** Bounds of an image's unit square (§8.9.5.2) in page space. */
    private fun unitSquare(matrix: PdfMatrix): PdfRect? {
        val points = arrayOf(
            matrix.apply(0.0, 0.0),
            matrix.apply(1.0, 0.0),
            matrix.apply(1.0, 1.0),
            matrix.apply(0.0, 1.0)
        )
        if (points.any { !it[0].isFinite() || !it[1].isFinite() }) return null
        val xs = points.map { it[0] }
        val ys = points.map { it[1] }
        return PdfRect(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun area(rect: PdfRect): Double = rect.width * rect.height

    /** How much of [page] the rectangle covers, 0..1. */
    private fun coverage(rect: PdfRect, page: PdfRect): Double {
        val area = area(page)
        if (area <= 0.0) return 0.0
        val width = min(rect.right, page.right) - max(rect.left, page.left)
        val height = min(rect.top, page.top) - max(rect.bottom, page.bottom)
        if (width <= 0.0 || height <= 0.0) return 0.0
        return (width * height / area).coerceIn(0.0, 1.0)
    }

    /**
     * True when the page paints something that is not a page-covering
     * background: a filled shape or a stroke inside the page.
     */
    private fun hasPaintedContent(display: DisplayList, page: PdfRect): Boolean {
        for (command in display.commands) {
            val path = when (command) {
                is FillPath -> command.path
                is StrokePath -> command.path
                is FillStrokePath -> command.path
                else -> null
            } ?: continue
            val rect = pathRect(path, command.matrix) ?: continue
            if (coverage(rect, page) < 0.95) return true
        }
        return false
    }

    /** Bounds of [path] after [matrix] (control points, so never too small). */
    private fun pathRect(path: PdfPath, matrix: PdfMatrix): PdfRect? {
        if (path.isEmpty) return null
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        fun include(x: Double, y: Double) {
            val point = matrix.apply(x, y)
            if (!point[0].isFinite() || !point[1].isFinite()) return
            if (point[0] < minX) minX = point[0]
            if (point[0] > maxX) maxX = point[0]
            if (point[1] < minY) minY = point[1]
            if (point[1] > maxY) maxY = point[1]
        }
        for (segment in path.segments) {
            when (segment) {
                is MoveTo -> include(segment.x, segment.y)
                is LineTo -> include(segment.x, segment.y)
                is CurveTo -> {
                    include(segment.x1, segment.y1)
                    include(segment.x2, segment.y2)
                    include(segment.x3, segment.y3)
                }
                ClosePath -> Unit
            }
        }
        if (maxX < minX || maxY < minY) return null
        return PdfRect(minX, minY, maxX, maxY)
    }

    // ------------------------------------------------------- page furniture

    /** A page number: a bare number, "Page 4 of 20", "- 7 -", "iv" in a band. */
    private fun isPageNumber(line: TextLine, page: PdfRect, options: ReflowOptions): Boolean {
        val text = line.text.trim()
        if (text.isEmpty() || text.length > 32) return false
        if (!inBand(toRect(line), page, options)) return false
        return PAGE_NUMBER.matches(text)
    }

    private fun isPageNumberRun(run: PdfTextRun, page: PdfRect, options: ReflowOptions): Boolean {
        val text = run.text.trim()
        if (text.isEmpty() || text.length > 32) return false
        if (!inBand(toRect(run), page, options)) return false
        return PAGE_NUMBER.matches(text)
    }

    /** Small text in the bottom band: the place a footnote lives. */
    private fun isFootnote(
        line: TextLine,
        page: PdfRect,
        bodySize: Double,
        options: ReflowOptions
    ): Boolean {
        if (bodySize <= 0.0) return false
        val rect = toRect(line)
        if (!inFooterBand(rect, page, options)) return false
        return lineSize(line) < bodySize * FOOTNOTE_RATIO
    }

    private fun isFootnoteRun(run: PdfTextRun, page: PdfRect, options: ReflowOptions): Boolean {
        if (!inFooterBand(toRect(run), page, options)) return false
        // The row pass has no page-wide body size yet; the line pass does the
        // precise test, this one only keeps footnotes out of a table region.
        return run.fontSize < page.height * MIN_FOOTNOTE_PAGE_RATIO
    }

    private fun inBand(rect: PdfRect, page: PdfRect, options: ReflowOptions): Boolean {
        val height = page.height
        if (height <= 0.0) return false
        val topLimit = page.top - height * options.headerBand
        val bottomLimit = page.bottom + height * options.footerBand
        return rect.bottom >= topLimit - 1e-6 || rect.top <= bottomLimit + 1e-6
    }

    private fun inFooterBand(rect: PdfRect, page: PdfRect, options: ReflowOptions): Boolean {
        val height = page.height
        if (height <= 0.0) return false
        return rect.top <= page.bottom + height * options.footerBand + 1e-6
    }

    // ---------------------------------------------------------------- helpers

    private fun toRect(line: TextLine): PdfRect {
        val bounds = line.bounds
        return PdfRect(bounds.x0, bounds.y0, bounds.x1, bounds.y1)
    }

    private fun toRect(run: PdfTextRun): PdfRect {
        val bounds = run.box()
        return PdfRect(bounds.x0, bounds.y0, bounds.x1, bounds.y1)
    }

    /** Largest font size of the line. */
    private fun lineSize(line: TextLine): Double {
        var best = 0.0
        for (run in line.runs) {
            if (run.fontSize > best) best = run.fontSize
        }
        return if (best > 0.0) best else 1.0
    }

    /**
     * True when every run of the line that carries text uses a bold face. The
     * engine's text model has no style flag, so the `/BaseFont` name is the only
     * evidence (ISO 32000-1 §9.6.1); a subset prefix does not hide it.
     */
    private fun isBoldLine(line: TextLine): Boolean {
        var seen = false
        for (run in line.runs) {
            if (run.text.isBlank()) continue
            seen = true
            val name = run.fontName?.lowercase() ?: return false
            if (!("bold" in name || "black" in name || "heavy" in name)) return false
        }
        return seen
    }

    /**
     * Right edge of the column each line belongs to: the widest line that
     * overlaps it horizontally. A two-column page therefore gets one edge per
     * column, and a short line is only "short" against its own column.
     */
    private fun columnRights(lines: List<TextLine>): DoubleArray {
        val out = DoubleArray(lines.size)
        if (lines.size > MAX_COLUMN_LINES) {
            for (i in lines.indices) out[i] = lines[i].bounds.x1
            return out
        }
        for (i in lines.indices) {
            val a = lines[i].bounds
            var best = a.x1
            for (j in lines.indices) {
                if (i == j) continue
                val b = lines[j].bounds
                val overlap = min(a.x1, b.x1) - max(a.x0, b.x0)
                if (overlap < 0.5 * min(a.width, b.width)) continue
                if (b.x1 > best) best = b.x1
            }
            out[i] = best
        }
        return out
    }

    /** The marker at the start of a list item, or "" when there is none. */
    private fun markerOf(text: String): String {
        val trimmed = text.trimStart()
        BULLET_MARKER.find(trimmed)?.let { return it.groupValues[1] }
        NUMBER_MARKER.find(trimmed)?.let { return it.groupValues[1] }
        return ""
    }

    private val BULLET_MARKER = Regex("^([\u2022\u25e6\u25aa\u25ab\u00b7\u2013\u2014*\\-])\\s+")
    private val NUMBER_MARKER = Regex("^((?:\\d{1,3}|[A-Za-z]|[ivxlcdmIVXLCDM]{1,6})[.)])\\s+")
    private val PAGE_NUMBER = Regex(
        "^(?:[-\u2013\u2014]\\s*)?(?:page\\s*)?\\d{1,4}(?:\\s*(?:/|of)\\s*\\d{1,4})?(?:\\s*[-\u2013\u2014])?$" +
            "|^(?:[-\u2013\u2014]\\s*)?[ivxlcdm]{1,7}(?:\\s*[-\u2013\u2014])?$",
        RegexOption.IGNORE_CASE
    )

    private const val MAX_HEADING_LINES = 3
    private const val HEADING_RATIO = 1.22

    /**
     * A short bold block this much larger than the body is a heading even when
     * the size difference alone is under [HEADING_RATIO]: a word processor's
     * "Heading 2" is often only 20 % larger than the body, and a size-only rule
     * read it as running text (found while capturing the T33 screenshots).
     */
    private const val BOLD_HEADING_RATIO = 1.06
    private const val HEADING_LEVEL_1 = 1.6
    private const val HEADING_LEVEL_2 = 1.35
    private const val FOOTNOTE_RATIO = 0.9
    private const val MIN_FOOTNOTE_PAGE_RATIO = 0.011
    private const val MAX_COLUMN_LINES = 400
    private const val MIN_TABLE_ROWS = 3
    private const val MIN_TABLE_CELLS = 3
}
