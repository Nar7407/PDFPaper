package com.pdfpaper.app.ui.viewer.ink

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T54 stylus input policy: only a pen draws in stylus-only mode, a finger near
 * the pen is a palm, and the pen button is an eraser.
 */
class InkInputPolicyTest {

    @Test
    fun onlyPenToolTypesCountAsStylus() {
        assertTrue(InkInputPolicy.isStylus(MotionEvent.TOOL_TYPE_STYLUS))
        assertTrue(InkInputPolicy.isStylus(MotionEvent.TOOL_TYPE_ERASER))
        assertFalse(InkInputPolicy.isStylus(MotionEvent.TOOL_TYPE_FINGER))
        assertFalse(InkInputPolicy.isStylus(MotionEvent.TOOL_TYPE_MOUSE))
        assertFalse(InkInputPolicy.isStylus(MotionEvent.TOOL_TYPE_UNKNOWN))
    }

    @Test
    fun aFingerIsAPalmWhileThePenIsDown() {
        assertTrue(
            InkInputPolicy.isPalm(
                MotionEvent.TOOL_TYPE_FINGER,
                eventTime = 10_000L,
                penActive = true,
                lastPenTime = 10_000L
            )
        )
        assertFalse(
            InkInputPolicy.isPalm(
                MotionEvent.TOOL_TYPE_STYLUS,
                eventTime = 10_000L,
                penActive = true,
                lastPenTime = 10_000L
            )
        )
    }

    @Test
    fun aFingerIsAPalmForSixHundredMillisecondsAfterThePenLifts() {
        assertTrue(
            InkInputPolicy.isPalm(
                MotionEvent.TOOL_TYPE_FINGER,
                eventTime = 10_000L + InkInputPolicy.PALM_TIMEOUT_MS - 1,
                penActive = false,
                lastPenTime = 10_000L
            )
        )
        assertFalse(
            InkInputPolicy.isPalm(
                MotionEvent.TOOL_TYPE_FINGER,
                eventTime = 10_000L + InkInputPolicy.PALM_TIMEOUT_MS,
                penActive = false,
                lastPenTime = 10_000L
            )
        )
    }

    @Test
    fun aFingerIsNotAPalmWhenThePenWasNeverUsed() {
        assertFalse(
            InkInputPolicy.isPalm(
                MotionEvent.TOOL_TYPE_FINGER,
                eventTime = 100L,
                penActive = false,
                lastPenTime = 0L
            )
        )
    }

    @Test
    fun stylusOnlyRejectsFingersButNotThePen() {
        assertTrue(InkInputPolicy.rejectedByStylusOnly(MotionEvent.TOOL_TYPE_FINGER, true))
        assertTrue(InkInputPolicy.rejectedByStylusOnly(MotionEvent.TOOL_TYPE_MOUSE, true))
        assertFalse(InkInputPolicy.rejectedByStylusOnly(MotionEvent.TOOL_TYPE_STYLUS, true))
        assertFalse(InkInputPolicy.rejectedByStylusOnly(MotionEvent.TOOL_TYPE_ERASER, true))
        assertFalse(InkInputPolicy.rejectedByStylusOnly(MotionEvent.TOOL_TYPE_FINGER, false))
    }

    @Test
    fun thePenButtonAndTheEraserEndBothErase() {
        assertTrue(
            InkInputPolicy.isEraser(
                MotionEvent.TOOL_TYPE_STYLUS,
                MotionEvent.BUTTON_STYLUS_PRIMARY
            )
        )
        assertTrue(
            InkInputPolicy.isEraser(
                MotionEvent.TOOL_TYPE_ERASER,
                buttonState = 0
            )
        )
        assertFalse(
            InkInputPolicy.isEraser(
                MotionEvent.TOOL_TYPE_STYLUS,
                buttonState = 0
            )
        )
        assertFalse(
            InkInputPolicy.isEraser(
                MotionEvent.TOOL_TYPE_FINGER,
                MotionEvent.BUTTON_STYLUS_PRIMARY
            )
        )
    }
}
