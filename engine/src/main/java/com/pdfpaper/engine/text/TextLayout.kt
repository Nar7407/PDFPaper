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
 * Limitation: a full-width block (a heading) that spans a gutter keeps the
 * page single-column, so the columns below are read line by line instead.
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
    val split = columnSplit(runs) ?: return sortLines(runs)
    val (left, right) = split
    return arrange(left, depth + 1) + arrange(right, depth + 1)
}

/**
 * Splits [runs] at the widest vertical gutter, or null when no gap looks like
 * a column separator. A gap qualifies when it is wider than 1.5 em and there
 * is text on both sides at overlapping heights (so a wide word space on a
 * single line is not mistaken for a column).
 */
private fun columnSplit(runs: List<PdfTextRun>): Pair<List<PdfTextRun>, List<PdfTextRun>>? {
    val gaps = xGaps(runs)
    if (gaps.isEmpty()) return null
    val median = medianFontSize(runs)
    val minGutter = max(MIN_GUTTER, median * GUTTER_EM)
    val minHeight = median * 1.5
    for (gap in gaps.sortedByDescending { it.width }) {
        if (gap.width < minGutter) break
        if (sideBySideHeight(runs, gap.mid) < minHeight) continue
        val left = runs.filter { max(it.x0, it.x1) <= gap.mid }
        val right = runs.filter { min(it.x0, it.x1) >= gap.mid }
        if (left.isEmpty() || right.isEmpty()) continue
        return left to right
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
