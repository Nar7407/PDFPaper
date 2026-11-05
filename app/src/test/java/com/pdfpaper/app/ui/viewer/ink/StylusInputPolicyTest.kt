package com.pdfpaper.app.ui.viewer.ink

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T54b stylus input policy: the rule set behind all pen-capable tool layers.
 * Kept purely value-driven so every rule is unit-tested without an Android
 * runtime.
 *
 * Two outcomes matter to a caller: [StylusInputPolicy.InputFilterResult.handled]
 * (the tool took the event: draw or consume) and `scrollZoom` (pass it through
 * to the viewer). A pass-through is deliberately *not* handled.
 */
class StylusInputPolicyTest {

    private val penDown = StylusInputPolicy.StylusContext(
        mode = StylusInputPolicy.StylusMode.AUTO,
        penActive = true,
        lastPenTime = 10_000L
    )

    private fun filter(
        toolType: Int,
        buttonState: Int = 0,
        eventTime: Long = 10_000L,
        ctx: StylusInputPolicy.StylusContext
    ) = StylusInputPolicy.filter(toolType, buttonState, eventTime, ctx)

    @Test
    fun only_stylus_and_eraser_tool_types_are_pen() {
        assertTrue(StylusInputPolicy.isStylus(MotionEvent.TOOL_TYPE_STYLUS))
        assertTrue(StylusInputPolicy.isStylus(MotionEvent.TOOL_TYPE_ERASER))
        assertFalse(StylusInputPolicy.isStylus(MotionEvent.TOOL_TYPE_FINGER))
        assertFalse(StylusInputPolicy.isStylus(MotionEvent.TOOL_TYPE_MOUSE))
        assertFalse(StylusInputPolicy.isStylus(MotionEvent.TOOL_TYPE_UNKNOWN))
    }

    @Test
    fun finger_is_palm_while_pen_is_down() {
        val result = filter(MotionEvent.TOOL_TYPE_FINGER, ctx = penDown)
        assertTrue(result.scrollZoom)
        assertFalse(result.handled())
    }

    @Test
    fun pen_is_not_a_palm_while_pen_is_down() {
        assertTrue(filter(MotionEvent.TOOL_TYPE_STYLUS, ctx = penDown).handled())
    }

    @Test
    fun finger_is_palm_within_600ms_after_pen_lifts() {
        val ctx = StylusInputPolicy.StylusContext(
            mode = StylusInputPolicy.StylusMode.AUTO,
            lastPenTime = 10_000L
        )
        val inside = filter(
            MotionEvent.TOOL_TYPE_FINGER,
            eventTime = 10_000L + StylusInputPolicy.PALM_TIMEOUT_MS - 1,
            ctx = ctx
        )
        assertTrue(inside.scrollZoom)
        assertFalse(inside.handled())

        val after = filter(
            MotionEvent.TOOL_TYPE_FINGER,
            eventTime = 10_000L + StylusInputPolicy.PALM_TIMEOUT_MS,
            ctx = ctx
        )
        assertTrue(after.scrollZoom)
        assertFalse(after.handled())
    }

    @Test
    fun finger_is_not_palm_when_pen_was_never_used() {
        val ctx = StylusInputPolicy.StylusContext(mode = StylusInputPolicy.StylusMode.AUTO)
        val result = filter(MotionEvent.TOOL_TYPE_FINGER, eventTime = 100L, ctx = ctx)
        assertFalse(result.handled())
        assertTrue(result.scrollZoom)
    }

    @Test
    fun pen_only_rejects_fingers_and_mouse() {
        val ctx = StylusInputPolicy.StylusContext(
            mode = StylusInputPolicy.StylusMode.ON,
            stylusOnly = true
        )
        assertFalse(filter(MotionEvent.TOOL_TYPE_FINGER, ctx = ctx).handled())
        assertTrue(filter(MotionEvent.TOOL_TYPE_FINGER, ctx = ctx).scrollZoom)
        assertFalse(filter(MotionEvent.TOOL_TYPE_MOUSE, ctx = ctx).handled())
        assertTrue(filter(MotionEvent.TOOL_TYPE_STYLUS, ctx = ctx).draw)
        assertTrue(filter(MotionEvent.TOOL_TYPE_ERASER, ctx = ctx).draw)
    }

    @Test
    fun off_mode_lets_pen_and_finger_pass_through() {
        val ctx = StylusInputPolicy.StylusContext(mode = StylusInputPolicy.StylusMode.OFF)
        for (tool in listOf(
            MotionEvent.TOOL_TYPE_FINGER,
            MotionEvent.TOOL_TYPE_STYLUS,
            MotionEvent.TOOL_TYPE_MOUSE,
            MotionEvent.TOOL_TYPE_UNKNOWN
        )) {
            val result = filter(tool, eventTime = 100L, ctx = ctx)
            assertFalse("tool $tool must not be handled in OFF mode", result.handled())
            assertTrue(result.scrollZoom)
        }
    }

