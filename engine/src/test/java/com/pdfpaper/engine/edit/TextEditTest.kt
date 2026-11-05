package com.pdfpaper.engine.edit

import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.text.extractText
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T17 text acceptance: adding text appends a content stream (the original stays
 * byte-for-byte), standard-14 and embedded-fallback fonts both round-trip, and
 * the added text is readable by the extractor after reopening.
 */
class TextEditTest {

    private fun onePageDoc(content: String): ByteArray {
        val bytes = content.toByteArray(StandardCharsets.ISO_8859_1)
        return buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                TestObj(
                    3, 0,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                        "/Resources << /Font << /F1 6 0 R >> >> /Contents 5 0 R >>"
                ),
                TestObj(5, 0, "<< /Length ${bytes.size} >>\nstream\n$content\nendstream"),
                TestObj(6, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
            )
        )
    }

    private fun open(bytes: ByteArray): PdfDocument = PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun resolve(document: PdfDocument, obj: PdfObject): PdfObject = when (obj) {
        is PdfRef -> document.resolve(obj) ?: error("dangling reference")
        else -> obj
    }

    @Test
    fun addsTextWithStandardFontAndKeepsOriginalContent() {
        val original = "BT /F1 12 Tf 10 10 Td (Original) Tj ET"
        val document = open(onePageDoc(original))
        val edits = listOf(
            AddTextEdit(0, "Added", 100.0, 200.0, EditFont.Standard(StandardFont.HELVETICA), 18.0, PdfColor(1.0, 0.0, 0.0))
        )
        val out = PdfEditOps.save(document, edits).getOrThrow()
        document.close()

        open(out).use { reopened ->
            assertFalse(reopened.file.rebuiltFromScan)
            val page = reopened.pages().single()

            val contents = page.dict["Contents"] as PdfArray
            assertEquals(2, contents.items.size)
            val first = reopened.resolve(contents.items[0] as PdfRef) as PdfStream
            assertEquals(original, String(first.decoded { reopened.resolve(it) }.getOrThrow().bytes, StandardCharsets.ISO_8859_1))

            val added = reopened.resolve(contents.items[1] as PdfRef) as PdfStream
            val addedText = String(added.decoded { reopened.resolve(it) }.getOrThrow().bytes, StandardCharsets.ISO_8859_1)
            assertTrue(addedText.contains("Added"))

            val fonts = page.resources!!.get("Font") as PdfDict
            assertTrue(fonts.entries.keys.any { it.name.startsWith("PpF") })

            val text = page.extractText().runs.joinToString("") { it.text }
            assertTrue(text.contains("Original"), "expected original text, got '$text'")
            assertTrue(text.contains("Added"), "expected added text, got '$text'")
        }
    }

    @Test
    fun fallbackFontIsEmbeddedAndExtracts() {
        val document = open(onePageDoc(""))
        val edits = listOf(AddTextEdit(0, "Hello \u00E9\u00FC\u00DF", 72.0, 72.0, EditFont.Fallback, 14.0))
        val out = PdfEditOps.save(document, edits).getOrThrow()
        document.close()

        open(out).use { reopened ->
            val page = reopened.pages().single()
            val fonts = page.resources!!.get("Font") as PdfDict
            val name = fonts.entries.keys.first { it.name.startsWith("PpF") }
            val font = resolve(reopened, fonts.entries.getValue(name)) as PdfDict

            assertEquals("TrueType", (font["Subtype"] as PdfName).name)
            assertEquals("WinAnsiEncoding", (font["Encoding"] as PdfName).name)
            val widths = (font["Widths"] as PdfArray).items.size
            assertTrue(widths >= 200, "expected a full WinAnsi width row, got $widths")

            val descriptor = reopened.resolve(font["FontDescriptor"] as PdfRef) as PdfDict
            val program = reopened.resolve(descriptor["FontFile2"] as PdfRef) as PdfStream
            assertTrue(program.decoded { reopened.resolve(it) }.getOrThrow().bytes.size > 100_000)

            val text = page.extractText().runs.joinToString("") { it.text }
            assertTrue(text.contains("Hello"), "expected fallback text, got '$text'")
        }
    }

    @Test
    fun unmappableCharacterBecomesQuestionMark() {
        val document = open(onePageDoc(""))
        // Greek alpha has no WinAnsi code and must degrade, not crash.
        val out = PdfEditOps.save(document, listOf(AddTextEdit(0, "A\u03B1B", 10.0, 10.0))).getOrThrow()
        document.close()
        open(out).use { reopened ->
            val contents = reopened.pages().single().dict["Contents"] as PdfArray
            val stream = reopened.resolve(contents.items.last() as PdfRef) as PdfStream
            val bytes = stream.decoded { reopened.resolve(it) }.getOrThrow().bytes
            assertTrue(String(bytes, StandardCharsets.ISO_8859_1).contains("(A?B)"))
        }
    }
}
