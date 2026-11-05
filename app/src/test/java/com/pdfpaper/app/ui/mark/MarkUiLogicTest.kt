package com.pdfpaper.app.ui.mark

import com.pdfpaper.engine.mark.BatesPlacement
import com.pdfpaper.engine.mark.MarkAlign
import com.pdfpaper.engine.mark.NumberFormat
import com.pdfpaper.engine.mark.PageSelection
import com.pdfpaper.engine.mark.WatermarkPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the mark screens that can be checked without a device: the
 * page range the user types, the tokens in a header or footer, and the
 * geometry the live preview draws.
 */
class MarkUiLogicTest {

    /** A US Letter page in points, the way the engine sees it. */
    private val letter = MarkBox(0.0, 0.0, 612.0, 792.0)

    // ---- The page range ---------------------------------------------------

    @Test
    fun aSinglePageBecomesTheZeroBasedPageNumber() {
        assertEquals(setOf(0), parsePageRange("1"))
        assertEquals(setOf(4), parsePageRange("5"))
    }

    @Test
    fun aRangeOfPagesBecomesEveryPageInIt() {
        assertEquals(setOf(2, 3, 4), parsePageRange("3-5"))
    }

    @Test
    fun aListOfPagesAndRangesIsReadTogether() {
        assertEquals(setOf(0, 2, 3, 4, 6), parsePageRange("1,3-5,7"))
    }

    @Test
    fun spacesAroundThePagesAreIgnored() {
        assertEquals(setOf(0, 2, 3, 4), parsePageRange(" 1 , 3 - 5 "))
    }

    @Test
    fun rubbishInTheRangeIsSkippedInsteadOfFailing() {
        assertEquals(setOf(0), parsePageRange("1,abc,2x,,x-3"))
    }

    @Test
    fun aBackwardsRangeIsSkipped() {
        assertEquals(emptySet<Int>(), parsePageRange("5-3"))
    }

    @Test
    fun pageZeroIsNotAPage() {
        assertEquals(emptySet<Int>(), parsePageRange("0"))
    }

    @Test
    fun anEmptyRangeSelectsNoPage() {
        assertEquals(emptySet<Int>(), parsePageRange(""))
        assertEquals(emptySet<Int>(), parsePageRange("   "))
    }

    @Test
    fun theModeDecidesWhichPagesAreSelected() {
        assertEquals(PageSelection.All, pageSelectionFor(PageRangeMode.ALL, ""))
        assertEquals(PageSelection.Odd, pageSelectionFor(PageRangeMode.ODD, ""))
        assertEquals(PageSelection.Even, pageSelectionFor(PageRangeMode.EVEN, ""))
        assertEquals(
            PageSelection.Custom(setOf(0, 2)),
            pageSelectionFor(PageRangeMode.CUSTOM, "1,3")
        )
    }

    @Test
    fun theNoteUnderTheRangePickerSaysWhatIsSelected() {
        assertEquals("Every page of the document.", describePageRange(PageRangeMode.ALL, ""))
        assertEquals("The odd pages: 1, 3, 5 …", describePageRange(PageRangeMode.ODD, ""))
        assertEquals("The even pages: 2, 4, 6 …", describePageRange(PageRangeMode.EVEN, ""))
        assertEquals("Pages 1, 3, 4.", describePageRange(PageRangeMode.CUSTOM, "1,3-4"))
        assertEquals(
            "Type the pages, for example 1,3-5.",
            describePageRange(PageRangeMode.CUSTOM, "")
        )
    }

    // ---- The watermark ----------------------------------------------------

    @Test
    fun theWatermarkInTheMiddleSitsOnTheCentreOfThePage() {
        val anchor = watermarkAnchors(WatermarkPosition.CENTER, letter, 48.0).single()
        assertEquals(306.0, anchor.x, 0.001)
        assertEquals(396.0 - 48.0 * 0.35, anchor.y, 0.001)
        assertEquals(MarkAlign.CENTER, anchor.align)
    }

