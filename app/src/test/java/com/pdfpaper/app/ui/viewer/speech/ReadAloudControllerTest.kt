package com.pdfpaper.app.ui.viewer.speech

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.speech.ChapterSource
import com.pdfpaper.engine.speech.SpeechChapter
import com.pdfpaper.engine.speech.SpeechPage
import com.pdfpaper.engine.speech.SpeechParagraph
import com.pdfpaper.engine.speech.SpeechWord
import com.pdfpaper.engine.text.TextRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T34: the read-aloud state machine. It is synchronous and returns the actions
 * the platform should take (speak, highlight, scroll, load a page, pause), so
 * the whole reading flow is tested on the JVM without a speech engine.
 */
class ReadAloudControllerTest {

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
            SpeechWord(index.toString(), 10, text.length, listOf(TextRect(95.0, 700.0, 105.0, 712.0)))
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

    private fun emptyPage(index: Int): SpeechPage =
        SpeechPage(pageIndex = index, paragraphs = emptyList(), scanned = true)

    private fun pageFeed(vararg pages: SpeechPage): SpeechPageFeed = object : SpeechPageFeed {
        private val byIndex = pages.associateBy { it.pageIndex }
        override fun page(index: Int): SpeechPage? = byIndex[index]
    }

    private val chapters = SpeechChapterList(
        listOf(
            SpeechChapter("One", 0, 0, 0.0, ChapterSource.OUTLINE),
            SpeechChapter("Two", 1, 0, 0.0, ChapterSource.OUTLINE),
            SpeechChapter("Three", 2, 1, 0.5, ChapterSource.OUTLINE)
        ),
        ChapterSource.OUTLINE
    )

    private fun controller(
        feed: SpeechPageFeed = pageFeed(page(0), page(1), page(2)),
        chapters: SpeechChapterList = this.chapters,
        pageCount: Int = 3,
        clock: () -> Long = { 0L }
    ): ReadAloudController = ReadAloudController(
        chapters = chapters,
        feed = feed,
        pageCount = { pageCount },
        clock = clock
    )

    private fun List<SpeechAction>.speak(): SpeechAction.Speak =
        filterIsInstance<SpeechAction.Speak>().single()

    private fun List<SpeechAction>.has(type: Class<out SpeechAction>): Boolean =
        any { type.isInstance(it) }

    // ------------------------------------------------------------------ start

    @Test
    fun startSpeaksTheParagraphAtThePosition() {
        val c = controller()
        val actions = c.start(SpeechPosition(0, 1))
        val speak = actions.speak()
        assertEquals(ReadAloudController.utteranceId(0, 1), speak.utteranceId)
        assertEquals(0, speak.pageIndex)
        assertEquals(1, speak.paragraphIndex)
        assertEquals("Paragraph 1", speak.text)
        assertEquals(1f, speak.rate, 0.001f)
        assertTrue(c.state.speaking)
        assertTrue(c.state.active)
    }

    @Test
    fun startAlsoScrollsToTheParagraph() {
        val c = controller()
        val actions = c.start(SpeechPosition(0, 1))
        val scroll = actions.filterIsInstance<SpeechAction.ScrollTo>().single()
        assertEquals(0, scroll.pageIndex)
        assertEquals(paragraph(0, 1).fraction, scroll.fraction, 0.0001)
        assertEquals(
            listOf("StateChanged", "ScrollTo", "Speak"),
            actions.map { it::class.simpleName }
        )
    }

    // --------------------------------------------------------------- highlight

    @Test
    fun onWordStartHighlightsTheWordThatHoldsTheOffset() {
        val c = controller()
        c.start(SpeechPosition(0, 0))
        val paragraph = paragraph(0, 0)
        val word = paragraph.words[1]
        val actions = c.onWordStart(ReadAloudController.utteranceId(0, 0), word.start)
        val highlight = actions.filterIsInstance<SpeechAction.Highlight>().single()
        assertFalse(highlight.paragraphOnly)
        assertEquals(paragraph, highlight.paragraph)
        assertEquals(word.rects, highlight.rects)
    }

