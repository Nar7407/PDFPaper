package com.pdfpaper.convert.raster

import java.util.zip.CRC32

/** A decoded PNG: 8-bit RGBA, row-major, first row first. */
internal class PngImage(val width: Int, val height: Int, val rgba: ByteArray)

/** Outcome of [PngReader.read]: pixels, or the reason they could not be had. */
internal sealed interface PngResult {
    class Ok(val image: PngImage) : PngResult
    class Failed(val reason: String) : PngResult
}

/**
 * Minimal PNG reader (RFC 2083, ISO 15948) written by hand.
 *
 * A .docx stores its pictures as complete PNG or JPEG files; to place a PNG in
 * a PDF the samples have to come out of the image, and the convert module may
 * not use `javax.imageio` or `java.awt`. The subset implemented here is what
 * documents actually contain: every colour type, 1/2/4/8/16 bits per sample,
 * filters 0–4, tRNS transparency; Adam7 interlacing and corrupt CRCs are
 * reported instead of guessed.
 */
internal object PngReader {

    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private const val MAX_PIXELS = 40_000_000L

    fun read(bytes: ByteArray): PngResult {
        if (bytes.size < 8 + 25 || !bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) {
            return PngResult.Failed("not a PNG file")
        }
        var pos = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        var palette: ByteArray? = null
        var transparency: ByteArray? = null
        var idat: java.io.ByteArrayOutputStream? = null
        while (pos + 8 <= bytes.size) {
            val length = readInt(bytes, pos) ?: return PngResult.Failed("truncated chunk header")
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            if (length < 0 || dataStart + length + 4 > bytes.size) {
                return PngResult.Failed("truncated $type chunk")
            }
            val data = bytes.copyOfRange(dataStart, dataStart + length)
            val storedCrc = readInt(bytes, dataStart + length) ?: return PngResult.Failed("truncated CRC")
            val crc = CRC32()
            crc.update(bytes, pos + 4, 4 + length)
            if (crc.value.toInt() != storedCrc) return PngResult.Failed("CRC mismatch in $type chunk")

            when (type) {
                "IHDR" -> {
                    if (data.size < 13) return PngResult.Failed("short IHDR")
                    width = readInt(data, 0) ?: return PngResult.Failed("bad IHDR width")
                    height = readInt(data, 4) ?: return PngResult.Failed("bad IHDR height")
                    bitDepth = data[8].toInt() and 0xFF
                    colorType = data[9].toInt() and 0xFF
                    val compression = data[10].toInt() and 0xFF
                    val filter = data[11].toInt() and 0xFF
                    val interlace = data[12].toInt() and 0xFF
                    if (compression != 0 || filter != 0) return PngResult.Failed("unsupported compression/filter method")
                    if (interlace != 0) return PngResult.Failed("Adam7 interlacing is not supported")
                    if (width <= 0 || height <= 0) return PngResult.Failed("empty image")
                    if (width.toLong() * height > MAX_PIXELS) return PngResult.Failed("image too large")
                    val channels = channelsOf(colorType) ?: return PngResult.Failed("colour type $colorType")
                    if (!validDepth(colorType, bitDepth)) {
                        return PngResult.Failed("$bitDepth bits per sample for colour type $colorType")
                    }
                    if (channels > 0 && (bitDepth < 8) && colorType != 0 && colorType != 3) {
                        return PngResult.Failed("$bitDepth bits per sample for colour type $colorType")
                    }
                }
                "PLTE" -> palette = data
                "tRNS" -> transparency = data
                "IDAT" -> {
                    val out = idat ?: java.io.ByteArrayOutputStream(bytes.size / 2 + 64).also { idat = it }
                    out.write(data)
                }
                "IEND" -> break
            }
            pos = dataStart + length + 4
        }
        val compressed = idat ?: return PngResult.Failed("no IDAT data")
        if (colorType == 3 && palette == null) return PngResult.Failed("indexed image without PLTE")

        val channels = channelsOf(colorType)!!
        val bitsPerPixel = channels * bitDepth
        val rowBytes = (width.toLong() * bitsPerPixel + 7) / 8
        val expected = height.toLong() * (rowBytes + 1)
        if (expected > Int.MAX_VALUE) return PngResult.Failed("image too large")
        val raw = Zlib.decode(compressed.toByteArray(), expected.toInt())
            ?: return PngResult.Failed("corrupt IDAT stream")
        if (raw.size < expected) return PngResult.Failed("truncated image data")

        val rgba = ByteArray(width * height * 4)
        unfilter(raw, width, height, rowBytes.toInt(), (bitsPerPixel + 7) / 8, rgba, bitDepth, colorType, palette, transparency)
        return PngResult.Ok(PngImage(width, height, rgba))
    }

    private fun channelsOf(colorType: Int): Int? = when (colorType) {
        0 -> 1
        2 -> 3
        3 -> 1
        4 -> 2
        6 -> 4
        else -> null
    }

    private fun validDepth(colorType: Int, depth: Int): Boolean = when (colorType) {
        0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16
        3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8
        2, 4, 6 -> depth == 8 || depth == 16
        else -> false
    }

