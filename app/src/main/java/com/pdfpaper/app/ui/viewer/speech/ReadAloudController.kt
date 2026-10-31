package com.pdfpaper.app.ui.viewer.speech

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.speech.SpeechChapter
import com.pdfpaper.engine.speech.SpeechPage
import com.pdfpaper.engine.speech.SpeechParagraph
import com.pdfpaper.engine.text.TextRect

/**
 * The pages read aloud has already extracted (T34). [page] returns null when the
 * caller has to load that page first, which is how the controller asks for one
 * page at a time and never holds the whole document.
 */
interface SpeechPageFeed {
    fun page(index: Int): SpeechPage?
}

/** Why read aloud stopped. [USER] is the reader pressing pause. */
enum class PauseReason { USER, SLEEP, NO_TEXT, ERROR, FINISHED }

/**
 * What the platform should do next. The controller never touches a speech
 * engine, a bitmap or a coroutine: it returns actions and the caller performs
 * them, so the same state machine drives the viewer (T34) and the background
 * service (T64).
 */
sealed interface SpeechAction {

    data class Speak(
        val utteranceId: String,
        val pageIndex: Int,
        val paragraphIndex: Int,
        val text: String,
        val rate: Float
    ) : SpeechAction

    /** [paragraphOnly] is true when the engine cannot report word positions. */
    data class Highlight(
        val pageIndex: Int,
        val paragraph: SpeechParagraph,
        val rects: List<TextRect>,
        val paragraphOnly: Boolean
    ) : SpeechAction

    data class ScrollTo(val pageIndex: Int, val fraction: Double) : SpeechAction

    data class NeedPage(val pageIndex: Int) : SpeechAction

    data class Paused(val reason: PauseReason, val message: String?) : SpeechAction

    data object Finished : SpeechAction

    data class StateChanged(val state: SpeechState) : SpeechAction
}

/**
 * The synchronous read-aloud state machine (T34).
 *
 * It speaks one paragraph at a time, asks for the next page only when the
 * current one is exhausted, and turns the speech engine's word offsets back
 * into highlight rectangles. Every public method returns the actions for the
 * change it made, starting with [SpeechAction.StateChanged] when the state
 * changed, so a caller never has to observe a property.
 */
