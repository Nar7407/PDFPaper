package com.pdfpaper.app.ui.viewer.turn

import android.view.KeyEvent

/**
 * Task T58: one-handed reading. Everything here is free of Android types so
 * the zone geometry, the direction rules and the scroll maths are unit-tested
 * on the JVM; the viewer only supplies screen sizes and reads the settings.
 */

/** Which part of the page a tap landed in. Physical, not mirrored. */
enum class TurnZone {
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
    CENTER
}

/** The way a turn moves through the document. */
enum class TurnDirection {
    FORWARD,
    BACKWARD
}

/** What an edge tap does when it fires. */
enum class TurnAction(val label: String) {
    /** Move exactly one page. */
    PAGE("Turn one page"),

    /** Move one screen, keeping a few lines of context. */
    SCREEN("Scroll one screen")
}

/** How pages are laid out while reading. */
enum class TurnMode(val label: String, val description: String) {
    /** One long scroll, the current behaviour. */
    CONTINUOUS("Continuous scroll", "One long scroll, like a web page."),

    /** One page per snap, still vertical. */
    PAGE_SNAP("Page snap", "Stops on whole pages as you scroll."),

    /** One page per screen, paging sideways. */
    HORIZONTAL("Horizontal paging", "One page per screen, turned sideways.")
}

/** Whether a turn is animated. */
enum class TurnAnimation {
    ANIMATED,
    INSTANT
}

/**
 * The reading preferences of one viewer session (T58). Volume keys are OFF by
 * default because claiming them takes the volume control away from the user
 * without asking; edge taps are ON because they cost nothing until tapped.
 */
data class TurnSettings(
    val volumeKeys: Boolean = DEFAULT_VOLUME_KEYS,
    val invertVolume: Boolean = false,
    val edgeTaps: Boolean = DEFAULT_EDGE_TAPS,
    val leftHanded: Boolean = false,
    val verticalZones: Boolean = false,
    val action: TurnAction = TurnAction.PAGE,
    val zonePercent: Int = DEFAULT_ZONE_PERCENT,
    val overlapLines: Int = DEFAULT_OVERLAP_LINES,
    val mode: TurnMode = TurnMode.CONTINUOUS,
    val animation: TurnAnimation = TurnAnimation.ANIMATED,
    val hapticTick: Boolean = false
) {
    /** The clamped zone width, so a bad stored value cannot eat the page. */
    val zone: Float
        get() = TurnZones.zoneFraction(zonePercent)

    /** A copy with every stored value brought back into its allowed range. */
    fun normalised(): TurnSettings = copy(
        zonePercent = zonePercent.coerceIn(MIN_ZONE_PERCENT, MAX_ZONE_PERCENT),
        overlapLines = overlapLines.coerceIn(MIN_OVERLAP_LINES, MAX_OVERLAP_LINES)
    )

    companion object {
        const val DEFAULT_VOLUME_KEYS = false
        const val DEFAULT_EDGE_TAPS = true
        const val DEFAULT_ZONE_PERCENT = 22
        const val MIN_ZONE_PERCENT = 10
        const val MAX_ZONE_PERCENT = 40
        const val DEFAULT_OVERLAP_LINES = 2
        const val MIN_OVERLAP_LINES = 0
        const val MAX_OVERLAP_LINES = 8

        /** The library default, also used when a stored value is unreadable. */
        val DEFAULT = TurnSettings()
    }
}

/**
 * Where a tap landed (T58).
 *
 * The horizontal zones always win: a tap in the left 22 % is "back" even when
 * it is also inside the top 22 %, because the left edge is what a thumb finds
 * without looking. Top and bottom are opt-in and only exist in the middle
 * column, so they never shadow a corner.
 */
object TurnZones {

    fun zoneFraction(percent: Int): Float =
        percent.coerceIn(TurnSettings.MIN_ZONE_PERCENT, TurnSettings.MAX_ZONE_PERCENT) / 100f

    /**
     * The physical zone of a tap. [width] and [height] are the size of the
     * page surface in pixels; a zero or negative size (before the first
     * layout pass) is always [TurnZone.CENTER].
     */
    fun zoneAt(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        settings: TurnSettings
    ): TurnZone {
        if (width <= 0f || height <= 0f) return TurnZone.CENTER
        val zone = settings.zone
        val fx = (x / width).coerceIn(0f, 1f)
        val fy = (y / height).coerceIn(0f, 1f)
        return when {
            fx < zone -> TurnZone.LEFT
            fx > 1f - zone -> TurnZone.RIGHT
            settings.verticalZones && fy < zone -> TurnZone.TOP
            settings.verticalZones && fy > 1f - zone -> TurnZone.BOTTOM
            else -> TurnZone.CENTER
        }
    }

    /**
     * The direction a zone turns, or null for the centre (which toggles the
     * toolbar instead). A left-handed reader swaps the two vertical edges
     * only: the top and bottom zones keep their meaning because they are not
     * reached by the thumb.
     */
    fun directionFor(zone: TurnZone, settings: TurnSettings): TurnDirection? {
        val mirrored = settings.leftHanded
        return when (zone) {
            TurnZone.LEFT -> if (mirrored) TurnDirection.FORWARD else TurnDirection.BACKWARD
            TurnZone.RIGHT -> if (mirrored) TurnDirection.BACKWARD else TurnDirection.FORWARD
            TurnZone.TOP -> TurnDirection.BACKWARD
            TurnZone.BOTTOM -> TurnDirection.FORWARD
            TurnZone.CENTER -> null
        }
    }
}

