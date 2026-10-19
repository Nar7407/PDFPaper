package com.pdfpaper.convert.docx

import com.pdfpaper.convert.FontMapping
import com.pdfpaper.convert.media.ImageBag
import com.pdfpaper.convert.placementOf
import com.pdfpaper.convert.raster.PngWriter
import com.pdfpaper.convert.raster.SoftwareRenderer
import com.pdfpaper.engine.graphics.DisplayList
import com.pdfpaper.engine.graphics.DrawRasterImage
import com.pdfpaper.engine.graphics.DrawTextRun
import com.pdfpaper.engine.graphics.FillPath
import com.pdfpaper.engine.graphics.FillStrokePath
import com.pdfpaper.engine.graphics.StrokePath
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.render.renderDisplayList
import java.util.LinkedHashSet
import kotlin.math.abs
import kotlin.math.atan2

/**
 * One text run with the geometry and formatting the document target needs.
 * Everything is in *device points*: the page's crop box mapped so that (0,0) is
 * the top-left corner of the displayed (rotated) page and y grows down, which
 * is the space Word's page coordinates live in.
 */
internal class RunBox(
    val text: String,
    val left: Double,
    val right: Double,
    val top: Double,
    val bottom: Double,
    /** Device y of the baseline. */
    val baseline: Double,
    /** Page-space font size in points. */
    val size: Double,
    val family: String,
    val bold: Boolean,
    val italic: Boolean,
    /** False when the PDF font name did not match a known family. */
    val recognized: Boolean,
    /** Baseline is not horizontal; the target formats cannot rotate text. */
    val rotated: Boolean
)

/** Runs on one baseline, left to right, with the text a reader would see. */
internal class PageLine(val segments: List<RunBox>, val text: String, val column: Int) {

    val left: Double = segments.minOf { it.left }
    val right: Double = segments.maxOf { it.right }
    val top: Double = segments.minOf { it.top }
    val bottom: Double = segments.maxOf { it.bottom }

    /** The largest font size on the line, used for geometry tolerances. */
    val maxSize: Double = segments.maxOf { it.size }

    /** The size of the characters that dominate the line (weighted by length). */
    val size: Double = run {
        var best = 0.0
        var bestWeight = -1
        for (segment in segments) {
            val weight = segment.text.count { !it.isWhitespace() }
            if (weight > bestWeight) {
                bestWeight = weight
                best = segment.size
            }
        }
        if (best > 0.0) best else maxSize
    }

    /** Family/bold/italic of the dominant segment. */
    val family: String = dominant().family
    val bold: Boolean = dominant().bold
    val italic: Boolean = dominant().italic

    val rotated: Boolean = segments.all { it.rotated }

    private fun dominant(): RunBox = segments.maxByOrNull { it.text.count { c -> !c.isWhitespace() } }!!

    /** True when a wide space between two runs marks a table cell boundary. */
    fun cellBoundaries(): List<Int> {
        val boundaries = ArrayList<Int>()
        for (i in 1 until segments.size) {
            val gap = segments[i].left - segments[i - 1].right
            val size = maxOf(segments[i].size, segments[i - 1].size)
            if (gap > maxOf(CELL_GAP_EM * size, MIN_CELL_GAP)) boundaries.add(i)
        }
        return boundaries
    }

    companion object {
        const val CELL_GAP_EM = 2.0
        const val MIN_CELL_GAP = 14.0
    }
}

/** A picture to place, in device points. */
internal class PageImage(
    val partName: String,
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
    val flipH: Boolean,
    val flipV: Boolean,
    /** Covers most of the page: a background, not a figure. */
    val backgroundLike: Boolean
)

internal class ColumnRange(val left: Double, val right: Double)

/** Everything the document builders need from one PDF page. */
internal class PageContent(
    val pageNumber: Int,
    val width: Double,
    val height: Double,
    /** Lines in reading order: column by column, each top to bottom. */
    val lines: List<PageLine>,
    val columns: List<ColumnRange>,
    val images: List<PageImage>,
    val imagesSkipped: List<String>,
    val invisibleRuns: Int,
    val rotatedRuns: Int,
    val vectorFills: Int,
    val vectorStrokes: Int,
    /** Part name of a rasterized copy of the page's vector art, when asked for. */
    val vectorBackgroundPart: String?,
    val unknownFonts: List<String>,
    val warnings: List<String>
) {
    /** The x a body paragraph in [column] starts at. */
    fun columnLeft(column: Int): Double = columns.getOrElse(column) { ColumnRange(0.0, width) }.left

    fun columnRight(column: Int): Double = columns.getOrElse(column) { ColumnRange(0.0, width) }.right

    val hasVectorArt: Boolean get() = vectorFills > 0 || vectorStrokes > 0
}

