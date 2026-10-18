package com.pdfpaper.convert.raster

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Minimal PNG encoder (RFC 2083) written by hand.
 *
 * The convert module may not use `javax.imageio` or any third-party codec, so
 * PNG (the one format DrawingML always accepts) is produced here: signature,
 * IHDR, one IDAT of zlib-compressed scanlines and IEND, each chunk CRC-checked.
 * Only 8-bit truecolour is emitted (colour types 2 and 6), which is enough for
 * both the fidelity page render and decoded PDF images.
 */
object PngWriter {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /** Encodes an opaque ARGB raster as an 8-bit RGB PNG. */
    fun encodeRgb(raster: Raster): ByteArray {
        val raw = ByteArray(raster.height * (1 + raster.width * 3))
        var out = 0
        var src = 0
        repeat(raster.height) {
            raw[out++] = 0 // filter: None
            repeat(raster.width) {
                val argb = raster.pixels[src++]
                raw[out++] = ((argb shr 16) and 0xFF).toByte()
                raw[out++] = ((argb shr 8) and 0xFF).toByte()
                raw[out++] = (argb and 0xFF).toByte()
            }
        }
        return encode(raster.width, raster.height, colorType = 2, raw = raw)
    }

    /** Encodes RGBA samples (4 bytes per pixel, row 0 first) as an RGBA PNG. */
    fun encodeRgba(width: Int, height: Int, rgba: ByteArray): ByteArray {
        require(rgba.size == width * height * 4) { "RGBA buffer size mismatch" }
        val raw = ByteArray(height * (1 + width * 4))
        var out = 0
        var src = 0
        repeat(height) {
            raw[out++] = 0
            System.arraycopy(rgba, src, raw, out, width * 4)
            out += width * 4
            src += width * 4
        }
        return encode(width, height, colorType = 6, raw = raw)
    }

    private fun encode(width: Int, height: Int, colorType: Int, raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size / 2 + 128)
        out.write(SIGNATURE)
        val header = ByteArrayOutputStream(13)
        writeInt(header, width)
        writeInt(header, height)
        header.write(8) // bit depth
        header.write(colorType)
        header.write(0) // compression: deflate
        header.write(0) // filter method: adaptive
        header.write(0) // interlace: none
        chunk(out, "IHDR", header.toByteArray())
        chunk(out, "IDAT", deflate(raw))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false) // zlib wrapper
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size / 3 + 64)
            val buffer = ByteArray(1 shl 16)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n > 0) out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
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
