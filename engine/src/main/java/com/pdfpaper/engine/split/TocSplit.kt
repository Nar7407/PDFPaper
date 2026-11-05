package com.pdfpaper.engine.split

import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.outline.DocumentOutline
import com.pdfpaper.engine.outline.OutlineEntry
import com.pdfpaper.engine.outline.documentPages
import com.pdfpaper.engine.reflow.ReflowBlockKind
import com.pdfpaper.engine.reflow.ReflowExtractor
import com.pdfpaper.engine.write.PdfDocumentAssembler
import com.pdfpaper.engine.write.PdfPageSelection
import com.pdfpaper.engine.write.PdfWriter
import java.util.Locale

/** Where the chapter starts come from (T66). */
enum class TocSplitSource { OUTLINE, HEADINGS }

/**
 * User choices for one split (T66). [level] is one-based: 1 keeps only
 * top-level entries, 2 also their children, and so on. [duplicateBoundaryPage]
 * puts the page a chapter starts on at the end of the chapter before it, so a
 * chapter that really begins on the page named in the contents is not cut off.
 */
data class TocSplitOptions(
    val level: Int = 1,
    val duplicateBoundaryPage: Boolean = false,
    val source: TocSplitSource = TocSplitSource.OUTLINE
)

/**
 * One planned output file. [pages] are zero-based inclusive source pages;
 * the app and the CLI show them one-based like every other range in PDFPaper.
 */
data class TocSplitPart(
    val id: String,
    val title: String,
    val pages: IntRange,
    val fileName: String,
    val checked: Boolean = true
)

/** A read-only preview of the split: what would be written, and what was corrected. */
data class TocSplitPlan(
    val source: TocSplitSource,
    val requestedLevel: Int,
    val parts: List<TocSplitPart>,
    val issues: List<TocSplitIssue>,
    val pageCount: Int
)

/**
 * Something the planner had to correct or skip (T66). Every one is shown to the
 * user; none of them stops the split.
 */
sealed class TocSplitIssue {

    /** The document has no outline at all (or an outline with no items). */
    data object NoOutline : TocSplitIssue()

    /** The outline exists but no entry at the requested level points at a page. */
    data object NoUsableEntries : TocSplitIssue()

    /** One entry's destination could not be resolved to a page of this document. */
    data class InvalidDestination(val title: String, val depth: Int) : TocSplitIssue()

    /** An entry pointed at an earlier page than the one before it. */
    data class OutOfOrder(val title: String, val originalPage: Int, val sortedPage: Int) : TocSplitIssue()

    /** Two entries start on the same page; the first one keeps it. */
    data class SamePage(val title: String, val page: Int) : TocSplitIssue()

    /** A range came out empty and was dropped. */
    data class EmptyRange(val title: String) : TocSplitIssue()

    /** An internal link was dropped because its target page is in another part. */
    data class DroppedExternalLink(val sourcePage: Int, val targetPage: Int) : TocSplitIssue()

    /** The planned file name differs from the chapter title after sanitising. */
    data class OutputNameAdjusted(val title: String, val fileName: String) : TocSplitIssue()
}

/** One finished part handed to the writer's sink. */
data class TocSplitOutput(
    val part: TocSplitPart,
    val bytes: ByteArray,
    val report: TocSplitReport
)

/** What happened while one part was written. */
data class TocSplitReport(
    val droppedCrossPartLinks: Int,
    val issues: List<TocSplitIssue>
)

/**
 * Splits a document at its outline (T66). The plan is built from the resolved
 * outline entries, so it never throws on a malformed one: a bad entry is
 * reported and skipped.
 */
object TocSplit {

