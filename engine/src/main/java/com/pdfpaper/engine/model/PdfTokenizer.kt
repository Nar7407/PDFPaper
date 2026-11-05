package com.pdfpaper.engine.model

import java.io.ByteArrayOutputStream

/** Thrown for malformed object syntax; always converted to [Result.failure] at API boundaries. */
class PdfParseException(message: String) : Exception(message)

/** Tokens (§7.2.2); every token carries its byte range for stream data slicing. */
internal sealed interface PdfToken {
    val start: Int
    val end: Int
}

internal data class TInt(val value: Long, override val start: Int, override val end: Int) : PdfToken
internal data class TReal(val value: Double, override val start: Int, override val end: Int) : PdfToken
internal data class TWord(val text: String, override val start: Int, override val end: Int) : PdfToken
internal data class TName(val name: String, override val start: Int, override val end: Int) : PdfToken
internal data class TString(val bytes: ByteArray, override val start: Int, override val end: Int) : PdfToken
internal data class THex(val bytes: ByteArray, override val start: Int, override val end: Int) : PdfToken
internal data class TArrayStart(override val start: Int, override val end: Int) : PdfToken
internal data class TArrayEnd(override val start: Int, override val end: Int) : PdfToken
internal data class TDictStart(override val start: Int, override val end: Int) : PdfToken
internal data class TDictEnd(override val start: Int, override val end: Int) : PdfToken

/**
 * Tokenizer for PDF object syntax (§7.2, §7.3). Lenient where real files vary
 * (comments, whitespace, empty names); strict garbage fails via
 * [PdfParseException], never with an uncontrolled crash.
 */
internal fun tokenize(input: ByteArray): List<PdfToken> {
    val out = ArrayList<PdfToken>()
    var i = 0
    while (i < input.size) {
        val b = input[i]
        when {
            b.isWs() || b == 0.toByte() -> i++
            b == '%'.code.toByte() -> {
                while (i < input.size && input[i] != '\n'.code.toByte()) i++
            }
            b == '('.code.toByte() -> {
                val (bytes, next) = readLiteral(input, i)
                out += TString(bytes, i, next)
                i = next
            }
            b == '<'.code.toByte() -> {
                if (i + 1 < input.size && input[i + 1] == '<'.code.toByte()) {
                    out += TDictStart(i, i + 2)
                    i += 2
                } else {
                    val hex = tryReadHex(input, i)
                    if (hex == null) {
                        // Lone '<' (e.g. inside binary stream data): keep as a word
                        // so data tokens can be skipped by position instead of
                        // failing the whole object on non-hex bytes.
                        out += TWord("<", i, i + 1)
                        i++
                    } else {
                        out += THex(hex.first, i, hex.second)
                        i = hex.second
                    }
                }
            }
            b == '>'.code.toByte() -> {
                if (i + 1 < input.size && input[i + 1] == '>'.code.toByte()) {
                    out += TDictEnd(i, i + 2)
                    i += 2
                } else {
                    // Lone '>' (e.g. inside binary stream data): keep as a word so
                    // data tokens can be skipped by position; the parser still
                    // rejects it where an object is expected.
                    out += TWord(">", i, i + 1)
                    i++
                }
            }
            b == '['.code.toByte() -> {
                out += TArrayStart(i, i + 1)
                i++
            }
            b == ']'.code.toByte() -> {
                out += TArrayEnd(i, i + 1)
                i++
            }
            b == '/'.code.toByte() -> {
                val (name, next) = readName(input, i)
                out += TName(name, i, next)
                i = next
            }
            b == ')'.code.toByte() -> {
                // Stray ')' (e.g. inside binary stream data): same leniency as '>'.
                out += TWord(")", i, i + 1)
                i++
            }
            else -> {
                var j = i
                while (j < input.size && !isDelimByte(input[j])) j++
                if (j == i) throw PdfParseException("Bad byte 0x%02X at $i".format(b))
                val text = input.copyOfRange(i, j).toString(Charsets.ISO_8859_1)
                out += classifyNumberOrWord(text, i, j)
                i = j
            }
        }
    }
    return out
}

private fun classifyNumberOrWord(text: String, start: Int, end: Int): PdfToken =
    when (val number = scanNumber(text)) {
        null -> TWord(text, start, end)
        is NumberLong -> TInt(number.value, start, end)
        is NumberDouble -> TReal(number.value, start, end)
    }

