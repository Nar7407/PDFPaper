package com.pdfpaper.engine.create

import com.pdfpaper.engine.edit.Standard14
import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.page.page
import com.pdfpaper.engine.page.pageCount
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PdfBuilderTest {

    @Test
    fun a4UsesIsoDimensionsInBothOrientations() {
        val portrait = PdfBuilder(PageSize.A4).apply {
            addPage().drawText("portrait", 72.0, 72.0)
        }.build()
        openPdf(portrait).use { document ->
            assertEquals(1, document.pageCount())
            val box = mediaBox(pageOf(document, 0))
            assertEqualsList(listOf(0.0, 0.0, 595.276, 841.89), box)
        }

        val landscape = PdfBuilder(PageSize.A4, PageOrientation.LANDSCAPE).apply {
            addPage().drawText("landscape", 72.0, 72.0)
        }.build()
        openPdf(landscape).use { document ->
            val box = mediaBox(pageOf(document, 0))
            assertEqualsList(listOf(0.0, 0.0, 841.89, 595.276), box)
        }
    }

    @Test
    fun letterAndCustomSizesWork() {
        val letter = PdfBuilder(PageSize.LETTER).apply { addPage() }.build()
        openPdf(letter).use { document ->
            assertEqualsList(listOf(0.0, 0.0, 612.0, 792.0), mediaBox(pageOf(document, 0)))
        }

        val custom = PdfBuilder(PageSize.custom(300.0, 400.0), PageOrientation.LANDSCAPE).apply {
            addPage()
        }.build()
        openPdf(custom).use { document ->
            assertEqualsList(listOf(0.0, 0.0, 400.0, 300.0), mediaBox(pageOf(document, 0)))
        }
    }

    @Test
    fun pagesCanOverrideTheDocumentSize() {
        val bytes = PdfBuilder(PageSize.A4).apply {
            addPage()
            addPage(PageSize.LETTER, PageOrientation.LANDSCAPE)
            addPage(PageSize.custom(100.0, 200.0))
        }.build()

        openPdf(bytes).use { document ->
            assertEquals(3, document.pageCount())
            assertEqualsList(listOf(0.0, 0.0, 595.276, 841.89), mediaBox(pageOf(document, 0)))
            assertEqualsList(listOf(0.0, 0.0, 792.0, 612.0), mediaBox(pageOf(document, 1)))
            assertEqualsList(listOf(0.0, 0.0, 100.0, 200.0), mediaBox(pageOf(document, 2)))
        }
    }

    @Test
    fun parsesPresetAndCustomSizes() {
        val a4 = PageSize.parse("a4") ?: error("A4 did not parse")
        assertClose(595.276, a4.width)
        assertClose(841.89, a4.height)

        val letter = PageSize.parse("Letter") ?: error("Letter did not parse")
        assertEqualsList(listOf(612.0, 792.0), listOf(letter.width, letter.height))

        val custom = PageSize.parse("300x400") ?: error("custom size did not parse")
        assertEqualsList(listOf(300.0, 400.0), listOf(custom.width, custom.height))

        assertEquals(null, PageSize.parse("A3"))
        assertEquals(null, PageSize.parse("0x400"))
        assertEquals(null, PageSize.parse("not-a-size"))
    }

    @Test
    fun drawsTextLinesAndRectanglesAndSetsMetadata() {
        val bytes = PdfBuilder(PageSize.A4, metadata = PdfMetadata(
            title = "Builder test",
            author = "Tester",
            subject = "T21",
            keywords = "pdf, builder"
        )).apply {
            val page = addPage()
            page.drawText(
                "Hello T21",
                72.0,
                100.0,
                18.0,
                StandardFont.HELVETICA_BOLD,
                PdfColor(0.8, 0.1, 0.1)
            )
            page.drawLine(10.0, 20.0, 300.0, 20.0, 2.0)
            page.drawRect(
                50.0,
                50.0,
                100.0,
                40.0,
                fill = PdfColor(0.0, 0.5, 0.0),
                stroke = PdfColor.BLACK,
                strokeWidth = 2.0
            )
        }.build()

        openPdf(bytes).use { document ->
            val page = pageOf(document, 0)
            assertEquals("Hello T21", textOf(page))

            val content = contentOf(page)
            assertContains(content, "BT")
            assertContains(content, "/F1 18 Tf")
            assertContains(content, "0.8 0.1 0.1 rg")
            assertContains(content, " Tj")
            assertContains(content, "2 w 1 J 1 j")
            assertContains(content, " m ")
            assertContains(content, " l S")
            assertContains(content, " re")
            assertContains(content, "B\n")

            val resources = page.dict["Resources"] as com.pdfpaper.engine.model.PdfDict
            val fonts = resources["Font"] as com.pdfpaper.engine.model.PdfDict
            val font = fonts["F1"] as com.pdfpaper.engine.model.PdfRef
            val fontDict = page.document.resolve(font) as com.pdfpaper.engine.model.PdfDict
            assertEquals("Helvetica-Bold", (fontDict["BaseFont"] as com.pdfpaper.engine.model.PdfName).name)
            assertEquals(224L, (fontDict["Widths"] as com.pdfpaper.engine.model.PdfArray).items.size.toLong())
        }

        val raw = bytes.toString(Charsets.ISO_8859_1)
        assertContains(raw, "/Title (Builder test)")
        assertContains(raw, "/Author (Tester)")
        assertContains(raw, "/Subject (T21)")
        assertContains(raw, "/Keywords (pdf, builder)")
        assertContains(raw, "/Producer (PDFPaper)")
    }

    @Test
    fun textWidthUsesTheStandardFontMetrics() {
        val page = PdfBuilder().addPage()
        val expected = Standard14.width(StandardFont.TIMES_ROMAN, "Wrapping", 11.0)
        assertClose(expected, page.textWidth("Wrapping", 11.0, StandardFont.TIMES_ROMAN), 1e-9)
        assertTrue(expected > 0.0)
    }

    @Test
    fun blankPageHasNoTextAndStillOpens() {
        val bytes = PdfBuilder(PageSize.A4).apply { addPage() }.build()
        openPdf(bytes).use { document ->
            assertEquals(1, document.pageCount())
            assertEquals(emptyList(), textRuns(pageOf(document, 0)))
        }
    }

    @Test
    fun rejectsInvalidInput() {
        assertFailsWith<IllegalArgumentException> { PdfBuilder().build() }
        assertFailsWith<IllegalArgumentException> {
            PdfBuilder().addPage().drawText("two\nlines", 0.0, 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            PdfBuilder().addPage().drawText("text", 0.0, 0.0, size = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            PdfBuilder().addPage().drawRect(0.0, 0.0, 10.0, 10.0, fill = null, stroke = null)
        }
        assertFailsWith<IllegalArgumentException> {
            PdfBuilder().addPage().drawImage(
                com.pdfpaper.engine.edit.EditImages.decode(imageBytes("png", 4, 4)).getOrThrow(),
                0.0, 0.0, 0.0, 4.0
            )
        }
    }
}
