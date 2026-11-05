package com.pdfpaper.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T61: link navigation maths and the exact-position back stack. */
class LinkNavigationTest {

    @Test
    fun `scroll offset puts the target near the top third`() {
        val offset = linkScrollOffset(
            pageFractionTop = 0.5,
            itemHeightPx = 1000,
            viewportHeightPx = 500
        )
        assertEquals(340, offset)
    }

    @Test
    fun `scroll offset clamps at the top and bottom`() {
        assertEquals(0, linkScrollOffset(0.0, 1000, 600))
        assertEquals(0, linkScrollOffset(-1.0, 1000, 600))
        assertEquals(840, linkScrollOffset(1.0, 1000, 500))
    }

    @Test
    fun `back stack restores positions in reverse order`() {
        var stack = emptyList<ReadingPosition>()
        stack = pushPosition(stack, ReadingPosition(3, 120, 8))
        stack = pushPosition(stack, ReadingPosition(9, 40, 16))
        assertEquals(2, stack.size)

        val (second, afterSecond) = popPosition(stack)
        assertEquals(ReadingPosition(9, 40, 16), second)
        assertEquals(1, afterSecond.size)

        val (first, afterFirst) = popPosition(afterSecond)
        assertEquals(ReadingPosition(3, 120, 8), first)
        assertTrue(afterFirst.isEmpty())

        val (none, empty) = popPosition(afterFirst)
        assertNull(none)
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `back stack is bounded`() {
        var stack = emptyList<ReadingPosition>()
        repeat(40) { stack = pushPosition(stack, ReadingPosition(it, it, it)) }
        assertEquals(20, stack.size)
        assertEquals(20, stack.first().pageIndex)
        assertEquals(39, stack.last().pageIndex)
    }
}
