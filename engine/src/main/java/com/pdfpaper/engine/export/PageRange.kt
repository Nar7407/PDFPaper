package com.pdfpaper.engine.export

/**
 * Which pages an export covers. Page numbers are zero-based inside the engine;
 * [parse] reads the one-based text a user types ("1-3,7", "all").
 */
sealed interface PageRange {

    object All : PageRange

    data class Custom(val pages: Set<Int>) : PageRange

    /** True when the zero-based [index] is selected. */
    fun includes(index: Int): Boolean = when (this) {
        All -> true
        is Custom -> index in pages
    }

    companion object {

        /**
         * Parses "all", "1", "1-3,7" into a [PageRange]. Tokens are one-based;
         * reversed ranges ("5-2") and non-numbers are rejected rather than
         * silently ignored, so a typo cannot export the wrong pages.
         */
        fun parse(text: String): Result<PageRange> {
            val value = text.trim()
            if (value.isEmpty() || value.equals("all", ignoreCase = true)) {
                return Result.success(All)
            }
            val pages = HashSet<Int>()
            for (raw in value.split(',')) {
                val piece = raw.trim()
                if (piece.isEmpty()) {
                    return Result.failure(IllegalArgumentException("Empty page in \"$text\""))
                }
                val dash = piece.indexOf('-')
                if (dash < 0) {
                    val page = piece.toIntOrNull()
                        ?: return Result.failure(IllegalArgumentException("Not a page number: \"$piece\""))
                    if (page < 1) return Result.failure(IllegalArgumentException("Page numbers start at 1: \"$piece\""))
                    pages += page - 1
                } else {
                    val from = piece.substring(0, dash).trim().toIntOrNull()
                        ?: return Result.failure(IllegalArgumentException("Not a page number: \"$piece\""))
                    val to = piece.substring(dash + 1).trim().toIntOrNull()
                        ?: return Result.failure(IllegalArgumentException("Not a page number: \"$piece\""))
                    if (from < 1 || to < 1) {
                        return Result.failure(IllegalArgumentException("Page numbers start at 1: \"$piece\""))
                    }
                    if (to < from) {
                        return Result.failure(IllegalArgumentException("Page range runs backwards: \"$piece\""))
                    }
                    for (page in from..to) pages += page - 1
                }
            }
            if (pages.isEmpty()) return Result.failure(IllegalArgumentException("No pages in \"$text\""))
            return Result.success(Custom(pages))
        }
    }
}

/** "1-3,5" for the selected zero-based indices, in ascending order (CLI echo). */
fun PageRange.describe(pageCount: Int): String = when (this) {
    PageRange.All -> "1-$pageCount"
    is PageRange.Custom -> {
        val sorted = pages.filter { it in 0 until pageCount }.sorted()
        if (sorted.isEmpty()) "none" else compress(sorted)
    }
}

private fun compress(sorted: List<Int>): String {
    val parts = ArrayList<String>()
    var start = sorted[0]
    var previous = sorted[0]
    for (i in 1..sorted.size) {
        val current = if (i < sorted.size) sorted[i] else Int.MIN_VALUE
        if (current == previous + 1) {
            previous = current
            continue
        }
        parts += if (start == previous) "${start + 1}" else "${start + 1}-${previous + 1}"
        if (i < sorted.size) {
            start = current
            previous = current
        }
    }
    return parts.joinToString(",")
}
