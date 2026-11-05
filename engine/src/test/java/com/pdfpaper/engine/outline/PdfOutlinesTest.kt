package com.pdfpaper.engine.outline

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.xref.IndirectRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T56: app bookmarks become real document outline items through an incremental
 * update — the original revision is kept byte for byte, and an outline the
 * document already had keeps its own items.
 */
class PdfOutlinesTest {

    private fun rootRef(document: PdfDocument): PdfRef =
        (document.file.trailer.root as? IndirectRef)?.let { PdfRef(it.objNum, it.gen) }!!

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun documentBytes(withOutline: Boolean): ByteArray {
        val objects = mutableListOf(
            TestObj(
                1, 0,
                "<< /Type /Catalog /Pages 2 0 R" +
                    (if (withOutline) " /Outlines 6 0 R" else "") +
                    " >>"
            ),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 9 0 R] /Count 2 >>"),
            TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>"),
            TestObj(9, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>")
        )
        if (withOutline) {
            objects += TestObj(
                6, 0,
                "<< /Type /Outlines /First 7 0 R /Last 7 0 R /Count 1 >>"
            )
            objects += TestObj(
                7, 0,
                "<< /Title (Chapter one) /Parent 6 0 R /Dest [3 0 R /Fit] >>"
            )
        }
        return buildTestPdf(objects)
    }

    /** Walks the top-level outline items from /First through /Next. */
    private fun items(document: PdfDocument): List<PdfDict> {
        val catalog = document.resolve(rootRef(document)) as PdfDict
        val outlineRef = catalog["Outlines"] as? PdfRef ?: return emptyList()
        val root = document.resolve(outlineRef) as? PdfDict ?: return emptyList()
        val out = ArrayList<PdfDict>()
        var next = root["First"] as? PdfRef
        var guard = 0
        while (next != null && guard++ < 50) {
            val dict = document.resolve(next) as PdfDict
            out += dict
            next = dict["Next"] as? PdfRef
        }
        return out
    }

    @Test
    fun bookmarksBecomeOutlineItemsAfterTheOriginalBytes() {
        val original = documentBytes(withOutline = false)
        val bytes = open(original).use { document ->
            PdfOutlines.applyIncremental(
                document,
                original,
                listOf(
                    PdfOutlines.Entry("Introduction", 0),
                    PdfOutlines.Entry("Findings", 1)
                )
            ).getOrThrow()
        }

        assertTrue(bytes.size > original.size)
        assertTrue(
            bytes.copyOf(original.size).contentEquals(original),
            "the original revision must stay byte-identical"
        )
        open(bytes).use { reopened ->
            val items = items(reopened)
            assertEquals(listOf("Introduction", "Findings"), items.map { (it["Title"] as PdfString).text() })
            val dest = items[1]["Dest"] as PdfArray
            assertEquals("XYZ", (dest.items[1] as PdfName).name)
            assertEquals(9, (dest.items[0] as PdfRef).objNum)
            val catalog = reopened.resolve(rootRef(reopened)) as PdfDict
            val root = reopened.resolve(catalog["Outlines"] as PdfRef) as PdfDict
            assertEquals(2, (root["Count"] as PdfInteger).value)
            assertNotNull(root["First"])
            assertNotNull(root["Last"])
        }
    }

    @Test
    fun anExistingOutlineKeepsItsItems() {
        val original = documentBytes(withOutline = true)
        val bytes = open(original).use { document ->
            PdfOutlines.applyIncremental(
                document,
                original,
                listOf(PdfOutlines.Entry("Appendix", 1))
            ).getOrThrow()
        }

        assertTrue(bytes.copyOf(original.size).contentEquals(original))
        open(bytes).use { reopened ->
            val items = items(reopened)
            assertEquals(
                listOf("Chapter one", "Appendix"),
                items.map { (it["Title"] as PdfString).text() }
            )
            // The old item now links forward, and the new one links back.
            assertNotNull(items[0]["Next"])
            assertNotNull(items[1]["Prev"])
            assertEquals(7, (items[1]["Prev"] as PdfRef).objNum)
            val catalog = reopened.resolve(rootRef(reopened)) as PdfDict
            val root = reopened.resolve(catalog["Outlines"] as PdfRef) as PdfDict
            assertEquals(2, (root["Count"] as PdfInteger).value)
            assertEquals(7, (root["First"] as PdfRef).objNum)
        }
    }

    @Test
    fun savingFewerBookmarksRemovesTheDeletedOne() {
        val original = documentBytes(withOutline = false)
        val first = open(original).use { document ->
            PdfOutlines.applyIncremental(
                document,
                original,
                listOf(PdfOutlines.Entry("A", 0), PdfOutlines.Entry("B", 1))
            ).getOrThrow()
        }
        val second = open(first).use { document ->
            PdfOutlines.applyIncremental(
                document,
                first,
                listOf(PdfOutlines.Entry("A", 0))
            ).getOrThrow()
        }
        assertTrue(second.copyOf(first.size).contentEquals(first))
        open(second).use { reopened ->
            assertEquals(
                listOf("A"),
                items(reopened).map { (it["Title"] as PdfString).text() }
            )
            val catalog = reopened.resolve(rootRef(reopened)) as PdfDict
            val root = reopened.resolve(catalog["Outlines"] as PdfRef) as PdfDict
            assertEquals(1, (root["Count"] as PdfInteger).value)
        }
    }

    @Test
    fun noBookmarksFailsWithAMessage() {
        val original = documentBytes(withOutline = false)
        open(original).use { document ->
            val result = PdfOutlines.applyIncremental(document, original, emptyList())
            assertTrue(result.isFailure)
            assertNotNull(result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun aPageOutsideTheDocumentFails() {
        val original = documentBytes(withOutline = false)
        open(original).use { document ->
            val result = PdfOutlines.applyIncremental(
                document,
                original,
                listOf(PdfOutlines.Entry("Nowhere", 5))
            )
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun aRebuiltFileWithoutStartXrefFailsCleanly() {
        val original = documentBytes(withOutline = false)
        val damaged = String(original, Charsets.ISO_8859_1)
            .replace(Regex("startxref\\s+\\d+"), "startxref\n-1")
            .toByteArray(Charsets.ISO_8859_1)
        open(damaged).use { document ->
            val result = PdfOutlines.applyIncremental(
                document,
                damaged,
                listOf(PdfOutlines.Entry("x", 0))
            )
            assertTrue(result.isFailure)
        }
    }
}
