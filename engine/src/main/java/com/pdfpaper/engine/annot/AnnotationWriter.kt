package com.pdfpaper.engine.annot

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfSerializer
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone

/**
 * A new annotation to add to a page when saving (ISO 32000-1, §12.5.6):
 * a text markup highlight, a freehand ink stroke set, or a text note.
 * [pageIndex] is a zero-based index into the source document's pages.
 */
sealed interface AnnotationSpec {
    val pageIndex: Int
}

/** /Highlight built from page-space rectangles (§12.5.6.10). */
class HighlightSpec(
    override val pageIndex: Int,
    val rects: List<PdfRect>,
    val color: PdfColor = DEFAULT_HIGHLIGHT_COLOR,
    val contents: String? = null,
    val title: String? = null
) : AnnotationSpec {
    companion object {
        val DEFAULT_HIGHLIGHT_COLOR = PdfColor(1.0, 0.92, 0.23)
    }
}

/** /Ink built from freehand polylines (§12.5.6.13). Each stroke is `x0 y0 x1 y1 ...`. */
class InkSpec(
    override val pageIndex: Int,
    val strokes: List<List<Double>>,
    val color: PdfColor = PdfColor.BLACK,
    val width: Double = 2.0,
    val contents: String? = null,
    val title: String? = null
) : AnnotationSpec

/** /Text sticky note (§12.5.6.4). */
class NoteSpec(
    override val pageIndex: Int,
    val rect: PdfRect,
    val contents: String,
    val color: PdfColor = PdfColor(1.0, 0.87, 0.0),
    val title: String? = null,
    val open: Boolean = false
) : AnnotationSpec

/**
 * Builds the dictionaries and appearance streams for [AnnotationSpec]s
 * (§12.5.5 appearance streams, §12.5.6 subtypes). Streams are self-contained:
 * a highlight carries its own /ExtGState blend, an ink annotation its /BS, a
 * note its icon drawing, so any viewer can render them without guessing.
 */
internal class AnnotationWriter(private val builder: PdfDocumentBuilder) {

    private val date: PdfString by lazy { pdfDate() }

    /** Adds one annotation; returns its reference, or null for an empty spec. */
    fun build(spec: AnnotationSpec, pageRef: PdfRef): PdfRef? = when (spec) {
        is HighlightSpec -> buildHighlight(spec, pageRef)
        is InkSpec -> buildInk(spec, pageRef)
        is NoteSpec -> buildNote(spec, pageRef)
    }

    private fun buildHighlight(spec: HighlightSpec, pageRef: PdfRef): PdfRef? {
        val rects = spec.rects.filter { !it.isEmpty }
        val box = PdfRect.union(rects) ?: return null
        val quad = ArrayList<PdfObject>(rects.size * 8)
        for (r in rects) {
            // Table 164 order: upper-left, upper-right, lower-left, lower-right.
            quad += real(r.left); quad += real(r.top)
            quad += real(r.right); quad += real(r.top)
            quad += real(r.left); quad += real(r.bottom)
            quad += real(r.right); quad += real(r.bottom)
        }
        val w = box.width
        val h = box.height
        val content = buildString {
            append("q /GS0 gs ")
            append(rgb(spec.color))
            for (r in rects) {
                append(' ')
                append(num(r.left - box.left)); append(' '); append(num(r.bottom - box.bottom))
                append(' '); append(num(r.width)); append(' '); append(num(r.height))
                append(" re")
            }
            append(" f Q")
        }
        val resources = dict(
            "ExtGState" to dict(
                "GS0" to dict("ca" to real(0.5), "CA" to real(0.5), "BM" to PdfName("Multiply"))
            )
        )
        val ap = form(listOf(0.0, 0.0, w, h), resources, content)
        val annot = LinkedHashMap<PdfName, PdfObject>()
        common(annot, "Highlight", box, spec.contents, spec.title, pageRef)
        annot[PdfName("C")] = colorArray(spec.color)
        annot[PdfName("CA")] = real(0.5)
        annot[PdfName("QuadPoints")] = PdfArray(quad)
        annot[PdfName("AP")] = dict("N" to ap)
        return builder.add(PdfDict(annot))
    }

    private fun buildInk(spec: InkSpec, pageRef: PdfRef): PdfRef? {
        val strokes = spec.strokes.filter { it.size >= 4 && it.size % 2 == 0 }
        if (strokes.isEmpty()) return null
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (stroke in strokes) {
            var i = 0
            while (i + 1 < stroke.size) {
                minX = minOf(minX, stroke[i]); maxX = maxOf(maxX, stroke[i])
                minY = minOf(minY, stroke[i + 1]); maxY = maxOf(maxY, stroke[i + 1])
                i += 2
            }
        }
        val pad = spec.width / 2.0 + 1.0
        val box = PdfRect(minX - pad, minY - pad, maxX + pad, maxY + pad)
        val inkList = ArrayList<PdfObject>(strokes.size)
        for (stroke in strokes) {
            inkList += PdfArray(stroke.map { real(it) })
        }
        val content = buildString {
            append("q ")
            append(strokeRgb(spec.color))
            append(' '); append(num(spec.width)); append(" w 1 J 1 j")
            for (stroke in strokes) {
                var i = 0
                while (i + 1 < stroke.size) {
                    val x = stroke[i] - box.left
                    val y = stroke[i + 1] - box.bottom
                    append(' '); append(num(x)); append(' '); append(num(y))
                    append(if (i == 0) " m" else " l")
                    i += 2
                }
                append(" S")
            }
            append(" Q")
        }
        val ap = form(listOf(0.0, 0.0, box.width, box.height), null, content)
        val annot = LinkedHashMap<PdfName, PdfObject>()
        common(annot, "Ink", box, spec.contents, spec.title, pageRef)
        annot[PdfName("C")] = colorArray(spec.color)
        annot[PdfName("InkList")] = PdfArray(inkList)
        annot[PdfName("BS")] = dict(
            "W" to real(spec.width),
            "S" to PdfName("S")
        )
        annot[PdfName("AP")] = dict("N" to ap)
        return builder.add(PdfDict(annot))
    }

