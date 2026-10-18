package com.pdfpaper.engine.edit

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.PdfTextRun
import com.pdfpaper.engine.text.extractText
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T16 acceptance: each fixture's text runs are listed, and replace/delete/move
 * either rewrite the original operators (font kept) or fall back to the bundled
 * font, with the strategy and overflow reported. Outputs are re-opened and the
 * text extracted to prove the page still reads correctly.
 */
class TextRunEditTest {

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun runs(page: PdfPage): List<PdfTextRun> = page.extractText().runs

    private fun runText(page: PdfPage): String = runs(page).joinToString("|") { it.text }

    private fun fontNames(page: PdfPage): Set<String> =
        ((page.resources?.get("Font")) as? PdfDict)?.entries?.keys?.map { it.name }?.toSet().orEmpty()

    // ------------------------------------------------------------ listing

    @Test
    fun listsRunsForEveryFixture() {
        open(TextRunFixtures.standardHelvetica()).use { doc ->
            val list = PdfEditOps.listTextRuns(doc, 0)
            assertEquals(listOf("Hello", "World", "second line"), list.map { it.text })
            assertEquals(24.0, list[0].fontSize, 1e-9)
            assertEquals("Helvetica", list[0].fontName)
            list.forEach { assertFalse(it.invisible) }
        }
        open(TextRunFixtures.wordSubset()).use { doc ->
            val list = PdfEditOps.listTextRuns(doc, 0)
            assertEquals(listOf("WORD", "EXPORT"), list.map { it.text })
            assertEquals("SubsetFont", list[0].fontName)
        }
        open(TextRunFixtures.latexDifferences()).use { doc ->
            assertEquals(listOf("\u00C4\u00D8"), PdfEditOps.listTextRuns(doc, 0).map { it.text })
        }
        open(TextRunFixtures.scannedOcr()).use { doc ->
            val list = PdfEditOps.listTextRuns(doc, 0)
            assertEquals(listOf("scanned ocr text"), list.map { it.text })
            assertTrue(list[0].invisible, "OCR layer must be reported invisible")
        }
        open(TextRunFixtures.cidNoToUnicode()).use { doc ->
            assertEquals(listOf("Hi"), PdfEditOps.listTextRuns(doc, 0).map { it.text })
        }
    }

    // ----------------------------------------------- strategy 1: rewrite

    @Test
    fun quoteOperatorsAreListedAndEdited() {
        open(TextRunFixtures.quoteOperators()).use { doc ->
            assertEquals(listOf("Alpha", "Beta"), PdfEditOps.listTextRuns(doc, 0).map { it.text })
        }
        val document = open(TextRunFixtures.quoteOperators())
        val result = PdfEditOps.saveWithReport(
            document,
            listOf(DeleteTextEdit(0, 0), ReplaceTextEdit(0, 1, "B"))
        ).getOrThrow()
        document.close()
        assertEquals(
            setOf("delete", "replace"),
            result.textReports.map { it.operation }.toSet()
        )
        open(result.bytes).use { reopened ->
            assertEquals(listOf("B"), runs(reopened.pages().single()).map { it.text })
        }
    }
    @Test
    fun standardFontReplacesInPlaceAndKeepsFollowingTextPut() {
        val document = open(TextRunFixtures.standardHelvetica())
        val before = runs(document.pages().single())
        val worldX = before.first { it.text == "World" }.x0

        val result = PdfEditOps.saveWithReport(document, listOf(ReplaceTextEdit(0, 0, "Hi"))).getOrThrow()
        document.close()

        val report = result.textReports.single()
        assertEquals("replace", report.operation)
        assertEquals(TextEditStrategy.REWRITE, report.strategy)
        assertFalse(report.overflow)

        open(result.bytes).use { reopened ->
            assertFalse(reopened.file.rebuiltFromScan)
            val page = reopened.pages().single()
            assertTrue(runText(page).contains("Hi"), "got '${runText(page)}'")
            assertFalse(runText(page).contains("Hello"))
            // No reflow: the following run did not move.
            val world = runs(page).first { it.text == "World" }
            assertEquals(worldX, world.x0, 1e-3)
            // Strategy 1 keeps the original font and adds no fallback.
            assertTrue(fontNames(page).none { it.startsWith("PpF") }, "unexpected fallback font")
        }
    }

    @Test
    fun widerReplacementReportsOverflow() {
        val document = open(TextRunFixtures.standardHelvetica())
        val result = PdfEditOps
            .saveWithReport(document, listOf(ReplaceTextEdit(0, 0, "Hello World and more text")))
            .getOrThrow()
        document.close()
        val report = result.textReports.single()
        assertEquals(TextEditStrategy.REWRITE, report.strategy)
        assertTrue(report.overflow, "expected overflow")
        assertTrue(report.overflowAmount > 0.0)
    }

