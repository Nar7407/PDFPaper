package com.pdfpaper.app.ui.viewer.turn

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T58: the reading gesture contract is pure logic, so the zone mapping, the
 * direction rules, the left-handed mirror and the scroll maths are tested on
 * the JVM rather than only by hand on a phone.
 */
class PageTurnModelTest {

    private val settings = TurnSettings()

    private fun zone(x: Float, y: Float, s: TurnSettings = settings): TurnZone =
        TurnZones.zoneAt(x, y, WIDTH, HEIGHT, s)

    @Test
    fun theEdgesOfThePageAreTheTurnZones() {
        // 22 % of 1000 px is the left/right band.
        assertEquals(TurnZone.LEFT, zone(10f, HEIGHT / 2))
        assertEquals(TurnZone.LEFT, zone(219f, HEIGHT / 2))
        assertEquals(TurnZone.CENTER, zone(221f, HEIGHT / 2))
        assertEquals(TurnZone.CENTER, zone(WIDTH / 2, HEIGHT / 2))
        assertEquals(TurnZone.RIGHT, zone(781f, HEIGHT / 2))
        assertEquals(TurnZone.RIGHT, zone(990f, HEIGHT / 2))
    }

    @Test
    fun topAndBottomZonesAreOptIn() {
        // Off by default: the middle column is all "toggle the toolbars".
        assertEquals(TurnZone.CENTER, zone(WIDTH / 2, 10f))
        assertEquals(TurnZone.CENTER, zone(WIDTH / 2, HEIGHT - 10f))

        val vertical = settings.copy(verticalZones = true)
        assertEquals(TurnZone.TOP, zone(WIDTH / 2, 10f, vertical))
        assertEquals(TurnZone.BOTTOM, zone(WIDTH / 2, HEIGHT - 10f, vertical))
        assertEquals(TurnZone.CENTER, zone(WIDTH / 2, HEIGHT / 2, vertical))
    }

    @Test
    fun theSideZonesWinOverTheTopAndBottomOnes() {
        val vertical = settings.copy(verticalZones = true)
        // A corner tap belongs to the thumb, not to the top band.
        assertEquals(TurnZone.LEFT, zone(10f, 10f, vertical))
        assertEquals(TurnZone.RIGHT, zone(WIDTH - 10f, 10f, vertical))
        assertEquals(TurnZone.LEFT, zone(10f, HEIGHT - 10f, vertical))
    }

    @Test
    fun aTapOutsideTheSurfaceIsClamped() {
        assertEquals(TurnZone.LEFT, zone(-40f, HEIGHT / 2))
        assertEquals(TurnZone.RIGHT, zone(WIDTH + 40f, HEIGHT / 2))
        assertEquals(TurnZone.CENTER, TurnZones.zoneAt(10f, 10f, 0f, 0f, settings))
    }

    @Test
    fun directionsFollowThePhysicalSides() {
        assertEquals(TurnDirection.BACKWARD, TurnZones.directionFor(TurnZone.LEFT, settings))
        assertEquals(TurnDirection.FORWARD, TurnZones.directionFor(TurnZone.RIGHT, settings))
        assertEquals(TurnDirection.BACKWARD, TurnZones.directionFor(TurnZone.TOP, settings))
        assertEquals(TurnDirection.FORWARD, TurnZones.directionFor(TurnZone.BOTTOM, settings))
        assertNull(TurnZones.directionFor(TurnZone.CENTER, settings))
    }

    @Test
    fun leftHandedMirrorsOnlyTheTwoVerticalEdges() {
        val left = settings.copy(leftHanded = true)
        assertEquals(TurnDirection.FORWARD, TurnZones.directionFor(TurnZone.LEFT, left))
        assertEquals(TurnDirection.BACKWARD, TurnZones.directionFor(TurnZone.RIGHT, left))
        // Top and bottom are not reached by the thumb, so they keep their meaning.
        assertEquals(TurnDirection.BACKWARD, TurnZones.directionFor(TurnZone.TOP, left))
        assertEquals(TurnDirection.FORWARD, TurnZones.directionFor(TurnZone.BOTTOM, left))
    }

