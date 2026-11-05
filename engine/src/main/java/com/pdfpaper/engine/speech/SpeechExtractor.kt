package com.pdfpaper.engine.speech

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.outline.DocumentOutline
import com.pdfpaper.engine.outline.documentPages
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.reflow.ReflowBlockKind
import com.pdfpaper.engine.reflow.ReflowExtractor
import com.pdfpaper.engine.reflow.ReflowPage
import com.pdfpaper.engine.text.PageLayout
import com.pdfpaper.engine.text.TextRect
import com.pdfpaper.engine.text.layout
import kotlin.math.abs

/**
 * Turns a page into the paragraphs a speech engine will say (T34).
 *
 * The paragraph, heading, list and hidden-page-furniture decisions are T33's
 * reflow rules, so what is spoken is exactly what the reflow view shows: a page
 * number or a footnote that reflow drops is never spoken either. The words and
 * their boxes are new here, because the reader sees the spoken word highlighted
 * on the page and only the engine knows a character's rectangle (T11).
 */
object SpeechExtractor {

    fun extract(
        page: PdfPage,
        options: SpeechOptions = SpeechOptions(),
        decoder: ExternalImageDecoder? = null
    ): Result<SpeechPage> = runCatching {
        val reflow = ReflowExtractor.extract(page, options.reflow, decoder).getOrThrow()
        speak(page, reflow)
    }

    /**
     * The document's own chapters, in outline order. Empty when the document has
     * no usable outline, which tells the caller to use [mergeHeadings] instead.
     */
    fun chapters(document: PdfDocument): List<SpeechChapter> {
        val pages = documentPages(document)
        if (pages.isEmpty()) return emptyList()
        val entries = DocumentOutline.read(document)
        val out = ArrayList<SpeechChapter>(entries.size)
        for (entry in entries) {
            val page = pages.getOrNull(entry.pageIndex) ?: continue
            out += SpeechChapter(
                title = entry.title,
                pageIndex = entry.pageIndex,
                paragraphIndex = 0,
                fraction = fractionOfTop(entry.top, PageGeometry.boxOf(page)),
                source = ChapterSource.OUTLINE
            )
        }
        return out
    }

    /**
     * Adds a page's level 1-2 headings to a chapter list as the fallback when
     * the document has no outline, sorted by position and without duplicates.
     */
    fun mergeHeadings(chapters: List<SpeechChapter>, page: SpeechPage): List<SpeechChapter> {
        val out = ArrayList<SpeechChapter>(chapters.size + page.headings.size)
        out += chapters
        for (heading in page.headings) {
            if (heading.level !in 1..2) continue
            val paragraph = page.paragraphs.getOrNull(heading.paragraphIndex) ?: continue
            out += SpeechChapter(
                title = heading.title,
                pageIndex = page.pageIndex,
                paragraphIndex = heading.paragraphIndex,
                fraction = paragraph.fraction,
                source = ChapterSource.HEADING
            )
        }
        out.sortWith(compareBy({ it.pageIndex }, { it.paragraphIndex }))
        val seen = HashSet<Pair<Int, Int>>()
        return out.filter { seen.add(it.pageIndex to it.paragraphIndex) }
    }

    private fun speak(page: PdfPage, reflow: ReflowPage): SpeechPage {
        val textBlocks = reflow.blocks.filter { it.isText }
        if (textBlocks.isEmpty()) {
            return SpeechPage(page.index, emptyList(), reflow.scanned)
        }
        val layout = page.layout()
        val paragraphs = ArrayList<SpeechParagraph>(textBlocks.size)
        val headings = ArrayList<SpeechHeading>()
        for (block in textBlocks) {
            val start = block.pageTextStart.coerceIn(0, layout.text.length)
            val end = block.pageTextEnd.coerceIn(start, layout.text.length)
            if (end <= start) continue
            val tokens = tokenize(layout.text, start, end)
            if (tokens.isEmpty()) continue
            val words = speakWords(tokens, layout)
            if (words.isEmpty()) continue

            val spoken = StringBuilder()
            val outWords = ArrayList<SpeechWord>(words.size)
            for ((text, rects) in words) {
                if (spoken.isNotEmpty()) spoken.append(' ')
                val wordStart = spoken.length
                spoken.append(text)
                outWords += SpeechWord(text, wordStart, spoken.length, rects)
            }
            val index = paragraphs.size
            paragraphs += SpeechParagraph(
                pageIndex = page.index,
                index = index,
                text = spoken.toString(),
                words = outWords,
                headingLevel = block.level,
                bounds = block.bounds,
                fraction = fractionOf(block.bounds, reflow.box)
            )
            if (block.kind == ReflowBlockKind.HEADING && block.level in 1..2) {
                val title = block.text.trim().take(160)
                if (title.isNotEmpty()) {
                    headings += SpeechHeading(title, page.index, index, block.level)
                }
            }
        }
        return SpeechPage(page.index, paragraphs, reflow.scanned, headings)
    }

