package com.pdfpaper.engine.text

/**
 * Minimal CMap parser for /ToUnicode streams (ISO 32000-1, §9.7.9 CMap
 * operators with Tables 127-128; ToUnicode structure per the "ToUnicode CMaps"
 * subsection of §9.7). Only what text extraction needs is parsed:
 *
 *  - `beginbfchar` blocks: `<src> <dst>` pairs, dst = UTF-16BE hex possibly
 *    containing several code points.
 *  - `beginbfrange` blocks: `<lo> <hi> <dst>` with the destination incremented
 *    codepoint-wise per source, and the array form `<lo> <hi> [<d1> <d2> ...]`.
 *
 * `cidrange`/`cidchar` and named destinations are not used by ToUnicode and
 * are ignored. The parser is tolerant: malformed entries are skipped, and
 * output size is capped so a corrupt stream cannot exhaust memory.
 */
object CMapParser {

    /** Hard cap on materialised mappings per CMap (real ones stay below ~20000). */
    private const val MAX_ENTRIES = 100_000

    /** cap on consecutive codepoints synthesized by one bfrange row. */
    private const val MAX_RANGE_SPAN = 65_536L

    /**
     * Returns source-code → Unicode-string mappings. Keys keep the raw integer
     * value of the source hex string; simple-font callers mask to the low
     * byte (the parser also stores that alias), composite callers use the
     * value as a CID. An empty map is returned when nothing parses; callers
     * then fall back to the base encoding.
     */
    fun parse(data: ByteArray): Map<Int, String> {
        val text = toLatin1(data)
        val out = LinkedHashMap<Int, String>()
        var i = 0
        while (i < text.length && out.size < MAX_ENTRIES) {
            val beginChar = text.indexOf("beginbfchar", i)
            val beginRange = text.indexOf("beginbfrange", i)
            val takeChar = beginChar >= 0 && (beginRange < 0 || beginChar < beginRange)
            if (!takeChar && beginRange < 0) break
            if (takeChar) {
                val end = text.indexOf("endbfchar", beginChar)
                val stop = if (end >= 0) end else text.length
                val tokens = tokenize(text, beginChar + 11, stop)
                parseBfchar(tokens, out)
                i = if (end >= 0) end + 9 else text.length
            } else {
                val end = text.indexOf("endbfrange", beginRange)
                val stop = if (end >= 0) end else text.length
                val tokens = tokenize(text, beginRange + 12, stop)
                parseBfrange(tokens, out)
                i = if (end >= 0) end + 10 else text.length
            }
        }
        return out
    }

    /** Hex-string contents in order; `[` and `]` are separate single-char tokens. */
    private fun tokenize(text: String, from: Int, to: Int): List<String> {
        val tokens = ArrayList<String>(64)
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                c == '%' -> { while (i < to && text[i] != '\n') i++ }
                c == '<' -> {
                    val close = text.indexOf('>', i)
                    if (close < 0 || close > to) break
                    val inner = text.substring(i + 1, close).filter { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
                    if (inner.length % 2 == 0) tokens.add(inner)
                    i = close + 1
                }
                c == '[' || c == ']' -> { tokens.add(c.toString()); i++ }
                else -> i++
            }
        }
        return tokens
    }

    private fun parseBfchar(tokens: List<String>, out: LinkedHashMap<Int, String>) {
        var k = 0
        while (k + 1 < tokens.size && out.size < MAX_ENTRIES) {
            val src = hexToLong(tokens[k])
            val dst = utf16Decode(tokens[k + 1])
            if (src != null && dst.isNotEmpty()) put(out, src, dst)
            k += 2
        }
    }

    private fun parseBfrange(tokens: List<String>, out: LinkedHashMap<Int, String>) {
        var k = 0
        while (k + 2 < tokens.size && out.size < MAX_ENTRIES) {
            // Bracket tokens reset row scanning (skip stray ones defensively).
            val first = hexToLong(tokens[k])
            val second = if (first != null) hexToLong(tokens[k + 1]) else null
            if (first == null || second == null) {
                k++
                continue
            }
            val third = tokens[k + 2]
            if (third == "[") {
                var j = k + 3
                var code = first
                while (j < tokens.size && tokens[j] != "]") {
                    val dst = utf16Decode(tokens[j])
                    if (dst.isNotEmpty()) put(out, code, dst)
                    code++
                    j++
                }
                k = if (j < tokens.size) j + 1 else tokens.size
            } else {
                val base = utf16Decode(third)
                if (base.isNotEmpty()) {
                    val span = (second - first).coerceAtLeast(0).coerceAtMost(MAX_RANGE_SPAN)
                    val baseCp = codePoints(base)
                    if (baseCp.isNotEmpty()) {
                        var off = 0L
                        while (off <= span && out.size < MAX_ENTRIES) {
                            put(out, first + off, stringOfCps(listOf((baseCp[0] + off).toInt())))
                            off++
                        }
                    }
                }
                k += 3
            }
        }
    }

    private fun put(out: LinkedHashMap<Int, String>, src: Long, dst: String) {
        if (out.size >= MAX_ENTRIES) return
        // Full value (CID lookups) plus low-byte alias (simple-font codes),
        // so a two-byte source <0041> also answers code 0x41.
        if (src !in 0..0xFFFFFFFFL) return
        val v = src.toInt()
        out[v] = dst
        if (v !in 0..0xFF) out[v and 0xFF] = dst
    }

    private fun hexToLong(hex: String): Long? {
        if (hex.isEmpty() || hex.length > 8) return null
        var v = 0L
        for (ch in hex) {
            val d = Character.digit(ch, 16)
            if (d < 0) return null
            v = (v shl 4) + d
        }
        return v
    }

    private fun toLatin1(data: ByteArray): String {
        val sb = StringBuilder(data.size)
        for (b in data) sb.append((b.toInt() and 0xFF).toChar())
        return sb.toString()
    }

    /** Decodes a UTF-16BE byte sequence (hex pairs) into a String. */
    internal fun utf16Decode(hex: String): String {
        if (hex.isEmpty()) return ""
        val bytes = ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte()
        }
        val sb = StringBuilder(bytes.size / 2)
        var i = 0
        while (i + 1 < bytes.size) {
            val u = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            if (u in 0xD800..0xDBFF && i + 3 < bytes.size) {
                val lo = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                if (lo in 0xDC00..0xDFFF) {
                    sb.appendCodePoint(0x10000 + ((u - 0xD800) shl 10) + (lo - 0xDC00))
                    i += 4
                    continue
                }
            }
            sb.append(u.toChar())
            i += 2
        }
        return sb.toString()
    }

    internal fun codePoints(s: String): IntArray {
        val out = IntArray(s.length)
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                out[n++] = Character.toCodePoint(c, s[i + 1])
                i += 2
            } else {
                out[n++] = c.code
                i++
            }
        }
        return out.copyOf(n)
    }

    internal fun stringOfCps(cps: List<Int>): String {
        val sb = StringBuilder()
        for (cp in cps) {
            if (cp in 0..0x10FFFF) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }
}
