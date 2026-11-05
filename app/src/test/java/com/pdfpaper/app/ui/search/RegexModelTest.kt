package com.pdfpaper.app.ui.search

import com.pdfpaper.engine.text.RegexFlag
import com.pdfpaper.engine.text.RegexMatch
import com.pdfpaper.engine.text.RegexOptions
import com.pdfpaper.engine.text.RegexPatternError
import com.pdfpaper.engine.text.RegexResult
import com.pdfpaper.engine.text.TextRect
import com.pdfpaper.engine.text.normaliseWithOffsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure regex search model (T65): rows, grouping, the two
 * exports, the summary sentences, the saved-pattern codec and the two mapping
 * helpers. No Android or coroutine types are involved, so these run on the JVM.
 */
class RegexModelTest {

    private fun match(
        page: Int = 0,
        text: String = "AB-1234",
        before: String = "\u2026invoice ",
        after: String = " dated\u2026",
        ordinal: Int = 0,
        rects: List<TextRect> = emptyList()
    ) = RegexMatch(
        pageIndex = page,
        start = 10,
        end = 10 + text.length,
        text = text,
        before = before,
        after = after,
        rects = rects,
        ordinal = ordinal
    )

    private fun matches(vararg matches: RegexMatch) = RegexResult.Matches(matches.toList())

    private fun row(
        fileId: Long = 0,
        uri: String = "",
        name: String = "report.pdf",
        page: Int = 2,
        ordinal: Int = 0,
        text: String = "AB-1",
        before: String = "x ",
        after: String = " y"
    ) = RegexRow(
        fileId = fileId,
        documentUri = uri,
        displayName = name,
        pageIndex = page,
        ordinal = ordinal,
        text = text,
        before = before,
        after = after
    )

    // ------------------------------------------------------------------ flags

    @Test
    fun flagToggleKeepsOrderIndependence() {
        val base = setOf(RegexFlag.CASE_INSENSITIVE, RegexFlag.ACROSS_LINES)
        val on = toggleRegexFlag(base, RegexFlag.WHOLE_WORD, true)
        assertEquals(base + RegexFlag.WHOLE_WORD, on)
        // Toggling the same flag on twice changes nothing.
        assertEquals(on, toggleRegexFlag(on, RegexFlag.WHOLE_WORD, true))
        // Order of the input set does not matter.
        val reversed = setOf(RegexFlag.ACROSS_LINES, RegexFlag.CASE_INSENSITIVE)
        assertEquals(
            toggleRegexFlag(base, RegexFlag.WHOLE_WORD, true),
            toggleRegexFlag(reversed, RegexFlag.WHOLE_WORD, true)
        )
        assertEquals(base, toggleRegexFlag(on, RegexFlag.WHOLE_WORD, false))
        // Off on a flag that was never on is a no-op.
        assertEquals(base, toggleRegexFlag(base, RegexFlag.MULTILINE, false))
    }

    @Test
    fun optionsMapFlagsAndBudgets() {
        val flags = setOf(RegexFlag.CASE_INSENSITIVE, RegexFlag.WHOLE_WORD)
        val options = regexOptions(flags, LIBRARY_MAX_MATCHES, LIBRARY_RUN_TIME_MS)
        assertEquals(flags, options.flags)
        assertEquals(5000, options.maxMatches)
        assertEquals(30_000L, options.runTimeLimitMs)
        // The per-page deadline and the context radius keep their defaults.
        assertEquals(RegexOptions.DEFAULT_TIME_LIMIT_MS, options.timeLimitMs)
        assertEquals(RegexOptions.DEFAULT_CONTEXT_RADIUS, options.contextRadius)
        // The defaults the app starts from.
        assertEquals(
            setOf(RegexFlag.CASE_INSENSITIVE, RegexFlag.ACROSS_LINES),
            DEFAULT_REGEX_FLAGS
        )
    }

    // ------------------------------------------------------------------- rows

    @Test
    fun rowsForPageCarriesOrdinalAndRects() {
        val rects = listOf(TextRect(10.0, 20.0, 30.0, 40.0))
        val rows = rowsForPage(
            scope = RegexScope.DOCUMENT,
            fileId = 7,
            documentUri = "content://doc",
            displayName = "report.pdf",
            pageIndex = 3,
            result = matches(match(ordinal = 0, rects = rects), match(ordinal = 1))
        )
        assertEquals(2, rows.size)
        assertEquals(3, rows[0].pageIndex)
        assertEquals(0, rows[0].ordinal)
        assertEquals(1, rows[1].ordinal)
        assertEquals(rects, rows[0].rects)
        assertEquals("AB-1234", rows[0].text)
        assertEquals("\u2026invoice AB-1234 dated\u2026", rows[0].context)
        // A document-scope row carries no file identity: there is one document.
        assertEquals(0L, rows[0].fileId)
        assertEquals("", rows[0].documentUri)
        assertEquals("report.pdf", rows[0].displayName)
        // An invalid pattern produces no rows at all.
        assertTrue(
            rowsForPage(
                RegexScope.DOCUMENT, 0, "", "report.pdf", 0,
                RegexResult.InvalidPattern("(", RegexPatternError("Unclosed group", 0))
            ).isEmpty()
        )
    }

