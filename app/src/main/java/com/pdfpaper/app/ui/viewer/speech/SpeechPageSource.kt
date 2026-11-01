package com.pdfpaper.app.ui.viewer.speech

import com.pdfpaper.engine.speech.SpeechPage

/**
 * The pages read aloud needs (T64).
 *
 * The session asks for one page at a time and never holds the whole document,
 * so the source extracts a page only when it is really needed. The service
 * (T64's foreground service) is the real implementation; the test uses a fake
 * that serves pages from a map.
 */
interface SpeechPageSource {

    /** The page, extracted on demand; null when the page does not exist. */
    suspend fun page(index: Int): SpeechPage?

    /** The chapters known so far (the outline plus the headings seen). */
    suspend fun chapters(): SpeechChapterList

    /** The document's page count. */
    fun pageCount(): Int
}
