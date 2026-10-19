package com.pdfpaper.engine.create

import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.page.page
import com.pdfpaper.engine.page.pageCount
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfFromImagesTest {

    private val page = PageSize.A4
    private val margin = 36.0

    @Test
    fun fitScalesTheWholeImageInsideTheMargins() {
        val width = 200
        val height = 100
        val placement = PdfFromImages.place(
            width,
            height,
            ImagePdfOptions(pageSize = page, margin = margin, fit = ImageFit.FIT)
        )

        val contentWidth = page.width - 2 * margin
        val contentHeight = page.height - 2 * margin
        val scale = minOf(contentWidth / width, contentHeight / height)

        assertClose(contentWidth, placement.width)
        assertClose(height * scale, placement.height)
        assertClose(margin, placement.x)
        assertClose(margin + (contentHeight - height * scale) / 2, placement.y)
        assertNull(placement.clip)
    }

    @Test
    fun fillCoversTheMarginsAndClipsTheOverflow() {
        val width = 100
        val height = 300
        val placement = PdfFromImages.place(
            width,
            height,
            ImagePdfOptions(pageSize = page, margin = margin, fit = ImageFit.FILL)
        )

        val contentWidth = page.width - 2 * margin
        val contentHeight = page.height - 2 * margin
        val scale = maxOf(contentWidth / width, contentHeight / height)

        assertClose(width * scale, placement.width)
        assertClose(height * scale, placement.height)
        assertTrue(placement.height > contentHeight, "fill should overflow vertically")
        assertClose(margin + (contentWidth - width * scale) / 2, placement.x)
        assertClose(margin + (contentHeight - height * scale) / 2, placement.y)

        val clip = assertNotNull(placement.clip)
        assertClose(margin, clip.x)
        assertClose(margin, clip.y)
        assertClose(contentWidth, clip.width)
        assertClose(contentHeight, clip.height)
    }

    @Test
    fun originalKeepsPixelSizeAndCentresIt() {
        val placement = PdfFromImages.place(
            120,
            80,
            ImagePdfOptions(pageSize = page, margin = 72.0, fit = ImageFit.ORIGINAL)
        )

        val contentWidth = page.width - 144.0
        val contentHeight = page.height - 144.0
        assertClose(120.0, placement.width)
        assertClose(80.0, placement.height)
        assertClose(72.0 + (contentWidth - 120.0) / 2, placement.x)
        assertClose(72.0 + (contentHeight - 80.0) / 2, placement.y)
        assertNull(placement.clip)
    }

    @Test
    fun landscapeSwapsTheContentBox() {
        val placement = PdfFromImages.place(
            400,
            200,
            ImagePdfOptions(
                pageSize = page,
                orientation = PageOrientation.LANDSCAPE,
                margin = 50.0,
                fit = ImageFit.FIT
            )
        )
        val contentWidth = page.height - 100.0
        val contentHeight = page.width - 100.0
        val scale = minOf(contentWidth / 400.0, contentHeight / 200.0)

        assertClose(400.0 * scale, placement.width)
        assertClose(200.0 * scale, placement.height)
        assertClose(50.0, placement.x)
    }

    @Test
    fun pngIsEmbeddedOnItsOwnPage() {
        val image = EditImages.decode(imageBytes("png", 64, 32)).getOrThrow()
        val bytes = PdfFromImages.create(
            listOf(image),
            ImagePdfOptions(pageSize = page, margin = margin, fit = ImageFit.FIT)
        )

        openPdf(bytes).use { document ->
            assertEquals(1, document.pageCount())
            assertEqualsList(listOf(0.0, 0.0, 595.276, 841.89), mediaBox(pageOf(document, 0)))
            val stream = firstImage(pageOf(document, 0))
            assertEquals(64L, (stream.dict["Width"] as com.pdfpaper.engine.model.PdfInteger).value)
            assertEquals(32L, (stream.dict["Height"] as com.pdfpaper.engine.model.PdfInteger).value)
            assertEquals("FlateDecode", (stream.dict["Filter"] as PdfName).name)
            assertContainsOperation(contentOf(pageOf(document, 0)), "/Im1 Do")
        }
    }

    @Test
    fun fillWritesAClipRectangle() {
        val image = EditImages.decode(imageBytes("png", 100, 300)).getOrThrow()
        val bytes = PdfFromImages.create(
            listOf(image),
            ImagePdfOptions(pageSize = page, margin = margin, fit = ImageFit.FILL)
        )
        openPdf(bytes).use { document ->
            val content = contentOf(pageOf(document, 0))
            assertContainsOperation(content, " re W n")
            assertContainsOperation(content, "/Im1 Do")
        }
    }

    @Test
    fun createFilesHandlesPngAndJpegInOrder() {
        val png = Files.createTempFile("pdfpaper-image", ".png").toFile()
        val jpeg = Files.createTempFile("pdfpaper-image", ".jpg").toFile()
        png.deleteOnExit()
        jpeg.deleteOnExit()
        png.writeBytes(imageBytes("png", 40, 20))
        jpeg.writeBytes(imageBytes("jpg", 30, 10))

        val bytes = PdfFromImages.createFiles(
            listOf(png, jpeg),
            ImagePdfOptions(pageSize = page, margin = 10.0, fit = ImageFit.ORIGINAL)
        ).getOrThrow()

        openPdf(bytes).use { document ->
            assertEquals(2, document.pageCount())
            val first = firstImage(pageOf(document, 0))
            val second = firstImage(pageOf(document, 1))
            assertEquals("FlateDecode", (first.dict["Filter"] as PdfName).name)
            assertEquals("DCTDecode", (second.dict["Filter"] as PdfName).name)
            val secondWidth = second.dict["Width"] as com.pdfpaper.engine.model.PdfInteger
            assertEquals(30L, secondWidth.value)
        }
    }

    @Test
    fun failuresAreReturnedNotThrown() {
        assertTrue(PdfFromImages.createFiles(emptyList()).isFailure)
        assertTrue(PdfFromImages.createFiles(listOf(File("missing-image.png"))).isFailure)

        val invalid = Files.createTempFile("pdfpaper-image", ".png").toFile()
        invalid.deleteOnExit()
        invalid.writeText("not an image")
        val result = PdfFromImages.createFiles(listOf(invalid))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains(invalid.name))
    }

    @Test
    fun rejectsImpossibleMarginsAndEmptyInput() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            PdfFromImages.place(100, 100, ImagePdfOptions(pageSize = page, margin = 300.0))
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            PdfFromImages.create(emptyList())
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            PdfFromImages.place(0, 100)
        }
    }

    @Test
    fun imageResourcesAreSharedAcrossPages() {
        val image = EditImages.decode(imageBytes("png", 8, 8)).getOrThrow()
        val bytes = PdfFromImages.create(
            listOf(image, image),
            ImagePdfOptions(pageSize = page)
        )
        openPdf(bytes).use { document ->
            assertEquals(2, document.pageCount())
            val first = pageOf(document, 0).dict["Resources"] as PdfDict
            val second = pageOf(document, 1).dict["Resources"] as PdfDict
            val firstXObjects = first["XObject"] as PdfDict
            val secondXObjects = second["XObject"] as PdfDict
            val firstRef = firstXObjects.entries.values.single()
            val secondRef = secondXObjects.entries.values.single()
            assertEquals(firstRef, secondRef)
        }
    }
}

private fun assertContainsOperation(content: String, operation: String) {
    assertTrue(content.contains(operation), "expected '$operation' in:\n$content")
}
