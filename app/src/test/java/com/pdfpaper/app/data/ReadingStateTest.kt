package com.pdfpaper.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task T38b: the reading session. Everything the viewer restores is decided by
 * these pure rules, so they are tested here rather than on a device: what a
 * saved state means when the file has changed, what an explicit page means, and
 * what "Always start at page 1" keeps.
 */
class ReadingStateTest {

    private fun state(
        pageIndex: Int = 47,
        pageFraction: Float = 0.25f,
        pageCount: Int = 210,
        zoom: Float = 1.5f,
        mode: ReadingMode = ReadingMode.PAGE,
        orientation: ReadingOrientation = ReadingOrientation.PORTRAIT,
        lastToolId: String? = "rotate",
        speechPage: Int = 0,
        speechParagraph: Int = 0,
        updatedAt: Long = 1_700_000_000_000L
    ) = ReadingState(
        pageIndex = pageIndex,
        pageFraction = pageFraction,
        pageCount = pageCount,
        zoom = zoom,
        mode = mode,
        orientation = orientation,
        lastToolId = lastToolId,
        speechPage = speechPage,
        speechParagraph = speechParagraph,
        updatedAt = updatedAt
    )

    // ------------------------------------------------------------------ codec

    @Test
    fun codecRoundTripsEveryField() {
        val saved = state(lastToolId = "comment|reply %2F naïve")

        val decoded = ReadingStateCodec.decode(ReadingStateCodec.encode(saved))

        assertEquals(saved, decoded)
    }

    @Test
    fun aSessionRoundTripsTheSpeechPosition() {
        val saved = state(speechPage = 12, speechParagraph = 3)

        val decoded = ReadingStateCodec.decode(ReadingStateCodec.encode(saved))

        assertEquals(12, decoded?.speechPage)
        assertEquals(3, decoded?.speechParagraph)
        assertEquals(saved, decoded)
    }

    @Test
    fun recordsWrittenBeforeT34ReadSpeechAsTheStart() {
        // A T38b record (9 fields) and a T59 record (10 fields) predate the
        // speech position; both must keep their page rather than fail.
        val t38b = ReadingStateCodec.decode("1|7|0.5|10|1.0|PAGE|AUTO|%20|0")
        val t59 = ReadingStateCodec.decode("1|7|0.5|10|1.0|PAGE|AUTO|%20|0|4")

        assertEquals(7, t38b?.pageIndex)
        assertEquals(0, t38b?.speechPage)
        assertEquals(0, t38b?.speechParagraph)
        assertEquals(4, t59?.flowOrder)
        assertEquals(0, t59?.speechPage)
    }

    @Test
    fun aRecordWithOnlyTheSpeechPageStillDecodes() {
        val decoded = ReadingStateCodec.decode("1|7|0.5|10|1.0|PAGE|AUTO|%20|0|4|12")

        assertEquals(12, decoded?.speechPage)
        assertEquals(0, decoded?.speechParagraph)
    }

    @Test
    fun codecRoundTripsAnEmptyToolIdAsNull() {
        val saved = state(lastToolId = null)

        assertNull(ReadingStateCodec.decode(ReadingStateCodec.encode(saved))?.lastToolId)
    }

    @Test
    fun codecRejectsRecordsItCannotTrust() {
        assertNull(ReadingStateCodec.decode(null))
        assertNull(ReadingStateCodec.decode(""))
        assertNull(ReadingStateCodec.decode("   "))
        // A different version, too few fields, a bad number and a bad enum all
        // decode to null rather than to a wrong position.
        assertNull(ReadingStateCodec.decode("9|1|0.0|10|1.0|PAGE|AUTO|%20|0"))
        assertNull(ReadingStateCodec.decode("1|1|0.0"))
        assertNull(ReadingStateCodec.decode("1|x|0.0|10|1.0|PAGE|AUTO|%20|0"))
        assertNull(ReadingStateCodec.decode("1|1|0.0|10|1.0|SIDEWAYS|AUTO|%20|0"))
    }

