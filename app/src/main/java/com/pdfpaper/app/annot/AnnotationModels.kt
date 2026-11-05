package com.pdfpaper.app.annot

import com.pdfpaper.engine.annot.AnnotationType
import com.pdfpaper.engine.annot.PdfAnnotation
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import kotlin.math.roundToInt

/**
 * T56: what the Annotations hub shows. A bookmark is the app's own row (a page,
 * a label and a colour); every other kind is a real PDF annotation that can be
 * edited and deleted in the file.
 */
enum class AnnotationKind(val label: String) {
    HIGHLIGHT("Highlight"),
    UNDERLINE("Underline"),
    STRIKEOUT("Strikeout"),
    NOTE("Note"),
    INK("Ink"),
    STAMP("Stamp"),
    FREE_TEXT("Free text"),
    BOOKMARK("Bookmark");

    val isBookmark: Boolean get() = this == BOOKMARK
    val isPdf: Boolean get() = this != BOOKMARK
}

/** A colour the hub and the viewer offer for a bookmark or a markup annotation. */
object AnnotationColors {

    data class Swatch(val label: String, val hex: String)

    val all: List<Swatch> = listOf(
        Swatch("Yellow", "#F5D547"),
        Swatch("Green", "#5BD07A"),
        Swatch("Blue", "#4A9BE8"),
        Swatch("Red", "#E8555F"),
        Swatch("Purple", "#A57BE0"),
        Swatch("Orange", "#F09A3E")
    )

    val default: String get() = all.first().hex

    fun fromHex(hex: String): PdfColor? {
        val cleaned = hex.removePrefix("#")
        if (cleaned.length != 6) return null
        val value = cleaned.toIntOrNull(16) ?: return null
        return PdfColor(
            ((value shr 16) and 0xFF) / 255.0,
            ((value shr 8) and 0xFF) / 255.0,
            (value and 0xFF) / 255.0
        )
    }

    fun of(color: PdfColor?): String? {
        if (color == null) return null
        fun channel(value: Double): Int =
            (value.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        return "#%02X%02X%02X".format(channel(color.red), channel(color.green), channel(color.blue))
    }

    /** Android ARGB for a hex swatch; grey when the value is missing. */
    fun argb(hex: String?, fallback: Int = 0xFF9E9E9E.toInt()): Int {
        if (hex == null) return fallback
        val cleaned = hex.removePrefix("#")
        if (cleaned.length != 6) return fallback
        val value = cleaned.toIntOrNull(16) ?: return fallback
        return (0xFF000000.toInt()) or value
    }

    /** The swatch name of a stored colour, for an export or a list row. */
    fun labelFor(hex: String?): String? =
        all.firstOrNull { it.hex.equals(hex, ignoreCase = true) }?.label
}

/** The kind a PDF annotation maps to, or null when the hub does not list it. */
fun annotationKindOf(annotation: PdfAnnotation): AnnotationKind? = when (annotation.type) {
    AnnotationType.HIGHLIGHT -> AnnotationKind.HIGHLIGHT
    AnnotationType.UNDERLINE, AnnotationType.SQUIGGLY -> AnnotationKind.UNDERLINE
    AnnotationType.STRIKEOUT -> AnnotationKind.STRIKEOUT
    AnnotationType.TEXT -> AnnotationKind.NOTE
    AnnotationType.INK -> AnnotationKind.INK
    AnnotationType.STAMP -> AnnotationKind.STAMP
    AnnotationType.FREE_TEXT -> AnnotationKind.FREE_TEXT
    else -> null
}

/** "l,b,r,t" in PDF user space, the form the index stores. */
fun formatBounds(rect: PdfRect?): String? {
    if (rect == null || rect.isEmpty) return null
    return "${rect.left},${rect.bottom},${rect.right},${rect.top}"
}

fun parseBounds(text: String?): PdfRect? {
    val parts = text?.split(',') ?: return null
    if (parts.size != 4) return null
    val values = parts.map { it.trim().toDoubleOrNull() ?: return null }
    return PdfRect(values[0], values[1], values[2], values[3])
}

/** One hub row, with the document it belongs to. */
data class AnnotationItem(
    val id: Long,
    val documentId: Long,
    val documentUri: String,
    val displayName: String,
    val kind: AnnotationKind,
    val pageIndex: Int,
    val color: String?,
    val author: String?,
    val createdAt: Long,
    val modifiedAt: Long?,
    val quotedText: String?,
    val noteText: String?,
    val bounds: String?,
    val objectNumber: Int?,
    val label: String?,
    val tag: String?
) {
    val time: Long get() = modifiedAt ?: createdAt

    /** The text the hub searches, copies and exports for this item. */
    val text: String
        get() = listOfNotNull(
            label?.takeIf { it.isNotBlank() },
            quotedText?.takeIf { it.isNotBlank() },
            noteText?.takeIf { it.isNotBlank() }
        ).joinToString(if (quotedText.isNullOrBlank()) "" else "\n")
}

/** One document the annotation index knows about. */
data class AnnotationDocument(
    val id: Long,
    val fingerprint: String,
    val documentUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val pageCount: Int,
    val indexedAt: Long,
    val source: String
)

/** What to show: no kinds means every kind, a null colour every colour. */
data class AnnotationFilter(
    val kinds: Set<AnnotationKind> = emptySet(),
    val color: String? = null,
    val documentId: Long? = null,
    val date: AnnotationDateFilter = AnnotationDateFilter.ANY,
    val query: String = ""
)

enum class AnnotationDateFilter(val label: String) {
    ANY("Any date"),
    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    THIS_YEAR("This year")
}

data class AnnotationStats(
    val documents: Int,
    val annotations: Int,
    val bookmarks: Int,
    val byKind: Map<AnnotationKind, Int>
) {
    val total: Int get() = annotations
}

/** The order the hub shows its groups in. */
enum class AnnotationGrouping(val label: String) {
    DOCUMENT("By document"),
    DATE("By date")
}

/** One visible heading in the hub with the rows under it. */
data class AnnotationGroupUi(val title: String, val items: List<AnnotationItem>)

/**
 * Groups hub rows for display. By document the heading is the file name and
 * the order is alphabetical; by date the heading is the local calendar day
 * (`2026-10-25`) and the newest day comes first.
 */
fun groupAnnotations(
    items: List<AnnotationItem>,
    grouping: AnnotationGrouping,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
): List<AnnotationGroupUi> = when (grouping) {
    AnnotationGrouping.DOCUMENT -> items
        .groupBy { it.displayName }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .map { (name, rows) ->
            AnnotationGroupUi(name, rows.sortedWith(compareBy({ it.pageIndex }, { it.createdAt })))
        }
    AnnotationGrouping.DATE -> items
        .groupBy {
            java.time.Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate().toString()
        }
        .entries
        .sortedByDescending { it.key }
        .map { (day, rows) -> AnnotationGroupUi(day, rows.sortedByDescending { it.time }) }
}

