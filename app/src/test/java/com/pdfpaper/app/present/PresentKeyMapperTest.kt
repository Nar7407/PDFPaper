package com.pdfpaper.app.present

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T53: the clicker/keyboard contract is pure logic, so it is tested on the JVM
 * rather than only on a device.
 */
class PresentKeyMapperTest {

    private fun command(keyCode: Int, repeat: Int = 0): PresentCommand? =
        PresentKeyMapper.command(keyCode, KeyEvent.ACTION_DOWN, repeat)

    @Test
    fun pageDownEnterAndArrowsAdvance() {
        assertEquals(PresentCommand.NEXT, command(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(PresentCommand.NEXT, command(KeyEvent.KEYCODE_SPACE))
        assertEquals(PresentCommand.NEXT, command(KeyEvent.KEYCODE_ENTER))
        assertEquals(PresentCommand.NEXT, command(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(PresentCommand.NEXT, command(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(PresentCommand.NEXT, command(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(PresentCommand.NEXT, command(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test
    fun pageUpAndArrowsGoBack() {
        assertEquals(PresentCommand.PREVIOUS, command(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(PresentCommand.PREVIOUS, command(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PresentCommand.PREVIOUS, command(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun volumeKeysActAsAClicker() {
        assertEquals(PresentCommand.NEXT, command(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(PresentCommand.PREVIOUS, command(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(PresentKeyMapper.handles(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(PresentKeyMapper.handles(KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun mediaKeysNavigateAndPlayPauseTogglesTheTimer() {
        assertEquals(PresentCommand.NEXT, command(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(PresentCommand.NEXT, command(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(PresentCommand.PREVIOUS, command(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(PresentCommand.PREVIOUS, command(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(PresentCommand.TOGGLE_TIMER, command(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(PresentCommand.TOGGLE_TIMER, command(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(PresentCommand.TOGGLE_TIMER, command(KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    @Test
    fun bAndPeriodToggleTheBlackScreen() {
        assertEquals(PresentCommand.TOGGLE_BLACK, command(KeyEvent.KEYCODE_B))
        assertEquals(PresentCommand.TOGGLE_BLACK, command(KeyEvent.KEYCODE_PERIOD))
    }

    @Test
    fun homeEndEscapeMenuTabAndPen() {
        assertEquals(PresentCommand.FIRST, command(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals(PresentCommand.LAST, command(KeyEvent.KEYCODE_MOVE_END))
        assertEquals(PresentCommand.EXIT, command(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(PresentCommand.TOGGLE_CONTROLS, command(KeyEvent.KEYCODE_MENU))
        assertEquals(PresentCommand.TOGGLE_CONTROLS, command(KeyEvent.KEYCODE_TAB))
        assertEquals(PresentCommand.TOGGLE_PEN, command(KeyEvent.KEYCODE_P))
    }

    @Test
    fun keyUpAndRepeatedPressesDoNotFireAgain() {
        assertNull(
            PresentKeyMapper.command(
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.ACTION_UP,
                0
            )
        )
        assertNull(command(KeyEvent.KEYCODE_PAGE_DOWN, repeat = 1))
        // The release must still be consumed so it does not leak to the system.
        assertTrue(PresentKeyMapper.handles(KeyEvent.KEYCODE_PAGE_DOWN))
    }

    @Test
    fun unrelatedKeysAreLeftToTheSystem() {
        assertNull(command(KeyEvent.KEYCODE_A))
        assertFalse(PresentKeyMapper.handles(KeyEvent.KEYCODE_A))
        assertNull(command(KeyEvent.KEYCODE_BACK))
        assertFalse(PresentKeyMapper.handles(KeyEvent.KEYCODE_BACK))
    }
}
