package com.pdfpaper.app.ui.present

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.present.PenStroke
import com.pdfpaper.app.present.PresentPages
import com.pdfpaper.app.present.PresentTimerReading
import com.pdfpaper.app.present.PresentTransition
import com.pdfpaper.app.present.PresenterTimer
import com.pdfpaper.app.present.shouldAppend
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Session state of the full-screen presentation mode (T53): the page, the
 * temporary pen, the laser point, the black screen, the controls and the
 * options. Rendering and persistence stay outside: the viewer supplies page
 * bitmaps and the settings store keeps the transition, timer and last slide.
 */
data class PresentUiState(
    val active: Boolean = false,
    val path: String = "",
    val pageCount: Int = 0,
    val pageIndex: Int = 0,
    /** +1 when the last page change moved forward, -1 when it moved back. */
    val direction: Int = 1,
    val transition: PresentTransition = PresentTransition.FADE,
    val black: Boolean = false,
    val penMode: Boolean = false,
    val showControls: Boolean = true,
    val strokes: List<PenStroke> = emptyList(),
    val draft: PenStroke? = null,
    val laser: Pair<Float, Float>? = null,
    val limitMinutes: Int = 15,
    /** The slide the last session stopped on, when it differs from the start. */
    val resumePage: Int? = null,
    val showJumpGrid: Boolean = false,
    val showOptions: Boolean = false
) {
    val pageNumber: Int get() = (pageIndex + 1).coerceAtLeast(1)
    val hasPrevious: Boolean get() = pageIndex > 0
    val hasNext: Boolean get() = pageIndex < pageCount - 1
    val canSaveInk: Boolean get() = strokes.isNotEmpty()
}

class PresentViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PresentUiState())
    val state: StateFlow<PresentUiState> = _state.asStateFlow()

    private val _timer = MutableStateFlow(
        PresentTimerReading(0L, PresenterTimer.DEFAULT_LIMIT_MILLIS, running = false)
    )
    val timer: StateFlow<PresentTimerReading> = _timer.asStateFlow()

    private val clock = PresenterTimer()
    private var ticker: Job? = null

    /** Starts (or restarts) a session; safe to call once per entry. */
    fun start(path: String, pageCount: Int, startPage: Int) {
        if (pageCount <= 0) return
        val app = getApplication<Application>()
        val limitMinutes = AppSettings.presenterTimerMinutes(app)
        clock.setLimit(minutesToMillis(limitMinutes))
        clock.restart(SystemClock.elapsedRealtime())
        val saved = AppSettings.presenterLastPage(app, path)
        val page = PresentPages.clamp(startPage, pageCount)
        _state.value = PresentUiState(
            active = true,
            path = path,
            pageCount = pageCount,
            pageIndex = page,
            transition = AppSettings.presenterTransition(app),
            limitMinutes = limitMinutes,
            resumePage = saved?.takeIf { it in 0 until pageCount && it != page },
            showControls = true
        )
        publishTimer()
        startTicker()
    }

    fun exit() {
        saveLastPage()
        ticker?.cancel()
        ticker = null
        _state.update { it.copy(active = false, showJumpGrid = false, showOptions = false) }
    }

    fun next() = goTo(_state.value.pageIndex + 1)

    fun previous() = goTo(_state.value.pageIndex - 1)

    fun first() = goTo(0)

    fun last() = goTo(_state.value.pageCount - 1)

    fun goTo(index: Int) {
        val before = _state.value
        if (!before.active || before.pageCount <= 0) return
        val page = PresentPages.clamp(index, before.pageCount)
        if (page == before.pageIndex) return
        _state.value = before.copy(
            pageIndex = page,
            direction = if (page > before.pageIndex) 1 else -1,
            black = false,
            laser = null,
            draft = null,
            showJumpGrid = false
        )
        saveLastPage()
    }

    fun resume() {
        val resumePage = _state.value.resumePage ?: return
        _state.update { it.copy(resumePage = null) }
        goTo(resumePage)
    }

    fun startHere() {
        _state.update { it.copy(resumePage = null) }
    }

    fun toggleBlack() {
        _state.update { it.copy(black = !it.black, showControls = false) }
    }

    fun setBlack(black: Boolean) {
        _state.update { it.copy(black = black, showControls = if (black) false else it.showControls) }
    }

    fun togglePen() {
        _state.update {
            it.copy(
                penMode = !it.penMode,
                laser = null,
                showControls = true,
                draft = null
            )
        }
    }

    fun setControls(show: Boolean) {
        _state.update { it.copy(showControls = show) }
    }

    fun toggleControls() {
        _state.update { it.copy(showControls = !it.showControls) }
    }

    fun setLaser(x: Float?, y: Float?) {
        _state.update {
            it.copy(laser = if (x == null || y == null) null else x to y)
        }
    }

    fun startStroke(pageIndex: Int, x: Float, y: Float) {
        _state.update {
            it.copy(draft = PenStroke(pageIndex, listOf(x, y)), showControls = false)
        }
    }

    fun extendStroke(x: Float, y: Float) {
        val draft = _state.value.draft ?: return
        if (!draft.shouldAppend(x, y)) return
        _state.update {
            val current = it.draft ?: return@update it
            it.copy(draft = current.copy(points = current.points + x + y))
        }
    }

    fun endStroke() {
        _state.update {
            val draft = it.draft ?: return@update it
            if (draft.points.size < 4) {
                it.copy(draft = null)
            } else {
                it.copy(strokes = it.strokes + draft, draft = null)
            }
        }
    }

    fun undoStroke() {
        _state.update {
            if (it.strokes.isEmpty()) it else it.copy(strokes = it.strokes.dropLast(1))
        }
    }

    fun clearStrokes() {
        _state.update { it.copy(strokes = emptyList(), draft = null) }
    }

    fun setTransition(transition: PresentTransition) {
        AppSettings.setPresenterTransition(getApplication(), transition)
        _state.update { it.copy(transition = transition) }
    }

    fun setTimerMinutes(minutes: Int) {
        val safe = minutes.coerceIn(0, MAX_LIMIT_MINUTES)
        AppSettings.setPresenterTimerMinutes(getApplication(), safe)
        clock.setLimit(minutesToMillis(safe))
        _state.update { it.copy(limitMinutes = safe) }
        publishTimer()
    }

    fun toggleTimer() {
        clock.toggle(SystemClock.elapsedRealtime())
        publishTimer()
    }

    fun restartTimer() {
        clock.restart(SystemClock.elapsedRealtime())
        publishTimer()
    }

    fun showJumpGrid(show: Boolean) {
        _state.update { it.copy(showJumpGrid = show, showOptions = false) }
    }

    fun showOptions(show: Boolean) {
        _state.update { it.copy(showOptions = show, showJumpGrid = false) }
    }

    private fun saveLastPage() {
        val current = _state.value
        if (current.path.isNotBlank() && current.pageCount > 0) {
            AppSettings.setPresenterLastPage(
                getApplication(),
                current.path,
                current.pageIndex
            )
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                publishTimer()
                delay(TICK_MILLIS)
            }
        }
    }

    private fun publishTimer() {
        _timer.value = clock.reading(SystemClock.elapsedRealtime())
    }

    override fun onCleared() {
        ticker?.cancel()
        ticker = null
        super.onCleared()
    }

    companion object {
        const val DEFAULT_LIMIT_MINUTES: Int = 15
        const val MAX_LIMIT_MINUTES: Int = 240
        private const val TICK_MILLIS: Long = 500L

        /** 0 minutes means "no limit"; the clock still counts up. */
        fun minutesToMillis(minutes: Int): Long =
            if (minutes <= 0) 0L else minutes * 60_000L
    }
}
