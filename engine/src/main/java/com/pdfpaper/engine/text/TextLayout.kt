package com.pdfpaper.engine.text

import com.pdfpaper.engine.page.PdfPage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned rectangle in page space (PDF user units, y up). */
data class TextRect(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val width: Double get() = x1 - x0
    val height: Double get() = y1 - y0
    val isEmpty: Boolean get() = x1 <= x0 || y1 <= y0

    /** Smallest rectangle containing both. */
    fun union(other: TextRect): TextRect = TextRect(
        min(x0, other.x0),
        min(y0, other.y0),
        max(x1, other.x1),
        max(y1, other.y1)
    )
}

/** The run's page-space box with the corners ordered. */
internal fun PdfTextRun.box(): TextRect =
    TextRect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))

/** A character range of a [PageLayout] that a search found (T11). */
data class TextMatch(
    /** Inclusive start offset in [PageLayout.text]. */
    val start: Int,
    /** Exclusive end offset in [PageLayout.text]. */
    val end: Int,
    /** Highlight rectangles in page space, one per run fragment. */
    val rects: List<TextRect>
)

/**
 * One line of text in reading order (T11). [text] concatenates the runs; a
 * single space is inserted where the horizontal gap between two runs is wider
 * than a typical word space, so the line reads naturally.
 */
class TextLine internal constructor(
    val runs: List<PdfTextRun>,
    val text: String,
    /** Offset of `runs[i].text` inside [text] (same order as [runs]). */
    internal val runStarts: IntArray,
    /** Offset of [text] inside [PageLayout.text]. */
    internal val start: Int
) {
    /** Page-space box enclosing every run. */
    val bounds: TextRect = runs.drop(1).fold(runs.first().box()) { acc, run ->
        acc.union(run.box())
    }
}

/**
 * A page's text in reading order (T11) with the searches and selections built
 * on it. Reading order is per column: columns are found from vertical
 * whitespace (a gutter wider than a word space, with text on both sides at the
 * same height), each column is then read top to bottom and left to right.
 *
 * A full-width block (a title or an abstract) that spans the gutter is read
 * before the columns when it sits above them and after them when it sits
 * below. A spanning block beside the columns still keeps the page single-
 * column: there is no honest place to put it in the reading order.
 */
