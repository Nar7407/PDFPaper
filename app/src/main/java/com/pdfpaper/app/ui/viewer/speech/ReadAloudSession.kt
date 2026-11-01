package com.pdfpaper.app.ui.viewer.speech

import com.pdfpaper.engine.speech.SpeechPage
import com.pdfpaper.engine.text.TextRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the viewer shows while a document is read aloud (T64).
 *
 * The session owns the reading and publishes this state; the viewer only
 * draws it and sends commands back. [wordPageIndex] and [wordRects] say which
 * word is being spoken, so the viewer can highlight it; [scrollToken] is bumped
 * on every scroll request, so the viewer scrolls once per request instead of
 * scrolling again on every recomposition.
 */
data class ReadAloudPlaybackState(
    val active: Boolean = false,
    val speaking: Boolean = false,
    val documentName: String = "",
    val pageIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val chapterTitle: String? = null,
    val paragraphText: String = "",
    val wordPageIndex: Int = -1,
    val wordRects: List<TextRect> = emptyList(),
    val paragraphOnly: Boolean = false,
    val message: String? = null,
    val finished: Boolean = false,
    val scrollPageIndex: Int = -1,
    val scrollFraction: Double = 0.0,
    /** Bumped on every scroll request, so the viewer consumes it once. */
    val scrollToken: Long = 0L
)

/**
 * The playback executor (T64).
 *
 * It owns T34's [ReadAloudController] - the pure state machine - and does what
 * the controller asks: speak a paragraph, highlight a word, scroll to a place,
 * fetch the next page. The engine, the pages and the coroutine scope are given
 * to it, so the same session drives the viewer's foreground reading and the
 * background service; only the owner of the session differs.
 *
 * The session is used from one thread (the service runs it on the main
 * thread), so the page cache below needs no locking.
 */
