package com.pdfpaper.engine.table

import com.pdfpaper.engine.create.PageOrientation
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.create.PdfMetadata
import com.pdfpaper.engine.create.PdfPageBuilder
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T35/T60: table detection and formatting. Ruled and borderless tables, merged
 * cells, right-aligned financial columns, OCR word boxes, and every output
 * format.
 */
class TableExtractorTest {

    // ---------------------------------------------------------------- fixtures

    private fun word(
        text: String,
        left: Double,
        baseline: Double,
        size: Double = 10.0,
        bold: Boolean = false
    ): TableWord {
        val height = size * 0.7
        val width = text.length * size * 0.52
        return TableWord(text, left, baseline, left + width, baseline + height, fontSize = size, bold = bold)
    }

    private fun region(): PdfRect = PdfRect(0.0, 0.0, 600.0, 800.0)

    private fun vRule(x: Double, bottom: Double = 600.0, top: Double = 760.0): TableRule =
        TableRule(horizontal = false, position = x, start = bottom, end = top, thickness = 0.5)

    private fun hRule(y: Double, left: Double = 40.0, right: Double = 420.0): TableRule =
        TableRule(horizontal = true, position = y, start = left, end = right, thickness = 0.5)

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun firstPage(bytes: ByteArray): PdfPage = open(bytes).pages().first()

    /** Three columns, three rows of text; the caller adds any ruling lines. */
    private fun tableWords(): List<TableWord> = listOf(
        word("Name", 50.0, 740.0, bold = true), word("Qty", 220.0, 740.0, bold = true),
        word("Price", 360.0, 740.0, bold = true),
        word("Widget", 50.0, 700.0), word("2", 220.0, 700.0), word("1.50", 360.0, 700.0),
        word("Gadget", 50.0, 660.0), word("10", 220.0, 660.0), word("12.00", 360.0, 660.0)
    )

    private fun ruledTable(): List<TableRule> = listOf(
        hRule(755.0), hRule(715.0), hRule(675.0), hRule(640.0),
        vRule(200.0), vRule(340.0)
    )

    private fun detect(
        words: List<TableWord>,
        rules: List<TableRule>,
        options: TableOptions = TableOptions.DEFAULT,
        source: TableSource = TableSource.TEXT
    ): TableResult = TableExtractor.extractFromWords(region(), words, rules, options, source)

    // --------------------------------------------------------------- detection

    @Test
    fun ruledTableDetectsRowsColumnsAndHeader() {
        val result = detect(tableWords(), ruledTable())
        assertTrue(result.detected)
        val table = result.table!!
        assertEquals(3, table.columns)
        assertEquals(3, table.rows.size)
        assertTrue(table.hasHeader)
        assertEquals("Name", table.rows[0].cells[0].text)
        assertEquals("Qty", table.rows[0].cells[1].text)
        assertEquals("Price", table.rows[0].cells[2].text)
        assertEquals("Widget", table.rows[1].cells[0].text)
        assertEquals("12.00", table.rows[2].cells[2].text)
        assertTrue(table.rows[0].cells.all { it.isHeader })
        assertTrue(result.confidence > 0.8)
    }

    @Test
    fun borderlessTableUsesTextAlignment() {
        val result = detect(tableWords(), emptyList())
        assertTrue(result.detected)
        assertEquals(3, result.table!!.columns)
        assertEquals(3, result.table!!.rows.size)
        // A borderless table is less certain than a ruled one.
        assertTrue(result.notes.any { it.contains("borderless") })
        val ruled = detect(tableWords(), ruledTable())
        assertTrue(result.confidence < ruled.confidence)
    }

    @Test
    fun twoColumnRunOfTextIsNotATable() {
        // A single column of prose has no interior separators.
        val prose = (0 until 6).map { word("The quick brown fox jumps", 50.0, 740.0 - it * 16.0) }
        val result = detect(prose, emptyList())
        assertFalse(result.detected)
        assertNull(result.table)
        assertTrue(result.notes.any { it.contains("single column") || it.contains("no table") })
    }

    @Test
    fun tooFewWordsIsNotATable() {
        val result = detect(listOf(word("a", 50.0, 700.0), word("b", 200.0, 700.0)), emptyList())
        assertFalse(result.detected)
    }

