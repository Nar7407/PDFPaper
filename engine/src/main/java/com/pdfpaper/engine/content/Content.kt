package com.pdfpaper.engine.content

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfInlineImage
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfString

/**
 * Parsed page content (ISO 32000-1, §7.8): commands in stream order, one per
 * operator occurrence. Scanning is tolerant: corrupt bytes stop scanning and
 * yield the prefix decoded so far plus [warnings], never an exception.
 */
class ParsedContent(val commands: List<ContentCommand>, val warnings: List<String>)

/** One operator occurrence (§7.8.1): [operands] in source order, then [operator]. */
class ContentCommand(val operands: List<PdfObject>, val operator: String) {
    override fun toString(): String = "(${operands.joinToString(" ")}) $operator"
}

// Bounds that keep broken streams from consuming unbounded memory.
internal const val MAX_OPERANDS = 4096
internal const val MAX_COMMANDS = 1_000_000

/**
 * Parses content-stream bytes (§7.8.2-3) into [ContentCommand]s. The BI/ID/EI
 * inline-image sequence (§8.9.7.3) is collapsed into a single command with
 * operator "ID" whose sole operand is a [PdfInlineImage].
 */
fun parseContent(data: ByteArray): ParsedContent {
    val scanner = ContentScanner(data)
    val warnings = scanner.warnings
    val commands = ArrayList<ContentCommand>()
    val operands = ArrayList<PdfObject>()
    val frames = ArrayDeque<Frame>()

    fun push(obj: PdfObject) {
        when (val top = frames.lastOrNull()) {
            null -> operands += obj
            is Frame.Array -> top.items += obj
            is Frame.Dict -> when (val key = top.pendingKey) {
                null -> warnings += "dictionary value without key ignored: $obj"
                else -> { top.entries[key] = obj; top.pendingKey = null }
            }
        }
    }

    while (true) {
        if (operands.size > MAX_OPERANDS) {
            warnings += "more than $MAX_OPERANDS consecutive operands, abandoning remainder"
            operands.clear()
        }
        if (commands.size >= MAX_COMMANDS) {
            warnings += "more than $MAX_COMMANDS operators, rest ignored"
            break
        }
        val token = scanner.next() ?: break
        when (token) {
            is CToken.CArrayStart -> frames.addLast(Frame.Array(mutableListOf()))
            is CToken.CArrayEnd -> {
                when (val top = frames.removeLastOrNull()) {
                    is Frame.Array -> push(PdfArray(top.items))
                    else -> warnings += "unmatched ']' ignored"
                }
            }
            is CToken.CDictStart -> frames.addLast(Frame.Dict(LinkedHashMap(), null))
            is CToken.CDictEnd -> {
                when (val top = frames.removeLastOrNull()) {
                    is Frame.Dict -> {
                        top.pendingKey?.let { warnings += "missing value for /${it.name} ignored" }
                        push(PdfDict(top.entries))
                    }
                    else -> warnings += "unmatched '>>' ignored"
                }
            }
            is CToken.CName -> {
                val top = frames.lastOrNull()
                if (top is Frame.Dict) {
                    when (val key = top.pendingKey) {
                        null -> top.pendingKey = PdfName(token.name)
                        // Names are legal dictionary values (/Filter /FlateDecode).
                        else -> { top.entries[key] = PdfName(token.name); top.pendingKey = null }
                    }
                } else push(PdfName(token.name))
            }
            is CToken.CInt -> push(PdfInteger(token.value))
            is CToken.CReal -> push(PdfReal(token.value))
            is CToken.CString -> push(PdfString(token.bytes))
            is CToken.CWord -> when {
                // Inline image (BI/ID/EI -> one "ID" command, §8.9.7.3).
                token.text == "BI" -> {
                    val image = readInlineImage(scanner)
                    if (image == null) {
                        warnings += "broken inline image after BI"
                    } else {
                        // Inline images are standalone commands in real streams.
                        while (frames.isNotEmpty()) {
                            warnings += "unterminated object abandoned around inline image"
                            frames.removeLast()
                        }
                        commands += ContentCommand(listOf(image), "ID")
                    }
                }
                else -> {
                    // Operators never nest: abandon dangling arrays/dicts.
                    while (frames.isNotEmpty()) {
                        warnings += "unterminated object abandoned at operator '${token.text}'"
                        frames.removeLast()
                    }
                    commands += ContentCommand(operands.toList(), token.text)
                    operands.clear()
                }
            }
        }
    }
    if (operands.isNotEmpty()) warnings += "trailing operands at end of stream: ${operands.size}"
    return ParsedContent(commands, warnings)
}

