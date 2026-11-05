package com.pdfpaper.convert

import com.pdfpaper.convert.docx.DocxMode
import com.pdfpaper.convert.ooxml.OpcPackage
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PDF→DOCX end-to-end: the converter's own model is checked through the
 * written package, which is what Word actually opens.
 */
class PdfToDocxTest {

    private fun document(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun convert(bytes: ByteArray, mode: DocxMode = DocxMode.FLOW): PdfToDocxResult =
        document(bytes).use { PdfToDocx.convert(it, PdfToDocxOptions(mode = mode)) }

    private fun part(result: PdfToDocxResult, name: String): String =
        String(OpcPackage.read(result.bytes).getValue(name), Charsets.UTF_8)

    private fun documentXml(result: PdfToDocxResult): String = part(result, "word/document.xml")

    /** The text of every `w:p`, in document order, tables included. */
    private fun paragraphTexts(xml: String): List<String> {
        val texts = ArrayList<String>()
        val current = StringBuilder()
        for (match in Regex("<w:t(?: [^>]*)?>(.*?)</w:t>|</w:p>").findAll(xml)) {
            if (match.value == "</w:p>") {
                texts += current.toString()
                current.setLength(0)
            } else {
                current.append(match.groupValues[1])
            }
        }
        return texts
    }

    private fun countOf(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    @Test
    fun `a text page becomes paragraphs, a heading, lists and a table`() {
        val result = convert(flowDocumentPdf())
        assertEquals(emptyList(), DocxValidator.validate(result.bytes))
        val xml = documentXml(result)

        // The two-line first paragraph is one paragraph with a single space.
        assertTrue(
            paragraphTexts(xml).any {
                it == "This document summarises the results of the quarter and the outlook for the next one."
            },
            xml
        )
        assertEquals(2, result.report.headings, "the 24 pt title and the 14 pt heading")
        assertTrue(xml.contains("<w:pStyle w:val=\"Heading1\"/>"), xml)
        assertTrue(xml.contains("<w:pStyle w:val=\"Heading2\"/>"), xml)
        assertEquals(4, result.report.listItems)
        assertTrue(xml.contains("<w:numPr>"), xml)
        assertEquals(1, result.report.tables)
        assertTrue(xml.contains("<w:tbl>"), xml)
        assertTrue(xml.contains("<w:gridCol"), xml)
        assertTrue(xml.contains("Widgets"), xml)
        // The page-number footer is gone.
        assertEquals(1, result.report.runningHeadsRemoved)
        assertFalse(xml.contains("Page 1"), xml)
    }

    @Test
    fun `one page is one section`() {
        val result = convert(twoPageDocxPdf())
        assertEquals(emptyList(), DocxValidator.validate(result.bytes))
        assertEquals(2, result.report.pageCount)
        val xml = documentXml(result)
        // One section per page; the last one lives at the end of w:body.
        assertEquals(2, countOf(xml, "<w:sectPr>"))
        assertTrue(xml.contains("First page body"), xml)
        assertTrue(xml.contains("Second page body"), xml)
        assertTrue(xml.contains("<w:type w:val=\"nextPage\"/>"), xml)
    }

    @Test
    fun `two columns become a two column section`() {
        val result = convert(twoColumnPdf())
        assertEquals(emptyList(), DocxValidator.validate(result.bytes))
        val xml = documentXml(result)
        assertTrue(xml.contains("<w:cols w:num=\"2\""), xml)
        assertTrue(xml.contains("Left column line 0"), xml)
        assertTrue(xml.contains("Right column line 5"), xml)
    }

    @Test
    fun `running heads are dropped from every page`() {
        val result = convert(runningHeadPdf())
        assertEquals(emptyList(), DocxValidator.validate(result.bytes))
        assertEquals(3, result.report.runningHeadsRemoved)
        val xml = documentXml(result)
        assertTrue(xml.contains("Body of page 2"), xml)
        assertFalse(xml.contains(">Page "), xml)
    }

    @Test
    fun `images become inline pictures in flow mode`() {
        val result = convert(imagePagePdf())
        assertEquals(emptyList(), DocxValidator.validate(result.bytes))
        assertEquals(1, result.report.images)
        val parts = OpcPackage.read(result.bytes)
        assertTrue(parts.keys.any { it.startsWith("word/media/image1.") }, parts.keys.toString())
        assertTrue(parts.containsKey("word/_rels/document.xml.rels"))
        val xml = documentXml(result)
        assertTrue(xml.contains("<wp:inline "), xml)
        assertTrue(xml.contains("r:embed=\"rId4\""), xml)
    }

    @Test
    fun `exact mode places every line in a frame and anchors pictures`() {
        val result = convert(textAndVectorPdf("Hello world"), DocxMode.EXACT)
        assertEquals(emptyList(), DocxValidator.validate(result.bytes))
        val xml = documentXml(result)
        assertTrue(xml.contains("<w:framePr "), xml)
        assertTrue(xml.contains("w:hAnchor=\"page\""), xml)
        assertTrue(xml.contains("Hello world"), xml)
        // Vector art is folded into a page picture behind the text.
        val parts = OpcPackage.read(result.bytes)
        assertTrue(parts.containsKey("word/media/image1.png"), parts.keys.toString())
        assertTrue(xml.contains("behindDoc=\"1\""), xml)
    }

    @Test
    fun `exact mode keeps the image position`() {
        val result = convert(imagePagePdf(), DocxMode.EXACT)
        assertEquals(emptyList(), DocxValidator.validate(result.bytes))
        val xml = documentXml(result)
        assertTrue(xml.contains("<wp:anchor "), xml)
        // 50 pt from the left, 30 pt from the top of a 100 pt tall page.
        assertTrue(xml.contains(">635000</wp:posOffset>"), xml)
        assertTrue(xml.contains(">381000</wp:posOffset>"), xml)
    }

    @Test
    fun `conversion is deterministic`() {
        val first = convert(flowDocumentPdf()).bytes
        val second = convert(flowDocumentPdf()).bytes
        assertEquals(first.toList(), second.toList())
    }

    @Test
    fun `max pages stops early and reports it`() {
        val result = document(runningHeadPdf()).use {
            PdfToDocx.convert(it, PdfToDocxOptions(maxPages = 2))
        }
        assertEquals(2, result.report.pageCount)
        assertTrue(result.report.notes.any { it.contains("first 2") }, result.report.notes.toString())
    }

    @Test
    fun `the report names the mode and its limits`() {
        val flow = convert(flowDocumentPdf()).report
        assertTrue(flow.mode.startsWith("flow"), flow.mode)
        assertTrue(flow.toText().contains("black"), flow.toText())
        assertTrue(flow.toText().contains("headings"), flow.toText())

        val exact = convert(textAndVectorPdf(), DocxMode.EXACT).report
        assertTrue(exact.mode.startsWith("exact"), exact.mode)
        assertTrue(exact.toText().contains("frame"), exact.toText())
    }

    @Test
    fun `an empty PDF still yields a valid document`() {
        val result = document(pagesPdf(listOf(""))).use { PdfToDocx.convert(it) }
        assertEquals(emptyList(), DocxValidator.validate(result.bytes))
        assertEquals(1, result.report.pageCount)
    }
}
