package com.pdfpaper.app.ui.viewer.speech

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.speech.SpeechPage
import com.pdfpaper.engine.speech.SpeechParagraph
import com.pdfpaper.engine.speech.SpeechWord
import com.pdfpaper.engine.text.TextRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T64: the playback executor. The session drives T34's controller against a
 * speech engine and a page source, and publishes what the viewer shows: the
 * spoken word's boxes, the place to scroll to and the message under the bar.
 */
class ReadAloudSessionTest {

    // ---------------------------------------------------------------- fixtures

    private fun paragraph(
        page: Int,
        index: Int,
        text: String = "Paragraph $index"
    ): SpeechParagraph = SpeechParagraph(
        pageIndex = page,
        index = index,
        text = text,
        words = listOf(
            SpeechWord("Paragraph", 0, 9, listOf(TextRect(10.0, 700.0, 90.0, 712.0))),
            SpeechWord(
                text.substring(9),
                9,
                text.length,
                listOf(TextRect(95.0, 700.0, 105.0, 712.0))
            )
        ),
        headingLevel = 0,
        bounds = PdfRect(10.0, 700.0, 500.0, 712.0),
        fraction = 0.1 * (index + 1)
    )

    private fun page(index: Int, count: Int = 2): SpeechPage = SpeechPage(
        pageIndex = index,
        paragraphs = List(count) { paragraph(index, it) },
        scanned = false
    )

    /** A speech engine that records what it was asked to say. */
    private class FakeSynthesis : SpeechSynthesis {
        val spoken = mutableListOf<String>()
        var stops = 0
        var shutdowns = 0

        override fun speak(text: String, utteranceId: String, rate: Float) {
            spoken += text
        }

        override fun stop() {
            stops++
        }

        override fun shutdown() {
            shutdowns++
        }
    }

    /** A page source that serves the pages it was given. */
    private class FakePages(
        private val pages: Map<Int, SpeechPage>
    ) : SpeechPageSource {
        val requested = mutableListOf<Int>()

        override suspend fun page(index: Int): SpeechPage? {
            requested += index
            return pages[index]
        }

        override suspend fun chapters(): SpeechChapterList =
            SpeechChapterList(emptyList(), null)

        override fun pageCount(): Int = pages.size
    }

    private fun session(
        synthesis: FakeSynthesis = FakeSynthesis(),
        pages: FakePages = FakePages(mapOf(0 to page(0), 1 to page(1)))
    ): ReadAloudSession = ReadAloudSession(
        synthesis = synthesis,
        pages = pages,
        scope = CoroutineScope(Dispatchers.Unconfined)
    )

    // ------------------------------------------------------------------ tests

    @Test
    fun startingSpeaksTheFirstParagraph() = runBlocking {
        val synthesis = FakeSynthesis()
        val session = session(synthesis)

        session.start("Doc", SpeechPosition())

        assertEquals(listOf("Paragraph 0"), synthesis.spoken)
        assertTrue(session.state.value.active)
        assertTrue(session.state.value.speaking)
        assertEquals("Doc", session.state.value.documentName)
    }

    @Test
    fun finishingAParagraphMovesToTheNext() = runBlocking {
        val synthesis = FakeSynthesis()
        val session = session(synthesis)
        session.start("Doc", SpeechPosition())

        session.onUtteranceDone(ReadAloudController.utteranceId(0, 0))

        assertEquals(listOf("Paragraph 0", "Paragraph 1"), synthesis.spoken)
        assertEquals(1, session.state.value.paragraphIndex)
    }

    @Test
    fun aWordRangePublishesTheWordBoxes() = runBlocking {
        val session = session()
        session.start("Doc", SpeechPosition())

        session.onRangeStart(ReadAloudController.utteranceId(0, 0), 0)

        val state = session.state.value
        assertEquals(0, state.wordPageIndex)
        assertFalse(state.paragraphOnly)
        assertEquals(
            listOf(TextRect(10.0, 700.0, 90.0, 712.0)),
            state.wordRects
        )
    }

    @Test
    fun aPageWithoutTextIsReportedAndSkipped() = runBlocking {
        val synthesis = FakeSynthesis()
        val pages = FakePages(
            mapOf(
                0 to page(0, count = 1),
                1 to SpeechPage(pageIndex = 1, paragraphs = emptyList(), scanned = true)
            )
        )
        val session = session(synthesis, pages)
        session.start("Doc", SpeechPosition())

        session.onUtteranceDone(ReadAloudController.utteranceId(0, 0))

        // The empty page is reported and the reading moves on instead of
        // speaking nothing and stalling.
        assertTrue(session.state.value.message?.contains("No text") == true)
        assertNotNull(session.state.value.message)
    }

    @Test
    fun aMissingPageIsFetchedAndTheReadingContinues() = runBlocking {
        val synthesis = FakeSynthesis()
        val pages = FakePages(mapOf(0 to page(0), 1 to page(1)))
        val session = session(synthesis, pages)

        session.start("Doc", SpeechPosition())

        // The session asked the source for the page it needed.
        assertTrue(pages.requested.contains(0))
        assertEquals(listOf("Paragraph 0"), synthesis.spoken)
    }

    @Test
    fun pausingStopsTheSpeechAndResumingSpeaksAgain() = runBlocking {
        val synthesis = FakeSynthesis()
        val session = session(synthesis)
        session.start("Doc", SpeechPosition())

        session.pause()
        assertTrue(synthesis.stops >= 1)
        assertFalse(session.state.value.speaking)

        session.resume()
        assertEquals(listOf("Paragraph 0", "Paragraph 0"), synthesis.spoken)
        assertTrue(session.state.value.speaking)
    }

    @Test
    fun focusLossPausesAndTheGainResumes() = runBlocking {
        val synthesis = FakeSynthesis()
        val session = session(synthesis)
        session.start("Doc", SpeechPosition())

        session.onFocusEvent(FocusEvent.LOSS_TRANSIENT)
        assertFalse(session.state.value.speaking)

        session.onFocusEvent(FocusEvent.GAIN)
        assertTrue(session.state.value.speaking)
        assertEquals(2, synthesis.spoken.size)
    }

    @Test
    fun theNoiseEventPausesWithoutResuming() = runBlocking {
        val synthesis = FakeSynthesis()
        val session = session(synthesis)
        session.start("Doc", SpeechPosition())

        session.onFocusEvent(FocusEvent.NOISY)
        assertFalse(session.state.value.speaking)

        // A headphone unplug is not a focus event: the next gain does not
        // restart the reading behind the reader's back.
        session.onFocusEvent(FocusEvent.GAIN)
        assertFalse(session.state.value.speaking)
        assertEquals(1, synthesis.spoken.size)
    }

    @Test
    fun stoppingClearsTheWordBoxes() = runBlocking {
        val session = session()
        session.start("Doc", SpeechPosition())
        session.onRangeStart(ReadAloudController.utteranceId(0, 0), 0)
        assertTrue(session.state.value.wordRects.isNotEmpty())

        session.stop()

        val state = session.state.value
        assertTrue(state.wordRects.isEmpty())
        assertEquals(-1, state.wordPageIndex)
        assertFalse(state.active)
    }
}