private sealed interface Frame {
    class Array(val items: MutableList<PdfObject>) : Frame
    class Dict(val entries: LinkedHashMap<PdfName, PdfObject>, var pendingKey: PdfName?) : Frame
}

/**
 * Inline image capture (§8.9.7.3, Table 93): dictionary pairs until the ID
 * operator, then raw bytes. Data starts after exactly one whitespace byte and
 * ends at "EI" surrounded by whitespace (tolerated: any "EI", none: whole rest).
 */
private fun readInlineImage(scanner: ContentScanner): PdfInlineImage? {
    val entries = LinkedHashMap<PdfName, PdfObject>()
    var pendingKey: PdfName? = null

    fun assignValue(obj: PdfObject) {
        when (val key = pendingKey) {
            null -> scanner.warnings += "inline image value without key ignored: $obj"
            else -> { entries[key] = obj; pendingKey = null }
        }
    }

    while (true) {
        val token = scanner.next() ?: run {
            scanner.warnings += "missing ID after BI"
            return null
        }
        when (token) {
            is CToken.CWord -> {
                if (token.text == "ID") break
                scanner.warnings += "unexpected '${token.text}' inside inline image dictionary"
            }
            is CToken.CName -> {
                when (val key = pendingKey) {
                    null -> pendingKey = PdfName(token.name)
                    else -> { entries[key] = PdfName(token.name); pendingKey = null }
                }
            }
            is CToken.CInt -> assignValue(PdfInteger(token.value))
            is CToken.CReal -> assignValue(PdfReal(token.value))
            is CToken.CString -> assignValue(PdfString(token.bytes))
            is CToken.CArrayStart -> {
                val array = readInlineArray(scanner)
                if (array == null) return null
                assignValue(array)
            }
            is CToken.CDictStart -> {
                scanner.warnings += "nested dict unsupported in inline image, skipped"
                skipNested(scanner)
            }
            is CToken.CArrayEnd -> scanner.warnings += "unmatched ']' in inline image, skipped"
            is CToken.CDictEnd -> scanner.warnings += "unmatched '>>' in inline image, skipped"
        }
    }
    pendingKey?.let { scanner.warnings += "missing value for /${it.name} in inline image ignored" }

    // Exactly one whitespace byte separates ID and the data (binary data may
    // legitimately start with whitespace bytes, so never skip more).
    var dataStart = scanner.pos
    val d = scanner.data
    if (dataStart < d.size && d[dataStart].isContentWs()) dataStart++

    val found = findEi(d, dataStart)
    val end: Int
    if (found != null) {
        var e = found.first
        // The whitespace immediately preceding EI is the terminator's delimiter,
        // not image data — strip exactly one such byte.
        if (e > dataStart && d[e - 1].isContentWs()) e--
        end = e
        scanner.pos = found.second
    } else {
        end = d.size
        scanner.pos = d.size
        scanner.warnings += "missing EI after inline image, data extends to end of stream"
    }
    val imageData = if (end > dataStart) d.copyOfRange(dataStart, end) else ByteArray(0)
    return PdfInlineImage(PdfDict(entries), imageData)
}

private fun readInlineArray(scanner: ContentScanner): PdfArray? {
    val items = ArrayList<PdfObject>()
    while (true) {
        val token = scanner.next() ?: run {
            scanner.warnings += "unterminated array in inline image"
            return null
        }
        when (token) {
            is CToken.CArrayEnd -> return PdfArray(items)
            is CToken.CInt -> items += PdfInteger(token.value)
            is CToken.CReal -> items += PdfReal(token.value)
            is CToken.CName -> items += PdfName(token.name)
            is CToken.CString -> items += PdfString(token.bytes)
            is CToken.CArrayStart -> {
                val nested = readInlineArray(scanner)
                if (nested == null) return null
                items += nested
            }
            is CToken.CWord -> scanner.warnings += "unexpected '${token.text}' in inline array"
            else -> scanner.warnings += "unexpected token in inline array"
        }
    }
}

/** Skips a balanced token region (used for unsupported nested dicts). */
private fun skipNested(scanner: ContentScanner) {
    var depth = 1
    while (depth > 0) {
        when (scanner.next()) {
            null -> return
            is CToken.CDictStart, is CToken.CArrayStart -> depth++
            is CToken.CDictEnd, is CToken.CArrayEnd -> depth--
            else -> {}
        }
    }
}