    /** Reconstructs the scanlines in place and expands every pixel to RGBA. */
    private fun unfilter(
        raw: ByteArray,
        width: Int,
        height: Int,
        rowBytes: Int,
        bpp: Int,
        rgba: ByteArray,
        bitDepth: Int,
        colorType: Int,
        palette: ByteArray?,
        transparency: ByteArray?
    ) {
        val previous = ByteArray(rowBytes)
        val current = ByteArray(rowBytes)
        var offset = 0
        for (y in 0 until height) {
            val filter = raw[offset].toInt() and 0xFF
            offset++
            System.arraycopy(raw, offset, current, 0, rowBytes)
            offset += rowBytes
            for (i in 0 until rowBytes) {
                val a = if (i >= bpp) current[i - bpp].toInt() and 0xFF else 0
                val b = previous[i].toInt() and 0xFF
                val c = if (i >= bpp) previous[i - bpp].toInt() and 0xFF else 0
                val x = current[i].toInt() and 0xFF
                current[i] = when (filter) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + (a + b) / 2
                    4 -> x + paeth(a, b, c)
                    else -> x
                }.toByte()
            }
            expandRow(current, y, width, bitDepth, colorType, palette, transparency, rgba)
            System.arraycopy(current, 0, previous, 0, rowBytes)
        }
    }

    private fun expandRow(
        row: ByteArray,
        y: Int,
        width: Int,
        bitDepth: Int,
        colorType: Int,
        palette: ByteArray?,
        transparency: ByteArray?,
        rgba: ByteArray
    ) {
        var out = y * width * 4
        fun put(r: Int, g: Int, b: Int, a: Int) {
            rgba[out++] = r.toByte()
            rgba[out++] = g.toByte()
            rgba[out++] = b.toByte()
            rgba[out++] = a.toByte()
        }
        /** 8-bit value of sample [index] of the row (16-bit samples keep the high byte). */
        fun sample8(index: Int): Int =
            if (bitDepth == 16) row[index * 2].toInt() and 0xFF else row[index].toInt() and 0xFF
        /** 16-bit value of sample [index], for colour-key comparison. */
        fun sample16(index: Int): Int =
            if (bitDepth == 16) readInt16(row, index * 2) else sample8(index) * 257

        when (colorType) {
            0 -> {
                val max = (1 shl bitDepth) - 1
                val key = transparency?.let { if (it.size >= 2) readInt16(it, 0) else -1 } ?: -1
                for (x in 0 until width) {
                    val value = when {
                        bitDepth == 16 -> row[x * 2].toInt() and 0xFF
                        bitDepth == 8 -> row[x].toInt() and 0xFF
                        else -> sampleAt(row, x, bitDepth) * 255 / max
                    }
                    val grey16 = sample16(x)
                    put(value, value, value, if (key >= 0 && grey16 == key) 0 else 255)
                }
            }
            2 -> {
                val key = transparency?.takeIf { it.size >= 6 }?.let {
                    intArrayOf(readInt16(it, 0), readInt16(it, 2), readInt16(it, 4))
                }
                for (x in 0 until width) {
                    val r = sample8(x * 3)
                    val g = sample8(x * 3 + 1)
                    val b = sample8(x * 3 + 2)
                    val opaque = if (key == null) 255 else {
                        val matches = sample16(x * 3) == key[0] &&
                            sample16(x * 3 + 1) == key[1] &&
                            sample16(x * 3 + 2) == key[2]
                        if (matches) 0 else 255
                    }
                    put(r, g, b, opaque)
                }
            }
            3 -> {
                val entries = (palette?.size ?: 0) / 3
                for (x in 0 until width) {
                    val index = sampleAt(row, x, bitDepth).coerceIn(0, 255)
                    val r = if (index < entries) palette!![index * 3].toInt() and 0xFF else 0
                    val g = if (index < entries) palette!![index * 3 + 1].toInt() and 0xFF else 0
                    val b = if (index < entries) palette!![index * 3 + 2].toInt() and 0xFF else 0
                    val a = if (transparency != null && index < transparency.size) {
                        transparency[index].toInt() and 0xFF
                    } else {
                        255
                    }
                    put(r, g, b, a)
                }
            }
            4 -> {
                for (x in 0 until width) {
                    val grey = sample8(x * 2)
                    put(grey, grey, grey, sample8(x * 2 + 1))
                }
            }
            6 -> for (x in 0 until width) {
                put(sample8(x * 4), sample8(x * 4 + 1), sample8(x * 4 + 2), sample8(x * 4 + 3))
            }
        }
    }

    /** Sample [x] of a row for bit depths 1/2/4/8/16. */
    private fun sampleAt(row: ByteArray, x: Int, bitDepth: Int): Int = when (bitDepth) {
        8 -> row[x].toInt() and 0xFF
        16 -> readInt16(row, x * 2)
        else -> {
            val perByte = 8 / bitDepth
            val byte = row[x / perByte].toInt() and 0xFF
            val shift = 8 - bitDepth * (x % perByte + 1)
            (byte shr shift) and ((1 shl bitDepth) - 1)
        }
    }

    private fun readInt16(data: ByteArray, index: Int): Int =
        ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)

    private fun readInt(data: ByteArray, index: Int): Int? {
        if (index + 3 >= data.size) return null
        return ((data[index].toInt() and 0xFF) shl 24) or
            ((data[index + 1].toInt() and 0xFF) shl 16) or
            ((data[index + 2].toInt() and 0xFF) shl 8) or
            (data[index + 3].toInt() and 0xFF)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }
}
