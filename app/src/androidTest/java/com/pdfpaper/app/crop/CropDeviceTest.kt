package com.pdfpaper.app.crop

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.render.AndroidImageDecoder
import com.pdfpaper.app.render.AndroidPageRasterizer
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.crop.CropRequest
import com.pdfpaper.engine.crop.CropSpec
import com.pdfpaper.engine.crop.PdfCrop
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.mark.PageSelection
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.redact.PdfRedaction
import com.pdfpaper.engine.text.extractText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * On-device acceptance for T46: the crop engine runs with the Android decoder
 * and rasterizer (the same codecs the viewer uses), writes the CropBox while
 * keeping the MediaBox, deletes hidden text with the T28 pass, and auto-crop
 * detects content from a real Android page render.
 */
@RunWith(AndroidJUnit4::class)
class CropDeviceTest {

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun textPdf(): ByteArray = PdfBuilder(PageSize.A4).apply {
        addPage().apply {
            drawText("OUTSIDE", 20.0, 20.0, 14.0)
            drawText("INSIDE", 250.0, 400.0, 14.0)
        }
    }.build()

    private fun paintedPdf(): ByteArray = PdfBuilder(PageSize.LETTER).apply {
        addPage().apply {
            drawRect(100.0, 100.0, 200.0, 200.0)
        }
    }.build()

    private fun cropBox(document: PdfDocument): PdfRect =
        PdfRect.of(document.pages()[0].cropBox) ?: error("no crop box")

    private fun mediaBox(document: PdfDocument): PdfRect =
        PdfRect.of(document.pages()[0].mediaBox) ?: error("no media box")

    private fun textOf(bytes: ByteArray): String = open(bytes).use { document ->
        document.pages()[0].extractText().runs.joinToString("") { it.text }
    }

    @Test
    fun cropWritesTheCropBoxAndKeepsTheMediaBox() {
        open(textPdf()).use { document ->
            val media = mediaBox(document)
            val result = PdfCrop.apply(
                document,
                CropRequest(CropSpec.Margins(100.0, 100.0, 100.0, 100.0), PageSelection.All)
            ).getOrThrow()
            open(result.bytes).use { reopened ->
                val box = cropBox(reopened)
                assertEquals(100.0, box.left, 0.01)
                assertEquals(100.0, box.bottom, 0.01)
                assertEquals(media.right - 100.0, box.right, 0.01)
                assertEquals(media.top - 100.0, box.top, 0.01)
                assertEquals(media, mediaBox(reopened))
            }
        }
    }

    @Test
    fun aNormalCropKeepsTheOutsideTextButRemoveHiddenDeletesIt() {
        open(textPdf()).use { document ->
            val cropped = PdfCrop.apply(
                document,
                CropRequest(CropSpec.Margins(100.0, 100.0, 100.0, 100.0), PageSelection.All)
            ).getOrThrow()
            assertTrue(textOf(cropped.bytes).contains("OUTSIDE"))
        }
        open(textPdf()).use { document ->
            val cleaned = PdfCrop.apply(
                document,
                CropRequest(CropSpec.Margins(100.0, 100.0, 100.0, 100.0), PageSelection.All),
                removeHiddenContent = true,
                decoder = AndroidImageDecoder
            ).getOrThrow()
            assertTrue(cleaned.hiddenContentRemoved)
            val text = textOf(cleaned.bytes)
            assertFalse("outside text survived: $text", text.contains("OUTSIDE"))
            assertTrue("inside text was lost: $text", text.contains("INSIDE"))
            open(cleaned.bytes).use { reopened ->
                val verification = PdfRedaction.verify(reopened, listOf("OUTSIDE"))
                assertTrue("leaks=${verification.leaks}", verification.passed)
            }
        }
    }

    @Test
    fun autoCropDetectsThePaintedBlockOnAndroid() {
        open(paintedPdf()).use { document ->
            val auto = PdfCrop.auto(
                document = document,
                rasterizer = AndroidPageRasterizer,
                tolerance = 10,
                dpi = 96
            ).getOrThrow()
            val box = auto.boxes[0]
            assertNotNull(box)
            // PdfBuilder is top-left with width/height; PDF is y-up.
            // Letter is 612 × 792 and the rectangle is 100..300 on both axes.
            assertEquals(100.0, box!!.left, 3.0)
            assertEquals(792.0 - 300.0, box.bottom, 3.0)
            assertEquals(300.0, box.right, 3.0)
            assertEquals(792.0 - 100.0, box.top, 3.0)
            assertTrue(abs(box.width - 200.0) < 4.0)
            assertTrue(abs(box.height - 200.0) < 4.0)
        }
    }
}
