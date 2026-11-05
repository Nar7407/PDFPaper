package com.pdfpaper.app.index

/**
 * Turns what the user typed into an FTS4 MATCH expression (T50).
 *
 * - words are prefix queries, so "budg" finds "budget";
 * - a quoted phrase is an exact phrase, so "annual report" does not match the
 *   two words on different pages;
 * - everything is quoted, which keeps FTS operators and punctuation from
 *   turning a search into a syntax error.
 *
 * The same parsed terms feed the "file name only" filter, which is a plain
 * case-insensitive substring match rather than an FTS query.
 */
data class ParsedSearch(
    /** Null when there is nothing searchable in the input. */
    val matchExpression: String?,
    val plainTerms: List<String>
)

object SearchQueryBuilder {

    private const val MAX_INPUT = 400
    private const val MAX_TERMS = 12
    private const val MAX_TERM_LENGTH = 80

    fun parse(input: String): ParsedSearch {
        val text = input.trim().take(MAX_INPUT)
        if (text.isEmpty()) return ParsedSearch(null, emptyList())

        val phrases = ArrayList<String>()
        val words = ArrayList<String>()
        val plain = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false

        fun flush() {
            if (current.isBlank()) {
                current.setLength(0)
                return
            }
            val value = clean(current.toString())
            current.setLength(0)
            if (value.isEmpty()) return
            if (quoted) {
                phrases += value
                plain += value
            } else {
                // Punctuation is a separator: "(tax:2026)" is two words, not
                // one phrase with a space inside.
                val split = value.split(' ').filter { it.isNotEmpty() }
                words += split
                plain += split
            }
        }

        for (ch in text) {
            when {
                ch == '"' -> {
                    flush()
                    quoted = !quoted
                }
                quoted -> current.append(ch)
                ch.isWhitespace() -> flush()
                else -> current.append(ch)
            }
        }
        flush()

        val expressions = ArrayList<String>()
        // Phrases first is only cosmetic; FTS4 treats the AND terms equally.
        for (phrase in phrases.take(MAX_TERMS)) {
            expressions += "\"${phrase.replace("\"", "")}\""
        }
        val remaining = (MAX_TERMS - expressions.size).coerceAtLeast(0)
        for (word in words.take(remaining)) {
            // FTS4 only treats an *unquoted* term followed by '*' as a prefix
            // query; `"budg"*` is an exact phrase and would miss "budget".
            // The token is reduced to letters/digits and lowercased, so the
            // FTS operators (which are uppercase words) cannot appear.
            val token = word.lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
            if (token.isNotEmpty()) expressions += "$token*"
        }
        if (expressions.isEmpty()) return ParsedSearch(null, emptyList())
        return ParsedSearch(
            matchExpression = expressions.joinToString(" AND "),
            plainTerms = plain.distinct().take(MAX_TERMS)
        )
    }

    private fun clean(value: String): String {
        val builder = StringBuilder(value.length)
        for (ch in value) {
            // Letters and digits in every script, plus the punctuation that is
            // common inside file names. Everything else is a separator.
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '@') {
                builder.append(ch)
            } else if (builder.isNotEmpty() && builder.last() != ' ') {
                builder.append(' ')
            }
        }
        return builder.toString()
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_TERM_LENGTH)
    }
}
