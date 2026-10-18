package com.pdfpaper.engine.edit

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor

/**
 * One editable text run on a page (task T16), derived from the T7 text runs
 * (ISO 32000-1, §9.4). [id] is stable for a page and usable in the text edits.
 */
class EditableTextRun(
    /** Zero-based id in reading (content) order; used by the text edits. */
    val id: Int,
    /** Decoded text of the run (may be empty when nothing decodes). */
    val text: String,
    /** Axis-aligned box in page user space. */
    val bounds: PdfRect,
    /** /BaseFont name with any subset prefix removed, or null when absent. */
    val fontName: String?,
    /** Font size from the last Tf, in untransformed text-space units. */
    val fontSize: Double,
    /** Non-stroking colour at the run (§8.6). */
    val color: PdfColor,
    /** True when /Tr 3 or 7 mean the run is not painted (e.g. an OCR layer). */
    val invisible: Boolean
) {
    override fun toString(): String =
        "TextRun($id \"$text\" $fontName $fontSize [${bounds.left},${bounds.bottom},${bounds.right},${bounds.top}])"
}

/** How a run's text was changed when it could not be rewritten in place. */
enum class TextEditStrategy {
    /** The original text-showing operators were rewritten with the run's font. */
    REWRITE,

    /** The run was removed (its operators taken out), drawing nothing in its place. */
    REMOVED,

    /** The run was removed and the replacement drawn with the bundled fallback font. */
    FALLBACK_FONT,

    /** Nothing changed (the run id was not found on the page). */
    NONE
}

/** What happened to one text edit, surfaced so a caller can report it. */
class TextEditReport(
    val pageIndex: Int,
    val runId: Int,
    /** "replace" | "delete" | "move". */
    val operation: String,
    val strategy: TextEditStrategy,
    /** True when the replacement is wider than the text it replaces. */
    val overflow: Boolean = false,
    /** By how much the replacement overflows, in page user units (0 when it fits). */
    val overflowAmount: Double = 0.0,
    /** Extra human-readable detail, or null. */
    val note: String? = null
) {
    override fun toString(): String = buildString {
        append("page ").append(pageIndex + 1).append(' ').append(operation)
        append(" run ").append(runId).append(": ").append(strategy.name.lowercase())
        if (overflow) {
            append(" (overflow ")
            append(String.format(java.util.Locale.ROOT, "%.2f", overflowAmount))
            append("pt)")
        }
        note?.let { append(" — ").append(it) }
    }
}

/** A full save plus the text-edit reports gathered while applying it (T16). */
class SaveResult(val bytes: ByteArray, val textReports: List<TextEditReport>)