    @Test
    fun anUnknownUtteranceIdIsIgnored() {
        val c = controller()
        c.start(SpeechPosition(0, 0))
        assertTrue(c.onWordStart("p:9:9", 0).isEmpty())
        assertTrue(c.onUtteranceDone("p:9:9").isEmpty())
    }

    // --------------------------------------------------------------- advance

    @Test
    fun finishingAParagraphSpeaksTheNextOneOnThePage() {
        val c = controller()
        c.start(SpeechPosition(0, 0))
        val actions = c.onUtteranceDone(ReadAloudController.utteranceId(0, 0))
        val speak = actions.speak()
        assertEquals(ReadAloudController.utteranceId(0, 1), speak.utteranceId)
        assertEquals(SpeechPosition(0, 1), c.state.position)
    }

    @Test
    fun finishingTheLastParagraphRequestsTheNextPage() {
        val c = controller(feed = pageFeed(page(0)))
        c.start(SpeechPosition(0, 1))
        val actions = c.onUtteranceDone(ReadAloudController.utteranceId(0, 1))
        val need = actions.filterIsInstance<SpeechAction.NeedPage>().single()
        assertEquals(1, need.pageIndex)
        assertFalse(c.state.speaking)
    }

    @Test
    fun playbackContinuesAfterThePageArrives() {
        val c = controller(feed = pageFeed(page(0)))
        c.start(SpeechPosition(0, 1))
        c.onUtteranceDone(ReadAloudController.utteranceId(0, 1))
        val actions = c.onPageReady(page(1))
        assertEquals(
            ReadAloudController.utteranceId(1, 0),
            actions.speak().utteranceId
        )
        assertTrue(c.state.speaking)
    }

    @Test
    fun nextParagraphCrossesAPageBoundary() {
        val c = controller()
        c.start(SpeechPosition(0, 1))
        val actions = c.nextParagraph()
        assertEquals(ReadAloudController.utteranceId(1, 0), actions.speak().utteranceId)
        assertEquals(1, actions.filterIsInstance<SpeechAction.ScrollTo>().single().pageIndex)
    }

    @Test
    fun previousParagraphCrossesBack() {
        val c = controller()
        c.start(SpeechPosition(1, 0))
        val actions = c.previousParagraph()
        assertEquals(ReadAloudController.utteranceId(0, 1), actions.speak().utteranceId)
    }

    // --------------------------------------------------------------- chapters

    @Test
    fun nextChapterJumpsToTheNextChapterStart() {
        val c = controller()
        c.start(SpeechPosition(0, 0))
        val actions = c.nextChapter()
        assertEquals(ReadAloudController.utteranceId(1, 0), actions.speak().utteranceId)
        assertEquals("Two", c.state.chapter?.title)
    }

    @Test
    fun previousChapterFirstReturnsToTheCurrentChaptersStart() {
        val c = controller()
        c.start(SpeechPosition(1, 1))
        val actions = c.previousChapter()
        assertEquals(ReadAloudController.utteranceId(1, 0), actions.speak().utteranceId)
    }

    @Test
    fun nextChapterFromTheLastChapterFinishes() {
        val c = controller()
        c.start(SpeechPosition(2, 1))
        val actions = c.nextChapter()
        assertTrue(actions.has(SpeechAction.Finished::class.java))
        assertFalse(c.state.speaking)
    }

    // ------------------------------------------------------------- empty page

    @Test
    fun aPageWithNoTextIsReportedAndSkipped() {
        val c = controller(feed = pageFeed(page(0), emptyPage(1), page(2)))
        val actions = c.start(SpeechPosition(1, 0))
        val paused = actions.filterIsInstance<SpeechAction.Paused>().single()
        assertEquals(PauseReason.NO_TEXT, paused.reason)
        assertFalse(c.state.speaking)

        val resumed = c.resume()
        assertEquals(ReadAloudController.utteranceId(2, 0), resumed.speak().utteranceId)
    }

