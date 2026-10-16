package com.pdfpaper.engine.filter

import com.pdfpaper.engine.model.PdfDict
import java.io.ByteArrayOutputStream

/**
 * LZWDecode (§7.4.4.2): codes packed MSB-first, starting at 9 bits up to 12,
 * codes 256 (clear) and 257 (EOI), table holds 0-4095.
 *
 * [earlyChange] (DecodeParms /EarlyChange, default 1): with 1 the code length
 * grows one code earlier than with 0.
 */
internal object LzwFilter : PdfFilter {
    override val names: Set<String> = setOf("LZWDecode", "LZW")

    override fun decode(data: ByteArray, params: PdfDict?): DecodedBytes {
        val earlyChange = intParam(params, "EarlyChange", 1).coerceIn(0, 1)
        val out = ByteArrayOutputStream()
        val warnings = ArrayList<String>()
        if (data.isEmpty()) {
            warnings += "empty input"
            return DecodedBytes(ByteArray(0), warnings)
        }
        val table = ArrayList<ByteArray>(4096)
        for (b in 0..255) table += byteArrayOf(b.toByte())
        // Codes are table indices: reserve 256 (clear) and 257 (EOI), which are
        // handled explicitly and never looked up.
        table += byteArrayOf()
        table += byteArrayOf()
        var codeLength = 9
        var nextCode = 258
        var bitPos = 0
        var previous: ByteArray? = null

        fun reset() {
            table.subList(258, table.size).clear()
            codeLength = 9
            nextCode = 258
            previous = null
        }

        while (true) {
            val code = readCode(data, bitPos, codeLength)
            if (code == null) {
                warnings += "truncated data, decoded prefix returned"
                break
            }
            bitPos += codeLength
            when (code) {
                257 -> break // EOI: trailing bits ignored.
                256 -> {
                    reset()
                    continue
                }
            }
            val entry: ByteArray = when {
                code < table.size -> table[code]
                code == table.size && previous != null ->
                    previous!! + previous!![0]
                else -> {
                    warnings += "invalid code $code, decoded prefix returned"
                    break
                }
            }
            out.write(entry, 0, entry.size)
            if (out.size() > MAX_OUTPUT) {
                warnings += "output exceeds ${MAX_OUTPUT} bytes, decoding stopped"
                break
            }
            val prev = previous
            if (prev != null) {
                if (nextCode >= 4096) {
                    warnings += "string table full, decoded prefix returned"
                    break
                }
                table += prev + entry[0]
                nextCode++
                if (codeLength < 12 && nextCode + earlyChange == (1 shl codeLength)) {
                    codeLength++
                }
            }
            previous = entry
        }
        return DecodedBytes(out.toByteArray(), warnings)
    }

    private fun readCode(data: ByteArray, bitPos: Int, codeLength: Int): Int? {
        if (bitPos + codeLength > data.size * 8) return null
        var code = 0
        for (k in 0 until codeLength) {
            val bitIndex = bitPos + k
            val byte = data[bitIndex / 8].toInt() and 0xFF
            code = (code shl 1) or ((byte shr (7 - (bitIndex % 8))) and 1)
        }
        return code
    }

    private operator fun ByteArray.plus(byte: Byte): ByteArray {
        val out = ByteArray(size + 1)
        copyInto(out)
        out[size] = byte
        return out
    }
}
