package com.pdfpaper.engine.text

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T7 acceptance: extracted text matches expected text for six fixture PDFs
 * (WinAnsi, Standard, MacRoman, /Differences, /ToUnicode, Type0 Identity-H)
 * plus graceful-fallback and positioning/operator coverage.
 *
 * All advances are hand-computed 1/1000-em PDF font widths.
 */
class TextExtractionTest {

    // ------------------------------------------------------------- helpers

    private fun openPdf(objects: List<TestObj>): PdfDocument =
        PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow()

    private fun extract(objects: List<TestObj>): PageText =
        openPdf(objects).use { doc -> doc.pages().first().extractText() }

    private fun joinedTexts(t: PageText): String = t.runs.joinToString("|") { it.text }

    /** Stream object (num); [dict] holds extra entries (no closing ">>"). */
    private fun streamObj(num: Int, dict: String, content: String): TestObj {
        val data = content.toByteArray(StandardCharsets.ISO_8859_1)
        val body = ("<< /Length ${data.size} $dict>>\nstream\n").toByteArray(StandardCharsets.US_ASCII) +
            data + "\nendstream".toByteArray(StandardCharsets.US_ASCII)
        return TestObj(num, 0, body)
    }

    /**
     * One-page PDF: object 1 catalog, 2 pages, 3 page, 4 content stream;
     * [extraObjects] start at number 5 (fonts, ToUnicode streams, ...).
     * [fontRefEntries] go straight into /Resources /Font.
     */
    private fun onePagePdf(content: String, fontRefEntries: String, extraObjects: List<TestObj> = emptyList()): List<TestObj> =
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << $fontRefEntries >> >> /Contents 4 0 R >>"
            ),
            streamObj(4, "", content)
        ) + extraObjects

    // Helvetica widths (AFM, 1/1000 em) for the glyphs this file exercises.
    private val helv: Map<Int, Long> = mapOf(
        32 to 278L,  // space
        72 to 722L,  // H
        87 to 944L,  // W
        100 to 556L, // d
        101 to 556L, // e
        108 to 222L, // l
        111 to 556L, // o
        114 to 333L  // r
    )

    private fun helvWidthsString(): String {
        val sb = StringBuilder()
        for (c in 32..116) {
            if (c > 32) sb.append(' ')
            sb.append(helv[c] ?: 0L)
        }
        return sb.toString()
    }

    // ------------------------------------------------------- T7 acceptance

    @Test
    fun winAnsiSimpleFontTextPositionsAndSize() {
        val objs = onePagePdf(
            content = "BT /F1 24 Tf 100 700 Td (Hello World) Tj ET",
            fontRefEntries = "/F1 5 0 R",
            extraObjects = listOf(
                TestObj(
                    5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding " +
                        "/FirstChar 32 /Widths [ ${helvWidthsString()} ] >>"
                )
            )
        )
        val runs = extract(objs).runs
        assertEquals(1, runs.size)
        val r = runs[0]
        assertEquals("Hello World", r.text)
        assertEquals(24.0, r.fontSize, 1e-9)
        assertEquals("Helvetica", r.fontName)
        assertEquals(100.0, r.x0, 1e-9)
        assertEquals(700.0, r.y0, 1e-9)
        // sum(widths)/1000 × 24: (722+556+222+222+556+278+944+556+333+222+556)/1000 × 24 = 124.008
        assertEquals(224.008, r.x1, 1e-6)
        assertEquals(724.0, r.y1, 1e-9)
    }

    @Test
    fun standardEncodingMapsApostropheCodeToQuoteright() {
        val objs = onePagePdf(
            content = "BT /F1 12 Tf 10 100 Td (Can't) Tj ET",
            fontRefEntries = "/F1 5 0 R",
            extraObjects = listOf(
                TestObj(5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /TimesSub /Encoding /StandardEncoding >>")
            )
        )
        assertEquals("Can\u2019t", joinedTexts(extract(objs)))
    }

    @Test
    fun macRomanEncodingDecodesHighBytes() {
        // (São) — ã = 0x8B in MacRomanEncoding.
        val objs = onePagePdf(
            content = "BT /F1 14 Tf 10 100 Td (S\u008Bo) Tj ET",
            fontRefEntries = "/F1 5 0 R",
            extraObjects = listOf(
                TestObj(5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /MacFont /Encoding /MacRomanEncoding >>")
            )
        )
        assertEquals("S\u00E3o", joinedTexts(extract(objs)))
    }

    @Test
    fun differencesOverrideBaseEncoding() {
        // 65 → /Aring (Å), 87 → /endash (–); WinAnsi base still applies elsewhere.
        val objs = onePagePdf(
            content = "BT /F1 12 Tf 10 100 Td (A-W) Tj ET",
            fontRefEntries = "/F1 5 0 R",
            extraObjects = listOf(
                TestObj(
                    5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /DiffFont /Encoding " +
                        "<< /BaseEncoding /WinAnsiEncoding /Differences [ 65 /Aring 87 /endash ] >> >>"
                )
            )
        )
        assertEquals("\u00C5-\u2013", joinedTexts(extract(objs)))
    }

    private fun toUnicodeCMap(): String = listOf(
        "/CIDInit /ProcSet findresource begin",
        "12 dict begin",
        "begincmap",
        "1 begincodespacerange",
        "<00> <FF>",
        "endcodespacerange",
        "1 beginbfchar",
        "<41> <00E7>",
        "endbfchar",
        "1 beginbfrange",
        "<31> <33> <0250>",
        "endbfrange",
        "endcmap",
        "end end"
    ).joinToString("\n")

    @Test
    fun toUnicodeBeatsBaseEncodingForSimpleFont() {
        // Map: 0x41→ç, 0x31..0x33→0x250... (ə ɐ ɑ); code 66 falls back to base.
        val objs = onePagePdf(
            content = "BT /F1 12 Tf 10 100 Td (AB123) Tj ET",
            fontRefEntries = "/F1 5 0 R",
            extraObjects = listOf(
                TestObj(
                    5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /UniFont /Encoding /WinAnsiEncoding /ToUnicode 6 0 R >>"
                ),
                streamObj(6, "", toUnicodeCMap())
            )
        )
        assertEquals("çB\u0250\u0251\u0252", joinedTexts(extract(objs)))
    }

    @Test
    fun type0IdentityHDecodesCidsAndUsesWWidths() {
        // F1: /W covers cid 1[500] 2[600] 3[700]; cid 65 falls back to /DW 600.
        // F2: range form /W [10 20 500] → cids 10..20 at width 500.
        val objs = onePagePdf(
            content = "BT /F1 12 Tf 50 300 Td <000100020041> Tj ET\n" +
                "BT /F2 10 Tf 50 200 Td <000A0014> Tj ET",
            fontRefEntries = "/F1 5 0 R /F2 7 0 R",
            extraObjects = listOf(
                TestObj(
                    5, 0, "<< /Type /Font /Subtype /Type0 /BaseFont /TestCID /Encoding /Identity-H " +
                        "/DescendantFonts [6 0 R] /ToUnicode 8 0 R >>"
                ),
                TestObj(
                    6, 0, "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /TestCID " +
                        "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /DW 600 /W [ 1 [ 500 600 700 ] ] >>"
                ),
                TestObj(
                    7, 0, "<< /Type /Font /Subtype /Type0 /BaseFont /TestCID2 /Encoding /Identity-H " +
                        "/DescendantFonts [<</Type /Font /Subtype /CIDFontType2 /BaseFont /T2 /DW 900 /W [ 10 20 500 ] >>] /ToUnicode 8 0 R >>"
                ),
                streamObj(
                    8, "", listOf(
                        "begincmap",
                        "3 beginbfchar",
                        "<0001> <00C5>",
                        "<0002> <20AC>",
                        "<0041> <005A>",
                        "endbfchar",
                        "2 beginbfchar",
                        "<000A> <00E9>",
                        "<0014> <00E8>",
                        "endbfchar",
                        "endcmap"
                    ).joinToString("\n")
                )
            )
        )
        val runs = extract(objs).runs
        assertEquals(2, runs.size)
        assertEquals("\u00C5\u20ACZ", runs[0].text)
        assertEquals(12.0, runs[0].fontSize, 1e-9)
        // (500+600+600)/1000 × 12 = 20.4 from x=50
        assertEquals(70.4, runs[0].x1, 1e-6)
        assertEquals(300.0, runs[0].y0, 1e-9)
        assertEquals("\u00E9\u00E8", runs[1].text)
        assertEquals("TestCID2", runs[1].fontName)
        // (500+500)/1000 × 10 = 10 from x=50
        assertEquals(60.0, runs[1].x1, 1e-6)
    }

    // --------------------------------------------------- graceful fallback

    @Test
    fun unknownFontWithoutWidthsExtractsTextWithoutCrash() {
        val objs = onePagePdf(
            content = "BT /F1 12 Tf 10 100 Td (Bye) Tj ET",
            fontRefEntries = "/F1 5 0 R",
            extraObjects = listOf(
                TestObj(5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /NotInstalled-XX >>")
            )
        )
        val page = extract(objs)
        assertEquals("Bye", joinedTexts(page))
        assertEquals(1, page.runs.size)
        assertTrue(page.warnings.isNotEmpty(), "missing /Widths expected to degrade, not crash")
    }

    @Test
    fun missingFontResourceFallsBackGracefully() {
        // Tf names a font that is not in /Resources at all.
        val objs = onePagePdf(
            content = "BT /F99 12 Tf 10 100 Td (bare) Tj ET",
            fontRefEntries = ""
        )
        val page = extract(objs)
        assertEquals("bare", page.runs.single().text)
        assertEquals(null, page.runs.single().fontName)
        assertTrue(page.warnings.any { it.contains("F99") })
    }

    @Test
    fun pageWithoutResourcesYieldsEmptyRunsNotCrash() {
        val objs = listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>"),
            streamObj(4, "", "1 0 0 RG 10 w 50 m 100 l S")
        )
        val page = extract(objs)
        assertTrue(page.runs.isEmpty())
        assertTrue(page.warnings.isEmpty())
    }

    // -------------------------------------------- positioning and operators

    @Test
    fun readingOrderFollowsContentWithTdAndTStar() {
        val objs = onePagePdf(
            content = "BT /F1 12 Tf 1 0 0 1 10 100 Tm 20 TL (first) Tj T* (second) Tj ET",
            fontRefEntries = "/F1 5 0 R",
            extraObjects = listOf(
                TestObj(5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Simple >>")
            )
        )
        val runs = extract(objs).runs
        assertEquals(listOf("first", "second"), runs.map { it.text })
        assertEquals(100.0, runs[0].y0, 1e-9)
        assertEquals(80.0, runs[1].y0, 1e-9) // T* moves by -TL
    }

    @Test
    fun quoteApostropheAndDoubleQuoteOperators() {
        val objs = onePagePdf(
            content = "BT /F1 12 Tf 10 100 Td 14 TL (first) Tj (second) ' (third) ' 2 1 (fourth) \" ET",
            fontRefEntries = "/F1 5 0 R",
            extraObjects = listOf(
                TestObj(5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Simple >>")
            )
        )
        val runs = extract(objs).runs
        assertEquals(listOf("first", "second", "third", "fourth"), runs.map { it.text })
        assertEquals(100.0, runs[0].y0, 1e-9)
        assertEquals(86.0, runs[1].y0, 1e-9)
        assertEquals(72.0, runs[2].y0, 1e-9)
        assertEquals(58.0, runs[3].y0, 1e-9)
    }

    @Test
    fun tdAccumulatesRelativeToCurrentLineOrigin() {
        val objs = onePagePdf(
            content = "BT /F1 12 Tf 30 500 Td (a) Tj 25 0 Td (b) Tj ET",
            fontRefEntries = "/F1 5 0 R",
            extraObjects = listOf(
                TestObj(5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Simple /FirstChar 97 /Widths [ 600 ] >>")
            )
        )
        val runs = extract(objs).runs
        assertEquals(30.0, runs[0].x0, 1e-9)
        // Glyph advances move Tm only; Td offsets are relative to the current
        // LINE START (the text line matrix), matching mainstream extractors.
        assertEquals(55.0, runs[1].x0, 1e-9)
        assertEquals(30.0 + 600 / 1000.0 * 12, runs[0].x1, 1e-6)
    }

    @Test
    fun tjSplitsRunsAndAppliesKerning() {
        val objs = onePagePdf(
            content = "BT /F1 24 Tf 100 700 Td [(Hel) 200 (lo)] TJ ET",
            fontRefEntries = "/F1 5 0 R",
            extraObjects = listOf(
                TestObj(
                    5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding " +
                        "/FirstChar 32 /Widths [ ${helvWidthsString()} ] >>"
                )
            )
        )
        val runs = extract(objs).runs
        assertEquals(listOf("Hel", "lo"), runs.map { it.text })
        // start of "lo" = 100 + (722+556+222)/1000×24 - 200/1000×24
        assertEquals(100.0 + 1500 * 24 / 1000.0 - 200 * 24 / 1000.0, runs[1].x0, 1e-6)
    }

    @Test
    fun wordSpacingAppliesPerSpaceCode() {
        val objs = onePagePdf(
            content = "BT /F1 10 Tf 50 Tw 10 100 Td (  ) Tj ET",
            fontRefEntries = "/F1 5 0 R",
            extraObjects = listOf(
                TestObj(5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FirstChar 32 /Widths [ 278 ] >>")
            )
        )
        val run = extract(objs).runs.single()
        // 2 spaces × ((278/1000 × 10) + 50) = 2 × 52.78
        assertEquals(105.56, run.x1 - run.x0, 1e-9)
    }

    @Test
    fun charSpacingAndHorizontalScaleWidenRun() {
        val objs = onePagePdf(
            content = "BT /F1 10 Tf 10 100 Td (dd) Tj 2 Tc (dd) Tj 50 Tz ET\n" +
                "BT /F1 10 Tf 2 Tc 50 Tz 10 100 Td (dd) Tj ET",
            fontRefEntries = "/F1 5 0 R",
            extraObjects = listOf(
                TestObj(5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Simple /FirstChar 100 /Widths [ 600 ] >>")
            )
        )
        val runs = extract(objs).runs
        // charSpace applies per glyph, inside the Tz scaling: (6+2)×0.5 = 4 per glyph
        assertEquals(12.0, runs[0].x1 - runs[0].x0, 1e-9) // no Tc yet
        assertEquals(16.0, runs[1].x1 - runs[1].x0, 1e-9) // + Tc 2: 2×8
        assertEquals(8.0, runs[2].x1 - runs[2].x0, 1e-9) // + Tz 50: 2×4
    }

    @Test
    fun riseLiftsBoundingBoxAndScaleZeroCollapses() {
        val objs = onePagePdf(
            content = "BT /F1 10 Tf 4 Ts 10 100 Td (x) Tj 0 Tz (y) Tj ET",
            fontRefEntries = "/F1 5 0 R",
            extraObjects = listOf(
                TestObj(5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Simple /FirstChar 120 /Widths [ 600 600 ] >>")
            )
        )
        val runs = extract(objs).runs
        assertEquals(104.0, runs[0].y0, 1e-9) // baseline 100 + rise 4
        assertEquals(114.0, runs[0].y1, 1e-9) // + fontSize 10
        assertEquals(0.0, runs[1].x1 - runs[1].x0, 1e-9) // Tz 0 collapses advance
        assertEquals("y", runs[1].text)
    }

    @Test
    fun cmMatrixTransformsBoundingBox() {
        // scale up by 2 and translate by (10, 5): bbox scales accordingly.
        val objs = onePagePdf(
            content = "q 2 0 0 2 10 5 cm BT /F1 10 Tf 0 0 Td (x) Tj ET Q",
            fontRefEntries = "/F1 5 0 R",
            extraObjects = listOf(
                TestObj(5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Simple /FirstChar 120 /Widths [ 600 ] >>")
            )
        )
        val run = extract(objs).runs.single()
        assertEquals(10.0, run.x0, 1e-9)  // (0,0) → (10,5)
        assertEquals(5.0, run.y0, 1e-9)
        // advance 6 × 2 = 12 → x1 = 22; height 10 × 2 = 20 → y1 = 25
        assertEquals(22.0, run.x1, 1e-9)
        assertEquals(25.0, run.y1, 1e-9)
        assertEquals(10.0, run.fontSize, 1e-9) // fontSize stays the Tf value (text space)
    }
}