    @Test
    fun groupingIsByFileThenPageInReadingOrder() {
        val rows = listOf(
            row(fileId = 2, uri = "content://b", name = "b.pdf", page = 5),
            row(fileId = 1, uri = "content://a", name = "a.pdf", page = 4),
            row(fileId = 2, uri = "content://b", name = "b.pdf", page = 1),
            row(fileId = 1, uri = "content://a", name = "a.pdf", page = 2),
            row(fileId = 1, uri = "content://a", name = "a.pdf", page = 4)
        )
        val groups = groupRows(rows)
        assertEquals(2, groups.size)
        // Files keep the order they first appeared in.
        assertEquals(listOf(2L, 1L), groups.map { it.fileId })
        assertEquals(listOf("b.pdf", "a.pdf"), groups.map { it.displayName })
        // Pages are read in order, not in arrival order.
        assertEquals(listOf(1, 5), groups[0].pages.map { it.pageIndex })
        assertEquals(listOf(2, 4), groups[1].pages.map { it.pageIndex })
        // Both rows on page 4 stay, in arrival order.
        assertEquals(2, groups[1].pages[1].rows.size)
        // A document-scope run is one group with one file id.
        assertEquals(1, groupRows(listOf(row(page = 1), row(page = 3))).size)
    }

    @Test
    fun uniqueUsesFilePageTextSoTheSameTextInTwoFilesIsKept() {
        val rows = listOf(
            row(fileId = 1, uri = "content://a", page = 2, text = "AB-1"),
            row(fileId = 1, uri = "content://a", page = 2, text = "AB-1"),
            row(fileId = 2, uri = "content://b", page = 2, text = "AB-1")
        )
        val unique = uniqueRows(rows)
        assertEquals(2, unique.size)
        assertEquals(listOf(1L, 2L), unique.map { it.fileId })
        assertEquals(rows[0], unique[0])
    }

    @Test
    fun uniqueDropsARepeatedRowInTheSamePage() {
        val rows = listOf(
            row(page = 1, text = "AB-1", ordinal = 0),
            row(page = 1, text = "AB-1", ordinal = 1),
            row(page = 1, text = "AB-2", ordinal = 2),
            row(page = 2, text = "AB-1", ordinal = 0)
        )
        val unique = uniqueRows(rows)
        assertEquals(3, unique.size)
        assertEquals(listOf(0, 2, 0), unique.map { it.ordinal })
        // Without the switch the state keeps every occurrence.
        val state = RegexUiState(scope = RegexScope.DOCUMENT, rows = rows)
        assertEquals(4, state.visibleRows.size)
        assertEquals(3, state.copy(uniqueOnly = true).visibleRows.size)
    }

    // ---------------------------------------------------------------- exports

    @Test
    fun csvStartsWithBomAndQuotesOnlyWhenNeeded() {
        val plain = row(page = 4, text = "AB-1", before = "a ", after = " b")
        val csv = rowsToCsv(listOf(plain))
        assertEquals('\uFEFF', csv.first())
        val lines = csv.removePrefix("\uFEFF").trimEnd('\n').split("\n")
        assertEquals("file,page,match,context", lines[0])
        // Nothing special in the fields, so nothing is quoted.
        assertEquals("report.pdf,5,AB-1,a AB-1 b", lines[1])
        assertFalse(lines[1].contains('"'))
        // The page is one-based in the file.
        assertTrue(lines[1].startsWith("report.pdf,5,"))
    }

