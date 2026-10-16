package com.pdfpaper.engine.model

/** "N G obj ... endobj" with the parsed body. */
data class ParsedIndirect(val objNum: Int, val gen: Int, val obj: PdfObject)

/** Parse one direct object (§7.3); trailing bytes are ignored (tolerant). */
fun parseDirectObject(bytes: ByteArray): Result<PdfObject> = runCatching {
    val parser = ObjectParser(tokenize(bytes), bytes)
    parser.parseSingle()
}

/** Parse one indirect object (§7.3.8); a missing `endobj` is tolerated. */
fun parseIndirectObject(bytes: ByteArray): Result<ParsedIndirect> = runCatching {
    ObjectParser(tokenize(bytes), bytes).parseIndirect()
}

/**
 * Recursive-descent parser over [PdfToken]s. Tolerant where real files vary:
 * missing `endobj`/`endstream` and wrong `/Length` fall back to scanning for
 * `endstream` or end of input; anything else malformed fails via
 * [PdfParseException] (surfaced as [Result.failure], never a crash).
 */
internal class ObjectParser(private val tokens: List<PdfToken>, private val input: ByteArray) {

    private var pos = 0
    private var depth = 0

    fun parseSingle(): PdfObject {
        if (tokens.isEmpty()) throw PdfParseException("Empty object")
        return parseObject()
    }

    fun parseIndirect(): ParsedIndirect {
        val num = (peek() as? TInt)?.value?.toIntExact()
            ?: throw PdfParseException("Indirect object must start with an object number")
        val gen = (peekAt(1) as? TInt)?.value?.toIntExact()
            ?: throw PdfParseException("Indirect object needs a generation number")
        val objWord = peekAt(2) as? TWord
            ?: throw PdfParseException("Indirect object needs 'obj'")
        if (objWord.text != "obj") throw PdfParseException("Expected 'obj', got '${objWord.text}'")
        pos += 3
        val obj = parseObject()
        val end = peek()
        if (end is TWord) {
            if (end.text != "endobj") throw PdfParseException("Expected 'endobj', got '${end.text}'")
            pos++
        }
        // Missing 'endobj' (or end of input): accept what was parsed.
        return ParsedIndirect(num, gen, obj)
    }

    private fun parseObject(): PdfObject {
        if (++depth > MAX_DEPTH) throw PdfParseException("Nesting too deep")
        try {
            val t = peek() ?: throw PdfParseException("Unexpected end of object")
            return when (t) {
                is TArrayStart -> parseArray()
                is TDictStart -> parseDictOrStream()
                is TInt -> {
                    val genTok = peekAt(1)
                    val rWord = peekAt(2)
                    if (genTok is TInt && rWord is TWord && rWord.text == "R") {
                        pos += 3
                        PdfRef(t.value.toIntExact(), genTok.value.toIntExact())
                    } else {
                        pos++
                        PdfInteger(t.value)
                    }
                }
                is TReal -> {
                    pos++
                    PdfReal(t.value)
                }
                is TWord -> when (t.text) {
                    "true" -> {
                        pos++
                        PdfBoolean(true)
                    }
                    "false" -> {
                        pos++
                        PdfBoolean(false)
                    }
                    "null" -> {
                        pos++
                        PdfNull
                    }
                    else -> throw PdfParseException("Unexpected keyword '${t.text}'")
                }
                is TName -> {
                    pos++
                    PdfName(t.name)
                }
                is TString -> {
                    pos++
                    PdfString(t.bytes)
                }
                is THex -> {
                    pos++
                    PdfString(t.bytes)
                }
                is TArrayEnd -> throw PdfParseException("Unexpected ']'")
                is TDictEnd -> throw PdfParseException("Unexpected '>>'")
            }
        } finally {
            depth--
        }
    }

    private fun parseArray(): PdfArray {
        pos++ // '['
        val items = ArrayList<PdfObject>()
        while (true) {
            val t = peek() ?: throw PdfParseException("Unterminated array")
            if (t is TArrayEnd) {
                pos++
                break
            }
            items += parseObject()
        }
        return PdfArray(items)
    }

