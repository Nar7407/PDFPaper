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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T34: the pure read-aloud model. Positions, the chapter walk, paragraph
 * cursors, the sleep timer and the stored voice key are all decided here so
 * they are unit-tested without a speech engine or a device.
 */
class SpeechModelTest {

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

    private fun chapter(
        title: String,
        page: Int,
        paragraph: Int,
        source: ChapterSource = ChapterSource.OUTLINE
    ): SpeechChapter = SpeechChapter(title, page, paragraph, 0.0, source)

    // ------------------------------------------------------------------ speed

    @Test
    fun speedSnapsToTheNearestPreset() {
        assertEquals(0.75f, SpeechSpeed.nearest(0.1f), 0.001f)
        assertEquals(1.0f, SpeechSpeed.nearest(0.9f), 0.001f)
        assertEquals(1.25f, SpeechSpeed.nearest(1.2f), 0.001f)
        assertEquals(1.5f, SpeechSpeed.nearest(1.7f), 0.001f)
        assertEquals(2.0f, SpeechSpeed.nearest(9f), 0.001f)
        assertEquals(listOf(0.75f, 1f, 1.25f, 1.5f, 2f), SpeechSpeed.PRESETS)
    }

    @Test
    fun speedLabelsReadNaturally() {
        assertEquals("0.75x", SpeechSpeed.label(0.75f))
        assertEquals("1x", SpeechSpeed.label(1f))
        assertEquals("1.25x", SpeechSpeed.label(1.25f))
        assertEquals("2x", SpeechSpeed.label(2f))
    }

    // ------------------------------------------------------------------ sleep

    @Test
    fun sleepOptionsExposeTheirMinutes() {
        assertNull(SleepOption.OFF.minutes)
        assertNull(SleepOption.END_OF_CHAPTER.minutes)
        assertEquals(15, SleepOption.MINUTES_15.minutes)
        assertEquals(30, SleepOption.MINUTES_30.minutes)
        assertEquals(60, SleepOption.MINUTES_60.minutes)
        assertEquals("Off", SleepOption.OFF.label())
        assertEquals("End of chapter", SleepOption.END_OF_CHAPTER.label())
        assertEquals("15 minutes", SleepOption.MINUTES_15.label())
    }

    @Test
    fun aMinutesTimerCountsDownAndExpires() {
        val timer = SleepTimer(SleepOption.MINUTES_15, startedAtMillis = 1_000L)
        assertEquals(15 * 60_000L, timer.remainingMillis(1_000L))
        assertEquals(60_000L, timer.remainingMillis(1_000L + 14 * 60_000L))
        assertFalse(timer.isExpired(1_000L + 14 * 60_000L))
        assertTrue(timer.isExpired(1_000L + 15 * 60_000L))
        assertEquals("14:00 left", timer.label(1_000L + 60_000L))
    }

    @Test
    fun endOfChapterHasNoClock() {
        val timer = SleepTimer(SleepOption.END_OF_CHAPTER, startedAtMillis = 0L)
        assertNull(timer.remainingMillis(10_000L))
        assertFalse(timer.isExpired(10_000_000L))
        assertEquals("End of chapter", timer.label(10_000L))
    }

    // --------------------------------------------------------------- chapters

    private val chapters = SpeechChapterList(
        listOf(
            chapter("One", 0, 0),
            chapter("Two", 1, 0),
            chapter("Three", 2, 1)
        ),
        ChapterSource.OUTLINE
    )

    @Test
    fun chapterListFindsTheChapterHoldingAPosition() {
        assertEquals("One", chapters.chapterAt(0, 1)?.title)
        assertEquals("Two", chapters.chapterAt(1, 0)?.title)
        assertEquals("Two", chapters.chapterAt(1, 5)?.title)
        assertEquals("Three", chapters.chapterAt(2, 1)?.title)
        assertEquals("Three", chapters.chapterAt(9, 0)?.title)
        assertNull(SpeechChapterList(emptyList(), null).chapterAt(0, 0))
    }

