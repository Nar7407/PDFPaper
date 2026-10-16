package com.pdfpaper.engine.filter

import com.pdfpaper.engine.model.PdfDict
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** FlateDecode (§7.4.4): zlib/deflate via [Inflater], then predictor reversal (§7.4.4.3-4). */
internal object FlateFilter : PdfFilter {
    override val names: Set<String> = setOf("FlateDecode", "Fl")

    override fun decode(data: ByteArray, params: PdfDict?): DecodedBytes {
        val warnings = ArrayList<String>()
        val raw = inflate(data, warnings)
        return DecodedBytes(applyPredictor(raw, params, warnings), warnings)
    }

    private fun inflate(data: ByteArray, warnings: MutableList<String>): ByteArray {
        if (data.isEmpty()) {
            warnings += "empty input"
            return ByteArray(0)
        }
        val inflater = Inflater()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            inflater.setInput(data)
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    warnings += "truncated input, decoded prefix returned"
                    break
                }
                val count = try {
                    inflater.inflate(buf)
                } catch (e: DataFormatException) {
                    warnings += "corrupt data (${e.message}), decoded prefix returned"
                    break
                }
                if (count == 0) {
                    if (inflater.finished() || inflater.needsInput()) break
                    warnings += "decoder stalled, decoded prefix returned"
                    break
                }
                out.write(buf, 0, count)
                if (out.size() > MAX_OUTPUT) {
                    warnings += "output exceeds ${MAX_OUTPUT} bytes, decoding stopped"
                    break
                }
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun applyPredictor(
        bytes: ByteArray,
        params: PdfDict?,
        warnings: MutableList<String>
    ): ByteArray {
        val predictor = intParam(params, "Predictor", 1)
        if (params == null || predictor == 1) return bytes
        val columns = intParam(params, "Columns", 1)
        val colors = intParam(params, "Colors", 1)
        val bpc = intParam(params, "BitsPerComponent", 8)
        if (bpc != 8) {
            warnings += "unsupported BitsPerComponent $bpc, predictors skipped"
            return bytes
        }
        if (columns <= 0 || colors <= 0) {
            warnings += "invalid Columns/Colors, predictors skipped"
            return bytes
        }
        val rowLength = colors * columns
        return when (predictor) {
            2 -> tiffUnpredict(bytes, rowLength, colors, warnings)
            in 10..15 -> pngUnpredict(bytes, rowLength, colors, warnings)
            else -> {
                warnings += "unsupported Predictor $predictor"
                bytes
            }
        }
    }

    /** TIFF horizontal differencing (§7.4.4.3): each byte adds the same-position byte `colors` back. */
    private fun tiffUnpredict(
        bytes: ByteArray,
        rowLength: Int,
        colors: Int,
        warnings: MutableList<String>
    ): ByteArray {
        if (rowLength <= 0) return bytes
        val out = bytes.copyOf()
        for (i in out.indices) {
            if (i % rowLength >= colors) {
                out[i] = ((out[i].toInt() and 0xFF) + (out[i - colors].toInt() and 0xFF)).toByte()
            }
        }
        if (bytes.size % rowLength != 0) warnings += "data is not a whole number of rows"
        return out
    }

    /** PNG predictors (§7.4.4.4): every row starts with a filter-type byte (0-4). */
    private fun pngUnpredict(
        bytes: ByteArray,
        rowLength: Int,
        colors: Int,
        warnings: MutableList<String>
    ): ByteArray {
        val stride = rowLength + 1
        if (stride <= 1) return bytes
        val rows = bytes.size / stride
        if (rows == 0) {
            warnings += "no complete rows"
            return ByteArray(0)
        }
        if (bytes.size % stride != 0) warnings += "trailing bytes after last full row ignored"
        val out = ByteArrayOutputStream(rows * rowLength)
        var previous = ByteArray(rowLength)
        var offset = 0
        repeat(rows) {
            val filterType = bytes[offset].toInt() and 0xFF
            val raw = bytes.copyOfRange(offset + 1, offset + stride)
            offset += stride
            val row = when (filterType) {
                0 -> raw
                1 -> unSub(raw, colors)
                2 -> unUp(raw, previous)
                3 -> unAverage(raw, previous, colors)
                4 -> unPaeth(raw, previous, colors)
                else -> {
                    warnings += "unknown PNG filter type $filterType, row copied raw"
                    raw
                }
            }
            out.write(row, 0, row.size)
            previous = row
        }
        return out.toByteArray()
    }

    private fun unSub(raw: ByteArray, colors: Int): ByteArray {
        val out = ByteArray(raw.size)
        for (i in raw.indices) {
            val left = if (i >= colors) out[i - colors].toInt() and 0xFF else 0
            out[i] = ((raw[i].toInt() and 0xFF) + left).toByte()
        }
        return out
    }

    private fun unUp(raw: ByteArray, previous: ByteArray): ByteArray {
        val out = ByteArray(raw.size)
        for (i in raw.indices) {
            out[i] = ((raw[i].toInt() and 0xFF) + (previous[i].toInt() and 0xFF)).toByte()
        }
        return out
    }

    private fun unAverage(raw: ByteArray, previous: ByteArray, colors: Int): ByteArray {
        val out = ByteArray(raw.size)
        for (i in raw.indices) {
            val left = if (i >= colors) out[i - colors].toInt() and 0xFF else 0
            val up = previous[i].toInt() and 0xFF
            out[i] = ((raw[i].toInt() and 0xFF) + (left + up) / 2).toByte()
        }
        return out
    }

    private fun unPaeth(raw: ByteArray, previous: ByteArray, colors: Int): ByteArray {
        val out = ByteArray(raw.size)
        for (i in raw.indices) {
            val left = if (i >= colors) out[i - colors].toInt() and 0xFF else 0
            val up = previous[i].toInt() and 0xFF
            val upLeft = if (i >= colors) previous[i - colors].toInt() and 0xFF else 0
            out[i] = ((raw[i].toInt() and 0xFF) + paeth(left, up, upLeft)).toByte()
        }
        return out
    }

    private fun paeth(left: Int, up: Int, upLeft: Int): Int {
        val p = left + up - upLeft
        val pa = kotlin.math.abs(p - left)
        val pb = kotlin.math.abs(p - up)
        val pc = kotlin.math.abs(p - upLeft)
        return if (pa <= pb && pa <= pc) left else if (pb <= pc) up else upLeft
    }
}
