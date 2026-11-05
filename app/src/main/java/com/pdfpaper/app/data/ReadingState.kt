package com.pdfpaper.app.data

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/**
 * How the reader was looking at a document (T38b). The mode is part of the
 * session because "fit content" (T57) and reflow (T33) change what a page even
 * is; restoring only the page number would land the reader on the wrong text.
 */
enum class ReadingMode {
    /** Whole pages, as they are. */
    PAGE,

    /** Pages framed to their content (T57). */
    FIT_CONTENT,

    /** The continuous text view (T33). */
    REFLOW,

    /** One column (or figure) of the page at a time (T59). */
    COLUMN_FLOW
}

/** The orientation the reader chose for this document; AUTO follows the device. */
enum class ReadingOrientation { AUTO, PORTRAIT, LANDSCAPE }

/**
 * One document's reading session: where the reader was and how they were
 * looking at it. It is app data, never written into the PDF.
 */
data class ReadingState(
    /** Zero-based page at the top of the viewport. */
    val pageIndex: Int = 0,
    /** How far into that page the viewport starts, 0..1. */
    val pageFraction: Float = 0f,
    /** The page count when the state was saved, for the Recent list. */
    val pageCount: Int = 0,
    val zoom: Float = 1f,
    val mode: ReadingMode = ReadingMode.PAGE,
    val orientation: ReadingOrientation = ReadingOrientation.AUTO,
    /** The last tool the reader had selected, if any. */
    val lastToolId: String? = null,
    /** T59: the reading-order index of the segment column flow was on. */
    val flowOrder: Int = 0,
    /** T34: the paragraph read aloud reached, for "continue reading aloud". */
    val speechPage: Int = 0,
    val speechParagraph: Int = 0,
    val updatedAt: Long = 0L
) {
    companion object {
        /** The viewer's zoom limits, shared so a restored zoom is always valid. */
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 6f
    }
}

/** Where a document should open, after the saved state met the file. */
data class RestoredReading(
    val pageIndex: Int = 0,
    val pageFraction: Float = 0f,
    val zoom: Float = 1f,
    val mode: ReadingMode = ReadingMode.PAGE,
    val orientation: ReadingOrientation = ReadingOrientation.AUTO,
    val lastToolId: String? = null,
    /** T59: which segment of [pageIndex] column flow was on. */
    val flowOrder: Int = 0,
    /** T34: the paragraph read aloud reached, offered as "Continue reading aloud". */
    val speechPage: Int = 0,
    val speechParagraph: Int = 0,
    /**
     * A short sentence for the reader when the file no longer has the page that
     * was saved (it was replaced by a shorter revision, for example).
     */
    val note: String? = null,
    /**
     * True when the values came from a saved session. A document with no saved
     * session keeps whatever framing it already had (T57), because there is no
     * session opinion to override it with.
     */
    val restoredFromSession: Boolean = false
)

/**
 * Turns a saved [ReadingState] into the position a document opens at. Every
 * rule lives here so it is unit-tested without a device: an explicit page (a
 * search hit or a hub row) wins, "Always start at page 1" ignores the saved
 * position but keeps the mode and zoom, and a shorter file clamps to its last
 * page and says so.
 */
object ReadingRestore {

    fun resolve(
        state: ReadingState?,
        pageCount: Int,
        alwaysStartAtPageOne: Boolean = false,
        explicitPage: Int? = null
    ): RestoredReading {
        if (pageCount <= 0) return RestoredReading()
        val last = pageCount - 1
        val zoom = (state?.zoom ?: 1f)
            .coerceIn(ReadingState.MIN_ZOOM, ReadingState.MAX_ZOOM)
        val mode = state?.mode ?: ReadingMode.PAGE
        val orientation = state?.orientation ?: ReadingOrientation.AUTO
        val tool = state?.lastToolId
        // A page that moved is a different page, so its flow segment is gone.
        val flowOrder = state?.flowOrder ?: 0
        // No saved session at all: page 1, no zoom, the default mode, and the
        // framing the document already had (there is no session opinion to
        // override it with).
        if (state == null) return RestoredReading()
        if (explicitPage != null) {
            // The caller asked for a page (a search hit, a hub row), so the
            // saved spot is not used - but the mode and zoom still are.
            return RestoredReading(
                pageIndex = explicitPage.coerceIn(0, last),
                pageFraction = 0f,
                zoom = zoom,
                mode = mode,
                orientation = orientation,
                lastToolId = tool,
                flowOrder = 0,
                speechPage = state.speechPage.coerceIn(0, last),
                speechParagraph = if (state.speechPage > last) {
                    0
                } else {
                    state.speechParagraph.coerceAtLeast(0)
                },
                restoredFromSession = true
            )
        }
        if (alwaysStartAtPageOne) {
            // The reader asked to always begin at the first page; the mode,
            // zoom and tool are still theirs.
            return RestoredReading(
                pageIndex = 0,
                pageFraction = 0f,
                zoom = zoom,
                mode = mode,
                orientation = orientation,
                lastToolId = tool,
                flowOrder = 0,
                speechPage = state.speechPage.coerceIn(0, last),
                speechParagraph = if (state.speechPage > last) {
                    0
                } else {
                    state.speechParagraph.coerceAtLeast(0)
                },
                restoredFromSession = true
            )
        }
        val page = state.pageIndex.coerceIn(0, last)
        val clamped = page != state.pageIndex
        val speechPage = state.speechPage.coerceIn(0, last)
        val speechMoved = speechPage != state.speechPage
        return RestoredReading(
            pageIndex = page,
            // A page that moved is a different page, so its fraction is gone.
            pageFraction = if (clamped) 0f else state.pageFraction.coerceIn(0f, 1f),
            zoom = zoom,
            mode = mode,
            orientation = orientation,
            lastToolId = tool,
            flowOrder = if (clamped) 0 else flowOrder.coerceAtLeast(0),
            // A shorter file must not offer a paragraph that no longer exists.
            speechPage = speechPage,
            speechParagraph = if (clamped || speechMoved) {
                0
            } else {
                state.speechParagraph.coerceAtLeast(0)
            },
            note = if (clamped) {
                "This document changed and now has ${pageCountLabel(pageCount)}. " +
                    "Showing page ${page + 1}."
            } else {
                null
            },
            restoredFromSession = true
        )
    }