private fun findEi(data: ByteArray, from: Int): Pair<Int, Int>? {
    // Preferred (§8.9.7.3): EI preceded and followed by whitespace/EOD.
    for (i in maxOf(1, from) until data.size - 1) {
        if (data[i] == 'E'.code.toByte() && data[i + 1] == 'I'.code.toByte() &&
            data[i - 1].isContentWs() &&
            (i + 2 >= data.size || data[i + 2].isContentWs())
        ) {
            return i to (i + 2)
        }
    }
    // Tolerant fallback: any "EI" pair.
    for (i in from until data.size - 1) {
        if (data[i] == 'E'.code.toByte() && data[i + 1] == 'I'.code.toByte()) {
            return i to (i + 2)
        }
    }
    return null
}

/**
 * Content-stream tokenizer (§7.8.3, object syntax per §7.2-7.3). It mirrors
 * the object tokenizer's byte semantics but scans lazily: binary inline-image
 * data must be captured byte-exact and cannot survive whole-input tokenizing.
 */
private val NUMBER = Regex("[+-]?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][+-]?\\d+)?")

internal sealed interface CToken {
    object CArrayStart : CToken
    object CArrayEnd : CToken
    object CDictStart : CToken
    object CDictEnd : CToken
    data class CInt(val value: Long) : CToken
    data class CReal(val value: Double) : CToken
    data class CName(val name: String) : CToken
    data class CString(val bytes: ByteArray) : CToken
    data class CWord(val text: String) : CToken
}

internal class ContentScanner(val data: ByteArray) {

    var pos = 0
    val warnings = ArrayList<String>()

    fun next(): CToken? {
        while (pos < data.size) {
            val b = data[pos]
            when {
                b.isContentWs() || b == 0.toByte() -> pos++
                b == '%'.code.toByte() -> { // comment (§7.2.3): skip to EOL
                    while (pos < data.size && data[pos] != '\n'.code.toByte()) pos++
                }
                b == '('.code.toByte() -> return readLiteral()
                b == '<'.code.toByte() -> return if (peek(1) == '<'.code.toByte()) {
                    pos += 2
                    CToken.CDictStart
                } else readHex()
                b == '>'.code.toByte() -> return if (peek(1) == '>'.code.toByte()) {
                    pos += 2
                    CToken.CDictEnd
                } else {
                    pos++
                    CToken.CWord(">")
                }
                b == '['.code.toByte() -> { pos++; return CToken.CArrayStart }
                b == ']'.code.toByte() -> { pos++; return CToken.CArrayEnd }
                b == '/'.code.toByte() -> return readName()
                b == ')'.code.toByte() -> { // stray ')' inside binary data: word
                    pos++
                    return CToken.CWord(")")
                }
                else -> return readNumberOrWord()
            }
        }
        return null
    }

    private fun peek(offset: Int): Byte? =
        if (pos + offset < data.size) data[pos + offset] else null

    /** Literal string (§7.3.4.2); unterminated keeps the partial value. */
    private fun readLiteral(): CToken.CString {
        val buf = java.io.ByteArrayOutputStream()
        var i = pos + 1
        var depth = 1
        while (i < data.size && depth > 0) {
            val c = data[i]
            when (c) {
                '\\'.code.toByte() -> {
                    i++
                    if (i >= data.size) break
                    when (val e = data[i]) {
                        'n'.code.toByte() -> buf.write('\n'.code)
                        'r'.code.toByte() -> buf.write('\r'.code)
                        't'.code.toByte() -> buf.write('\t'.code)
                        'b'.code.toByte() -> buf.write('\b'.code)
                        'f'.code.toByte() -> buf.write(12)
                        '('.code.toByte() -> buf.write('('.code)
                        ')'.code.toByte() -> buf.write(')'.code)
                        '\\'.code.toByte() -> buf.write('\\'.code)
                        '\r'.code.toByte() -> {
                            if (i + 1 < data.size && data[i + 1] == '\n'.code.toByte()) i++
                        }
                        '\n'.code.toByte() -> { /* line continuation: emit nothing */ }
                        else -> {
                            if (e in '0'.code.toByte()..'7'.code.toByte()) {
                                var value = e - '0'.code.toByte()
                                repeat(2) {
                                    val n = i + 1
                                    if (n < data.size && data[n] in '0'.code.toByte()..'7'.code.toByte()) {
                                        i = n
                                        value = value * 8 + (data[i] - '0'.code.toByte())
                                    }
                                }
                                buf.write(value and 0xFF)
                            } else {
                                buf.write(e.toInt())
                            }
                        }
                    }
                    i++
                }
                '('.code.toByte() -> { depth++; buf.write(c.toInt()); i++ }
                ')'.code.toByte() -> {
                    depth--
                    if (depth > 0) buf.write(c.toInt())
                    i++
                }
                '\r'.code.toByte() -> {
                    buf.write('\n'.code)
                    i++
                    if (i < data.size && data[i] == '\n'.code.toByte()) i++
                }
                else -> { buf.write(c.toInt()); i++ }
            }
        }
        if (depth > 0) warnings += "unterminated literal string, partial value kept"
        pos = i.coerceAtMost(data.size)
        return CToken.CString(buf.toByteArray())
    }

