package com.pdfpaper.engine.model

import com.pdfpaper.engine.io.ByteArraySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.assertIs
import kotlin.test.assertNotNull

private val ASCII = Charsets.US_ASCII

/** Minimal classic-xref document builder (self-contained; mirrors T2 fixtures). */
private fun classicDoc(objects: List<Triple<Int, Int, String>>, rootRef: String = "1 0 R"): ByteArray {
    val out = ByteArrayOutputStream()
    fun w(s: String) {
        out.write(s.toByteArray(ASCII))
    }
    w("%PDF-1.4\n")
    val offsets = mutableMapOf<Int, Long>()
    for ((num, gen, body) in objects) {
        offsets[num] = out.size().toLong()
        w("$num $gen obj\n$body\nendobj\n")
    }
    val maxNum = objects.maxOf { it.first }
    val xrefAt = out.size().toLong()
    w("xref\n0 ${maxNum + 1}\n")
    for (i in 0..maxNum) {
        val off = offsets[i]
        if (off == null) w("0000000000 00000 f \n") else w("%010d %05d n \n".format(off, 0))
    }
    w("trailer\n<< /Size ${maxNum + 1} /Root $rootRef >>\n")
    w("startxref\n$xrefAt\n%%EOF\n")
    return out.toByteArray()
}

/** Document whose objects 10/11 live in object stream 4 (entries need an xref stream). */
private fun compressedDoc(): ByteArray {
    val out = ByteArrayOutputStream()
    fun w(s: String) {
        out.write(s.toByteArray(ASCII))
    }
    w("%PDF-1.4\n")
    val catalogAt = out.size()
    w("1 0 obj\n<< /Type /Catalog /A 10 0 R /B 11 0 R >>\nendobj\n")
    val stmAt = out.size()
    // Object-stream payload: header pairs then objects.
    val payload = "10 0 11 3\n(A)42"
    w("4 0 obj\n<< /Type /ObjStm /N 2 /First 10 >>\nstream\n$payload\nendstream\nendobj\n")
    val xrefAt = out.size()
    // Entries for objects 0..11 with W = [1 4 2].
    val buf = ByteBuffer.allocate(12 * 7)
    fun free() {
        buf.put(0.toByte())
        buf.putInt(0)
        buf.putShort(0.toShort())
    }
    fun uncompressed(offset: Int) {
        buf.put(2.toByte())
        buf.putInt(offset)
        buf.putShort(0.toShort())
    }
    fun compressed(stream: Int, index: Int) {
        buf.put(1.toByte())
        buf.putInt(stream)
        buf.putShort(index.toShort())
    }
    free() // 0
    uncompressed(catalogAt) // 1
    free() // 2
    free() // 3
    uncompressed(stmAt) // 4
    free() // 5
    free() // 6
    free() // 7
    free() // 8
    free() // 9
    compressed(4, 0) // 10
    compressed(4, 1) // 11
    val dict = "<< /Type /XRef /Size 12 /Root 1 0 R /W [1 4 2] /Length ${buf.position()} >>"
    w("5 0 obj\n$dict\nstream\n")
    out.write(buf.array(), 0, buf.position())
    w("\nendstream\nendobj\n")
    w("startxref\n$xrefAt\n%%EOF\n")
    return out.toByteArray()
}

private fun openDoc(bytes: ByteArray): PdfDocument {
    val result = PdfDocument.open(ByteArraySource(bytes))
    assertTrue(result.isSuccess)
    return result.getOrThrow()
}

class PdfDocumentTest {

    @Test
    fun resolvesCatalogAndNestedReferences() {
        val bytes = classicDoc(
            listOf(
                Triple(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                Triple(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                Triple(3, 0, "<< /Type /Page /Parent 2 0 R >>")
            )
        )
        openDoc(bytes).use { doc ->
            val catalog = assertIs<PdfDict>(assertNotNull(doc.resolveRoot()))
            assertEquals(PdfName("Catalog"), catalog["Type"])
            val pagesRef = assertIs<PdfRef>(assertNotNull(catalog["Pages"]))
            assertEquals(PdfRef(2, 0), pagesRef)
            val pages = assertIs<PdfDict>(assertNotNull(doc.resolve(pagesRef)))
            assertEquals(PdfInteger(1), pages["Count"])
            val kids = assertIs<PdfArray>(assertNotNull(pages["Kids"]))
            assertEquals(PdfRef(3, 0), kids.items[0])
        }
    }

    @Test
    fun referenceCycleTerminatesWithNull() {
        val bytes = classicDoc(
            listOf(
                Triple(1, 0, "2 0 R"),
                Triple(2, 0, "1 0 R")
            )
        )
        openDoc(bytes).use { doc ->
            // Raw deref still works (no hang); chained resolve hits the cycle guard.
            assertEquals(PdfRef(2, 0), doc.deref(1))
            assertNull(doc.resolve(PdfRef(1, 0)))
            assertNull(doc.resolve(PdfRef(2, 0)))
        }
    }

    @Test
    fun resolvesObjectsFromObjectStream() {
        openDoc(compressedDoc()).use { doc ->
            assertEquals(PdfString("A"), doc.resolve(PdfRef(10, 0)))
            assertEquals(PdfInteger(42), doc.resolve(PdfRef(11, 0)))
            val catalog = assertIs<PdfDict>(assertNotNull(doc.resolveRoot()))
            assertEquals(PdfRef(10, 0), catalog["A"])
        }
    }

    @Test
    fun missingObjectsResolveToNull() {
        val bytes = classicDoc(
            listOf(Triple(1, 0, "<< /Type /Catalog >>"))
        )
        openDoc(bytes).use { doc ->
            assertNull(doc.deref(99))
            assertNull(doc.resolve(PdfRef(99, 0)))
        }
    }
}
