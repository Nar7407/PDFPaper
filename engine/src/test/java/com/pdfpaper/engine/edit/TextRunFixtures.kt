package com.pdfpaper.engine.edit

import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * In-memory acceptance fixtures for T16 text-run editing. Building them in Kotlin
 * keeps the repository free of generated binaries while still covering the five
 * real-world shapes the task names:
 *
 *  1. [standardHelvetica]  - a standard-14 font, no embedding (viewer supplies it)
 *  2. [wordSubset]         - a Word-style subset TrueType with /Widths + /FontFile2
 *  3. [latexDifferences]   - a LaTeX-style Type1 with a /Differences encoding
 *  4. [scannedOcr]         - a scanned page: an image plus an invisible OCR layer
 *  5. [cidNoToUnicode]     - a Type0 Identity-H font with no /ToUnicode
 *  6. [cidEmbedded]        - a Type0 font with /ToUnicode and an embedded program
 *
 * Every fixture is a one-page PDF whose single content stream is object 4.
 */
object TextRunFixtures {

    /** /BaseFont /Helvetica AFM widths (1/1000 em) for ASCII, default 500. */
    private val HELVETICA: Map<Int, Int> = mapOf(
        ' '.code to 278, 'H'.code to 722, 'W'.code to 944, 'e'.code to 556,
        'l'.code to 222, 'o'.code to 556, 'r'.code to 333, 'd'.code to 556,
        'i'.code to 222, 's'.code to 500, 'c'.code to 500, 'a'.code to 556,
        'n'.code to 556, 't'.code to 278
    )

    /** The bundled Noto program, used as the embedded font for the subset fixtures. */
    val notoProgram: ByteArray by lazy {
        val path = "com/pdfpaper/engine/edit/NotoSans-Regular.ttf"
        checkNotNull(TextRunFixtures::class.java.classLoader.getResourceAsStream(path)) {
            "bundled fallback font missing: $path"
        }.use { it.readBytes() }
    }

    fun standardHelvetica(): ByteArray {
        val content = "BT /F1 24 Tf 72 700 Td (Hello) Tj (World) Tj ET\n" +
            "BT /F1 12 Tf 72 650 Td (second line) Tj ET"
        return onePage(content, "/Font << /F1 5 0 R >>", listOf(
            TestObj(
                5, 0,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding " +
                    "/FirstChar 32 /LastChar 122 /Widths [ ${helveticaWidths()} ] >>"
            )
        ))
    }

    fun wordSubset(): ByteArray {
        val content = "BT /F1 24 Tf 72 700 Td [(WORD) -120 (EXPORT)] TJ ET"
        return onePage(content, "/Font << /F1 5 0 R >>", listOf(
            TestObj(
                5, 0,
                "<< /Type /Font /Subtype /TrueType /BaseFont /ABCDEF+SubsetFont " +
                    "/Encoding /WinAnsiEncoding /FirstChar 65 /LastChar 90 " +
                    "/Widths [ ${List(26) { "700" }.joinToString(" ")} ] /FontDescriptor 6 0 R >>"
            ),
            TestObj(6, 0, "<< /Type /FontDescriptor /FontName /ABCDEF+SubsetFont /Flags 4 " +
                "/FontBBox [-1000 -400 3000 2000] /Ascent 1069 /Descent -293 /CapHeight 714 /StemV 80 " +
                "/FontFile2 7 0 R >>"),
            binaryStream(7, "", notoProgram)
        ))
    }

    fun latexDifferences(): ByteArray {        // Codes 65/66 remap to /Adieresis (Ä) and /Oslash (Ø) via /Differences.
        val content = "BT /F1 12 Tf 72 700 Td (AB) Tj ET"
        return onePage(content, "/Font << /F1 5 0 R >>", listOf(
            TestObj(
                5, 0,
                "<< /Type /Font /Subtype /Type1 /BaseFont /CMR10 /Encoding " +
                    "<< /BaseEncoding /WinAnsiEncoding /Differences [ 65 /Adieresis 66 /Oslash ] >> " +
                    "/FirstChar 32 /LastChar 122 /Widths [ ${helveticaWidths()} ] >>"
            )
        ))
    }