class PageLayout internal constructor(
    val lines: List<TextLine>,
    /** Lines joined with '\n'. */
    val text: String
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * Highlight rectangles covering the character range [start, end) of
     * [text]. Characters are placed by linear interpolation along the run's
     * baseline, matching how the renderer stretches glyphs to the advance (T9).
     */
    fun rectsFor(start: Int, end: Int): List<TextRect> {
        if (start >= end || lines.isEmpty()) return emptyList()
        val out = ArrayList<TextRect>()
        for (line in lines) {
            val from = max(start, line.start) - line.start
            val to = min(end, line.start + line.text.length) - line.start
            if (from >= to) continue
            lineRects(line, from, to, out)
        }
        return out
    }

    /**
     * Text whose glyphs fall inside [boxes] (T56). Characters are placed with
     * the same interpolation as [rectsFor]; a character counts when at least
     * half of its box overlaps one of [boxes]. Hits are returned per line in
     * reading order, joined with '\n', and the run between the first and last
     * hit character is kept whole (so the words a highlight covers read
     * naturally).
     */
    fun textInRects(boxes: List<TextRect>): String {
        val queries = boxes.filter { !it.isEmpty }
        if (queries.isEmpty() || lines.isEmpty()) return ""
        val out = StringBuilder()
        for (line in lines) {
            var first = -1
            var last = -1
            for (offset in 0 until line.text.length) {
                val rect = charRect(line, offset) ?: continue
                if (queries.none { overlaps(rect, it) }) continue
                if (first < 0) first = offset
                last = offset
            }
            if (first < 0) continue
            val text = line.text.substring(first, last + 1).trim()
            if (text.isEmpty()) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(text)
        }
        return out.toString()
    }

    /**
     * Character range (inclusive bounds) of the word at [offset]; when [offset]
     * falls on a separator the nearest word is returned. Empty for empty text.
     */
    fun wordRangeAt(offset: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        var index = offset.coerceIn(0, text.length - 1)
        if (!isWordChar(text[index])) {
            var right = index
            while (right < text.length && !isWordChar(text[right])) right++
            if (right < text.length) {
                index = right
            } else {
                while (index > 0 && !isWordChar(text[index])) index--
            }
        }
        if (!isWordChar(text[index])) return IntRange.EMPTY
        var start = index
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = index
        while (end + 1 < text.length && isWordChar(text[end + 1])) end++
        return start..end
    }

    /** Word nearest the page-space point, or an empty range on an empty page. */
    fun wordAt(x: Double, y: Double): IntRange {
        if (lines.isEmpty()) return IntRange.EMPTY
        var best: TextLine? = null
        var bestDistance = Double.MAX_VALUE
        for (line in lines) {
            val box = line.bounds
            val dx = when {
                x > box.x1 -> x - box.x1
                x < box.x0 -> box.x0 - x
                else -> 0.0
            }
            val dy = when {
                y > box.y1 -> y - box.y1
                y < box.y0 -> box.y0 - y
                else -> 0.0
            }
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = line
            }
        }
        val line = best ?: return IntRange.EMPTY
        val box = line.bounds
        if (x <= box.x0) return wordRangeAt(line.start)
        if (x >= box.x1) return wordRangeAt(line.start + line.text.length - 1)

        var chosen = 0
        var chosenDistance = Double.MAX_VALUE
        for (index in line.runs.indices) {
            val runBox = line.runs[index].box()
            val distance = when {
                x > runBox.x1 -> x - runBox.x1
                x < runBox.x0 -> runBox.x0 - x
                else -> 0.0
            }
            if (distance < chosenDistance) {
                chosenDistance = distance
                chosen = index
            }
        }
        val run = line.runs[chosen]
        val length = run.text.length
        if (length == 0) return wordRangeAt(line.start + line.runStarts[chosen])
        val span = run.endX - run.originX
        val fraction = if (abs(span) < 1e-9) 0.0 else ((x - run.originX) / span).coerceIn(0.0, 1.0)
        val local = (fraction * length).toInt().coerceIn(0, length - 1)
        return wordRangeAt(line.start + line.runStarts[chosen] + local)
    }

    /**
     * All case-insensitive occurrences of [query] on this page, in reading
     * order. Matches never span a line break. [maxMatches] caps pathological
     * queries so a search cannot run away.
     */
    fun search(query: String, maxMatches: Int = DEFAULT_MAX_MATCHES): List<TextMatch> {
        if (query.isEmpty() || lines.isEmpty()) return emptyList()
        val out = ArrayList<TextMatch>()
        for (line in lines) {
            var from = 0
            while (out.size < maxMatches) {
                val at = line.text.indexOf(query, from, ignoreCase = true)
                if (at < 0) break
                val start = line.start + at
                val end = start + query.length
                out += TextMatch(start, end, rectsFor(start, end))
                from = at + query.length
            }
            if (out.size >= maxMatches) break
        }
        return out
    }

    private fun lineRects(line: TextLine, from: Int, to: Int, out: MutableList<TextRect>) {
        for (index in line.runs.indices) {
            val run = line.runs[index]
            val length = run.text.length
            if (length == 0) continue
            val startInRun = line.runStarts[index]
            val a = max(from, startInRun)
            val b = min(to, startInRun + length)
            if (a >= b) continue
            val fa = (a - startInRun).toDouble() / length
            val fb = (b - startInRun).toDouble() / length
            val xa = run.originX + fa * (run.endX - run.originX)
            val xb = run.originX + fb * (run.endX - run.originX)
            val box = run.box()
            out += TextRect(min(xa, xb), box.y0, max(xa, xb), box.y1)
        }
    }

    companion object {
        /** Matches kept per page by [search] unless the caller says otherwise. */
        const val DEFAULT_MAX_MATCHES: Int = 1000

        /** Empty layout, for a page that cannot be read. */
        val EMPTY: PageLayout = PageLayout(emptyList(), "")

        private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
    }
}

/** Lays [pageText] out in reading order, ready for search and selection. */
fun pageLayout(pageText: PageText): PageLayout = layoutOf(readingOrder(pageText.runs))

/**
 * Body size of the page (T33): the median line size, weighted by how much text
 * each line carries, so a large title does not decide it. 0 for an empty page.
 */
