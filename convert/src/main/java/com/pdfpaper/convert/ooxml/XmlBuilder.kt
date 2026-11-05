package com.pdfpaper.convert.ooxml

/**
 * A tiny XML 1.0 writer for the OOXML parts we produce. It only covers what
 * the converters need — elements, attributes and leaf text — and it is
 * deliberately strict about escaping: text coming out of a PDF can contain
 * control characters or unpaired surrogates that are illegal in XML, and one
 * bad byte would make the whole document open with a repair prompt.
 *
 * No third-party XML library is used (AGENTS.md: no new dependencies).
 */
class XmlBuilder {

    private val sb = StringBuilder(1 shl 16)
    private var depth = 0

    /** `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`. */
    fun declaration(): XmlBuilder {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        return this
    }

    fun open(name: String, vararg attrs: Pair<String, String>): XmlBuilder {
        indent().append('<').append(name)
        appendAttrs(attrs)
        sb.append(">\n")
        depth++
        return this
    }

    fun close(name: String): XmlBuilder {
        depth--
        indent().append("</").append(name).append(">\n")
        return this
    }

    fun empty(name: String, vararg attrs: Pair<String, String>): XmlBuilder {
        indent().append('<').append(name)
        appendAttrs(attrs)
        sb.append("/>\n")
        return this
    }

    /** `<name ...>text</name>` on one line, so whitespace never leaks into text. */
    fun leafText(name: String, text: String, vararg attrs: Pair<String, String>): XmlBuilder {
        indent().append('<').append(name)
        appendAttrs(attrs)
        sb.append('>').append(escapeText(text)).append("</").append(name).append(">\n")
        return this
    }

    fun build(): String = sb.toString()

    private fun appendAttrs(attrs: Array<out Pair<String, String>>) {
        for ((key, value) in attrs) {
            sb.append(' ').append(key).append("=\"").append(escapeAttribute(value)).append('"')
        }
    }

    private fun indent(): StringBuilder {
        for (i in 0 until depth) sb.append("  ")
        return sb
    }

    companion object {
        /**
         * Drops characters XML 1.0 forbids (§2.2): control characters other
         * than tab/CR/LF, and unpaired surrogates. Replaced rather than thrown
         * so a damaged PDF can still convert.
         */
        fun stripInvalid(text: String): String {
            var needsFilter = false
            for (ch in text) {
                if (!isXmlChar(ch)) {
                    needsFilter = true
                    break
                }
            }
            if (!needsFilter) return text
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (Character.isHighSurrogate(ch) && i + 1 < text.length &&
                    Character.isLowSurrogate(text[i + 1])
                ) {
                    out.append(ch).append(text[i + 1])
                    i += 2
                    continue
                }
                out.append(if (isXmlChar(ch)) ch else '\uFFFD')
                i++
            }
            return out.toString()
        }

        /** Escapes text content (`&`, `<`, `>`). */
        fun escapeText(text: String): String {
            val cleaned = stripInvalid(text)
            val out = StringBuilder(cleaned.length + 8)
            for (ch in cleaned) {
                when (ch) {
                    '&' -> out.append("&amp;")
                    '<' -> out.append("&lt;")
                    '>' -> out.append("&gt;")
                    else -> out.append(ch)
                }
            }
            return out.toString()
        }

        /** Escapes an attribute value (`&`, `<`, `>`, `"`, and whitespace refs). */
        fun escapeAttribute(text: String): String {
            val cleaned = stripInvalid(text)
            val out = StringBuilder(cleaned.length + 8)
            for (ch in cleaned) {
                when (ch) {
                    '&' -> out.append("&amp;")
                    '<' -> out.append("&lt;")
                    '>' -> out.append("&gt;")
                    '"' -> out.append("&quot;")
                    '\n' -> out.append("&#10;")
                    '\r' -> out.append("&#13;")
                    '\t' -> out.append("&#9;")
                    else -> out.append(ch)
                }
            }
            return out.toString()
        }

        private fun isXmlChar(ch: Char): Boolean =
            ch == '\t' || ch == '\n' || ch == '\r' ||
                (ch in '\u0020'..'\uD7FF') || (ch in '\uE000'..'\uFFFD')
    }
}
