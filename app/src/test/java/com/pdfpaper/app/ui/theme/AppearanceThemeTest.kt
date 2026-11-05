package com.pdfpaper.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Settings → Appearance: the choice→theme mapping and the palette rotation.
 * Both are decided in pure Kotlin so a device is not needed to prove them.
 */
class AppearanceThemeTest {

    // ------------------------------------------------------------ mapping

    @Test
    fun followSystemUsesTheSystemDarkTheme() {
        val light = AppearanceTheme.resolve(AppearanceChoice.FOLLOW_SYSTEM, systemDark = false, palette = null)
        val dark = AppearanceTheme.resolve(AppearanceChoice.FOLLOW_SYSTEM, systemDark = true, palette = null)
        assertFalse(light.darkBackground)
        assertTrue(dark.darkBackground)
        assertTrue(light.lightBarIcons)
        assertFalse(dark.lightBarIcons)
    }

    @Test
    fun lightAndDarkIgnoreTheSystem() {
        val light = AppearanceTheme.resolve(AppearanceChoice.LIGHT, systemDark = true, palette = null)
        val dark = AppearanceTheme.resolve(AppearanceChoice.DARK, systemDark = false, palette = null)
        assertFalse(light.darkBackground)
        assertTrue(light.lightBarIcons)
        assertTrue(dark.darkBackground)
        assertFalse(dark.lightBarIcons)
    }

    @Test
    fun amoledBlackIsAlwaysDarkWhateverTheSystemSays() {
        val amoled = AppearanceTheme.resolve(AppearanceChoice.AMOLED_BLACK, systemDark = false, palette = null)
        assertTrue(amoled.darkBackground)
        assertFalse(amoled.lightBarIcons)
    }

    @Test
    fun codeStyleFollowsItsPaletteAndIgnoresTheSystem() {
        val midnight = AppearanceTheme.resolve(
            AppearanceChoice.CODE_STYLE,
            systemDark = false,
            palette = CodePalettes.MIDNIGHT_BLUE
        )
        val sand = AppearanceTheme.resolve(
            AppearanceChoice.CODE_STYLE,
            systemDark = true,
            palette = CodePalettes.SAND
        )
        assertTrue(midnight.darkBackground)
        assertFalse(midnight.lightBarIcons)
        assertEquals(CodePalettes.MIDNIGHT_BLUE, midnight.palette)
        assertFalse(sand.darkBackground)
        assertTrue(sand.lightBarIcons)
        assertEquals(CodePalettes.SAND, sand.palette)
    }

    @Test
    fun aChoiceWithoutAPaletteCarriesNone() {
        val theme = AppearanceTheme.resolve(AppearanceChoice.DARK, systemDark = false, palette = null)
        assertNull(theme.palette)
    }

    // ----------------------------------------------------------- rotation

    @Test
    fun inOrderStartsAtTheFirstPaletteBeforeAnyLaunch() {
        assertEquals(0, CodePaletteRotation.nextInOrder(6, CodePalettes.NO_LAST_INDEX))
        assertEquals(0, CodePaletteRotation.nextInOrder(6, 99))
        assertEquals(0, CodePaletteRotation.nextInOrder(6, -5))
    }

    @Test
    fun inOrderAdvancesAndWrapsAround() {
        assertEquals(1, CodePaletteRotation.nextInOrder(6, 0))
        assertEquals(2, CodePaletteRotation.nextInOrder(6, 1))
        assertEquals(5, CodePaletteRotation.nextInOrder(6, 4))
        assertEquals(0, CodePaletteRotation.nextInOrder(6, 5))
    }

    @Test
    fun inOrderVisitsEveryPaletteInSixLaunchesThenStartsAgain() {
        var last = CodePalettes.NO_LAST_INDEX
        val seen = mutableListOf<Int>()
        repeat(6) {
            last = CodePaletteRotation.nextInOrder(CodePalettes.ALL.size, last)
            seen += last
        }
        assertEquals((0 until 6).toList(), seen)
        assertEquals(0, CodePaletteRotation.nextInOrder(CodePalettes.ALL.size, last))
    }

    @Test
    fun shuffleNeverReturnsThePreviousPalette() {
        val random = Random(6327)
        for (previous in 0 until CodePalettes.ALL.size) {
            repeat(200) {
                val next = CodePaletteRotation.nextShuffle(CodePalettes.ALL.size, previous, random)
                assertNotEquals("shuffle repeated palette $previous", previous, next)
                assertTrue(next in 0 until CodePalettes.ALL.size)
            }
        }
    }

    @Test
    fun shuffleCanReachEveryOtherPalette() {
        val random = Random(42)
        val seen = mutableSetOf<Int>()
        repeat(200) {
            seen += CodePaletteRotation.nextShuffle(CodePalettes.ALL.size, lastIndex = 2, random)
        }
        assertEquals((0 until 6).toSet() - 2, seen)
    }

    @Test
    fun shuffleWithoutAHistoryStaysInRange() {
        val random = Random(7)
        repeat(100) {
            val next = CodePaletteRotation.nextShuffle(
                CodePalettes.ALL.size,
                CodePalettes.NO_LAST_INDEX,
                random
            )
            assertTrue(next in 0 until CodePalettes.ALL.size)
        }
    }

    @Test
    fun aSinglePaletteCanOnlyReturnItself() {
        assertEquals(0, CodePaletteRotation.nextShuffle(1, 0, Random(1)))
        assertEquals(0, CodePaletteRotation.nextInOrder(1, 0))
    }

    @Test
    fun rotationsNeverChangeAPaletteWhileTheAppIsInUse() {
        // Turning "change palette each launch" off always yields the chosen one.
        for (chosen in 0 until CodePalettes.ALL.size) {
            repeat(20) {
                assertEquals(
                    chosen,
                    CodePaletteRotation.indexForLaunch(
                        count = CodePalettes.ALL.size,
                        lastIndex = 3,
                        changeEachLaunch = false,
                        rotation = PaletteRotation.SHUFFLE,
                        chosenIndex = chosen,
                        random = Random(it)
                    )
                )
            }
        }
    }

    @Test
    fun indexForLaunchFollowsTheSelectedRotation() {
        val random = Random(9)
        assertEquals(
            4,
            CodePaletteRotation.indexForLaunch(
                count = 6,
                lastIndex = 3,
                changeEachLaunch = true,
                rotation = PaletteRotation.IN_ORDER,
                chosenIndex = 0,
                random = random
            )
        )
        val shuffled = CodePaletteRotation.indexForLaunch(
            count = 6,
            lastIndex = 3,
            changeEachLaunch = true,
            rotation = PaletteRotation.SHUFFLE,
            chosenIndex = 0,
            random = random
        )
        assertNotEquals(3, shuffled)
    }

    @Test
    fun anOutOfRangeChosenPaletteIsCoerced() {
        assertEquals(
            5,
            CodePaletteRotation.indexForLaunch(
                count = 6,
                lastIndex = 0,
                changeEachLaunch = false,
                rotation = PaletteRotation.IN_ORDER,
                chosenIndex = 99,
                random = Random(1)
            )
        )
        assertEquals(
            0,
            CodePaletteRotation.indexForLaunch(
                count = 6,
                lastIndex = 0,
                changeEachLaunch = false,
                rotation = PaletteRotation.IN_ORDER,
                chosenIndex = -4,
                random = Random(1)
            )
        )
    }
}