    private fun parseDictOrStream(): PdfObject {
        pos++ // '<<'
        val map = LinkedHashMap<PdfName, PdfObject>()
        while (true) {
            val t = peek() ?: throw PdfParseException("Unterminated dictionary")
            if (t is TDictEnd) {
                pos++
                break
            }
            if (t !is TName) throw PdfParseException("Dictionary key must be a name")
            pos++
            map[PdfName(t.name)] = parseObject()
        }
        val dict = PdfDict(map)
        val next = peek()
        if (next is TWord && next.text == "stream") {
            pos++
            return parseStreamData(dict, next.end)
        }
        return dict
    }

    /** Stream data (§7.3.8.1): declared /Length wins when `endstream` follows it, else scan. */
    private fun parseStreamData(dict: PdfDict, afterStream: Int): PdfStream {
        var dataStart = afterStream
        dataStart += eolLengthAt(dataStart)
        val declared = (dict["Length"] as? PdfInteger)?.value?.toIntOrNull()
        var dataEnd: Int? = null
        if (declared != null && declared >= 0 && dataStart + declared <= input.size) {
            val afterData = dataStart + declared + eolLengthAt(dataStart + declared)
            if (matchWordAt(afterData, "endstream")) {
                dataEnd = dataStart + declared
            }
        }
        if (dataEnd == null) {
            val found = indexOfWord(dataStart, "endstream")
            dataEnd = if (found >= 0) stripTrailingEol(dataStart, found) else input.size
        }
        skipDataTokens(dataEnd)
        return PdfStream(dict, input.copyOfRange(dataStart, dataEnd))
    }

    /**
     * The tokenizer also tokenized the raw stream data; skip those tokens (plus
     * `endstream`) by byte position so parsing continues after the stream.
     * Missing `endstream` consumes everything to end of input (tolerant).
     */
    private fun skipDataTokens(dataEnd: Int) {
        var keywordAt = dataEnd + eolLengthAt(dataEnd)
        while (pos < tokens.size && tokens[pos].start < keywordAt) pos++
        val t = tokens.getOrNull(pos)
        if (t is TWord && t.text == "endstream") pos++
    }

    private fun eolLengthAt(at: Int): Int {
        if (at >= input.size) return 0
        return if (input[at] == '\r'.code.toByte()) {
            if (at + 1 < input.size && input[at + 1] == '\n'.code.toByte()) 2 else 1
        } else if (input[at] == '\n'.code.toByte()) {
            1
        } else {
            0
        }
    }

    private fun matchWordAt(at: Int, word: String): Boolean {
        val bytes = word.toByteArray(Charsets.US_ASCII)
        if (at + bytes.size > input.size) return false
        for (k in bytes.indices) {
            if (input[at + k] != bytes[k]) return false
        }
        val after = at + bytes.size
        return after >= input.size || isPdfDelim(input[after])
    }

    private fun indexOfWord(from: Int, word: String): Int {
        val bytes = word.toByteArray(Charsets.US_ASCII)
        outer@ for (i in from..input.size - bytes.size) {
            for (k in bytes.indices) {
                if (input[i + k] != bytes[k]) continue@outer
            }
            val after = i + bytes.size
            if (after < input.size && !isPdfDelim(input[after])) continue@outer
            return i
        }
        return -1
    }

    private fun stripTrailingEol(dataStart: Int, end: Int): Int {
        var e = end
        if (e - 2 >= dataStart && input[e - 2] == '\r'.code.toByte() && input[e - 1] == '\n'.code.toByte()) {
            e -= 2
        } else if (e - 1 >= dataStart && (input[e - 1] == '\n'.code.toByte() || input[e - 1] == '\r'.code.toByte())) {
            e -= 1
        }
        return e
    }

    private fun peek(): PdfToken? = tokens.getOrNull(pos)

    private fun peekAt(offset: Int): PdfToken? = tokens.getOrNull(pos + offset)

    private companion object {
        const val MAX_DEPTH = 256
    }
}

private fun Long.toIntExact(): Int {
    if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) {
        throw PdfParseException("Integer out of range: $this")
    }
    return toInt()
}

private fun Long.toIntOrNull(): Int? =
    if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) null else toInt()

private fun isPdfDelim(b: Byte): Boolean {
    val c = b.toInt() and 0xFF
    return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 ||
        c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
        c == '['.code || c == ']'.code || c == '/'.code || c == '%'.code
}
