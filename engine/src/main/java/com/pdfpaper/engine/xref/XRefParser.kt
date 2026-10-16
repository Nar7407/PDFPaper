package com.pdfpaper.engine.xref

import com.pdfpaper.engine.io.RandomAccessSource
import java.nio.charset.StandardCharsets

/** One parsed xref section (classic table §7.5.4 or xref stream §7.5.8) plus its trailer. */
internal data class ParsedSection(
    val entries: Map<Int, XRefEntry>,
    val trailer: Trailer
)

/**
 * Low-level file-structure parsing (ISO 32000-1, §7.5). Internal helpers for
 * [PdfFile]; tokens here are minimal on purpose — the full object model
 * (arrays, streams, references) arrives in T4 (§7.3).
 */
internal object XRefParser {

    /** Max bytes of a single xref section parsed in one window (classic tables
     *  larger than this fall back to the rebuild scan). */
    private const val SECTION_WINDOW = 4 * 1024 * 1024
    private const val TAIL_SCAN = 8192
    private const val SCAN_CHUNK = 65536
    private const val SCAN_OVERLAP = 64

    // -- File header (§7.5.2) -------------------------------------------------

    /** Version from the "%PDF-1.N" marker near the file start, or null. */
    fun findHeaderVersion(source: RandomAccessSource): String? {
        val head = source.read(0, minOf(source.size, 2048).toInt())
        val idx = indexOf(head, "%PDF-".toByteArray(StandardCharsets.US_ASCII))
        if (idx < 0) return null
        var end = idx + 5
        while (end < head.size && (head[end].isAsciiDigit() || head[end] == '.'.code.toByte())) {
            end++
        }
        if (end == idx + 5) return null
        return head.copyOfRange(idx + 5, end).toString(StandardCharsets.US_ASCII)
    }

    // -- startxref (§7.5.5) ----------------------------------------------------

    /** Offset from the last "startxref" marker, or null when missing/broken. */
    fun findStartXref(source: RandomAccessSource): Long? {
        val tailLen = minOf(source.size, TAIL_SCAN.toLong()).toInt()
        if (tailLen <= 0) return null
        val tail = source.read(source.size - tailLen, tailLen)
        val marker = "startxref".toByteArray(StandardCharsets.US_ASCII)
        var last = -1
        var i = 0
        while (i <= tail.size - marker.size) {
            if (matchesAt(tail, i, marker)) last = i
            i++
        }
        if (last < 0) return null
        var j = last + marker.size
        while (j < tail.size && tail[j].isAsciiWhitespace()) j++
        var k = j
        while (k < tail.size && tail[k].isAsciiDigit()) k++
        if (k == j) return null
        return tail.copyOfRange(j, k).toString(StandardCharsets.US_ASCII).toLongOrNull()
    }

    // -- Sections ---------------------------------------------------------------

    /** Parses the xref section at [offset] (classic table or xref stream). Null = broken. */
    fun parseSectionAt(source: RandomAccessSource, offset: Long): ParsedSection? {
        if (offset < 0 || offset >= source.size) return null
        val windowLen = minOf(source.size - offset, SECTION_WINDOW.toLong()).toInt()
        if (windowLen <= 0) return null
        // Latin-1 maps bytes 1:1 to chars so string indices stay byte indices.
        val text = source.read(offset, windowLen).toString(StandardCharsets.ISO_8859_1)
        var pos = skipWsAndComments(text, 0)
        return try {
            if (text.startsWith("xref", pos) && isDelimAfter(text, pos + 4)) {
                parseClassic(text, pos + 4)
            } else {
                parseXRefStream(source, offset, text, pos)
            }
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    /** Classic cross-reference table (§7.5.4) plus trailer dictionary (§7.5.5). */
    private fun parseClassic(text: String, start: Int): ParsedSection? {
        var pos = start
        val entries = LinkedHashMap<Int, XRefEntry>()
        while (true) {
            val (line, next) = readLine(text, pos) ?: return null
            pos = next
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed == "trailer") break
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size != 2) return null
            val first = parts[0].toIntOrNull() ?: return null
            val count = parts[1].toIntOrNull() ?: return null
            if (first < 0 || count < 0) return null
            repeat(count) { k ->
                val (entryLine, entryNext) = readLine(text, pos) ?: return null
                pos = entryNext
                entries[first + k] =
                    parseClassicEntry(entryLine, first + k) ?: return null
            }
        }
        pos = skipWsAndComments(text, pos)
        val (raw, _) = parseDictRaw(text, pos) ?: return null
        return ParsedSection(entries, trailerFromRaw(raw))
    }

