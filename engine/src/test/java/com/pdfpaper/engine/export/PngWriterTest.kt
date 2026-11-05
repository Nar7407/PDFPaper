package com.pdfpaper.engine.export

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PngWriterTest {

    @Test
    fun `opaque colour pixels become an RGB PNG`() {
        val rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 128.toByte(), 0, 255.toByte()
        )
        val png = PngCheck.read(PngWriter.encode(2, 1, rgba))
        assertEquals(2, png.width)
        assertEquals(1, png.height)
        assertEquals(8, png.bitDepth)
        assertEquals(2, png.colorType)
        assertContentEquals(byteArrayOf(255.toByte(), 0, 0), png.pixel(0, 0))
        assertContentEquals(byteArrayOf(0, 128.toByte(), 0), png.pixel(1, 0))
        assertTrue(png.chunkTypes.contains("IDAT"))
    }

    @Test
    fun `alpha pixels become an RGBA PNG`() {
        val rgba = byteArrayOf(
            10, 20, 30, 40,
            50, 60, 70, 255.toByte()
        )
        val png = PngCheck.read(PngWriter.encode(2, 1, rgba))
        assertEquals(6, png.colorType)
        assertContentEquals(byteArrayOf(10, 20, 30, 40), png.pixel(0, 0))
    }

    @Test
    fun `neutral pixels shrink to a grey PNG`() {
        val rgba = byteArrayOf(
            40, 40, 40, 255.toByte(),
            200.toByte(), 200.toByte(), 200.toByte(), 255.toByte()
        )
        val png = PngCheck.read(PngWriter.encode(2, 1, rgba))
        assertEquals(0, png.colorType)
        assertContentEquals(byteArrayOf(40), png.pixel(0, 0))
        assertContentEquals(byteArrayOf(200.toByte()), png.pixel(1, 0))
    }

    @Test
    fun `neutral pixels with alpha become grey plus alpha`() {
        val rgba = byteArrayOf(
            40, 40, 40, 0,
            200.toByte(), 200.toByte(), 200.toByte(), 255.toByte()
        )
        val png = PngCheck.read(PngWriter.encode(2, 1, rgba))
        assertEquals(4, png.colorType)
        assertContentEquals(byteArrayOf(40, 0), png.pixel(0, 0))
        assertContentEquals(byteArrayOf(200.toByte(), 255.toByte()), png.pixel(1, 0))
    }

    @Test
    fun `stencil masks become a 1-bit PNG with tRNS`() {
        // 8x1: opaque, clear, opaque, clear, clear, clear, opaque, clear
        val alpha = byteArrayOf(
            255.toByte(), 0, 255.toByte(), 0, 0, 0, 255.toByte(), 0
        )
        val png = PngCheck.read(PngWriter.encodeStencil(8, 1, alpha))
        assertEquals(1, png.bitDepth)
        assertEquals(0, png.colorType)
        assertTrue(png.chunkTypes.contains("tRNS"))
        assertContentEquals(byteArrayOf(0, 0), png.chunkData["tRNS"])
        val row = png.stencilRow(0)
        assertTrue(row[0] && !row[1] && row[2] && !row[3] && !row[4] && !row[5] && row[6] && !row[7])
    }

    @Test
    fun `indexed PNG keeps the palette and alpha table`() {
        val png = PngCheck.read(
            PngWriter.encodeIndexed(
                2, 1,
                byteArrayOf(0, 1),
                byteArrayOf(0, 0, 0, 255.toByte(), 0, 0),
                byteArrayOf(0, 255.toByte())
            )
        )
        assertEquals(3, png.colorType)
        assertContentEquals(byteArrayOf(0, 0, 0, 255.toByte(), 0, 0), png.chunkData["PLTE"])
        assertContentEquals(byteArrayOf(0, 255.toByte()), png.chunkData["tRNS"])
    }

    @Test
    fun `invalid buffers return an empty result instead of throwing`() {
        assertTrue(PngWriter.encode(0, 10, ByteArray(0)).isEmpty())
        assertTrue(PngWriter.encode(4, 4, ByteArray(8)).isEmpty())
        assertTrue(PngWriter.encodeStencil(4, 4, ByteArray(4)).isEmpty())
        assertTrue(PngWriter.encodeIndexed(2, 1, byteArrayOf(0, 1), ByteArray(4)).isEmpty())
    }
}
