package com.pdfpaper.engine.annot

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.PdfPage

/**
 * Annotation subtypes the engine understands (ISO 32000-1, §12.5.6). Anything
 * else parses as [UNKNOWN] but still keeps its dictionary so a caller can read
 * the raw entries.
 */
enum class AnnotationType {
    TEXT, LINK, FREE_TEXT, LINE, SQUARE, CIRCLE, POLYGON, POLYLINE,
    HIGHLIGHT, UNDERLINE, SQUIGGLY, STRIKEOUT, STAMP, CARET, INK, POPUP,
    FILE_ATTACHMENT, SOUND, MOVIE, WIDGET, SCREEN, PRINTER_MARK, TRAP_NET,
    WATERMARK, REDACT, UNKNOWN;

    companion object {
        fun from(subtype: String): AnnotationType = when (subtype) {
            "Text" -> TEXT
            "Link" -> LINK
            "FreeText" -> FREE_TEXT
            "Line" -> LINE
            "Square" -> SQUARE
            "Circle" -> CIRCLE
            "Polygon" -> POLYGON
            "PolyLine" -> POLYLINE
            "Highlight" -> HIGHLIGHT
            "Underline" -> UNDERLINE
            "Squiggly" -> SQUIGGLY
            "StrikeOut" -> STRIKEOUT
            "Stamp" -> STAMP
            "Caret" -> CARET
            "Ink" -> INK
            "Popup" -> POPUP
            "FileAttachment" -> FILE_ATTACHMENT
            "Sound" -> SOUND
            "Movie" -> MOVIE
            "Widget" -> WIDGET
            "Screen" -> SCREEN
            "PrinterMark" -> PRINTER_MARK
            "TrapNet" -> TRAP_NET
            "Watermark" -> WATERMARK
            "Redact" -> REDACT
            else -> UNKNOWN
        }
    }
}

/**
 * One quadrilateral of /QuadPoints (§12.5.6.10, Table 164): the four corners are
 * stored in the spec's order — upper-left, upper-right, lower-left, lower-right.
 */
class QuadPoint(
    val x1: Double, val y1: Double,
    val x2: Double, val y2: Double,
    val x3: Double, val y3: Double,
    val x4: Double, val y4: Double
) {
    /** Axis-aligned bounds of the (possibly skewed) quadrilateral. */
    fun bounds(): PdfRect = PdfRect(
        minOf(x1, x2, x3, x4), minOf(y1, y2, y3, y4),
        maxOf(x1, x2, x3, x4), maxOf(y1, y2, y3, y4)
    )

    /** Points in order: upper-left, upper-right, lower-left, lower-right. */
    fun points(): DoubleArray = doubleArrayOf(x1, y1, x2, y2, x3, y3, x4, y4)
}

/**
 * One annotation (§12.5). [dict] and [ref] give access to the raw object; the
 * typed fields are the ones the reader and renderer need. Missing values are
 * null rather than zero so "absent" and "explicitly 0" stay distinguishable.
 */
class PdfAnnotation(
    val type: AnnotationType,
    val subtype: String,
    val rect: PdfRect?,
    val contents: String?,
    val title: String?,
    /** /C — annotation colour (§12.5.2). */
    val color: PdfColor?,
    /** /InteriorColor /IC. */
    val interiorColor: PdfColor?,
    /** /F annotation flags (§12.5.3, Table 165). */
    val flags: Int,
    /** /Border [h v w] width, or /BS /W; null when neither is present. */
    val borderWidth: Double?,
    /** /QuadPoints grouped into quadrilaterals (§12.5.6.10). */
    val quadPoints: List<QuadPoint>,
    /** /InkList — one flat `[x y x y ...]` polyline per stroke (§12.5.6.13). */
    val inkLists: List<List<Double>>,
    /** /AP /N normal appearance form XObject, when present (§12.5.5). */
    val appearance: PdfStream?,
    /** /AS appearance state (§12.5.5). */
    val appearanceState: String?,
    val dict: PdfDict,
    val ref: PdfRef?
) {

    /** Flags that suppress display entirely (§12.5.3, Table 165). */
    val hidden: Boolean get() = flags and (INVISIBLE or HIDDEN or NO_VIEW) != 0

    /** True when the annotation has a border a reader would draw. */
    val hasVisibleBorder: Boolean get() = (borderWidth ?: 0.0) > 0.0

    /** Quadrilaterals as axis-aligned boxes; empty when there are no /QuadPoints. */
    fun quadBounds(): List<PdfRect> = quadPoints.map { it.bounds() }

    override fun toString(): String = "PdfAnnotation($subtype, rect=$rect)"

    companion object {
        const val INVISIBLE = 1
        const val HIDDEN = 2
        const val PRINT = 4
        const val NO_ZOOM = 8
        const val NO_ROTATE = 16
        const val NO_VIEW = 32
        const val READ_ONLY = 64
        const val LOCKED = 128
        const val TOGGLE_NO_VIEW = 256
        const val LOCKED_CONTENTS = 512
    }
}

/**
 * Every annotation on this page, in /Annots order (§12.5.2). Entries that are
 * not dictionaries (or resolve to nothing) are skipped; malformed values inside
 * one annotation never remove the others.
 */