    private fun parseClassicEntry(line: String, objNum: Int): XRefEntry? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 3) return null
        val offset = parts[0].toLongOrNull() ?: return null
        val gen = parts[1].toIntOrNull() ?: return null
        return when (parts[2].firstOrNull()) {
            'n' -> XRefEntry(objNum, gen, offset, EntryKind.UNCOMPRESSED)
            'f' -> XRefEntry(objNum, gen, offset, EntryKind.FREE)
            else -> null
        }
    }

    /** Cross-reference stream (§7.5.8). Filtered streams await T4/T5; only direct
     *  /Length and unfiltered data are supported here (filtered -> null). */
    private fun parseXRefStream(
        source: RandomAccessSource,
        sectionOffset: Long,
        text: String,
        start: Int
    ): ParsedSection? {
        var pos = start
        // "N G obj" header (may span lines, so read a chunk and match loosely).
        val headerChunk = text.substring(pos, minOf(pos + 64, text.length))
        val headerMatch = Regex("(\\d+)\\s+(\\d+)\\s+obj\\b").find(headerChunk) ?: return null
        pos += headerMatch.range.last + 1
        pos = skipWsAndComments(text, pos)
        val (dict, afterDict) = parseDictRaw(text, pos) ?: return null
        // "stream" keyword on its own line.
        pos = afterDict
        var streamFound = false
        while (true) {
            val (line, next) = readLine(text, pos) ?: return null
            pos = next
            if (line.trim().isEmpty()) continue
            if (line.trim() != "stream") return null
            streamFound = true
            break
        }
        if (!streamFound) return null
        val dataStart = sectionOffset + pos
        val length = dict["Length"]?.trim()?.toIntOrNull() ?: return null
        if (length < 0 || dataStart + length > source.size) return null
        val widths = parseIntList(dict["W"] ?: return null) ?: return null
        if (widths.size != 3 || widths.any { it < 0 || it > 8 }) return null
        val width = widths.sum()
        if (width <= 0) return null
        val indexRaw = dict["Index"]?.let { parseIntList(it) }
        if (indexRaw != null && indexRaw.size % 2 != 0) return null
        val size = dict["Size"]?.trim()?.toIntOrNull() ?: return null
        if (size < 0) return null
        val ranges = indexRaw?.chunked(2)?.map { it[0] to it[1] } ?: listOf(0 to size)
        val totalEntries = ranges.sumOf { it.second }
        if (totalEntries < 0 || totalEntries.toLong() * width > length) return null
        val data = source.read(dataStart, totalEntries * width)
        if (data.size < totalEntries * width) return null
        val entries = LinkedHashMap<Int, XRefEntry>()
        var p = 0
        for ((first, count) in ranges) {
            var objNum = first
            repeat(count) {
                val type = if (widths[0] == 0) 1 else getUInt(data, p, widths[0]).toInt()
                val field2 = getUInt(data, p + widths[0], widths[1])
                val field3 = getUInt(data, p + widths[0] + widths[1], widths[2])
                entries[objNum] = when (type) {
                    0 -> XRefEntry(objNum, field3.toInt(), 0L, EntryKind.FREE)
                    1 -> XRefEntry(objNum, field3.toInt(), field2, EntryKind.UNCOMPRESSED)
                    2 -> XRefEntry(
                        objNum, -1, -1L, EntryKind.COMPRESSED,
                        field2.toInt(), field3.toInt()
                    )
                    else -> return null
                }
                objNum++
                p += width
            }
        }
        val tail = source.read(dataStart + length, 32)
            .toString(StandardCharsets.ISO_8859_1).trimStart()
        if (!tail.startsWith("endstream")) return null
        return ParsedSection(entries, trailerFromRaw(dict))
    }

    // -- Validation ---------------------------------------------------------------

    /** Every uncompressed entry must point at a matching "N G obj" header. */
    fun validateEntries(source: RandomAccessSource, entries: Map<Int, XRefEntry>): Boolean {
        for ((num, entry) in entries) {
            if (entry.kind != EntryKind.UNCOMPRESSED) continue
            if (entry.offset < 0 || entry.offset >= source.size) return false
            val head = try {
                source.read(entry.offset, 64)
            } catch (_: Exception) {
                return false
            }
            if (!matchesObjHeader(head, num, entry.gen)) return false
        }
        return true
    }

    // -- Rebuild scan ---------------------------------------------------------------

    /**
     * Fallback when the xref table is missing or broken: scan the whole file for
     * "N G obj" markers. Chunked with overlap so large files are never fully
     * loaded; last occurrence wins (newest in incremental files, §7.5.6).
     */
    fun rebuildScan(source: RandomAccessSource): Map<Int, XRefEntry> {
        val out = LinkedHashMap<Int, XRefEntry>()
        var base = 0L
        var carry = ByteArray(0)
        while (base < source.size) {
            val len = minOf(SCAN_CHUNK.toLong(), source.size - base).toInt()
            val data = try {
                source.read(base, len)
            } catch (_: Exception) {
                break
            }
            val buf = if (carry.isEmpty()) data else carry + data
            scanBuffer(buf, base - carry.size, out)
            carry = if (data.size >= SCAN_OVERLAP) {
                data.copyOfRange(data.size - SCAN_OVERLAP, data.size)
            } else {
                data
            }
            base += len
        }
        return out
    }

    private fun scanBuffer(buf: ByteArray, bufBase: Long, out: MutableMap<Int, XRefEntry>) {
        var i = 0
        while (i < buf.size) {
            if (!buf[i].isAsciiDigit() || (i > 0 && !isDelimByte(buf[i - 1]))) {
                i++
                continue
            }
            val match = matchObjHeaderAt(buf, i)
            if (match == null) {
                i++
                continue
            }
            out[match.first] = XRefEntry(match.first, match.second, bufBase + i, EntryKind.UNCOMPRESSED)
            i = match.third
        }
    }

    /** Matches "<num> <gen> obj" + delimiter at [i]; returns (num, gen, nextIndex). */
    private fun matchObjHeaderAt(buf: ByteArray, i: Int): Triple<Int, Int, Int>? {
        var j = i
        while (j < buf.size && buf[j].isAsciiDigit()) j++
        if (j == i || j - i > 10) return null
        val num = asciiInt(buf, i, j) ?: return null
        val k = skipAsciiWs(buf, j)
        var m = k
        while (m < buf.size && buf[m].isAsciiDigit()) m++
        if (m == k || m - k > 10) return null
        val gen = asciiInt(buf, k, m) ?: return null
        val n = skipAsciiWs(buf, m)
        if (n + 3 > buf.size) return null
        if (buf[n] != 'o'.code.toByte() || buf[n + 1] != 'b'.code.toByte() || buf[n + 2] != 'j'.code.toByte()) {
            return null
        }
        if (n + 3 < buf.size && !isDelimByte(buf[n + 3])) return null
        return Triple(num, gen, n + 3)
    }

    // -- Minimal dictionary parsing (§7.3.7, just enough for trailer/xref dicts) --

    /** Parses "<< ... >>" at [start]; values kept as raw text (balanced for containers). */
    internal fun parseDictRaw(text: String, start: Int): Pair<Map<String, String>, Int>? {
        var i = skipWsAndComments(text, start)
        if (!text.startsWith("<<", i)) return null
        i += 2
        val map = LinkedHashMap<String, String>()
        while (true) {
            i = skipWsAndComments(text, i)
            if (i >= text.length) return null
            if (text.startsWith(">>", i)) {
                i += 2
                break
            }
            if (text[i] != '/') return null
            i++
            val nameStart = i
            while (i < text.length && !isDelim(text[i])) i++
            if (i == nameStart) return null
            val name = text.substring(nameStart, i)
            i = skipWsAndComments(text, i)
            val (value, next) = readRawValue(text, i) ?: return null
            map[name] = value
            i = next
        }
        return map to i
    }

    private fun readRawValue(text: String, start: Int): Pair<String, Int>? {
        val i = skipWsAndComments(text, start)
        if (i >= text.length) return null
        return when {
            text.startsWith("<<", i) -> balanced(text, i, "<<", ">>")
            text[i] == '<' -> angleString(text, i)
            text[i] == '(' -> parenString(text, i)
            text[i] == '[' -> balanced(text, i, "[", "]")
            text[i] == '/' -> {
                var j = i + 1
                while (j < text.length && !isDelim(text[j])) j++
                text.substring(i, j) to j
            }
            else -> {
                // Plain token, or an indirect reference "N G R" (§7.3.10).
                var j = i
                while (j < text.length && !isDelim(text[j])) j++
                if (j == i) return null
                val first = text.substring(i, j)
                if (first.toIntOrNull() == null) return first to j
                val k = skipWsAndComments(text, j)
                var m = k
                while (m < text.length && !isDelim(text[m])) m++
                if (m == k || text.substring(k, m).toIntOrNull() == null) return first to j
                val n = skipWsAndComments(text, m)
                var q = n
                while (q < text.length && !isDelim(text[q])) q++
                if (q == n || text.substring(n, q) != "R") return first to j
                "$first ${text.substring(k, m)} R" to q
            }
        }
    }

    /** Raw span of a balanced open/close pair, skipping nested pairs and strings. */
    private fun balanced(text: String, start: Int, open: String, close: String): Pair<String, Int>? {
        var i = start + open.length
        var depth = 1
        while (i < text.length && depth > 0) {
            when {
                text.startsWith(open, i) -> {
                    depth++
                    i += open.length
                }
                text.startsWith(close, i) -> {
                    depth--
                    i += close.length
                }
                text[i] == '(' -> i = parenString(text, i)?.second ?: return null
                text[i] == '<' && !text.startsWith("<<", i) -> {
                    i = angleString(text, i)?.second ?: return null
                }
                text[i] == '%' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                else -> i++
            }
        }
        if (depth != 0) return null
        return text.substring(start, i) to i
    }

    private fun angleString(text: String, start: Int): Pair<String, Int>? {
        val end = text.indexOf('>', start + 1)
        if (end < 0) return null
        return text.substring(start, end + 1) to end + 1
    }

    private fun parenString(text: String, start: Int): Pair<String, Int>? {
        var i = start + 1
        var depth = 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '\\' -> i += 2
                '(' -> {
                    depth++
                    i++
                }
                ')' -> {
                    depth--
                    i++
                }
                else -> i++
            }
        }
        if (depth != 0) return null
        return text.substring(start, i) to i
    }

    internal fun trailerFromRaw(raw: Map<String, String>): Trailer {
        val size = raw["Size"]?.trim()?.toIntOrNull() ?: -1
        val root = raw["Root"]?.let { parseRef(it) }
        val prev = raw["Prev"]?.trim()?.toLongOrNull()
        return Trailer(size, root, prev, raw)
    }

    private fun parseRef(raw: String): IndirectRef? {
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size != 3 || parts[2] != "R") return null
        val num = parts[0].toIntOrNull() ?: return null
        val gen = parts[1].toIntOrNull() ?: return null
        return IndirectRef(num, gen)
    }

    internal fun parseIntList(raw: String): List<Int>? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(Regex("\\s+")).map { it.toIntOrNull() ?: return null }
    }

    // -- Byte/text helpers ----------------------------------------------------------

    private fun getUInt(data: ByteArray, offset: Int, width: Int): Long {
        var value = 0L
        for (k in 0 until width) {
            value = (value shl 8) or (data[offset + k].toInt() and 0xFF).toLong()
        }
        return value
    }

    private fun readLine(text: String, pos: Int): Pair<String, Int>? {
        if (pos >= text.length) return null
        var end = pos
        while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
        val line = text.substring(pos, end)
        var next = end
        if (next < text.length && text[next] == '\r') next++
        if (next < text.length && text[next] == '\n') next++
        return line to next
    }

    private fun skipWsAndComments(text: String, start: Int): Int {
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                while (i < text.length && text[i] != '\n') i++
            } else if (c == '\u0000' || c.isWhitespace()) {
                i++
            } else {
                break
            }
        }
        return i
    }

    /** Delimiters (§7.2.2): whitespace plus ( ) < > [ ] / %. */
    private fun isDelim(c: Char): Boolean =
        c == '\u0000' || c.isWhitespace() || c in "()<>[]{}/%"

    private fun isDelimAfter(text: String, pos: Int): Boolean =
        pos >= text.length || isDelim(text[pos])

    private fun skipAsciiWs(buf: ByteArray, start: Int): Int {
        var i = start
        while (i < buf.size && buf[i].isAsciiWhitespace()) i++
        return i
    }

    private fun asciiInt(buf: ByteArray, from: Int, to: Int): Int? {
        if (from >= to) return null
        var value = 0
        for (i in from until to) {
            val d = buf[i].toInt() - '0'.code
            if (d < 0 || d > 9) return null
            value = value * 10 + d
            if (value > Int.MAX_VALUE / 10) return null
        }
        return value
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun matchesAt(haystack: ByteArray, pos: Int, needle: ByteArray): Boolean {
        if (pos + needle.size > haystack.size) return false
        for (j in needle.indices) {
            if (haystack[pos + j] != needle[j]) return false
        }
        return true
    }

    private fun isDelimByte(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 ||
            c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '/'.code || c == '%'.code
    }

    private fun Byte.isAsciiDigit(): Boolean = this in '0'.code.toByte()..'9'.code.toByte()

    private fun Byte.isAsciiWhitespace(): Boolean {
        val c = this.toInt() and 0xFF
        return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
    }
}

