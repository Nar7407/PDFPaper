package com.pdfpaper.engine.export

import com.pdfpaper.engine.write.Deflate
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Minimal PNG encoder (RFC 2083) written by hand.
 *
 * The engine may not use `javax.imageio` or `android.graphics`, so the export
 * formats that need a codec are produced here: signature, IHDR, IDAT with a
 * zlib stream (`java.util.zip.Deflater`, §7.4.4 uses the same wrapper) and
 * IEND, each chunk CRC-checked.
 *
 * [encode] writes the smallest lossless form of a 32-bit RGBA buffer: an
 * 8-bit grey or grey+alpha file when every pixel is neutral, otherwise RGB or
 * RGBA. [encodeStencil] writes the 1-bit grey + `tRNS` form an /ImageMask
 * needs (bit set = opaque black, bit clear = transparent), and
 * [encodeIndexed] writes an 8-bit palette image with `PLTE` and `tRNS`.
 */
object PngWriter {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /**
     * Encodes [rgba] (`width * height * 4` bytes, row 0 first) choosing the
     * colour type from the samples: grey/grey+alpha for neutral pixels, RGB or
     * RGBA otherwise. Invalid buffers return an empty array instead of
     * throwing, so a caller can report "cannot encode" rather than crash.
     */
    fun encode(width: Int, height: Int, rgba: ByteArray): ByteArray {
        if (width <= 0 || height <= 0) return ByteArray(0)
        if (rgba.size < width * height * 4) return ByteArray(0)
        var opaque = true
        var neutral = true
        var i = 0
        while (i < width * height * 4) {
            if (rgba[i + 3].toInt() and 0xFF != 255) opaque = false
            if (rgba[i] != rgba[i + 1] || rgba[i] != rgba[i + 2]) neutral = false
            if (!opaque && !neutral) return encodeRgba(width, height, rgba)
            i += 4
        }
        return when {
            neutral && opaque -> encodeGray(width, height, grayOf(rgba, width, height, alpha = false), alpha = false)
            neutral -> encodeGray(width, height, grayOf(rgba, width, height, alpha = true), alpha = true)
            opaque -> encodeRgb(width, height, rgba)
            else -> encodeRgba(width, height, rgba)
        }
    }

    /** Encodes [rgba] as an 8-bit RGBA PNG (colour type 6). */
    fun encodeRgba(width: Int, height: Int, rgba: ByteArray): ByteArray {
        if (width <= 0 || height <= 0 || rgba.size < width * height * 4) return ByteArray(0)
        val raw = ByteArray(height * (1 + width * 4))
        var out = 0
        var src = 0
        repeat(height) {
            raw[out++] = 0 // filter: None
            System.arraycopy(rgba, src, raw, out, width * 4)
            out += width * 4
            src += width * 4
        }
        return encodeChunks(width, height, bitDepth = 8, colorType = 6, raw = raw)
    }

    /** Encodes [rgba] as an 8-bit RGB PNG (colour type 2), dropping alpha. */
    fun encodeRgb(width: Int, height: Int, rgba: ByteArray): ByteArray {
        if (width <= 0 || height <= 0 || rgba.size < width * height * 4) return ByteArray(0)
        val raw = ByteArray(height * (1 + width * 3))
        var out = 0
        var src = 0
        repeat(height) {
            raw[out++] = 0
            repeat(width) {
                raw[out++] = rgba[src]
                raw[out++] = rgba[src + 1]
                raw[out++] = rgba[src + 2]
                src += 4
            }
        }
        return encodeChunks(width, height, bitDepth = 8, colorType = 2, raw = raw)
    }

    /** Encodes an 8-bit grey PNG (colour type 0), or grey+alpha (type 4). */
    fun encodeGray(width: Int, height: Int, gray: ByteArray, alpha: Boolean = false): ByteArray {
        val bands = if (alpha) 2 else 1
        if (width <= 0 || height <= 0 || gray.size < width * height * bands) return ByteArray(0)
        val raw = ByteArray(height * (1 + width * bands))
        var out = 0
        var src = 0
        repeat(height) {
            raw[out++] = 0
            System.arraycopy(gray, src, raw, out, width * bands)
            out += width * bands
            src += width * bands
        }
        return encodeChunks(width, height, bitDepth = 8, colorType = if (alpha) 4 else 0, raw = raw)
    }

