package com.pdfpaper.app.ui.search

import com.pdfpaper.engine.annot.HighlightSpec

/**
 * T65 Task 8: the pure mapping from regex rows to highlight annotations.
 * Rows without boxes (library scope, or a page whose layout changed) are
 * skipped and counted, never written as zero-size highlights.
 */
object RegexActions {

    /**
     * Converts regex rows to highlight specs. Each row that has at least one
     * rect becomes one [HighlightSpec]; rows without rects are skipped and
     * counted by [skippedWithoutBoxes].
     *
     * @param rows the match rows in reading order
     * @param author the highlight author (T on the annotation); blank → "PDFPaper regex"
     * @param maxHighlights cap the number of specs emitted (head of the list wins)
     */
    fun highlightsFor(
        rows: List<RegexRow>,
        author: String,
        maxHighlights: Int = 2000
    ): List<HighlightSpec> {
        val specs = ArrayList<HighlightSpec>(maxHighlights.coerceAtLeast(0))
        for (row in rows) {
            if (specs.size >= maxHighlights) break
            if (row.rects.isEmpty()) continue
            specs += HighlightSpec(
                pageIndex = row.pageIndex,
                rects = row.rects.map { tr ->
                    com.pdfpaper.engine.geom.PdfRect(tr.x0, tr.y0, tr.x1, tr.y1)
                },
                contents = row.text,
                title = author.ifBlank { "PDFPaper regex" }
            )
        }
        return specs
    }

    /** Counts rows that have no rects (and were therefore skipped by highlightsFor). */
    fun skippedWithoutBoxes(rows: List<RegexRow>): Int =
        rows.count { it.rects.isEmpty() }
}