    @Test
    fun aCornerWatermarkKeepsTheMarginFromTheEdge() {
        val topLeft = watermarkAnchors(WatermarkPosition.TOP_LEFT, letter, 48.0).single()
        assertEquals(36.0, topLeft.x, 0.001)
        assertEquals(792.0 - 36.0 - 48.0, topLeft.y, 0.001)
        assertEquals(MarkAlign.LEFT, topLeft.align)

        val bottomRight = watermarkAnchors(WatermarkPosition.BOTTOM_RIGHT, letter, 48.0).single()
        assertEquals(612.0 - 36.0, bottomRight.x, 0.001)
        assertEquals(36.0, bottomRight.y, 0.001)
        assertEquals(MarkAlign.RIGHT, bottomRight.align)
    }

    @Test
    fun aTiledWatermarkCoversThePageWithManyMarks() {
        val anchors = watermarkAnchors(WatermarkPosition.TILED, letter, 48.0)
        // Three columns of 220 pt and six rows of 140 pt fit on the page.
        assertEquals(18, anchors.size)
        assertTrue(anchors.all { it.align == MarkAlign.CENTER })
        // The first tile sits in the middle of the first column.
        assertEquals(612.0 / 3.0 / 2.0, anchors.first().x, 0.001)
    }

    // ---- The header, the footer and the Bates number ----------------------

    @Test
    fun theHeaderSitsUnderTheTopMarginAndTheFooterOverTheBottomOne() {
        val header = headerAnchor(letter, 36.0, 54.0, 54.0, MarkAlign.CENTER)
        assertEquals(792.0 - 36.0, header.y, 0.001)
        assertEquals(306.0, header.x, 0.001)

        val footer = footerAnchor(letter, 36.0, 54.0, 54.0, MarkAlign.CENTER)
        assertEquals(36.0, footer.y, 0.001)
        assertEquals(306.0, footer.x, 0.001)
    }

    @Test
    fun theLinesAreAlignedToTheMargins() {
        val left = headerAnchor(letter, 36.0, 54.0, 54.0, MarkAlign.LEFT)
        assertEquals(54.0, left.x, 0.001)

        val right = headerAnchor(letter, 36.0, 54.0, 54.0, MarkAlign.RIGHT)
        assertEquals(612.0 - 54.0, right.x, 0.001)
    }

    @Test
    fun theBatesNumberSitsInTheChosenCorner() {
        val topLeft = batesAnchor(
            BatesPlacement.TOP_LEFT,
            letter,
            28.0,
            28.0,
            36.0,
            36.0
        )
        assertEquals(36.0, topLeft.x, 0.001)
        assertEquals(792.0 - 28.0, topLeft.y, 0.001)
        assertEquals(MarkAlign.LEFT, topLeft.align)

        val bottomRight = batesAnchor(
            BatesPlacement.BOTTOM_RIGHT,
            letter,
            28.0,
            28.0,
            36.0,
            36.0
        )
        assertEquals(612.0 - 36.0, bottomRight.x, 0.001)
        assertEquals(28.0, bottomRight.y, 0.001)
        assertEquals(MarkAlign.RIGHT, bottomRight.align)

        val bottomCentre = batesAnchor(
            BatesPlacement.BOTTOM_CENTER,
            letter,
            28.0,
            28.0,
            36.0,
            36.0
        )
        assertEquals(306.0, bottomCentre.x, 0.001)
        assertEquals(MarkAlign.CENTER, bottomCentre.align)
    }

    @Test
    fun theBatesNumberIsPaddedToTheChosenNumberOfDigits() {
        assertEquals("000042", batesText("", 6, 42, ""))
        assertEquals("ABC-000042", batesText("ABC-", 6, 42, ""))
        assertEquals("42", batesText("", 1, 42, ""))
        assertEquals("00000042", batesText("", 8, 42, ""))
        assertEquals("000042-X", batesText("", 6, 42, "-X"))
    }

