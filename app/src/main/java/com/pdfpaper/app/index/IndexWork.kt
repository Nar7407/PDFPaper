package com.pdfpaper.app.index

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pdfpaper.app.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Scheduling for the local index (T50). WorkManager owns the one running
 * indexer, so two scans cannot write the same file at once. Pause cancels the
 * current work and records the paused flag; resume starts a fresh incremental
 * scan, which naturally continues where the last completed file stopped.
 */
object IndexWork {

    const val UNIQUE_NAME = "pdfpaper-search-index"
    const val PERIODIC_NAME = "pdfpaper-search-index-periodic"
    const val TAG = "pdfpaper-search-index"

    fun enqueue(context: Context, replace: Boolean = false): Boolean {
        if (AppSettings.indexPaused(context)) return false
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setConstraints(constraints(context))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
        return true
    }

    fun pause(context: Context) {
        AppSettings.setIndexPaused(context, true)
        cancelRunning(context)
    }

    fun resume(context: Context) {
        AppSettings.setIndexPaused(context, false)
        // Pause cancelled the periodic request too; put the daily refresh back
        // before starting the one-time catch-up scan.
        schedulePeriodic(context)
        enqueue(context, replace = true)
    }

    fun cancel(context: Context) {
        // Stop whatever is running now. The daily refresh stays scheduled, but
        // its next run is a day away so Cancel does not start a new scan.
        cancelRunning(context)
        schedulePeriodic(context, initialDelayDays = 1)
    }

    /**
     * Stops both WorkManager names. They are separate requests, so cancelling
     * only the one-time work would leave an in-flight periodic scan running.
     */
    private fun cancelRunning(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(UNIQUE_NAME)
        manager.cancelUniqueWork(PERIODIC_NAME)
    }

    /**
     * Rebuild/delete use this instead of [IndexRepository.clearIndex] directly,
     * so a worker that is still finishing its current file cannot write rows
     * after the index was cleared.
     */
    suspend fun clearIndex(context: Context) {
        IndexWorker.runMutex.withLock {
            IndexRepository.get(context).clearIndex()
        }
    }

    fun schedulePeriodic(context: Context, initialDelayDays: Long = 0) {
        val request = PeriodicWorkRequestBuilder<IndexWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayDays, TimeUnit.DAYS)
            .setConstraints(constraints(context))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** Called once from the activity so an idle install still refreshes. */
    fun ensurePeriodic(context: Context) {
        schedulePeriodic(context)
    }

    fun observe(context: Context): Flow<List<WorkInfo>> = flow {
        val manager = WorkManager.getInstance(context)
        while (true) {
            // The daily refresh is a separate unique request. Include it only
            // while it is actually running: a periodic request normally sits
            // ENQUEUED between runs, and counting that as active would leave
            // the progress bar and Pause button on forever.
            val infos = runCatching {
                val oneTime = manager.getWorkInfosForUniqueWork(UNIQUE_NAME).get()
                val periodic = manager.getWorkInfosForUniqueWork(PERIODIC_NAME).get()
                    .filter { it.state == WorkInfo.State.RUNNING }
                oneTime + periodic
            }.getOrDefault(emptyList())
            emit(infos)
            delay(1_000)
        }
    }.flowOn(Dispatchers.IO)

    private fun constraints(context: Context): Constraints =
        Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(AppSettings.indexOnlyWhileCharging(context))
            .build()
}