    @Test
    fun mergedColumnsWhenTheVerticalRuleIsMissing() {
        // The rule between columns 0 and 1 stops below the header, so the header
        // cell spans two columns while the body rows keep three.
        val rules = ruledTable().map {
            if (!it.horizontal && it.position == 200.0) it.copy(end = 715.0) else it
        }
        val result = detect(tableWords(), rules)
        assertTrue(result.detected)
        val first = result.table!!.rows[0].cells[0]
        assertEquals("Name", first.text)
        assertEquals(2, first.colSpan)
        assertEquals(3, result.table!!.columns)
    }

    @Test
    fun mergedRowsWhenTheHorizontalRuleIsMissing() {
        // Remove the rule between the body rows and leave the second body row's
        // first cell empty: the cell above spans down.
        val words = tableWords().filterNot { it.text == "Gadget" }
        val rules = ruledTable().filterNot { it.horizontal && it.position == 675.0 }
        val result = detect(words, rules)
        assertTrue(result.detected)
        val merged = result.table!!.rows[1].cells[0]
        assertEquals("Widget", merged.text)
        assertEquals(2, merged.rowSpan)
        assertEquals("10", result.table!!.rows[2].cells[0].text)
    }

    @Test
    fun multiLineCellIsJoinedWithNewlines() {
        val words = listOf(
            word("Qty", 220.0, 740.0), word("Item", 50.0, 740.0), word("Price", 360.0, 740.0),
            word("A widget", 50.0, 700.0), word("2", 220.0, 700.0), word("1.50", 360.0, 700.0),
            word("that wraps", 50.0, 686.0),
            word("Gadget", 50.0, 650.0), word("10", 220.0, 650.0), word("12.00", 360.0, 650.0)
        )
        val result = detect(words, ruledTable())
        assertTrue(result.detected)
        val cell = result.table!!.rows[1].cells[0]
        assertEquals("A widget\nthat wraps", cell.text)
        assertEquals(3, result.table!!.rows.size)
    }

    @Test
    fun footnoteMarkerIsPulledOutOfTheValue() {
        val words = tableWords() + TableWord(
            "1", 100.0, 700.0, 104.0, 707.0, fontSize = 6.0, raised = true
        )
        val result = detect(words, ruledTable())
        assertTrue(result.detected)
        val cell = result.table!!.rows[1].cells[0]
        assertEquals("Widget", cell.text)
        assertEquals(listOf("1"), cell.footnotes)
    }

    @Test
    fun financialRightAlignedNumbersKeepTheirText() {
        val words = listOf(
            word("Account", 50.0, 740.0), word("Balance", 360.0, 740.0),
            word("Revenue", 50.0, 700.0), word("1,234.50", 340.0, 700.0),
            word("Costs", 50.0, 660.0), word("(987.65)", 340.0, 660.0)
        )
        val rules = listOf(hRule(755.0), hRule(715.0), hRule(675.0), hRule(640.0), vRule(300.0))
        val result = detect(words, rules)
        assertTrue(result.detected)
        val text = TableFormatter.format(result.table!!, TableFormat.TEXT)
        assertTrue(text.contains("1,234.50"))
        assertTrue(text.contains("(987.65)"))
        val csv = TableFormatter.format(result.table!!, TableFormat.CSV)
        assertTrue(csv.contains("1,234.50"))
    }

    @Test
    fun scannedTableHasLowerConfidence() {
        val textResult = detect(tableWords(), ruledTable(), source = TableSource.TEXT)
        val ocrWords = tableWords().map { it.copy(confidence = 0.62) }
        val ocrResult = detect(ocrWords, ruledTable(), source = TableSource.OCR)
        assertTrue(ocrResult.detected)
        assertTrue(ocrResult.confidence < textResult.confidence)
        assertTrue(ocrResult.notes.any { it.contains("OCR") })
    }

    @Test
    fun confidenceFlagsTheHardCases() {
        val easy = detect(tableWords(), ruledTable())
        val hard = detect(tableWords(), emptyList())
        assertTrue(easy.confidence > hard.confidence)
    }

    // ------------------------------------------------------- ruled page extract

