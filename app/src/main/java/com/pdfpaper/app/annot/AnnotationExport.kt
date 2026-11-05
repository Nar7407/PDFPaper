package com.pdfpaper.app.annot

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The two text formats the hub can export (T56). */
enum class AnnotationExportFormat(val label: String, val extension: String) {
    MARKDOWN("Markdown", "md"),
    TEXT("Plain text", "txt")
}

/**
 * Options for an export. The Obsidian flavour is Markdown only: it adds YAML
 * frontmatter and a `[[document#page=N]]` link so the note can be filed in a
 * vault and jump back to the PDF.
 */
data class AnnotationExportOptions(
    val format: AnnotationExportFormat = AnnotationExportFormat.MARKDOWN,
    val obsidian: Boolean = false,
    val includeQuotedText: Boolean = true,
    val includeNotes: Boolean = true
)

/**
 * Renders hub items as Markdown or plain text. Pure and deterministic: the
 * caller passes the export time, which is what makes the golden-file tests
 * possible. Items are grouped by document, then by page.
 */
object AnnotationExport {

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun render(
        items: List<AnnotationItem>,
        options: AnnotationExportOptions = AnnotationExportOptions(),
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().format(dateFormat)
        val grouped = items
            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.pageIndex }, { it.createdAt }))
            .groupBy { it.displayName }
        return when (options.format) {
            AnnotationExportFormat.MARKDOWN -> markdown(grouped, options, date)
            AnnotationExportFormat.TEXT -> text(grouped, date)
        }
    }

    /** A file name for the share sheet or the SAF create dialog. */
    fun fileName(
        items: List<AnnotationItem>,
        format: AnnotationExportFormat,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().format(dateFormat)
        val documents = items.map { it.displayName }.distinct()
        val single = documents.size == 1
        val base = if (single) documents.first().substringBeforeLast('.').ifBlank { "annotations" } else "annotations"
        val safe = base.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "annotations" }
        val suffix = if (single) "$safe-annotations-$date" else "$safe-$date"
        return "$suffix.${format.extension}"
    }

    private fun markdown(
        grouped: Map<String, List<AnnotationItem>>,
        options: AnnotationExportOptions,
        date: String
    ): String {
        val out = StringBuilder()
        val total = grouped.values.sumOf { it.size }
        if (options.obsidian) {
            out.append("---\n")
            out.append("tags:\n  - pdfpaper\n  - annotations\n")
            out.append("source: PDFPaper\n")
            out.append("exported: $date\n")
            out.append("---\n\n")
        }
        out.append("# Annotations\n\n")
        out.append(
            "$date · ${countLabel(total, "annotation")} from " +
                countLabel(grouped.size, "document") + "\n"
        )
        for ((document, rows) in grouped) {
            out.append("\n## ")
            if (options.obsidian) {
                out.append("[[${document}]]")
            } else {
                out.append(document)
            }
            out.append("\n\n")
            for (item in rows) {
                val page = item.pageIndex + 1
                out.append("### ")
                if (options.obsidian) {
                    out.append("[[${document}#page=$page|Page $page]]")
                } else {
                    out.append("Page $page")
                }
                out.append(" · ").append(item.kind.label)
                AnnotationColors.labelFor(item.color)?.let { out.append(" · ").append(it) }
                item.author?.takeIf { it.isNotBlank() }?.let { out.append(" · ").append(it) }
                out.append("\n\n")
                val quote = item.quotedText?.takeIf { it.isNotBlank() && options.includeQuotedText }
                if (quote != null) {
                    if (options.obsidian) {
                        out.append("> [!quote]\n")
                        quote.lineSequence().forEach { out.append("> ").append(it).append("\n") }
                        out.append("\n")
                    } else {
                        quote.lineSequence().forEach { out.append("> ").append(it).append("\n") }
                        out.append("\n")
                    }
                }
                val note = item.noteText?.takeIf { it.isNotBlank() && options.includeNotes }
                if (note != null) {
                    out.append(note.trim()).append("\n\n")
                }
                item.tag?.takeIf { it.isNotBlank() }?.let { out.append("Tag: ").append(it).append("\n\n") }
            }
        }
        out.append("---\n\n_Exported by PDFPaper. The annotation index stays on this device._\n")
        return out.toString()
    }

    private fun text(grouped: Map<String, List<AnnotationItem>>, date: String): String {
        val out = StringBuilder()
        val total = grouped.values.sumOf { it.size }
        out.append("PDFPaper annotations\n")
        out.append("$date\n")
        out.append("${countLabel(total, "annotation")} from ${countLabel(grouped.size, "document")}\n")
        for ((document, rows) in grouped) {
            out.append("\n").append(document).append("\n")
            for (item in rows) {
                val page = item.pageIndex + 1
                out.append("  Page $page [${item.kind.label}]")
                AnnotationColors.labelFor(item.color)?.let { out.append(" · $it") }
                item.author?.takeIf { it.isNotBlank() }?.let { out.append(" · $it") }
                out.append("\n")
                item.quotedText?.takeIf { it.isNotBlank() }?.let { quote ->
                    quote.lineSequence().forEach { out.append("    \"").append(it).append("\"\n") }
                }
                item.noteText?.takeIf { it.isNotBlank() }?.let { note ->
                    note.trim().lineSequence().forEach { out.append("    ").append(it).append("\n") }
                }
                item.tag?.takeIf { it.isNotBlank() }?.let { out.append("    Tag: ").append(it).append("\n") }
            }
        }
        return out.toString()
    }

    private fun countLabel(count: Int, singular: String): String =
        if (count == 1) "1 $singular" else "$count ${singular}s"
}