/** Literal string (§7.3.4.2): nesting, escapes, octal, line continuation; unterminated returns what was read. */
private fun readLiteral(input: ByteArray, start: Int): Pair<ByteArray, Int> {
    val buf = ByteArrayOutputStream()
    var i = start + 1
    var depth = 1
    while (i < input.size && depth > 0) {
        val c = input[i]
        when (c) {
            '\\'.code.toByte() -> {
                i++
                if (i >= input.size) break
                when (val e = input[i]) {
                    'n'.code.toByte() -> buf.write('\n'.code)
                    'r'.code.toByte() -> buf.write('\r'.code)
                    't'.code.toByte() -> buf.write('\t'.code)
                    'b'.code.toByte() -> buf.write('\b'.code)
                    'f'.code.toByte() -> buf.write(12)
                    '('.code.toByte() -> buf.write('('.code)
                    ')'.code.toByte() -> buf.write(')'.code)
                    '\\'.code.toByte() -> buf.write('\\'.code)
                    '\r'.code.toByte() -> {
                        if (i + 1 < input.size && input[i + 1] == '\n'.code.toByte()) i++
                    }
                    '\n'.code.toByte() -> { /* line continuation: emit nothing */ }
                    else -> {
                        if (e in '0'.code.toByte()..'7'.code.toByte()) {
                            var value = (e - '0'.code.toByte())
                            repeat(2) {
                                val n = i + 1
                                if (n < input.size && input[n] in '0'.code.toByte()..'7'.code.toByte()) {
                                    i = n
                                    value = value * 8 + (input[i] - '0'.code.toByte())
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
            '('.code.toByte() -> {
                depth++
                buf.write(c.toInt())
                i++
            }
            ')'.code.toByte() -> {
                depth--
                if (depth > 0) buf.write(c.toInt())
                i++
            }
            '\r'.code.toByte() -> {
                buf.write('\n'.code)
                i++
                if (i < input.size && input[i] == '\n'.code.toByte()) i++
            }
            else -> {
                buf.write(c.toInt())
                i++
            }
        }
    }
    return buf.toByteArray() to i
}

/**
 * Hexadecimal string (§7.3.4.3): whitespace ignored, odd length padded with 0.
 * Returns null when the bytes cannot form a hex string (no `>` before the end,
 * or a non-hex byte), so the caller can fall back to treating `<` as data — a
 * ciphertext or image stream may legitimately contain stray `<` bytes.
 */
private fun tryReadHex(input: ByteArray, start: Int): Pair<ByteArray, Int>? {
    val digits = StringBuilder()
    var i = start + 1
    while (i < input.size && input[i] != '>'.code.toByte()) {
        val c = input[i]
        if (c.isWs()) {
            i++
        } else if (c.isHexDigitByte()) {
            digits.append(c.toInt().toChar())
            i++
        } else {
            return null
        }
    }
    if (i >= input.size) return null
    i++ // '>'
    if (digits.length % 2 == 1) digits.append('0')
    val out = ByteArray(digits.length / 2)
    for (k in out.indices) {
        out[k] = digits.substring(k * 2, k * 2 + 2).toInt(16).toByte()
    }
    return out to i
}

/** Name object (§7.3.5): `#` escapes decoded, malformed `#` kept literally. */
private fun readName(input: ByteArray, start: Int): Pair<String, Int> {
    val buf = ByteArrayOutputStream()
    var i = start + 1
    while (i < input.size && !isDelimByte(input[i])) {
        if (input[i] == '#'.code.toByte() &&
            i + 2 < input.size &&
            input[i + 1].isHexDigitByte() &&
            input[i + 2].isHexDigitByte()
        ) {
            val hex = input.copyOfRange(i + 1, i + 3).toString(Charsets.US_ASCII)
            buf.write(hex.toInt(16))
            i += 3
        } else {
            buf.write(input[i].toInt())
            i++
        }
    }
    return buf.toString(Charsets.ISO_8859_1.name()) to i
}

private fun Byte.isWs(): Boolean {
    val c = toInt() and 0xFF
    return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
}

private fun Byte.isHexDigitByte(): Boolean {
    val c = toInt()
    return c in '0'.code..'9'.code || c in 'a'.code..'f'.code || c in 'A'.code..'F'.code
}

/** Delimiters (§7.2.2): whitespace plus ( ) < > [ ] / %. */
private fun isDelimByte(b: Byte): Boolean {
    val c = b.toInt() and 0xFF
    return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 ||
        c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
        c == '['.code || c == ']'.code || c == '/'.code || c == '%'.code
}