    // ---------------------------------------------------------------- words

    /** One token of the page text with its own offsets. */
    private class Token(val text: String, val start: Int, val end: Int)

    private fun tokenize(text: String, from: Int, to: Int): List<Token> {
        val out = ArrayList<Token>()
        var i = from
        while (i < to) {
            while (i < to && text[i].isWhitespace()) i++
            if (i >= to) break
            val start = i
            while (i < to && !text[i].isWhitespace()) i++
            out += Token(text.substring(start, i), start, i)
        }
        return out
    }

    /** A word ready to be said, with the boxes of every line it occupies. */
    private data class Spoken(val text: String, val rects: List<TextRect>)

    private fun speakWords(tokens: List<Token>, layout: PageLayout): List<Spoken> {
        val out = ArrayList<Spoken>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val next = tokens.getOrNull(i + 1)
            val joined = next?.let { joinHyphen(token, it, layout) }
            if (joined != null) {
                out += joined
                i += 2
            } else {
                out += Spoken(token.text, layout.rectsFor(token.start, token.end))
                i += 1
            }
        }
        return out
    }

    /**
     * A word broken with a hyphen at the end of a line: "interna-" + "tional"
     * is one word on two lines. The same conditions as the reflow paragraph
     * text (a letter before the hyphen, a lowercase continuation) keep the
     * spoken text and the reflow text identical.
     */
    private fun joinHyphen(first: Token, second: Token, layout: PageLayout): Spoken? {
        val text = first.text
        if (text.length < 2 || !text.endsWith("-")) return null
        if (!text[text.length - 2].isLetter()) return null
        if (second.text.isEmpty() || !second.text[0].isLowerCase()) return null
        val firstRects = layout.rectsFor(first.start, first.end)
        val secondRects = layout.rectsFor(second.start, second.end)
        if (firstRects.isEmpty() || secondRects.isEmpty()) return null
        if (abs(firstRects.first().y0 - secondRects.first().y0) <= 0.5) return null
        return Spoken(
            text.dropLast(1) + second.text,
            firstRects + secondRects
        )
    }

    private fun fractionOf(bounds: PdfRect, box: PageBox): Double {
        val height = box.rotatedHeight
        if (height <= 0.0) return 0.0
        val matrix = PageGeometry.matrixFor(box, 1.0, 1.0)
        val point = matrix.apply(bounds.centerX, bounds.centerY)
        if (!point[1].isFinite()) return 0.0
        return (point[1] / height).coerceIn(0.0, 1.0)
    }

    /**
     * A destination's top edge as 0..1 from the top of the displayed page. A
     * destination without a top (`/Fit`, `/FitR`) starts at the page's top; the
     * page's own `/Rotate` and crop box are applied through [PageGeometry], so
     * a rotated page still lands on the right line (T61's rule).
     */
    private fun fractionOfTop(top: Double?, box: PageBox): Double {
        if (top == null) return 0.0
        val height = box.rotatedHeight
        if (height <= 0.0) return 0.0
        val matrix = PageGeometry.matrixFor(box, 1.0, 1.0)
        val point = matrix.apply(box.left, top)
        if (!point[1].isFinite()) return 0.0
        return (point[1] / height).coerceIn(0.0, 1.0)
    }
}