    // ---- The tokens in a header or a footer -------------------------------

    @Test
    fun theTokensAreReplacedByTheirValues() {
        val text = expandMarkTokens(
            "{filename} · page {page} of {pages} · {date} · {title}",
            page = 3,
            total = 12,
            date = "2026-10-20",
            fileName = "report.pdf",
            title = "The report",
            format = NumberFormat.DECIMAL
        )
        assertEquals("report.pdf · page 3 of 12 · 2026-10-20 · The report", text)
    }

    @Test
    fun thePageTokenUsesTheChosenNumberFormat() {
        val roman = expandMarkTokens(
            "{page}",
            page = 4,
            total = 9,
            date = "",
            fileName = "",
            title = "",
            format = NumberFormat.LOWER_ROMAN
        )
        assertEquals("iv", roman)

        val ofTotal = expandMarkTokens(
            "{page}",
            page = 4,
            total = 9,
            date = "",
            fileName = "",
            title = "",
            format = NumberFormat.PAGE_OF_TOTAL
        )
        assertEquals("Page 4 of 9", ofTotal)
    }

    // ---- The page box -----------------------------------------------------

    @Test
    fun aPointOfThePageBecomesAPointOfTheShownPage() {
        val (x, y) = letter.toDevice(0.0, 0.0)
        assertEquals(0.0, x, 0.001)
        assertEquals(792.0, y, 0.001)
    }

    @Test
    fun aTurnedPageShowsItsCornersInAnotherPlace() {
        val turned90 = letter.copy(rotate = 90)
        val (x90, y90) = turned90.toDevice(0.0, 0.0)
        assertEquals(0.0, x90, 0.001)
        assertEquals(0.0, y90, 0.001)

        val turned180 = letter.copy(rotate = 180)
        val (x180, y180) = turned180.toDevice(0.0, 0.0)
        assertEquals(612.0, x180, 0.001)
        assertEquals(0.0, y180, 0.001)

        val turned270 = letter.copy(rotate = 270)
        val (x270, y270) = turned270.toDevice(0.0, 0.0)
        assertEquals(792.0, x270, 0.001)
        assertEquals(612.0, y270, 0.001)
    }

    @Test
    fun aTurnedPageIsShownWithItsSidesSwapped() {
        val turned = letter.copy(rotate = 90)
        assertEquals(792.0, turned.displayWidth, 0.001)
        assertEquals(612.0, turned.displayHeight, 0.001)
        assertEquals(612.0, letter.displayWidth, 0.001)
        assertEquals(792.0, letter.displayHeight, 0.001)
    }

    // ---- The font choice --------------------------------------------------

    @Test
    fun latinTextNeedsNoEmbeddedFont() {
        assertTrue(isLatinText("CONFIDENTIAL"))
        assertTrue(isLatinText("Confidential 2026 - naïve"))
        assertTrue(isLatinText(""))
    }

    @Test
    fun textInAnotherScriptNeedsTheEmbeddedFont() {
        assertFalse(isLatinText("गोपनीय"))
        assertFalse(isLatinText("draft — परिचय"))
    }

    @Test
    fun anUnknownChoiceFallsBackToTheDefault() {
        assertEquals(MarkAlign.CENTER, enumOrDefault("CENTER", MarkAlign.LEFT))
        assertEquals(MarkAlign.LEFT, enumOrDefault("SIDEWAYS", MarkAlign.LEFT))
        assertEquals(MarkAlign.LEFT, enumOrDefault(null, MarkAlign.LEFT))
    }

    // ---- The colours ------------------------------------------------------

    @Test
    fun everyColourChoiceBecomesAnEngineColour() {
        assertEquals(0.75, MarkColorChoice.GREY.toPdfColor().red, 0.001)
        assertEquals(0.0, MarkColorChoice.BLACK.toPdfColor().red, 0.001)
        assertEquals(1.0, MarkColorChoice.BLACK.toPdfColor().alpha, 0.001)
    }
}
