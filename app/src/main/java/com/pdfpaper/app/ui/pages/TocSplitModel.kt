package com.pdfpaper.app.ui.pages

import com.pdfpaper.engine.split.TocSplitFileNames
import com.pdfpaper.engine.split.TocSplitIssue
import com.pdfpaper.engine.split.TocSplitPart
import java.util.Locale

/**
 * One chapter in the split preview (T66), after the user's edits. [pages] are
 * zero-based inclusive source pages; the screen shows them one-based.
 * [estimateBytes] is a rough share of the source file, not a promise.
 */
data class EditableTocSplitPart(
    val id: String,
    val title: String,
    val pages: IntRange,
    val fileName: String,
    val checked: Boolean = true,
    val estimateBytes: Long = 0L
)

/**
 * The pure preview edits for a chapter split (T66). Everything here works on
 * the plan alone, so merge/split/naming rules are testable without Android and
 * the ViewModel never has to re-read the document to change a range.
 */
object TocSplitModel {

    fun toggleCheck(parts: List<EditableTocSplitPart>, id: String): List<EditableTocSplitPart> =
        parts.map { if (it.id == id) it.copy(checked = !it.checked) else it }

    /** The chapters that will be written: checked ones, in preview order. */
    fun activeParts(parts: List<EditableTocSplitPart>): List<EditableTocSplitPart> =
        parts.filter { it.checked }

    /**
     * Renames one chapter and renumbers every file name, so a duplicate title
     * gets ` (2)` and the prefixes stay in order.
     */
    fun rename(
        parts: List<EditableTocSplitPart>,
        id: String,
        title: String
    ): List<EditableTocSplitPart> =
        withFileNames(parts.map { if (it.id == id) it.copy(title = title) else it })

    /**
     * Merges two adjacent chapters into one. Only neighbours whose ranges touch
     * can be merged: a gap would silently add pages the user did not select.
     */
    fun merge(
        parts: List<EditableTocSplitPart>,
        firstId: String,
        secondId: String
    ): Result<List<EditableTocSplitPart>> {
        val first = parts.indexOfFirst { it.id == firstId }
        val second = parts.indexOfFirst { it.id == secondId }
        if (first < 0 || second < 0 || first == second) {
            return Result.failure(IllegalArgumentException("Choose two different chapters"))
        }
        val low = minOf(first, second)
        val high = maxOf(first, second)
        if (high != low + 1) {
            return Result.failure(IllegalArgumentException("Only neighbouring chapters can be merged"))
        }
        val a = parts[low]
        val b = parts[high]
        if (b.pages.first != a.pages.last + 1) {
            return Result.failure(IllegalArgumentException("These chapters do not touch"))
        }
        val merged = a.copy(
            title = "${a.title} & ${b.title}",
            pages = a.pages.first..b.pages.last,
            checked = a.checked || b.checked
        )
        val updated = ArrayList<EditableTocSplitPart>(parts.size - 1)
        updated += parts.subList(0, low)
        updated += merged
        updated += parts.subList(high + 1, parts.size)
        return Result.success(withFileNames(updated))
    }

    /**
     * Splits one chapter at [splitAtPage] (the first page of the second part).
     * The page itself belongs to the second part, so both ranges stay non-empty.
     */
    fun split(
        parts: List<EditableTocSplitPart>,
        targetId: String,
        splitAtPage: Int,
        firstTitle: String,
        secondTitle: String
    ): Result<List<EditableTocSplitPart>> {
        val index = parts.indexOfFirst { it.id == targetId }
        if (index < 0) return Result.failure(IllegalArgumentException("That chapter is gone"))
        val part = parts[index]
        if (splitAtPage <= part.pages.first || splitAtPage > part.pages.last) {
            return Result.failure(
                IllegalArgumentException(
                    "Choose a page between ${part.pages.first + 2} and ${part.pages.last + 1}"
                )
            )
        }
        val used = parts.mapTo(HashSet()) { it.id }
        var secondId = "${part.id}_b"
        var counter = 2
        while (!used.add(secondId)) {
            secondId = "${part.id}_b${counter++}"
        }
        val first = part.copy(title = firstTitle, pages = part.pages.first..(splitAtPage - 1))
        val second = part.copy(
            id = secondId,
            title = secondTitle,
            pages = splitAtPage..part.pages.last,
            checked = part.checked
        )
        val updated = ArrayList<EditableTocSplitPart>(parts.size + 1)
        updated += parts.subList(0, index)
        updated += first
        updated += second
        updated += parts.subList(index + 1, parts.size)
        return Result.success(withFileNames(updated))
    }

