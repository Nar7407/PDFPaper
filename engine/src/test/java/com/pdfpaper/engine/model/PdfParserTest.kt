package com.pdfpaper.engine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertIs

private fun parse(source: String): PdfObject =
    parseDirectObject(source.toByteArray(Charsets.ISO_8859_1)).getOrThrow()

private fun fails(source: String) {
    assertTrue(
        "Expected failure for: $source",
        parseDirectObject(source.toByteArray(Charsets.ISO_8859_1)).isFailure
    )
}

class PdfParserTest {

    @Test
    fun nullObject() {
        assertEquals(PdfNull, parse("null"))
    }

    @Test
    fun booleans() {
        assertEquals(PdfBoolean(true), parse("true"))
        assertEquals(PdfBoolean(false), parse("false"))
    }

    @Test
    fun integers() {
        assertEquals(PdfInteger(42), parse("42"))
        assertEquals(PdfInteger(-7), parse("-7"))
        assertEquals(PdfInteger(12), parse("+12"))
    }

    @Test
    fun reals() {
        assertEquals(PdfReal(3.14), parse("3.14"))
        assertEquals(PdfReal(-0.5), parse("-0.5"))
        assertEquals(PdfReal(4.0), parse("4."))
        assertEquals(PdfReal(0.5), parse(".5"))
        assertTrue(parse("12") is PdfInteger)
    }

    @Test
    fun literalStringEscapes() {
        assertEquals(PdfString("a\nb\t\\c"), parse("(a\\nb\\t\\\\c)"))
    }

    @Test
    fun literalStringOctalAndNesting() {
        assertEquals(PdfString("AB"), parse("(\\101\\102)"))
        assertEquals(PdfString("(a)b"), parse("((a)b)"))
    }

    @Test
    fun literalStringLineContinuation() {
        assertEquals(PdfString("ab"), parse("(a\\\nb)"))
    }

    @Test
    fun hexStrings() {
        assertEquals(PdfString("Hello"), parse("<48656C6C6F>"))
        assertEquals(byteArrayOf(0x40.toByte()).toList(), parse("<4>").let { (it as PdfString).bytes.toList() })
        assertEquals(PdfString("Hello"), parse("<48 65 6C 6C 6F>"))
    }

    @Test
    fun names() {
        assertEquals(PdfName("Type"), parse("/Type"))
        assertEquals(PdfName("A B"), parse("/A#20B"))
        assertEquals(PdfName(""), parse("/ /X"))
    }

    @Test
    fun arrayMixed() {
        val array = assertIs<PdfArray>(parse("[1 2 (three) /Four]"))
        assertEquals(4, array.items.size)
        assertEquals(PdfInteger(1), array.items[0])
        assertEquals(PdfInteger(2), array.items[1])
        assertEquals(PdfString("three"), array.items[2])
        assertEquals(PdfName("Four"), array.items[3])
    }

    @Test
    fun arrayNestedAndEmpty() {
        val nested = assertIs<PdfArray>(parse("[[1] [2]]"))
        assertEquals(PdfArray(listOf(PdfInteger(1))), nested.items[0])
        assertEquals(PdfArray(listOf(PdfInteger(2))), nested.items[1])
        assertEquals(0, assertIs<PdfArray>(parse("[]")).items.size)
    }

    @Test
    fun dictionarySimple() {
        val dict = assertIs<PdfDict>(parse("<</Type /Example /Count 3>>"))
        assertEquals(PdfName("Example"), dict["Type"])
        assertEquals(PdfInteger(3), dict["Count"])
    }

    @Test
    fun dictionaryNested() {
        val dict = assertIs<PdfDict>(parse("<</A <</B 1>> /C [true null]>>"))
        assertEquals(PdfInteger(1), (dict["A"] as PdfDict)["B"])
        val list = assertIs<PdfArray>(dict["C"])
        assertEquals(PdfBoolean(true), list.items[0])
        assertEquals(PdfNull, list.items[1])
    }

    @Test
    fun indirectReference() {
        assertEquals(PdfRef(4, 0), parse("4 0 R"))
    }

    @Test
    fun indirectObject() {
        val parsed = parseIndirectObject("12 0 obj\ntrue\nendobj".toByteArray(Charsets.ISO_8859_1))
            .getOrThrow()
        assertEquals(ParsedIndirect(12, 0, PdfBoolean(true)), parsed)
    }

    @Test
    fun streamWithExactLength() {
        val parsed = parseIndirectObject(
            "5 0 obj\n<< /Length 11 >>\nstream\nhello world\nendstream\nendobj"
                .toByteArray(Charsets.ISO_8859_1)
        ).getOrThrow()
        val stream = assertIs<PdfStream>(parsed.obj)
        assertEquals("hello world", stream.data.toString(Charsets.ISO_8859_1))
        assertEquals(PdfInteger(11), stream.dict["Length"])
    }

    @Test
    fun streamWithWrongLengthFallsBackToEndstream() {
        val parsed = parseIndirectObject(
            "5 0 obj\n<< /Length 4 >>\nstream\nhello world\nendstream\nendobj"
                .toByteArray(Charsets.ISO_8859_1)
        ).getOrThrow()
        val stream = assertIs<PdfStream>(parsed.obj)
        assertEquals("hello world", stream.data.toString(Charsets.ISO_8859_1))
    }

    @Test
    fun streamMissingEndstreamReadsToEnd() {
        val parsed = parseIndirectObject(
            "5 0 obj\n<< /Length 5 >>\nstream\nhello".toByteArray(Charsets.ISO_8859_1)
        ).getOrThrow()
        val stream = assertIs<PdfStream>(parsed.obj)
        assertEquals("hello", stream.data.toString(Charsets.ISO_8859_1))
    }

    @Test
    fun missingEndobjIsTolerated() {
        val parsed = parseIndirectObject("1 0 obj\n42".toByteArray(Charsets.ISO_8859_1)).getOrThrow()
        assertEquals(ParsedIndirect(1, 0, PdfInteger(42)), parsed)
    }

    @Test
    fun unterminatedStringIsTolerated() {
        assertEquals(PdfString("unterminated"), parse("(unterminated"))
    }

    @Test
    fun malformedInputsFailWithoutThrowing() {
        fails(">>>")
        fails("[1 2")
        fails("foobar")
        fails("")
        fails("<</A 1>")
    }

    @Test
    fun utf16StringDecodesAfterBom() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x41)
        assertEquals("A", PdfString(bytes).text())
    }
}
