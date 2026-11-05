package com.pdfpaper.app.index

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.render.AndroidRasterDecoder
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.export.PngWriter
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.ocr.OcrBounds
import com.pdfpaper.engine.ocr.OcrProvider
import com.pdfpaper.engine.ocr.OcrWord
import com.pdfpaper.engine.ocr.PdfOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device checks for the T50 extraction step. The text path uses the engine's
 * reading-order layout, a page with no text layer becomes "needs OCR", the
 * per-file limits are enforced before/while reading, and an invisible T25 OCR
 * layer is part of the indexed text (the task explicitly requires that).
 */
@RunWith(AndroidJUnit4::class)
class PdfIndexExtractorDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val extractor = PdfIndexExtractor(context)

    private fun write(name: String, bytes: ByteArray): File {
        val file = File(context.cacheDir, "index-test-$name-${System.nanoTime()}.pdf")
        file.writeBytes(bytes)
        return file
    }

    private fun textAndBlankPdf(): ByteArray = PdfBuilder(PageSize.A4).apply {
        addPage().apply {
            drawText("budget report for the year", 40.0, 60.0, 14.0)
        }
        addPage()
    }.build()

    @Test
    fun textPagesAreIndexedAndBlankPagesAreCounted() {
        val file = write("mixed", textAndBlankPdf())
        try {
            val outcome = extractor.extract(file.absolutePath, file.length())
            assertTrue(outcome is PdfIndexExtractor.Outcome.Text)
            val text = outcome as PdfIndexExtractor.Outcome.Text
            assertEquals(2, text.pageCount)
            assertEquals(1, text.pagesWithoutText)
            assertEquals(1, text.pages.size)
            assertEquals(0, text.pages.single().pageIndex)
            assertTrue(text.pages.single().text.contains("budget report"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun anInvisibleOcrLayerBecomesIndexedText() {
        val rgba = ByteArray(40 * 20 * 4) { 0xFF.toByte() }
        val png = PngWriter.encode(40, 20, rgba)
        assertTrue(png.isNotEmpty())
        val image = EditImages.decode(png, AndroidRasterDecoder).getOrThrow()
        val scan = PdfBuilder(PageSize.A4).apply {
            addPage().apply {
                drawImage(image, 40.0, 40.0, 200.0, 100.0)
            }
        }.build()

        val provider = OcrProvider { _, _ ->
            listOf(
                OcrWord(
                    "budget",
                    OcrBounds(2.0, 2.0, 30.0, 18.0),
                    0.9
                )
            )
        }
        val searchable = PdfDocument.open(ByteArraySource(scan)).getOrThrow().use { document ->
            PdfOcr.makeSearchable(document, provider).getOrThrow()
        }
        assertEquals(1, searchable.wordsAdded)

        val file = write("ocr", searchable.bytes)
        try {
            val outcome = extractor.extract(file.absolutePath, file.length())
            assertTrue(outcome is PdfIndexExtractor.Outcome.Text)
            val text = outcome as PdfIndexExtractor.Outcome.Text
            assertEquals(1, text.pageCount)
            assertEquals(0, text.pagesWithoutText)
            assertTrue(text.pages.single().text.contains("budget"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun limitsAndCancellationReturnOutcomesInsteadOfThrowing() {
        val bytes = textAndBlankPdf()
        val file = write("limits", bytes)
        try {
            assertTrue(
                extractor.extract(file.absolutePath, PdfIndexExtractor.MAX_FILE_BYTES + 1) is
                    PdfIndexExtractor.Outcome.TooLarge
            )
            assertTrue(
                extractor.extract(file.absolutePath, file.length(), deadlineNanos = 0L) is
                    PdfIndexExtractor.Outcome.TimedOut
            )
            assertTrue(
                extractor.extract(
                    file.absolutePath,
                    file.length(),
                    cancelled = { true }
                ) is PdfIndexExtractor.Outcome.Cancelled
            )
        } finally {
            file.delete()
        }

        val corrupt = write("corrupt", "this is not a PDF".toByteArray())
        try {
            assertTrue(
                extractor.extract(corrupt.absolutePath, corrupt.length()) is
                    PdfIndexExtractor.Outcome.Failed
            )
        } finally {
            corrupt.delete()
        }
    }
}