    /** Plan from the document outline. */
    fun planOutline(document: PdfDocument, options: TocSplitOptions): TocSplitPlan {
        val pages = documentPages(document)
        val diagnostics = DocumentOutline.diagnose(document)
        val issues = ArrayList<TocSplitIssue>()
        if (diagnostics.isEmpty()) {
            issues += TocSplitIssue.NoOutline
            return TocSplitPlan(TocSplitSource.OUTLINE, options.level, emptyList(), issues, pages.size)
        }

        val candidates = ArrayList<Entry>()
        var outlinePosition = 0
        for (diagnostic in diagnostics) {
            val position = outlinePosition++
            if (diagnostic.depth >= options.level) continue
            val pageIndex = diagnostic.pageIndex
            if (pageIndex == null) {
                issues += TocSplitIssue.InvalidDestination(diagnostic.title, diagnostic.depth)
                continue
            }
            if (diagnostic.title.isBlank()) {
                issues += TocSplitIssue.InvalidDestination("(untitled)", diagnostic.depth)
                continue
            }
            candidates += Entry(diagnostic.title, pageIndex, position)
        }

        return buildPlan(document, pages.size, candidates, issues, TocSplitSource.OUTLINE, options)
    }

    /**
     * Plan from headings only, for a document without a usable outline. This
     * uses T33's detector, so it is a guess: the plan is labelled
     * [TocSplitSource.HEADINGS] and never presented as the table of contents.
     */
    fun planHeadings(document: PdfDocument, options: TocSplitOptions): TocSplitPlan {
        val pages = documentPages(document)
        val issues = ArrayList<TocSplitIssue>()
        val candidates = ArrayList<Entry>()
        val seenPages = HashSet<Int>()
        for (page in pages) {
            val blocks = ReflowExtractor.extract(page).getOrNull()?.blocks.orEmpty()
            // Only the first heading of a page starts a chapter; a page with a
            // title and a subtitle is still one chapter.
            val heading = blocks.firstOrNull {
                it.kind == ReflowBlockKind.HEADING && it.level in 1..options.level
            } ?: continue
            val title = heading.text.trim()
            if (title.isEmpty()) continue
            if (!seenPages.add(page.index)) continue
            candidates += Entry(title, page.index, candidates.size)
        }
        return buildPlan(document, pages.size, candidates, issues, TocSplitSource.HEADINGS, options)
    }

    /** The outline of [document] as the splitter sees it (after a save/reopen). */
    fun readOutlines(document: PdfDocument): List<OutlineEntry> = DocumentOutline.read(document)

    private data class Entry(val title: String, val pageIndex: Int, val position: Int)

    private fun buildPlan(
        document: PdfDocument,
        pageCount: Int,
        candidates: List<Entry>,
        issues: MutableList<TocSplitIssue>,
        source: TocSplitSource,
        options: TocSplitOptions
    ): TocSplitPlan {
        // Same page: keep the first (stable), report the rest. This is what
        // stops a zero-page part.
        val kept = ArrayList<Entry>()
        val pageSeen = HashSet<Int>()
        for (entry in candidates) {
            if (!pageSeen.add(entry.pageIndex)) {
                issues += TocSplitIssue.SamePage(entry.title, entry.pageIndex)
                continue
            }
            kept += entry
        }

        // Report the entries that move, then stable-sort by page.
        var highest = -1
        for (entry in kept) {
            if (entry.pageIndex < highest) {
                val sortedPosition = kept.count { it.pageIndex < entry.pageIndex }
                issues += TocSplitIssue.OutOfOrder(entry.title, entry.position, sortedPosition)
            } else {
                highest = entry.pageIndex
            }
        }
        val sorted = kept.sortedBy { it.pageIndex }

        if (sorted.isEmpty()) {
            issues += TocSplitIssue.NoUsableEntries
            return TocSplitPlan(source, options.level, emptyList(), issues, pageCount)
        }

        val parts = ArrayList<TocSplitPart>()
        for ((index, entry) in sorted.withIndex()) {
            val start = entry.pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            val end = if (index == sorted.lastIndex) {
                pageCount - 1
            } else {
                val next = sorted[index + 1].pageIndex
                if (options.duplicateBoundaryPage) next else next - 1
            }
            if (end < start) {
                issues += TocSplitIssue.EmptyRange(entry.title)
                continue
            }
            parts += TocSplitPart(
                id = "part_${parts.size}",
                title = entry.title,
                pages = start..end,
                fileName = ""
            )
        }

        val named = TocSplitFileNames.assignFileNames(parts)
        return TocSplitPlan(source, options.level, named, issues, pageCount)
    }
}