    @Test
    fun nextAndPreviousChapterWalkTheList() {
        assertEquals("Two", chapters.next(0, 1)?.title)
        assertEquals("Three", chapters.next(1, 0)?.title)
        assertEquals("Two", chapters.previous(2, 1)?.title)
        assertEquals("One", chapters.previous(1, 0)?.title)
        assertEquals(2, chapters.indexOf(chapters.chapters[2]))
    }

    @Test
    fun thereIsNoNextChapterAfterTheLast() {
        assertNull(chapters.next(2, 1))
        assertNull(chapters.next(5, 0))
        assertNull(chapters.previous(0, 0))
    }

    // --------------------------------------------------------------- cursors

    @Test
    fun nextParagraphStaysOnThePage() {
        val next = SpeechCursors.nextParagraph(
            SpeechPosition(0, 0), page(0), page(1), pageCount = 3
        )
        assertEquals(SpeechPosition(0, 1), next)
    }

    @Test
    fun nextParagraphCrossesToTheNextPage() {
        val next = SpeechCursors.nextParagraph(
            SpeechPosition(0, 1), page(0), page(1), pageCount = 3
        )
        assertEquals(SpeechPosition(1, 0), next)
    }

    @Test
    fun theLastParagraphOfTheLastPageHasNoSuccessor() {
        assertNull(
            SpeechCursors.nextParagraph(
                SpeechPosition(2, 1), page(2), null, pageCount = 3
            )
        )
    }

    @Test
    fun previousParagraphCrossesBackAPage() {
        val previous = SpeechCursors.previousParagraph(
            SpeechPosition(1, 0), page(1), page(0)
        )
        assertEquals(SpeechPosition(0, 1), previous)
        val within = SpeechCursors.previousParagraph(
            SpeechPosition(1, 1), page(1), page(0)
        )
        assertEquals(SpeechPosition(1, 0), within)
    }

    @Test
    fun theFirstParagraphHasNoPredecessor() {
        assertNull(SpeechCursors.previousParagraph(SpeechPosition(0, 0), page(0), null))
    }

    // ------------------------------------------------------------ voice keys

    @Test
    fun voiceKeysRoundTripEngineLocaleAndName() {
        val key = SpeechVoiceKey("com.google.android.tts", "en-GB", "English (United Kingdom)")
        val decoded = SpeechVoiceKey.decode(key.encode())
        assertEquals(key, decoded)
    }

    @Test
    fun aVoiceKeySurvivesSeparatorsInItsParts() {
        val key = SpeechVoiceKey("engine|one", "hi-IN", "Voice|name: two")
        assertEquals(key, SpeechVoiceKey.decode(key.encode()))
    }

    @Test
    fun aMalformedVoiceKeyDecodesToNull() {
        assertNull(SpeechVoiceKey.decode(null))
        assertNull(SpeechVoiceKey.decode(""))
        assertNull(SpeechVoiceKey.decode("not base64 !!"))
        // Valid Base64 but only two parts.
        val two = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("a|b".toByteArray())
        assertNull(SpeechVoiceKey.decode(two))
    }

    // -------------------------------------------------------------- describe

    @Test
    fun describeSpeechNamesPageChapterAndPosition() {
        val text = describeSpeech(
            pageIndex = 4,
            pageCount = 10,
            position = SpeechPosition(4, 2, 0, 1),
            chapter = chapters.chapters[1]
        )
        assertTrue(text, text.contains("Page 5 of 10"))
        assertTrue(text, text.contains("Two"))
        assertTrue(text, text.contains("paragraph 3"))
    }

    @Test
    fun describeSpeechWithoutAChapterOmitsIt() {
        val text = describeSpeech(
            pageIndex = 0,
            pageCount = 1,
            position = SpeechPosition(0, 0),
            chapter = null
        )
        assertTrue(text, text.contains("Page 1 of 1"))
        assertFalse(text, text.contains("·"))
        assertNotNull(text)
    }
}