fun PageLayout.bodyFontSize(): Double {
    val samples = ArrayList<Pair<Double, Int>>(lines.size)
    for (line in lines) {
        val length = line.text.trim().length
        if (length == 0) continue
        val size = line.runs.maxOf { it.fontSize }
        if (size.isFinite() && size > 0.0) samples += size to length
    }
    if (samples.isEmpty()) return 0.0
    samples.sortBy { it.first }
    val total = samples.sumOf { it.second }
    var seen = 0
    for ((size, weight) in samples) {
        seen += weight
        if (seen * 2 >= total) return size
    }
    return samples.last().first
}

/** Extracts [page]'s text and lays it out in reading order. */
fun PdfPage.layout(): PageLayout = pageLayout(extractText())

// --------------------------------------------------------------- reading order

/**
 * Orders runs for reading. Columns separated by a vertical gutter are read
 * left to right, each column top to bottom, runs on one line left to right
 * ([sortLines]).
 */
private fun readingOrder(runs: List<PdfTextRun>): List<PdfTextRun> = arrange(runs, 0)

private fun arrange(runs: List<PdfTextRun>, depth: Int): List<PdfTextRun> {
    if (runs.size <= 1 || depth >= MAX_DEPTH) return sortLines(runs)
    val wide = wideColumnSplit(runs)
    if (wide != null) {
        return sortLines(wide.header) +
            arrange(wide.left, depth + 1) +
            arrange(wide.right, depth + 1) +
            sortLines(wide.tail)
    }
    val split = columnSplit(runs) ?: return sortLines(runs)
    val (left, right) = split
    return arrange(left, depth + 1) + arrange(right, depth + 1)
}

/** A page whose columns are topped or footed by a block that spans the gutter. */
private class WideSplit(
    val header: List<PdfTextRun>,
    val left: List<PdfTextRun>,
    val right: List<PdfTextRun>,
    val tail: List<PdfTextRun>
)

/**
 * Finds a column gutter that a full-width title, abstract or footer also
 * crosses. The gutter is looked for among the runs narrow enough to sit in one
 * column; a spanning run belongs before the columns when it is wholly above
 * them and after them when it is wholly below. Anything else (a spanning block
 * level with the columns) is left to the single-column fallback: guessing where
 * it belongs would read the page out of order.
 */
private fun wideColumnSplit(runs: List<PdfTextRun>): WideSplit? {
    if (runs.size <= 3) return null
    val totalWidth = runs.maxOf { max(it.x0, it.x1) } - runs.minOf { min(it.x0, it.x1) }
    if (totalWidth <= 0.0) return null
    val narrow = runs.filter { abs(max(it.x0, it.x1) - min(it.x0, it.x1)) < totalWidth * 0.6 }
    if (narrow.size < 2) return null
    val gap = widestGutter(narrow) ?: dominantGutter(runs) ?: return null
    val spanning = runs.filter { min(it.x0, it.x1) < gap.mid && max(it.x0, it.x1) > gap.mid }
    if (spanning.isEmpty()) return null
    val left = runs.filter { max(it.x0, it.x1) <= gap.mid && it !in spanning }
    val right = runs.filter { min(it.x0, it.x1) >= gap.mid && it !in spanning }
    if (left.isEmpty() || right.isEmpty()) return null
    val top = max(left.maxOf { max(it.y0, it.y1) }, right.maxOf { max(it.y0, it.y1) })
    val bottom = min(left.minOf { min(it.y0, it.y1) }, right.minOf { min(it.y0, it.y1) })
    val header = spanning.filter { min(it.y0, it.y1) >= top }
    val tail = spanning.filter { max(it.y0, it.y1) <= bottom }
    if (header.size + tail.size != spanning.size) return null
    return WideSplit(header, left, right, tail)
}

/**
 * Splits [runs] at the widest vertical gutter, or null when no gap looks like
 * a column separator. A gap qualifies when it is wider than 1.5 em and there
 * is text on both sides at overlapping heights (so a wide word space on a
 * single line is not mistaken for a column).
 */
private fun columnSplit(runs: List<PdfTextRun>): Pair<List<PdfTextRun>, List<PdfTextRun>>? {
    val gap = widestGutter(runs) ?: return null
    val left = runs.filter { max(it.x0, it.x1) <= gap.mid }
    val right = runs.filter { min(it.x0, it.x1) >= gap.mid }
    if (left.isEmpty() || right.isEmpty()) return null
    return left to right
}

/**
 * The gutter between the two most common line starts. Used when a spanning
 * run bridges the whitespace for [widestGutter]: the columns still start at
 * two stable x positions, and the gap between the left column's right edge and
 * the right column's left edge is the gutter.
 */
