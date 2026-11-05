package com.pdfpaper.engine.outline

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T34: the document outline is read once per document and gives the reader's
 * chapter list a title, a page and (when the destination says so) a top edge
 * (ISO 32000-1 §12.3.3 for the outline, §12.3.2 for destinations).
 */
class DocumentOutlineTest {

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    /** A two-page document whose catalogue carries [extra]. */
    private fun bytes(
        extra: String = "",
        objects: List<TestObj> = emptyList()
    ): ByteArray = buildTestPdf(
        listOf(
            TestObj(
                1, 0,
                "<< /Type /Catalog /Pages 2 0 R" + extra + " >>"
            ),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>"),
            TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>"),
            TestObj(4, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>")
        ) + objects
    )

    @Test
    fun explicitXyzDestinationGivesPageAndTop() {
        val pdf = bytes(
            extra = " /Outlines 6 0 R",
            objects = listOf(
                TestObj(6, 0, "<< /Type /Outlines /First 7 0 R /Last 7 0 R /Count 1 >>"),
                TestObj(7, 0, "<< /Title (Introduction) /Parent 6 0 R /Dest [3 0 R /XYZ 0 700 0] >>")
            )
        )
        open(pdf).use { document ->
            val entries = DocumentOutline.read(document)
            assertEquals(1, entries.size)
            assertEquals("Introduction", entries[0].title)
            assertEquals(0, entries[0].pageIndex)
            assertEquals(700.0, entries[0].top!!, 0.001)
            assertEquals(0, entries[0].depth)
        }
    }

    @Test
    fun goToActionIsResolved() {
        val pdf = bytes(
            extra = " /Outlines 6 0 R",
            objects = listOf(
                TestObj(6, 0, "<< /Type /Outlines /First 7 0 R /Last 7 0 R /Count 1 >>"),
                TestObj(
                    7, 0,
                    "<< /Title (Method) /Parent 6 0 R " +
                        "/A << /S /GoTo /D [4 0 R /Fit] >> >>"
                )
            )
        )
        open(pdf).use { document ->
            val entries = DocumentOutline.read(document)
            assertEquals(1, entries.size)
            assertEquals("Method", entries[0].title)
            assertEquals(1, entries[0].pageIndex)
            assertEquals(null, entries[0].top)
        }
    }

    @Test
    fun namedDestinationInCatalogDestsIsResolved() {
        val pdf = bytes(
            extra = " /Outlines 6 0 R /Dests 8 0 R",
            objects = listOf(
                TestObj(6, 0, "<< /Type /Outlines /First 7 0 R /Last 7 0 R /Count 1 >>"),
                TestObj(7, 0, "<< /Title (Results) /Parent 6 0 R /Dest (res) >>"),
                TestObj(8, 0, "<< /res [4 0 R /XYZ 0 500 0] >>")
            )
        )
        open(pdf).use { document ->
            val entries = DocumentOutline.read(document)
            assertEquals(1, entries.size)
            assertEquals(1, entries[0].pageIndex)
            assertEquals(500.0, entries[0].top!!, 0.001)
        }
    }

    @Test
    fun namedDestinationInNamesTreeIsResolved() {
        val pdf = bytes(
            extra = " /Outlines 6 0 R /Names 9 0 R",
            objects = listOf(
                TestObj(6, 0, "<< /Type /Outlines /First 7 0 R /Last 7 0 R /Count 1 >>"),
                TestObj(7, 0, "<< /Title (Appendix) /Parent 6 0 R /Dest (app) >>"),
                TestObj(9, 0, "<< /Dests 10 0 R >>"),
                TestObj(10, 0, "<< /Names [(app) [3 0 R /XYZ 0 300 0]] >>")
            )
        )
        open(pdf).use { document ->
            val entries = DocumentOutline.read(document)
            assertEquals(1, entries.size)
            assertEquals("Appendix", entries[0].title)
            assertEquals(0, entries[0].pageIndex)
            assertEquals(300.0, entries[0].top!!, 0.001)
        }
    }

    @Test
    fun nestedItemsCarryTheirDepth() {
        val pdf = bytes(
            extra = " /Outlines 6 0 R",
            objects = listOf(
                TestObj(6, 0, "<< /Type /Outlines /First 7 0 R /Last 7 0 R /Count 2 >>"),
                TestObj(
                    7, 0,
                    "<< /Title (Part one) /Parent 6 0 R /First 8 0 R /Last 8 0 R " +
                        "/Dest [3 0 R /Fit] >>"
                ),
                TestObj(8, 0, "<< /Title (Section one) /Parent 7 0 R /Dest [4 0 R /Fit] >>")
            )
        )
        open(pdf).use { document ->
            val entries = DocumentOutline.read(document)
            assertEquals(listOf("Part one", "Section one"), entries.map { it.title })
            assertEquals(listOf(0, 1), entries.map { it.depth })
            assertEquals(listOf(0, 1), entries.map { it.pageIndex })
        }
    }

    @Test
    fun itemsComeInDocumentOrder() {
        val pdf = bytes(
            extra = " /Outlines 6 0 R",
            objects = listOf(
                TestObj(6, 0, "<< /Type /Outlines /First 7 0 R /Last 9 0 R /Count 3 >>"),
                TestObj(7, 0, "<< /Title (A) /Parent 6 0 R /Next 8 0 R /Dest [3 0 R /Fit] >>"),
                TestObj(
                    8, 0,
                    "<< /Title (B) /Parent 6 0 R /Prev 7 0 R /Next 9 0 R " +
                        "/First 10 0 R /Last 10 0 R /Dest [4 0 R /Fit] >>"
                ),
                TestObj(9, 0, "<< /Title (C) /Parent 6 0 R /Prev 8 0 R /Dest [3 0 R /Fit] >>"),
                TestObj(10, 0, "<< /Title (B1) /Parent 8 0 R /Dest [4 0 R /Fit] >>")
            )
        )
        open(pdf).use { document ->
            val entries = DocumentOutline.read(document)
            assertEquals(listOf("A", "B", "B1", "C"), entries.map { it.title })
            assertEquals(listOf(0, 0, 1, 0), entries.map { it.depth })
        }
    }

    @Test
    fun anEntryWithAMissingPageIsSkipped() {
        val pdf = bytes(
            extra = " /Outlines 6 0 R",
            objects = listOf(
                TestObj(6, 0, "<< /Type /Outlines /First 7 0 R /Last 8 0 R /Count 2 >>"),
                TestObj(7, 0, "<< /Title (Gone) /Parent 6 0 R /Next 8 0 R /Dest [99 0 R /Fit] >>"),
                TestObj(8, 0, "<< /Title (Here) /Parent 6 0 R /Prev 7 0 R /Dest [4 0 R /Fit] >>")
            )
        )
        open(pdf).use { document ->
            val entries = DocumentOutline.read(document)
            assertEquals(listOf("Here"), entries.map { it.title })
            assertEquals(listOf(1), entries.map { it.pageIndex })
        }
    }

    @Test
    fun aCyclicOutlineEndsWithoutThrowing() {
        val pdf = bytes(
            extra = " /Outlines 6 0 R",
            objects = listOf(
                TestObj(6, 0, "<< /Type /Outlines /First 7 0 R /Last 7 0 R /Count 1 >>"),
                // Item 7's child is item 7 itself; its /Next is item 7 too.
                TestObj(
                    7, 0,
                    "<< /Title (Loop) /Parent 6 0 R /First 7 0 R /Next 7 0 R " +
                        "/Dest [3 0 R /Fit] >>"
                )
            )
        )
        open(pdf).use { document ->
            val entries = DocumentOutline.read(document)
            assertEquals(listOf("Loop"), entries.map { it.title })
        }
    }

    @Test
    fun aDocumentWithoutAnOutlineReturnsEmpty() {
        open(bytes()).use { document ->
            assertTrue(DocumentOutline.read(document).isEmpty())
        }
    }
}
