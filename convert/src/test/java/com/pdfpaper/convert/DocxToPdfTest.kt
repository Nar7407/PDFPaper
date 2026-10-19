package com.pdfpaper.convert

import com.pdfpaper.convert.docx.DocxMode
import com.pdfpaper.convert.pdf.StandardFont
import com.pdfpaper.convert.pdf.StandardFonts
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.text.PdfTextRun
import com.pdfpaper.engine.text.extractText
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DOCX→PDF end to end. The inputs are real packages written by python-docx (so
 * the reader is tested against an independent implementation of the format) and
 * the outputs are re-opened with the engine's own PDF reader, which checks the
 * file the way a viewer would.
 */
class DocxToPdfTest {

    private class RenderedPage(
        val width: Double,
        val height: Double,
        /** All runs of the page, joined without separators. */
        val text: String,
        val runs: List<PdfTextRun>
    ) {
        /** Every run of the page that contains [needle]. */
        fun runsWith(needle: String): List<PdfTextRun> = runs.filter { it.text.contains(needle) }

        /** The one run that contains [needle]; [needle] must fit in one run. */
        fun run(needle: String): PdfTextRun =
            runs.firstOrNull { it.text.contains(needle) }
                ?: error("no run contains \"$needle\" in: $text")

        /** The runs sharing the baseline of the run that contains [needle]. */
        fun lineOf(needle: String): List<PdfTextRun> {
            val y = run(needle).originY
            return runs.filter { abs(it.originY - y) < 0.2 }
        }

        fun has(needle: String): Boolean = text.contains(needle)
    }

    private fun sample(name: String): ByteArray =
        javaClass.getResourceAsStream("/docx/$name")?.readBytes()
            ?: error("missing test resource /docx/$name")

    private fun convert(name: String): DocxToPdfResult = DocxToPdf.convert(sample(name), sourceName = name)

    /** Opens the produced PDF with the engine and returns what it reads back. */
    private fun render(result: DocxToPdfResult): List<RenderedPage> =
        PdfDocument.open(ByteArraySource(result.bytes)).getOrThrow().use { document ->
            document.pages().map { page ->
                val extracted = page.extractText()
                assertTrue(
                    extracted.warnings.isEmpty(),
                    "the engine reported problems reading the produced PDF: ${extracted.warnings}"
                )
                val box = PageGeometry.boxOf(page)
                RenderedPage(
                    width = box.width,
                    height = box.height,
                    text = extracted.runs.joinToString("") { it.text },
                    runs = extracted.runs
                )
            }
        }