private fun dominantGutter(runs: List<PdfTextRun>): Gap? {
    if (runs.size < 4) return null
    val totalWidth = runs.maxOf { max(it.x0, it.x1) } - runs.minOf { min(it.x0, it.x1) }
    if (totalWidth <= 0.0) return null
    val starts = runs.groupBy { (min(it.x0, it.x1) / 4.0).toInt() }
        .mapValues { it.value.size }
        .entries
        .filter { it.value >= 2 }
        .sortedByDescending { it.value }
    if (starts.size < 2) return null
    val leftStart = starts[0].key * 4.0
    val rightEntry = starts.firstOrNull {
        it.key * 4.0 - leftStart > totalWidth * 0.25
    } ?: return null
    val rightStart = rightEntry.key * 4.0
    val columnRuns = runs.filter {
        abs(max(it.x0, it.x1) - min(it.x0, it.x1)) < totalWidth * 0.6
    }
    val leftRuns = columnRuns.filter { abs(min(it.x0, it.x1) - leftStart) <= 8.0 }
    val rightRuns = columnRuns.filter { abs(min(it.x0, it.x1) - rightStart) <= 8.0 }
    if (leftRuns.isEmpty() || rightRuns.isEmpty()) return null
    val leftEdge = leftRuns.maxOf { max(it.x0, it.x1) }
    val rightEdge = rightRuns.minOf { min(it.x0, it.x1) }
    val median = medianFontSize(runs)
    val minGutter = max(MIN_GUTTER, median * GUTTER_EM)
    if (rightEdge - leftEdge < minGutter) return null
    val mid = (leftEdge + rightEdge) / 2.0
    if (sideBySideHeight(runs, mid) < median * 1.5) return null
    return Gap(mid, rightEdge - leftEdge)
}

/** The widest gutter with text on both sides, or null when there is none. */
private fun widestGutter(runs: List<PdfTextRun>): Gap? {
    val gaps = xGaps(runs)
    if (gaps.isEmpty()) return null
    val median = medianFontSize(runs)
    val minGutter = max(MIN_GUTTER, median * GUTTER_EM)
    val minHeight = median * 1.5
    for (gap in gaps.sortedByDescending { it.width }) {
        if (gap.width < minGutter) break
        if (sideBySideHeight(runs, gap.mid) < minHeight) continue
        return gap
    }
    return null
}

private class Gap(val mid: Double, val width: Double)

/** Whitespace bands in x: the gaps between the merged run intervals. */
private fun xGaps(runs: List<PdfTextRun>): List<Gap> {
    val intervals = runs.map { min(it.x0, it.x1) to max(it.x0, it.x1) }.sortedBy { it.first }
    val gaps = ArrayList<Gap>()
    var high = intervals[0].second
    for (index in 1 until intervals.size) {
        val (lo, hi) = intervals[index]
        if (lo > high) {
            gaps += Gap((high + lo) / 2.0, lo - high)
            high = hi
        } else {
            high = max(high, hi)
        }
    }
    return gaps
}

/** Total height over which the runs left and right of [x] overlap vertically. */
private fun sideBySideHeight(runs: List<PdfTextRun>, x: Double): Double {
    val left = mergedY(runs.filter { max(it.x0, it.x1) <= x })
    val right = mergedY(runs.filter { min(it.x0, it.x1) >= x })
    return overlapLength(left, right)
}

private class Interval(val lo: Double, val hi: Double)

private fun mergedY(runs: List<PdfTextRun>): List<Interval> {
    if (runs.isEmpty()) return emptyList()
    val intervals = runs.map { it.box() }
        .map { Interval(min(it.y0, it.y1), max(it.y0, it.y1)) }
        .sortedBy { it.lo }
    val merged = ArrayList<Interval>()
    for (interval in intervals) {
        val last = merged.lastOrNull()
        if (last != null && interval.lo <= last.hi) {
            merged[merged.size - 1] = Interval(last.lo, max(last.hi, interval.hi))
        } else {
            merged += interval
        }
    }
    return merged
}

private fun overlapLength(a: List<Interval>, b: List<Interval>): Double {
    var total = 0.0
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        val lo = max(a[i].lo, b[j].lo)
        val hi = min(a[i].hi, b[j].hi)
        if (hi > lo) total += hi - lo
        if (a[i].hi < b[j].hi) i++ else j++
    }
    return total
}

