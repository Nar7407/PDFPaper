package com.pdfpaper.app.ui.viewer.ink

import android.view.MotionEvent

/**
 * T54 input policy for the ink gesture filter. Kept as pure integer/long logic
 * so the palm and stylus rules are unit-tested without an Android runtime; the
 * viewer's `pointerInteropFilter` only supplies the values from `MotionEvent`.
 */
internal object InkInputPolicy {

    /** A finger this soon after the pen is treated as the heel of the hand. */
    const val PALM_TIMEOUT_MS = 600L

    fun isStylus(toolType: Int): Boolean =
        toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER

    /**
     * True for a non-stylus contact while the pen is down or was lifted within
     * [PALM_TIMEOUT_MS]. Such a contact must never scroll, zoom or draw.
     */
    fun isPalm(
        toolType: Int,
        eventTime: Long,
        penActive: Boolean,
        lastPenTime: Long
    ): Boolean = !isStylus(toolType) &&
        (penActive || (lastPenTime > 0L && eventTime - lastPenTime < PALM_TIMEOUT_MS))

    /** Stylus-only mode passes finger events through to the viewer's scroll/zoom. */
    fun rejectedByStylusOnly(toolType: Int, stylusOnly: Boolean): Boolean =
        stylusOnly && !isStylus(toolType)

    /** The pen's primary button and the eraser end both erase. */
    fun isEraser(toolType: Int, buttonState: Int): Boolean =
        isStylus(toolType) &&
            ((buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                toolType == MotionEvent.TOOL_TYPE_ERASER)
}