/** "N G obj" header check used to validate xref entries and rebuilt candidates. */
internal fun matchesObjHeader(head: ByteArray, objNum: Int, gen: Int): Boolean {
    val text = head.toString(StandardCharsets.ISO_8859_1)
    val end = text.indexOf('\n').takeIf { it >= 0 }?.let { minOf(it, text.length) } ?: text.length
    val parts = text.substring(0, end).trim().split(Regex("\\s+"))
    return parts.size >= 3 &&
        parts[0].toIntOrNull() == objNum &&
        parts[1].toIntOrNull() == gen &&
        parts[2] == "obj"
}

/** Index of "endobj" with delimiters on both sides, or -1. */
internal fun indexOfEndObj(buf: ByteArray): Int {
    val marker = "endobj".toByteArray(StandardCharsets.US_ASCII)
    outer@ for (i in 0..buf.size - marker.size) {
        for (j in marker.indices) {
            if (buf[i + j] != marker[j]) continue@outer
        }
        val beforeOk = i == 0 || isDelimByteLocal(buf[i - 1])
        val after = i + marker.size
        val afterOk = after >= buf.size || isDelimByteLocal(buf[after])
        if (beforeOk && afterOk) return i
    }
    return -1
}

private fun isDelimByteLocal(b: Byte): Boolean {
    val c = b.toInt() and 0xFF
    return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 ||
        c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
        c == '['.code || c == ']'.code || c == '/'.code || c == '%'.code
}
