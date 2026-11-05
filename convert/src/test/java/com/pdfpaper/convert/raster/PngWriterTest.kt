package com.pdfpaper.convert.raster

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PngWriterTest {

    @Test
    fun `rgb raster encodes with the right header and scanlines`() {
        val raster = Raster(2, 1)
        raster.pixels[0] = 0xFFFF0000.toInt() // red
        raster.pixels[1] = 0xFF0000FF.toInt() // blue

        val png = PngWriter.encodeRgb(raster)
        assertContentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            png.copyOfRange(0, 8)
        )
        assertEquals("IHDR", String(png, 12, 4, Charsets.US_ASCII))
        assertEquals(2, readInt(png, 16))
        assertEquals(1, readInt(png, 20))
        assertEquals(8, png[24].toInt()) // bit depth
        assertEquals(2, png[25].toInt()) // colour type: truecolour
        assertEquals("IEND", String(png, png.size - 8, 4, Charsets.US_ASCII))

        // The IDAT payload is a zlib stream: filter byte + two RGB triples.
        val idat = extractIdat(png)
        val raw = inflate(idat)
        assertContentEquals(
            byteArrayOf(0, 0xFF.toByte(), 0, 0, 0, 0, 0xFF.toByte()),
            raw
        )
    }

    @Test
    fun `rgba raster keeps the alpha channel`() {
        val rgba = byteArrayOf(255.toByte(), 0, 0, 128.toByte())
        val png = PngWriter.encodeRgba(1, 1, rgba)
        assertEquals(6, png[25].toInt()) // colour type: truecolour + alpha
        assertContentEquals(rgba, inflate(extractIdat(png)).copyOfRange(1, 5))
    }

    @Test
    fun `chunk crc is valid`() {
        val png = PngWriter.encodeRgb(Raster(1, 1))
        // Verify every chunk's CRC over type+data.
        var offset = 8
        while (offset < png.size) {
            val length = readInt(png, offset)
            val type = png.copyOfRange(offset + 4, offset + 8)
            val data = png.copyOfRange(offset + 8, offset + 8 + length)
            val stored = readInt(png, offset + 8 + length)
            val crc = java.util.zip.CRC32()
            crc.update(type)
            crc.update(data)
            assertEquals(crc.value.toInt(), stored, "CRC mismatch in ${String(type)}")
            offset += 12 + length
        }
        assertTrue(offset == png.size)
    }

    private fun extractIdat(png: ByteArray): ByteArray {
        var offset = 8
        while (offset < png.size) {
            val length = readInt(png, offset)
            val type = String(png, offset + 4, 4, Charsets.US_ASCII)
            if (type == "IDAT") return png.copyOfRange(offset + 8, offset + 8 + length)
            offset += 12 + length
        }
        error("no IDAT chunk")
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && inflater.needsInput()) break
            out.write(buffer, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