    @Test
    fun codecTreatsAnUndecodableToolIdAsNoTool() {
        // The tool id is app data, not a position: a value this version cannot
        // read must not cost the reader their page.
        val decoded = ReadingStateCodec.decode("1|7|0.0|10|1.0|PAGE|AUTO|%%%|0")

        assertEquals(7, decoded?.pageIndex)
        assertNull(decoded?.lastToolId)
    }

    @Test
    fun codecClampsValuesThatWouldBreakTheViewer() {
        val decoded = ReadingStateCodec.decode("1|3|2.5|10|99.0|REFLOW|LANDSCAPE|%20|0")

        assertEquals(3, decoded?.pageIndex)
        assertEquals(1f, decoded?.pageFraction)
        assertEquals(ReadingState.MAX_ZOOM, decoded?.zoom)
        assertEquals(ReadingMode.REFLOW, decoded?.mode)
        assertEquals(ReadingOrientation.LANDSCAPE, decoded?.orientation)
    }

    // ---------------------------------------------------------------- restore

    @Test
    fun aDocumentWithNoSessionKeepsItsOwnFraming() {
        val restore = ReadingRestore.resolve(state = null, pageCount = 12)

        assertEquals(0, restore.pageIndex)
        assertEquals(0f, restore.pageFraction)
        assertEquals(1f, restore.zoom)
        assertEquals(ReadingMode.PAGE, restore.mode)
        assertEquals(ReadingOrientation.AUTO, restore.orientation)
        assertNull(restore.lastToolId)
        // False is what tells the view model not to overwrite the framing the
        // document already had (T57).
        assertFalse(restore.restoredFromSession)
    }

    @Test
    fun aSavedSessionIsRestoredExactly() {
        val restore = ReadingRestore.resolve(state = state(), pageCount = 210)

        assertEquals(47, restore.pageIndex)
        assertEquals(0.25f, restore.pageFraction)
        assertEquals(1.5f, restore.zoom)
        assertEquals(ReadingMode.PAGE, restore.mode)
        assertEquals(ReadingOrientation.PORTRAIT, restore.orientation)
        assertEquals("rotate", restore.lastToolId)
        assertNull(restore.note)
        assertTrue(restore.restoredFromSession)
    }

    @Test
    fun aShorterFileClampsToItsLastPageAndSaysSo() {
        val restore = ReadingRestore.resolve(state = state(pageIndex = 47), pageCount = 5)

        assertEquals(4, restore.pageIndex)
        // A page that moved is a different page, so its fraction is dropped.
        assertEquals(0f, restore.pageFraction)
        assertEquals(
            "This document changed and now has 5 pages. Showing page 5.",
            restore.note
        )
        assertTrue(restore.restoredFromSession)
    }

    @Test
    fun aOnePageFileClampsWithSingularWording() {
        val restore = ReadingRestore.resolve(state = state(pageIndex = 47), pageCount = 1)

        assertEquals(0, restore.pageIndex)
        assertEquals(
            "This document changed and now has 1 page. Showing page 1.",
            restore.note
        )
    }

    @Test
    fun aShorterFileClampsTheSavedSpeechPageToo() {
        val restore = ReadingRestore.resolve(
            state = state(pageIndex = 1, speechPage = 47, speechParagraph = 3),
            pageCount = 5
        )

        assertEquals(4, restore.speechPage)
        // The paragraph belonged to the page that moved, so it is gone.
        assertEquals(0, restore.speechParagraph)
    }

    @Test
    fun aPageThatStillExistsIsNotClampedAndHasNoNote() {
        val restore = ReadingRestore.resolve(state = state(pageIndex = 3), pageCount = 4)

        assertEquals(3, restore.pageIndex)
        assertEquals(0.25f, restore.pageFraction)
        assertNull(restore.note)
    }

