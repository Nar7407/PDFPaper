package com.pdfpaper.app.data

import com.pdfpaper.safety.SafetyReport
import java.util.concurrent.ConcurrentHashMap

/**
 * Last safety report per source path (T49). The viewer scans in the background
 * and the details screen can show the result immediately instead of scanning
 * the same file twice. The cache is memory-only and small; a saved or edited
 * file gets a new path and a fresh scan.
 */
object SafetyScanCache {

    private const val MAX_ENTRIES = 12
    private val reports = ConcurrentHashMap<String, SafetyReport>()

    fun get(path: String): SafetyReport? = reports[path]

    fun put(path: String, report: SafetyReport) {
        if (path.isBlank()) return
        if (reports.size >= MAX_ENTRIES) reports.clear()
        reports[path] = report
    }

    fun clear() {
        reports.clear()
    }
}
