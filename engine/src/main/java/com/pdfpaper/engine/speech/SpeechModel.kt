package com.pdfpaper.engine.speech

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.reflow.ReflowOptions
import com.pdfpaper.engine.text.TextRect

/**
 * One word of the spoken text (T34). [start]/[end] are offsets in
 * [SpeechParagraph.text], so the character range the platform speech engine
 * reports through `onRangeStart` maps straight back to this word. [rects] are
 * the page-space boxes to highlight: a word broken with a hyphen over two lines
 * has two.
 */
data class SpeechWord(
    val text: String,
    val start: Int,
    val end: Int,
    val rects: List<TextRect>
)

/**
 * One paragraph as the reader hears it. [bounds] is the page-space box of the
 * whole paragraph, used when the speech engine cannot report a word range;
 * [fraction] is its centre as 0..1 from the top of the displayed page, which is
 * what the viewer scrolls to.
 */
data class SpeechParagraph(
    val pageIndex: Int,
    val index: Int,
    val text: String,
    val words: List<SpeechWord>,
    val headingLevel: Int,
    val bounds: PdfRect,
    val fraction: Double
) {
    val isEmpty: Boolean get() = words.isEmpty() || text.isBlank()

    /** The word holding [offset] of [text]; a space maps to the word before it. */
    fun wordAt(offset: Int): SpeechWord? {
        if (offset < 0 || offset >= text.length || words.isEmpty()) return null
        var before: SpeechWord? = null
        for (word in words) {
            if (offset >= word.start && offset < word.end) return word
            if (word.start <= offset) before = word
        }
        return before ?: words.first()
    }

    /** The highlight boxes of the words [first]..[last] (inclusive). */
    fun rectsForWords(first: Int, last: Int): List<TextRect> {
        if (first > last) return emptyList()
        return words.subList(
            first.coerceAtLeast(0),
            (last + 1).coerceAtMost(words.size)
        ).flatMap { it.rects }
    }
}

/** A heading that can act as a chapter when the document has no outline. */
data class SpeechHeading(
    val title: String,
    val pageIndex: Int,
    val paragraphIndex: Int,
    val level: Int
)

/** Where a chapter came from: the document's own outline, or a T33 heading. */
enum class ChapterSource { OUTLINE, HEADING }

/**
 * One stop for "next chapter" and the end-of-chapter sleep timer. An outline
 * chapter starts at the destination's top ([fraction]) and at paragraph 0; a
 * heading chapter starts at its own paragraph.
 */
data class SpeechChapter(
    val title: String,
    val pageIndex: Int,
    val paragraphIndex: Int,
    val fraction: Double,
    val source: ChapterSource
)

/** One page ready to be spoken. */
data class SpeechPage(
    val pageIndex: Int,
    val paragraphs: List<SpeechParagraph>,
    val scanned: Boolean,
    val headings: List<SpeechHeading> = emptyList()
) {
    /** True when the page has nothing to read (a scan, a picture, a blank). */
    val isEmpty: Boolean get() = paragraphs.isEmpty()
}

/** What speech extraction keeps from the reflow rules (T33). */
data class SpeechOptions(val reflow: ReflowOptions = ReflowOptions.DEFAULT)
