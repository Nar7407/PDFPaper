package com.pdfpaper.app.present

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.ui.present.PresentViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T53: the presentation view model on a real Android runtime — settings
 * persistence, page clamping, the black screen/pen state and resume.
 */
@RunWith(AndroidJUnit4::class)
class PresentViewModelDeviceTest {

    @Test
    fun sessionNavigatesPersistsAndResumes() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PresentViewModel(app) as T
        }
        val viewModel = ViewModelProvider(store, factory)[PresentViewModel::class.java]
        val path = "present-test-${System.nanoTime()}.pdf"

        try {
            viewModel.start(path, pageCount = 5, startPage = 2)
            assertTrue(viewModel.state.value.active)
            assertEquals(2, viewModel.state.value.pageIndex)
            assertEquals(3, viewModel.state.value.pageNumber)
            assertNull(viewModel.state.value.resumePage)

            viewModel.next()
            assertEquals(3, viewModel.state.value.pageIndex)
            assertEquals(1, viewModel.state.value.direction)
            viewModel.previous()
            assertEquals(2, viewModel.state.value.pageIndex)
            assertEquals(-1, viewModel.state.value.direction)

            viewModel.goTo(99)
            assertEquals(4, viewModel.state.value.pageIndex)
            assertFalse(viewModel.state.value.hasNext)
            viewModel.goTo(-4)
            assertEquals(0, viewModel.state.value.pageIndex)
            assertFalse(viewModel.state.value.hasPrevious)

            assertEquals(0, AppSettings.presenterLastPage(app, path))
            viewModel.goTo(4)
            assertEquals(4, AppSettings.presenterLastPage(app, path))

            viewModel.toggleBlack()
            assertTrue(viewModel.state.value.black)
            assertFalse(viewModel.state.value.showControls)
            viewModel.toggleBlack()
            assertFalse(viewModel.state.value.black)

            viewModel.togglePen()
            assertTrue(viewModel.state.value.penMode)
            viewModel.startStroke(4, 0.1f, 0.2f)
            viewModel.extendStroke(0.3f, 0.4f)
            viewModel.extendStroke(0.5f, 0.6f)
            viewModel.endStroke()
            assertEquals(1, viewModel.state.value.strokes.size)
            assertEquals(4, viewModel.state.value.strokes.first().pageIndex)
            viewModel.undoStroke()
            assertTrue(viewModel.state.value.strokes.isEmpty())
            viewModel.clearStrokes()
            viewModel.togglePen()
            assertFalse(viewModel.state.value.penMode)

            viewModel.exit()
            assertFalse(viewModel.state.value.active)

            // Re-entering from slide 1 offers the slide that was left.
            viewModel.start(path, pageCount = 5, startPage = 0)
            assertEquals(4, viewModel.state.value.resumePage)
            viewModel.resume()
            assertEquals(4, viewModel.state.value.pageIndex)
            assertNull(viewModel.state.value.resumePage)
        } finally {
            viewModel.exit()
            store.clear()
        }
    }

    @Test
    fun transitionAndTimerAreStoredInSettings() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PresentViewModel(app) as T
        }
        val viewModel = ViewModelProvider(store, factory)[PresentViewModel::class.java]
        try {
            viewModel.start("present-settings-${System.nanoTime()}.pdf", 3, 0)
            viewModel.setTransition(PresentTransition.SLIDE)
            assertEquals(PresentTransition.SLIDE, AppSettings.presenterTransition(app))
            assertEquals(PresentTransition.SLIDE, viewModel.state.value.transition)

            viewModel.setTimerMinutes(0)
            assertEquals(0, AppSettings.presenterTimerMinutes(app))
            assertEquals(0, viewModel.state.value.limitMinutes)
            assertNull(viewModel.timer.value.remainingMillis)

            viewModel.setTimerMinutes(30)
            assertEquals(30, AppSettings.presenterTimerMinutes(app))
            // A few milliseconds of the new limit may already have run when the
            // clock is read back, so allow a small tolerance rather than pinning
            // the exact instant.
            val remaining = viewModel.timer.value.remainingMillis ?: 0L
            assertTrue(remaining in (30 * 60_000L - 5_000L)..(30 * 60_000L))
        } finally {
            viewModel.exit()
            store.clear()
        }
    }
}