    private fun near(expected: Double, actual: Double, tolerance: Double = 1.5) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "expected $expected, got $actual (tolerance $tolerance)"
        )
    }

    /** The decompressed content streams of the produced PDF, one string per page. */
    private fun contentStreams(result: DocxToPdfResult): String =
        PdfDocument.open(ByteArraySource(result.bytes)).getOrThrow().use { document ->
            document.pages().joinToString("\n") { page ->
                page.contents().getOrThrow()?.bytes?.toString(Charsets.US_ASCII) ?: ""
            }
        }

    @Test
    fun `a simple document becomes pages with a heading, body text and lists`() {
        val result = convert("simple.docx")
        val pages = render(result)
        val report = result.report

        assertEquals(2, pages.size, "the page break should end the first page")
        assertEquals(2, report.pages)
        assertEquals(1, report.headings)
        assertEquals(6, report.listItems, "three bullets and three numbered items")
        assertEquals(0, report.pictures)

        val first = pages[0]
        assertTrue(first.has("Quarterly Report"), "the heading is missing: ${first.text}")
        assertTrue(first.has("The quick brown fox jumps over the lazy dog."), "body text is missing")
        assertTrue(first.has("This line is centred."))
        assertTrue(first.has("\u2022Milk and bread"), "bullet items are missing: ${first.text}")
        assertTrue(first.has("1.Steps:") && first.has("3.Read the numbers"), "numbered items are missing")
        assertTrue(pages[1].has("Second page"), "the second page is missing its text")

        // The template's Heading 1 style is 14 pt, the body text 11 pt.
        near(14.0, first.run("Quarterly").fontSize, 0.1)
        near(11.0, first.run("quick").fontSize, 0.1)

        // The template's headings use the theme's major font, Calibri, which is
        // not one of the standard fonts: the substitution is reported, not hidden.
        assertTrue(
            report.notes.any { it.contains("Calibri") && it.contains("Helvetica") },
            "the font substitution should be reported: ${report.notes}"
        )
    }

    @Test
    fun `run formatting, alignment and indents reach the PDF`() {
        val result = convert("formatting.docx")
        val page = render(result).single()

        near(8.0, page.run("Eight").fontSize, 0.1)
        near(14.0, page.run("Fourteen").fontSize, 0.1)
        near(24.0, page.run("Twenty-four").fontSize, 0.1)

        // Letter with 1.25 inch side margins (the python-docx template): the
        // text area runs from 90 to 522.
        near(522.0, page.run("margin.").endX, 1.5)

        // A justified line is stretched to the right margin, its last line is not.
        val justified = page.lineOf("justified").maxOf { it.endX }
        near(522.0, justified, 1.5)
        val lastLine = page.lineOf("does.").maxOf { it.endX }
        assertTrue(lastLine < 520.0, "the last line of a justified paragraph must not be stretched: $lastLine")

        // 0.5 in left indent plus 0.25 in first line; 0.75 in with a 0.5 in hanging indent.
        near(90.0 + 36.0 + 18.0, page.run("Left").originX, 1.5)
        near(90.0 + 54.0 - 36.0, page.run("Hanging").originX, 1.5)

        // Character spacing (w:spacing = 60 twips = 3 pt) is a Tc of 3 points.
        // The body font comes from the theme as Cambria, which is substituted
        // by Times-Roman: the widths are that font's.
        val tracked = page.lineOf("spread")
        val natural = StandardFonts.width(StandardFont.TIMES_ROMAN, "spread out letters", 11.0)
        near(natural + 3.0 * "spread out letters".length, tracked.maxOf { it.endX } - tracked.minOf { it.originX })

        assertTrue(page.has("x2 and H2O"), "superscript and subscript text is missing")
        assertTrue(page.has("smalltalk"), "small-caps text is missing")
        assertTrue(page.has("gone"), "struck-out text should still be drawn")
        assertTrue(
            result.report.notes.any { it.contains("small caps") },
            "small caps cannot be drawn and should be reported: ${result.report.notes}"
        )
        assertTrue(
            result.report.notes.any { it.contains("Papyrus") },
            "an unknown font should be named in the report: ${result.report.notes}"
        )
    }

    @Test
    fun `tables keep their page size, cells and grid`() {
        val result = convert("table.docx")
        val pages = render(result)

        assertEquals(1, pages.size, "the tables should fit on one A4 page")
        near(595.28, pages[0].width, 1.0)
        near(841.89, pages[0].height, 1.0)

        val text = pages[0].text
        assertTrue(text.contains("Table of figures"))
        assertTrue(text.contains("Bolts"), "cell text is missing: $text")
        assertTrue(text.contains("144"))
        assertTrue(text.contains("second line in the cell"), "the second cell paragraph is missing")
        assertTrue(text.contains("Merged"), "the merged cell is missing")
        assertTrue(text.contains("After the tables"))

        assertTrue(
            result.report.notes.any { it.contains("wider than the text area") },
            "the six-column table is wider than the text area and should be reported"
        )

        // The grid of the three Table Grid tables is drawn as filled rectangles.
        val content = contentStreams(result)
        assertTrue(
            content.split("re f").size > 20,
            "the table grid lines should be in the content stream"
        )
    }

    @Test
    fun `pictures are embedded and unsupported ones are named`() {
        val result = convert("image.docx")
        val pages = render(result)

        assertEquals(1, pages.size)
        assertEquals(3, result.report.pictures, "three PNGs are placed")
        assertTrue(
            result.report.notes.any { it.contains("BMP") },
            "the unsupported picture should be reported: ${result.report.notes}"
        )
        assertTrue(pages[0].has("End of the picture document."))

        // The two PNG XObjects (the first picture is used twice) reach the file.
        val ascii = String(result.bytes, Charsets.ISO_8859_1)
        assertTrue(ascii.contains("/Subtype /Image"), "no image XObject was written")
        assertTrue(ascii.contains("/FlateDecode"), "the PNG pixels should be deflated")
    }

    @Test
    fun `page size follows the section and a landscape section gets its own pages`() {
        val pages = render(convert("sections.docx"))

        assertEquals(3, pages.size, "one portrait page, then two landscape pages")
        assertTrue(pages[0].height > pages[0].width, "the first section is portrait")
        near(841.89, pages[1].width, 1.0)
        near(595.28, pages[1].height, 1.0)

        assertTrue(pages[0].has("Section one"))
        assertTrue(pages[0].has("A sub-heading") && pages[0].has("A sub-sub-heading"))
        assertTrue(pages[0].has("A paragraph with a manual line break here:"))
        assertTrue(pages[0].has("Ends the portrait section."))

        // The long word does not fit on one line and has to be broken; no
        // characters may be lost while doing that.
        val url = "https://example.invalid/a/very/long/path/without/any/spaces/that/has/to/wrap/and/keeps/" +
            "going/for/quite/a/while/before/it/stops/in/the/middle/of/a/long/word"
        assertTrue(pages[0].has(url), "the long word should be broken, not dropped")
        val pieces = pages[0].runsWith("example.invalid")
        assertTrue(pieces.size >= 1)
        assertTrue(pieces.last().endX > 500.0, "the first part should fill the line")

        assertTrue(pages[1].has("Section two (landscape)"))
        assertTrue(pages[2].has("This paragraph asks for a page break before it"))
        assertTrue(pages[2].has("Landscape body text"))
    }

    @Test
    fun `our own DOCX output converts back to a readable PDF`() {
        val source = PdfDocument.open(ByteArraySource(flowDocumentPdf())).getOrThrow()
        val docx = source.use { PdfToDocx.convert(it, PdfToDocxOptions(mode = DocxMode.FLOW)) }
        val result = DocxToPdf.convert(docx.bytes, sourceName = "round-trip.docx")
        val pages = render(result)

        assertTrue(pages.isNotEmpty())
        assertTrue(result.report.paragraphs > 5, "the round trip lost paragraphs")
        assertTrue(result.report.listItems >= 4, "the lists did not survive the round trip")
        assertEquals(1, result.report.tables)

        val text = pages.joinToString("\n") { it.text }
        assertTrue(text.contains("Quarterly Report"))
        assertTrue(text.contains("Revenue grew in every region"))
        assertTrue(text.contains("Lower operating costs"), "bullet text is missing: $text")
        assertTrue(text.contains("Review the forecast"), "numbered text is missing: $text")
        assertTrue(text.contains("Widgets") && text.contains("24.00"), "table text is missing: $text")
    }

    @Test
    fun `something that is not a package is refused with a clear message`() {
        val error = assertFailsWith<DocxToPdfException> {
            DocxToPdf.convert("this is not a package".toByteArray())
        }
        assertTrue(error.message!!.isNotBlank())
    }

    @Test
    fun `a zip without a document part is refused`() {
        val archive = zip(
            "[Content_Types].xml" to
                """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "_rels/.rels" to
                """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>"""
        )
        val error = assertFailsWith<DocxToPdfException> { DocxToPdf.convert(archive) }
        assertTrue(error.message!!.contains("document"), "unexpected message: ${error.message}")
    }

    @Test
    fun `a document without styles still lays out with the defaults`() {
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>No styles at all</w:t></w:r></w:p>
                <w:sectPr>
                  <w:pgSz w:w="12240" w:h="15840"/>
                  <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"
                           w:header="720" w:footer="720" w:gutter="0"/>
                </w:sectPr>
              </w:body>
            </w:document>"""
        val archive = zip(
            "[Content_Types].xml" to contentTypes("word/document.xml"),
            "_rels/.rels" to relationships(
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>"""
            ),
            "word/document.xml" to document
        )
        val result = DocxToPdf.convert(archive, sourceName = "bare.docx")
        val pages = render(result)

        assertEquals(1, pages.size)
        assertTrue(pages[0].has("No styles at all"))
        near(612.0, pages[0].width)
        near(792.0, pages[0].height)
        near(11.0, pages[0].run("No").fontSize, 0.1)
        assertTrue(result.report.notes.none { it.contains("font") }, "no font should be substituted here")
    }

    // ------------------------------------------------------------------ helpers

    private fun zip(vararg parts: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun contentTypes(vararg overrides: String): String {
        val entries = overrides.joinToString("") { part ->
            """<Override PartName="/$part" ContentType="application/xml"/>"""
        }
        return """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">$entries</Types>"""
    }

    private fun relationships(vararg entries: String): String =
        """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${entries.joinToString("")}</Relationships>"""
}
