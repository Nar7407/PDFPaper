package com.pdfpaper.renderjvm

import com.pdfpaper.engine.export.ImageExtractOptions
import com.pdfpaper.engine.export.PdfImageExtractor
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.write.PdfDocumentBuilder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T45 acceptance on the JVM host: a JPEG written by the host codec and stored
 * in a PDF must come back out byte for byte, and a Flate image must become a
 * PNG that ImageIO can open.
 */
class ExtractJpegTest {

    private fun buildDocument(images: List<Pair<Map<String, PdfObject>, ByteArray>>): ByteArray {
        val builder = PdfDocumentBuilder("1.7")
        val pagesRef = PdfRef(builder.allocate(), 0)
        val pageRef = PdfRef(builder.allocate(), 0)
        val xobjects = LinkedHashMap<PdfName, PdfObject>()
        images.forEachIndexed { index, (dict, data) ->
            xobjects[PdfName("Im$index")] = builder.addStream(PdfDict(dict.mapKeys { PdfName(it.key) }), data)
        }
        val content = images.indices.joinToString("") { "q 100 0 0 100 20 600 cm /Im$it Do Q\n" }
        val contents = builder.addStream(
            PdfDict(mapOf(PdfName("Type") to PdfName("Content"))),
            content.toByteArray(Charsets.US_ASCII)
        )
        builder.put(
            pageRef.objNum,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Page"),
                    PdfName("Parent") to pagesRef,
                    PdfName("MediaBox") to PdfArray(
                        listOf(PdfInteger(0), PdfInteger(0), PdfInteger(612), PdfInteger(792))
                    ),
                    PdfName("Resources") to PdfDict(mapOf(PdfName("XObject") to PdfDict(xobjects))),
                    PdfName("Contents") to contents
                )
            )
        )
        builder.put(
            pagesRef.objNum,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Pages"),
                    PdfName("Kids") to PdfArray(listOf(pageRef)),
                    PdfName("Count") to PdfInteger(1)
                )
            )
        )
        builder.root = builder.add(
            PdfDict(mapOf(PdfName("Type") to PdfName("Catalog"), PdfName("Pages") to pagesRef))
        )
        return builder.build()
    }

    private fun jpeg(): ByteArray {
        val width = 40
        val height = 24
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * 4
                rgba[i] = (x * 6).toByte()
                rgba[i + 1] = (y * 10).toByte()
                rgba[i + 2] = ((x + y) * 3).toByte()
                rgba[i + 3] = 255.toByte()
            }
        }
        return JvmImageEncoder.encodeJpeg(width, height, rgba, quality = 85, grayscale = false)!!.bytes
    }

    private fun imageDict(width: Int, height: Int, filter: String): Map<String, PdfObject> = mapOf(
        "Type" to PdfName("XObject"),
        "Subtype" to PdfName("Image"),
        "Width" to PdfInteger(width.toLong()),
        "Height" to PdfInteger(height.toLong()),
        "ColorSpace" to PdfName("DeviceGray"),
        "BitsPerComponent" to PdfInteger(8),
        "Filter" to PdfName(filter)
    )

    @Test
    fun theExtractedJpegIsTheOriginalFile() {
        val original = jpeg()
        val pdf = buildDocument(listOf(imageDict(40, 24, "DCTDecode") to original))
        PdfDocument.open(ByteArraySource(pdf)).getOrThrow().use { document ->
            val result = PdfImageExtractor.extract(
                document,
                ImageExtractOptions(documentName = "sample.pdf"),
                JvmImageDecoder
            ).getOrThrow()
            val image = result.images.single()
            assertEquals("jpg", image.format)
            assertTrue(original.contentEquals(image.bytes))
            assertEquals(original.contentHashCode(), image.bytes!!.contentHashCode())
        }
    }

    @Test
    fun aFlateImageBecomesAPngImageIoCanOpen() {
        val samples = ByteArray(8 * 4)
        for (y in 0 until 4) {
            for (x in 0 until 8) samples[y * 8 + x] = ((x + y) * 16).toByte()
        }
        val deflater = java.util.zip.Deflater()
        deflater.setInput(samples)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(256)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n > 0) out.write(buffer, 0, n)
        }
        deflater.end()
        val compressed = out.toByteArray()
        val pdf = buildDocument(listOf(imageDict(8, 4, "FlateDecode") to compressed))
        PdfDocument.open(ByteArraySource(pdf)).getOrThrow().use { document ->
            val result = PdfImageExtractor.extract(
                document,
                ImageExtractOptions(documentName = "sample.pdf"),
                JvmImageDecoder
            ).getOrThrow()
            val png = result.images.single().bytes!!
            val decoded = ImageIO.read(ByteArrayInputStream(png))
            assertNotNull(decoded)
            val image: BufferedImage = decoded
            assertEquals(8, image.width)
            assertEquals(4, image.height)
            // Read the sample, not getRGB(): ImageIO applies a linear-grey →
            // sRGB conversion for a grey PNG without a gAMA chunk.
            val sample = image.raster.getSample(7, 3, 0)
            assertEquals((7 + 3) * 16, sample)
        }
    }
}
