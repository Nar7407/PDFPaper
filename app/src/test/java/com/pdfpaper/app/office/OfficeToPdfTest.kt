package com.pdfpaper.app.office

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app-facing contract: a good document becomes a PDF the engine can read,
 * and every bad input comes back as a typed failure instead of an exception.
 */
class OfficeToPdfTest {

    private fun sample(name: String): File =
        File(checkNotNull(javaClass.getResource("/office/$name")).toURI())

    private fun tempFile(suffix: String, bytes: ByteArray): File {
        val file = File.createTempFile("office", suffix)
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    private fun pageCount(pdf: ByteArray): Int =
        PdfDocument.open(ByteArraySource(pdf)).getOrThrow().use { it.pages().size }

    @Test
    fun aWordDocumentConvertsToAPdfTheEngineCanRead() {
        val conversion = OfficeToPdf.convert(sample("notes.docx"), OfficeKind.WORD).getOrThrow()

        assertEquals("%PDF-", String(conversion.pdf.copyOf(5), Charsets.US_ASCII))
        assertTrue("expected at least one page", pageCount(conversion.pdf) >= 1)
        assertTrue(
            "expected the font substitution warning, got ${conversion.warnings}",
            conversion.warnings.any { it.contains("is replaced by") }
        )
    }

    @Test
    fun aPresentationConvertsToOnePagePerSlide() {
        val conversion =
            OfficeToPdf.convert(sample("deck.pptx"), OfficeKind.POWERPOINT).getOrThrow()

        assertEquals(2, pageCount(conversion.pdf))
    }

    @Test
    fun aFileOverTheLimitIsRefusedAsTooLarge() {
        val error = OfficeToPdf
            .convert(sample("notes.docx"), OfficeKind.WORD, maxBytes = 1024)
            .exceptionOrNull()

        assertTrue("expected TooLarge, got $error", error is OfficeConversionException.TooLarge)
        assertTrue(error!!.message!!.contains("the limit is"))
    }

    @Test
    fun aTruncatedWordPackageFailsAsCorruptWithoutThrowing() {
        val truncated = tempFile(".docx", sample("notes.docx").readBytes().copyOf(200))

        val error = OfficeToPdf.convert(truncated, OfficeKind.WORD).exceptionOrNull()

        assertTrue("expected Corrupt, got $error", error is OfficeConversionException.Corrupt)
    }

    @Test
    fun aFileThatIsNoPackageAtAllKeepsTheConvertersMessage() {
        val garbage = tempFile(".pptx", "not a zip at all".toByteArray())

        val error = OfficeToPdf.convert(garbage, OfficeKind.POWERPOINT).exceptionOrNull()

        assertTrue("expected Corrupt, got $error", error is OfficeConversionException.Corrupt)
        assertTrue(error!!.message!!.isNotBlank())
    }

    @Test
    fun aMissingFileFailsInsteadOfReadingNothing() {
        val missing = File(
            System.getProperty("java.io.tmpdir"),
            "gone-${System.nanoTime()}.docx"
        )

        val error = OfficeToPdf.convert(missing, OfficeKind.WORD).exceptionOrNull()

        assertTrue("expected Corrupt, got $error", error is OfficeConversionException.Corrupt)
    }

    @Test
    fun aConversionThatRunsTooLongIsStoppedAndReported() {
        val error = OfficeToPdf
            .convert(sample("deck.pptx"), OfficeKind.POWERPOINT, timeoutMs = 1)
            .exceptionOrNull()

        assertTrue("expected Corrupt, got $error", error is OfficeConversionException.Corrupt)
        assertTrue(error!!.message!!.contains("did not finish"))
    }
}
