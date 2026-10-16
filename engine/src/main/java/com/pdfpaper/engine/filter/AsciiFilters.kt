package com.pdfpaper.engine.filter

import com.pdfpaper.engine.model.PdfDict
import java.io.ByteArrayOutputStream

/** ASCIIHexDecode (§7.4.2, Table 6): hex pairs, whitespace ignored, `>` ends data. */
internal object AsciiHexFilter : PdfFilter {
    override val names: Set<String> = setOf("ASCIIHexDecode", "AHx")

    override fun decode(data: ByteArray, params: PdfDict?): DecodedBytes {
        val out = ByteArrayOutputStream()
        val warnings = ArrayList<String>()
        var high: Int? = null
        var ended = false
        for (b in data) {
            val c = b.toInt() and 0xFF
            when {
                c == '>'.code -> {
                    ended = true
                    break
                }
                c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 -> {
                    // Whitespace ignored.
                }
                c.isHex() -> {
                    val digit = hexValue(c)
                    if (high == null) {
                        high = digit
                    } else {
                        out.write((high shl 4) or digit)
                        high = null
                    }
                }
                else -> {
                    warnings += "invalid character 0x%02X, decoding stopped".format(c)
                    high = null
                    break
                }
            }
        }
        if (high != null) {
            // Odd trailing digit: pad with 0 per spec; only warn when data was cut off.
            out.write(high shl 4)
            if (!ended) warnings += "odd trailing digit, padded with 0"
        }
        if (!ended && data.isNotEmpty()) warnings += "missing EOD marker '>'"
        return DecodedBytes(out.toByteArray(), warnings)
    }

    private fun Int.isHex(): Boolean =
        this in '0'.code..'9'.code || this in 'a'.code..'f'.code || this in 'A'.code..'F'.code

    private fun hexValue(c: Int): Int = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        else -> c - 'A'.code + 10
    }
}

/** ASCII85Decode (§7.4.3): 5 chars -> 4 bytes, `z` = zeros, `~>` ends data. */
internal object Ascii85Filter : PdfFilter {
    override val names: Set<String> = setOf("ASCII85Decode", "A85")

    override fun decode(data: ByteArray, params: PdfDict?): DecodedBytes {
        val out = ByteArrayOutputStream()
        val warnings = ArrayList<String>()
        val group = IntArray(5)
        var size = 0
        var ended = false
        var i = 0
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            i++
            when {
                c == '~'.code -> {
                    if (i < data.size && data[i].toInt() and 0xFF == '>'.code) {
                        ended = true
                        i++
                    } else {
                        warnings += "stray '~', decoding stopped"
                    }
                    break
                }
                c == 'z'.code -> {
                    if (size != 0) {
                        warnings += "'z' inside a group, decoding stopped"
                        break
                    }
                    out.write(byteArrayOf(0, 0, 0, 0), 0, 4)
                }
                c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 -> {
                    // Whitespace ignored.
                }
                c in '!'.code..'u'.code -> {
                    group[size++] = c - '!'.code
                    if (size == 5) {
                        out.write(decodeGroup(group, 5), 0, 4)
                        size = 0
                    }
                }
                else -> {
                    warnings += "invalid character 0x%02X, decoding stopped".format(c)
                    break
                }
            }
        }
        if (size in 2..4) {
            // Final partial group: pad with 'u' (84), emit size-1 bytes.
            for (k in size until 5) group[k] = 84
            out.write(decodeGroup(group, 5), 0, size - 1)
            if (!ended) warnings += "truncated data, partial group padded"
        } else if (size == 1) {
            warnings += "single trailing character dropped"
        }
        if (!ended && data.isNotEmpty()) warnings += "missing EOD marker '~>'"
        return DecodedBytes(out.toByteArray(), warnings)
    }

    private fun decodeGroup(group: IntArray, count: Int): ByteArray {
        var value = 0L
        for (k in 0 until count) value = value * 85 + group[k]
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }
}