    @Test
    fun latexDifferencesReplacesUsingOriginalFont() {
        val document = open(TextRunFixtures.latexDifferences())
        val result = PdfEditOps.saveWithReport(document, listOf(ReplaceTextEdit(0, 0, "XY"))).getOrThrow()
        document.close()
        assertEquals(TextEditStrategy.REWRITE, result.textReports.single().strategy)
        open(result.bytes).use { reopened ->
            assertEquals("XY", runText(reopened.pages().single()))
        }
    }

    @Test
    fun unrepresentableCharacterFallsBackToBundledFont() {
        val document = open(TextRunFixtures.latexDifferences())
        // Greek alpha has no code in the /Differences + WinAnsi encoding.
        val result = PdfEditOps.saveWithReport(document, listOf(ReplaceTextEdit(0, 0, "A\u03B1B"))).getOrThrow()
        document.close()
        assertEquals(TextEditStrategy.FALLBACK_FONT, result.textReports.single().strategy)
        open(result.bytes).use { reopened ->
            val page = reopened.pages().single()
            assertTrue(fontNames(page).any { it.startsWith("PpF") }, "expected an embedded fallback font")
            // The replacement is drawn as '?', matching WinAnsi's best effort.
            assertEquals("A?B", runText(page))
        }
    }

    // ------------------------------------------------- strategy on subsets

    @Test
    fun subsetFontRewritesWhenTheCodeIsCovered() {
        val document = open(TextRunFixtures.wordSubset())
        val result = PdfEditOps.saveWithReport(document, listOf(ReplaceTextEdit(0, 0, "WORDY"))).getOrThrow()
        document.close()
        val report = result.textReports.single()
        assertEquals(TextEditStrategy.REWRITE, report.strategy)
        assertTrue(report.overflow, "WORDY is wider than WORD")
        open(result.bytes).use { reopened ->
            assertTrue(runText(reopened.pages().single()).contains("WORDY"))
        }
    }

    @Test
    fun subsetFontFallsBackForUncoveredGlyph() {
        val document = open(TextRunFixtures.wordSubset())
        // The subset only declares A–Z (codes 65..90); lowercase has no code.
        val result = PdfEditOps.saveWithReport(document, listOf(ReplaceTextEdit(0, 1, "export"))).getOrThrow()
        document.close()
        assertEquals(TextEditStrategy.FALLBACK_FONT, result.textReports.single().strategy)
        open(result.bytes).use { reopened ->
            val page = reopened.pages().single()
            assertTrue(fontNames(page).any { it.startsWith("PpF") })
            assertTrue(runText(page).contains("export"), "got '${runText(page)}'")
            assertTrue(runText(page).contains("WORD"), "the untouched run must survive")
        }
    }

    // ----------------------------------------------------------- Type0

    @Test
    fun type0WithoutToUnicodeAlwaysFallsBack() {
        val document = open(TextRunFixtures.cidNoToUnicode())
        val result = PdfEditOps.saveWithReport(document, listOf(ReplaceTextEdit(0, 0, "Ho"))).getOrThrow()
        document.close()
        assertEquals(TextEditStrategy.FALLBACK_FONT, result.textReports.single().strategy)
        open(result.bytes).use { reopened ->
            assertTrue(runText(reopened.pages().single()).contains("Ho"))
        }
    }

    @Test
    fun type0WithToUnicodeAndEmbeddedProgramRewrites() {
        val document = open(TextRunFixtures.cidEmbedded())
        val result = PdfEditOps.saveWithReport(document, listOf(ReplaceTextEdit(0, 0, "H"))).getOrThrow()
        document.close()
        assertEquals(TextEditStrategy.REWRITE, result.textReports.single().strategy)
        open(result.bytes).use { reopened ->
            assertEquals("H", runText(reopened.pages().single()))
        }
    }

    // ------------------------------------------------------ delete / move

    @Test
    fun deletesRunWithoutMovingTheFollowingText() {
        val document = open(TextRunFixtures.standardHelvetica())
        val worldX = runs(document.pages().single()).first { it.text == "World" }.x0

        val result = PdfEditOps.saveWithReport(document, listOf(DeleteTextEdit(0, 0))).getOrThrow()
        document.close()
        assertEquals("delete", result.textReports.single().operation)
        assertEquals(TextEditStrategy.REMOVED, result.textReports.single().strategy)

        open(result.bytes).use { reopened ->
            val page = reopened.pages().single()
            assertFalse(runText(page).contains("Hello"))
            assertEquals(listOf("World", "second line"), runs(page).map { it.text })
            assertEquals(worldX, runs(page).first { it.text == "World" }.x0, 1e-3)
        }
    }

