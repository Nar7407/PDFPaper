package com.pdfpaper.app.ui.snip

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.table.CsvDelimiter
import com.pdfpaper.engine.table.MergeHandling
import com.pdfpaper.engine.table.Table
import com.pdfpaper.engine.table.TableCell
import com.pdfpaper.engine.table.TableOptions
import com.pdfpaper.engine.table.TableResult
import com.pdfpaper.engine.table.TableRow
import com.pdfpaper.engine.table.TableSource
import com.pdfpaper.engine.table.TableWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure snip logic behind the marquee table tool (T60). No
 * Android or Compose types are involved, so these run on the JVM.
 */
class SnipModelTest {

    /** A 200 x 100 pt page at 1:1, so a fraction maps to a round number. */
    private val plan = PageGeometry.plan(PageBox(0.0, 0.0, 200.0, 100.0, 0), 1.0)

    private fun table(header: Boolean = true, vararg rows: List<String>): Table =
        Table(
            rows = rows.map { row -> TableRow(row.map { TableCell(it) }) },
            hasHeader = header
        )

    // ------------------------------------------------------------ the marquee

    @Test
    fun dragBecomesARectangleInPageSpace() {
        val rect = snipRectFromDrag(plan, 0.25f, 0.25f, 0.75f, 0.75f)!!
        // The bitmap's y grows down, PDF user space grows up, so the top of the
        // drag is the larger y value.
        assertEquals(50.0, rect.left, 1e-4)
        assertEquals(150.0, rect.right, 1e-4)
        assertEquals(25.0, rect.bottom, 1e-4)
        assertEquals(75.0, rect.top, 1e-4)
    }

    @Test
    fun aDragBackwardsStillProducesAPositiveRectangle() {
        val rect = snipRectFromDrag(plan, 0.75f, 0.75f, 0.25f, 0.25f)!!
        assertEquals(50.0, rect.left, 1e-4)
        assertEquals(150.0, rect.right, 1e-4)
    }

    @Test
    fun aTapIsNotABox() {
        assertNull(snipRectFromDrag(plan, 0.5f, 0.5f, 0.505f, 0.5f))
        assertNull(snipRectFromDrag(plan, 0.5f, 0.5f, 0.5f, 0.5f))
    }

