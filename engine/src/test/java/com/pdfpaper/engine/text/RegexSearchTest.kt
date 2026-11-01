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
 * T65: the one matcher. Flags, the whole-word filter, the three bounds on a
 * catastrophic pattern, progress and cancel are all exercised here so the CLI
 * and both app scopes cannot drift.
 */
class RegexSearchTest {

    // ------------------------------------------------------------------ flags

    @Test
    fun caseInsensitiveByDefaultAndOff() {
        val on = RegexSearch.find("\\bAB-\\d{4}\\b", "The AB-1234 invoice")
        assertEquals(1, on.matches().size)
        assertEquals("AB-1234", on.matches()[0].text)

        val off = RegexSearch.find(
            "\\bab-\\d{4}\\b",
            "The AB-1234 invoice",
            RegexOptions(flags = setOf(RegexFlag.ACROSS_LINES))
        )
        assertEquals(0, off.matches().size)
        // The same pattern with a matching case is found when the flag is off.
        assertEquals(
            1,
            RegexSearch.find(
                "\\bAB-\\d{4}\\b",
                "The AB-1234 invoice",
                RegexOptions(flags = setOf(RegexFlag.ACROSS_LINES))
            ).matches().size
        )
    }

    @Test
    fun multilineMakesCaretMatchEveryLine() {
        val text = "aaa\nbbb"
        val withFlag = RegexOptions(flags = setOf(RegexFlag.MULTILINE))
        assertEquals(1, RegexSearch.find("^bbb", text, withFlag).matches().size)
        assertEquals(4, RegexSearch.find("^bbb", text, withFlag).matches()[0].start)
        assertEquals(0, RegexSearch.find("^bbb", text).matches().size)
    }

    @Test
    fun acrossLinesFindsASentenceOverALineBreakWithTwoBoxes() {
        val doc = pdfWith(
            listOf("BT /F1 12 Tf 50 700 Td (alpha beta) Tj 0 -14 Td (gamma delta) Tj ET")
        )
        val layout = doc.pages()[0].layout()
        assertEquals("alpha beta\ngamma delta", layout.text)
        val result = RegexSearch.find("beta\\ngamma", layout)
        val match = result.matches().single()
        assertEquals("beta\ngamma", match.text)
        // One rectangle per line fragment, not one box over the whole page.
        assertEquals(2, match.rects.size)
    }

    @Test
    fun acrossLinesOffFindsOnlyThePerLinePart() {
        val doc = pdfWith(
            listOf("BT /F1 12 Tf 50 700 Td (alpha beta) Tj 0 -14 Td (gamma delta) Tj ET")
        )
        val layout = doc.pages()[0].layout()
        val off = RegexOptions(flags = emptySet())
        assertEquals(0, RegexSearch.find("beta\\ngamma", layout, off).matches().size)
        // Each line is still searched on its own.
        val perLine = RegexSearch.find("beta", layout, off)
        assertEquals(1, perLine.matches().size)
        assertEquals(1, perLine.matches()[0].rects.size)
    }

    @Test
    fun wholeWordKeepsARealWordAndRejectsAnEmbeddedOne() {
        val text = "cat scatter cat"
        val whole = RegexOptions(
            flags = setOf(RegexFlag.CASE_INSENSITIVE, RegexFlag.ACROSS_LINES, RegexFlag.WHOLE_WORD)
        )
        val kept = RegexSearch.find("cat", text, whole).matches()
        assertEquals(2, kept.size)
        assertEquals(listOf(0, 12), kept.map { it.start })

        val all = RegexSearch.find("cat", text).matches()
        assertEquals(3, all.size)
    }

    @Test
    fun wholeWordAtBothEndsOfTheText() {
        val whole = RegexOptions(flags = setOf(RegexFlag.WHOLE_WORD))
        assertEquals(1, RegexSearch.find("cat", "cat", whole).matches().size)
        assertEquals(1, RegexSearch.find("cat", "a cat", whole).matches().size)
        // Punctuation is not a word character, so "cat." still counts.
        assertEquals(1, RegexSearch.find("cat", "cat.", whole).matches().size)
        assertEquals(0, RegexSearch.find("cat", "cats", whole).matches().size)
    }

