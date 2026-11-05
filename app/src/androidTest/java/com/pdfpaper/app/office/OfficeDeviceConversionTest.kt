package com.pdfpaper.app.office

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The acceptance case that matters: the sample office files convert on a real
 * device (not only on the JVM the unit tests run on), and the PDF opens with
 * the same engine the viewer uses.
 */
@RunWith(AndroidJUnit4::class)
class OfficeDeviceConversionTest {

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    /** Copies a test asset out of the test APK so the converters can read it. */
    private fun copyAsset(name: String): File {
        val target = File(instrumentation.targetContext.cacheDir, "office-samples/$name")
        target.parentFile?.mkdirs()
        instrumentation.context.assets.open("office/$name").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun pageCount(pdf: ByteArray): Int =
        PdfDocument.open(ByteArraySource(pdf)).getOrThrow().use { it.pages().size }

    @Test
    fun theWordSampleConvertsToAPageTheEngineCanRead() {
        val result = OfficeToPdf.convert(copyAsset("notes.docx"), OfficeKind.WORD)

        assertTrue("conversion failed: ${result.exceptionOrNull()}", result.isSuccess)
        val conversion = result.getOrThrow()
        assertEquals("%PDF-", String(conversion.pdf.copyOf(5), Charsets.US_ASCII))
        assertTrue(pageCount(conversion.pdf) >= 1)
        assertTrue(conversion.warnings.isNotEmpty())
    }

    @Test
    fun thePowerPointSampleConvertsToTwoPages() {
        val result = OfficeToPdf.convert(copyAsset("deck.pptx"), OfficeKind.POWERPOINT)

        assertTrue("conversion failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(2, pageCount(result.getOrThrow().pdf))
    }

    @Test
    fun theLegacySampleIsRefusedBeforeAnyConversion() {
        assertEquals(
            DocumentKind.LegacyOffice,
            OfficeDetect.detect(copyAsset("legacy-sample.doc"))
        )
    }
}
