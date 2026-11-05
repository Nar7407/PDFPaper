package com.pdfpaper.app.present

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T53: deterministic presentation clock (no sleeps, caller supplies time). */
class PresenterTimerTest {

    @Test
    fun elapsedFollowsStartPauseAndResume() {
        val timer = PresenterTimer(limitMillis = 0L)
        timer.start(1_000L)
        assertEquals(0L, timer.elapsed(1_000L))
        assertEquals(5_000L, timer.elapsed(6_000L))
        assertTrue(timer.running)

        timer.pause(6_000L)
        assertFalse(timer.running)
        assertEquals(5_000L, timer.elapsed(60_000L))

        timer.start(60_000L)
        assertEquals(7_000L, timer.elapsed(62_000L))
    }

    @Test
    fun togglePausesAndResumes() {
        val timer = PresenterTimer()
        timer.start(0L)
        timer.toggle(1_000L)
        assertFalse(timer.running)
        assertEquals(1_000L, timer.elapsed(9_000L))
        timer.toggle(9_000L)
        assertTrue(timer.running)
        assertEquals(2_000L, timer.elapsed(10_000L))
    }

    @Test
    fun restartClearsTheClockAndKeepsItRunning() {
        val timer = PresenterTimer()
        timer.start(0L)
        timer.restart(4_000L)
        assertEquals(0L, timer.elapsed(4_000L))
        assertEquals(3_000L, timer.elapsed(7_000L))
        assertTrue(timer.running)
    }

    @Test
    fun resetKeepsThePausedState() {
        val timer = PresenterTimer()
        timer.start(0L)
        timer.reset(2_000L)
        assertTrue(timer.running)
        assertEquals(0L, timer.elapsed(2_000L))

        timer.pause(3_000L)
        timer.reset(10_000L)
        assertFalse(timer.running)
        assertEquals(0L, timer.elapsed(20_000L))
    }

    @Test
    fun limitCountsDownAndClampsAtZero() {
        val timer = PresenterTimer(limitMillis = 10_000L)
        timer.start(0L)
        assertEquals(10_000L, timer.remaining(0L))
        assertEquals(4_000L, timer.remaining(6_000L))
        assertEquals(0L, timer.remaining(15_000L))
        assertEquals(15_000L, timer.elapsed(15_000L))
        assertEquals(
            PresentTimerReading(15_000L, 0L, running = true),
            timer.reading(15_000L)
        )
    }

    @Test
    fun zeroLimitMeansNoRemaining() {
        val timer = PresenterTimer(limitMillis = 0L)
        timer.start(0L)
        assertNull(timer.remaining(30_000L))
        assertEquals(30_000L, timer.reading(30_000L).elapsedMillis)
        assertNull(timer.reading(30_000L).remainingMillis)
    }

    @Test
    fun formatHandlesMinutesAndHours() {
        assertEquals("00:00", PresenterTimer.format(0L))
        assertEquals("00:09", PresenterTimer.format(9_900L))
        assertEquals("01:15", PresenterTimer.format(75_000L))
        assertEquals("59:59", PresenterTimer.format(3_599_000L))
        assertEquals("1:00:00", PresenterTimer.format(3_600_000L))
        assertEquals("12:34:56", PresenterTimer.format(45_296_000L))
    }
}
