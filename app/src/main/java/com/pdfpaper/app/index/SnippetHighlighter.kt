package com.pdfpaper.app.index

/** One run of a snippet; [highlighted] marks the words FTS4 matched. */
data class SnippetPart(val text: String, val highlighted: Boolean)

/**
 * FTS4's `snippet()` is given two control characters as its start/end markers
 * (see [SearchIndexDao]). This parser turns that marked string into styled
 * runs. It is deliberately tolerant: a stray marker, a control character from
 * a broken PDF, or a run of whitespace can never crash the result list.
 */
object SnippetHighlighter {

    const val MARK_START: Char = '\u0001'
    const val MARK_END: Char = '\u0002'

    fun parse(marked: String): List<SnippetPart> {
        val parts = ArrayList<SnippetPart>()
        val current = StringBuilder()
        var highlighted = false
        var lastWasSpace = false

        fun flush() {
            if (current.isEmpty()) return
            parts += SnippetPart(current.toString(), highlighted)
            current.setLength(0)
        }

        for (ch in marked) {
            when {
                ch == MARK_START -> {
                    flush()
                    highlighted = true
                    lastWasSpace = false
                }
                ch == MARK_END -> {
                    flush()
                    highlighted = false
                    lastWasSpace = false
                }
                ch.isWhitespace() || ch.code < 0x20 -> {
                    if (!lastWasSpace) {
                        if (current.isNotEmpty() ||
                            parts.lastOrNull()?.text?.endsWith(' ') != true
                        ) {
                            current.append(' ')
                            lastWasSpace = true
                        }
                    }
                }
                else -> {
                    current.append(ch)
                    lastWasSpace = false
                }
            }
        }
        flush()
        // Keep the space between two highlighted runs, but not at the edges of
        // the whole snippet.
        if (parts.isNotEmpty()) {
            parts[0] = parts[0].copy(text = parts[0].text.trimStart())
            val last = parts.lastIndex
            parts[last] = parts[last].copy(text = parts[last].text.trimEnd())
        }
        return parts.filter { it.text.isNotEmpty() }
    }

    fun plain(marked: String): String = parse(marked).joinToString("") { it.text }
}
