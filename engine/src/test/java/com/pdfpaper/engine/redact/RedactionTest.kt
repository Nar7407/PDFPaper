package com.pdfpaper.engine.redact

import com.pdfpaper.engine.edit.TextRunFixtures
import com.pdfpaper.engine.image.PdfImages
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.text.extractText
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RedactionTest {

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    @Test
    fun findAndRedactTextRemovesExtractionAndRawBytes() {
        val source = open(TextRunFixtures.standardHelvetica())
        source.use { doc ->
            val matches = PdfRedaction.find(doc, "World")
            assertEquals(1, matches.size, "matches=$matches")
            val result = PdfRedaction.apply(doc, listOf(matches.single().toMark()))
            assertTrue(result.complete, "unredacted=${result.unredacted} warnings=${result.warnings}")

            open(result.bytes).use { reopened ->
                val page = reopened.pages().single()
                val text = page.extractText().runs.joinToString("|") { it.text }
                assertFalse(text.contains("World"), "text=$text")
                assertTrue(text.contains("Hello"), "text=$text")
                val verification = PdfRedaction.verify(reopened, listOf("World"))
                assertTrue(verification.passed, "leaks=${verification.leaks}")
            }
        }
    }

    @Test
    fun deletePagesLeavesOnlyChosenPage() {
        val source = open(TextRunFixtures.standardHelvetica())
        source.use { doc ->
            val result = PdfRedaction.deletePages(doc, setOf(0))
            assertEquals(0, result.pageCount)
        }
    }

    @Test
    fun verifyReportsDeliberatelyBrokenDocument() {
        val source = open(TextRunFixtures.standardHelvetica())
        source.use { doc ->
            val verification = PdfRedaction.verify(doc, listOf("World"))
            assertFalse(verification.passed)
            assertTrue(verification.remaining.getValue("World") > 0)
        }
    }

    @Test
    fun sharedFormIsCopiedOnlyWhereItIsMarked() {
        val source = open(sharedFormPdf())
        source.use { doc ->
            val matches = PdfRedaction.find(doc, "Shared secret", pageIndices = setOf(0))
            assertEquals(1, matches.size, "matches=$matches")
            val result = PdfRedaction.apply(doc, listOf(matches.single().toMark()))
            open(result.bytes).use { reopened ->
                assertEquals("", allText(reopened.pages()[0]))
                assertEquals("Shared secret", allText(reopened.pages()[1]))
                assertTrue(PdfRedaction.verify(reopened, listOf("Shared secret")).passed.not())
                // Page 2 still intentionally contains the phrase; only page 1
                // should be checked when the mark is page-scoped.
                assertTrue(PdfRedaction.verify(reopened, listOf("Shared secret")).leaks.any { it.pageIndex == 1 })
                assertFalse(PdfRedaction.verify(reopened, listOf("Shared secret")).leaks.any { it.pageIndex == 0 })
            }
        }
    }

    @Test
    fun type0TextIsRemoved() {
        val source = open(TextRunFixtures.cidNoToUnicode())
        source.use { doc ->
            val match = PdfRedaction.find(doc, "Hi").single()
            open(PdfRedaction.apply(doc, listOf(match.toMark())).bytes).use { reopened ->
                val text = reopened.pages().single().extractText().runs.joinToString("") { it.text }
                assertEquals("", text)
                assertTrue(PdfRedaction.verify(reopened, listOf("Hi")).passed)
            }
        }
    }

    @Test
    fun imagePixelsInsideMarkAreBlankedAndOutsideKept() {
        val source = open(imagePdf())
        source.use { doc ->
            val mark = RedactionMark(
                0,
                com.pdfpaper.engine.geom.PdfRect(120.0, 530.0, 160.0, 570.0)
            )
            val result = PdfRedaction.apply(doc, listOf(mark))
            assertTrue(result.complete, "unredacted=${result.unredacted}")
            open(result.bytes).use { reopened ->
                val page = reopened.pages().single()
                val xobjects = page.resources?.get("XObject") as? com.pdfpaper.engine.model.PdfDict
                assertNotNull(xobjects)
                val imageRef = xobjects.entries.values.first() as PdfRef
                val stream = reopened.resolve(imageRef) as PdfStream
                val decoded = PdfImages.decodeImage(
                    stream.dict, stream.data, page.resources, reopened, JdkImageDecoder()
                ).image
                assertNotNull(decoded)
                // Image space: 10x10, placed 72..172 x 500..600.
                assertEquals(255, decoded.pixelAt(6, 5)!![0], "inside mark should be white")
                assertEquals(255, decoded.pixelAt(6, 5)!![1], "inside mark should be white")
                assertEquals(255, decoded.pixelAt(6, 5)!![2], "inside mark should be white")
                assertEquals(255, decoded.pixelAt(0, 0)!![0], "outside mark stays red")
                assertEquals(0, decoded.pixelAt(0, 0)!![1], "outside mark stays red")
            }
        }
    }

    @Test
    fun deleteOnePageRemovesItsContentAndDropsTheSharedFormOnlyIfUnused() {
        val source = open(sharedFormPdf())
        source.use { doc ->
            val result = PdfRedaction.deletePages(doc, setOf(0))
            assertEquals(1, result.pageCount)
            open(result.bytes).use { reopened ->
                assertEquals(1, reopened.pages().size)
                assertEquals("Shared secret", allText(reopened.pages().single()))
            }
        }
    }

    @Test
    fun findSupportsRegexPresetsAndPlainText() {
        val source = open(contactPdf())
        source.use { doc ->
            val plain = PdfRedaction.find(doc, "alice")
            assertEquals(1, plain.size)
            val regex = PdfRedaction.find(doc, "alice@[a-z.]+", regex = true)
            assertEquals(1, regex.size)
            val preset = PdfRedaction.find(doc, "", preset = RedactionPreset.EMAIL)
            assertEquals(1, preset.size)
            val numbers = PdfRedaction.find(doc, "", preset = RedactionPreset.NUMBER)
            assertTrue(numbers.isNotEmpty())
        }
    }

    // --------------------------------------------------------------- fixtures

    private fun sharedFormPdf(): ByteArray {
        val pageContent = "q 1 0 0 1 72 700 cm /Fm1 Do Q"
        val font = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding " +
            "/FirstChar 32 /LastChar 122 /Widths [ ${List(91) { 500 }.joinToString(" ")} ] >>"
        val form = stream(
            "/Type /XObject /Subtype /Form /BBox [0 0 220 60] " +
                "/Resources << /Font << /F1 7 0 R >> >> ",
            "BT /F1 12 Tf 0 10 Td (Shared secret) Tj ET"
        )
        return buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>"),
                TestObj(
                    3, 0,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                        "/Resources << /Font << /F1 7 0 R >> /XObject << /Fm1 8 0 R >> >> " +
                        "/Contents 5 0 R >>"
                ),
                TestObj(
                    4, 0,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                        "/Resources << /Font << /F1 7 0 R >> /XObject << /Fm1 8 0 R >> >> " +
                        "/Contents 6 0 R >>"
                ),
                TestObj(5, 0, streamBody(pageContent)),
                TestObj(6, 0, streamBody(pageContent)),
                TestObj(7, 0, font),
                TestObj(8, 0, form)
            )
        )
    }

    private fun imagePdf(): ByteArray {
        val pixels = ByteArray(10 * 10 * 3)
        for (i in 0 until 10 * 10) {
            pixels[i * 3] = 0xFF.toByte()
            pixels[i * 3 + 1] = 0
            pixels[i * 3 + 2] = 0
        }
        return buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                TestObj(
                    3, 0,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                        "/Resources << /XObject << /Im0 5 0 R >> >> /Contents 4 0 R >>"
                ),
                TestObj(4, 0, streamBody("q 100 0 0 100 72 500 cm /Im0 Do Q")),
                TestObj(
                    5, 0,
                    stream(
                        "/Type /XObject /Subtype /Image /Width 10 /Height 10 " +
                            "/ColorSpace /DeviceRGB /BitsPerComponent 8 ",
                        pixels
                    )
                )
            )
        )
    }

    private fun contactPdf(): ByteArray {
        val content = "BT /F1 12 Tf 72 700 Td (mail alice@example.com ref 12345) Tj ET"
        val font = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding " +
            "/FirstChar 32 /LastChar 122 /Widths [ ${List(91) { 500 }.joinToString(" ")} ] >>"
        return buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                TestObj(
                    3, 0,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                        "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>"
                ),
                TestObj(4, 0, streamBody(content)),
                TestObj(5, 0, font)
            )
        )
    }

    private fun streamBody(content: String): ByteArray = stream("", content)

    private fun stream(dict: String, content: String): ByteArray =
        stream(dict, content.toByteArray(StandardCharsets.ISO_8859_1))

    private fun stream(dict: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("<< /Length ${data.size} $dict>>\nstream\n".toByteArray(StandardCharsets.US_ASCII))
        out.write(data)
        out.write("\nendstream".toByteArray(StandardCharsets.US_ASCII))
        return out.toByteArray()
    }
}