class ReadAloudSession(
    private val synthesis: SpeechSynthesis,
    private val pages: SpeechPageSource,
    private val scope: CoroutineScope,
    initialSpeed: Float = 1f
) {

    private val _state = MutableStateFlow(ReadAloudPlaybackState())
    val state: StateFlow<ReadAloudPlaybackState> = _state.asStateFlow()

    /** The controller of the current reading; null while nothing is read. */
    private var controller: ReadAloudController? = null

    private var speed = SpeechSpeed.nearest(initialSpeed)

    /** The pages already extracted. Only a few are kept, never the whole file. */
    private val cache = LinkedHashMap<Int, SpeechPage>()

    /** The focus event that paused the reading, so only it can be undone. */
    private var focusPause: FocusEvent? = null

    /** The controller asks for pages synchronously; this is what it sees. */
    private val feed = object : SpeechPageFeed {
        override fun page(index: Int): SpeechPage? = cache[index]
    }

    // ------------------------------------------------------------- transport

    /**
     * Starts reading [documentName] at [position].
     *
     * The chapters are loaded first, because the controller walks them for
     * "next chapter" and for the end-of-chapter sleep timer.
     */
    suspend fun start(documentName: String, position: SpeechPosition) {
        this.controller?.stop()
        cache.clear()
        focusPause = null
        val created = ReadAloudController(
            chapters = pages.chapters(),
            feed = feed,
            initialSpeed = speed,
            pageCount = { pages.pageCount() }
        )
        controller = created
        _state.value = ReadAloudPlaybackState(
            active = true,
            documentName = documentName,
            scrollToken = _state.value.scrollToken
        )
        run(created.start(position))
    }

    /** Pauses the reading. [message] is shown under the bar. */
    fun pause(message: String? = null) {
        val current = controller ?: return
        run(current.pause(PauseReason.USER, message))
    }

    /** Starts the reading again where it stopped. */
    fun resume() {
        val current = controller ?: return
        run(current.resume())
    }

    /** Stops the reading and forgets it. */
    fun stop() {
        val current = controller
        controller = null
        focusPause = null
        cache.clear()
        synthesis.stop()
        current?.let { run(it.stop()) }
        val state = _state.value
        _state.value = state.copy(
            active = false,
            speaking = false,
            finished = false,
            message = null,
            wordPageIndex = -1,
            wordRects = emptyList(),
            paragraphOnly = false
        )
    }

    /** Stops the reading and releases the speech engine. */
    fun shutdown() {
        stop()
        synthesis.shutdown()
    }

    // --------------------------------------------------------------- cursors

    fun nextParagraph() {
        val current = controller ?: return
        run(current.nextParagraph())
    }

    fun previousParagraph() {
        val current = controller ?: return
        run(current.previousParagraph())
    }

    fun nextChapter() {
        val current = controller ?: return
        run(current.nextChapter())
    }

    fun previousChapter() {
        val current = controller ?: return
        run(current.previousChapter())
    }

    /** Changes the pace. The current paragraph is spoken again at the new pace. */
    fun setSpeed(rate: Float) {
        speed = SpeechSpeed.nearest(rate)
        val current = controller ?: return
        run(current.setSpeed(speed))
    }

    // ------------------------------------------------------------- callbacks

    /** The speech engine is ready, or not; [message] says why when it is not. */
    fun onReady(ok: Boolean, message: String?) {
        if (!ok) {
            pause(message ?: "This device has no speech engine")
            return
        }
        if (message != null) {
            _state.value = _state.value.copy(message = message)
        }
    }

    /** The engine started speaking the word at [start] of the utterance. */
    fun onRangeStart(utteranceId: String, start: Int) {
        val current = controller ?: return
        run(current.onWordStart(utteranceId, start))
    }

    /** The engine spoke the utterance to the end. */
    fun onUtteranceDone(utteranceId: String) {
        val current = controller ?: return
        run(current.onUtteranceDone(utteranceId))
    }

    /** The engine could not speak the utterance. */
    fun onUtteranceError(utteranceId: String, message: String) {
        val current = controller ?: return
        run(current.onUtteranceError(utteranceId, message))
    }

    /** The platform told us about the sound: a focus change or a noisy device. */
    fun onFocusEvent(event: FocusEvent) {
        when (AudioFocusPolicy.actionAfter(focusPause, event)) {
            FocusAction.PAUSE -> {
                focusPause = event
                pause(focusMessage(event))
            }
            FocusAction.RESUME -> {
                focusPause = null
                resume()
            }
            FocusAction.NONE -> Unit
        }
    }

    // --------------------------------------------------------------- actions

    /** Does what the controller asks for, one action at a time. */
    private fun run(actions: List<SpeechAction>) {
        for (action in actions) {
            when (action) {
                is SpeechAction.Speak ->
                    synthesis.speak(action.text, action.utteranceId, action.rate)
                is SpeechAction.Highlight -> publishHighlight(action)
                is SpeechAction.ScrollTo -> publishScroll(action)
                is SpeechAction.NeedPage -> fetchPage(action.pageIndex)
                is SpeechAction.Paused -> publishPaused(action)
                SpeechAction.Finished -> publishFinished()
                is SpeechAction.StateChanged -> publishState(action.state)
            }
        }
    }

    /** The word being spoken, or nothing when the highlight is cleared. */
    private fun publishHighlight(action: SpeechAction.Highlight) {
        val state = _state.value
        if (action.rects.isEmpty()) {
            _state.value = state.copy(
                wordPageIndex = -1,
                wordRects = emptyList(),
                paragraphOnly = false
            )
        } else {
            _state.value = state.copy(
                wordPageIndex = action.pageIndex,
                wordRects = action.rects,
                paragraphOnly = action.paragraphOnly
            )
        }
    }

    /** Where the viewer has to scroll to, once. */
    private fun publishScroll(action: SpeechAction.ScrollTo) {
        val state = _state.value
        _state.value = state.copy(
            scrollPageIndex = action.pageIndex,
            scrollFraction = action.fraction,
            scrollToken = state.scrollToken + 1
        )
    }

    /** The reading was paused: publish why and stop the engine. */
    private fun publishPaused(action: SpeechAction.Paused) {
        synthesis.stop()
        _state.value = _state.value.copy(
            speaking = false,
            message = action.message,
            finished = action.reason == PauseReason.FINISHED
        )
    }

    /** The document ended. */
    private fun publishFinished() {
        synthesis.stop()
        val state = _state.value
        _state.value = state.copy(
            speaking = false,
            finished = true,
            message = END,
            wordPageIndex = -1,
            wordRects = emptyList()
        )
    }

    /** The controller's own state: where the reading is and what it says. */
    private fun publishState(state: SpeechState) {
        val current = _state.value
        _state.value = current.copy(
            active = state.active,
            speaking = state.speaking,
            pageIndex = state.position.pageIndex,
            paragraphIndex = state.position.paragraphIndex,
            chapterTitle = state.chapter?.title,
            paragraphText = state.paragraphText,
            message = state.message
        )
    }

    /**
     * Extracts the page the controller asked for and hands it back.
     *
     * A page that does not exist ends the reading: the controller asked for
     * the page after the last one.
     */
    private fun fetchPage(index: Int) {
        scope.launch {
            val page = pages.page(index)
            val current = controller ?: return@launch
            if (page == null) {
                run(current.pause(PauseReason.FINISHED, END))
                return@launch
            }
            cachePage(page)
            run(current.onPageReady(page))
        }
    }

    /** Keeps a few pages, so the reader can go back without extracting again. */
    private fun cachePage(page: SpeechPage) {
        cache[page.pageIndex] = page
        while (cache.size > MAX_CACHED_PAGES) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }

    /** What the reader is told when the sound is taken away. */
    private fun focusMessage(event: FocusEvent): String = when (event) {
        FocusEvent.NOISY -> "Paused because the headphones were unplugged"
        FocusEvent.LOSS -> "Paused because another app took the sound"
        FocusEvent.LOSS_TRANSIENT -> "Paused while another app is using the sound"
        else -> "Paused"
    }

    private companion object {
        const val MAX_CACHED_PAGES = 3
        const val END = "End of the document"
    }
}
