package com.pdfpaper.engine.content

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfInlineImage
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

private val LATIN1 = StandardCharsets.ISO_8859_1

private fun parse(text: String): ParsedContent = parseContent(text.toByteArray(LATIN1))

private fun imageOf(p: ParsedContent): PdfInlineImage =
    p.commands.single { it.operator == "ID" }.operands.single() as PdfInlineImage

private fun num(obj: PdfObject): Double = when (obj) {
    is PdfInteger -> obj.value.toDouble()
    is PdfReal -> obj.value
    else -> Double.NaN
}

class ContentParserTest {

    // -- Every operator category (§7.8) --------------------------------------

    @Test
    fun allOperatorCategoriesInOneStream() {
        val content = """
            % header comment ignored
            q
            1 0 0 1 0 0 cm     % transform
            0 0 612 792 re
            f
            BT
            /F1 24 Tf
            1 0 0 1 100 700 Tm
            (Hello) Tj
            100 650 Td
            <4869> Tj
            0 -12 Td
            [-60 (lines) 30] TJ
            ET
            1 0 0 RG
            2 w
            0 0 m
            612 792 l
            100 50 y
            10 10 20 20 c
            5 5 3 3 1 1 v
            h
            S
            /DeviceRGB cs
            0.5 0 0.25 scn
            /Layout BDC
            <</MCID 0>> BDC
            EMC
            EMC
            /Sh0 sh
            10 10 100 50 re
            W n
            Q
        """.trimIndent()
        val parsed = parse(content)
        assertTrue(parsed.warnings.isEmpty())
        val ops = parsed.commands.map { it.operator }
        assertEquals(
            listOf(
                "q", "cm", "re", "f",
                "BT", "Tf", "Tm", "Tj", "Td", "Tj", "Td", "TJ", "ET",
                "RG", "w", "m", "l", "y", "c", "v", "h", "S",
                "cs", "scn",
                "BDC", "BDC", "EMC", "EMC",
                "sh", "re", "W", "n", "Q"
            ),
            ops
        )
        // Spot checks per category.
        assertEquals(listOf<PdfObject>(PdfName("F1"), PdfInteger(24)), parsed.commands[5].operands)
        assertEquals(
            listOf(1.0, 0.0, 0.0, 1.0, 100.0, 700.0),
            parsed.commands[6].operands.map { num(it) }
        )
        assertEquals("Hello", (parsed.commands[7].operands[0] as PdfString).text())
        assertEquals(3, (parsed.commands[11].operands[0] as PdfArray).items.size)
        assertEquals(
            listOf<PdfObject>(PdfName("Layout")),
            parsed.commands[24].operands
        )
        assertEquals(PdfInteger(0), ((parsed.commands[25].operands[0] as PdfDict)["MCID"]))
    }

    @Test
    fun numericEdgeCases() {
        val parsed = parse("-2 +3 0.5 -.25 1.5e2 -7 -3.2 m")
        assertEquals(1, parsed.commands.size)
        assertEquals(listOf(-2.0, 3.0, 0.5, -0.25, 150.0, -7.0, -3.2), parsed.commands[0].operands.map { num(it) })
    }

    @Test
    fun stringsAndNameEscapes() {
        val parsed = parse("<48656C6C6F> Tj (ab\\xyz) Tj /A#20B X")
        assertEquals(listOf("Tj", "Tj", "X"), parsed.commands.map { it.operator })
        assertEquals("Hello", (parsed.commands[0].operands[0] as PdfString).text())
        assertEquals("abxyz", (parsed.commands[1].operands[0] as PdfString).text())
        assertEquals("A B", (parsed.commands[2].operands[0] as PdfName).name)
    }

    @Test
    fun nestedArraysAndDictsAsOperands() {
        val parsed = parse("[[1 (x) << /K /V >> 2] [-.5]] M << /A [1 2] /B /NameValue >> Op")
        assertEquals(listOf("M", "Op"), parsed.commands.map { it.operator })
        assertTrue(parsed.warnings.isEmpty())
    }

    // -- Inline images (§8.9.7.3) ---------------------------------------------