fun PdfPage.annotations(): List<PdfAnnotation> {
    val doc = document
    val array = (resolve(doc, dict["Annots"] as? PdfArray ?: inheritedAnnots())) as? PdfArray ?: return emptyList()
    val out = ArrayList<PdfAnnotation>(array.items.size)
    for (item in array.items) {
        val obj = resolve(doc, item) ?: continue
        val annot = parseAnnotation(obj, directRef(item)) { o -> resolve(doc, o) }
        if (annot != null) out += annot
    }
    return out
}

/** /Annots is inheritable (§7.7.3.4, Table 30); walk /Parent like PdfPage does. */
private fun PdfPage.inheritedAnnots(): PdfObject? {
    var node: PdfDict? = dict
    val visited = HashSet<Int>()
    while (node != null) {
        node.entries[PdfName("Annots")]?.let { return it }
        val parent = node["Parent"] as? PdfRef ?: return null
        if (!visited.add(parent.objNum)) return null
        node = document.resolve(parent) as? PdfDict
    }
    return null
}

internal fun parseAnnotation(
    obj: PdfObject,
    ref: PdfRef?,
    resolve: (PdfObject?) -> PdfObject?
): PdfAnnotation? {
    val dict = obj as? PdfDict ?: return null
    // /Type is normally /Annot but some writers omit it; the /Subtype decides.
    val subtype = ((dict["Subtype"] as? PdfName)?.name).orEmpty()
    val appearanceDict = resolve(dict["AP"]) as? PdfDict
    val normal = resolve(appearanceDict?.get("N"))
    val stateName = (dict["AS"] as? PdfName)?.name
    val appearance = normalAppearance(resolve, normal, stateName)
    return PdfAnnotation(
        type = AnnotationType.from(subtype),
        subtype = subtype,
        rect = PdfRect.of(dict["Rect"] as? PdfArray),
        contents = (dict["Contents"] as? PdfString)?.text(),
        title = (dict["T"] as? PdfString)?.text(),
        color = colorOf(dict["C"]),
        interiorColor = colorOf(dict["IC"]),
        flags = (dict["F"] as? PdfInteger)?.value?.toInt() ?: 0,
        borderWidth = borderWidth(dict),
        quadPoints = quadPoints(dict["QuadPoints"] as? PdfArray),
        inkLists = inkLists(dict["InkList"] as? PdfArray),
        appearance = appearance,
        appearanceState = stateName,
        dict = dict,
        ref = ref
    )
}

/** /AP /N may be a form XObject or a state dictionary keyed by /AS (§12.5.5). */
private fun normalAppearance(
    resolve: (PdfObject?) -> PdfObject?,
    normal: PdfObject?,
    state: String?
): PdfStream? = when (normal) {
    is PdfStream -> normal
    is PdfDict -> {
        val chosen = resolve(state?.let { normal[it] } ?: normal.entries.values.firstOrNull())
        chosen as? PdfStream
    }
    else -> null
}

private fun borderWidth(dict: PdfDict): Double? {
    (dict["BS"] as? PdfDict)?.let { bs ->
        number(bs["W"])?.let { return it }
    }
    val border = dict["Border"] as? PdfArray
    return number(border?.items?.getOrNull(2))
}

private fun quadPoints(array: PdfArray?): List<QuadPoint> {
    val values = array?.items ?: return emptyList()
    if (values.size < 8) return emptyList()
    val numbers = values.map { number(it) ?: return emptyList() }
    val out = ArrayList<QuadPoint>(numbers.size / 8)
    var i = 0
    while (i + 7 < numbers.size) {
        out += QuadPoint(
            numbers[i], numbers[i + 1], numbers[i + 2], numbers[i + 3],
            numbers[i + 4], numbers[i + 5], numbers[i + 6], numbers[i + 7]
        )
        i += 8
    }
    return out
}

private fun inkLists(array: PdfArray?): List<List<Double>> {
    val items = array?.items ?: return emptyList()
    val out = ArrayList<List<Double>>(items.size)
    for (item in items) {
        val stroke = item as? PdfArray ?: continue
        val points = ArrayList<Double>(stroke.items.size)
        for (v in stroke.items) points += number(v) ?: return emptyList()
        out += points
    }
    return out
}

/** /C, /IC component array → device colour (§12.5.2; 1, 3 or 4 components). */
private fun colorOf(obj: PdfObject?): PdfColor? {
    val values = (obj as? PdfArray)?.items ?: return null
    val c = values.map { number(it) ?: return null }
    return when (c.size) {
        1 -> PdfColor(c[0], c[0], c[0])
        3 -> PdfColor(c[0], c[1], c[2])
        4 -> {
            // Naive CMYK → RGB, matching the T8 approximation (§8.6.4.4).
            PdfColor(
                (1 - c[0]) * (1 - c[3]),
                (1 - c[1]) * (1 - c[3]),
                (1 - c[2]) * (1 - c[3])
            )
        }
        else -> null
    }
}

private fun number(obj: PdfObject?): Double? = when (obj) {
    is PdfInteger -> obj.value.toDouble()
    is com.pdfpaper.engine.model.PdfReal -> obj.value
    else -> null
}

private fun resolve(doc: PdfDocument, obj: PdfObject?): PdfObject? = when (obj) {
    null -> null
    is PdfRef -> doc.resolve(obj)
    else -> obj
}

private fun directRef(obj: PdfObject): PdfRef? = obj as? PdfRef