    fun scannedOcr(): ByteArray {
        val content = "q 1 0 0 1 72 500 cm /Im0 Do Q\n" +
            "BT 3 Tr /F1 12 Tf 100 600 Td (scanned ocr text) Tj ET"
        return onePage(content, "/Font << /F1 5 0 R >> /XObject << /Im0 6 0 R >>", listOf(
            TestObj(
                5, 0,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding " +
                    "/FirstChar 32 /LastChar 122 /Widths [ ${helveticaWidths()} ] >>"
            ),
            binaryStream(
                6,
                "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 ",
                byteArrayOf(0x40, 0x80.toByte(), 0xC0.toByte())
            )
        ))
    }

    /** Standard 14 with the `'` and `"` text-showing operators (§9.4.3, Table 106). */
    fun quoteOperators(): ByteArray {
        val content = "BT /F1 12 Tf 14 TL 72 700 Td (Alpha) ' 0 0 (Beta) \" ET"
        return onePage(content, "/Font << /F1 5 0 R >>", listOf(
            TestObj(
                5, 0,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding " +
                    "/FirstChar 32 /LastChar 122 /Widths [ ${helveticaWidths()} ] >>"
            )
        ))
    }

    /** Type0 Identity-H with no /ToUnicode: CIDs read back as code points. */
    fun cidNoToUnicode(): ByteArray {
        val content = "BT /F1 24 Tf 72 700 Td <00480069> Tj ET"
        return onePage(content, "/Font << /F1 5 0 R >>", listOf(
            TestObj(
                5, 0,
                "<< /Type /Font /Subtype /Type0 /BaseFont /TestCID /Encoding /Identity-H " +
                    "/DescendantFonts [6 0 R] >>"
            ),
            TestObj(
                6, 0,
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /TestCID " +
                    "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
                    "/DW 1000 /W [ 72 [ 600 ] 105 [ 300 ] ] /FontDescriptor 7 0 R >>"
            ),
            TestObj(
                7, 0,
                "<< /Type /FontDescriptor /FontName /TestCID /Flags 4 " +
                    "/FontBBox [-1000 -400 3000 2000] /Ascent 1069 /Descent -293 /CapHeight 714 /StemV 80 >>"
            )
        ))
    }

    /** Type0 with /ToUnicode and an embedded program: glyphs exist → rewrite. */
    fun cidEmbedded(): ByteArray {
        val content = "BT /F1 24 Tf 72 700 Td <00480069> Tj ET"
        return onePage(content, "/Font << /F1 5 0 R >>", listOf(
            TestObj(
                5, 0,
                "<< /Type /Font /Subtype /Type0 /BaseFont /ABCDEF+NotoSans /Encoding /Identity-H " +
                    "/DescendantFonts [6 0 R] /ToUnicode 8 0 R >>"
            ),
            TestObj(
                6, 0,
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /ABCDEF+NotoSans " +
                    "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
                    "/DW 1000 /W [ 72 [ 600 ] 105 [ 300 ] ] /FontDescriptor 7 0 R >>"
            ),
            TestObj(7, 0, "<< /Type /FontDescriptor /FontName /ABCDEF+NotoSans /Flags 4 " +
                "/FontBBox [-1000 -400 3000 2000] /Ascent 1069 /Descent -293 /CapHeight 714 /StemV 80 " +
                "/FontFile2 9 0 R >>"),
            textStream(
                8, "",
                "begincmap\n2 beginbfchar\n<0048> <0048>\n<0069> <0069>\nendbfchar\nendcmap"
            ),
            binaryStream(9, "", notoProgram)
        ))
    }

    // -- Helpers --------------------------------------------------------------

    private fun onePage(content: String, resources: String, extra: List<TestObj>): ByteArray =
        buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                TestObj(
                    3, 0,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                        "/Resources << $resources >> /Contents 4 0 R >>"
                ),
                textStream(4, "", content)
            ) + extra
        )

    private fun helveticaWidths(): String {
        val sb = StringBuilder()
        for (code in 32..122) {
            if (code > 32) sb.append(' ')
            sb.append(HELVETICA[code] ?: 500)
        }
        return sb.toString()
    }

    private fun textStream(num: Int, dict: String, content: String): TestObj =
        binaryStream(num, dict, content.toByteArray(StandardCharsets.ISO_8859_1))

    private fun binaryStream(num: Int, dict: String, data: ByteArray): TestObj {
        val out = ByteArrayOutputStream()
        out.write("<< /Length ${data.size} $dict>>\nstream\n".toByteArray(StandardCharsets.US_ASCII))
        out.write(data)
        out.write("\nendstream".toByteArray(StandardCharsets.US_ASCII))
        return TestObj(num, 0, out.toByteArray())
    }
}