    @Test
    fun inlineImageWithBinaryDataEndsAtDelimitedEI() {
        // Binary data deliberately contains "EI" NOT preceded by whitespace, so
        // only the real, delimited terminator may end the capture.
        val head = "q BI /W 2 /H 2 /BPC 8 /CS /RGB ID ".toByteArray(LATIN1)
        val binary = byteArrayOf(0x00, 0x80.toByte(), 'E'.code.toByte(), 'I'.code.toByte(), 0x7F)
        val tail = " EI\nQ".toByteArray(LATIN1)
        val parsed = parseContent(head + binary + tail)
        assertEquals(listOf("q", "ID", "Q"), parsed.commands.map { it.operator })
        assertTrue(parsed.warnings.isEmpty())
        val image = imageOf(parsed)
        assertArrayEquals(binary, image.data)
        assertEquals(PdfInteger(2), image.dict["W"])
        assertEquals(PdfInteger(8), image.dict["BPC"])
        assertEquals(PdfName("RGB"), image.dict["CS"])
    }

    @Test
    fun inlineImageSkipsExactlyOneByteAfterID() {
        // One EOL after ID is skipped; data starting immediately after a single
        // whitespace separator must yield exactly the asserted bytes.
        val a = parse("BI /W 1 ID\nA EI")
        assertArrayEquals(byteArrayOf(0x41), imageOf(a).data)
        val b = parse("BI /W 1 ID A EI") // 'A' right after one space
        assertArrayEquals(byteArrayOf(0x41), imageOf(b).data)
    }

    @Test
    fun inlineImageFallsBackToAnyEi() {
        // No whitespace-delimited EI anywhere: the lenient fallback terminates
        // at the bare pair without crashing.
        val parsed = parse("BI /W 1 ID XEIY")
        val image = imageOf(parsed)
        assertArrayEquals(byteArrayOf('X'.code.toByte()), image.data)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun inlineImageDictMayHoldArraysAndNameValues() {
        val parsed = parse("BI /W 4 /H 2 /BPC 8 /D [0 255] /F /Fl ID \u0000 EI")
        val image = imageOf(parsed)
        assertEquals(listOf(0.0, 255.0), (image.dict["D"] as PdfArray).items.map { num(it) })
        assertEquals(PdfName("Fl"), image.dict["F"])
    }

    @Test
    fun deeplyNestedInlineImageArrayDoesNotOverflow() {
        val depth = 20_000
        val parsed = parse(
            "BI /W 1 /H 1 /D " + "[".repeat(depth) + "0" + "]".repeat(depth) + " ID \u0000 EI"
        )
        assertTrue(parsed.warnings.any { it.contains("nested too deeply") })
        assertTrue(parsed.commands.any { it.operator == "ID" })
    }

    @Test
    fun inlineImageWithoutEiConsumesRestWithWarning() {
        val parsed = parse("BI /W 1 ID \u0000\u0001\u0002")
        assertArrayEquals(byteArrayOf(0, 1, 2), imageOf(parsed).data)
        assertTrue(parsed.warnings.any { it.contains("missing EI") })
    }

    // -- Tolerance -------------------------------------------------------------

    @Test
    fun unterminatedLiteralKeepsPrefixAndWarns() {
        val parsed = parse("1 2 m (abc")
        assertEquals(listOf("m"), parsed.commands.map { it.operator })
        assertEquals(listOf(1.0, 2.0), parsed.commands[0].operands.map { num(it) })
        assertTrue(parsed.warnings.any { it.contains("unterminated literal") })
        assertTrue(parsed.warnings.any { it.contains("trailing operands") })
    }

    @Test
    fun unbalancedDelimitersReportButNeverCrash() {
        val parsed = parse("[1 2 m ] x")
        assertEquals(listOf("m", "x"), parsed.commands.map { it.operator })
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test
    fun missingDictValueWarns() {
        val parsed = parse("<</A 1 /B>> D")
        assertTrue(parsed.warnings.any { it.contains("missing value for /B") })
    }

    @Test
    fun trailingOperandsWarn() {
        val parsed = parse("1 2 m 7 8")
        assertTrue(parsed.warnings.any { it.contains("trailing operands") })
        // Trailing digits never attach to the earlier command.
        assertEquals(2, parsed.commands[0].operands.size)
    }

    @Test
    fun commentOnlyStreamIsEmpty() {
        val parsed = parse("% only a comment\n%% another\n")
        assertTrue(parsed.commands.isEmpty())
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun boundsKickInOnRunawayOperandStream() {
        val huge = (0 until 5000).joinToString(" ") { "$it" }
        val parsed = parse(huge)
        assertTrue(
            parsed.warnings.any { it.contains("more than $MAX_OPERANDS consecutive operands") }
        )
    }
}