    @Test
    fun theEndOfTheDocumentFinishes() {
        val c = controller()
        c.start(SpeechPosition(2, 1))
        val actions = c.onUtteranceDone(ReadAloudController.utteranceId(2, 1))
        assertTrue(actions.has(SpeechAction.Finished::class.java))
        assertFalse(c.state.speaking)
        assertFalse(actions.has(SpeechAction.Speak::class.java))
    }

    // ------------------------------------------------------------------ sleep

    @Test
    fun aMinutesSleepTimerPausesWhenItExpires() {
        var now = 1_000L
        val c = controller(clock = { now })
        c.start(SpeechPosition(0, 0))
        c.setSleep(SleepOption.MINUTES_15)
        assertTrue(c.tick(now + 60_000L).isEmpty())
        val actions = c.tick(now + 15 * 60_000L)
        val paused = actions.filterIsInstance<SpeechAction.Paused>().single()
        assertEquals(PauseReason.SLEEP, paused.reason)
        assertFalse(c.state.speaking)
    }

    @Test
    fun endOfChapterSleepPausesOnlyAtAChapterBoundary() {
        val c = controller()
        c.start(SpeechPosition(0, 0))
        c.setSleep(SleepOption.END_OF_CHAPTER)

        // Still inside chapter One: keep speaking.
        val same = c.onUtteranceDone(ReadAloudController.utteranceId(0, 0))
        assertEquals(ReadAloudController.utteranceId(0, 1), same.speak().utteranceId)

        // Chapter One ends, chapter Two begins: pause before its first word.
        val actions = c.onUtteranceDone(ReadAloudController.utteranceId(0, 1))
        val paused = actions.filterIsInstance<SpeechAction.Paused>().single()
        assertEquals(PauseReason.SLEEP, paused.reason)
        assertFalse(actions.has(SpeechAction.Speak::class.java))
        assertEquals(SpeechPosition(0, 1), c.state.position)
    }

    // ------------------------------------------------------------------ speed

    @Test
    fun speedChangeReSpeaksTheCurrentParagraphAtTheNewRate() {
        val c = controller()
        c.start(SpeechPosition(0, 0))
        val actions = c.setSpeed(1.5f)
        val speak = actions.speak()
        assertEquals(ReadAloudController.utteranceId(0, 0), speak.utteranceId)
        assertEquals(1.5f, speak.rate, 0.001f)
        assertEquals(1.5f, c.state.speed, 0.001f)
    }

    @Test
    fun anUtteranceErrorPausesAndKeepsThePosition() {
        val c = controller()
        c.start(SpeechPosition(0, 0))
        val actions = c.onUtteranceError(ReadAloudController.utteranceId(0, 0), "Voice failed")
        val paused = actions.filterIsInstance<SpeechAction.Paused>().single()
        assertEquals(PauseReason.ERROR, paused.reason)
        assertEquals("Voice failed", paused.message)
        assertFalse(c.state.speaking)
        assertEquals(SpeechPosition(0, 0), c.state.position)
    }

    @Test
    fun stopClearsTheActiveState() {
        val c = controller()
        c.start(SpeechPosition(0, 0))
        val actions = c.stop()
        assertFalse(c.state.active)
        assertFalse(c.state.speaking)
        assertFalse(actions.has(SpeechAction.Speak::class.java))
    }

    @Test
    fun stateIsReportedAfterEveryChange() {
        val c = controller()
        assertTrue(c.start(SpeechPosition(0, 0)).has(SpeechAction.StateChanged::class.java))
        assertTrue(c.pause().has(SpeechAction.StateChanged::class.java))
        assertTrue(c.setSpeed(1.25f).has(SpeechAction.StateChanged::class.java))
        assertTrue(c.nextParagraph().has(SpeechAction.StateChanged::class.java))
        assertTrue(c.stop().has(SpeechAction.StateChanged::class.java))
    }
}
