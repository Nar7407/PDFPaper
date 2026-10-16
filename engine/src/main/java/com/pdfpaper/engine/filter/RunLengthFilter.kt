package com.pdfpaper.engine.filter

import com.pdfpaper.engine.model.PdfDict
import java.io.ByteArrayOutputStream

/** RunLengthDecode (§7.4.5): length byte + literal run (0-127) or repeat run (129-255), 128 ends data. */
internal object RunLengthFilter : PdfFilter {
    override val names: Set<String> = setOf("RunLengthDecode", "RL")

    override fun decode(data: ByteArray, params: PdfDict?): DecodedBytes {
        val out = ByteArrayOutputStream()
        val warnings = ArrayList<String>()
        var i = 0
        var ended = false
        while (i < data.size) {
            val length = data[i].toInt() and 0xFF
            i++
            when {
                length == 128 -> {
                    ended = true
                    break
                }
                length <= 127 -> {
                    val count = length + 1
                    val have = minOf(count, data.size - i)
                    out.write(data, i, have)
                    i += have
                    if (have < count) {
                        warnings += "truncated literal run, decoded prefix returned"
                        break
                    }
                }
                else -> {
                    if (i >= data.size) {
                        warnings += "truncated repeat run, decoded prefix returned"
                        break
                    }
                    val byte = data[i]
                    i++
                    repeat(257 - length) { out.write(byte.toInt()) }
                }
            }
            if (out.size() > MAX_OUTPUT) {
                warnings += "output exceeds ${MAX_OUTPUT} bytes, decoding stopped"
                break
            }
        }
        if (!ended && data.isNotEmpty()) warnings += "missing EOD marker (128)"
        return DecodedBytes(out.toByteArray(), warnings)
    }
}
