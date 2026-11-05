package com.pdfpaper.app.ui.viewer.ink

import android.view.MotionEvent

/**
 * T54b stylus input policy: a single, pure rule set that decides how every
 * contact is handled while the pen is in range. It is used by the ink layer
 * (T54), the signature pad (T26), and the tool layers that accept pen taps /
 * drags (redaction marks, notes, free text, shapes). Kept as pure int/long
 * logic so it can be unit-tested without an Android runtime; the gesture
 * filters only supply [MotionEvent] values.
 *
 * Design:
 *  - A finger near an active pen is the heel of the hand and must never
 *    scroll, zoom or draw (palm rejection, T54).
 *  - [stylusMode] controls the default:
 *      OFF  = viewer behaviour (fingers scroll/zoom, pen draws only inside
 *             dedicated ink/shape tool layers).
 *      AUTO = default for a tool that supports pen (e.g. the signature pad);
 *             the tool enters pen mode on the first real pen contact and falls
 *             back to finger afterwards.
 *      ON   = the tool is pen-only; fingers pass through to the viewer.
 *  - The pen's primary button is an eraser unless the caller maps it
 *    differently ([ButtonAction]).
 */
object StylusInputPolicy {

    /** Finger touches are treated as the heel of a palm this long after the pen lifts. */
    const val PALM_TIMEOUT_MS = 600L

    enum class ButtonAction { DRAW, ERASER, TOOL_SWITCH, NONE }

    /** Default button mapping: primary button erases, secondary button does nothing. */
    val DEFAULT_BUTTON_MAP = ButtonMap(
        primary = ButtonAction.ERASER,
        secondary = ButtonAction.NONE
    )

    data class ButtonMap(
        val primary: ButtonAction = ButtonAction.DRAW,
        val secondary: ButtonAction = ButtonAction.NONE
    )

    /** What a gesture filter should do with an event. */
    data class InputFilterResult(
        /** The event produced a draw/extend point (pen sample, or a mark/shape sample). */
        val draw: Boolean = false,
        /** The event should be passed to the viewer for scrolling / zooming. */
        val scrollZoom: Boolean = false,
        /** Consume the event so nothing else (selection, turn) sees it. */
        val consume: Boolean = false
    ) {
        /** True when the tool took the event (it drew or consumed it). */
        fun handled(): Boolean = draw || consume
    }

    enum class StylusMode { OFF, AUTO, ON }

    /** Per-tool state needed by the policy: mode, the current drawing tool, whether
     *  a pen is down, and the last pen contact time. The tool layer owns this and
     *  mutates it only on ACTION_UP/ACTION_CANCEL or a button action, so the policy
     *  never owns state. */
    data class StylusContext(
        val mode: StylusMode = StylusMode.OFF,
        val tool: Int = TOOL_NONE,
        val penActive: Boolean = false,
        val lastPenTime: Long = 0L,
        val stylusOnly: Boolean = false,
        val buttonMap: ButtonMap = DEFAULT_BUTTON_MAP
    ) {
        companion object {
            const val TOOL_NONE = 0
            const val TOOL_DRAW = 1
            const val TOOL_ERASER = 2
            const val TOOL_SHAPE = 3
        }
    }

    /** True for a real pen (including the eraser tip). */
    fun isStylus(toolType: Int): Boolean =
        toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER

    /** True for a non-stylus contact while the pen is down or was lifted recently. */
    fun isPalm(
        toolType: Int,
        eventTime: Long,
        penActive: Boolean,
        lastPenTime: Long
    ): Boolean = !isStylus(toolType) &&
        (penActive || (lastPenTime > 0L && eventTime - lastPenTime < PALM_TIMEOUT_MS))

    /** The button-action currently in effect for a stylus event. */
    fun buttonAction(toolType: Int, buttonState: Int, map: ButtonMap): ButtonAction {
        if (!isStylus(toolType)) return ButtonAction.DRAW
        return when {
            (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 -> map.primary
            (buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 -> map.secondary
            toolType == MotionEvent.TOOL_TYPE_ERASER -> ButtonAction.ERASER
            else -> ButtonAction.DRAW
        }
    }

    /**
     * Decide how [event] is handled in [context]. Returns a result that tells the
     * caller to draw a sample, to pass the event through, or to consume it.
     */
    fun filter(
        toolType: Int,
        buttonState: Int,
        eventTime: Long,
        context: StylusContext
    ): InputFilterResult {
        val stylus = isStylus(toolType)
        when {
            // A palm contact (finger while the pen is down, or within the timeout)
            // never draws or scrolls; let the viewer handle it.
            isPalm(toolType, eventTime, context.penActive, context.lastPenTime) ->
                return InputFilterResult(scrollZoom = true)

            !stylus && context.stylusOnly ->
                // Pen-only tool: fingers pass through to the viewer.
                return InputFilterResult(scrollZoom = true)

            stylus -> {
                // Real pen contact renews the palm window.
                when (context.mode) {
                    StylusMode.OFF -> return InputFilterResult(scrollZoom = true)
                    StylusMode.AUTO, StylusMode.ON -> {
                        // The first pen contact in AUTO mode enters pen mode; the
                        // caller is expected to remember this so the first point is
                        // not a stray empty gesture.
                        when (buttonAction(toolType, buttonState, context.buttonMap)) {
                            ButtonAction.ERASER ->
                                return InputFilterResult(draw = true, consume = true)
                            ButtonAction.TOOL_SWITCH ->
                                return InputFilterResult(consume = true)
                            else ->
                                return InputFilterResult(draw = true, consume = true)
                        }
                    }
                }
            }
        }
        // Unknown contact (mouse / unknown): fall through, let the viewer decide.
        return InputFilterResult(scrollZoom = true)
    }
}
