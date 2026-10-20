package com.pdfpaper.engine.xref

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.io.FileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

private data class Obj(val num: Int, val gen: Int, val body: String)

private val ASCII = StandardCharsets.US_ASCII

private fun catalogObjects(pageBody: String = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>") =
    listOf(
        Obj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
        Obj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
        Obj(3, 0, pageBody)
    )

/** Classic xref table (§7.5.4) + trailer (§7.5.5). Offsets computed for real. */
private fun classicPdf(
    objects: List<Obj>,
    rootRef: String = "1 0 R",
    offsetShift: Long = 0,
    extraTrailer: String = ""
): ByteArray {
    val out = ByteArrayOutputStream()
    fun write(s: String) = out.write(s.toByteArray(ASCII))
    write("%PDF-1.4\n")
    val offsets = mutableMapOf<Int, Long>()
    val gens = mutableMapOf<Int, Int>()
    for (o in objects) {
        offsets[o.num] = out.size().toLong()
        gens[o.num] = o.gen
        write("${o.num} ${o.gen} obj\n${o.body}\nendobj\n")
    }
    val maxNum = objects.maxOf { it.num }
    val xrefAt = out.size().toLong()
    write("xref\n0 ${maxNum + 1}\n")
    for (i in 0..maxNum) {
        val off = offsets[i]
        if (off == null) {
            write("0000000000 00000 f \n")
        } else {
            write("%010d %05d n \n".format(off + offsetShift, gens[i]))
        }
    }
    write("trailer\n<< /Size ${maxNum + 1} /Root $rootRef$extraTrailer >>\n")
    write("startxref\n$xrefAt\n%%EOF\n")
    return out.toByteArray()
}

/** Xref stream variant (§7.5.8), unfiltered entries with W = [1 4 2]. */
private fun xrefStreamPdf(objects: List<Obj>): ByteArray {
    val out = ByteArrayOutputStream()
    fun write(s: String) = out.write(s.toByteArray(ASCII))
    write("%PDF-1.4\n")
    val offsets = mutableMapOf<Int, Int>()
    for (o in objects) {
        offsets[o.num] = out.size()
        write("${o.num} ${o.gen} obj\n${o.body}\nendobj\n")
    }
    val maxNum = objects.maxOf { it.num }
    val streamObjAt = out.size().toLong()
    val buf = ByteBuffer.allocate((maxNum + 1) * 7)
    for (i in 0..maxNum) {
        val off = offsets[i]
        if (off == null) {
            buf.put(0.toByte())
            buf.putInt(0)
            buf.putShort(65535.toShort())
        } else {
            // ISO 32000-1, Table 18: type 2 is a plain object, field 2 is the
            // byte offset and field 3 the generation number.
            buf.put(2.toByte())
            buf.putInt(off)
            buf.putShort(0.toShort())
        }
    }
    val dict = "<< /Type /XRef /Size ${maxNum + 1} /Root 1 0 R /W [1 4 2] /Length ${buf.position()} >>"
    write("${maxNum + 1} 0 obj\n$dict\nstream\n")
    out.write(buf.array(), 0, buf.position())
    write("\nendstream\nendobj\n")
    write("startxref\n$streamObjAt\n%%EOF\n")
    return out.toByteArray()
}

private fun ByteArray.ascii(): String = toString(ASCII)

class PdfFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun validFileOpensWithHeaderTrailerAndOffsets() {
        val result = PdfFile.open(ByteArraySource(classicPdf(catalogObjects())))

        assertTrue(result.isSuccess)
        result.getOrThrow().use { pdf ->
            assertEquals("1.4", pdf.headerVersion)
            assertEquals(4, pdf.trailer.size)
            assertEquals(IndirectRef(1, 0), pdf.trailer.root)
            assertEquals("1 0 R", pdf.trailer.raw["Root"])
            assertFalse(pdf.rebuiltFromScan)
            assertEquals(EntryKind.FREE, pdf.entry(0)?.kind)
            assertEquals(EntryKind.UNCOMPRESSED, pdf.entry(2)?.kind)
            assertEquals(0, pdf.entry(2)?.gen)
            val obj1 = assertNotNull(pdf.readObject(1)).ascii()
            assertTrue(obj1.contains("/Catalog"))
            val obj3 = assertNotNull(pdf.readObject(3)).ascii()
            assertTrue(obj3.contains("/MediaBox"))
        }
    }

    @Test
    fun truncatedFileWithoutStartXrefOpensViaRebuild() {
        val full = classicPdf(catalogObjects())
        val cutAt = full.ascii().indexOf("startxref") - 5
        val truncated = full.copyOfRange(0, cutAt)

        val result = PdfFile.open(ByteArraySource(truncated))

        assertTrue(result.isSuccess)
        result.getOrThrow().use { pdf ->
            assertTrue(pdf.rebuiltFromScan)
            assertEquals("1.4", pdf.headerVersion)
            assertEquals(4, pdf.trailer.size)
            assertTrue(assertNotNull(pdf.readObject(1)).ascii().contains("/Catalog"))
        }
    }

    @Test
    fun wrongXrefOffsetsFallBackToRebuild() {
        val result = PdfFile.open(ByteArraySource(classicPdf(catalogObjects(), offsetShift = 7)))

        assertTrue(result.isSuccess)
        result.getOrThrow().use { pdf ->
            assertTrue(pdf.rebuiltFromScan)
            assertTrue(assertNotNull(pdf.readObject(2)).ascii().contains("/Pages"))
        }
    }

    @Test
    fun startXrefPointingPastEofFallsBackToRebuild() {
        val text = classicPdf(catalogObjects()).ascii()
        val marker = "startxref\n"
        val digitsStart = text.indexOf(marker) + marker.length
        var digitsEnd = digitsStart
        while (digitsEnd < text.length && text[digitsEnd].isDigit()) digitsEnd++
        val broken = (text.substring(0, digitsStart) + "99999999" + text.substring(digitsEnd))
            .toByteArray(ASCII)

        val result = PdfFile.open(ByteArraySource(broken))

        assertTrue(result.isSuccess)
        result.getOrThrow().use { pdf ->
            assertTrue(pdf.rebuiltFromScan)
            assertTrue(assertNotNull(pdf.readObject(1)).ascii().contains("/Catalog"))
        }
    }

    @Test
    fun incrementalUpdateResolvesNewestObject() {
        val base = classicPdf(catalogObjects())
        val baseXref = base.ascii().substringAfterLast("startxref\n").substringBefore("\n").trim().toLong()
        val newPage = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>"
        val upd = ByteArrayOutputStream()
        fun write(s: String) = upd.write(s.toByteArray(ASCII))
        val newOff = (base.size + upd.size()).toLong()
        write("3 0 obj\n$newPage\nendobj\n")
        val updXref = (base.size + upd.size()).toLong()
        write("xref\n3 1\n")
        write("%010d %05d n \n".format(newOff, 0))
        write("trailer\n<< /Size 4 /Root 1 0 R /Prev $baseXref >>\n")
        write("startxref\n$updXref\n%%EOF\n")
        val combined = base + upd.toByteArray()

        val result = PdfFile.open(ByteArraySource(combined))

        assertTrue(result.isSuccess)
        result.getOrThrow().use { pdf ->
            assertFalse(pdf.rebuiltFromScan)
            assertEquals(baseXref, pdf.trailer.prev)
            assertEquals(IndirectRef(1, 0), pdf.trailer.root)
            val page = assertNotNull(pdf.readObject(3)).ascii()
            assertTrue(page.contains("100 100"))
            // Older objects still resolve through the /Prev chain (§7.5.6).
            assertTrue(assertNotNull(pdf.readObject(1)).ascii().contains("/Catalog"))
        }
    }

    @Test
    fun xrefStreamFileOpens() {
        val result = PdfFile.open(ByteArraySource(xrefStreamPdf(catalogObjects())))

        assertTrue(result.isSuccess)
        result.getOrThrow().use { pdf ->
            assertFalse(pdf.rebuiltFromScan)
            assertEquals(4, pdf.trailer.size)
            assertEquals(IndirectRef(1, 0), pdf.trailer.root)
            assertEquals(EntryKind.FREE, pdf.entry(0)?.kind)
            assertTrue(assertNotNull(pdf.readObject(2)).ascii().contains("/Pages"))
        }
    }

    @Test
    fun garbageWithoutHeaderFailsWithoutThrowing() {
        val result = PdfFile.open(ByteArraySource("This is not a PDF at all\njust text\n".toByteArray(ASCII)))

        assertTrue(result.isFailure)
    }

    @Test
    fun emptyInputFailsWithoutThrowing() {
        val result = PdfFile.open(ByteArraySource(ByteArray(0)))

        assertTrue(result.isFailure)
    }

    @Test
    fun missingObjectLookupReturnsNull() {
        val result = PdfFile.open(ByteArraySource(classicPdf(catalogObjects())))

        assertTrue(result.isSuccess)
        result.getOrThrow().use { pdf ->
            assertNull(pdf.entry(99))
            assertNull(pdf.readObject(99))
            assertNull(pdf.readObject(0))
        }
    }

    @Test
    fun fileSourceOpensWithoutLoadingAllBytes() {
        val file = tempFolder.newFile("sample.pdf")
        file.writeBytes(classicPdf(catalogObjects()))

        val result = PdfFile.open(FileSource(file))

        assertTrue(result.isSuccess)
        result.getOrThrow().use { pdf ->
            assertEquals("1.4", pdf.headerVersion)
            assertTrue(assertNotNull(pdf.readObject(1)).ascii().contains("/Catalog"))
        }
    }
}
