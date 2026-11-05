package com.pdfpaper.convert.docx

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** The body, the numbering definitions it uses and the per-page statistics. */
internal class BuiltDocument(
    val blocks: List<DocBlock>,
    val numbering: DocNumbering,
    val stats: List<DocxPageStats>,
    /** Body size the headings were measured against, in points. */
    val bodySize: Double,
    /** Space inserted after each body paragraph, in points (flow mode). */
    val paragraphGap: Double
) {
    val headings: Int get() = stats.sumOf { it.headings }
    val tables: Int get() = stats.sumOf { it.tables }
    val vectorPages: Int get() = stats.count { it.vectorsRasterized }
}

/**
 * Turns analysed pages into DOCX blocks.
 *
 * Flow mode rebuilds the document: lines are merged into paragraphs, sized-up
 * text becomes a heading, markers become real lists, runs of aligned lines
 * become tables and pictures are placed where they stood in the reading order.
 * Exact mode keeps the page geometry: every line becomes a paragraph frame at
 * its original coordinates and pictures stay anchored to the page.
 */
internal class DocxBlockBuilder(
    private val contents: List<PageContent>,
    private val mode: DocxMode
) {

    private val numbering = NumberingState()
    private val blocks = ArrayList<DocBlock>()
    private val stats = ArrayList<DocxPageStats>(contents.size)

    private var bodySize = 12.0
    private var paragraphGap = 0.0
    private var droppedLines: Set<PageLine> = emptySet()
    private var margins = Margins(720, 720, 720, 720)

    fun build(): BuiltDocument {
        bodySize = bodyFontSize(contents)
        droppedLines = if (mode == DocxMode.FLOW) runningHeads(contents) else emptySet()
        paragraphGap = if (mode == DocxMode.FLOW) paragraphGap(contents, droppedLines) else 0.0
        margins = documentMargins(contents, droppedLines)

        for (content in contents) {
            val counts = PageCounts()
            val pageBlocks = when (mode) {
                DocxMode.FLOW -> flowPage(content, counts)
                DocxMode.EXACT -> exactPage(content, counts)
            }
            closeSection(pageBlocks, section(content))
            blocks += pageBlocks
            stats += counts.toStats(content, mode)
        }
        return BuiltDocument(blocks, numbering.toNumbering(), stats, bodySize, paragraphGap)
    }

    // ------------------------------------------------------------- sections

    /**
     * Ends the page's section. The section properties live on the page's last
     * paragraph; a table or picture cannot carry them, so an empty paragraph is
     * appended (this is also what Word itself writes after a final table).
     */
    private fun closeSection(pageBlocks: MutableList<DocBlock>, section: DocSection) {
        val last = pageBlocks.lastOrNull()
        if (last is DocParagraphBlock) {
            pageBlocks[pageBlocks.size - 1] = DocParagraphBlock(last.paragraph.withSectionEnd(section))
        } else {
            pageBlocks += DocParagraphBlock(DocParagraph(emptyList(), sectionEnd = section))
        }
    }

    private fun section(content: PageContent): DocSection =
        if (mode == DocxMode.EXACT) {
            DocSection(
                pageWidth = Twips.ofPoints(content.width),
                pageHeight = Twips.ofPoints(content.height),
                marginLeft = 0, marginRight = 0, marginTop = 0, marginBottom = 0
            )
        } else {
            val width = Twips.ofPoints(content.width)
            val height = Twips.ofPoints(content.height)
            // Never let the margins eat the text area of a small page.
            val roomX = max(0, (width - Twips.ofInches(1.0)) / 2)
            val roomY = max(0, (height - Twips.ofInches(0.6)) / 2)
            DocSection(
                pageWidth = width,
                pageHeight = height,
                marginLeft = minOf(margins.left, roomX),
                marginRight = minOf(margins.right, roomX),
                marginTop = minOf(margins.top, roomY),
                marginBottom = minOf(margins.bottom, roomY),
                columns = max(1, content.columns.size),
                columnSpace = columnSpace(content)
            )
        }

    /** Width of the gutter between the page's columns, in twips. */
    private fun columnSpace(content: PageContent): Int {
        val gaps = ArrayList<Double>()
        for (i in 1 until content.columns.size) {
            gaps += content.columns[i].left - content.columns[i - 1].right
        }
        if (gaps.isEmpty()) return 708
        return Twips.ofPoints(gaps.sorted()[gaps.size / 2].coerceIn(12.0, 36.0))
    }

    // --------------------------------------------------------- flow: a page

    private fun flowPage(content: PageContent, counts: PageCounts): MutableList<DocBlock> {
        val pageBlocks = ArrayList<DocBlock>()
        counts.runningHeadsRemoved = content.lines.count { it in droppedLines }
        for (column in 0 until max(1, content.columns.size)) {
            val lines = content.lines.filter { it.column == column && it !in droppedLines }
            pageBlocks += flowColumn(content, column, lines, counts)
        }
        return pageBlocks
    }

    private fun flowColumn(
        content: PageContent,
        column: Int,
        lines: List<PageLine>,
        counts: PageCounts
    ): List<DocBlock> {
        val columnLeft = content.columnLeft(column)
        val columnRight = content.columnRight(column)
        val leading = medianGap(lines)

        val tables = tableRegions(lines, columnLeft, columnRight, leading)
        counts.tables += tables.size
        val inTable = BooleanArray(lines.size)
        for (region in tables) for (i in region.start..region.end) inTable[i] = true
        // Paragraph rules care about the text margin, not about a table that
        // happens to stick out past the body text.
        val textRight = lines.filterIndexed { index, _ -> !inTable[index] }
            .maxOfOrNull { it.right }
            ?.coerceAtLeast(columnLeft)
            ?: columnRight

        val groups = ArrayList<Group>()
        var current = ArrayList<PageLine>()
        for (i in lines.indices) {
            if (inTable[i]) {
                if (current.isNotEmpty()) {
                    groups += Group(current, columnLeft, textRight)
                    current = ArrayList()
                }
                continue
            }
            val line = lines[i]
            if (current.isNotEmpty() &&
                startsNewParagraph(current.last(), line, textRight, leading)
            ) {
                groups += Group(current, columnLeft, textRight)
                current = ArrayList()
            }
            current += line
        }
        if (current.isNotEmpty()) groups += Group(current, columnLeft, textRight)

        val positioned = ArrayList<Positioned>()
        for (region in tables) positioned += Positioned(lines[region.start].top, false, region.block)
        positioned += listBlocks(groups, counts)
        positioned += imageBlocks(content, column, counts, columnLeft, columnRight)
        // Stable sort: a picture that starts exactly at a line's top goes above it.
        positioned.sortWith(compareBy({ it.top }, { if (it.image) 0 else 1 }))
        return positioned.map { it.block }
    }

    /** Paragraphs (and their numbering) for the groups of one column. */
    private fun listBlocks(groups: List<Group>, counts: PageCounts): List<Positioned> {
        val markers = ArrayList<ListMarker?>(groups.size)
        for (group in groups) {
            val marker = detectMarker(group.text)
            markers += marker?.withLevel(levelFor(group))
        }
        val accepted = acceptMarkers(groups, markers)

        val out = ArrayList<Positioned>(groups.size)
        var open: OpenList? = null
        for (i in groups.indices) {
            val group = groups[i]
            val marker = accepted[i]
            counts.paragraphs++
            if (marker == null) {
                open = null
                if (headingLevel(group, marker) > 0) counts.headings++
                out += Positioned(group.top, false, DocParagraphBlock(paragraph(group, 0, null, null)))
                continue
            }
            counts.listItems++
            val continuing = open != null && open.kind == marker.kind && open.level == marker.level &&
                (marker.kind == ListKind.BULLET || marker.start == open.nextStart)
            val numId: Int
            if (continuing) {
                val list = open!!
                list.nextStart = marker.start + 1
                numId = list.numId
            } else {
                val overrides = if (marker.kind != ListKind.BULLET && marker.start != 1) {
                    mapOf(marker.level to marker.start)
                } else {
                    emptyMap()
                }
                numId = numbering.allocate(marker.kind.abstractNumId, overrides)
                open = OpenList(marker.kind, marker.level, numId, marker.start + 1)
            }
            out += Positioned(
                group.top, false,
                DocParagraphBlock(paragraph(group, marker.level, DocListRef(numId, marker.level), marker))
            )
        }
        return out
    }

    /**
     * Markers are only trusted when the column really looks like a list: a
     * bullet glyph is a list on its own, a number or letter needs a neighbour
     * of the same kind or an indented line.
     */
    private fun acceptMarkers(
        groups: List<Group>,
        markers: List<ListMarker?>
    ): List<ListMarker?> {
        val out = ArrayList<ListMarker?>(markers.size)
        for (i in markers.indices) {
            val marker = markers[i]
            if (marker == null) {
                out += null
                continue
            }
            val indented = groups[i].left - groups[i].columnLeft > 0.5 * groups[i].size
            val neighbour = (i > 0 && markers[i - 1]?.kind == marker.kind) ||
                (i + 1 < markers.size && markers[i + 1]?.kind == marker.kind)
            out += if (marker.kind == ListKind.BULLET || neighbour || indented) marker else null
        }
        return out
    }

    private fun paragraph(
        group: Group,
        level: Int,
        list: DocListRef?,
        marker: ListMarker?
    ): DocParagraph {
        val heading = headingLevel(group, marker)
        val runs = when {
            heading > 0 -> listOf(DocRun(group.text))
            list != null -> runList(group, marker?.length ?: 0)
            else -> runList(group, 0)
        }
        val alignment = alignment(group)
        if (heading > 0) {
            return DocParagraph(
                runs,
                styleId = "Heading$heading",
                alignment = alignment,
                spacingExplicit = true
            )
        }
        if (list != null) {
            return DocParagraph(
                runs,
                styleId = "ListParagraph",
                list = list,
                alignment = alignment,
                indentLeft = 720 * (level + 1),
                indentFirstLine = -360,
                spacingExplicit = true
            )
        }
        val indent = if (alignment == DocAlign.CENTER || alignment == DocAlign.RIGHT) 0.0
        else max(0.0, group.left - group.columnLeft)
        return DocParagraph(
            runs,
            alignment = alignment,
            indentLeft = Twips.ofPoints(indent),
            spacingExplicit = true,
            spaceAfter = Twips.ofPoints(paragraphGap)
        )
    }

    /** A short, sized-up paragraph is a heading; the ratio decides the level. */
    private fun headingLevel(group: Group, marker: ListMarker?): Int {
        if (marker != null) return 0
        if (group.text.length > 200 || group.lines.size > 4) return 0
        if (detectMarker(group.text) != null) return 0
        val ratio = group.size / bodySize
        return when {
            ratio >= 1.55 -> 1
            ratio >= 1.30 -> 2
            ratio >= 1.15 -> 3
            else -> 0
        }
    }

    private fun alignment(group: Group): DocAlign? {
        val width = group.columnRight - group.columnLeft
        if (width <= 0.0) return null
        val size = group.size
        if (group.lines.size == 1) {
            val textWidth = group.right - group.left
            val centre = (group.left + group.right) / 2
            val columnCentre = (group.columnLeft + group.columnRight) / 2
            if (textWidth < 0.75 * width && abs(centre - columnCentre) < 0.03 * width) return DocAlign.CENTER
            if (group.right > group.columnRight - 0.5 * size &&
                group.left > group.columnLeft + 1.0 * size &&
                textWidth < 0.6 * width
            ) {
                return DocAlign.RIGHT
            }
            return null
        }
        // Justified text: every line but the last one reaches the margin.
        val full = group.lines.dropLast(1).all { it.right > group.columnRight - 1.2 * size }
        return if (full) DocAlign.JUSTIFY else null
    }

    private fun runList(group: Group, strip: Int): List<DocRun> {
        val runs = ArrayList<DocRun>()
        var toStrip = strip
        val lines = group.lines
        for ((index, line) in lines.withIndex()) {
            if (index > 0) {
                val previous = lines[index - 1].text.trimEnd()
                val joinHyphen = (previous.endsWith("-") || previous.endsWith("\u2010")) &&
                    line.text.firstOrNull()?.isLowerCase() == true
                if (joinHyphen) {
                    dropTrailingHyphen(runs)
                } else if (runs.isNotEmpty()) {
                    runs += DocRun(" ", family = group.family, sizeHalfPoints = halfPoints(group.size))
                }
            }
            for (segment in line.segments) {
                var text = segment.text
                if (toStrip > 0) {
                    if (text.length <= toStrip) {
                        toStrip -= text.length
                        continue
                    }
                    text = text.substring(toStrip)
                    toStrip = 0
                }
                if (text.isEmpty()) continue
                runs += DocRun(
                    text,
                    family = segment.family,
                    sizeHalfPoints = halfPoints(segment.size),
                    bold = segment.bold,
                    italic = segment.italic
                )
            }
        }
        return trim(runs)
    }

    private fun dropTrailingHyphen(runs: MutableList<DocRun>) {
        while (runs.isNotEmpty()) {
            val last = runs.last()
            val trimmed = last.text.trimEnd()
            if (trimmed.isEmpty()) {
                runs.removeAt(runs.size - 1)
                continue
            }
            if (trimmed.endsWith("-") || trimmed.endsWith("\u2010")) {
                val head = trimmed.dropLast(1)
                runs.removeAt(runs.size - 1)
                if (head.isNotEmpty()) runs += copyOf(last, head)
                return
            }
            return
        }
    }

    private fun trim(runs: List<DocRun>): List<DocRun> {
        if (runs.isEmpty()) return runs
        val out = ArrayList<DocRun>(runs.size)
        for ((index, run) in runs.withIndex()) {
            var text = run.text
            if (index == 0) text = text.trimStart()
            if (index == runs.size - 1) text = text.trimEnd()
            if (text.isEmpty()) continue
            out += copyOf(run, text)
        }
        return out
    }

    private fun copyOf(run: DocRun, text: String): DocRun = DocRun(
        text, run.family, run.sizeHalfPoints, run.bold, run.italic, run.underline, run.colorHex
    )

    // ------------------------------------------------------- flow: pictures

    private fun imageBlocks(
        content: PageContent,
        column: Int,
        counts: PageCounts,
        columnLeft: Double,
        columnRight: Double
    ): List<Positioned> {
        val out = ArrayList<Positioned>()
        for (image in content.images) {
            if (imageColumn(content, image) != column) continue
            counts.images++
            if (image.backgroundLike) {
                // A page-sized picture belongs behind the text, where it was.
                out += Positioned(
                    image.top, true,
                    DocImageBlock(
                        DocImage(
                            image.partName,
                            Twips.ofPoints(image.width), Twips.ofPoints(image.height),
                            DocImageAnchor(
                                Twips.ofPoints(image.left), Twips.ofPoints(image.top),
                                behindText = true
                            ),
                            image.flipH, image.flipV
                        )
                    )
                )
                continue
            }
            var width = image.width
            var height = image.height
            val areaWidth = columnRight - columnLeft
            val textHeight = max(72.0, content.height - 72.0)
            if (width > areaWidth || height > textHeight) {
                val scale = minOf(areaWidth / width, textHeight / height)
                width *= scale
                height *= scale
                counts.warnings += "a picture was scaled down to fit the text area"
            }
            out += Positioned(
                image.top, true,
                DocImageBlock(
                    DocImage(
                        image.partName,
                        Twips.ofPoints(width), Twips.ofPoints(height),
                        null, image.flipH, image.flipV
                    )
                )
            )
        }
        return out
    }

    private fun imageColumn(content: PageContent, image: PageImage): Int {
        if (content.columns.size <= 1) return 0
        val centre = image.left + image.width / 2
        val index = content.columns.indexOfFirst { centre >= it.left - 1.0 && centre <= it.right + 1.0 }
        return if (index < 0) 0 else index
    }

    // --------------------------------------------------------- flow: tables

    private class TableRegion(val start: Int, val end: Int, val block: DocTableBlock)

    /**
     * Runs of consecutive lines that split into the same number of cells at
     * consistent x positions, wide enough to be a real table and not just a
     * line with a wide gap in it.
     */
    private fun tableRegions(
        lines: List<PageLine>,
        columnLeft: Double,
        columnRight: Double,
        leading: Double
    ): List<TableRegion> {
        val out = ArrayList<TableRegion>()
        var i = 0
        while (i < lines.size) {
            val first = cellsOf(lines[i])
            if (first == null) {
                i++
                continue
            }
            val rows = ArrayList<List<List<RunBox>>>()
            rows += first
            var j = i + 1
            while (j < lines.size) {
                val next = cellsOf(lines[j]) ?: break
                if (next.size != first.size) break
                if (!aligned(first, next, lines[i].size)) break
                val step = lines[j].top - lines[j - 1].top
                if (leading > 0.5 && step > max(2.5 * leading + lines[j - 1].size, 3.0 * lines[j].size)) break
                rows += next
                j++
            }
            val left = rows.minOf { row -> row.minOf { cell -> cell.first().left } }
            val right = rows.maxOf { row -> row.maxOf { cell -> cell.last().right } }
            if (rows.size >= 2 && right - left > 0.45 * (columnRight - columnLeft)) {
                out += TableRegion(i, j - 1, tableBlock(rows))
                i = j
            } else {
                i++
            }
        }
        return out
    }

    /** The runs of one line split at its wide gaps, or null when it has none. */
    private fun cellsOf(line: PageLine): List<List<RunBox>>? {
        val boundaries = line.cellBoundaries()
        if (boundaries.isEmpty()) return null
        val cells = ArrayList<List<RunBox>>()
        var start = 0
        for (boundary in boundaries) {
            cells += line.segments.subList(start, boundary)
            start = boundary
        }
        cells += line.segments.subList(start, line.segments.size)
        if (cells.count { cell -> cell.any { it.text.isNotBlank() } } < 2) return null
        return cells
    }

    private fun aligned(first: List<List<RunBox>>, next: List<List<RunBox>>, size: Double): Boolean {
        val tolerance = max(1.2 * size, 14.0)
        for (c in first.indices) {
            if (abs(first[c].first().left - next[c].first().left) > tolerance) return false
        }
        return true
    }

    private fun tableBlock(rows: List<List<List<RunBox>>>): DocTableBlock {
        val columns = rows[0].size
        val widths = IntArray(columns)
        for (c in 0 until columns) {
            val left = rows.minOf { it[c].first().left }
            val right = rows.maxOf { it[c].last().right }
            widths[c] = Twips.ofPoints(max(right - left + 6.0, 24.0))
        }
        val blockRows = rows.map { row ->
            row.map { cell ->
                DocParagraph(
                    cell.map { run ->
                        DocRun(
                            run.text,
                            family = run.family,
                            sizeHalfPoints = halfPoints(run.size),
                            bold = run.bold,
                            italic = run.italic
                        )
                    },
                    spacingExplicit = true
                )
            }
        }
        return DocTableBlock(blockRows, widths.toList())
    }

    // -------------------------------------------------------- exact: a page

    private fun exactPage(content: PageContent, counts: PageCounts): MutableList<DocBlock> {
        val pageBlocks = ArrayList<DocBlock>()
        val drawings = ArrayList<DocImage>()
        content.vectorBackgroundPart?.let { part ->
            drawings += DocImage(
                part,
                Twips.ofPoints(content.width), Twips.ofPoints(content.height),
                DocImageAnchor(0, 0, behindText = true)
            )
        }
        for (image in content.images) {
            counts.images++
            drawings += DocImage(
                image.partName,
                Twips.ofPoints(image.width), Twips.ofPoints(image.height),
                DocImageAnchor(
                    Twips.ofPoints(image.left), Twips.ofPoints(image.top),
                    behindText = image.backgroundLike
                ),
                image.flipH, image.flipV
            )
        }
        // One paragraph for the whole page: floating pictures cost no text flow.
        if (drawings.isNotEmpty()) pageBlocks += DocImageBlock(drawings)

        for (line in content.lines) {
            counts.paragraphs++
            val size = line.maxSize
            val pad = max(4.0, 0.25 * size)
            pageBlocks += DocParagraphBlock(
                DocParagraph(
                    runs = line.segments.map { segment ->
                        DocRun(
                            segment.text,
                            family = segment.family,
                            sizeHalfPoints = halfPoints(segment.size),
                            bold = segment.bold,
                            italic = segment.italic
                        )
                    },
                    frame = DocFrame(
                        x = Twips.ofPoints(line.left),
                        y = Twips.ofPoints(line.top),
                        width = Twips.ofPoints(max(line.right - line.left + pad, 2.0 * size)),
                        height = Twips.ofPoints(1.45 * size)
                    ),
                    // 1.21 em puts the baseline roughly where the page had it,
                    // give or take the font's descent, which the engine does
                    // not expose.
                    lineHeight = Twips.ofPoints(1.21 * size),
                    spacingExplicit = true
                )
            )
        }
        return pageBlocks
    }

    // --------------------------------------------------------- whole document

    private class Margins(val left: Int, val right: Int, val top: Int, val bottom: Int)

    /** Margins that keep the document's own text area, clamped to something sane. */
    private fun documentMargins(contents: List<PageContent>, dropped: Set<PageLine>): Margins {
        var left = Double.MAX_VALUE
        var right = Double.MAX_VALUE
        var top = Double.MAX_VALUE
        var bottom = Double.MAX_VALUE
        for (content in contents) {
            for (line in content.lines) {
                if (line in dropped) continue
                left = minOf(left, line.left)
                right = minOf(right, content.width - line.right)
                top = minOf(top, line.top)
                bottom = minOf(bottom, content.height - line.bottom)
            }
            for (image in content.images) {
                if (image.backgroundLike) continue
                left = minOf(left, image.left)
                right = minOf(right, content.width - image.left - image.width)
                top = minOf(top, image.top)
                bottom = minOf(bottom, content.height - image.top - image.height)
            }
        }
        if (left == Double.MAX_VALUE) return Margins(720, 720, 720, 720)
        val min = Twips.ofInches(0.25)
        val max = Twips.ofInches(1.5)
        fun clamp(value: Double) = Twips.ofPoints(max(0.0, value)).coerceIn(min, max)
        return Margins(clamp(left), clamp(right), clamp(top), clamp(bottom))
    }

    /** Extra vertical space the PDF put between paragraphs, in points. */
    private fun paragraphGap(contents: List<PageContent>, dropped: Set<PageLine>): Double {
        val inter = ArrayList<Double>()
        val intra = ArrayList<Double>()
        for (content in contents) {
            for (column in 0 until max(1, content.columns.size)) {
                val lines = content.lines.filter { it.column == column && it !in dropped }
                val leading = medianGap(lines)
                for (i in 1 until lines.size) {
                    val step = lines[i].top - lines[i - 1].bottom
                    if (step < 0.0 || step > 3.0 * lines[i].size) continue
                    if (startsNewParagraph(lines[i - 1], lines[i], content.columnRight(column), leading)) {
                        inter += step
                    } else {
                        intra += step
                    }
                }
            }
        }
        if (inter.isEmpty() || intra.isEmpty()) return 0.0
        return (median(inter) - median(intra)).coerceIn(0.0, 24.0)
    }

    /** The document's body size: the size most characters are set in. */
    private fun bodyFontSize(contents: List<PageContent>): Double {
        val weight = HashMap<Double, Int>()
        for (content in contents) {
            for (line in content.lines) {
                for (segment in line.segments) {
                    val key = (segment.size * 2).roundToInt() / 2.0
                    weight[key] = (weight[key] ?: 0) + segment.text.count { !it.isWhitespace() }
                }
            }
        }
        if (weight.isEmpty()) return 12.0
        return weight.entries
            .sortedWith(compareByDescending<Map.Entry<Double, Int>> { it.value }.thenBy { it.key })
            .first().key
    }

    /** Median vertical step between consecutive lines of one column. */
    private fun medianGap(lines: List<PageLine>): Double {
        val steps = ArrayList<Double>()
        for (i in 1 until lines.size) {
            val step = lines[i].top - lines[i - 1].bottom
            if (step > 0.0 && step < 3.0 * max(lines[i].size, lines[i - 1].size)) steps += step
        }
        return if (steps.isEmpty()) 0.0 else median(steps)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    // --------------------------------------------------------- running heads

    /**
     * Lines in the top or bottom strip of the page that are page numbers or
     * that repeat on most pages are running heads/footers, not body text.
     */
    private fun runningHeads(contents: List<PageContent>): Set<PageLine> {
        val pageCount = contents.size
        class Candidate(val line: PageLine, val normalized: String)

        val candidates = ArrayList<Candidate>()
        for (content in contents) {
            for (line in content.lines) {
                if (line.text.length > 80) continue
                val inTop = line.top < 0.08 * content.height
                val inBottom = line.bottom > 0.92 * content.height
                if (!inTop && !inBottom) continue
                candidates += Candidate(line, normalize(line.text))
            }
        }
        val counts = HashMap<String, Int>()
        for (candidate in candidates) counts[candidate.normalized] = (counts[candidate.normalized] ?: 0) + 1

        val dropped: MutableSet<PageLine> = Collections.newSetFromMap(IdentityHashMap())
        for (candidate in candidates) {
            val repeated = pageCount >= 3 && (counts[candidate.normalized] ?: 0) >= (pageCount + 1) / 2
            if (repeated || looksLikePageNumber(candidate.line.text)) dropped += candidate.line
        }
        return dropped
    }

    private fun normalize(text: String): String =
        text.lowercase().map { if (it.isDigit()) '#' else it }.joinToString("")
            .replace(Regex("\\s+"), " ").trim()

    // -------------------------------------------------------------- helpers

    /** True when [line] should start a new paragraph after [previous]. */
    private fun startsNewParagraph(
        previous: PageLine,
        line: PageLine,
        columnRight: Double,
        leading: Double
    ): Boolean {
        val size = max(previous.size, line.size)
        val step = line.top - previous.bottom
        // A gap bigger than the column's own leading separates paragraphs.
        if (leading > 0.0 && step > max(1.45 * leading, 0.6 * size)) return true
        // A line that starts further right opens an indented paragraph.
        if (line.left > previous.left + max(1.0, 0.35 * size)) return true
        // A line that stopped well short of the margin ended its paragraph.
        if (previous.right < columnRight - 2.5 * size && line.left > previous.left - 0.35 * size) return true
        // A size change is a heading or a caption, never a continuation.
        if (abs(line.size - previous.size) > 0.12 * size) return true
        // A list marker always starts something new.
        if (detectMarker(line.text) != null) return true
        return false
    }

    private fun halfPoints(points: Double): Int = (points * 2).roundToInt().coerceIn(8, 400)

    private class Positioned(val top: Double, val image: Boolean, val block: DocBlock)

    private class Group(
        val lines: List<PageLine>,
        val columnLeft: Double,
        val columnRight: Double
    ) {
        val text: String = join(lines)
        val left: Double = lines.minOf { it.left }
        val right: Double = lines.maxOf { it.right }
        val top: Double = lines.first().top

        private val dominant: PageLine = lines.maxByOrNull { line ->
            line.segments.sumOf { it.text.count { c -> !c.isWhitespace() } }
        }!!

        val size: Double get() = dominant.size
        val family: String get() = dominant.family

        private fun join(lines: List<PageLine>): String {
            val sb = StringBuilder()
            for ((index, line) in lines.withIndex()) {
                val text = line.text.trim()
                if (index > 0) {
                    val previous = lines[index - 1].text.trimEnd()
                    val joinHyphen = (previous.endsWith("-") || previous.endsWith("\u2010")) &&
                        text.firstOrNull()?.isLowerCase() == true
                    if (joinHyphen) {
                        if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
                    } else {
                        sb.append(' ')
                    }
                }
                sb.append(text)
            }
            return sb.toString()
        }
    }

    // ------------------------------------------------------------ list marks

    private enum class ListKind(val abstractNumId: Int) {
        BULLET(DocxTemplates.ABSTRACT_BULLET),
        DECIMAL(DocxTemplates.ABSTRACT_DECIMAL),
        LOWER_LETTER(DocxTemplates.ABSTRACT_LOWER_LETTER),
        UPPER_LETTER(DocxTemplates.ABSTRACT_UPPER_LETTER),
        LOWER_ROMAN(DocxTemplates.ABSTRACT_LOWER_ROMAN),
        UPPER_ROMAN(DocxTemplates.ABSTRACT_UPPER_ROMAN)
    }

    private class ListMarker(val kind: ListKind, val level: Int, val start: Int, val length: Int) {
        fun withLevel(level: Int): ListMarker = ListMarker(kind, level, start, length)
    }

    private class OpenList(val kind: ListKind, val level: Int, val numId: Int, var nextStart: Int)

    private class NumberingState {
        private val instances = ArrayList<DocNumInstance>()
        fun allocate(abstractNumId: Int, startOverrides: Map<Int, Int> = emptyMap()): Int {
            val id = instances.size + 1
            instances += DocNumInstance(id, abstractNumId, startOverrides)
            return id
        }

        fun toNumbering(): DocNumbering = DocNumbering(instances)
    }

    private fun detectMarker(text: String): ListMarker? {
        val trimmed = text.trimStart()
        BULLET.find(trimmed)?.let { return ListMarker(ListKind.BULLET, 0, 1, it.value.length - 1) }
        DECIMAL.find(trimmed)?.let {
            return ListMarker(ListKind.DECIMAL, 0, it.groupValues[1].toInt(), it.value.length - 1)
        }
        PAREN_DECIMAL.find(trimmed)?.let {
            return ListMarker(ListKind.DECIMAL, 0, it.groupValues[1].toInt(), it.value.length - 1)
        }
        ROMAN.find(trimmed)?.let { match ->
            val value = romanValue(match.groupValues[1])
            if (value in 1..30) {
                val upper = match.groupValues[1].first().isUpperCase()
                return ListMarker(
                    if (upper) ListKind.UPPER_ROMAN else ListKind.LOWER_ROMAN,
                    0, value, match.value.length - 1
                )
            }
        }
        UPPER_LETTER.find(trimmed)?.let {
            return ListMarker(
                ListKind.UPPER_LETTER, 0,
                it.groupValues[1][0] - 'A' + 1, it.value.length - 1
            )
        }
        LOWER_LETTER.find(trimmed)?.let {
            return ListMarker(
                ListKind.LOWER_LETTER, 0,
                it.groupValues[1][0] - 'a' + 1, it.value.length - 1
            )
        }
        return null
    }

    /** Nesting level from the paragraph's own indentation, level 0 at the margin. */
    private fun levelFor(group: Group): Int {
        val indent = group.left - group.columnLeft
        return when {
            indent < 1.0 * group.size -> 0
            indent < 3.0 * group.size -> 1
            else -> 2
        }
    }

    private fun looksLikePageNumber(text: String): Boolean = PAGE_NUMBER.matches(text.trim())

    private fun romanValue(text: String): Int {
        var total = 0
        var previous = 0
        for (ch in text.uppercase().reversed()) {
            val value = when (ch) {
                'I' -> 1
                'V' -> 5
                'X' -> 10
                'L' -> 50
                'C' -> 100
                'D' -> 500
                'M' -> 1000
                else -> return 0
            }
            if (value < previous) total -= value else total += value
            previous = max(previous, value)
        }
        return total
    }

    // ------------------------------------------------------------- statistics

    private class PageCounts {
        var paragraphs = 0
        var headings = 0
        var listItems = 0
        var tables = 0
        var images = 0
        var runningHeadsRemoved = 0
        val warnings = ArrayList<String>()

        fun toStats(content: PageContent, mode: DocxMode): DocxPageStats = DocxPageStats(
            pageNumber = content.pageNumber,
            widthPt = content.width,
            heightPt = content.height,
            paragraphs = paragraphs,
            headings = headings,
            listItems = listItems,
            tables = tables,
            images = images,
            imagesSkipped = content.imagesSkipped,
            invisibleRuns = content.invisibleRuns,
            rotatedRuns = content.rotatedRuns,
            runningHeadsRemoved = runningHeadsRemoved,
            vectorFills = content.vectorFills,
            vectorStrokes = content.vectorStrokes,
            vectorsRasterized = mode == DocxMode.EXACT && content.vectorBackgroundPart != null,
            unknownFonts = content.unknownFonts,
            warnings = content.warnings + warnings
        )
    }

    private companion object {
        val BULLET = Regex(
            "^([\\u2022\\u00b7\\u2219\\u25aa\\u25ab\\u25e6\\u2023\\u2043\\u25cf\\u25cb\\u25a0" +
                "\\u25a1\\u27a2\\u27a4\\u2013\\u2014*\\u30fb-])\\s+\\S"
        )
        val DECIMAL = Regex("^(\\d{1,3})[.)]\\s+\\S")
        val PAREN_DECIMAL = Regex("^\\((\\d{1,3})\\)\\s+\\S")
        val ROMAN = Regex("^([ivxlcdmIVXLCDM]{1,5})[.)]\\s+\\S")
        val UPPER_LETTER = Regex("^([A-Z])[.)]\\s+\\S")
        val LOWER_LETTER = Regex("^([a-z])[.)]\\s+\\S")
        val PAGE_NUMBER = Regex(
            "^[-\\u2013\\u2014\\s]*(page\\s*)?\\d{1,4}(\\s*(of|/|\\u2013|-)\\s*\\d{1,4})?\\s*[-\\u2013\\u2014\\s]*$",
            RegexOption.IGNORE_CASE
        )
    }
}