/**
 * The volume keys while reading (T58). Volume DOWN goes forward and volume UP
 * goes back, which is the convention of every phone reader; [TurnSettings.
 * invertVolume] swaps the pair for a reader who expects the opposite.
 *
 * This deliberately differs from presentation mode, where volume UP is "next":
 * a presenter is driving a deck, a reader is holding a book. Both sides read
 * the same [com.pdfpaper.app.input.KeyMap] for everything except the volume
 * pair, so a clicker behaves identically in either mode.
 */
object TurnKeys {
    fun handles(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP

    fun direction(keyCode: Int, settings: TurnSettings): TurnDirection? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN ->
            if (settings.invertVolume) TurnDirection.BACKWARD else TurnDirection.FORWARD
        KeyEvent.KEYCODE_VOLUME_UP ->
            if (settings.invertVolume) TurnDirection.FORWARD else TurnDirection.BACKWARD
        else -> null
    }
}

/**
 * Repeat handling for a held key (T58). Android's own repeat interval is far
 * faster than a page can be read, so a held volume key is throttled to
 * [INTERVAL_MS]. A key that only reports `repeatCount == 0` (a clicker, or a
 * key that is pressed once) is always accepted.
 */
object TurnRepeat {
    const val INTERVAL_MS = 200L

    fun accepts(repeatCount: Int, eventTime: Long, lastEventTime: Long): Boolean =
        repeatCount <= 0 || eventTime - lastEventTime >= INTERVAL_MS
}

/**
 * How far a "scroll one screen" turn moves (T58). The overlap is expressed in
 * text lines so it means the same thing on a phone and on a tablet, and the
 * step never falls below a quarter of the screen: with a tiny viewport or a
 * huge overlap setting the user must still move forward.
 */
object TurnScroll {
    /** An average A4 page of body text, used to turn "lines" into pixels. */
    const val LINES_PER_PAGE = 48

    fun overlapPx(pageHeightPx: Float, lines: Int): Float {
        if (pageHeightPx <= 0f) return 0f
        val clamped = lines.coerceIn(TurnSettings.MIN_OVERLAP_LINES, TurnSettings.MAX_OVERLAP_LINES)
        return pageHeightPx / LINES_PER_PAGE * clamped
    }

    fun stepPx(viewportHeightPx: Float, pageHeightPx: Float, lines: Int): Float {
        if (viewportHeightPx <= 0f) return 0f
        val step = viewportHeightPx - overlapPx(pageHeightPx, lines)
        return step.coerceAtLeast(viewportHeightPx * 0.25f)
    }
}

/**
 * Which page the reader is on (T58). A page is current when it covers the most
 * of the viewport: the last page of a short document cannot fill the screen, so
 * "top item" would keep naming the page above it and a page indicator would
 * never reach the end. The pairs are (page index, visible height in pixels) and
 * a list that has not been laid out yet is page 1.
 */
object TurnPosition {
    fun currentPage(visible: List<Pair<Int, Int>>): Int =
        visible.maxByOrNull { it.second }?.first ?: 0
}

/**
 * Animation choice (T58/T63). The system "remove animations" accessibility
 * setting and the app-wide reduce-motion flag both win over the per-document
 * preference: a reader who asked for no motion must not get a page slide from
 * us. Reduce motion also pins the reading mode to page snap, so a turn always
 * lands on a whole page instead of a partial scroll that has to be animated.
 */
object TurnMotion {
    fun effective(
        preference: TurnAnimation,
        systemRemovesAnimations: Boolean,
        reduceMotion: Boolean = false
    ): TurnAnimation =
        if (preference == TurnAnimation.INSTANT || systemRemovesAnimations || reduceMotion) {
            TurnAnimation.INSTANT
        } else {
            TurnAnimation.ANIMATED
        }

    /** The mode a turn actually uses; reduce motion forces [TurnMode.PAGE_SNAP]. */
    fun effectiveMode(mode: TurnMode, reduceMotion: Boolean): TurnMode =
        if (reduceMotion) TurnMode.PAGE_SNAP else mode
}

/**
 * When page turning is allowed (T58). Annotating, drawing, selecting text and
 * filling a form all own the finger, so turning steps aside rather than
 * competing for the same tap.
 */
object TurnPolicy {
    fun enabled(
        settings: TurnSettings,
        editing: Boolean,
        inkMode: Boolean,
        selectionActive: Boolean,
        formFilling: Boolean,
        placingMark: Boolean
    ): Boolean {
        if (!settings.edgeTaps && !settings.volumeKeys) return false
        return !(editing || inkMode || selectionActive || formFilling || placingMark)
    }

    /**
     * The edge layer is what makes a centre tap toggle the toolbars. It is
     * only installed when the reader actually wants edge taps and nothing
     * else owns the gesture stream.
     */
    fun edgeLayerActive(
        settings: TurnSettings,
        editing: Boolean,
        inkMode: Boolean,
        selectionActive: Boolean,
        formFilling: Boolean,
        placingMark: Boolean
    ): Boolean = settings.edgeTaps && !(editing || inkMode || selectionActive || formFilling || placingMark)
}
