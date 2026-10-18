package com.pdfpaper.convert

import com.pdfpaper.convert.ooxml.OpcPackage
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfToPptxTest {

    private fun document(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    @Test
    fun `text becomes a text box and vectors are reported as skipped`() {
        val result = document(textAndVectorPdf("Hello world")).use { PdfToPptx.convert(it) }
        assertEquals(emptyList(), PptxValidator.validate(result.bytes))

        val report = result.report
        assertEquals(1, report.pageCount)
        assertTrue(report.textBoxes >= 1, "expected a text box")
        assertEquals(0, report.pictures)
        assertEquals(1, report.vectorFills)
        assertTrue(report.toText().contains("vector graphics are not converted"))

        val slide = String(OpcPackage.read(result.bytes).getValue("ppt/slides/slide1.xml"), Charsets.UTF_8)
        assertTrue(slide.contains("Hello world"), slide)
    }

    @Test
    fun `page size comes from the media box`() {
        val result = document(textAndVectorPdf()).use { PdfToPptx.convert(it) }
        val presentation = String(
            OpcPackage.read(result.bytes).getValue("ppt/presentation.xml"), Charsets.UTF_8
        )
        // 200x100 pt.
        assertTrue(presentation.contains("cx=\"2540000\" cy=\"1270000\""), presentation)
    }

    @Test
    fun `images become pictures with a media part`() {
        val result = document(imagePagePdf()).use { PdfToPptx.convert(it) }
        assertEquals(emptyList(), PptxValidator.validate(result.bytes))
        assertEquals(1, result.report.pictures)
        val parts = OpcPackage.read(result.bytes)
        assertTrue(parts.keys.any { it.startsWith("ppt/media/image1.") }, parts.keys.toString())
        assertTrue(parts.keys.contains("ppt/slides/_rels/slide1.xml.rels"))
    }

    @Test
    fun `one slide per page`() {
        val result = document(twoPagePdf()).use { PdfToPptx.convert(it) }
        assertEquals(2, result.report.pageCount)
        val parts = OpcPackage.read(result.bytes)
        assertTrue(parts.containsKey("ppt/slides/slide1.xml"))
        assertTrue(parts.containsKey("ppt/slides/slide2.xml"))
    }

    @Test
    fun `max-pages stops early and says so`() {
        val result = document(twoPagePdf()).use { PdfToPptx.convert(it, PdfToPptxOptions(maxPages = 1)) }
        assertEquals(1, result.report.pageCount)
        assertTrue(result.report.toText().contains("only the first 1"))
    }

    @Test
    fun `fidelity mode renders a background picture`() {
        val result = document(imagePagePdf()).use {
            PdfToPptx.convert(it, PdfToPptxOptions(fidelity = true, dpi = 72.0))
        }
        assertEquals(emptyList(), PptxValidator.validate(result.bytes))
        assertTrue(result.report.mode.startsWith("fidelity"))
        assertTrue(result.report.pictures >= 1)
        val slide = String(OpcPackage.read(result.bytes).getValue("ppt/slides/slide1.xml"), Charsets.UTF_8)
        // The background picture covers the whole slide.
        assertTrue(slide.contains("<p:pic>"), slide)
        assertTrue(slide.contains("<a:off x=\"0\" y=\"0\"/>"), slide)
    }

    @Test
    fun `report mentions the text colour limitation`() {
        val result = document(textAndVectorPdf()).use { PdfToPptx.convert(it) }
        assertTrue(result.report.toText().contains("uses black"))
    }
}