    @Test
    fun auto_mode_draws_on_pen_contact() {
        val ctx = StylusInputPolicy.StylusContext(mode = StylusInputPolicy.StylusMode.AUTO)
        val result = filter(MotionEvent.TOOL_TYPE_STYLUS, eventTime = 1_000L, ctx = ctx)
        assertTrue(result.draw)
        assertTrue(result.consume)
        assertTrue(result.handled())
    }

    @Test
    fun pen_primary_button_draws_by_default() {
        val ctx = StylusInputPolicy.StylusContext(mode = StylusInputPolicy.StylusMode.AUTO)
        val result = filter(
            MotionEvent.TOOL_TYPE_STYLUS,
            MotionEvent.BUTTON_STYLUS_PRIMARY,
            eventTime = 1_000L,
            ctx = ctx
        )
        assertTrue(result.draw)
        assertTrue(result.consume)
    }

    @Test
    fun eraser_tip_draws_without_button() {
        val ctx = StylusInputPolicy.StylusContext(mode = StylusInputPolicy.StylusMode.AUTO)
        val result = filter(MotionEvent.TOOL_TYPE_ERASER, eventTime = 1_000L, ctx = ctx)
        assertTrue(result.draw)
        assertTrue(result.consume)
    }

    @Test
    fun secondary_button_is_draw_by_default() {
        val ctx = StylusInputPolicy.StylusContext(mode = StylusInputPolicy.StylusMode.AUTO)
        val result = filter(
            MotionEvent.TOOL_TYPE_STYLUS,
            MotionEvent.BUTTON_STYLUS_SECONDARY,
            eventTime = 1_000L,
            ctx = ctx
        )
        assertTrue(result.draw)
    }

    @Test
    fun custom_button_map_is_applied() {
        val map = StylusInputPolicy.ButtonMap(
            primary = StylusInputPolicy.ButtonAction.TOOL_SWITCH,
            secondary = StylusInputPolicy.ButtonAction.ERASER
        )
        val ctx = StylusInputPolicy.StylusContext(
            mode = StylusInputPolicy.StylusMode.AUTO,
            buttonMap = map
        )
        val primary = filter(
            MotionEvent.TOOL_TYPE_STYLUS,
            MotionEvent.BUTTON_STYLUS_PRIMARY,
            eventTime = 1_000L,
            ctx = ctx
        )
        // Tool switch consumes the event but does not draw a point.
        assertTrue(primary.consume)
        assertFalse(primary.draw)

        val secondary = filter(
            MotionEvent.TOOL_TYPE_STYLUS,
            MotionEvent.BUTTON_STYLUS_SECONDARY,
            eventTime = 1_000L,
            ctx = ctx
        )
        assertTrue(secondary.draw)
    }

    @Test
    fun button_action_for_unknown_tool_is_draw() {
        assertEquals(
            StylusInputPolicy.ButtonAction.DRAW,
            StylusInputPolicy.buttonAction(
                MotionEvent.TOOL_TYPE_UNKNOWN,
                0,
                StylusInputPolicy.DEFAULT_BUTTON_MAP
            )
        )
    }

    @Test
    fun button_action_for_pen_without_buttons_is_draw() {
        assertEquals(
            StylusInputPolicy.ButtonAction.DRAW,
            StylusInputPolicy.buttonAction(
                MotionEvent.TOOL_TYPE_STYLUS,
                0,
                StylusInputPolicy.DEFAULT_BUTTON_MAP
            )
        )
    }

    @Test
    fun default_button_map_erases_with_the_primary_button() {
        assertEquals(
            StylusInputPolicy.ButtonAction.ERASER,
            StylusInputPolicy.buttonAction(
                MotionEvent.TOOL_TYPE_STYLUS,
                MotionEvent.BUTTON_STYLUS_PRIMARY,
                StylusInputPolicy.DEFAULT_BUTTON_MAP
            )
        )
        assertEquals(
            StylusInputPolicy.ButtonAction.NONE,
            StylusInputPolicy.buttonAction(
                MotionEvent.TOOL_TYPE_STYLUS,
                MotionEvent.BUTTON_STYLUS_SECONDARY,
                StylusInputPolicy.DEFAULT_BUTTON_MAP
            )
        )
    }

    @Test
    fun palm_window_is_renewed_while_the_pen_is_down() {
        val ctx = StylusInputPolicy.StylusContext(
            mode = StylusInputPolicy.StylusMode.AUTO,
            penActive = true,
            lastPenTime = 5_000L
        )
        val result = filter(
            MotionEvent.TOOL_TYPE_FINGER,
            eventTime = 5_000L + 10L,
            ctx = ctx
        )
        assertFalse(result.handled())
        assertTrue(result.scrollZoom)
    }
}
