package com.pdfpaper.app.ui.viewer

import kotlin.math.roundToInt

/** A reading position saved before jumping through a link (T61). */
data class ReadingPosition(
    val pageIndex: Int,
    val offsetPx: Int,
    val horizontalPx: Int = 0
)

private const val MAX_BACK_STACK = 20

/** Pushes [position] and keeps the back stack bounded. */
fun pushPosition(
    stack: List<ReadingPosition>,
    position: ReadingPosition
): List<ReadingPosition> = (stack + position).takeLast(MAX_BACK_STACK)

/** Removes and returns the newest position; null when the stack is empty. */
fun popPosition(stack: List<ReadingPosition>): Pair<ReadingPosition?, List<ReadingPosition>> =
    if (stack.isEmpty()) null to stack else stack.last() to stack.dropLast(1)

/**
 * Scroll offset that puts the target preview roughly a third of the way down
 * the viewport. The result is clamped to zero so the first page cannot
 * overscroll.
 */
fun linkScrollOffset(
    pageFractionTop: Double,
    itemHeightPx: Int,
    viewportHeightPx: Int
): Int {
    val fraction = pageFractionTop.coerceIn(0.0, 1.0)
    val target = fraction * itemHeightPx.coerceAtLeast(1)
    val anchor = viewportHeightPx.coerceAtLeast(1) * 0.32
    return (target - anchor).roundToInt().coerceAtLeast(0)
}