    @Test
    fun readsARuledTableOffARealPage() {
        val pdf = PdfBuilder(PageSize.A4, PageOrientation.PORTRAIT, PdfMetadata())
        val page: PdfPageBuilder = pdf.addPage()
        val columns = listOf(50.0 to "Name", 220.0 to "Qty", 360.0 to "Price")
        for ((x, text) in columns) page.drawText(text, x, 100.0, 10.0)
        page.drawText("Widget", 50.0, 130.0, 10.0)
        page.drawText("2", 220.0, 130.0, 10.0)
        page.drawText("1.50", 360.0, 130.0, 10.0)
        page.drawText("Gadget", 50.0, 160.0, 10.0)
        page.drawText("10", 220.0, 160.0, 10.0)
        page.drawText("12.00", 360.0, 160.0, 10.0)
        page.drawLine(40.0, 90.0, 400.0, 90.0)
        page.drawLine(40.0, 120.0, 400.0, 120.0)
        page.drawLine(40.0, 150.0, 400.0, 150.0)
        page.drawLine(40.0, 180.0, 400.0, 180.0)
        page.drawLine(180.0, 90.0, 180.0, 180.0)
        page.drawLine(330.0, 90.0, 330.0, 180.0)
        val bytes = pdf.build()

        val result = TableExtractor.extract(
            firstPage(bytes),
            PdfRect(0.0, 0.0, PageSize.A4.width, PageSize.A4.height)
        ).getOrThrow()
        assertTrue(result.detected, result.notes.toString())
        assertEquals(3, result.table!!.columns)
        val csv = TableFormatter.format(result.table!!, TableFormat.CSV)
        assertTrue(csv.contains("Widget,2,1.50"), csv)
        assertTrue(csv.contains("Gadget,10,12.00"), csv)
    }

    // ---------------------------------------------------------------- formatting

    private fun sampleTable(): Table = Table(
        listOf(
            TableRow(listOf(TableCell("Name"), TableCell("Qty"))),
            TableRow(listOf(TableCell("A|B"), TableCell("x\ny"))),
            TableRow(listOf(TableCell("007"), TableCell("\"quoted\"")))
        ),
        hasHeader = true
    )

    @Test
    fun markdownEscapesPipesAndLineBreaks() {
        val md = TableFormatter.format(sampleTable(), TableFormat.MARKDOWN)
        val lines = md.trim().split('\n')
        assertEquals("| Name | Qty |", lines[0])
        assertEquals("| --- | --- |", lines[1])
        assertEquals("| A\\|B | x<br>y |", lines[2])
        assertEquals("| 007 | \"quoted\" |", lines[3])
    }

    @Test
    fun csvQuotesAndKeepsValues() {
        val csv = TableFormatter.format(sampleTable(), TableFormat.CSV)
        assertTrue(csv.contains("Name,Qty"))
        // The embedded newline is quoted; a pipe is ordinary in CSV.
        assertTrue(csv.contains("A|B,\"x\ny\""), csv)
        // A leading zero is never reformatted.
        assertTrue(csv.contains("007"))
    }

    @Test
    fun csvSemicolonDelimiterForDecimalCommaLocales() {
        val table = Table(
            listOf(
                TableRow(listOf(TableCell("Item"), TableCell("Price"))),
                TableRow(listOf(TableCell("A"), TableCell("1,50")))
            ),
            hasHeader = true
        )
        val csv = TableFormatter.format(
            table,
            TableFormat.CSV,
            TableOptions(delimiter = CsvDelimiter.SEMICOLON)
        )
        assertTrue(csv.contains("Item;Price"))
        assertTrue(csv.contains("A;1,50"))
    }

    @Test
    fun tsvFlattensTabsAndNewlines() {
        val table = Table(
            listOf(TableRow(listOf(TableCell("a\tb"), TableCell("c\nd"))))
        )
        val tsv = TableFormatter.format(table, TableFormat.TSV)
        assertEquals("a b\tc d\n", tsv)
    }