/**
 * Turns a PDF page into the geometry the DOCX writers work from: runs, lines,
 * columns and pictures.
 *
 * The column and line rules mirror the engine's `TextLayout` (a gutter wider
 * than 1.5 em with text on both sides at overlapping heights is a column
 * separator; runs within 0.4 em of a baseline are one line). They are
 * re-implemented here because the converter needs the column *ranges* — for
 * `w:cols` and for absolute placement — which the engine's reading-order API
 * does not expose.
 */
internal object PageAnalysis {

    private const val KEEP_BASELINE_EM = 0.4
    private const val MIN_LINE_TOLERANCE = 1.0
    private const val SPACE_GAP_EM = 0.2
    private const val GUTTER_EM = 1.5
    private const val MIN_GUTTER = 6.0
    private const val MAX_COLUMN_DEPTH = 8

    /**
     * A side of a provisional column split must cover at least this much of
     * the taller side's text height. Without the rule a table whose last
     * column sits far to the right looks like a page with two columns.
     */
    private const val COLUMN_SHARE = 0.25

    /** Largest font size we accept from a PDF run; bigger is a broken operator. */
    private const val MAX_FONT_SIZE = 400.0

    fun analyze(
        page: PdfPage,
        pageNumber: Int,
        images: ImageBag,
        rasterizeVectors: Boolean,
        dpi: Double,
        maxPixels: Int
    ): PageContent {
        val box = PageGeometry.boxOf(page)
        val matrix = PageGeometry.matrixFor(box, 1.0, 1.0)
        val list = page.buildDisplayList(decoder = null)
        val warnings = ArrayList<String>(list.warnings)
        val unknownFonts = LinkedHashSet<String>()

        val runs = ArrayList<RunBox>()
        val placedImages = ArrayList<PageImage>()
        val imagesSkipped = ArrayList<String>()
        var invisible = 0
        var rotated = 0
        var estimatedWidths = 0

        for (command in list.commands) {
            when (command) {
                is DrawTextRun -> {
                    val run = command.run
                    // Mode 7 only contributes to the clip; nothing is painted.
                    if (run.renderMode == 7) continue
                    if (run.text.isBlank()) continue
                    // Invisible text (an OCR layer over a scan) would be a
                    // duplicate of the page picture, so it is left out.
                    if (run.renderMode == 3) {
                        invisible++
                        continue
                    }
                    val size = run.fontSize * (command.matrix * run.startMatrix).meanScale()
                    if (!size.isFinite() || size <= 0.0 || size > MAX_FONT_SIZE) {
                        warnings += "text run with a nonsensical size ($size) skipped"
                        continue
                    }
                    val origin = matrix.apply(run.originX, run.originY)
                    val end = matrix.apply(run.endX, run.endY)
                    val degrees = Math.toDegrees(atan2(end[1] - origin[1], end[0] - origin[0]))
                    val isRotated = abs(normalize(degrees)) > 1.0
                    if (isRotated) rotated++

                    var left = minOf(
                        matrix.apply(run.x0, run.y0)[0], matrix.apply(run.x1, run.y0)[0],
                        matrix.apply(run.x1, run.y1)[0], matrix.apply(run.x0, run.y1)[0]
                    )
                    var right = maxOf(
                        matrix.apply(run.x0, run.y0)[0], matrix.apply(run.x1, run.y0)[0],
                        matrix.apply(run.x1, run.y1)[0], matrix.apply(run.x0, run.y1)[0]
                    )
                    var top = minOf(
                        matrix.apply(run.x0, run.y0)[1], matrix.apply(run.x1, run.y0)[1],
                        matrix.apply(run.x1, run.y1)[1], matrix.apply(run.x0, run.y1)[1]
                    )
                    var bottom = maxOf(
                        matrix.apply(run.x0, run.y0)[1], matrix.apply(run.x1, run.y0)[1],
                        matrix.apply(run.x1, run.y1)[1], matrix.apply(run.x0, run.y1)[1]
                    )
                    // Fonts without /Widths give a zero-width box; the baseline
                    // is still known, so the width is estimated like T17 does.
                    if (right - left < 0.05) {
                        right = left + size * 0.5 * maxOf(1, run.text.length)
                        estimatedWidths++
                    }
                    if (bottom - top < 0.05) {
                        top = origin[1] - size
                        bottom = origin[1]
                    }
                    val mapped = FontMapping.of(run.fontName)
                    if (!mapped.recognized) {
                        unknownFonts += run.fontName?.takeIf { it.isNotBlank() } ?: "(unnamed font)"
                    }
                    runs += RunBox(
                        text = run.text,
                        left = left, right = right, top = top, bottom = bottom,
                        baseline = origin[1],
                        size = size,
                        family = mapped.family, bold = mapped.bold, italic = mapped.italic,
                        recognized = mapped.recognized, rotated = isRotated
                    )
                }
                is DrawRasterImage -> {
                    val part = images.partFor(command.image)
                    if (part == null) {
                        imagesSkipped += ImageBag.describe(command.image)
                        continue
                    }
                    val placement = placementOf(matrix * command.matrix)
                    if (placement == null) {
                        imagesSkipped += "rotated or skewed image"
                        continue
                    }
                    val area = placement.width * placement.height
                    val pageArea = maxOf(1.0, box.rotatedWidth * box.rotatedHeight)
                    placedImages += PageImage(
                        partName = part,
                        left = placement.x, top = placement.y,
                        width = placement.width, height = placement.height,
                        flipH = placement.flipH, flipV = placement.flipV,
                        backgroundLike = area > 0.5 * pageArea
                    )
                }
                else -> {}
            }
        }

        if (estimatedWidths > 0) {
            warnings += "$estimatedWidths text run(s) had no width metrics; widths were estimated"
        }

        val columns = columnGroups(runs)
        val lines = ArrayList<PageLine>(runs.size)
        for ((index, group) in columns.withIndex()) {
            for (lineRuns in lineGroups(group)) lines += line(lineRuns, index)
        }

        val vectorFills = list.commands.count { it is FillPath || it is FillStrokePath }
        val vectorStrokes = list.commands.count { it is StrokePath || it is FillStrokePath }
        val background = if (rasterizeVectors && (vectorFills > 0 || vectorStrokes > 0)) {
            rasterizeVectors(list, pageNumber, images, box, dpi, maxPixels, warnings)
        } else {
            null
        }

        return PageContent(
            pageNumber = pageNumber,
            width = box.rotatedWidth,
            height = box.rotatedHeight,
            lines = lines,
            columns = columns.map { ColumnRange(it.minOf { run -> run.left }, it.maxOf { run -> run.right }) },
            images = placedImages,
            imagesSkipped = imagesSkipped,
            invisibleRuns = invisible,
            rotatedRuns = rotated,
            vectorFills = vectorFills,
            vectorStrokes = vectorStrokes,
            vectorBackgroundPart = background,
            unknownFonts = unknownFonts.toList(),
            warnings = warnings
        )
    }