class ReadAloudController(
    chapters: SpeechChapterList,
    private val feed: SpeechPageFeed,
    initialSpeed: Float = 1f,
    initialSleep: SleepOption = SleepOption.OFF,
    private val pageCount: () -> Int = { 0 },
    private val clock: () -> Long = System::currentTimeMillis
) {

    private var chapterList = chapters
    private var speed = SpeechSpeed.nearest(initialSpeed)
    private var sleepOption = initialSleep
    private var sleepStartedAt = 0L
    private var position = SpeechPosition()
    private var chapter: SpeechChapter? = null
    private var currentParagraph: SpeechParagraph? = null
    private var speaking = false
    private var active = false
    private var message: String? = null
    private var hasWordRanges = true

    /** A position whose page has not been extracted yet. */
    private var pending: SpeechPosition? = null

    /** A page known to have no text, so [resume] can step past it. */
    private var skipPage: Int? = null

    val state: SpeechState
        get() = SpeechState(
            active = active,
            speaking = speaking,
            position = position,
            chapter = chapter,
            speed = speed,
            sleep = sleepOption,
            sleepStartedAt = sleepStartedAt,
            paragraphText = currentParagraph?.text.orEmpty(),
            hasWordRanges = hasWordRanges,
            message = message
        )

    fun updateChapters(chapters: SpeechChapterList) {
        chapterList = chapters
        val updated = chapterList.chapterAt(position.pageIndex, position.paragraphIndex)
        if (updated != chapter) chapter = updated
    }

    // ------------------------------------------------------------- transport

    fun start(position: SpeechPosition): List<SpeechAction> {
        active = true
        speaking = false
        currentParagraph = null
        pending = null
        skipPage = null
        message = null
        this.position = position.normalized()
        chapter = chapterList.chapterAt(this.position.pageIndex, this.position.paragraphIndex)
        return begin(this.position)
    }

    fun resume(): List<SpeechAction> {
        if (!active) return start(position)
        val waiting = pending
        if (waiting != null) {
            pending = null
            return begin(waiting)
        }
        val skipped = skipPage
        if (skipped != null) {
            skipPage = null
            return begin(
                SpeechPosition(
                    pageIndex = skipped + 1,
                    paragraphIndex = 0,
                    wordIndex = 0,
                    chapterIndex = position.chapterIndex
                )
            )
        }
        return begin(position)
    }

    fun pause(reason: PauseReason = PauseReason.USER, message: String? = null): List<SpeechAction> {
        speaking = false
        this.message = message ?: defaultMessage(reason)
        return listOf(snapshot(), SpeechAction.Paused(reason, this.message))
    }

    fun stop(): List<SpeechAction> {
        active = false
        speaking = false
        currentParagraph = null
        pending = null
        skipPage = null
        message = null
        return listOf(snapshot())
    }

    // --------------------------------------------------------------- cursors

    fun nextParagraph(): List<SpeechAction> {
        val next = SpeechCursors.nextParagraph(
            position,
            feed.page(position.pageIndex),
            feed.page(position.pageIndex + 1),
            pageCount()
        ) ?: return finish()
        return clearHighlight() + begin(next)
    }

    fun previousParagraph(): List<SpeechAction> {
        val previous = SpeechCursors.previousParagraph(
            position,
            feed.page(position.pageIndex),
            feed.page(position.pageIndex - 1)
        ) ?: return listOf(snapshot())
        return clearHighlight() + begin(previous)
    }

    fun nextChapter(): List<SpeechAction> {
        val next = chapterList.next(position.pageIndex, position.paragraphIndex)
            ?: return finish()
        return clearHighlight() + begin(
            SpeechPosition(next.pageIndex, next.paragraphIndex, 0, chapterList.indexOf(next))
        )
    }

    fun previousChapter(): List<SpeechAction> {
        val current = chapterList.chapterAt(position.pageIndex, position.paragraphIndex)
            ?: return listOf(snapshot())
        val inside = position.pageIndex > current.pageIndex ||
            position.paragraphIndex > current.paragraphIndex
        if (inside) {
            return clearHighlight() + begin(
                SpeechPosition(current.pageIndex, current.paragraphIndex, 0, chapterList.indexOf(current))
            )
        }
        val previous = chapterList.previous(current.pageIndex, current.paragraphIndex)
            ?: return clearHighlight() + begin(
                SpeechPosition(current.pageIndex, current.paragraphIndex, 0, chapterList.indexOf(current))
            )
        return clearHighlight() + begin(
            SpeechPosition(previous.pageIndex, previous.paragraphIndex, 0, chapterList.indexOf(previous))
        )
    }

    // ---------------------------------------------------------------- options

    fun setSpeed(rate: Float): List<SpeechAction> {
        speed = SpeechSpeed.nearest(rate)
        val paragraph = currentParagraph
        if (!speaking || paragraph == null) return listOf(snapshot())
        return listOf(snapshot(), speakAction(paragraph))
    }

    fun setSleep(option: SleepOption): List<SpeechAction> {
        sleepOption = option
        sleepStartedAt = if (option.minutes != null) clock() else 0L
        if (option == SleepOption.END_OF_CHAPTER && chapterList.isEmpty) {
            message = "This document has no chapters, so the sleep timer is off"
        }
        return listOf(snapshot())
    }

    // --------------------------------------------------------------- callbacks

    fun onWordStart(utteranceId: String, start: Int): List<SpeechAction> {
        if (utteranceId != currentUtteranceId()) return emptyList()
        val paragraph = currentParagraph ?: return emptyList()
        val word = paragraph.wordAt(start)
        if (word != null) {
            return listOf(
                SpeechAction.Highlight(position.pageIndex, paragraph, word.rects, false)
            )
        }
        // The engine is saying the paragraph but cannot say where: highlight the
        // whole paragraph and tell the reader once, rather than showing nothing.
        hasWordRanges = false
        message = "This voice does not report word positions, so the paragraph is highlighted"
        return listOf(
            snapshot(),
            SpeechAction.Highlight(
                position.pageIndex,
                paragraph,
                listOf(paragraph.bounds.toTextRect()),
                true
            )
        )
    }

    fun onUtteranceDone(utteranceId: String): List<SpeechAction> {
        if (utteranceId != currentUtteranceId()) return emptyList()
        speaking = false
        val clear = clearHighlight()
        val next = SpeechCursors.nextParagraph(
            position,
            feed.page(position.pageIndex),
            feed.page(position.pageIndex + 1),
            pageCount()
        ) ?: return listOf(snapshot()) + clear + listOf(SpeechAction.Finished)
        return clear + continueAt(next, respectChapterSleep = true)
    }

    fun onUtteranceError(utteranceId: String, message: String): List<SpeechAction> {
        if (utteranceId != currentUtteranceId()) return emptyList()
        return pause(PauseReason.ERROR, message)
    }

    fun onPageReady(page: SpeechPage): List<SpeechAction> {
        val waiting = pending ?: return emptyList()
        if (waiting.pageIndex != page.pageIndex) return emptyList()
        pending = null
        return begin(waiting, page)
    }

    fun tick(now: Long = clock()): List<SpeechAction> {
        val remaining = SleepTimer(sleepOption, sleepStartedAt).remainingMillis(now)
            ?: return emptyList()
        if (remaining > 0L) return emptyList()
        return pause(PauseReason.SLEEP, "Sleep timer finished")
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Starts at [target]. [ready] is the page the caller has just extracted for a
     * pending [SpeechAction.NeedPage]; it is used directly so the caller does not
     * have to put it into the feed before calling back.
     */
    private fun begin(target: SpeechPosition, ready: SpeechPage? = null): List<SpeechAction> {
        val page = ready ?: feed.page(target.pageIndex)
        if (page == null) {
            pending = target
            position = target
            currentParagraph = null
            speaking = false
            message = null
            chapter = chapterList.chapterAt(target.pageIndex, target.paragraphIndex)
            return listOf(snapshot(), SpeechAction.NeedPage(target.pageIndex))
        }
        if (page.paragraphs.isEmpty()) {
            pending = null
            skipPage = target.pageIndex
            position = target
            currentParagraph = null
            speaking = false
            message = NO_TEXT
            chapter = chapterList.chapterAt(target.pageIndex, target.paragraphIndex)
            return listOf(snapshot(), SpeechAction.Paused(PauseReason.NO_TEXT, NO_TEXT))
        }
        val paragraph = page.paragraphs.getOrNull(target.paragraphIndex)
            ?: page.paragraphs.first()
        position = SpeechPosition(
            pageIndex = page.pageIndex,
            paragraphIndex = paragraph.index,
            wordIndex = 0,
            chapterIndex = target.chapterIndex
        )
        chapter = chapterList.chapterAt(position.pageIndex, position.paragraphIndex)
        currentParagraph = paragraph
        pending = null
        skipPage = null
        speaking = true
        message = null
        return listOf(
            snapshot(),
            SpeechAction.ScrollTo(page.pageIndex, paragraph.fraction),
            speakAction(paragraph)
        )
    }

    /**
     * Speaks the paragraph at [target], unless the sleep timer was set to end
     * of chapter and the target starts another chapter: then it pauses and
     * remembers where to continue.
     */
    private fun continueAt(target: SpeechPosition, respectChapterSleep: Boolean): List<SpeechAction> {
        if (respectChapterSleep &&
            sleepOption == SleepOption.END_OF_CHAPTER &&
            chapterList.chapters.isNotEmpty()
        ) {
            val nextChapter = chapterList.chapterAt(target.pageIndex, target.paragraphIndex)
            val current = chapter
            if (nextChapter != null && current != null && nextChapter != current) {
                pending = target
                return pause(PauseReason.SLEEP, "Sleep timer: end of chapter")
            }
        }
        return begin(target)
    }

    private fun finish(): List<SpeechAction> {
        speaking = false
        currentParagraph = null
        pending = null
        message = END
        return listOf(snapshot(), SpeechAction.Finished)
    }

    private fun speakAction(paragraph: SpeechParagraph): SpeechAction.Speak = SpeechAction.Speak(
        utteranceId = utteranceId(position.pageIndex, paragraph.index),
        pageIndex = position.pageIndex,
        paragraphIndex = paragraph.index,
        text = paragraph.text,
        rate = speed
    )

    private fun clearHighlight(): List<SpeechAction> {
        val paragraph = currentParagraph ?: return emptyList()
        return listOf(SpeechAction.Highlight(position.pageIndex, paragraph, emptyList(), false))
    }

    private fun currentUtteranceId(): String =
        if (currentParagraph == null) "" else utteranceId(position.pageIndex, position.paragraphIndex)

    private fun snapshot(): SpeechAction.StateChanged = SpeechAction.StateChanged(state)

    private fun defaultMessage(reason: PauseReason): String? = when (reason) {
        PauseReason.USER -> null
        PauseReason.SLEEP -> "Sleep timer finished"
        PauseReason.NO_TEXT -> NO_TEXT
        PauseReason.ERROR -> "Read aloud stopped"
        PauseReason.FINISHED -> END
    }

    private fun SpeechPosition.normalized(): SpeechPosition = SpeechPosition(
        pageIndex = pageIndex.coerceAtLeast(0),
        paragraphIndex = paragraphIndex.coerceAtLeast(0),
        wordIndex = wordIndex.coerceAtLeast(0),
        chapterIndex = chapterIndex.coerceAtLeast(0)
    )

    companion object {
        private const val NO_TEXT = "No text on this page"
        private const val END = "End of the document"

        /** The id a speech engine reports word ranges and completion for. */
        fun utteranceId(pageIndex: Int, paragraphIndex: Int): String =
            "p:$pageIndex:$paragraphIndex"
    }
}

/** The whole paragraph as one highlight box when the voice reports no words. */
private fun PdfRect.toTextRect(): TextRect = TextRect(left, bottom, right, top)