    @Test
    fun aPointRoundTripsThroughTheFractionAndBack() {
        val rect = PdfRect(50.0, 25.0, 150.0, 75.0)
        val (fx, fy) = snipFractionOf(plan, (rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2)
        assertEquals(0.5f, fx, 1e-4f)
        assertEquals(0.5f, fy, 1e-4f)
    }

    // ------------------------------------------------------------ stitching

    @Test
    fun aRepeatedHeaderIsDroppedWhenPagesAreStitched() {
        val first = table(true, listOf("Item", "Qty"), listOf("Bolt", "4"))
        val second = table(true, listOf("Item", "Qty"), listOf("Nut", "7"))
        val stitched = stitchTables(listOf(first, second))!!
        assertEquals(3, stitched.rows.size)
        assertEquals("Bolt", stitched.rows[1].cells[0].text)
        assertEquals("Nut", stitched.rows[2].cells[0].text)
        assertTrue(stitched.hasHeader)
    }

    @Test
    fun aDifferentFirstRowIsKept() {
        val first = table(true, listOf("Item", "Qty"), listOf("Bolt", "4"))
        val second = table(true, listOf("Item", "Price"), listOf("Nut", "7"))
        val stitched = stitchTables(listOf(first, second))!!
        assertEquals(4, stitched.rows.size)
    }

    @Test
    fun stitchingCanKeepTheRepeatedHeader() {
        val first = table(true, listOf("Item", "Qty"), listOf("Bolt", "4"))
        val second = table(true, listOf("Item", "Qty"), listOf("Nut", "7"))
        val stitched = stitchTables(listOf(first, second), dropRepeatedHeader = false)!!
        assertEquals(4, stitched.rows.size)
    }

    @Test
    fun oneTableIsNotStitched() {
        val only = table(true, listOf("a", "b"))
        assertEquals(only, stitchTables(listOf(only)))
        assertNull(stitchTables(emptyList()))
    }

    // ------------------------------------------------------------ formatting

    @Test
    fun markdownEscapesPipesAndKeepsTheColumns() {
        val source = table(
            true,
            listOf("Name", "Note"),
            listOf("a|b", "line one\nline two")
        )
        val text = formatSnip(listOf(source), SnipSettings(format = SnipFormat.MARKDOWN))
        assertEquals(
            "| Name | Note |\n" +
                "| --- | --- |\n" +
                "| a\\|b | line one<br>line two |\n",
            text
        )
    }

    @Test
    fun markdownAlwaysHasAHeaderRow() {
        val source = table(false, listOf("a", "b"), listOf("c", "d"))
        val text = formatSnip(listOf(source), SnipSettings(format = SnipFormat.MARKDOWN))
        assertTrue(text.startsWith("|  |  |\n| --- | --- |\n"))
        assertTrue(text.contains("| a | b |"))
    }

    @Test
    fun csvQuotesTheDelimiterAndHonoursTheSemicolon() {
        val source = table(true, listOf("Name", "Note"), listOf("Smith, J", "said \"hi\""))
        val comma = formatSnip(listOf(source), SnipSettings(format = SnipFormat.CSV))
        assertEquals("Name,Note\n\"Smith, J\",\"said \"\"hi\"\"\"\n", comma)

        val semicolon = formatSnip(
            listOf(source),
            SnipSettings(
                format = SnipFormat.CSV,
                options = SnipOptions(delimiter = CsvDelimiter.SEMICOLON)
            )
        )
        assertEquals("Name;Note\nSmith, J;\"said \"\"hi\"\"\"\n", semicolon)
    }

    @Test
    fun tsvFlattensTabsAndLineBreaks() {
        val source = table(true, listOf("Name", "Note"), listOf("a\tb", "one\ntwo"))
        val text = formatSnip(listOf(source), SnipSettings(format = SnipFormat.TSV))
        assertEquals("Name\tNote\na b\tone two\n", text)
    }

    @Test
    fun plainTextRightAlignsANumericColumn() {
        val source = table(true, listOf("Item", "Qty"), listOf("Bolt", "4"), listOf("Nut", "17"))
        val lines = formatSnip(listOf(source), SnipSettings(format = SnipFormat.TEXT))
            .trimEnd()
            .lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("Item"))
        // The number column is padded on the left, so the digits line up.
        assertTrue(lines[1].endsWith("  4"))
        assertTrue(lines[2].endsWith(" 17"))
    }

    @Test
    fun mergedCellsFollowTheOption() {
        val merged = Table(
            rows = listOf(
                TableRow(listOf(TableCell("Title", colSpan = 2))),
                TableRow(listOf(TableCell("a"), TableCell("b")))
            ),
            hasHeader = false
        )
        val repeat = formatSnip(
            listOf(merged),
            SnipSettings(format = SnipFormat.CSV, options = SnipOptions(merged = MergedCells.REPEAT))
        )
        assertEquals("Title,Title\na,b\n", repeat)
        val blank = formatSnip(
            listOf(merged),
            SnipSettings(format = SnipFormat.CSV, options = SnipOptions(merged = MergedCells.BLANK))
        )
        assertEquals("Title,\na,b\n", blank)
    }

    @Test
    fun noTableFallsBackToThePlainWords() {
        val result = TableResult(
            table = null,
            confidence = 0.0,
            source = TableSource.TEXT,
            notes = listOf("no table detected"),
            words = listOf(
                TableWord("hello", 0.0, 40.0, 30.0, 50.0),
                TableWord("world", 35.0, 40.0, 70.0, 50.0)
            )
        )
        val text = formatSnip(result, SnipSettings(format = SnipFormat.MARKDOWN))
        assertEquals("hello world", text.trimEnd())
    }

    // ------------------------------------------------------------ the summary

    @Test
    fun confidenceIsAPercentageWithItsSource() {
        assertEquals("90% confident (text)", confidenceLabel(0.9, TableSource.TEXT))
        assertEquals(
            "50% confident (text) · check the hard cases",
            confidenceLabel(0.5, TableSource.TEXT)
        )
        assertEquals(
            "40% confident (OCR) · check the hard cases",
            confidenceLabel(0.4, TableSource.OCR)
        )
        assertEquals("100% confident (OCR)", confidenceLabel(2.0, TableSource.OCR))
    }

