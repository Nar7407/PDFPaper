package com.pdfpaper.engine.redact

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor

/**
 * What a redaction mark is asking to remove (T28).
 *
 * All mark kinds remove every kind of page content that intersects the marked
 * rectangles (text, images, vector paths and annotations). The kind is kept as
 * metadata for callers and for clearer reports; an [IMAGE] mark is normally the
 * result of selecting an image in the UI, and a [PAGE] mark covers the whole
 * page.
 */
enum class RedactionMarkType {
    /** A free-hand rectangular selection. */
    AREA,

    /** A T11 text selection: [RedactionMark.rects] are the line fragments. */
    TEXT_SELECTION,

    /** An image selected as a unit. */
    IMAGE,

    /** The whole page is removed from view. */
    PAGE;

    companion object {
        fun parse(value: String?): RedactionMarkType = when (value?.trim()?.lowercase()) {
            null, "", "area", "rect" -> AREA
            "text", "text-selection", "text_selection", "selection" -> TEXT_SELECTION
            "image" -> IMAGE
            "page", "whole-page", "whole_page" -> PAGE
            else -> throw IllegalArgumentException("Unknown redaction mark type '$value'")
        }
    }
}

/**
 * One redaction request. [pageIndex] is zero-based, matching [com.pdfpaper.engine.page.PdfPage.index].
 *
 * [rects] are in unrotated PDF page space. For [RedactionMarkType.PAGE] an empty
 * list means the page's CropBox. [overlayText] is drawn on top of the cover in
 * black; [color] is the cover fill colour.
 *
 * [expectedText] is not used while rewriting. It is carried so a caller can
 * verify the redaction later without keeping the original selection around.
 */
class RedactionMark(
    val pageIndex: Int,
    val rects: List<PdfRect>,
    val type: RedactionMarkType = RedactionMarkType.AREA,
    val overlayText: String? = null,
    val color: PdfColor = PdfColor(0.0, 0.0, 0.0),
    val expectedText: String? = null
) {
    init {
        require(pageIndex >= 0) { "pageIndex must be non-negative" }
        require(type == RedactionMarkType.PAGE || rects.isNotEmpty()) {
            "A redaction mark needs at least one rectangle"
        }
        require(rects.all { it.left.isFinite() && it.bottom.isFinite() && it.right.isFinite() && it.top.isFinite() }) {
            "Redaction rectangles must be finite"
        }
    }

    constructor(
        pageIndex: Int,
        rect: PdfRect,
        type: RedactionMarkType = RedactionMarkType.AREA,
        overlayText: String? = null,
        color: PdfColor = PdfColor(0.0, 0.0, 0.0),
        expectedText: String? = null
    ) : this(pageIndex, listOf(rect), type, overlayText, color, expectedText)

    /** Bounding box of all fragments, used for annotation and bookmark tests. */
    val bounds: PdfRect? get() = PdfRect.union(rects)

    fun duplicates(): RedactionMark = RedactionMark(
        pageIndex, rects, type, overlayText, color, expectedText
    )
}

/** Plain-text, regular-expression and preset searches (T28). */
enum class RedactionPreset(val regex: String) {
    EMAIL("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"),
    PHONE("(?:\\+?\\d{1,3}[\\s.\\-]?)?(?:\\(?\\d{2,4}\\)?[\\s.\\-]?)?\\d{3,4}[\\s.\\-]?\\d{4}"),
    NUMBER("(?<![\\d.])\\d+(?:\\.\\d+)?(?![\\d.])");

    companion object {
        fun parse(value: String): RedactionPreset = when (value.trim().lowercase()) {
            "email", "e-mail" -> EMAIL
            "phone", "telephone", "mobile" -> PHONE
            "number", "numbers", "numeric" -> NUMBER
            else -> throw IllegalArgumentException("Unknown redaction preset '$value'")
        }
    }
}

/** One found occurrence, ready to become a [RedactionMark]. */
class RedactionFindMatch(
    val pageIndex: Int,
    /** Matched text as extracted from the page. */
    val text: String,
    /** Inclusive offset in the page's extracted text. */
    val start: Int,
    /** Exclusive offset in the page's extracted text. */
    val end: Int,
    /** Page-space rectangles covering the match. */
    val rects: List<PdfRect>,
    /** True when the match came from a Form XObject stream. */
    val inForm: Boolean = false
) {
    fun toMark(
        overlayText: String? = null,
        color: PdfColor = PdfColor(0.0, 0.0, 0.0)
    ): RedactionMark = RedactionMark(
        pageIndex = pageIndex,
        rects = rects,
        type = RedactionMarkType.TEXT_SELECTION,
        overlayText = overlayText,
        color = color,
        expectedText = text
    )
}

/** Where a non-redactable remnant was observed during verification. */
enum class RedactionLeakKind { TEXT, RENDERED_TEXT, RAW_STREAM }

/** One remaining occurrence of a string that should have been removed. */
class RedactionLeak(
    val expected: String,
    val pageIndex: Int,
    /** Object number of the stream, or -1 when the leak came from text/render analysis. */
    val streamObjNum: Int,
    val kind: RedactionLeakKind,
    val detail: String
)

/**
 * Result of [PdfRedaction.verify]. [remaining] counts matches per expected
 * string, including text extraction, re-rendered text commands, and decoded
 * stream bytes. [unredacted] contains content the apply phase could not safely
 * rewrite (for example a JPEG with no available decoder).
 */
class RedactionVerification(
    val expected: List<String>,
    val remaining: Map<String, Int>,
    val leaks: List<RedactionLeak>,
    val unredacted: List<String> = emptyList(),
    val checkedPages: Int = 0
) {
    val passed: Boolean get() = remaining.values.all { it == 0 } && unredacted.isEmpty()
}

/**
 * Output of [PdfRedaction.apply] or [PdfRedaction.deletePages].
 *
 * [complete] is false when any content could not be rewritten safely. The
 * caller should display [unredacted] and should not treat the document as
 * privacy-safe; [PdfRedaction.verify] reports the same condition.
 */
class RedactionResult(
    val bytes: ByteArray,
    val warnings: List<String> = emptyList(),
    val unredacted: List<String> = emptyList(),
    val marksApplied: Int = 0,
    val pagesDeleted: Int = 0,
    val pageCount: Int = 0
) {
    val complete: Boolean get() = unredacted.isEmpty()
}
