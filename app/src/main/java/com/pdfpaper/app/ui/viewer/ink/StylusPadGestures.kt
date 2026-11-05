package com.pdfpaper.app.ui.viewer.ink

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * A pen-first drawing gesture for a signature pad or any fixed canvas (T54b).
 * It applies the same palm-rejection and stylus rules as the ink layer so the
 * pen behaves identically wherever it is used.
 *
 * - A finger contact is accepted only when [finger] is true; otherwise it is
 *   left to the parent (so a pad inside a scrollable sheet never traps a scroll).
 * - A palm (finger while the pen is down / just lifted) never draws.
 * - [onPoint] receives normalised 0..1 fractions; [onEnd] tells the caller
 *   whether the gesture was a tap (true) or a drag (false).
 * - ACTION_CANCEL delivers no point and ends the gesture, so a stroke cannot
 *   be left half-written when the system takes the touch away.
 */
fun Modifier.stylusPadGestures(
    key: Any?,
    finger: Boolean,
    onPoint: (fractionX: Float, fractionY: Float, isPen: Boolean) -> Unit,
    onEnd: (tap: Boolean) -> Unit,
    tapThresholdPx: Float = 8f
): Modifier = pointerInput(key, finger) {
    var lastPenTime = 0L
    var penActive = false

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val isPen = down.type == PointerType.Stylus || down.type == PointerType.Eraser
        if (isPen) {
            penActive = true
            lastPenTime = down.uptimeMillis
        }
        val palm = StylusInputPolicy.isPalm(
            toolTypeOf(down.type),
            down.uptimeMillis,
            penActive,
            lastPenTime
        )
        if (palm) return@awaitEachGesture
        if (!isPen && !finger) return@awaitEachGesture

        fun fraction(offset: Offset): Pair<Float, Float> =
            offset.x / size.width.coerceAtLeast(1) to offset.y / size.height.coerceAtLeast(1)

        val (sx, sy) = fraction(down.position)
        onPoint(sx, sy, isPen)
        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                // Up, or a cancel delivered as a lifted contact with no point.
                if (!change.isConsumed) {
                    val (ex, ey) = fraction(change.position)
                    onPoint(ex, ey, isPen)
                }
                break
            }
            if ((change.position - down.position).getDistance() > tapThresholdPx) {
                moved = true
            }
            val (px, py) = fraction(change.position)
            change.consume()
            onPoint(px, py, isPen)
        }
        penActive = false
        if (isPen) lastPenTime = down.uptimeMillis
        onEnd(!moved)
    }
}

/**
 * A stylus-button tap on a fixed canvas: the pen button mapped to "erase"
 * removes the last stroke, and a plain stylus tap does nothing. Non-stylus
 * contacts fall through so the parent keeps its own taps.
 */
fun Modifier.stylusButtonTapGestures(
    key: Any?,
    onEraseLast: () -> Unit
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val isPen = down.type == PointerType.Stylus || down.type == PointerType.Eraser
        if (!isPen) return@awaitEachGesture
        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (!moved) onEraseLast()
                break
            }
            if ((change.position - down.position).getDistance() > 8f) {
                moved = true
            }
        }
    }
}

/** Maps a Compose pointer type back to the Android tool type the policy expects. */
private fun toolTypeOf(type: PointerType): Int = when (type) {
    PointerType.Stylus -> android.view.MotionEvent.TOOL_TYPE_STYLUS
    PointerType.Eraser -> android.view.MotionEvent.TOOL_TYPE_ERASER
    PointerType.Mouse -> android.view.MotionEvent.TOOL_TYPE_MOUSE
    PointerType.Touch -> android.view.MotionEvent.TOOL_TYPE_FINGER
    else -> android.view.MotionEvent.TOOL_TYPE_UNKNOWN
}
