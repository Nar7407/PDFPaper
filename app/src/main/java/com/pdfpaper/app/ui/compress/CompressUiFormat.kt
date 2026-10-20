package com.pdfpaper.app.ui.compress

import com.pdfpaper.engine.compress.CompressEstimate
import com.pdfpaper.engine.compress.CompressReport
import java.util.Locale

/**
 * The wording of the compress screen, kept apart from the Compose code so it
 * can be checked by plain JVM tests.
 */

/** The note the screen shows when the compression did not make the file smaller. */
const val KEEP_ORIGINAL_NOTE = "The compressed copy was not smaller, so the original file was kept."

/** The lowest image resolution the custom slider offers. */
const val MIN_TARGET_DPI = 72

/** The highest image resolution the custom slider offers. */
const val MAX_TARGET_DPI = 300

/** The lowest JPEG quality the custom slider offers. */
const val MIN_JPEG_QUALITY = 30

/** The highest JPEG quality the custom slider offers. */
const val MAX_JPEG_QUALITY = 95

/**
 * A file size in plain language: "820 B", "4.0 KB", "1.2 MB", "3.4 GB".
 * The decimal point is always a point, never a comma, so the numbers read the
 * same in every language.
 */
fun formatSize(bytes: Int): String {
    val value = bytes.coerceAtLeast(0)
    return when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        value < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.1f GB", value / (1024.0 * 1024.0 * 1024.0))
    }
}

/** The size before and after: "1.2 MB → 300 KB". */
fun formatSizes(before: Int, after: Int): String = "${formatSize(before)} → ${formatSize(after)}"

/**
 * The estimate line of the screen:
 * "Estimated size: about 1.2 MB (about 35% smaller) — estimated from the first 3 pages".
 */
fun estimateLine(estimate: CompressEstimate): String {
    val size = "about ${formatSize(estimate.estimatedBytes)}"
    val saving = if (estimate.estimatedSavedPercent > 0) {
        "about ${estimate.estimatedSavedPercent}% smaller"
    } else {
        "about the same size"
    }
    val pages = if (estimate.sampledPages in 1 until estimate.totalPages) {
        "estimated from the first ${estimate.sampledPages} pages"
    } else {
        "estimated from all ${estimate.totalPages} pages"
    }
    return "Estimated size: $size ($saving) — $pages"
}

/** How much the run saved: "Saved 35% (900 KB)". */
fun savedLine(report: CompressReport): String =
    "Saved ${report.savedPercent}% (${formatSize(report.savedBytes.coerceAtLeast(0))})"

/** One line per part of the file: the label and what it saved. */
fun categoryLines(report: CompressReport): List<String> =
    report.categories.map { category ->
        val saved = category.savedBytes
        if (saved > 0) {
            "${category.label}: ${formatSize(saved)} saved"
        } else {
            "${category.label}: nothing saved"
        }
    }

/** The counters of the run, in plain language. Counts of zero are left out. */
fun countLines(report: CompressReport): List<String> = listOfNotNull(
    countLine(report.imagesRecompressed, "image re-encoded", "images re-encoded"),
    countLine(report.imagesDownsampled, "image scaled down", "images scaled down"),
    countLine(report.streamsRecompressed, "stream packed again", "streams packed again"),
    countLine(report.duplicatesShared, "duplicate stream shared", "duplicate streams shared"),
    countLine(report.unusedObjectsRemoved, "unused object removed", "unused objects removed")
)

/** The notes about what the run left alone. */
fun skippedLines(report: CompressReport): List<String> {
    val notes = report.skipped.toMutableList()
    if (report.usedOriginal && notes.none { it.contains(KEEP_ORIGINAL_NOTE) }) {
        notes += KEEP_ORIGINAL_NOTE
    }
    return notes
}

/** Every line of the result card, in the order the card shows them. */
fun reportLines(report: CompressReport): List<String> =
    categoryLines(report) + countLines(report) + skippedLines(report)

/** "report.pdf" becomes "report-compressed.pdf". */
fun suggestedFileName(name: String): String {
    val base = name.substringBeforeLast('.', name).trim()
    val safe = if (base.isEmpty()) "document" else base
    return "$safe-compressed.pdf"
}

/** "1 image re-encoded" or "3 images re-encoded"; nothing for a count of zero. */
private fun countLine(value: Int, singular: String, plural: String): String? = when {
    value <= 0 -> null
    value == 1 -> "1 $singular"
    else -> "$value $plural"
}
