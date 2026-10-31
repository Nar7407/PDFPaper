package com.pdfpaper.engine.speech

import com.pdfpaper.engine.create.PageOrientation
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.create.PdfMetadata
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T34: the chapter list is the document outline when it has one (ISO 32000-1
 * §12.3.3), and the T33 level 1-2 headings when it does not. The reader's
 * "next chapter" and the end-of-chapter sleep timer both walk this list.
 */
class SpeechChaptersTest {

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    /** A two-page document whose outline has the given items. */
    private fun outlinePdf(vararg items: Pair<String, String>): ByteArray {
        // items: title to destination string, e.g. "One" to "[3 0 R /XYZ 0 700 0]"
        val objects = mutableListOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R /Outlines 6 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>"),
            TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>"),
            TestObj(4, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>"),
            TestObj(6, 0, "<< /Type /Outlines /First 7 0 R /Last ${7 + items.size - 1} 0 R /Count ${items.size} >>")
        )
        items.forEachIndexed { index, (title, dest) ->
            val num = 7 + index
            val next = if (index < items.size - 1) " /Next ${num + 1} 0 R" else ""
            val prev = if (index > 0) " /Prev ${num - 1} 0 R" else ""
            objects += TestObj(
                num, 0,
                "<< /Title ($title) /Parent 6 0 R$next$prev /Dest $dest >>"
            )
        }
        return buildTestPdf(objects)
    }

    private fun headingPdf(): ByteArray {
        val pdf = PdfBuilder(PageSize.A4, PageOrientation.PORTRAIT, PdfMetadata())
        val page = pdf.addPage()
        page.drawText("Chapter One", 60.0, 100.0, 18.0)
        for (i in 0 until 3) page.drawText("Body text after the first heading of the page", 60.0, 130.0 + i * 14.0, 10.0)
        page.drawText("Chapter Two", 60.0, 220.0, 18.0)
        for (i in 0 until 4) page.drawText("The body text of the page runs down the column", 60.0, 250.0 + i * 14.0, 10.0)
        return pdf.build()
    }

    private fun speechPage(vararg headings: SpeechHeading): SpeechPage = SpeechPage(
        pageIndex = 0,
        paragraphs = listOf(
            paragraph(0, 0.10),
            paragraph(1, 0.30),
            paragraph(2, 0.50)
        ),
        scanned = false,
        headings = headings.toList()
    )

    private fun paragraph(index: Int, fraction: Double): SpeechParagraph = SpeechParagraph(
        pageIndex = 0,
        index = index,
        text = "Paragraph $index",
        words = listOf(
            SpeechWord(
                "Paragraph", 0, 9,
                listOf(com.pdfpaper.engine.text.TextRect(0.0, 700.0, 100.0, 712.0))
            )
        ),
        headingLevel = 0,
        bounds = PdfRect(0.0, 700.0, 500.0, 712.0),
        fraction = fraction
    )

    @Test
    fun outlineChaptersCarryTitlePageAndFraction() {
        val pdf = outlinePdf(
            "Introduction" to "[3 0 R /XYZ 0 700 0]",
            "Method" to "[4 0 R /Fit]"
        )
        open(pdf).use { document ->
            val chapters = SpeechExtractor.chapters(document)
            assertEquals(listOf("Introduction", "Method"), chapters.map { it.title })
            assertEquals(listOf(0, 1), chapters.map { it.pageIndex })
            assertEquals(listOf(0, 0), chapters.map { it.paragraphIndex })
            assertEquals(ChapterSource.OUTLINE, chapters[0].source)
            assertEquals(ChapterSource.OUTLINE, chapters[1].source)
            // 700 from the bottom of a 792 pt page is 92 pt from the top.
            assertEquals(92.0 / 792.0, chapters[0].fraction, 0.01)
            // A /Fit destination has no top, so the chapter starts at the top.
            assertEquals(0.0, chapters[1].fraction, 0.001)
        }
    }

    @Test
    fun anOutlineChapterStartsAtItsDestinationTop() {
        val pdf = outlinePdf(
            "Near the top" to "[3 0 R /XYZ 0 700 0]",
            "Near the bottom" to "[3 0 R /XYZ 0 100 0]"
        )
        open(pdf).use { document ->
            val chapters = SpeechExtractor.chapters(document)
            assertEquals(2, chapters.size)
            assertTrue(chapters[0].fraction < 0.2, "${chapters[0].fraction}")
            assertTrue(chapters[1].fraction > 0.8, "${chapters[1].fraction}")
            assertTrue(chapters.all { it.fraction in 0.0..1.0 })
        }
    }

    @Test
    fun aDocumentWithoutAnOutlineHasNoOutlineChapters() {
        val pdf = PdfBuilder(PageSize.A4, PageOrientation.PORTRAIT, PdfMetadata())
        pdf.addPage().drawText("A page without an outline", 60.0, 100.0, 12.0)
        open(pdf.build()).use { document ->
            assertTrue(SpeechExtractor.chapters(document).isEmpty())
        }
    }

    @Test
    fun headingsBecomeChaptersWhenThereIsNoOutline() {
        open(headingPdf()).use { document ->
            val page = SpeechExtractor.extract(document.pages().first()).getOrThrow()
            val chapters = SpeechExtractor.mergeHeadings(emptyList(), page)
            assertEquals(listOf("Chapter One", "Chapter Two"), chapters.map { it.title })
            assertEquals(listOf(ChapterSource.HEADING, ChapterSource.HEADING), chapters.map { it.source })
            assertEquals(0, chapters[0].pageIndex)
            val first = page.paragraphs[chapters[0].paragraphIndex]
            assertEquals(first.fraction, chapters[0].fraction, 0.0001)
            assertEquals(0, chapters[0].paragraphIndex)
            assertTrue(chapters[1].paragraphIndex > chapters[0].paragraphIndex)
        }
    }

    @Test
    fun mergeHeadingsSortsAndSkipsDuplicates() {
        val existing = listOf(
            SpeechChapter("Later", 0, 2, 0.5, ChapterSource.HEADING)
        )
        val page = speechPage(
            SpeechHeading("Earlier", 0, 1, 1),
            SpeechHeading("Later", 0, 2, 2)
        )
        val merged = SpeechExtractor.mergeHeadings(existing, page)
        assertEquals(listOf("Earlier", "Later"), merged.map { it.title })
        assertEquals(listOf(1, 2), merged.map { it.paragraphIndex })
        assertEquals(2, merged.size)
    }

    @Test
    fun levelThreeHeadingsAreNotChapters() {
        val page = speechPage(
            SpeechHeading("A sub-sub-heading", 0, 1, 3)
        )
        assertTrue(SpeechExtractor.mergeHeadings(emptyList(), page).isEmpty())
    }

    @Test
    fun anOutlineEntryOnAMissingPageIsSkipped() {
        val pdf = outlinePdf(
            "Gone" to "[99 0 R /Fit]",
            "Here" to "[4 0 R /Fit]"
        )
        open(pdf).use { document ->
            val chapters = SpeechExtractor.chapters(document)
            assertEquals(listOf("Here"), chapters.map { it.title })
            assertEquals(listOf(1), chapters.map { it.pageIndex })
        }
    }
}
