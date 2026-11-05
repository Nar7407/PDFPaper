package com.pdfpaper.engine.write

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfBoolean
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfNull
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.test.arr
import com.pdfpaper.engine.test.dictOf
import com.pdfpaper.engine.test.i
import com.pdfpaper.engine.test.n
import com.pdfpaper.engine.test.r
import com.pdfpaper.engine.test.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

private val LATIN1 = StandardCharsets.ISO_8859_1

class PdfSerializerTest {

    private fun text(obj: PdfObject): String = PdfSerializer.toBytes(obj).toString(LATIN1)

    @Test
    fun scalarsAndReferences() {
        assertEquals("null", text(PdfNull))
        assertEquals("true", text(PdfBoolean(true)))
        assertEquals("false", text(PdfBoolean(false)))
        assertEquals("42", text(i(42)))
        assertEquals("-7", text(i(-7)))
        assertEquals("12 3 R", text(PdfRef(12, 3)))
    }

    @Test
    fun realsHaveNoExponentAndTrimZeros() {
        assertEquals("5", text(r(5.0)))
        assertEquals("0.5", text(r(0.5)))
        assertEquals("-2.5", text(r(-2.5)))
        assertEquals("0", text(r(0.0)))
        assertEquals("0.333333", text(r(1.0 / 3.0)))
        assertEquals("0", text(r(Double.NaN)))
        assertEquals("0", text(r(Double.POSITIVE_INFINITY)))
        // 0.1 must not serialise as "0.10000000000000001".
        assertEquals("0.1", text(r(0.1)))
    }

    @Test
    fun namesEscapeDelimitersAndNonAscii() {
        assertEquals("/Text", text(n("Text")))
        assertEquals("/A#20B", text(n("A B")))
        assertEquals("/Weird#23Name", text(n("Weird#Name")))
        assertEquals("/a#2Fb", text(n("a/b")))
    }

    @Test
    fun literalStringsEscapeParensAndBackslash() {
        assertEquals("(a\\(b\\)c)", text(PdfString("a(b)c")))
        assertEquals("(a\\\\b)", text(PdfString("a\\b")))
    }

    @Test
    fun nonPrintableStringsBecomeHex() {
        assertEquals("<610A62>", text(PdfString("a\nb")))
        assertEquals("<00FF>", text(str(0x00, 0xFF)))
    }

    @Test
    fun arraysAndDictionariesKeepOrder() {
        assertEquals("<< /Type /Page /Count 1 >>", text(dictOf("Type" to n("Page"), "Count" to i(1))))
        assertEquals("[1 /A (x) 2 0 R]", text(arr(i(1), n("A"), PdfString("x"), PdfRef(2, 0))))
        assertEquals("[]", text(PdfArray(emptyList())))
    }

    @Test
    fun streamRewritesLengthAndKeepsBytesExact() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val stream = PdfStream(dictOf("Filter" to n("FlateDecode"), "Length" to i(9999)), data)
        val bytes = PdfSerializer.toBytes(stream)
        val head = bytes.copyOfRange(0, bytes.indexOfSequence("stream".toByteArray(LATIN1))).toString(LATIN1)
        assertTrue("length must be rewritten", "/Length 3" in head)
        assertTrue("wrong length must not leak", "9999" !in head)
        val dataStart = bytes.indexOfSequence("stream\n".toByteArray(LATIN1)) + "stream\n".length
        assertTrue(bytes.copyOfRange(dataStart, dataStart + 3).contentEquals(data))
        assertTrue(bytes.toString(LATIN1).endsWith("endstream"))
    }

    @Test
    fun streamInDictionaryRoundTripsThroughParser() {
        val expected = "<< /Length 5 >>\nstream\nhello\nendstream"
        val bytes = PdfSerializer.toBytes(PdfStream(dictOf("Length" to i(5)), "hello".toByteArray(LATIN1)))
        assertEquals(expected, bytes.toString(LATIN1))
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
