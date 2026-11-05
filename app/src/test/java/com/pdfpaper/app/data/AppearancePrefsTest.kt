package com.pdfpaper.app.data

import com.pdfpaper.app.ui.theme.AppearanceChoice
import com.pdfpaper.app.ui.theme.CodePaletteRotation
import com.pdfpaper.app.ui.theme.CodePalettes
import com.pdfpaper.app.ui.theme.PaletteRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored appearance settings. The codec is what AppSettings reads and
 * writes, so a corrupt or hand-edited settings file can only fall back to the
 * defaults, never break the shell. The file stays on the device.
 */
class AppearancePrefsTest {

    private fun decode(
        choice: String? = null,
        changeEachLaunch: Boolean? = null,
        rotation: String? = null,
        paletteIndex: Int = 0,
        lastPaletteIndex: Int = CodePalettes.NO_LAST_INDEX
    ) = AppearancePrefCodec.decode(
        choice = choice,
        changeEachLaunch = changeEachLaunch,
        rotation = rotation,
        paletteIndex = paletteIndex,
        lastPaletteIndex = lastPaletteIndex
    )

    @Test
    fun aFreshInstallFollowsTheSystemAndRotatesInOrder() {
        val prefs = decode()
        assertEquals(AppearanceChoice.FOLLOW_SYSTEM, prefs.choice)
        assertTrue(prefs.changePaletteEachLaunch)
        assertEquals(PaletteRotation.IN_ORDER, prefs.rotation)
        assertEquals(0, prefs.paletteIndex)
        assertEquals(CodePalettes.NO_LAST_INDEX, prefs.lastPaletteIndex)
    }

    @Test
    fun everyChoiceAndRotationNameRoundTrips() {
        for (choice in AppearanceChoice.entries) {
            for (rotation in PaletteRotation.entries) {
                val prefs = decode(
                    choice = choice.name,
                    changeEachLaunch = false,
                    rotation = rotation.name,
                    paletteIndex = 4,
                    lastPaletteIndex = 2
                )
                assertEquals(choice, prefs.choice)
                assertEquals(rotation, prefs.rotation)
                assertFalse(prefs.changePaletteEachLaunch)
                assertEquals(4, prefs.paletteIndex)
                assertEquals(2, prefs.lastPaletteIndex)
            }
        }
    }

    @Test
    fun anUnknownChoiceFallsBackToFollowSystem() {
        assertEquals(AppearanceChoice.FOLLOW_SYSTEM, decode(choice = "PURPLE_RAIN").choice)
        assertEquals(AppearanceChoice.FOLLOW_SYSTEM, decode(choice = "").choice)
    }

    @Test
    fun anUnknownRotationFallsBackToInOrder() {
        assertEquals(PaletteRotation.IN_ORDER, decode(rotation = "randomish").rotation)
    }

    @Test
    fun aStalePaletteIndexIsClampedIntoTheList() {
        assertEquals(CodePalettes.ALL.size - 1, decode(paletteIndex = 99).paletteIndex)
        assertEquals(0, decode(paletteIndex = -7).paletteIndex)
        assertEquals(2, decode(paletteIndex = 2).paletteIndex)
    }

    @Test
    fun aStaleLastIndexIsClampedButRememberedAsUnused() {
        assertEquals(
            CodePalettes.ALL.size - 1,
            decode(lastPaletteIndex = 42).lastPaletteIndex
        )
        assertEquals(CodePalettes.NO_LAST_INDEX, decode(lastPaletteIndex = -42).lastPaletteIndex)
        assertEquals(3, decode(lastPaletteIndex = 3).lastPaletteIndex)
    }

    @Test
    fun theDefaultIsTheSameObjectTheThemeReads() {
        val prefs = decode()
        // The rotation rule must see "never used" and start at the first palette.
        assertEquals(0, CodePaletteRotation.nextInOrder(
            CodePalettes.ALL.size,
            prefs.lastPaletteIndex
        ))
    }
}
