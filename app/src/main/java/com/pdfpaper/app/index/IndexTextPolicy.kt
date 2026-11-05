package com.pdfpaper.app.index

/**
 * Keeps one page's indexed text bounded and readable. A malformed PDF can
 * claim an enormous page, and FTS4 stores a copy of every token, so the cap is
 * part of the memory contract rather than a cosmetic trim.
 */
object IndexTextPolicy {

    const val MAX_PAGE_CHARS = 200_000

    fun normalize(text: String): String {
        if (text.isEmpty()) return ""
        val builder = StringBuilder(minOf(text.length, MAX_PAGE_CHARS))
        var pendingSpace = false
        for (ch in text) {
            if (ch.isWhitespace() || ch.code < 0x20) {
                pendingSpace = builder.isNotEmpty()
                continue
            }
            if (pendingSpace && builder.length < MAX_PAGE_CHARS) {
                builder.append(' ')
                pendingSpace = false
            }
            if (builder.length >= MAX_PAGE_CHARS) break
            builder.append(ch)
        }
        return builder.toString().trim()
    }

    fun hasText(text: String): Boolean = text.any { it.isLetterOrDigit() }
}
