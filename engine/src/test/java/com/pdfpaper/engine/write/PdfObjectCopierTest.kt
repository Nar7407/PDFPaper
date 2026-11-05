package com.pdfpaper.engine.write

import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfObjectCopierTest {

    private fun source(vararg bodies: String) =
        WriteFixtures.open(buildTestPdf(bodies.mapIndexed { index, body -> TestObj(index + 1, 0, body) }))

    @Test
    fun sharedReferencesStayShared() {
        source("<< /Type /Catalog /A 2 0 R /B 2 0 R >>", "<< /X 1 >>").use { doc ->
            val builder = PdfDocumentBuilder()
            val copier = PdfObjectCopier(doc, builder)
            val ref = copier.copyIndirect(1)!!
            val dict = builder.get(ref.objNum) as PdfDict
            assertTrue("A and B must point at the same target object", dict["A"] === dict["B"])
            assertTrue(dict["A"] is PdfRef)
        }
    }

    @Test
    fun cyclesTerminate() {
        source("<< /Type /Catalog /Self 1 0 R >>").use { doc ->
            val builder = PdfDocumentBuilder()
            val copier = PdfObjectCopier(doc, builder)
            val ref = copier.copyIndirect(1)!!
            val dict = builder.get(ref.objNum) as PdfDict
            assertEquals(ref, dict["Self"])
        }
    }

    @Test
    fun knownRemapRedirectsAndDrops() {
        source("<< /Type /Catalog /A 2 0 R /B 3 0 R >>", "<< /X 1 >>", "<< /Y 2 >>").use { doc ->
            val builder = PdfDocumentBuilder()
            val copier = PdfObjectCopier(doc, builder)
            val mapped = PdfRef(100, 0)
            copier.mapKnown(2, mapped)
            copier.mapKnown(3, null)
            val ref = copier.copyIndirect(1)!!
            val dict = builder.get(ref.objNum) as PdfDict
            assertEquals(mapped, dict["A"])
            assertNull("a dropped reference removes the entry", dict["B"])
        }
    }

    @Test
    fun skipKeysCutsStructuralLinks() {
        source("<< /Type /Page /Parent 2 0 R /Rotate 90 >>", "<< /Type /Pages /Kids [] >>").use { doc ->
            val builder = PdfDocumentBuilder()
            val copier = PdfObjectCopier(doc, builder)
            val ref = copier.copyIndirect(1, setOf("Parent"))!!
            val dict = builder.get(ref.objNum) as PdfDict
            assertNull(dict["Parent"])
            assertEquals(PdfInteger(90), dict["Rotate"])
        }
    }

    @Test
    fun streamsKeepEncodedBytes() {
        val doc = WriteFixtures.open(
            buildTestPdf(
                listOf(
                    TestObj(1, 0, "<< /Type /Catalog /S 2 0 R >>"),
                    TestObj(2, 0, "<< /Length 3 /Filter /FlateDecode >>\nstream\n\u0001\u0002\u0003\nendstream")
                )
            )
        )
        doc.use {
            val builder = PdfDocumentBuilder()
            val copier = PdfObjectCopier(it, builder)
            val ref = copier.copyIndirect(2)!!
            val stream = builder.get(ref.objNum) as com.pdfpaper.engine.model.PdfStream
            assertTrue(stream.data.contentEquals(byteArrayOf(1, 2, 3)))
        }
    }

    @Test
    fun danglingReferenceCopiesAsNullInsteadOfThrowing() {
        source("<< /Type /Catalog /Missing 99 0 R >>").use { doc ->
            val builder = PdfDocumentBuilder()
            val copier = PdfObjectCopier(doc, builder)
            val ref = copier.copyIndirect(1)!!
            val dict = builder.get(ref.objNum) as PdfDict
            assertNull(dict["Missing"])
        }
    }

    @Test
    fun repeatedCopiesReuseMemoisedReference() {
        source("<< /Type /Catalog /A 2 0 R >>", "<< /X 1 >>").use { doc ->
            val builder = PdfDocumentBuilder()
            val copier = PdfObjectCopier(doc, builder)
            val first = copier.copyIndirect(2)
            val second = copier.copyIndirect(2)
            assertSame(first, second)
        }
    }
}