    @Test
    fun anExplicitPageWinsOverTheSavedSpot() {
        val restore = ReadingRestore.resolve(
            state = state(),
            pageCount = 210,
            explicitPage = 12
        )

        assertEquals(12, restore.pageIndex)
        assertEquals(0f, restore.pageFraction)
        // The mode, zoom and orientation are still the reader's.
        assertEquals(1.5f, restore.zoom)
        assertEquals(ReadingOrientation.PORTRAIT, restore.orientation)
        assertEquals("rotate", restore.lastToolId)
        assertTrue(restore.restoredFromSession)
    }

    @Test
    fun anExplicitPageIsClampedToTheDocument() {
        val restore = ReadingRestore.resolve(
            state = state(),
            pageCount = 3,
            explicitPage = 99
        )

        assertEquals(2, restore.pageIndex)
        assertNull(restore.note)
    }

    @Test
    fun alwaysStartAtPageOneKeepsTheModeAndZoom() {
        val restore = ReadingRestore.resolve(
            state = state(mode = ReadingMode.REFLOW, zoom = 2f),
            pageCount = 210,
            alwaysStartAtPageOne = true
        )

        assertEquals(0, restore.pageIndex)
        assertEquals(0f, restore.pageFraction)
        assertEquals(ReadingMode.REFLOW, restore.mode)
        assertEquals(2f, restore.zoom)
        assertEquals("rotate", restore.lastToolId)
        // A session exists, so the mode still applies to the framing.
        assertTrue(restore.restoredFromSession)
    }

    @Test
    fun alwaysStartAtPageOneDoesNothingWithoutASession() {
        val restore = ReadingRestore.resolve(
            state = null,
            pageCount = 210,
            alwaysStartAtPageOne = true
        )

        assertEquals(0, restore.pageIndex)
        assertFalse(restore.restoredFromSession)
    }

    @Test
    fun aDocumentWithNoPagesOpensAtTheDefault() {
        val restore = ReadingRestore.resolve(state = state(), pageCount = 0)

        assertEquals(0, restore.pageIndex)
        assertEquals(1f, restore.zoom)
        assertNull(restore.note)
        assertFalse(restore.restoredFromSession)
    }

    @Test
    fun aSavedZoomOutsideTheViewerLimitsIsBroughtBackIn() {
        val restore = ReadingRestore.resolve(
            state = state(zoom = 40f),
            pageCount = 10
        )

        assertEquals(ReadingState.MAX_ZOOM, restore.zoom)
    }

    // ---------------------------------------------------------------- labels

    @Test
    fun theProgressLabelNamesThePageAndTheCount() {
        assertEquals("Page 48 of 210", readingProgressLabel(47, 210))
        assertEquals("Page 1 of 1", readingProgressLabel(0, 1))
        assertEquals("Page 1 of 10", readingProgressLabel(-4, 10))
        assertEquals("Page 10 of 10", readingProgressLabel(99, 10))
        assertNull(readingProgressLabel(0, 0))
    }

    // ---------------------------------------------------------------- offset

    @Test
    fun theOffsetEstimateUsesThePagesOwnShape() {
        // A 1000 px wide page with a 0.5 aspect is 2000 px tall, so a quarter
        // of the way in is 500 px.
        assertEquals(500, readingOffsetPx(0.25f, 1000, 0.5f))
        assertEquals(0, readingOffsetPx(0f, 1000, 0.5f))
        assertEquals(2000, readingOffsetPx(1f, 1000, 0.5f))
    }

    @Test
    fun theOffsetEstimateClampsAndGuardsBadInput() {
        assertEquals(2000, readingOffsetPx(3f, 1000, 0.5f))
        assertEquals(0, readingOffsetPx(0.5f, 0, 0.5f))
        assertEquals(0, readingOffsetPx(0.5f, 1000, 0f))
        assertEquals(0, readingOffsetPx(0.5f, 1000, -1f))
    }
}