    /**
     * Applies the shared-boundary-page choice to the current ranges. Each
     * chapter ends on the page before the next start, or on that page itself
     * when [duplicate] is set, so a chapter that really begins on the page
     * named in the contents is not cut off.
     */
    fun applyDuplicateBoundary(
        parts: List<EditableTocSplitPart>,
        duplicate: Boolean
    ): List<EditableTocSplitPart> = parts.mapIndexed { index, part ->
        val end = if (index == parts.lastIndex) {
            part.pages.last
        } else {
            val nextStart = parts[index + 1].pages.first
            if (duplicate) nextStart else nextStart - 1
        }
        part.copy(pages = part.pages.first..end)
    }

    /** Rough per-part size, shared by page count. */
    fun recomputeEstimates(
        parts: List<EditableTocSplitPart>,
        totalBytes: Long,
        totalPages: Int
    ): List<EditableTocSplitPart> {
        if (totalPages <= 0 || totalBytes <= 0) {
            return parts.map { it.copy(estimateBytes = 0L) }
        }
        return parts.map { part ->
            val pages = part.pages.last - part.pages.first + 1
            part.copy(estimateBytes = totalBytes * pages.toLong() / totalPages.toLong())
        }
    }

    /** A short human size for the preview row. */
    fun formatEstimate(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return sized(kb, "KB")
        val mb = kb / 1024.0
        if (mb < 1024) return sized(mb, "MB")
        return sized(mb / 1024.0, "GB")
    }

    private fun sized(value: Double, unit: String): String {
        val text = if (value >= 10 || value == value.toLong().toDouble()) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return "$text $unit"
    }

    /** Planned names already in the destination; unchecked chapters are ignored. */
    fun findConflicts(
        parts: List<EditableTocSplitPart>,
        existingFiles: Set<String>
    ): List<String> =
        activeParts(parts).map { it.fileName }.filter { it in existingFiles }

    /** One line per issue, in the order the planner found them. */
    fun formatIssueDescription(issue: TocSplitIssue): String = when (issue) {
        TocSplitIssue.NoOutline ->
            "This document has no outline. Split every N pages instead, " +
                "or detect headings on the pages."
        TocSplitIssue.NoUsableEntries ->
            "The outline names no pages that exist in this document. " +
                "Split every N pages instead, or detect headings on the pages."
        is TocSplitIssue.InvalidDestination ->
            "Skipped \"${issue.title}\": its bookmark does not point at a page."
        is TocSplitIssue.OutOfOrder ->
            "The outline is out-of-order; \"" + issue.title +
                "\" was sorted by page (position ${issue.originalPage + 1} to " +
                "${issue.sortedPage + 1})."
        is TocSplitIssue.SamePage ->
            "\"${issue.title}\" starts on page ${issue.page + 1}, like the chapter " +
                "before it; the earlier entry keeps that start."
        is TocSplitIssue.EmptyRange ->
            "Skipped \"${issue.title}\": it would be an empty file."
        is TocSplitIssue.DroppedExternalLink ->
            "Dropped a link from page ${issue.sourcePage + 1} to page " +
                "${issue.targetPage + 1} (that page is in another part)."
        is TocSplitIssue.OutputNameAdjusted ->
            "\"${issue.title}\" is saved as ${issue.fileName}."
    }

    /** The engine's naming rules, applied to the preview list. */
    fun withFileNames(parts: List<EditableTocSplitPart>): List<EditableTocSplitPart> {
        val named = TocSplitFileNames.assignFileNames(
            parts.map { TocSplitPart(it.id, it.title, it.pages, "", it.checked) }
        )
        return parts.mapIndexed { index, part -> part.copy(fileName = named[index].fileName) }
    }
}
