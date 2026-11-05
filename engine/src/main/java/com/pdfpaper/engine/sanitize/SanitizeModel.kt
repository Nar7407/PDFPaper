package com.pdfpaper.engine.sanitize

/**
 * What to do with interactive form data (T29).
 *
 * [FLATTEN] paints the current widget appearances into page content, then
 * removes the form; [CLEAR] removes the form and its widgets without painting;
 * [KEEP] leaves fields and widgets untouched.
 */
enum class FormDataHandling {
    FLATTEN,
    CLEAR,
    KEEP;

    companion object {
        fun parse(value: String?): FormDataHandling = when (value?.trim()?.lowercase()) {
            null, "", "flatten" -> FLATTEN
            "clear", "remove", "drop" -> CLEAR
            "keep", "none" -> KEEP
            else -> throw IllegalArgumentException("Unknown form-data handling '$value'")
        }
    }
}

/** One privacy category reported by [PdfSanitizer]. */
enum class SanitizeItem(val label: String) {
    METADATA("metadata"),
    JAVASCRIPT_ACTIONS("JavaScript/actions"),
    EMBEDDED_FILES("embedded files"),
    ANNOTATIONS("comments/annotations"),
    FORM_DATA("form data"),
    HIDDEN_LAYERS("hidden layers"),
    BOOKMARKS("bookmarks"),
    LINKS("links"),
    THUMBNAILS("page thumbnails"),
    NAMED_DESTINATIONS("named destinations"),
    PREVIOUS_REVISIONS("previous revisions"),
    UNUSED_OBJECTS("unused objects"),
    HIDDEN_TEXT("hidden text")
}

/**
 * Independently switchable T29 removals. Every privacy category starts on;
 * [formData] starts at [FormDataHandling.CLEAR], the strictest of the two
 * required form modes.
 *
 * [removePreviousRevisions] is present for symmetry with the task option list.
 * The engine only produces fresh, full-rewrite files, so an incremental update
 * cannot be retained by a sanitizer; the output is always a full rewrite and
 * the report says how many revisions the source advertised. Turning this flag
 * off does not make the output incremental.
 */
data class SanitizeOptions(
    val removeMetadata: Boolean = true,
    val removeJavaScriptAndActions: Boolean = true,
    val removeEmbeddedFiles: Boolean = true,
    val removeCommentsAndAnnotations: Boolean = true,
    val formData: FormDataHandling = FormDataHandling.CLEAR,
    val removeHiddenLayers: Boolean = true,
    val removeBookmarks: Boolean = true,
    val removeLinks: Boolean = true,
    val removeThumbnails: Boolean = true,
    val removeNamedDestinations: Boolean = true,
    val removePreviousRevisions: Boolean = true,
    val removeUnusedObjects: Boolean = true,
    val removeHiddenText: Boolean = true
) {
    /** True when the form tree and its widget annotations must be dropped. */
    val removesFormData: Boolean get() = formData != FormDataHandling.KEEP
}

/**
 * What a sanitize pass removed, grouped by [SanitizeItem]. Counts are item
 * counts (a metadata dictionary counts as one, each outline item as one), not
 * bytes. Categories with a zero count are omitted from [removed].
 */
class SanitizeReport(
    val options: SanitizeOptions,
    val removed: Map<SanitizeItem, Int>,
    val warnings: List<String> = emptyList(),
    /** Always true: the writer never emits an incremental update. */
    val fullRewrite: Boolean = true
) {

    fun count(item: SanitizeItem): Int = removed[item] ?: 0

    val totalRemoved: Int get() = removed.values.sum()

    /** Human-readable report used by the CLI and the acceptance tests. */
    fun summary(): String {
        val out = StringBuilder()
        out.append("Sanitize report: ").append(totalRemoved).append(" item(s) removed")
        for ((item, count) in removed) {
            if (count <= 0) continue
            out.append('\n').append("  ").append(item.label).append(": ").append(count)
        }
        if (options.formData != FormDataHandling.KEEP) {
            out.append('\n').append("  form mode: ").append(options.formData.name.lowercase())
        }
        out.append('\n').append("  previous revisions: full rewrite")
        if (warnings.isNotEmpty()) {
            out.append('\n').append("Warnings:")
            for (warning in warnings) out.append('\n').append("  ").append(warning)
        }
        return out.toString()
    }
}

/** Successful sanitizer output. [bytes] is a complete, fresh PDF file. */
class SanitizeResult(
    val bytes: ByteArray,
    val report: SanitizeReport
)