    @Test
    fun volumeDownGoesForwardAndUpGoesBack() {
        assertEquals(
            TurnDirection.FORWARD,
            TurnKeys.direction(KeyEvent.KEYCODE_VOLUME_DOWN, settings)
        )
        assertEquals(
            TurnDirection.BACKWARD,
            TurnKeys.direction(KeyEvent.KEYCODE_VOLUME_UP, settings)
        )
        assertNull(TurnKeys.direction(KeyEvent.KEYCODE_PAGE_DOWN, settings))
    }

    @Test
    fun invertingTheVolumeKeysSwapsThePair() {
        val inverted = settings.copy(invertVolume = true)
        assertEquals(
            TurnDirection.BACKWARD,
            TurnKeys.direction(KeyEvent.KEYCODE_VOLUME_DOWN, inverted)
        )
        assertEquals(
            TurnDirection.FORWARD,
            TurnKeys.direction(KeyEvent.KEYCODE_VOLUME_UP, inverted)
        )
    }

    @Test
    fun onlyTheVolumePairIsClaimedWhileReading() {
        assertTrue(TurnKeys.handles(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(TurnKeys.handles(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(TurnKeys.handles(KeyEvent.KEYCODE_PAGE_DOWN))
        assertFalse(TurnKeys.handles(KeyEvent.KEYCODE_A))
    }

    @Test
    fun theZoneWidthIsClampedToSomethingUsable() {
        assertEquals(0.10f, TurnZones.zoneFraction(1), 0.0001f)
        assertEquals(0.40f, TurnZones.zoneFraction(90), 0.0001f)
        assertEquals(0.22f, TurnZones.zoneFraction(22), 0.0001f)

        val wild = TurnSettings(zonePercent = 95, overlapLines = 40).normalised()
        assertEquals(40, wild.zonePercent)
        assertEquals(8, wild.overlapLines)
    }

    @Test
    fun aHeldKeyRepeatsButNotAtThePlatformRate() {
        assertTrue(TurnRepeat.accepts(repeatCount = 0, eventTime = 100, lastEventTime = 0))
        assertFalse(TurnRepeat.accepts(repeatCount = 1, eventTime = 150, lastEventTime = 100))
        assertTrue(TurnRepeat.accepts(repeatCount = 2, eventTime = 300, lastEventTime = 100))
        assertTrue(TurnRepeat.accepts(repeatCount = 3, eventTime = 500, lastEventTime = 300))
    }

    @Test
    fun oneScreenKeepsAFewLinesOfContext() {
        // 4000 px of page, 48 lines: two lines of overlap are 166.7 px.
        assertEquals(166.67f, TurnScroll.overlapPx(4000f, 2), 0.01f)
        assertEquals(0f, TurnScroll.overlapPx(4000f, 0), 0.01f)
        assertEquals(1833.33f, TurnScroll.stepPx(2000f, 4000f, 2), 0.01f)
        // A huge overlap on a very tall page still moves forward by a quarter
        // of the screen. (The overlap itself is clamped to 8 lines first.)
        assertEquals(500f, TurnScroll.stepPx(2000f, 40000f, 40), 0.01f)
        assertEquals(500f, TurnScroll.stepPx(2000f, 40000f, 8), 0.01f)
        // Before the first layout pass nothing moves.
        assertEquals(0f, TurnScroll.stepPx(0f, 0f, 2), 0.01f)
    }

    @Test
    fun theSystemRemoveAnimationsSettingWins() {
        assertEquals(
            TurnAnimation.ANIMATED,
            TurnMotion.effective(TurnAnimation.ANIMATED, systemRemovesAnimations = false)
        )
        assertEquals(
            TurnAnimation.INSTANT,
            TurnMotion.effective(TurnAnimation.ANIMATED, systemRemovesAnimations = true)
        )
        assertEquals(
            TurnAnimation.INSTANT,
            TurnMotion.effective(TurnAnimation.INSTANT, systemRemovesAnimations = false)
        )
    }

    @Test
    fun theAppReduceMotionFlagAlsoMakesEveryTurnInstant() {
        assertEquals(
            TurnAnimation.INSTANT,
            TurnMotion.effective(
                TurnAnimation.ANIMATED,
                systemRemovesAnimations = false,
                reduceMotion = true
            )
        )
        assertEquals(
            TurnAnimation.ANIMATED,
            TurnMotion.effective(
                TurnAnimation.ANIMATED,
                systemRemovesAnimations = false,
                reduceMotion = false
            )
        )
    }

    @Test
    fun reduceMotionPinsTheReadingModeToWholePageSnaps() {
        assertEquals(
            TurnMode.PAGE_SNAP,
            TurnMotion.effectiveMode(TurnMode.CONTINUOUS, reduceMotion = true)
        )
        assertEquals(
            TurnMode.PAGE_SNAP,
            TurnMotion.effectiveMode(TurnMode.HORIZONTAL, reduceMotion = true)
        )
        assertEquals(
            TurnMode.CONTINUOUS,
            TurnMotion.effectiveMode(TurnMode.CONTINUOUS, reduceMotion = false)
        )
    }

    @Test
    fun turningStepsAsideWhileAnotherModeOwnsTheFinger() {
        assertTrue(TurnPolicy.enabled(settings, false, false, false, false, false))
        assertTrue(TurnPolicy.edgeLayerActive(settings, false, false, false, false, false))

        assertFalse(TurnPolicy.enabled(settings, true, false, false, false, false))
        assertFalse(TurnPolicy.enabled(settings, false, true, false, false, false))
        assertFalse(TurnPolicy.enabled(settings, false, false, true, false, false))
        assertFalse(TurnPolicy.enabled(settings, false, false, false, true, false))
        assertFalse(TurnPolicy.enabled(settings, false, false, false, false, true))
        assertFalse(TurnPolicy.edgeLayerActive(settings, false, false, true, false, false))
    }

    @Test
    fun bothGesturesOffMeansNoTurningAtAll() {
        val off = TurnSettings(edgeTaps = false, volumeKeys = false)
        assertFalse(TurnPolicy.enabled(off, false, false, false, false, false))
        // Volume-only reading still turns, it just has no edge layer.
        val keys = TurnSettings(edgeTaps = false, volumeKeys = true)
        assertTrue(TurnPolicy.enabled(keys, false, false, false, false, false))
        assertFalse(TurnPolicy.edgeLayerActive(keys, false, false, false, false, false))
    }

    @Test
    fun theTallestVisiblePageIsTheCurrentOne() {
        // Two pages in view: the one that covers most of the screen is where
        // the reader is.
        assertEquals(1, TurnPosition.currentPage(listOf(0 to 400, 1 to 1200)))
        assertEquals(0, TurnPosition.currentPage(listOf(0 to 1500, 1 to 300)))
        // Before the first layout pass there is no page to name.
        assertEquals(0, TurnPosition.currentPage(emptyList()))
    }

    @Test
    fun theLastPageCountsEvenWhenItCannotFillTheScreen() {
        // Scrolled to the end of a three-page document: the top item is still
        // page 2, but page 3 covers more of the screen and the readout says 3.
        assertEquals(2, TurnPosition.currentPage(listOf(1 to 673, 2 to 1527)))
        // A single page taller than the screen is simply the current page.
        assertEquals(4, TurnPosition.currentPage(listOf(4 to 2200)))
    }

    @Test
    fun theDefaultsAreEdgeTapsOnAndVolumeKeysOff() {
        assertFalse(settings.volumeKeys)
        assertTrue(settings.edgeTaps)
        assertFalse(settings.invertVolume)
        assertFalse(settings.leftHanded)
        assertEquals(TurnMode.CONTINUOUS, settings.mode)
        assertEquals(TurnAction.PAGE, settings.action)
        assertEquals(22, settings.zonePercent)
    }

    private companion object {
        const val WIDTH = 1000f
        const val HEIGHT = 2000f
    }
}
