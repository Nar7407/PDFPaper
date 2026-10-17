package com.pdfpaper.engine.text

import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages

/** One search match on one page of a document (T11). */
data class PageMatch(val pageIndex: Int, val match: TextMatch) {
    val start: Int get() = match.start
    val end: Int get() = match.end
    val rects: List<TextRect> get() = match.rects
}

/**
 * Case-insensitive search across [doc] (T11). Pages are visited in order and
 * each page's text is extracted and laid out, so this reads every page once.
 * [maxMatches] bounds the work for a very common query.
 */
fun PdfDocument.searchText(query: String, maxMatches: Int = PageLayout.DEFAULT_MAX_MATCHES): List<PageMatch> {
    if (query.isEmpty() || maxMatches <= 0) return emptyList()
    val out = ArrayList<PageMatch>()
    for ((index, page) in pages().withIndex()) {
        if (out.size >= maxMatches) break
        for (match in pageLayout(page.extractText()).search(query, maxMatches - out.size)) {
            out += PageMatch(index, match)
        }
    }
    return out
}