    /** Hex string (§7.3.4.3); bad digits stop the scan, partial bytes kept. */
    private fun readHex(): CToken.CString {
        val digits = StringBuilder()
        var i = pos + 1
        var bad = false
        while (i < data.size) {
            val c = data[i]
            if (c == '>'.code.toByte()) { i++; break }
            if (c.isContentWs() || c == 0.toByte()) { i++; continue }
            val v = digitValue(c)
            if (v != null) { digits.append("0123456789abcdef"[v]); i++ }
            else {
                warnings += "bad hex digit at byte $i, partial value kept"
                bad = true
                break
            }
        }
        pos = i
        if (bad) while (pos < data.size && data[pos] != '>'.code.toByte()) pos++
        if (pos < data.size && data[pos] == '>'.code.toByte()) pos++
        if (digits.length % 2 == 1) digits.append('0')
        val out = ByteArray(digits.length / 2)
        for (k in out.indices) out[k] = digits.substring(k * 2, k * 2 + 2).toInt(16).toByte()
        return CToken.CString(out)
    }

    /** Name object (§7.3.5), `#` escapes decoded. */
    private fun readName(): CToken.CName {
        val buf = java.io.ByteArrayOutputStream()
        var i = pos + 1
        while (i < data.size && !isContentDelim(data[i])) {
            val c = data[i]
            if (c == '#'.code.toByte() && i + 2 < data.size &&
                digitValue(data[i + 1]) != null && digitValue(data[i + 2]) != null
            ) {
                buf.write(digitValue(data[i + 1])!! * 16 + digitValue(data[i + 2])!!)
                i += 3
            } else {
                buf.write(c.toInt())
                i++
            }
        }
        pos = i
        return CToken.CName(buf.toString(Charsets.ISO_8859_1.name()))
    }

    private fun readNumberOrWord(): CToken {
        val start = pos
        while (pos < data.size && !isContentDelim(data[pos])) pos++
        val text = data.copyOfRange(start, pos).toString(Charsets.ISO_8859_1)
        if (NUMBER.matches(text)) {
            val isReal = text.contains('.') || text.contains('e') || text.contains('E')
            if (isReal) {
                val v = text.toDoubleOrNull()
                if (v == null) {
                    warnings += "bad real '$text'"
                    return CToken.CWord(text)
                }
                return CToken.CReal(v)
            }
            text.toLongOrNull()?.let { return CToken.CInt(it) }
            val v = text.toDoubleOrNull()
            if (v == null) return CToken.CWord(text)
            return CToken.CReal(v)
        }
        return CToken.CWord(text)
    }

    private fun digitValue(c: Byte): Int? {
        val v = c.toInt()
        return when {
            v in '0'.code..'9'.code -> v - '0'.code
            v in 'a'.code..'f'.code -> v - 'a'.code + 10
            v in 'A'.code..'F'.code -> v - 'A'.code + 10
            else -> null
        }
    }
}

/** Whitespace (§7.2.2): NUL, tab, LF, FF, CR, space. */
internal fun Byte.isContentWs(): Boolean {
    val c = toInt() and 0xFF
    return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
}

/** Delimiters (§7.2.2): whitespace plus ( ) < > [ ] / %. */
internal fun isContentDelim(b: Byte): Boolean {
    val c = b.toInt() and 0xFF
    return b.isContentWs() || c == '('.code || c == ')'.code || c == '<'.code ||
        c == '>'.code || c == '['.code || c == ']'.code || c == '/'.code || c == '%'.code
}