    private fun pageCountLabel(count: Int): String =
        if (count == 1) "1 page" else "$count pages"
}

/** "Page 48 of 210", or null when the count is not known yet. */
fun readingProgressLabel(pageIndex: Int, pageCount: Int): String? {
    if (pageCount <= 0) return null
    val page = (pageIndex + 1).coerceIn(1, pageCount)
    return "Page $page of $pageCount"
}

/**
 * The pixel offset that puts [fraction] of a page at the top, estimated from
 * the page's own shape before it has been laid out. [aspect] is the page's
 * width divided by its height (as drawn, so `/Rotate` is already applied). The
 * first frame needs an offset, and a page's height is only known once it has
 * been measured.
 */
fun readingOffsetPx(fraction: Float, pageWidthPx: Int, aspect: Float): Int {
    if (pageWidthPx <= 0 || aspect <= 0f) return 0
    val height = pageWidthPx / aspect
    return (fraction.coerceIn(0f, 1f) * height).toInt()
}

/**
 * Storage format for one document's session, pure JVM so it is unit-testable.
 * Fields are positional and separated by a `|`; the tool id is Base64 so an
 * unknown or empty value can never break the record. A record written by a
 * different version decodes to null rather than to a wrong position.
 */
object ReadingStateCodec {

    private const val VERSION = 1
    private const val SEPARATOR = '|'
    private const val FIELD_COUNT = 12
    /** Records written before T59 have no `flowOrder` field. */
    private const val FIELD_COUNT_T38B = 9
    /** T34 records have no speech position. */
    private const val FIELD_COUNT_T59 = 10
    private const val FIELD_COUNT_T34_PAGE = 11

    fun encode(state: ReadingState): String = listOf(
        VERSION.toString(),
        state.pageIndex.toString(),
        state.pageFraction.toString(),
        state.pageCount.toString(),
        state.zoom.toString(),
        state.mode.name,
        state.orientation.name,
        encodeField(state.lastToolId.orEmpty()),
        state.updatedAt.toString(),
        state.flowOrder.toString(),
        state.speechPage.toString(),
        state.speechParagraph.toString()
    ).joinToString(SEPARATOR.toString())

    fun decode(raw: String?): ReadingState? {
        if (raw.isNullOrBlank()) return null
        val fields = raw.split(SEPARATOR, limit = FIELD_COUNT)
        if (fields.size !in FIELD_COUNT_T38B..FIELD_COUNT) return null
        if (fields[0].toIntOrNull() != VERSION) return null
        val page = fields[1].toIntOrNull() ?: return null
        val fraction = fields[2].toFloatOrNull() ?: return null
        val count = fields[3].toIntOrNull() ?: return null
        val zoom = fields[4].toFloatOrNull() ?: return null
        val mode = enumOrNull<ReadingMode>(fields[5]) ?: return null
        val orientation = enumOrNull<ReadingOrientation>(fields[6]) ?: return null
        val tool = decodeField(fields[7]).orEmpty().ifBlank { null }
        val updated = fields[8].toLongOrNull() ?: 0L
        val flowOrder = if (fields.size > FIELD_COUNT_T38B) {
            fields[9].toIntOrNull()?.coerceAtLeast(0) ?: 0
        } else {
            0
        }
        val speechPage = if (fields.size > FIELD_COUNT_T59) {
            fields[10].toIntOrNull()?.coerceAtLeast(0) ?: 0
        } else {
            0
        }
        val speechParagraph = if (fields.size > FIELD_COUNT_T34_PAGE) {
            fields[11].toIntOrNull()?.coerceAtLeast(0) ?: 0
        } else {
            0
        }
        return ReadingState(
            pageIndex = page.coerceAtLeast(0),
            pageFraction = fraction.coerceIn(0f, 1f),
            pageCount = count.coerceAtLeast(0),
            zoom = zoom.coerceIn(ReadingState.MIN_ZOOM, ReadingState.MAX_ZOOM),
            mode = mode,
            orientation = orientation,
            lastToolId = tool,
            flowOrder = flowOrder,
            speechPage = speechPage,
            speechParagraph = speechParagraph,
            updatedAt = updated
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    private fun encodeField(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(UTF_8))

    private fun decodeField(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), UTF_8)
    }.getOrNull()
}