    @Test
    fun contextIsAttachedToEachMatch() {
        val result = RegexSearch.find(
            "AB-1234",
            "invoice AB-1234 dated today",
            RegexOptions(contextRadius = 4)
        )
        val match = result.matches().single()
        // Four characters on each side, the separating spaces kept.
        assertEquals("\u2026ice ", match.before)
        assertEquals(" dat\u2026", match.after)
        assertEquals("\u2026ice AB-1234 dat\u2026", match.contextText)
    }

    @Test
    fun zeroLengthMatchesAreSkippedAndTheRunTerminates() {
        val started = System.nanoTime()
        val result = RegexSearch.find("x*", "cat")
        assertEquals(0, result.matches().size)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 2_000, "zero-length loop took $elapsedMs ms")
    }

    @Test
    fun matchCapSetsTruncated() {
        val result = RegexSearch.find(
            "x",
            "x".repeat(50_000),
            RegexOptions(maxMatches = 10)
        )
        assertEquals(10, result.matches().size)
        assertTrue(result.truncated)
        assertTrue(!result.timedOut)
    }

    // --------------------------------------------------------------- budgets

    @Test
    fun perPageDeadlineStopsACatastrophicPattern() {
        // `(a+)+$` is optimised to linear time by this JDK, so the test uses a
        // pattern that still backtracks exponentially (`(.*a){20}$` on 40 a's
        // followed by `!` never matches and takes minutes without a deadline).
        val started = System.nanoTime()
        val result = RegexSearch.find(
            "(.*a){20}$",
            "a".repeat(40) + "!",
            RegexOptions(timeLimitMs = 150, runTimeLimitMs = 150)
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.timedOut, "expected a timeout, got $result")
        assertTrue(elapsedMs < 5_000, "catastrophic pattern ran $elapsedMs ms")
    }

    @Test
    fun runDeadlineStopsALongWalk() {
        val longLine = "lorem ipsum ".repeat(300)
        val doc = pdfWith(List(3) { "BT /F1 12 Tf 50 700 Td ($longLine) Tj ET" })
        val result = doc.searchRegex(
            "ipsum",
            RegexOptions(timeLimitMs = 1500, runTimeLimitMs = 1, maxMatches = 2000)
        )
        assertTrue(result.timedOut, "expected the run deadline to stop the walk")
        assertTrue((result as RegexResult.Matches).pagesSearched < 3)
    }

    @Test
    fun nestedQuantifierReportsTooComplexOrTimesOut() {
        val deep = "(".repeat(20_000) + "a" + ")".repeat(20_000)
        val result = RegexSearch.find(deep, "a", RegexOptions(timeLimitMs = 200, runTimeLimitMs = 200))
        val tooComplex = result is RegexResult.InvalidPattern &&
            result.error.message.contains("too complex")
        assertTrue(tooComplex || result.timedOut, "expected too complex or a timeout, got $result")
    }

    @Test
    fun invalidPatternCarriesMessageAndPosition() {
        val result = RegexSearch.find("[A-Z", "ABC")
        assertTrue(result is RegexResult.InvalidPattern, "expected InvalidPattern, got $result")
        val invalid = result as RegexResult.InvalidPattern
        assertEquals("[A-Z", invalid.pattern)
        assertTrue(invalid.error.message.contains("Unclosed"), invalid.error.message)
        assertTrue(invalid.error.position >= 0, "position was ${invalid.error.position}")
    }

    @Test
    fun emptyPatternReturnsNoMatches() {
        val result = RegexSearch.find("", "anything")
        assertTrue(result is RegexResult.Matches)
        assertEquals(0, result.matches().size)
        assertTrue(!result.truncated && !result.timedOut && !result.cancelled)
    }

    // -------------------------------------------------------------- documents

    @Test
    fun aPageWithNoTextReturnsNoMatches() {
        val doc = pdfWith(listOf(""))
        val result = doc.searchRegex("anything")
        assertEquals(0, result.matches().size)
        assertEquals(1, (result as RegexResult.Matches).pagesSearched)
    }

    @Test
    fun boxesOnARotatedPageStayInsideTheMediaBox() {
        val doc = pdfWith(
            listOf("BT /F1 12 Tf 50 700 Td (rotated AB-1234) Tj ET"),
            rotate = 90
        )
        val result = doc.searchRegex("AB-1234")
        val match = result.matches().single()
        assertEquals(1, match.rects.size)
        val rect = match.rects[0]
        assertTrue(rect.x0 >= 0 && rect.x1 <= 612, "x outside the MediaBox: $rect")
        assertTrue(rect.y0 >= 0 && rect.y1 <= 792, "y outside the MediaBox: $rect")
        assertTrue(rect.width > 0 && rect.height > 0, "degenerate rect: $rect")
    }

    @Test
    fun documentWalkReportsPagesAndProgress() {
        val doc = pdfWith(List(3) { "BT /F1 12 Tf 50 700 Td (id XY-1 here) Tj ET" })
        val progress = ArrayList<Pair<Int, Int>>()
        val result = doc.searchRegex("XY-1", onProgress = { done, total -> progress += done to total })
        val matches = result.matches()
        assertEquals(3, matches.size)
        assertEquals(listOf(0, 1, 2), matches.map { it.pageIndex })
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
        assertEquals(3, (result as RegexResult.Matches).pagesSearched)
        // Every match has boxes from its own page.
        assertTrue(matches.all { it.rects.isNotEmpty() })
    }

    @Test
    fun documentWalkCanBeCancelledAndKeepsWhatItFound() {
        val doc = pdfWith(List(2) { "BT /F1 12 Tf 50 700 Td (XY-1) Tj ET" })
        var checks = 0
        val result = doc.searchRegex("XY-1", isCancelled = { checks++ > 0 })
        assertTrue(result.cancelled)
        assertEquals(1, result.matches().size)
        assertEquals(1, (result as RegexResult.Matches).pagesSearched)
    }

    @Test
    fun matchesNeverSpanAPageBoundary() {
        val doc = pdfWith(
            listOf(
                "BT /F1 12 Tf 50 700 Td (AB) Tj ET",
                "BT /F1 12 Tf 50 700 Td (CD) Tj ET"
            )
        )
        assertEquals(0, doc.searchRegex("BC").matches().size)
        val split = doc.searchRegex("AB|CD").matches()
        assertEquals(2, split.size)
        assertEquals(0, split[0].pageIndex)
        assertEquals(1, split[1].pageIndex)
    }

    // --------------------------------------------------------------- helpers

    private fun RegexResult.matches(): List<RegexMatch> =
        (this as? RegexResult.Matches)?.matches ?: emptyList()

    private fun pdfWith(contents: List<String>, rotate: Int = 0): PdfDocument {
        val objects = ArrayList<TestObj>()
        val kids = ArrayList<String>()
        var number = 3
        for (unused in contents) {
            kids += "$number 0 R"
            number += 2
        }
        objects += TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>")
        objects += TestObj(
            2, 0,
            "<< /Type /Pages /Kids [ ${kids.joinToString(" ")} ] /Count ${contents.size} >>"
        )
        number = 3
        for (content in contents) {
            val rotateEntry = if (rotate != 0) " /Rotate $rotate" else ""
            objects += TestObj(
                number, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]$rotateEntry " +
                    "/Resources << /Font << /F1 $fontNumber 0 R >> >> /Contents ${number + 1} 0 R >>"
            )
            objects += streamObj(number + 1, "", content)
            number += 2
        }
        objects += fontObj()
        return PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow()
    }

    private fun streamObj(num: Int, dict: String, content: String): TestObj {
        val data = content.toByteArray(StandardCharsets.ISO_8859_1)
        val body = ("<< /Length ${data.size} $dict>>\nstream\n")
            .toByteArray(StandardCharsets.US_ASCII) +
            data + "\nendstream".toByteArray(StandardCharsets.US_ASCII)
        return TestObj(num, 0, body)
    }

    private val fontNumber = 100

    private fun fontObj(): TestObj {
        val widths = (32..126).joinToString(" ") { "500" }
        return TestObj(
            fontNumber, 0,
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding " +
                "/FirstChar 32 /Widths [ $widths ] >>"
        )
    }
}