/**
 * Output file names for a split (T66). Names are built from the chapter title,
 * never from the source file, and are safe on Windows, Android and a SAF
 * provider: no `: \ / * ? " < > |`, no trailing dots or spaces, at most 150
 * characters before `.pdf`.
 *
 * The numeric prefix (`01 - `, width follows the part count) makes a blank or
 * reserved title (`CON`, `AUX`, `LPT1`) harmless without a name blacklist.
 */
object TocSplitFileNames {

    private const val ILLEGAL = ":\\/*?\"<>|"
    private const val MAX_NAME = 150

    /** A complete file name for [title] at [index] (one-based) of [totalParts]. */
    fun sanitize(title: String, index: Int, totalParts: Int): String =
        build(clean(title), index, totalParts)

    /** The same rules for every part at once, with ` (2)`, ` (3)` for repeats. */
    fun assignFileNames(parts: List<TocSplitPart>): List<TocSplitPart> {
        val seen = HashMap<String, Int>()
        return parts.mapIndexed { index, part ->
            val base = clean(part.title)
            val count = (seen[base] ?: 0) + 1
            seen[base] = count
            val middle = if (count > 1) "$base ($count)" else base
            part.copy(fileName = build(middle, index + 1, parts.size))
        }
    }

    private fun build(middle: String, index: Int, totalParts: Int): String {
        val width = maxOf(2, totalParts.toString().length)
        val prefix = index.toString().padStart(width, '0') + " - "
        val room = (MAX_NAME - ".pdf".length - prefix.length).coerceAtLeast(1)
        val body = middle.take(room).trimEnd(' ', '.')
        val safe = if (body.isEmpty()) "Chapter" else body
        return prefix + safe + ".pdf"
    }

    private fun clean(title: String): String {
        val out = StringBuilder(title.length)
        for (ch in title) {
            when {
                ch in ILLEGAL -> out.append(' ')
                ch.code < 0x20 || ch.code == 0x7F -> out.append(' ')
                else -> out.append(ch)
            }
        }
        return out.toString().replace(Regex("\\s+"), " ").trim().trimEnd(' ', '.')
    }
}

/**
 * Writes the planned parts one at a time (T66). Each part is assembled, handed
 * to [sink] and then forgotten, so a 1,000-page book never holds more than one
 * output in memory. Cancellation is checked before every part and never throws;
 * [write] returns the number of parts actually delivered.
 */
object TocSplitWriter {

    fun write(
        document: PdfDocument,
        parts: List<TocSplitPart>,
        sink: (TocSplitOutput) -> Unit,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Int {
        val pageCount = documentPages(document).size
        var done = 0
        for (part in parts) {
            if (isCancelled()) break
            val start = part.pages.first.coerceIn(0, pageCount)
            val end = (part.pages.last + 1).coerceIn(start, pageCount)
            if (end <= start) continue
            val dropped = ArrayList<TocSplitIssue>()
            val delivered = try {
                val selections = (start until end).map { PdfPageSelection(document, it) }
                val bytes = PdfDocumentAssembler(
                    selections = selections,
                    primary = document,
                    version = PdfWriter.DEFAULT_VERSION,
                    rebasePageLabels = true,
                    onDroppedInternalLink = { source, target ->
                        dropped += TocSplitIssue.DroppedExternalLink(source, target)
                    }
                ).assemble()
                sink(TocSplitOutput(part, bytes, TocSplitReport(dropped.size, dropped.toList())))
                true
            } catch (_: Exception) {
                // A single unreadable part must not leave a half-written run.
                false
            }
            if (!delivered) break
            done++
            onProgress(done, parts.size)
        }
        return done
    }
}

/** `/Size` style formatting used by the app's estimate row (T66). */
internal fun formatByteEstimate(bytes: Long): String {
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
