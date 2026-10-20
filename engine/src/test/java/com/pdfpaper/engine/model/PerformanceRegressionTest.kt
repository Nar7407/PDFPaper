package com.pdfpaper.engine.model

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * T14 regression guards for the profiling fixes. The time bounds are
 * deliberately generous: they are an order of magnitude above the fixed cost
 * and only trip on a return of the original quadratic behaviour, not on a slow
 * machine. The correctness assertions in each test are the primary contract.
 */
class PerformanceRegressionTest {

    @Test
    fun scanNumberAcceptsOnlyPdfNumbers() {
        // Grammar (§7.3.3): [+-]?(\d+\.\d*|\.\d+|\d+)([eE][+-]?\d+)?
        assertEquals(NumberLong(0), scanNumber("0"))
        assertEquals(NumberLong(42), scanNumber("42"))
        assertEquals(NumberLong(-42), scanNumber("-42"))
        assertEquals(NumberLong(42), scanNumber("+42"))
        assertEquals(NumberDouble(1.5), scanNumber("1.5"))
        assertEquals(NumberDouble(0.5), scanNumber(".5"))
        assertEquals(NumberDouble(5.0), scanNumber("5."))
        assertEquals(NumberDouble(-0.25), scanNumber("-.25"))
        assertEquals(NumberDouble(1200.0), scanNumber("1.2e3"))
        assertEquals(NumberDouble(1.2e-3), scanNumber("1.2E-3"))
        // Integer overflow degrades to a real instead of failing.
        assertIs<NumberDouble>(scanNumber("99999999999999999999"))

        assertNull(scanNumber(""))
        assertNull(scanNumber("."))
        assertNull(scanNumber("+"))
        assertNull(scanNumber("1.2.3"))
        assertNull(scanNumber("1e"))
        assertNull(scanNumber("1e+"))
        assertNull(scanNumber("abc"))
        assertNull(scanNumber("12ab"))
        assertNull(scanNumber("0x10"))
        assertNull(scanNumber("1 2"))
    }

    private fun assertNull(value: NumberValue?) = assertEquals(null, value)

    @Test
    fun largeObjectIsReadAcrossProbeChunks() {
        // Bigger than the 8 KiB probe: the reader must grow to the full object.
        val payload = ByteArray(100_000) { (it * 31 % 251).toByte() }
        val streamBody = ("<< /Length ${payload.size} >>\nstream\n").toByteArray(Charsets.US_ASCII) +
            payload + "\nendstream".toByteArray(Charsets.US_ASCII)
        val bytes = buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog >>"),
                TestObj(2, 0, streamBody)
            )
        )
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow().use { doc ->
            val stream = assertIs<PdfStream>(assertNotNull(doc.deref(2)))
            assertEquals(payload.size, stream.data.size)
            assertTrue(stream.data.contentEquals(payload))
        }
    }

    @Test
    fun manyCompressedObjectsResolveWithoutRepeatedObjectStreamDecode() {
        val count = 4_000
        val doc = openObjectStreamDocument(count)
        doc.use {
            val started = System.nanoTime()
            for (i in 0 until count) {
                val objNum = 6 + i
                val dict = assertIs<PdfDict>(assertNotNull(it.resolve(PdfRef(objNum, 0))))
                assertEquals(PdfInteger(objNum.toLong()), dict["Idx"])
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            // Original code re-inflated and re-split the whole stream per object
            // (~16 s here); caching the parsed stream makes it tens of ms.
            assertTrue("deref of $count objects took ${ms}ms", ms < 5_000)
        }
    }

    /** xref-stream file with [count] objects packed into one Flate object stream (§7.5.7/8). */
    private fun openObjectStreamDocument(count: Int): PdfDocument {
        val objStmNum = 4
        val arrayNum = 5
        val firstCompressed = 6
        val xrefNum = firstCompressed + count

        val header = StringBuilder()
        val body = ByteArrayOutputStream()
        val refs = StringBuilder("[")
        for (i in 0 until count) {
            val objNum = firstCompressed + i
            header.append("$objNum ${body.size()} ")
            body.write("<< /Idx $objNum >>\n".toByteArray(Charsets.US_ASCII))
            refs.append("$objNum 0 R ")
        }
        refs.append("]")
        val first = header.toString().toByteArray(Charsets.US_ASCII).size
        val raw = ByteArrayOutputStream()
        raw.write(header.toString().toByteArray(Charsets.US_ASCII))
        body.writeTo(raw)
        val compressed = deflate(raw.toByteArray())

        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        val offsets = HashMap<Int, Long>()
        fun obj(num: Int, bodyBytes: ByteArray): Long {
            val at = out.size().toLong()
            w("$num 0 obj\n")
            out.write(bodyBytes)
            w("\nendobj\n")
            return at
        }
        w("%PDF-1.7\n")
        offsets[1] = obj(1, "<< /Type /Catalog /Pages 2 0 R /X $arrayNum 0 R >>".toByteArray(Charsets.US_ASCII))
        offsets[2] = obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(Charsets.US_ASCII))
        offsets[3] = obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>".toByteArray(Charsets.US_ASCII))
        offsets[objStmNum] = obj(
            objStmNum,
            ("<< /Type /ObjStm /N $count /First $first /Filter /FlateDecode " +
                "/Length ${compressed.size} >>\nstream\n").toByteArray(Charsets.US_ASCII) +
                compressed + "\nendstream".toByteArray(Charsets.US_ASCII)
        )
        offsets[arrayNum] = obj(arrayNum, refs.toString().toByteArray(Charsets.US_ASCII))

        val size = xrefNum + 1
        offsets[xrefNum] = out.size().toLong()
        val entries = ByteArrayOutputStream()
        fun entry(type: Int, f2: Int, f3: Int) {
            entries.write(type)
            entries.write((f2 ushr 16) and 0xFF)
            entries.write((f2 ushr 8) and 0xFF)
            entries.write(f2 and 0xFF)
            entries.write((f3 ushr 8) and 0xFF)
            entries.write(f3 and 0xFF)
        }
        entry(0, 0, 0)
        for (i in 1 until firstCompressed) entry(2, offsets.getValue(i).toInt(), 0)
        for (i in 0 until count) entry(1, objStmNum, i)
        entry(2, offsets.getValue(xrefNum).toInt(), 0)
        val xrefData = entries.toByteArray()
        w("$xrefNum 0 obj\n")
        w(
            "<< /Type /XRef /Size $size /Root 1 0 R /W [1 3 2] " +
                "/Index [0 $size] /Length ${xrefData.size} >>\nstream\n"
        )
        out.write(xrefData)
        w("\nendstream\nendobj\n")
        w("startxref\n${offsets.getValue(xrefNum)}\n%%EOF\n")
        return PdfDocument.open(ByteArraySource(out.toByteArray())).getOrThrow()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 3 + 64)
        val buf = ByteArray(65536)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }
}
