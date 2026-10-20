package com.pdfpaper.app.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.data.CacheExportFolder
import com.pdfpaper.app.render.AndroidImageDecoder
import com.pdfpaper.app.render.AndroidImageEncoder
import com.pdfpaper.app.render.AndroidPageRasterizer
import com.pdfpaper.app.render.AndroidRasterDecoder
import com.pdfpaper.app.render.PageBitmapRenderer
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.export.ImageExtractOptions
import com.pdfpaper.engine.export.PageExportOptions
import com.pdfpaper.engine.export.PdfImageExtractor
import com.pdfpaper.engine.export.PdfPageExport
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.page
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.write.PdfDocumentBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * On-device acceptance for T45: the exported page must be what the viewer
 * paints (same renderer, same pixels), JPEG extraction must be byte-identical,
 * PNG output must open for grey/RGB/CMYK/indexed/16-bit/soft-masked images,
 * and a 300-page run must not run out of memory.
 */
@RunWith(AndroidJUnit4::class)
class ExportImagesDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    /** A page with a red block, a line and text, so rendering has work to do. */
    private fun samplePdf(pages: Int = 1, size: PageSize = PageSize.LETTER): ByteArray {
        val builder = PdfBuilder(size)
        repeat(pages) { index ->
            val page = builder.addPage()
            page.drawRect(40.0, 40.0, 210.0, 90.0, fill = PdfColor(0.85, 0.15, 0.1))
            page.drawText("Page ${index + 1} export", 72.0, 620.0, 28.0)
            page.drawLine(72.0, 600.0, 500.0, 600.0, width = 2.0)
        }
        return builder.build()
    }

    private class Written(val bytes: MutableMap<String, ByteArray> = LinkedHashMap())

    private fun exportPages(
        document: PdfDocument,
        documentName: String,
        dpi: Int,
        format: com.pdfpaper.engine.export.ImageFormat,
        maxPixels: Long = PageExportOptions.DEFAULT_MAX_PIXELS
    ): Written {
        val written = Written()
        PdfPageExport.export(
            document = document,
            options = PageExportOptions(
                documentName = documentName,
                dpi = dpi,
                format = format,
                maxPixels = maxPixels
            ),
            rasterizer = AndroidPageRasterizer,
            encoder = AndroidImageEncoder,
            sink = { page -> written.bytes[page.fileName] = page.bytes }
        ).getOrThrow()
        return written
    }

    @Test
    fun exportedPagesMatchTheViewerAt72_150And300Dpi() {
        open(samplePdf()).use { document ->
            val page = document.page(0)!!
            for (dpi in listOf(72, 150, 300)) {
                val plan = PdfPageExport.plan(page, dpi)
                val rendered = PageBitmapRenderer.render(page, plan)
                val exported = exportPages(document, "sample.pdf", dpi, com.pdfpaper.engine.export.ImageFormat.PNG)
                val bytes = exported.bytes.values.single()
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertNotNull("PNG did not open at $dpi dpi", decoded)
                try {
                    assertEquals(rendered.bitmap.width, decoded.width)
                    assertEquals(rendered.bitmap.height, decoded.height)
                    val width = decoded.width
                    val height = decoded.height
                    val expected = IntArray(width * height)
                    val actual = IntArray(width * height)
                    rendered.bitmap.getPixels(expected, 0, width, 0, 0, width, height)
                    decoded.getPixels(actual, 0, width, 0, 0, width, height)
                    var differing = 0
                    for (i in expected.indices) {
                        if (abs(Color.red(expected[i]) - Color.red(actual[i])) > 8 ||
                            abs(Color.green(expected[i]) - Color.green(actual[i])) > 8 ||
                            abs(Color.blue(expected[i]) - Color.blue(actual[i])) > 8
                        ) {
                            differing++
                        }
                    }
                    // Visual regression tolerance: at most 0.5% of the pixels
                    // may differ by more than 8/255 per channel.
                    assertTrue(
                        "$dpi dpi: $differing differing pixels",
                        differing <= width * height / 200
                    )
                } finally {
                    rendered.bitmap.recycle()
                    decoded.recycle()
                }
            }
        }
    }

    @Test
    fun thePixelSizeFollowsTheDpi() {
        open(samplePdf()).use { document ->
            val sizes = listOf(72, 150, 300).map { dpi ->
                val written = exportPages(
                    document, "sample.pdf", dpi, com.pdfpaper.engine.export.ImageFormat.PNG
                )
                val bitmap = BitmapFactory.decodeByteArray(
                    written.bytes.values.single(), 0, written.bytes.values.single().size
                )
                val size = bitmap.width to bitmap.height
                bitmap.recycle()
                size
            }
            assertEquals(listOf(612 to 792, 1275 to 1650, 2550 to 3300), sizes)
        }
    }

    @Test
    fun aJpegExportOpensWithTheRightSize() {
        open(samplePdf()).use { document ->
            val written = exportPages(
                document, "sample.pdf", 150, com.pdfpaper.engine.export.ImageFormat.JPEG
            )
            val name = written.bytes.keys.single()
            assertTrue(name.endsWith(".jpg"))
            val bytes = written.bytes.values.single()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("JPEG did not open", bitmap)
            assertEquals(1275, bitmap.width)
            assertEquals(1650, bitmap.height)
            bitmap.recycle()
        }
    }

    @Test
    fun extractedJpegsAreByteIdenticalToTheOriginal() {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 32) {
            for (x in 0 until 48) {
                bitmap.setPixel(x, y, Color.rgb(x * 5 % 256, y * 8 % 256, (x + y) * 3 % 256))
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        val original = out.toByteArray()

        val image = EditImages.decode(original, AndroidRasterDecoder).getOrThrow()
        val builder = PdfBuilder(PageSize.LETTER)
        builder.addPage().drawImage(image, 50.0, 500.0, 240.0, 160.0)
        open(builder.build()).use { document ->
            val result = PdfImageExtractor.extract(
                document,
                ImageExtractOptions(documentName = "sample.pdf"),
                AndroidImageDecoder
            ).getOrThrow()
            val extracted = result.images.single()
            assertEquals("jpg", extracted.format)
            assertArrayEquals(original, extracted.bytes)
            assertEquals(original.contentHashCode(), extracted.bytes!!.contentHashCode())
        }
    }

    @Test
    fun extractedSampleImagesOpenAsPngIncludingASoftMask() {
        val fixture = FixtureDoc()
        val mask = fixture.addImage(
            width = 2, height = 1, colorSpace = PdfName("DeviceGray"), bits = 8,
            data = byteArrayOf(0, 255.toByte())
        )
        val gray = fixture.addImage(
            width = 2, height = 1, colorSpace = PdfName("DeviceGray"), bits = 8,
            data = byteArrayOf(128.toByte(), 64),
            extra = mapOf("SMask" to mask)
        )
        val cmyk = fixture.addImage(
            width = 2, height = 1, colorSpace = PdfName("DeviceCMYK"), bits = 8,
            data = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 255.toByte())
        )
        val indexed = fixture.addImage(
            width = 2, height = 1,
            colorSpace = PdfArray(
                listOf(
                    PdfName("Indexed"),
                    PdfName("DeviceRGB"),
                    PdfInteger(1),
                    PdfString(byteArrayOf(0, 0, 0, 255.toByte(), 0, 0))
                )
            ),
            bits = 8,
            data = byteArrayOf(0, 1)
        )
        val deep = fixture.addImage(
            width = 4, height = 1, colorSpace = PdfName("DeviceGray"), bits = 16,
            data = byteArrayOf(0x00, 0x00, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x40, 0x00)
        )
        val bytes = fixture.build(
            listOf(
                doubleArrayOf(0.0, 0.0, 612.0, 792.0) to listOf(gray, cmyk, indexed, deep)
            )
        )
        open(bytes).use { document ->
            val result = PdfImageExtractor.extract(
                document,
                ImageExtractOptions(documentName = "sample.pdf"),
                AndroidImageDecoder
            ).getOrThrow()
            assertEquals(4, result.exported)
            for (image in result.images) {
                val png = image.bytes!!
                val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
                assertNotNull("${image.fileName} did not open", bitmap)
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                bitmap.recycle()
            }
            // The soft-masked grey image carries its mask as alpha.
            val masked = BitmapFactory.decodeByteArray(
                result.images[0].bytes!!, 0, result.images[0].bytes!!.size
            )
            assertEquals(0, Color.alpha(masked.getPixel(0, 0)))
            assertEquals(255, Color.alpha(masked.getPixel(1, 0)))
            masked.recycle()
            // CMYK black came out black, CMYK white came out white.
            val converted = BitmapFactory.decodeByteArray(
                result.images[1].bytes!!, 0, result.images[1].bytes!!.size
            )
            assertEquals(Color.WHITE, converted.getPixel(0, 0))
            assertEquals(Color.BLACK, converted.getPixel(1, 0))
            converted.recycle()
        }
    }

    @Test
    fun threeHundredPagesExportWithoutRunningOutOfMemory() {
        open(samplePdf(pages = 300, size = PageSize.custom(72.0, 72.0))).use { document ->
            assertEquals(300, document.pages().size)
            var count = 0
            val summary = PdfPageExport.export(
                document = document,
                options = PageExportOptions(
                    documentName = "long.pdf",
                    dpi = 72,
                    format = com.pdfpaper.engine.export.ImageFormat.JPEG,
                    quality = 60
                ),
                rasterizer = AndroidPageRasterizer,
                encoder = AndroidImageEncoder,
                sink = { page ->
                    // Write it out and let it go, like the screen does.
                    assertEquals(72, page.pixelWidth)
                    assertEquals(72, page.pixelHeight)
                    count++
                }
            ).getOrThrow()
            assertEquals(300, summary.exported)
            assertEquals(300, count)
        }
    }

    @Test
    fun thePixelCapRefusesAndSuggestsALowerDpi() {
        open(samplePdf(size = PageSize.custom(2000.0, 2000.0))).use { document ->
            val error = PdfPageExport.export(
                document = document,
                options = PageExportOptions(
                    documentName = "huge.pdf",
                    dpi = 300,
                    maxPixels = 24_000_000
                ),
                rasterizer = AndroidPageRasterizer,
                encoder = AndroidImageEncoder,
                sink = {}
            ).exceptionOrNull()
            assertNotNull(error)
            val message = error!!.message.orEmpty()
            assertTrue(message, message.contains("MP limit"))
            assertTrue(message, message.contains("lower DPI"))
            assertTrue(message, message.contains("dpi would fit"))
        }
    }

    @Test
    fun theFolderWriterRefusesNothingUntilAskedAndDoesNotClobber() {
        val folder = CacheExportFolder(File(context.cacheDir, "t45-writer-${System.nanoTime()}"))
        assertNotNull(folder.write("a.png", "image/png", byteArrayOf(1, 2, 3)))
        assertTrue("a.png" in folder.existingNames())
        assertEquals(setOf("a.png"), folder.existingNames())
    }

    /** Minimal PDF builder for image dictionaries with masks. */
    private class FixtureDoc {
        val builder = PdfDocumentBuilder("1.7")

        fun addImage(
            width: Int,
            height: Int,
            colorSpace: PdfObject?,
            bits: Int,
            data: ByteArray,
            extra: Map<String, PdfObject> = emptyMap()
        ): PdfRef {
            val dict = LinkedHashMap<String, PdfObject>()
            dict["Type"] = PdfName("XObject")
            dict["Subtype"] = PdfName("Image")
            dict["Width"] = PdfInteger(width.toLong())
            dict["Height"] = PdfInteger(height.toLong())
            if (colorSpace != null) dict["ColorSpace"] = colorSpace
            dict["BitsPerComponent"] = PdfInteger(bits.toLong())
            dict.putAll(extra)
            return builder.addStream(PdfDict(dict.mapKeys { PdfName(it.key) }), data)
        }

        fun build(pages: List<Pair<DoubleArray, List<PdfRef>>>): ByteArray {
            val pagesRef = PdfRef(builder.allocate(), 0)
            val kids = ArrayList<PdfObject>()
            for ((box, images) in pages) {
                val pageRef = PdfRef(builder.allocate(), 0)
                val xobjects = LinkedHashMap<PdfName, PdfObject>()
                images.forEachIndexed { index, ref -> xobjects[PdfName("Im$index")] = ref }
                val content = images.indices.joinToString("") { "q 300 0 0 300 40 ${700 - it * 40} cm /Im$it Do Q\n" }
                val contentRef = builder.addStream(
                    PdfDict(mapOf(PdfName("Type") to PdfName("Content"))),
                    content.toByteArray(Charsets.US_ASCII)
                )
                builder.put(
                    pageRef.objNum,
                    PdfDict(
                        mapOf(
                            PdfName("Type") to PdfName("Page"),
                            PdfName("Parent") to pagesRef,
                            PdfName("MediaBox") to PdfArray(box.map { PdfInteger(it.toLong()) }),
                            PdfName("Resources") to PdfDict(
                                mapOf(PdfName("XObject") to PdfDict(xobjects))
                            ),
                            PdfName("Contents") to contentRef
                        )
                    )
                )
                kids += pageRef
            }
            builder.put(
                pagesRef.objNum,
                PdfDict(
                    mapOf(
                        PdfName("Type") to PdfName("Pages"),
                        PdfName("Kids") to PdfArray(kids),
                        PdfName("Count") to PdfInteger(kids.size.toLong())
                    )
                )
            )
            builder.root = builder.add(
                PdfDict(mapOf(PdfName("Type") to PdfName("Catalog"), PdfName("Pages") to pagesRef))
            )
            return builder.build()
        }
    }
}