    /** Renders everything but the pictures (they are placed as pictures). */
    private fun rasterizeVectors(
        list: DisplayList,
        pageNumber: Int,
        images: ImageBag,
        box: PageBox,
        dpi: Double,
        maxPixels: Int,
        warnings: MutableList<String>
    ): String? {
        val plan = PageGeometry.plan(box, dpi / 72.0, maxPixels)
        if (plan.downscaled) {
            warnings += "page rasterized at %.0f dpi to respect the pixel budget".format(plan.scale * 72.0)
        }
        val renderer = SoftwareRenderer(plan.pixelWidth, plan.pixelHeight, plan.matrix)
        renderDisplayList(DisplayList(list.commands.filter { it !is DrawRasterImage }, list.warnings), renderer)
        if (renderer.textRunsSkipped > 0) {
            warnings += "page picture omits ${renderer.textRunsSkipped} text run(s): no glyph rasterizer"
        }
        val name = "word/media/image${images.nextNumber()}.png"
        images.add(name, PngWriter.encodeRgb(renderer.raster))
        return name
    }

    // -------------------------------------------------------- reading order

    /**
     * Splits [runs] into columns at the widest qualifying vertical gutter,
     * recursively, left column first — the same rule the engine's reading order
     * uses (§ TextLayout.columnSplit).
     */
    private fun columnGroups(runs: List<RunBox>): List<List<RunBox>> {
        val out = ArrayList<List<RunBox>>()
        fun split(group: List<RunBox>, depth: Int) {
            if (group.size <= 1 || depth >= MAX_COLUMN_DEPTH) {
                out += group
                return
            }
            val halves = columnSplit(group)
            if (halves == null) {
                out += group
                return
            }
            split(halves.first, depth + 1)
            split(halves.second, depth + 1)
        }
        split(runs, 0)
        return out.filter { it.isNotEmpty() }
    }

