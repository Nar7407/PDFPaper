package com.pdfpaper.engine.write

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.test.arr
import com.pdfpaper.engine.test.dictOf
import com.pdfpaper.engine.test.i
import com.pdfpaper.engine.test.n
import com.pdfpaper.engine.test.str
import com.pdfpaper.engine.xref.PdfFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

private val LATIN1 = StandardCharsets.ISO_8859_1

class PdfWriterTest {

    private val catalog = dictOf("Type" to n("Catalog"), "Pages" to PdfRef(2, 0))
    private val pages = dictOf("Type" to n("Pages"), "Kids" to arr(PdfRef(3, 0)), "Count" to i(1))
    private val page = dictOf("Type" to n("Page"), "Parent" to PdfRef(2, 0))

    private fun objects() = listOf(
        IndirectObject(1, 0, catalog),
        IndirectObject(2, 0, pages),
        IndirectObject(3, 0, page)
    )

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    @Test
    fun writesHeaderTrailerAndReopenableXref() {
        val bytes = PdfWriter.write(objects(), PdfRef(1, 0))
        assertTrue(bytes.toString(LATIN1).startsWith("%PDF-1.7\n"))

        open(bytes).use { doc ->
            // If any xref offset were wrong the loader would rebuild instead.
            assertFalse("xref offsets must be valid", doc.file.rebuiltFromScan)
            assertEquals(4, doc.file.trailer.size)
            assertEquals(1, doc.file.trailer.root?.objNum)
            val resolved = doc.resolveRoot() as PdfDict
            assertEquals(PdfName("Catalog"), resolved["Type"])
        }
    }

    @Test
    fun trailerCarriesInfoAndId() {
        val id = arr(str(0x01), str(0x02))
        val bytes = PdfWriter.write(
            objects() + IndirectObject(9, 0, dictOf("Producer" to PdfString("PDFPaper"))),
            PdfRef(1, 0),
            info = PdfRef(9, 0),
            id = id
        )
        val text = bytes.toString(LATIN1)
        assertTrue("/Info 9 0 R" in text)
        assertTrue("/ID [<01> <02>]" in text)

        open(bytes).use { doc ->
            assertEquals(PdfString("PDFPaper"), (doc.deref(9) as PdfDict)["Producer"])
        }
    }

    @Test
    fun incrementalAppendPreservesPrefixAndWins() {
        val original = PdfWriter.write(objects(), PdfRef(1, 0))
        val start = PdfFile.open(ByteArraySource(original)).getOrThrow().startXrefOffset!!
        val updated = PdfWriter.append(
            original,
            start,
            listOf(IndirectObject(3, 0, dictOf("Type" to n("Page"), "Parent" to PdfRef(2, 0), "Rotate" to i(90)))),
            PdfRef(1, 0),
            size = 4
        )

        // The previous revision is copied byte-for-byte.
        assertTrue(updated.copyOf(original.size).contentEquals(original))

        open(updated).use { doc ->
            assertFalse("the chained xref must be valid", doc.file.rebuiltFromScan)
            assertEquals(4, doc.file.trailer.size)
            val page = doc.resolve(PdfRef(3, 0)) as PdfDict
            assertEquals(PdfInteger(90), page["Rotate"])
            // Objects not touched by the update are still reachable via /Prev.
            assertEquals(PdfName("Catalog"), (doc.resolve(PdfRef(1, 0)) as PdfDict)["Type"])
        }
    }

    @Test
    fun incrementalAppendSparseXrefGroupsRuns() {
        val original = PdfWriter.write(objects(), PdfRef(1, 0))
        val start = PdfFile.open(ByteArraySource(original)).getOrThrow().startXrefOffset!!
        val updated = PdfWriter.append(
            original,
            start,
            listOf(
                IndirectObject(3, 0, dictOf("Type" to n("Page"))),
                IndirectObject(5, 0, PdfInteger(1)),
                IndirectObject(6, 0, PdfInteger(2))
            ),
            PdfRef(1, 0),
            size = 7
        )
        val tail = updated.copyOfRange(original.size, updated.size).toString(LATIN1)
        // Two subsections: 3 (single) and 5 2 (run of two).
        assertTrue(tail, tail.contains("3 1\n"))
        assertTrue(tail, tail.contains("5 2\n"))
        assertTrue("/Prev $start" in tail)
    }

    @Test
    fun arrayValuesSurviveRoundTrip() {
        val db = dictOf("Nums" to arr(i(1), i(2), i(3)), "Name" to PdfName("A B"))
        val bytes = PdfWriter.write(
            listOf(
                IndirectObject(1, 0, dictOf("Type" to n("Catalog"), "Extra" to PdfRef(2, 0))),
                IndirectObject(2, 0, db)
            ),
            PdfRef(1, 0)
        )
        open(bytes).use { doc ->
            val dict = doc.deref(2) as PdfDict
            assertEquals(PdfName("A B"), dict["Name"])
            assertEquals(3, (dict["Nums"] as PdfArray).items.size)
        }
    }
}