    /**
     * Encodes a stencil mask as a 1-bit grey PNG with a `tRNS` entry for 0:
     * a set bit is opaque black, a clear bit is fully transparent (§8.9.6.2
     * stencils paint with the current fill colour, so black is the neutral
     * export).
     */
    fun encodeStencil(width: Int, height: Int, alpha: ByteArray): ByteArray {
        if (width <= 0 || height <= 0 || alpha.size < width * height) return ByteArray(0)
        val rowBytes = (width + 7) / 8
        val raw = ByteArray(height * (1 + rowBytes))
        var out = 0
        for (y in 0 until height) {
            raw[out++] = 0
            for (x in 0 until width) {
                if (alpha[y * width + x].toInt() and 0xFF < 128) continue
                val index = out + x / 8
                raw[index] = (raw[index].toInt() or (0x80 ushr (x % 8))).toByte()
            }
            out += rowBytes
        }
        // tRNS for colour type 0 is one 16-bit grey sample (the transparent one).
        return encodeChunks(
            width, height, bitDepth = 1, colorType = 0, raw = raw,
            extraChunks = listOf("tRNS" to byteArrayOf(0, 0))
        )
    }

    /**
     * Encodes an 8-bit indexed PNG (colour type 3) with the given palette and
     * optional per-entry alpha. Used where the source is already a palette.
     */
    fun encodeIndexed(
        width: Int,
        height: Int,
        indices: ByteArray,
        palette: ByteArray,
        alphaTable: ByteArray? = null
    ): ByteArray {
        if (width <= 0 || height <= 0 || indices.size < width * height) return ByteArray(0)
        if (palette.isEmpty() || palette.size % 3 != 0) return ByteArray(0)
        val entries = palette.size / 3
        if (entries > 256) return ByteArray(0)
        val raw = ByteArray(height * (1 + width))
        var out = 0
        var src = 0
        repeat(height) {
            raw[out++] = 0
            System.arraycopy(indices, src, raw, out, width)
            out += width
            src += width
        }
        val extra = ArrayList<Pair<String, ByteArray>>()
        extra.add("PLTE" to palette)
        if (alphaTable != null && alphaTable.isNotEmpty()) {
            extra.add("tRNS" to alphaTable.copyOf(minOf(alphaTable.size, entries)))
        }
        return encodeChunks(width, height, bitDepth = 8, colorType = 3, raw = raw, extraChunks = extra)
    }

    private fun grayOf(rgba: ByteArray, width: Int, height: Int, alpha: Boolean): ByteArray {
        val bands = if (alpha) 2 else 1
        val out = ByteArray(width * height * bands)
        var src = 0
        var dst = 0
        repeat(width * height) {
            out[dst++] = rgba[src]
            if (alpha) out[dst++] = rgba[src + 3]
            src += 4
        }
        return out
    }

    private fun encodeChunks(
        width: Int,
        height: Int,
        bitDepth: Int,
        colorType: Int,
        raw: ByteArray,
        extraChunks: List<Pair<String, ByteArray>> = emptyList()
    ): ByteArray {
        val out = ByteArrayOutputStream(raw.size / 3 + 128)
        out.write(SIGNATURE)
        val header = ByteArrayOutputStream(13)
        writeInt(header, width)
        writeInt(header, height)
        header.write(bitDepth)
        header.write(colorType)
        header.write(0) // compression: deflate
        header.write(0) // filter method: adaptive
        header.write(0) // interlace: none
        chunk(out, "IHDR", header.toByteArray())
        for ((type, data) in extraChunks) chunk(out, type, data)
        chunk(out, "IDAT", Deflate.encode(raw, level = 9))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        writeInt(out, data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeInt(out, crc.value.toInt())
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
