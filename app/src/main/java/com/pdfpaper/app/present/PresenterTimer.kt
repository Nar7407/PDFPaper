package com.pdfpaper.app.present

import java.util.Locale

/** One reading of the presentation clock. */
data class PresentTimerReading(
    val elapsedMillis: Long,
    /** Time left against the configured limit, or null when there is no limit. */
    val remainingMillis: Long?,
    val running: Boolean
)

/**
 * Presentation clock (T53): counts up from zero and, when a limit is set, also
 * reports the time left. Time is supplied by the caller so the logic is
 * deterministic and unit-tested; the view model passes `SystemClock` time.
 *
 * A limit of 0 (or less) means "no limit": elapsed keeps counting and
 * [PresentTimerReading.remainingMillis] is null.
 */
class PresenterTimer(limitMillis: Long = DEFAULT_LIMIT_MILLIS) {

    private var accumulatedMillis: Long = 0L
    private var startedAt: Long? = null

    var limitMillis: Long = limitMillis.coerceAtLeast(0L)
        private set

    val running: Boolean get() = startedAt != null

    fun setLimit(limitMillis: Long) {
        this.limitMillis = limitMillis.coerceAtLeast(0L)
    }

    fun start(nowMillis: Long) {
        if (startedAt == null) startedAt = nowMillis
    }

    fun pause(nowMillis: Long) {
        val started = startedAt ?: return
        accumulatedMillis += (nowMillis - started).coerceAtLeast(0L)
        startedAt = null
    }

    fun toggle(nowMillis: Long) {
        if (running) pause(nowMillis) else start(nowMillis)
    }

    /** Clears the clock; keeps running when it was running. */
    fun reset(nowMillis: Long) {
        accumulatedMillis = 0L
        if (startedAt != null) startedAt = nowMillis
    }

    /** Clears the clock and starts it from [nowMillis]. */
    fun restart(nowMillis: Long) {
        accumulatedMillis = 0L
        startedAt = nowMillis
    }

    fun elapsed(nowMillis: Long): Long {
        val runningSince = startedAt
        val current = if (runningSince == null) 0L else (nowMillis - runningSince).coerceAtLeast(0L)
        return accumulatedMillis + current
    }

    fun remaining(nowMillis: Long): Long? {
        if (limitMillis <= 0L) return null
        return (limitMillis - elapsed(nowMillis)).coerceAtLeast(0L)
    }

    fun reading(nowMillis: Long): PresentTimerReading = PresentTimerReading(
        elapsedMillis = elapsed(nowMillis),
        remainingMillis = remaining(nowMillis),
        running = running
    )

    companion object {
        const val DEFAULT_LIMIT_MILLIS: Long = 15L * 60L * 1000L

        /** `mm:ss`, or `h:mm:ss` once the clock reaches an hour. */
        fun format(millis: Long): String {
            val totalSeconds = millis.coerceAtLeast(0L) / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }
}
