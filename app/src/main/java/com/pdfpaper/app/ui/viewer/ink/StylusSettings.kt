package com.pdfpaper.app.ui.viewer.ink

/**
 * The reader-wide stylus preferences (T54b). They are stored once and shared by
 * every pen-capable tool, so a pen behaves the same in the ink layer, the
 * signature pad and the marking tools.
 *
 * [mode] is the default:
 *  - [StylusInputPolicy.StylusMode.OFF]  the viewer behaves as before: a finger
 *    scrolls and zooms, the pen draws only inside a tool that accepts it.
 *  - [StylusInputPolicy.StylusMode.AUTO] a tool that accepts a pen enters pen
 *    mode on the first real pen contact and lets fingers keep scrolling
 *    afterwards. This is the sensible default for a signature pad.
 *  - [StylusInputPolicy.StylusMode.ON]  pen-only: fingers always scroll/zoom and
 *    never draw, even inside a pen tool.
 *
 * [primary]/[secondary] map the pen's side buttons. The default erases with the
 * primary button because that is what a reader expects when they turn the pen
 * over or press the barrel button, and it matches the T54 rule.
 */
data class StylusSettings(
    val mode: StylusInputPolicy.StylusMode = StylusInputPolicy.StylusMode.AUTO,
    val primary: StylusInputPolicy.ButtonAction = StylusInputPolicy.ButtonAction.ERASER,
    val secondary: StylusInputPolicy.ButtonAction = StylusInputPolicy.ButtonAction.NONE
) {
    val buttonMap: StylusInputPolicy.ButtonMap
        get() = StylusInputPolicy.ButtonMap(primary = primary, secondary = secondary)

    /** Pen-only, i.e. fingers never draw. */
    val penOnly: Boolean get() = mode == StylusInputPolicy.StylusMode.ON

    fun withMode(mode: StylusInputPolicy.StylusMode): StylusSettings = copy(mode = mode)

    companion object {
        val DEFAULT = StylusSettings()

        /** Labels for the mode chip row, in display order. */
        val modeOptions: List<Pair<StylusInputPolicy.StylusMode, String>> = listOf(
            StylusInputPolicy.StylusMode.AUTO to "Auto",
            StylusInputPolicy.StylusMode.ON to "Pen only",
            StylusInputPolicy.StylusMode.OFF to "Off"
        )

        /** Short description under the chips. */
        fun describe(mode: StylusInputPolicy.StylusMode): String = when (mode) {
            StylusInputPolicy.StylusMode.AUTO ->
                "The pen draws as soon as you touch the screen; a finger still scrolls."
            StylusInputPolicy.StylusMode.ON ->
                "Only the pen draws. A finger always scrolls and zooms."
            StylusInputPolicy.StylusMode.OFF ->
                "The pen draws only inside an ink or marking tool."
        }

        fun buttonLabel(action: StylusInputPolicy.ButtonAction): String = when (action) {
            StylusInputPolicy.ButtonAction.ERASER -> "Erase"
            StylusInputPolicy.ButtonAction.TOOL_SWITCH -> "Switch tool"
            StylusInputPolicy.ButtonAction.DRAW -> "Draw"
            StylusInputPolicy.ButtonAction.NONE -> "Do nothing"
        }
    }
}
