package com.pdfpaper.engine.links

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * T36/T61: link annotations, explicit and named destinations, and the textual
 * references found in an academic-style paper.
 */
class LinkResolverTest {

    private fun open(): PdfDocument =
        PdfDocument.open(ByteArraySource(academicPdf())).getOrThrow()

    @Test
    fun `explicit GoTo destination carries page and fit`() {
        val index = LinkResolver(open()).resolve()
        val link = index.linksOnPage(0).first { it.kind == LinkKind.LINK && it.targets.isNotEmpty() }
        val target = link.targets.single()
        assertEquals(1, target.pageIndex)
        assertEquals(LinkFit.FIT_H, target.destination.fit)
        assertEquals(700.0, target.destination.top ?: 0.0, 0.01)
        assertEquals("References", target.title)
        assertTrue(target.previewRect.height > 10.0)
        assertTrue(target.pageFractionTop in 0.0..1.0)
    }

    @Test
    fun `named destination in catalog is resolved`() {
        val index = LinkResolver(open()).resolve()
        val link = index.linksOnPage(0).first {
            it.kind == LinkKind.LINK && it.targets.singleOrNull()?.pageIndex == 2
        }
        assertEquals(LinkFit.XYZ, link.targets.single().destination.fit)
        assertEquals(600.0, link.targets.single().destination.top ?: 0.0, 0.01)
    }

    @Test
    fun `named destination in Names tree resolves its FitR rectangle`() {
        val index = LinkResolver(open()).resolve()
        val link = index.linksOnPage(0).first {
            it.kind == LinkKind.LINK && it.targets.singleOrNull()?.destination?.fit == LinkFit.FIT_RECT
        }
        val target = link.targets.single()
        assertEquals(2, target.pageIndex)
        assertNotNull(target.destination.rect)
        assertEquals(50.0, target.destination.rect!!.left, 0.01)
        assertEquals(700.0, target.destination.rect!!.top, 0.01)
    }

    @Test
    fun `URI and GoToR links stay external`() {
        val index = LinkResolver(open()).resolve()
        val uri = index.linksOnPage(0).first { it.kind == LinkKind.URI }
        assertEquals("https://example.com/paper", uri.uri)
        assertFalse(uri.isResolved)

        val file = index.linksOnPage(0).first { it.kind == LinkKind.FILE }
        assertEquals("other.pdf", file.file)
        assertTrue(file.note!!.contains("another file"))
    }

    @Test
    fun `bracket citations expand ranges and find bibliography entries`() {
        val index = LinkResolver(open()).resolve()
        val citation = index.linksOnPage(0).first { it.text.contains("[3, 5-7]") }
        assertEquals(4, citation.targets.size)
        assertTrue(citation.targets.all { it.pageIndex == 1 })
        assertTrue(citation.targets[0].copyText.contains("Smith"))
        assertTrue(citation.targets[0].title.contains("[3]"))
        assertNull(citation.note)
    }

    @Test
    fun `author year reference resolves to the matching bibliography entry`() {
        val index = LinkResolver(open()).resolve()
        val citation = index.linksOnPage(0).first { it.text.contains("Smith, 2019") }
        val target = citation.targets.single()
        assertEquals(1, target.pageIndex)
        assertTrue(target.copyText.contains("Smith"))
        assertTrue(target.copyText.contains("2019"))
    }

    @Test
    fun `figure table section and page references find their targets`() {
        val index = LinkResolver(open()).resolve()
        val links = index.linksOnPage(0)

        val figure = links.first { it.kind == LinkKind.FIGURE }
        assertEquals(2, figure.targets.single().pageIndex)
        assertTrue(figure.targets.single().title.startsWith("Figure 2.1"))

        val table = links.first { it.kind == LinkKind.TABLE }
        assertEquals(2, table.targets.single().pageIndex)
        assertTrue(table.targets.single().title.startsWith("Table 4"))

        val section = links.first { it.kind == LinkKind.SECTION }
        assertEquals(3, section.targets.single().pageIndex)
        assertTrue(section.targets.single().copyText.contains("Methods"))

        val page = links.first { it.kind == LinkKind.PAGE }
        assertEquals(3, page.targets.single().pageIndex)
        assertEquals("Page 4", page.targets.single().title)
    }

    @Test
    fun `missing reference is reported clearly and never crashes`() {
        val index = LinkResolver(open()).resolve()
        val missing = index.linksOnPage(0).first { it.text == "[9]" }
        assertTrue(missing.targets.isEmpty())
        assertTrue(missing.note!!.contains("not found"))
    }