private fun medianFontSize(runs: List<PdfTextRun>): Double {
    val sizes = runs.map { it.fontSize }.filter { it.isFinite() && it > 0.0 }.sorted()
    return if (sizes.isEmpty()) 1.0 else sizes[sizes.size / 2]
}

/** Runs grouped into lines top to bottom, each line left to right. */
private fun sortLines(runs: List<PdfTextRun>): List<PdfTextRun> {
    val sorted = runs.sortedWith(compareByDescending<PdfTextRun> { it.originY }.thenBy { it.originX })
    val result = ArrayList<PdfTextRun>(sorted.size)
    var index = 0
    while (index < sorted.size) {
        val group = arrayListOf(sorted[index])
        var next = index + 1
        while (next < sorted.size && sameLine(group, sorted[next])) {
            group += sorted[next]
            next++
        }
        group.sortBy { it.originX }
        result += group
        index = next
    }
    return result
}

private fun sameLine(line: List<PdfTextRun>, run: PdfTextRun): Boolean {
    val baseline = line[0].originY
    val size = line.maxOf { it.fontSize }.coerceAtLeast(run.fontSize)
    return abs(baseline - run.originY) <= max(MIN_LINE_TOLERANCE, LINE_BASELINE_EM * size)
}

// ------------------------------------------------------------------- assembly

private fun layoutOf(ordered: List<PdfTextRun>): PageLayout {
    if (ordered.isEmpty()) return PageLayout.EMPTY
    val lines = ArrayList<TextLine>()
    val flat = StringBuilder()
    var index = 0
    while (index < ordered.size) {
        val group = arrayListOf(ordered[index])
        var next = index + 1
        while (next < ordered.size && sameLine(group, ordered[next])) {
            group += ordered[next]
            next++
        }
        group.sortBy { it.originX }

        val lineText = StringBuilder()
        val starts = IntArray(group.size)
        for (k in group.indices) {
            if (k > 0 && needsSpace(group[k - 1], group[k])) lineText.append(' ')
            starts[k] = lineText.length
            lineText.append(group[k].text)
        }
        val text = lineText.toString()
        if (lines.isNotEmpty()) flat.append('\n')
        val start = flat.length
        flat.append(text)
        lines += TextLine(group, text, starts, start)
        index = next
    }
    return PageLayout(lines, flat.toString())
}

/**
 * Box of the character at [offset] in [line], placed by linear interpolation
 * along the run's baseline (the same rule [PageLayout.rectsFor] uses). Offsets
 * of an inserted between-run space have no glyph and return null.
 */
private fun charRect(line: TextLine, offset: Int): TextRect? {
    for (index in line.runs.indices) {
        val run = line.runs[index]
        val length = run.text.length
        if (length == 0) continue
        val startInRun = line.runStarts[index]
        if (offset < startInRun || offset >= startInRun + length) continue
        val fa = (offset - startInRun).toDouble() / length
        val fb = (offset - startInRun + 1).toDouble() / length
        val xa = run.originX + fa * (run.endX - run.originX)
        val xb = run.originX + fb * (run.endX - run.originX)
        val box = run.box()
        return TextRect(min(xa, xb), box.y0, max(xa, xb), box.y1)
    }
    return null
}

/** True when at least half of [char] lies inside [box] on both axes. */
private fun overlaps(char: TextRect, box: TextRect): Boolean {
    val dx = min(char.x1, box.x1) - max(char.x0, box.x0)
    val dy = min(char.y1, box.y1) - max(char.y0, box.y0)
    if (dx <= 0.0 || dy <= 0.0) return false
    return dx >= char.width * 0.5 && dy >= char.height * 0.5
}

/** A space belongs between two same-line runs when the gap is bigger than one. */
private fun needsSpace(previous: PdfTextRun, current: PdfTextRun): Boolean {
    if (previous.text.isEmpty() || current.text.isEmpty()) return false
    if (previous.text.last().isWhitespace() || current.text.first().isWhitespace()) return false
    val gap = min(current.x0, current.x1) - max(previous.x0, previous.x1)
    val size = max(max(previous.fontSize, current.fontSize), 1.0)
    return gap > SPACE_GAP_EM * size
}

private const val GUTTER_EM = 1.5
private const val MIN_GUTTER = 6.0
private const val SPACE_GAP_EM = 0.2
private const val LINE_BASELINE_EM = 0.4
private const val MIN_LINE_TOLERANCE = 1.0
private const val MAX_DEPTH = 32