    @Test
    fun descriptionNamesTheSizeAndConfidence() {
        val result = TableResult(
            table = table(true, listOf("a", "b"), listOf("c", "d")),
            confidence = 0.8,
            source = TableSource.TEXT
        )
        assertEquals(
            "Table detected · 2 rows × 2 columns · 80% confident (text)",
            describeSnip(result)
        )
    }

    @Test
    fun aMissingTableIsSaidPlainly() {
        val result = TableResult(null, 0.0, TableSource.TEXT, listOf("too little text"))
        assertEquals(
            "No table detected. Plain text is offered instead.",
            describeSnip(result)
        )
        assertEquals(listOf("too little text"), snipNotes(result))
    }

    @Test
    fun tableSizeCountsSpannedColumns() {
        val merged = Table(
            rows = listOf(
                TableRow(listOf(TableCell("wide", colSpan = 3))),
                TableRow(listOf(TableCell("a"), TableCell("b"), TableCell("c")))
            ),
            hasHeader = false
        )
        assertEquals(2 to 3, tableSize(merged))
        assertEquals(3, merged.columns)
    }

    @Test
    fun mergedSpanIsReportedOnlyForARealMerge() {
        assertEquals(2 to 1, mergedSpan(TableCell("wide", colSpan = 2)))
        assertEquals(1 to 2, mergedSpan(TableCell("tall", rowSpan = 2)))
        assertNull(mergedSpan(TableCell("plain")))
    }

    @Test
    fun previewCellKeepsTheValue() {
        assertEquals("a|b", previewCell(TableCell("a|b")))
    }

    // ------------------------------------------------------------ names, settings

    @Test
    fun theFileNameDropsTheFolderAndThePdfSuffix() {
        assertEquals("report.txt", snipFileName("/tmp/out/report.pdf", SnipFormat.TEXT))
        assertEquals("table.csv", snipFileName("", SnipFormat.CSV))
        assertEquals("scan.tsv", snipFileName("C:\\docs\\scan.pdf", SnipFormat.TSV))
    }

    @Test
    fun theFormatCarriesItsExtensionAndMimeType() {
        assertEquals("md", SnipFormat.MARKDOWN.extension)
        assertEquals("text/csv", SnipFormat.CSV.mime)
        assertEquals(SnipFormat.MARKDOWN, SnipFormat.fromName(null))
        assertEquals(SnipFormat.MARKDOWN, SnipFormat.fromName("NOPE"))
        assertEquals(SnipFormat.TSV, SnipFormat.fromName("TSV"))
    }

    @Test
    fun optionsReachTheEngine() {
        val options = SnipOptions(
            headerRow = false,
            merged = MergedCells.BLANK,
            trimSpaces = false,
            delimiter = CsvDelimiter.SEMICOLON
        ).engine()
        assertEquals(false, options.headerRow)
        assertEquals(MergeHandling.LEAVE_BLANK, options.mergeHandling)
        assertEquals(false, options.trimSpaces)
        assertEquals(CsvDelimiter.SEMICOLON, options.delimiter)
    }

    @Test
    fun theDefaultSettingsAreMarkdownWithAHeader() {
        val settings = SnipSettings()
        assertEquals(SnipFormat.MARKDOWN, settings.format)
        assertEquals(true, settings.options.headerRow)
        assertEquals(TableOptions.DEFAULT.headerRow, settings.options.engine().headerRow)
        assertNotNull(settings.options.merged)
    }

    @Test
    fun aFormattingRunOverTheLiveTablesKeepsTheHeader() {
        val settings = SnipSettings(format = SnipFormat.CSV)
        val tables = listOf(
            table(true, listOf("h1", "h2"), listOf("1", "2")),
            table(true, listOf("h1", "h2"), listOf("3", "4"))
        )
        assertEquals("h1,h2\n1,2\n3,4\n", formatSnip(tables, settings))
    }
}