    private fun columnSplit(runs: List<RunBox>): Pair<List<RunBox>, List<RunBox>>? {
        val gaps = xGaps(runs)
        if (gaps.isEmpty()) return null
        val median = medianFontSize(runs)
        val minGutter = maxOf(MIN_GUTTER, median * GUTTER_EM)
        val minHeight = median * 1.5
        for (gap in gaps.sortedByDescending { it.width }) {
            if (gap.width < minGutter) break
            val left = runs.filter { maxOf(it.left, it.right) <= gap.mid }
            val right = runs.filter { minOf(it.left, it.right) >= gap.mid }
            if (left.isEmpty() || right.isEmpty()) continue
            val leftHeight = mergedY(left).sumOf { it.hi - it.lo }
            val rightHeight = mergedY(right).sumOf { it.hi - it.lo }
            val needed = maxOf(minHeight, COLUMN_SHARE * maxOf(leftHeight, rightHeight))
            if (minOf(leftHeight, rightHeight) < needed) continue
            return left to right
        }
        return null
    }

    private class Gap(val mid: Double, val width: Double)

    private fun xGaps(runs: List<RunBox>): List<Gap> {
        val intervals = runs.map { minOf(it.left, it.right) to maxOf(it.left, it.right) }.sortedBy { it.first }
        val gaps = ArrayList<Gap>()
        var high = intervals[0].second
        for (index in 1 until intervals.size) {
            val (lo, hi) = intervals[index]
            if (lo > high) {
                gaps += Gap((high + lo) / 2.0, lo - high)
                high = hi
            } else {
                high = maxOf(high, hi)
            }
        }
        return gaps
    }

    private class Interval(val lo: Double, val hi: Double)

    private fun mergedY(runs: List<RunBox>): List<Interval> {
        if (runs.isEmpty()) return emptyList()
        val intervals = runs.map { Interval(it.top, it.bottom) }.sortedBy { it.lo }
        val merged = ArrayList<Interval>()
        for (interval in intervals) {
            val last = merged.lastOrNull()
            if (last != null && interval.lo <= last.hi) {
                merged[merged.size - 1] = Interval(last.lo, maxOf(last.hi, interval.hi))
            } else {
                merged += interval
            }
        }
        return merged
    }

    private fun medianFontSize(runs: List<RunBox>): Double {
        val sizes = runs.map { it.size }.filter { it.isFinite() && it > 0.0 }.sorted()
        return if (sizes.isEmpty()) 1.0 else sizes[sizes.size / 2]
    }

    /** Runs grouped into lines, top to bottom, each left to right. */
    private fun lineGroups(runs: List<RunBox>): List<List<RunBox>> {
        val sorted = runs.sortedWith(compareBy({ it.baseline }, { it.left }))
        val groups = ArrayList<List<RunBox>>()
        var index = 0
        while (index < sorted.size) {
            val group = arrayListOf(sorted[index])
            var next = index + 1
            while (next < sorted.size && sameLine(group, sorted[next])) {
                group += sorted[next]
                next++
            }
            group.sortBy { it.left }
            groups += group
            index = next
        }
        return groups
    }

    private fun sameLine(line: List<RunBox>, run: RunBox): Boolean {
        val baseline = line[0].baseline
        val size = line.maxOf { it.size }.coerceAtLeast(run.size)
        return abs(baseline - run.baseline) <= maxOf(MIN_LINE_TOLERANCE, KEEP_BASELINE_EM * size)
    }

    /** Line text with the spaces a reader would see between the runs. */
    private fun line(runs: List<RunBox>, column: Int): PageLine {
        val text = StringBuilder()
        for (i in runs.indices) {
            if (i > 0 && needsSpace(runs[i - 1], runs[i])) text.append(' ')
            text.append(runs[i].text)
        }
        return PageLine(runs, text.toString(), column)
    }

    private fun needsSpace(previous: RunBox, current: RunBox): Boolean {
        if (previous.text.isEmpty() || current.text.isEmpty()) return false
        if (previous.text.last().isWhitespace() || current.text.first().isWhitespace()) return false
        val gap = minOf(current.left, current.right) - maxOf(previous.left, previous.right)
        val size = maxOf(maxOf(previous.size, current.size), 1.0)
        return gap > SPACE_GAP_EM * size
    }

    private fun normalize(degrees: Double): Double {
        var value = degrees % 360.0
        if (value > 180.0) value -= 360.0
        if (value < -180.0) value += 360.0
        return value
    }
}
