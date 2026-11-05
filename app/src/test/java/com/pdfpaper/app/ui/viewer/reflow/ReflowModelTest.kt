package com.pdfpaper.app.ui.viewer.reflow

import com.pdfpaper.engine.create.PageOrientation
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.create.PdfMetadata
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.extractText
import com.pdfpaper.engine.text.pageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T33: the reflow settings, the page-fraction mapping and the "text is small"
 * suggestion. Pure Kotlin, so it runs as an ordinary JVM unit test.
 */
class ReflowModelTest {

    // ---------------------------------------------------------------- settings

    @Test
    fun defaultsAreComfortable() {
        val d = ReflowSettings.DEFAULT
        assertEquals(17f, d.fontSizeSp, 0.001f)
        assertEquals(1.5f, d.lineSpacing, 0.001f)
        assertEquals(14f, d.paragraphSpacingDp, 0.001f)
        assertEquals(20f, d.marginDp, 0.001f)
        assertEquals(ReflowAlign.LEFT, d.align)
        assertEquals(ReflowFont.SERIF, d.font)
        assertTrue(d.hidePageNumbers)
        assertTrue(d.hideFootnotes)
        assertEquals(25.5f, d.lineHeightSp, 0.001f)
    }

    @Test
    fun valuesFromOutsideAreClamped() {
        val wild = ReflowSettings(
            fontSizeSp = 400f,
            lineSpacing = 9f,
            paragraphSpacingDp = -20f,
            marginDp = 999f
        ).normalised()

        assertEquals(ReflowSettings.MAX_FONT_SP, wild.fontSizeSp, 0.001f)
        assertEquals(ReflowSettings.MAX_LINE_SPACING, wild.lineSpacing, 0.001f)
        assertEquals(0f, wild.paragraphSpacingDp, 0.001f)
        assertEquals(ReflowSettings.MAX_MARGIN_DP, wild.marginDp, 0.001f)

        val tiny = ReflowSettings(
            fontSizeSp = 1f,
            lineSpacing = 0.1f
        ).normalised()
        assertEquals(ReflowSettings.MIN_FONT_SP, tiny.fontSizeSp, 0.001f)
        assertEquals(ReflowSettings.MIN_LINE_SPACING, tiny.lineSpacing, 0.001f)
    }

    @Test
    fun optionsFollowTheHideSwitches() {
        val shown = ReflowSettings(hidePageNumbers = false, hideFootnotes = false).options()
        assertFalse(shown.hidePageNumbers)
        assertFalse(shown.hideFootnotes)

        val hidden = ReflowSettings().options()
        assertTrue(hidden.hidePageNumbers)
        assertTrue(hidden.hideFootnotes)
    }

    @Test
    fun descriptionNamesTheFontSizeAndLeading() {
        val text = describeReflow(ReflowSettings(font = ReflowFont.SANS, fontSizeSp = 21.4f))
        assertTrue(text, text.startsWith("Sans 21 sp"))
        assertTrue(text, text.contains("50%"))
        assertTrue(text, text.contains("left"))

        val justified = describeReflow(ReflowSettings(align = ReflowAlign.JUSTIFIED))
        assertTrue(justified, justified.contains("justified"))
    }

    // ------------------------------------------------------------- page mapping

    @Test
    fun pageFractionIsTheScrollOffsetOverTheItemHeight() {
        assertEquals(0f, pageFraction(0, 1000), 0.0001f)
        assertEquals(0.5f, pageFraction(500, 1000), 0.0001f)
        assertEquals(1f, pageFraction(1000, 1000), 0.0001f)
        assertEquals(0f, pageFraction(500, 0), 0.0001f)
        assertEquals(1f, pageFraction(4000, 1000), 0.0001f)
    }

    @Test
    fun offsetForFractionIsTheInverseOfPageFraction() {
        assertEquals(0, offsetForFraction(0f, 800))
        assertEquals(400, offsetForFraction(0.5f, 800))
        assertEquals(800, offsetForFraction(1f, 800))
        assertEquals(0, offsetForFraction(0.5f, 0))

        val fraction = 0.37f
        val offset = offsetForFraction(fraction, 1200)
        assertEquals(fraction, pageFraction(offset, 1200), 0.002f)
    }

    @Test
    fun aPositionSurvivesAFractionRoundTrip() {
        val position = ReflowPosition(page = 12, fraction = 0.62f)
        val offset = offsetForFraction(position.fraction, 900)
        val back = ReflowPosition(position.page, pageFraction(offset, 900))
        assertEquals(12, back.page)
        assertEquals(0.62f, back.fraction, 0.002f)
    }

    @Test
    fun startIsTheFirstPage() {
        assertEquals(0, ReflowPosition.START.page)
        assertEquals(0f, ReflowPosition.START.fraction, 0.0001f)
    }

    // ------------------------------------------------------------ small text

    private fun layout(): com.pdfpaper.engine.text.PageLayout {
        val pdf = PdfBuilder(PageSize.A4, PageOrientation.PORTRAIT, PdfMetadata())
        val page = pdf.addPage()
        for (i in 0 until 6) {
            page.drawText("Body text of a page that is set in a known size", 60.0, 120.0 + i * 14.0, 10.0)
        }
        val document: PdfDocument = PdfDocument.open(ByteArraySource(pdf.build())).getOrThrow()
        return pageLayout(document.pages().first().extractText())
    }

    @Test
    fun smallTextIsFlaggedAtALowZoom() {
        val layout = layout()
        // 10 pt body drawn at 1 px per point on a 2.75 density screen is 3.6 sp.
        assertTrue(isTextSmall(layout, scale = 1.0, density = 2.75f))
        // The same page at a fit-width zoom of 3 px per point is 10.9 sp.
        assertTrue(isTextSmall(layout, scale = 3.0, density = 2.75f))
        // Zoomed in, it is comfortable.
        assertFalse(isTextSmall(layout, scale = 6.0, density = 2.75f))
    }

    @Test
    fun emptyLayoutIsNeverSmall() {
        val pdf = PdfBuilder(PageSize.A4, PageOrientation.PORTRAIT, PdfMetadata())
        pdf.addPage()
        val document: PdfDocument = PdfDocument.open(ByteArraySource(pdf.build())).getOrThrow()
        val layout = pageLayout(document.pages().first().extractText())
        assertTrue(layout.isEmpty)
        assertFalse(isTextSmall(layout, scale = 0.1, density = 3f))
    }

    @Test
    fun zeroDensityFallsBackToOne() {
        val layout = layout()
        assertFalse(isTextSmall(layout, scale = 10.0, density = 0f))
    }

    // ------------------------------------------------------------- regions

    @Test
    fun aRegionKeepsTheBitmapAspect() {
        // A wide chart: 970 x 416 px is the page box of a 140 x 60 mm picture.
        assertEquals(416f / 970f, reflowRegionAspect(970, 416), 0.0001f)
        // A tall region (a table rendered at 3x the column width).
        assertEquals(2f, reflowRegionAspect(400, 800), 0.0001f)
    }

    @Test
    fun aDegenerateRegionIsSquare() {
        assertEquals(1f, reflowRegionAspect(0, 800), 0.0001f)
        assertEquals(1f, reflowRegionAspect(800, 0), 0.0001f)
        assertEquals(1f, reflowRegionAspect(-1, -1), 0.0001f)
    }
}