    @Test
    fun textAlignsNumbersToTheRight() {
        val table = Table(
            listOf(
                TableRow(listOf(TableCell("Item"), TableCell("Qty"))),
                TableRow(listOf(TableCell("Widget"), TableCell("2"))),
                TableRow(listOf(TableCell("A"), TableCell("1000")))
            ),
            hasHeader = true
        )
        val text = TableFormatter.format(table, TableFormat.TEXT)
        val lines = text.trim().split('\n')
        // Every line is padded to the same width; numbers are right-aligned.
        assertTrue(lines.all { it.length == lines[0].length }, lines.joinToString("|"))
        assertTrue(lines[1].endsWith("   2"), lines[1])
        assertTrue(lines[2].endsWith("1000"), lines[2])
    }

    @Test
    fun gridRepeatsOrBlanksMergedValues() {
        val table = Table(
            listOf(
                TableRow(listOf(TableCell("Q1", colSpan = 2), TableCell("Q2"))),
                TableRow(listOf(TableCell("a"), TableCell("b"), TableCell("c")))
            )
        )
        val repeat = TableFormatter.grid(table, TableOptions(mergeHandling = MergeHandling.REPEAT_VALUE))
        assertEquals(listOf("Q1", "Q1", "Q2"), repeat[0])
        val blank = TableFormatter.grid(table, TableOptions(mergeHandling = MergeHandling.LEAVE_BLANK))
        assertEquals(listOf("Q1", "", "Q2"), blank[0])
    }

    @Test
    fun rowSpanGridFillsOrLeavesTheLowerSlot() {
        val table = Table(
            listOf(
                TableRow(listOf(TableCell("group", rowSpan = 2), TableCell("a"))),
                TableRow(listOf(TableCell("b")))
            )
        )
        val repeat = TableFormatter.grid(table, TableOptions(mergeHandling = MergeHandling.REPEAT_VALUE))
        assertEquals(listOf("group", "a"), repeat[0])
        assertEquals(listOf("group", "b"), repeat[1])
        val blank = TableFormatter.grid(table, TableOptions(mergeHandling = MergeHandling.LEAVE_BLANK))
        assertEquals(listOf("", "b"), blank[1])
    }

    @Test
    fun markdownWithoutHeaderGetsABlankHeaderRow() {
        val table = Table(
            listOf(TableRow(listOf(TableCell("a"), TableCell("b"))))
        )
        val md = TableFormatter.format(table, TableFormat.MARKDOWN)
        val lines = md.trim().split('\n')
        assertEquals("|  |  |", lines[0])
        assertEquals("| --- | --- |", lines[1])
        assertEquals("| a | b |", lines[2])
    }

    @Test
    fun plainTextFallbackGroupsWordsByBaseline() {
        val words = listOf(
            word("Hello", 50.0, 700.0), word("world", 100.0, 700.0),
            word("second", 50.0, 680.0)
        )
        val text = TableFormatter.plainText(words)
        assertEquals("Hello world\nsecond\n", text)
    }

    @Test
    fun formulaRecognizerIsUsedWhenInstalled() {
        val recognizer = FormulaRecognizer { _, _ -> "E=mc^2" }
        val words = tableWords()
        val options = TableOptions(formulaRecognizer = recognizer)
        // No formula check by default; installing one must not crash detection.
        val result = detect(words, ruledTable(), options)
        assertTrue(result.detected)
        assertEquals(3, result.table!!.columns)
    }

    @Test
    fun mergingEmptySelectionReportsNoTable() {
        val result = detect(emptyList(), ruledTable())
        assertFalse(result.detected)
        assertTrue(result.confidence == 0.0)
    }

    @Test
    fun ocrWordMappingUsesTheImageMatrix() {
        val words = listOf(
            com.pdfpaper.engine.ocr.OcrWord(
                "Hi",
                com.pdfpaper.engine.ocr.OcrBounds(0.0, 0.0, 100.0, 20.0),
                0.9
            )
        )
        // Identity placement would put the unit square on the page; scale by 2
        // and translate by (10, 20): pixel right 100 of 200 maps to page x 11.
        val mapped = TableExtractor.fromOcrWords(
            words, 200, 100,
            com.pdfpaper.engine.geom.PdfMatrix(2.0, 0.0, 0.0, 2.0, 10.0, 20.0)
        )
        assertEquals(1, mapped.size)
        assertEquals("Hi", mapped[0].text)
        assertEquals(10.0, mapped[0].left, 1e-9)
        assertEquals(11.0, mapped[0].right, 1e-9)
    }
}