    @Test
    fun `hit test prefers link annotations and finds references`() {
        val index = LinkResolver(open()).resolve()
        val link = index.linksOnPage(0).first { it.kind == LinkKind.URI }
        assertNotNull(index.hitTest(0, link.rect.centerX, link.rect.centerY))

        val citation = index.linksOnPage(0).first { it.text.contains("[3, 5-7]") }
        val hit = index.hitTest(0, citation.rect.centerX, citation.rect.centerY)
        assertEquals(LinkKind.CITATION, hit?.kind)
    }

    @Test
    fun `rotated page preview top stays inside the page`() {
        val index = LinkResolver(open()).resolve()
        for (target in index.targets) {
            assertTrue("fraction for ${target.title}", target.pageFractionTop in 0.0..1.0)
            assertFalse("preview for ${target.title}", target.previewRect.isEmpty)
        }
    }

    // ------------------------------------------------------------------ fixture

    private fun academicPdf(): ByteArray {
        val page0 = """
            1 Introduction
            See [3, 5-7] and (Smith, 2019) for details.
            Figure 2.1 and Table 4 are covered in Section 3.2.
            The appendix is on Page 4. Missing [9].
        """.trimIndent()
        val page1 = """
            References
            [3] Smith, J. (2019). A Study of PDF Links. Journal of Documents.
            [5] Jones, A. (2020). Destinations.
            [6] Brown, B. (2021). Link Previews.
            [7] White, C. (2022). Back Stacks.
        """.trimIndent()
        val page2 = """
            Figure 2.1: A linked figure.
            The figure is rendered at a small size and cached.
            Table 4: Results of the link test.
            Name        Value
            Links       12
            Previews    12
        """.trimIndent()
        val page3 = """
            3.2 Methods
            We resolve links in reading order and cache small previews.
            The preview card shows the target without moving the page.
        """.trimIndent()

        val objects = listOf(
            TestObj(
                1, 0,
                "<< /Type /Catalog /Pages 2 0 R /Outlines 12 0 R " +
                    "/Dests << /chap2 [5 0 R /XYZ 72 600 1] >> " +
                    "/Names << /Dests 14 0 R >> >>"
            ),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R 6 0 R] /Count 4 >>"),
            TestObj(3, 0, pageObject(8, annotations = true)),
            TestObj(4, 0, pageObject(9, annotations = false)),
            TestObj(5, 0, pageObject(10, annotations = false)),
            TestObj(6, 0, pageObject(11, annotations = false)),
            TestObj(
                7, 0,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
            ),
            TestObj(8, 0, contentStream(page0)),
            TestObj(9, 0, contentStream(page1)),
            TestObj(10, 0, contentStream(page2)),
            TestObj(11, 0, contentStream(page3)),
            TestObj(12, 0, "<< /Type /Outlines /First 13 0 R /Last 13 0 R /Count 1 >>"),
            TestObj(
                13, 0,
                "<< /Title (References) /Parent 12 0 R /Dest [4 0 R /Fit] >>"
            ),
            TestObj(14, 0, "<< /Names [(chapter3) [5 0 R /FitR 50 500 550 700]] >>")
        )
        return buildTestPdf(objects)
    }

    private fun pageObject(contentObj: Int, annotations: Boolean): String {
        val annots = if (!annotations) "" else """
            /Annots [
              << /Type /Annot /Subtype /Link /Rect [350 300 580 320] /Dest [4 0 R /FitH 700] >>
              << /Type /Annot /Subtype /Link /Rect [350 330 580 350] /A << /S /GoTo /D (chap2) >> >>
              << /Type /Annot /Subtype /Link /Rect [350 360 580 380] /Dest (chapter3) >>
              << /Type /Annot /Subtype /Link /Rect [350 390 580 410] /A << /S /URI /URI (https://example.com/paper) >> >>
              << /Type /Annot /Subtype /Link /Rect [350 420 580 440] /A << /S /GoToR /F (other.pdf) /D [0 /Fit] >> >>
            ]
        """.trimIndent()
        return "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << /Font << /F1 7 0 R >> >> /Contents $contentObj 0 R $annots >>"
    }

    private fun contentStream(text: String): String {
        val lines = text.lines()
        val ops = StringBuilder()
        var y = 740.0
        for (line in lines) {
            if (line.isBlank()) {
                y -= 12
                continue
            }
            ops.append("BT /F1 12 Tf 72 ")
                .append(y.toInt())
                .append(" Td (")
                .append(escape(line))
                .append(") Tj ET\n")
            y -= 18
        }
        val body = ops.toString()
        val length = body.toByteArray(StandardCharsets.US_ASCII).size
        return "<< /Length $length >>\nstream\n$body\nendstream"
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
}