    private fun buildNote(spec: NoteSpec, pageRef: PdfRef): PdfRef? {
        val box = if (spec.rect.isEmpty) PdfRect(spec.rect.left, spec.rect.bottom, spec.rect.left + 20.0, spec.rect.bottom + 20.0)
        else spec.rect
        val w = box.width
        val h = box.height
        val fold = minOf(w, h) * 0.35
        val content = buildString {
            append("q ")
            append(rgb(spec.color))
            append(" 0 0 "); append(num(w)); append(' '); append(num(h)); append(" re f")
            append(" 0 0 0 RG 0.75 w 0.375 0.375 "); append(num(w - 0.75)); append(' '); append(num(h - 0.75))
            append(" re S")
            append(" 0.75 w "); append(num(w - fold)); append(' '); append(num(h))
            append(" m "); append(num(w - fold)); append(' '); append(num(h - fold))
            append(" l "); append(num(w)); append(' '); append(num(h - fold)); append(" l S Q")
        }
        val ap = form(listOf(0.0, 0.0, w, h), null, content)
        val annot = LinkedHashMap<PdfName, PdfObject>()
        common(annot, "Text", box, spec.contents, spec.title, pageRef)
        annot[PdfName("Name")] = PdfName("Note")
        annot[PdfName("C")] = colorArray(spec.color)
        annot[PdfName("Open")] = com.pdfpaper.engine.model.PdfBoolean(spec.open)
        annot[PdfName("AP")] = dict("N" to ap)
        return builder.add(PdfDict(annot))
    }

    /** Entries shared by every new annotation (§12.5.2, Table 164). */
    private fun common(
        annot: LinkedHashMap<PdfName, PdfObject>,
        subtype: String,
        box: PdfRect,
        contents: String?,
        title: String?,
        pageRef: PdfRef
    ) {
        annot[PdfName("Type")] = PdfName("Annot")
        annot[PdfName("Subtype")] = PdfName(subtype)
        annot[PdfName("Rect")] = PdfArray(
            listOf(real(box.left), real(box.bottom), real(box.right), real(box.top))
        )
        annot[PdfName("F")] = PdfInteger(PdfAnnotation.PRINT.toLong())
        annot[PdfName("M")] = date
        annot[PdfName("P")] = pageRef
        if (contents != null) annot[PdfName("Contents")] = textString(contents)
        if (title != null) annot[PdfName("T")] = textString(title)
    }

    /** A form XObject (§8.10) with the given /BBox and content, no /Matrix. */
    private fun form(bbox: List<Double>, resources: PdfDict?, content: String): PdfRef {
        val entries = LinkedHashMap<PdfName, PdfObject>()
        entries[PdfName("Type")] = PdfName("XObject")
        entries[PdfName("Subtype")] = PdfName("Form")
        entries[PdfName("FormType")] = PdfInteger(1)
        entries[PdfName("BBox")] = PdfArray(bbox.map { real(it) })
        if (resources != null) entries[PdfName("Resources")] = resources
        // /Length is rewritten by the serialiser from the real byte count.
        entries[PdfName("Length")] = PdfInteger(0)
        val bytes = content.toByteArray(StandardCharsets.ISO_8859_1)
        return builder.addStream(PdfDict(entries), bytes)
    }

    private fun rgb(c: PdfColor): String =
        "${num(c.red)} ${num(c.green)} ${num(c.blue)} rg"

    private fun strokeRgb(c: PdfColor): String =
        "${num(c.red)} ${num(c.green)} ${num(c.blue)} RG"

    private fun colorArray(c: PdfColor): PdfArray = PdfArray(listOf(real(c.red), real(c.green), real(c.blue)))

    /** Compact PDF number for appearance-stream content (§7.3.3). */
    private fun num(value: Double): String = PdfSerializer.formatReal(value)

    private fun real(value: Double): PdfReal = PdfReal(value)

    private fun dict(vararg entries: Pair<String, PdfObject>): PdfDict =
        PdfDict(entries.associate { PdfName(it.first) to it.second })

    /** PDF text string (§7.9.2.2): Latin-1 when it round-trips, else UTF-16BE + BOM. */
    private fun textString(text: String): PdfString {
        val latin = text.toByteArray(StandardCharsets.ISO_8859_1)
        if (String(latin, StandardCharsets.ISO_8859_1) == text) return PdfString(latin)
        val out = ByteArrayOutputStream()
        out.write(0xFE)
        out.write(0xFF)
        out.write(text.toByteArray(StandardCharsets.UTF_16BE))
        return PdfString(out.toByteArray())
    }

    private fun pdfDate(): PdfString {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val s = "D:%04d%02d%02d%02d%02d%02dZ".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)
        )
        return PdfString(s.toByteArray(StandardCharsets.US_ASCII))
    }
}