    @Test
    fun movesRunAndKeepsItsText() {
        val document = open(TextRunFixtures.standardHelvetica())
        val before = runs(document.pages().single()).first { it.text == "Hello" }

        val result = PdfEditOps.saveWithReport(document, listOf(MoveTextEdit(0, 0, 30.0, -10.0))).getOrThrow()
        document.close()
        assertEquals("move", result.textReports.single().operation)

        open(result.bytes).use { reopened ->
            val moved = runs(reopened.pages().single()).first { it.text == "Hello" }
            assertEquals(before.x0 + 30.0, moved.x0, 0.5)
            assertEquals(before.y0 - 10.0, moved.y0, 0.5)
        }
    }

    @Test
    fun scannedOcrTextStaysInvisibleAfterReplacement() {
        val document = open(TextRunFixtures.scannedOcr())
        val result = PdfEditOps.saveWithReport(document, listOf(ReplaceTextEdit(0, 0, "fixed ocr"))).getOrThrow()
        document.close()
        assertEquals(TextEditStrategy.REWRITE, result.textReports.single().strategy)
        open(result.bytes).use { reopened ->
            val page = reopened.pages().single()
            assertEquals("fixed ocr", runText(page))
            assertTrue(runs(page).single().renderMode == 3, "OCR text must remain invisible")
        }
    }

    // --------------------------------------------------- malformed / missing

    @Test
    fun missingRunIdIsReportedAndDoesNotChangeTheText() {
        val document = open(TextRunFixtures.standardHelvetica())
        val result = PdfEditOps.saveWithReport(document, listOf(ReplaceTextEdit(0, 99, "nope"))).getOrThrow()
        document.close()
        val report = result.textReports.single()
        assertEquals(TextEditStrategy.NONE, report.strategy)
        assertTrue(report.note?.contains("not found") == true)
        open(result.bytes).use { reopened ->
            assertEquals(listOf("Hello", "World", "second line"), runs(reopened.pages().single()).map { it.text })
        }
    }

    // ------------------------------------------- external-viewer inspection

    @Test
    fun writesFixturesAndOutputsForExternalInspection() {
        val dir = File("build/textrun-review")
        dir.mkdirs()
        val fixtures = mapOf(
            "standard" to TextRunFixtures.standardHelvetica(),
            "word" to TextRunFixtures.wordSubset(),
            "latex" to TextRunFixtures.latexDifferences(),
            "scanned" to TextRunFixtures.scannedOcr(),
            "cid" to TextRunFixtures.cidNoToUnicode(),
            "cid-embedded" to TextRunFixtures.cidEmbedded(),
            "quotes" to TextRunFixtures.quoteOperators()
        )
        for ((name, bytes) in fixtures) File(dir, "fixture-$name.pdf").writeBytes(bytes)

        val edits = listOf(
            Triple("standard-replace", "standard", listOf(ReplaceTextEdit(0, 0, "Hi"))),
            Triple("standard-delete", "standard", listOf(DeleteTextEdit(0, 0))),
            Triple("standard-move", "standard", listOf(MoveTextEdit(0, 0, 30.0, -10.0))),
            Triple("word-replace", "word", listOf(ReplaceTextEdit(0, 0, "WORDY"))),
            Triple("word-fallback", "word", listOf(ReplaceTextEdit(0, 1, "export"))),
            Triple("latex-fallback", "latex", listOf(ReplaceTextEdit(0, 0, "A\u03B1B"))),
            Triple("scanned-replace", "scanned", listOf(ReplaceTextEdit(0, 0, "fixed ocr"))),
            Triple("cid-replace", "cid", listOf(ReplaceTextEdit(0, 0, "Ho"))),
            Triple("cid-embedded-replace", "cid-embedded", listOf(ReplaceTextEdit(0, 0, "H"))),
            Triple("quotes-edit", "quotes", listOf(DeleteTextEdit(0, 0), ReplaceTextEdit(0, 1, "B")))
        )
        for ((out, fixture, pageEdits) in edits) {
            val document = open(fixtures.getValue(fixture))
            val result = PdfEditOps.saveWithReport(document, pageEdits).getOrThrow()
            document.close()
            File(dir, "out-$out.pdf").writeBytes(result.bytes)
        }
        assertTrue(File(dir, "fixture-standard.pdf").isFile)
    }
}
