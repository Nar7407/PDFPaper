package com.pdfpaper.engine.export

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfBoolean
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageExtractorTest {

    private fun extract(
        bytes: ByteArray,
        options: ImageExtractOptions = ImageExtractOptions(documentName = "sample.pdf"),
        decoder: com.pdfpaper.engine.image.ExternalImageDecoder? = null
    ): ImageExtractResult {
        ExportFixtures.open(bytes).use { document ->
            return PdfImageExtractor.extract(document, options, decoder).getOrThrow()
        }
    }

    private fun single(image: PdfRef, fixture: ExportFixtures.Doc): ByteArray =
        fixture.build(listOf(ExportFixtures.page() to listOf(image)))

    @Test
    fun `a raw RGB image becomes an RGB PNG`() {
        val fixture = ExportFixtures.Doc()
        val ref = fixture.addImage(
            ExportFixtures.imageDict(3, 1), ExportFixtures.rgbSamples()
        )
        val result = extract(single(ref, fixture))
        val image = result.images.single()
        assertEquals("png", image.format)
        assertEquals("sample-img001.png", image.fileName)
        assertNull(image.skipped)
        val png = PngCheck.read(image.bytes!!)
        assertEquals(3, png.width)
        assertContentEquals(byteArrayOf(255.toByte(), 0, 0), png.pixel(0, 0))
        assertContentEquals(byteArrayOf(0, 255.toByte(), 0), png.pixel(1, 0))
        assertContentEquals(byteArrayOf(0, 0, 255.toByte()), png.pixel(2, 0))
    }

    @Test
    fun `a Flate grey image becomes a grey PNG`() {
        val fixture = ExportFixtures.Doc()
        val ref = fixture.addImage(
            ExportFixtures.imageDict(3, 1, PdfName("DeviceGray"), 8, PdfName("FlateDecode")),
            ExportFixtures.deflate(ExportFixtures.graySamples())
        )
        val result = extract(single(ref, fixture))
        val png = PngCheck.read(result.images.single().bytes!!)
        assertEquals(0, png.colorType)
        assertContentEquals(byteArrayOf(0), png.pixel(0, 0))
        assertContentEquals(byteArrayOf(128.toByte()), png.pixel(1, 0))
        assertContentEquals(byteArrayOf(255.toByte()), png.pixel(2, 0))
    }

    @Test
    fun `CMYK is converted to RGB`() {
        val fixture = ExportFixtures.Doc()
        val ref = fixture.addImage(
            ExportFixtures.imageDict(2, 1, PdfName("DeviceCMYK"), 8),
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 255.toByte())
        )
        val png = PngCheck.read(extract(single(ref, fixture)).images.single().bytes!!)
        // White and black are neutral, so the PNG writer picked grey.
        assertEquals(0, png.colorType)
        assertContentEquals(byteArrayOf(255.toByte()), png.pixel(0, 0))
        assertContentEquals(byteArrayOf(0), png.pixel(1, 0))
    }

    @Test
    fun `indexed colours resolve through the palette`() {
        val fixture = ExportFixtures.Doc()
        val space = PdfArray(
            listOf(
                PdfName("Indexed"),
                PdfName("DeviceRGB"),
                PdfInteger(1),
                PdfString(ExportFixtures.indexedLookup())
            )
        )
        val ref = fixture.addImage(
            ExportFixtures.imageDict(2, 1, space, 8),
            byteArrayOf(0, 1)
        )
        val result = extract(single(ref, fixture))
        val png = PngCheck.read(result.images.single().bytes!!)
        assertContentEquals(byteArrayOf(0, 0, 0), png.pixel(0, 0))
        assertContentEquals(byteArrayOf(255.toByte(), 0, 0), png.pixel(1, 0))
        assertEquals("Indexed(DeviceRGB)", result.listed.single().colorSpace)
    }

    @Test
    fun `an ICCBased image resolves through its alternate space`() {
        val fixture = ExportFixtures.Doc()
        val profile = fixture.builder.addStream(
            PdfDict(
                mapOf(
                    PdfName("N") to PdfInteger(3),
                    PdfName("Alternate") to PdfName("DeviceRGB")
                )
            ),
            byteArrayOf(1, 2, 3, 4)
        )
        val space = PdfArray(listOf(PdfName("ICCBased"), profile))
        val ref = fixture.addImage(
            ExportFixtures.imageDict(2, 1, space, 8),
            byteArrayOf(0, 0, 255.toByte(), 255.toByte(), 0, 0)
        )
        val result = extract(single(ref, fixture))
        assertEquals("ICCBased", result.listed.single().colorSpace)
        val png = PngCheck.read(result.images.single().bytes!!)
        assertContentEquals(byteArrayOf(0, 0, 255.toByte()), png.pixel(0, 0))
        assertContentEquals(byteArrayOf(255.toByte(), 0, 0), png.pixel(1, 0))
    }

    @Test
    fun `1 and 4 bit grey samples are unpacked`() {
        val oneBit = ExportFixtures.Doc()
        val one = oneBit.addImage(
            ExportFixtures.imageDict(8, 1, PdfName("DeviceGray"), 1),
            byteArrayOf(0b10101010.toByte())
        )
        val onePng = PngCheck.read(extract(single(one, oneBit)).images.single().bytes!!)
        assertContentEquals(byteArrayOf(255.toByte()), onePng.pixel(0, 0))
        assertContentEquals(byteArrayOf(0), onePng.pixel(1, 0))

        val fourBit = ExportFixtures.Doc()
        val four = fourBit.addImage(
            ExportFixtures.imageDict(2, 1, PdfName("DeviceGray"), 4),
            byteArrayOf(0x1F)
        )
        val fourPng = PngCheck.read(extract(single(four, fourBit)).images.single().bytes!!)
        assertContentEquals(byteArrayOf(17), fourPng.pixel(0, 0))
        assertContentEquals(byteArrayOf(255.toByte()), fourPng.pixel(1, 0))
    }

    @Test
    fun `16 bit samples are scaled into an 8 bit PNG`() {
        val fixture = ExportFixtures.Doc()
        val ref = fixture.addImage(
            ExportFixtures.imageDict(4, 1, PdfName("DeviceGray"), 16),
            ExportFixtures.gray16Samples()
        )
        val result = extract(single(ref, fixture))
        val image = result.images.single()
        val png = PngCheck.read(image.bytes!!)
        assertEquals(4, png.width)
        assertContentEquals(byteArrayOf(0), png.pixel(0, 0))
        assertContentEquals(byteArrayOf(127), png.pixel(1, 0))
        assertContentEquals(byteArrayOf(255.toByte()), png.pixel(2, 0))
        assertContentEquals(byteArrayOf(64), png.pixel(3, 0))
        assertEquals(16, result.listed.single().bitsPerComponent)
    }

    @Test
    fun `a soft mask becomes PNG alpha`() {
        val fixture = ExportFixtures.Doc()
        val mask = fixture.addImage(
            ExportFixtures.imageDict(2, 1, PdfName("DeviceGray"), 8),
            byteArrayOf(0, 255.toByte())
        )
        val ref = fixture.addImage(
            ExportFixtures.imageDict(
                2, 1, PdfName("DeviceGray"), 8,
                extra = mapOf("SMask" to mask)
            ),
            byteArrayOf(128.toByte(), 64)
        )
        val result = extract(single(ref, fixture))
        val png = PngCheck.read(result.images.single().bytes!!)
        assertEquals(4, png.colorType)
        assertContentEquals(byteArrayOf(128.toByte(), 0), png.pixel(0, 0))
        assertContentEquals(byteArrayOf(64, 255.toByte()), png.pixel(1, 0))
    }

    @Test
    fun `a stencil mask becomes a 1-bit black and transparent PNG`() {
        val fixture = ExportFixtures.Doc()
        val ref = fixture.addImage(
            ExportFixtures.imageDict(
                8, 1, colorSpace = null, bits = 1,
                extra = mapOf("ImageMask" to PdfBoolean(true))
            ),
            byteArrayOf(0xAA.toByte())
        )
        val result = extract(single(ref, fixture))
        val png = PngCheck.read(result.images.single().bytes!!)
        assertEquals(1, png.bitDepth)
        assertEquals(0, png.colorType)
        assertTrue(png.chunkTypes.contains("tRNS"))
        val row = png.stencilRow(0)
        assertTrue(row[0] && !row[1] && row[2] && !row[3])
        assertTrue(result.listed.single().imageMask)
    }

    @Test
    fun `a DCT image keeps the original JPEG bytes`() {
        val fixture = ExportFixtures.Doc()
        val ref = fixture.addImage(
            ExportFixtures.imageDict(1, 1, filter = PdfName("DCTDecode")),
            ExportFixtures.JPEG_BYTES.copyOf()
        )
        val result = extract(single(ref, fixture))
        val image = result.images.single()
        assertEquals("jpg", image.format)
        assertContentEquals(ExportFixtures.JPEG_BYTES, image.bytes)
        assertEquals(ExportFixtures.JPEG_BYTES.contentHashCode(), image.bytes!!.contentHashCode())
    }

    @Test
    fun `Flate-wrapped DCT is unwrapped before writing`() {
        val fixture = ExportFixtures.Doc()
        val ref = fixture.addImage(
            ExportFixtures.imageDict(
                1, 1,
                filter = PdfArray(listOf(PdfName("FlateDecode"), PdfName("DCTDecode")))
            ),
            ExportFixtures.deflate(ExportFixtures.JPEG_BYTES)
        )
        val result = extract(single(ref, fixture))
        val image = result.images.single()
        assertEquals("jpg", image.format)
        assertContentEquals(ExportFixtures.JPEG_BYTES, image.bytes)
        assertEquals("FlateDecode+DCTDecode", image.filter)
    }

    @Test
    fun `JPX keeps the original JPEG 2000 bytes`() {
        val fixture = ExportFixtures.Doc()
        val ref = fixture.addImage(
            ExportFixtures.imageDict(1, 1, filter = PdfName("JPXDecode")),
            ExportFixtures.JPX_BYTES.copyOf()
        )
        val result = extract(single(ref, fixture))
        val image = result.images.single()
        assertEquals("jp2", image.format)
        assertContentEquals(ExportFixtures.JPX_BYTES, image.bytes)
    }

    @Test
    fun `CCITT and JBIG2 are reported and skipped`() {
        for (filter in listOf("CCITTFaxDecode", "JBIG2Decode")) {
            val fixture = ExportFixtures.Doc()
            val ref = fixture.addImage(
                ExportFixtures.imageDict(10, 10, PdfName("DeviceGray"), 1, PdfName(filter)),
                ExportFixtures.GARBAGE
            )
            val result = extract(single(ref, fixture))
            val image = result.images.single()
            assertNull(image.fileName)
            assertTrue(image.skipped.orEmpty().contains("unsupported filter: $filter"), image.skipped.orEmpty())
            assertEquals(0, result.exported)
        }
    }

    @Test
    fun `broken image data is skipped without a crash`() {
        val fixture = ExportFixtures.Doc()
        val ref = fixture.addImage(
            ExportFixtures.imageDict(64, 64, filter = PdfName("FlateDecode")),
            ExportFixtures.GARBAGE
        )
        val result = extract(single(ref, fixture))
        val image = result.images.single()
        assertNotNull(image.skipped)
        assertNull(image.bytes)
    }

    @Test
    fun `small images are filtered by the minimum size`() {
        val fixture = ExportFixtures.Doc()
        val ref = fixture.addImage(ExportFixtures.imageDict(3, 1), ExportFixtures.rgbSamples())
        val result = extract(
            single(ref, fixture),
            ImageExtractOptions(documentName = "sample.pdf", minSize = 32)
        )
        assertEquals("smaller than 32 px", result.images.single().skipped)
    }

    @Test
    fun `identical images are de-duplicated by content`() {
        val fixture = ExportFixtures.Doc()
        val first = fixture.addImage(ExportFixtures.imageDict(3, 1), ExportFixtures.rgbSamples())
        val second = fixture.addImage(ExportFixtures.imageDict(3, 1), ExportFixtures.rgbSamples())
        val bytes = fixture.build(listOf(ExportFixtures.page() to listOf(first, second)))
        val result = extract(bytes)
        assertEquals(1, result.exported)
        assertEquals(1, result.skipped)
        assertTrue(result.images[1].skipped.orEmpty().contains("duplicate of sample-img001.png"))
    }

    @Test
    fun `duplicates can be kept when asked`() {
        val fixture = ExportFixtures.Doc()
        val first = fixture.addImage(ExportFixtures.imageDict(3, 1), ExportFixtures.rgbSamples())
        val second = fixture.addImage(ExportFixtures.imageDict(3, 1), ExportFixtures.rgbSamples())
        val bytes = fixture.build(listOf(ExportFixtures.page() to listOf(first, second)))
        val result = extract(
            bytes,
            ImageExtractOptions(documentName = "sample.pdf", skipDuplicates = false)
        )
        assertEquals(2, result.exported)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `the listing reports page dictionary details`() {
        val fixture = ExportFixtures.Doc()
        val ref = fixture.addImage(
            ExportFixtures.imageDict(4, 2, PdfName("DeviceGray"), 8, PdfName("FlateDecode")),
            ExportFixtures.deflate(ByteArray(8) { 7 })
        )
        val bytes = fixture.build(listOf(ExportFixtures.page() to listOf(ref)))
        ExportFixtures.open(bytes).use { document ->
            val info = PdfImageExtractor.list(document).single()
            assertEquals(0, info.pageIndex)
            assertEquals(ref.objNum, info.objectNumber)
            assertEquals(4, info.width)
            assertEquals(2, info.height)
            assertEquals(8, info.bitsPerComponent)
            assertEquals("DeviceGray", info.colorSpace)
            assertEquals("FlateDecode", info.filter)
            assertTrue(info.byteSize > 0)
            assertTrue(!info.imageMask)
            assertTrue(!info.hasSoftMask)
        }
    }

    @Test
    fun `images inside a form XObject are found`() {
        val fixture = ExportFixtures.Doc()
        val image = fixture.addImage(ExportFixtures.imageDict(3, 1), ExportFixtures.rgbSamples())
        val form = fixture.builder.addStream(
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("XObject"),
                    PdfName("Subtype") to PdfName("Form"),
                    PdfName("BBox") to PdfArray(
                        listOf(PdfInteger(0), PdfInteger(0), PdfInteger(612), PdfInteger(792))
                    ),
                    PdfName("Resources") to PdfDict(
                        mapOf(PdfName("XObject") to PdfDict(mapOf(PdfName("Im0") to image)))
                    )
                )
            ),
            "q 1 0 0 1 0 0 cm /Im0 Do Q".toByteArray(Charsets.US_ASCII)
        )
        val page = PdfRef(fixture.builder.allocate(), 0)
        // The page references the form; the fixture DSL only places images, so
        // build the page dictionary by hand.
        val pagesRef = PdfRef(fixture.builder.allocate(), 0)
        fixture.builder.put(
            page.objNum,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Page"),
                    PdfName("Parent") to pagesRef,
                    PdfName("MediaBox") to PdfArray(
                        listOf(PdfInteger(0), PdfInteger(0), PdfInteger(612), PdfInteger(792))
                    ),
                    PdfName("Resources") to PdfDict(
                        mapOf(PdfName("XObject") to PdfDict(mapOf(PdfName("Fm0") to form)))
                    )
                )
            )
        )
        fixture.builder.put(
            pagesRef.objNum,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Pages"),
                    PdfName("Kids") to PdfArray(listOf(page)),
                    PdfName("Count") to PdfInteger(1)
                )
            )
        )
        fixture.builder.root = fixture.builder.add(
            PdfDict(mapOf(PdfName("Type") to PdfName("Catalog"), PdfName("Pages") to pagesRef))
        )
        val result = extract(fixture.builder.build())
        assertEquals(1, result.images.size)
        assertEquals(3, result.images.single().width)
        assertNotNull(result.images.single().bytes)
    }

    @Test
    fun `a document without images reports that clearly`() {
        val fixture = ExportFixtures.Doc()
        val bytes = fixture.build(listOf(ExportFixtures.page() to emptyList()))
        val result = extract(bytes)
        assertEquals(0, result.images.size)
        assertTrue(result.warnings.any { it.contains("No image XObjects") })
    }

    @Test
    fun `DCT pixels can be decoded through the host decoder when needed`() {
        val fixture = ExportFixtures.Doc()
        val mask = fixture.addImage(
            ExportFixtures.imageDict(1, 1, PdfName("DeviceGray"), 8),
            byteArrayOf(255.toByte())
        )
        val ref = fixture.addImage(
            ExportFixtures.imageDict(1, 1, filter = PdfName("DCTDecode"), extra = mapOf("SMask" to mask)),
            ExportFixtures.JPEG_BYTES.copyOf()
        )
        // A DCT image is passed through untouched even with a soft mask; the
        // note says the mask was not carried by the JPEG.
        val result = extract(single(ref, fixture))
        assertContentEquals(ExportFixtures.JPEG_BYTES, result.images.single().bytes)
        assertTrue(result.images.single().notes.any { it.contains("soft mask") })
    }
}
