package com.pdfpaper.app.data

import com.pdfpaper.app.ui.theme.CodePalettes
import com.pdfpaper.app.ui.theme.AppearanceChoice
import com.pdfpaper.app.ui.theme.PaletteRotation

/**
 * The stored appearance settings (Settings → Appearance). Everything stays on
 * the device in the app-private settings file; nothing is ever uploaded.
 */
data class AppearancePrefs(
    val choice: AppearanceChoice = AppearanceChoice.FOLLOW_SYSTEM,
    /** Whether the Code style palette rotates on every cold start. */
    val changePaletteEachLaunch: Boolean = true,
    val rotation: PaletteRotation = PaletteRotation.IN_ORDER,
    /** The palette the reader picked, used while [changePaletteEachLaunch] is off. */
    val paletteIndex: Int = 0,
    /** The palette the last launch landed on; -1 before the first one. */
    val lastPaletteIndex: Int = CodePalettes.NO_LAST_INDEX
)

/**
 * Reads the raw stored values. An unknown name or a stale index falls back to
 * the default rather than crashing, so a hand-edited settings file can only
 * change the look, never break the app.
 */
object AppearancePrefCodec {

    fun decode(
        choice: String?,
        changeEachLaunch: Boolean?,
        rotation: String?,
        paletteIndex: Int,
        lastPaletteIndex: Int
    ): AppearancePrefs = AppearancePrefs(
        choice = AppearanceChoice.entries.firstOrNull { it.name == choice }
            ?: AppearanceChoice.FOLLOW_SYSTEM,
        changePaletteEachLaunch = changeEachLaunch ?: true,
        rotation = PaletteRotation.entries.firstOrNull { it.name == rotation }
            ?: PaletteRotation.IN_ORDER,
        paletteIndex = paletteIndex.coerceIn(0, CodePalettes.ALL.size - 1),
        lastPaletteIndex = lastPaletteIndex.coerceIn(
            CodePalettes.NO_LAST_INDEX,
            CodePalettes.ALL.size - 1
        )
    )
}