    @Test
    fun csvEscapesQuoteCommaAndNewline() {
        val tricky = row(
            page = 0,
            text = "a,b",
            before = "he said \"hi\" ",
            after = "\nand left"
        )
        val body = rowsToCsv(listOf(tricky)).removePrefix("\uFEFF")
        // A quoted field may hold a comma, a quote and a newline; its own quotes
        // are doubled, and the row therefore spans two lines in the file.
        assertTrue(body, body.contains("\"a,b\""))
        assertTrue(body, body.contains("\"he said \"\"hi\"\" a,b\nand left\""))
        assertEquals("\"a,b\"", csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", csvField("say \"hi\""))
        assertEquals("plain", csvField("plain"))
        assertEquals("\"line\nbreak\"", csvField("line\nbreak"))
    }

    @Test
    fun txtUsesOneLinePerMatchWithFilePrefixInLibraryScope() {
        val document = row(page = 3, text = "AB-1234", before = "\u2026invoice ", after = " dated\u2026")
        assertEquals(
            "page 4: AB-1234 \u2014 \u2026invoice AB-1234 dated\u2026\n",
            rowsToTxt(listOf(document))
        )
        // A library row names its file; a document row does not, because the
        // document is already the subject of the sheet.
        val library = document.copy(fileId = 3, documentUri = "content://x", displayName = "report.pdf")
        assertEquals(
            "report.pdf page 4: AB-1234 \u2014 \u2026invoice AB-1234 dated\u2026\n",
            rowsToTxt(listOf(library))
        )
        assertEquals("", rowsToTxt(emptyList()))
        assertEquals(
            "report-matches.csv",
            regexFileName(RegexScope.DOCUMENT, "report.pdf", csv = true)
        )
        assertEquals(
            "report-matches.txt",
            regexFileName(RegexScope.DOCUMENT, "report.pdf", csv = false)
        )
        assertEquals(
            "library-matches.csv",
            regexFileName(RegexScope.LIBRARY, "report.pdf", csv = true)
        )
        assertEquals(
            "library-matches.txt",
            regexFileName(RegexScope.LIBRARY, "", csv = false)
        )
    }

    // --------------------------------------------------------------- summary

    @Test
    fun summaryForNoMatches() {
        assertEquals(
            "No matches",
            describeRegexSummary(0, 0, null, false, false, false, 0)
        )
        assertEquals(
            "No matches; 2 files could not be searched",
            describeRegexSummary(0, 0, 0, false, false, false, 2)
        )
    }

    @Test
    fun summaryCountsPagesAndFilesWithSingulars() {
        assertEquals(
            "1 match on 1 page",
            describeRegexSummary(1, 1, null, false, false, false, 0)
        )
        assertEquals(
            "7 matches on 3 pages",
            describeRegexSummary(7, 3, null, false, false, false, 0)
        )
        assertEquals(
            "7 matches in 2 files (3 pages)",
            describeRegexSummary(7, 3, 2, false, false, false, 0)
        )
        assertEquals(
            "1 match in 1 file (1 page)",
            describeRegexSummary(1, 1, 1, false, false, false, 0)
        )
        // The state derives the same sentence from its own rows.
        val state = RegexUiState(
            scope = RegexScope.LIBRARY,
            rows = listOf(
                row(fileId = 1, uri = "content://a", page = 1),
                row(fileId = 1, uri = "content://a", page = 2),
                row(fileId = 2, uri = "content://b", page = 2)
            )
        )
        // A page is a (file, page) pair: page 2 of two files is two pages.
        assertEquals("3 matches in 2 files (3 pages)", state.summary)
    }

    @Test
    fun librarySummaryCountsTheSamePageIndexInTwoFilesSeparately() {
        val state = RegexUiState(
            scope = RegexScope.LIBRARY,
            rows = listOf(
                row(fileId = 1, uri = "content://a", page = 0, text = "AB-1"),
                row(fileId = 2, uri = "content://b", page = 0, text = "AB-1")
            )
        )
        assertEquals("2 matches in 2 files (2 pages)", state.summary)
    }

    @Test
    fun summaryAppendsTruncatedTimedOutCancelledAndUnsearchable() {
        assertEquals(
            "7 matches on 3 pages; stopped after the match limit; timed out; 3 files could not be searched",
            describeRegexSummary(7, 3, null, true, true, false, 3)
        )
        assertEquals(
            "2 matches on 1 page; cancelled",
            describeRegexSummary(2, 1, null, false, false, true, 0)
        )
        assertEquals(
            "4 matches on 2 pages; 1 file could not be searched",
            describeRegexSummary(4, 2, null, false, false, false, 1)
        )
    }

    // -------------------------------------------------------- saved patterns

    @Test
    fun patternCodecRoundTripsNamesWithTabsAndNewlines() {
        val patterns = listOf(
            RegexPattern(
                id = "p1",
                name = "Invoices\ttab\nnewline",
                pattern = "AB-\\d{4}",
                flags = setOf(RegexFlag.CASE_INSENSITIVE, RegexFlag.WHOLE_WORD),
                createdAt = 1_700_000_000_000L
            ),
            RegexPattern(
                id = "p2",
                name = "\u0907\u0928\u094d\u0935\u0949\u0907\u0938",
                pattern = "\\b\\d{4}-\\d{2}-\\d{2}\\b",
                flags = emptySet(),
                createdAt = 0L
            )
        )
        val encoded = RegexPatternCodec.encode(patterns)
        // One record per line: a name's own newline cannot break the list.
        assertEquals(2, encoded.split('\n').size)
        assertEquals(patterns, RegexPatternCodec.decode(encoded))
        assertEquals(emptyList<RegexPattern>(), RegexPatternCodec.decode(null))
        assertEquals(emptyList<RegexPattern>(), RegexPatternCodec.decode(""))
    }

    @Test
    fun patternCodecDropsMalformedRecordsAndUnknownFlags() {
        val good = RegexPattern(
            id = "ok",
            name = "IDs",
            pattern = "AB-\\d{4}",
            flags = setOf(RegexFlag.CASE_INSENSITIVE),
            createdAt = 12L
        )
        val goodRecord = RegexPatternCodec.encode(listOf(good))
        val raw = listOf(
            goodRecord,
            "too|few|fields",
            "bad|!!!notbase64|AB|CASE_INSENSITIVE|5",
            "bad|" + base64("name") + "|AB|CASE_INSENSITIVE|not-a-number",
            "partial|" + base64("name") + "|AB|CASE_INSENSITIVE,BOGUS|7"
        ).joinToString("\n")
        val decoded = RegexPatternCodec.decode(raw)
        assertEquals(2, decoded.size)
        assertEquals(good, decoded[0])
        assertEquals("partial", decoded[1].id)
        assertEquals(setOf(RegexFlag.CASE_INSENSITIVE), decoded[1].flags)
    }

    // ------------------------------------------------------ notes and wording

    @Test
    fun scopeLabelsAndLibraryFlagNote() {
        assertEquals("This document", RegexScope.DOCUMENT.label)
        assertEquals("Library", RegexScope.LIBRARY.label)
        assertNull(describeRegexFlagsNote(RegexScope.DOCUMENT))
        val note = describeRegexFlagsNote(RegexScope.LIBRARY)
        assertNotNull(note)
        assertTrue(note!!, note.contains("Multiline"))
        assertTrue(note, note.contains("Across lines"))
        assertTrue(note, note.contains("line breaks"))
    }

    @Test
    fun confirmTextMentionsUnsavedEditsAndStopping() {
        val plain = regexConfirmText(7, truncated = false, timedOut = false, hasUnsavedEdits = false)
        assertTrue(plain, plain.contains("7 highlights"))
        assertTrue(plain, plain.contains("new copy"))
        assertFalse(plain, plain.contains("stopped early"))
        assertFalse(plain, plain.contains("Unsaved"))

        val single = regexConfirmText(1, truncated = false, timedOut = false, hasUnsavedEdits = false)
        assertTrue(single, single.contains("1 highlight"))
        assertFalse(single, single.contains("1 highlights"))

        val stopped = regexConfirmText(3, truncated = true, timedOut = false, hasUnsavedEdits = false)
        assertTrue(stopped, stopped.contains("stopped early"))

        val unsaved = regexConfirmText(3, truncated = false, timedOut = true, hasUnsavedEdits = true)
        assertTrue(unsaved, unsaved.contains("stopped early"))
        assertTrue(unsaved, unsaved.contains("Unsaved"))
        assertTrue(unsaved, unsaved.contains("not in the copy"))
    }

    // --------------------------------------------------------------- mapping

    @Test
    fun boundsAreTheUnionWithThreeDecimals() {
        assertEquals(
            "10.000,20.000,30.000,40.000",
            boundsOf(listOf(TextRect(10.0, 20.0, 30.0, 40.0)))
        )
        assertEquals(
            "10.000,20.000,30.000,40.000",
            boundsOf(
                listOf(
                    TextRect(10.0, 20.0, 20.0, 30.0),
                    TextRect(15.0, 25.0, 30.0, 40.0)
                )
            )
        )
        // Rounded, not truncated.
        assertEquals("1.235,2.000,3.000,4.000", boundsOf(listOf(TextRect(1.2346, 2.0, 3.0, 4.0))))
        assertNull(boundsOf(emptyList()))
    }

    @Test
    fun layoutRangeMapsANormalisedRangeBack() {
        val normalised = normaliseWithOffsets("alpha\nbeta")
        assertEquals("alpha beta", normalised.text)
        val start = normalised.text.indexOf("beta")
        val range = layoutRangeFor(normalised, start, start + "beta".length)
        assertEquals(6..9, range)
        assertEquals("beta", "alpha\nbeta".substring(range.first, range.last + 1))
        // A collapsed whitespace run maps to the source whitespace it came from.
        val space = normalised.text.indexOf(' ')
        assertEquals(5, normalised.sourceIndex(space))
        // Out-of-range input is clamped instead of throwing.
        assertEquals(0..9, layoutRangeFor(normalised, -5, 500))
        // A match is found by its ordinal, and a missing ordinal is null.
        val result = matches(match(ordinal = 0), match(ordinal = 1), match(ordinal = 2))
        assertEquals(1, rowForOrdinal(result, 1)?.ordinal)
        assertNull(rowForOrdinal(result, 9))
        assertNull(rowForOrdinal(RegexResult.InvalidPattern("(", RegexPatternError("bad", 0)), 0))
    }

    private fun base64(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
}
