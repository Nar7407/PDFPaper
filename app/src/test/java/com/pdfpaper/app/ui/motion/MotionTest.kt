package com.pdfpaper.app.ui.motion

import androidx.compose.foundation.gestures.ScrollScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Task T63: reduce motion must remove the coast, not just shorten it. */
class MotionTest {

    @Test
    fun theNoFlingBehaviourDoesNotCoast() = runBlocking {
        var moved = 0f
        val scope = object : ScrollScope {
            override fun scrollBy(pixels: Float): Float {
                moved += pixels
                return pixels
            }
        }
        val consumed = with(NoFling) { scope.performFling(12_000f) }
        assertEquals(0f, consumed, 0f)
        assertEquals("a fling must not move the page", 0f, moved, 0f)
    }
}
