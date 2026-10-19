package com.pdfpaper.engine.annot

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfBoolean
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.stamp.StampPainter
import com.pdfpaper.engine.stamp.StampText
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfSerializer
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * A new annotation to add to a page when saving (ISO 32000-1, §12.5.6):
 * a text markup highlight or underline, a freehand ink stroke set, a text
 * note, a stamp, a comment reply or a review state change. [pageIndex] is a
 * zero-based index into the source document's pages.
 */
sealed interface AnnotationSpec {
    val pageIndex: Int
}

/**
 * /Highlight built from page-space rectangles (§12.5.6.10). [title] is the
 * author (/T), [subject] the short subject (/Subj); with [popup] the writer
 * also creates the /Popup window that shows [contents] (§12.5.6.14).
 */
class HighlightSpec(
    override val pageIndex: Int,
    val rects: List<PdfRect>,
    val color: PdfColor = DEFAULT_HIGHLIGHT_COLOR,
    val contents: String? = null,
    val title: String? = null,
    val subject: String? = null,
    val open: Boolean = false,
    val popup: Boolean = false
) : AnnotationSpec {
    companion object {
        val DEFAULT_HIGHLIGHT_COLOR = PdfColor(1.0, 0.92, 0.23)
    }
}

/** /Underline built from page-space rectangles (§12.5.6.10), drawn at [width] points. */
class UnderlineSpec(
    override val pageIndex: Int,
    val rects: List<PdfRect>,
    val color: PdfColor = DEFAULT_UNDERLINE_COLOR,
    val width: Double = 1.0,
    val contents: String? = null,
    val title: String? = null,
    val subject: String? = null,
    val open: Boolean = false,
    val popup: Boolean = false
) : AnnotationSpec {
    companion object {
        /** Acrobat's default underline colour. */
        val DEFAULT_UNDERLINE_COLOR = PdfColor(1.0, 0.0, 0.0)
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
    val color: PdfColor = DEFAULT_NOTE_COLOR,
    val title: String? = null,
    val open: Boolean = false,
    val subject: String? = null,
    val popup: Boolean = false
) : AnnotationSpec {
    companion object {
        val DEFAULT_NOTE_COLOR = PdfColor(1.0, 0.87, 0.0)
    }
}

/**
 * A reply to an existing annotation, linked with /IRT and /RT (§12.5.6.2,
 * Table 172). [targetObjNum] is the object number of the annotation being
 * replied to; the reply is written on [pageIndex], which must be the page the
 * target is on. The reply copies the target's colour unless [color] is set.
 */
class ReplySpec(
    override val pageIndex: Int,
    val targetObjNum: Int,
    val contents: String,
    val title: String? = null,
    val subject: String? = null,
    val color: PdfColor? = null,
    val replyType: ReplyType = ReplyType.REPLY
) : AnnotationSpec

/**
 * A review state change for an existing comment (§12.5.6.3): a hidden text
 * annotation with /IRT, /State and /StateModel /Review. [targetObjNum] must be
 * on [pageIndex] and names the original annotation the state applies to.
 */
class StatusSpec(
    override val pageIndex: Int,
    val targetObjNum: Int,
    val status: CommentStatus,
    val title: String? = null
) : AnnotationSpec

private const val POPUP_WIDTH = 216.0
private const val POPUP_HEIGHT = 108.0
private const val REPLY_SIZE = 20.0
private val REPLY_FALLBACK_COLOR = PdfColor(1.0, 0.87, 0.0)

/**
 * Builds the dictionaries and appearance streams for [AnnotationSpec]s
 * (§12.5.5 appearance streams, §12.5.6 subtypes). Streams are self-contained:
 * a highlight carries its own /ExtGState blend, an underline a rule, an ink
 * annotation its /BS, a note (and a reply) its icon drawing, a stamp its
 * border, colours, text, opacity and rotation, so any viewer can render them
 * without guessing. Popup windows carry no appearance stream (§12.5.6.14).
 */
internal class AnnotationWriter(private val builder: PdfDocumentBuilder) {

    private val date: PdfString by lazy { PdfString(PdfDate.now().toByteArray(StandardCharsets.US_ASCII)) }

    /**
     * Adds the annotation(s) for [spec] to page [pageRef]; returns their
     * references in /Annots order — a comment and its popup, when it has one.
     * [pageBox] is the page's crop box (used to place popups); [resolveAnnot]
     * maps a source annotation object number to its reference in the output
     * document, which replies and state changes need.
     */
    fun build(
        spec: AnnotationSpec,
        pageRef: PdfRef,
        pageBox: PdfRect? = null,
        resolveAnnot: (Int) -> PdfRef? = { null }
    ): List<PdfRef> = when (spec) {
        is HighlightSpec -> buildHighlight(spec, pageRef, pageBox)
        is UnderlineSpec -> buildUnderline(spec, pageRef, pageBox)
        is InkSpec -> listOfNotNull(buildInk(spec, pageRef))
        is NoteSpec -> buildNote(spec, pageRef, pageBox)
        is ReplySpec -> buildReply(spec, pageRef, resolveAnnot)
        is StatusSpec -> buildStatus(spec, pageRef, resolveAnnot)
        is StampSpec -> listOfNotNull(buildStamp(spec, pageRef))
    }

    private fun buildHighlight(spec: HighlightSpec, pageRef: PdfRef, pageBox: PdfRect?): List<PdfRef> {
        val rects = spec.rects.filter { !it.isEmpty }
        val box = PdfRect.union(rects) ?: return emptyList()
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
        common(annot, "Highlight", box, spec.contents, spec.title, pageRef, spec.subject, creation = true)
        annot[PdfName("C")] = colorArray(spec.color)
        annot[PdfName("CA")] = real(0.5)
        annot[PdfName("QuadPoints")] = PdfArray(quadPoints(rects))
        annot[PdfName("AP")] = dict("N" to ap)
        return addMarkup(annot, box, pageRef, pageBox, spec.popup, spec.open)
    }

    private fun buildUnderline(spec: UnderlineSpec, pageRef: PdfRef, pageBox: PdfRect?): List<PdfRef> {
        val rects = spec.rects.filter { !it.isEmpty }
        val box = PdfRect.union(rects) ?: return emptyList()
        // The rule sits on the lower edge of each rectangle (§12.5.6.10).
        val content = buildString {
            append("q ")
            append(strokeRgb(spec.color))
            append(' '); append(num(spec.width)); append(" w")
            for (r in rects) {
                val y = num(r.bottom - box.bottom)
                append(' '); append(num(r.left - box.left)); append(' '); append(y); append(" m")
                append(' '); append(num(r.right - box.left)); append(' '); append(y); append(" l")
            }
            append(" S Q")
        }
        val ap = form(listOf(0.0, 0.0, box.width, box.height), null, content)
        val annot = LinkedHashMap<PdfName, PdfObject>()
        common(annot, "Underline", box, spec.contents, spec.title, pageRef, spec.subject, creation = true)
        annot[PdfName("C")] = colorArray(spec.color)
        annot[PdfName("QuadPoints")] = PdfArray(quadPoints(rects))
        annot[PdfName("AP")] = dict("N" to ap)
        return addMarkup(annot, box, pageRef, pageBox, spec.popup, spec.open)
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

    private fun buildNote(spec: NoteSpec, pageRef: PdfRef, pageBox: PdfRect?): List<PdfRef> {
        val box = if (spec.rect.isEmpty) PdfRect(spec.rect.left, spec.rect.bottom, spec.rect.left + 20.0, spec.rect.bottom + 20.0)
        else spec.rect
        val ap = form(listOf(0.0, 0.0, box.width, box.height), null, noteIcon(box.width, box.height, spec.color))
        val annot = LinkedHashMap<PdfName, PdfObject>()
        common(annot, "Text", box, spec.contents, spec.title, pageRef, spec.subject, creation = true)
        annot[PdfName("Name")] = PdfName("Note")
        annot[PdfName("C")] = colorArray(spec.color)
        annot[PdfName("Open")] = PdfBoolean(spec.open)
        annot[PdfName("AP")] = dict("N" to ap)
        return addMarkup(annot, box, pageRef, pageBox, spec.popup, spec.open)
    }

    /**
     * A reply is a text annotation over the upper-left corner of the target
     * (§12.5.6.2). Its icon is drawn by [noteIcon]; viewers list it inside the
     * target's thread rather than as a separate comment.
     */
    private fun buildReply(spec: ReplySpec, pageRef: PdfRef, resolveAnnot: (Int) -> PdfRef?): List<PdfRef> {
        val target = resolveAnnot(spec.targetObjNum) ?: return emptyList()
        val targetDict = builder.get(target.objNum) as? PdfDict
        val box = replyBox(PdfRect.of(targetDict?.get("Rect") as? PdfArray))
        val color = spec.color ?: annotColor(targetDict)
        val ap = form(
            listOf(0.0, 0.0, box.width, box.height),
            null,
            noteIcon(box.width, box.height, color ?: REPLY_FALLBACK_COLOR)
        )
        val annot = LinkedHashMap<PdfName, PdfObject>()
        common(
            annot, "Text", box, spec.contents, spec.title, pageRef, spec.subject, creation = true,
            flags = PdfAnnotation.PRINT or PdfAnnotation.NO_ZOOM or PdfAnnotation.NO_ROTATE
        )
        annot[PdfName("Name")] = PdfName("Comment")
        if (color != null) annot[PdfName("C")] = colorArray(color)
        annot[PdfName("IRT")] = target
        annot[PdfName("RT")] = PdfName(spec.replyType.pdfName)
        annot[PdfName("AP")] = dict("N" to ap)
        return listOf(builder.add(PdfDict(annot)))
    }

    /**
     * A /Stamp (§12.5.6.7) painted by [StampPainter]. The appearance is
     * self-contained (border, colours, text, picture, opacity and rotation),
     * so a viewer renders it without interpreting the annotation's looks.
     * Dynamic designs add their "By <author> / at <date>" line here, at
     * placement time, and say so in /Contents for the comment list.
     */
    private fun buildStamp(spec: StampSpec, pageRef: PdfRef): PdfRef? {
        val placed = spec.timestamp ?: System.currentTimeMillis()
        val lines = if (spec.design.dynamic) StampText.lines(spec.author, placed) else emptyList()
        val appearance = StampPainter.paint(
            builder, spec.design, spec.size, spec.rotation, spec.opacity, lines, spec.image
        ) ?: return null
        if (appearance.width <= 0.0 || appearance.height <= 0.0) return null
        val box = PdfRect(spec.x, spec.y, spec.x + appearance.width, spec.y + appearance.height)
        val contents = if (spec.design.dynamic) StampText.contents(spec.design.label, spec.author, placed)
        else spec.design.label
        val ap = form(listOf(0.0, 0.0, appearance.width, appearance.height), appearance.resources, appearance.content)
        val annot = LinkedHashMap<PdfName, PdfObject>()
        common(annot, "Stamp", box, contents, spec.author, pageRef, subject = spec.design.group.label, creation = true)
        spec.design.standardName?.let { annot[PdfName("Name")] = PdfName(it) }
        if (spec.opacity < 1.0) annot[PdfName("CA")] = real(spec.opacity.coerceIn(0.0, 1.0))
        annot[PdfName("AP")] = dict("N" to ap)
        return builder.add(PdfDict(annot))
    }

    /**
     * A state change is a hidden text annotation replying to the comment
     * (§12.5.6.3): /F keeps the Hidden, NoZoom and NoRotate bits that make
     * Acrobat show the state in the comment list without drawing anything.
     */
    private fun buildStatus(spec: StatusSpec, pageRef: PdfRef, resolveAnnot: (Int) -> PdfRef?): List<PdfRef> {
        val target = resolveAnnot(spec.targetObjNum) ?: return emptyList()
        val targetDict = builder.get(target.objNum) as? PdfDict
        val box = PdfRect.of(targetDict?.get("Rect") as? PdfArray) ?: PdfRect(0.0, 0.0, REPLY_SIZE, REPLY_SIZE)
        val annot = LinkedHashMap<PdfName, PdfObject>()
        common(
            annot, "Text", box, null, spec.title, pageRef, creation = true,
            flags = PdfAnnotation.HIDDEN or PdfAnnotation.PRINT or PdfAnnotation.NO_ZOOM or PdfAnnotation.NO_ROTATE
        )
        annot[PdfName("IRT")] = target
        annot[PdfName("RT")] = PdfName("R")
        annot[PdfName("State")] = textString(spec.status.stateText)
        annot[PdfName("StateModel")] = PdfName("Review")
        return listOf(builder.add(PdfDict(annot)))
    }

    /** /QuadPoints in Table 164 order: upper-left, upper-right, lower-left, lower-right. */
    private fun quadPoints(rects: List<PdfRect>): ArrayList<PdfObject> {
        val quad = ArrayList<PdfObject>(rects.size * 8)
        for (r in rects) {
            quad += real(r.left); quad += real(r.top)
            quad += real(r.right); quad += real(r.top)
            quad += real(r.left); quad += real(r.bottom)
            quad += real(r.right); quad += real(r.bottom)
        }
        return quad
    }

    /**
     * Stores a markup annotation plus, when asked, its popup window
     * (§12.5.6.14). Returns /Annots references: the annotation, then the popup.
     */
    private fun addMarkup(
        annot: LinkedHashMap<PdfName, PdfObject>,
        box: PdfRect,
        pageRef: PdfRef,
        pageBox: PdfRect?,
        popup: Boolean,
        open: Boolean
    ): List<PdfRef> {
        val annotRef = PdfRef(builder.allocate(), 0)
        val refs = ArrayList<PdfRef>(2)
        refs += annotRef
        if (popup) {
            val popupRef = addPopup(annotRef, box, pageRef, pageBox, open)
            annot[PdfName("Popup")] = popupRef
            refs += popupRef
        }
        builder.put(annotRef.objNum, PdfDict(annot))
        return refs
    }

    /** A popup window opening beside its parent (Table 182); no /AP. */
    private fun addPopup(
        parentRef: PdfRef,
        parentBox: PdfRect,
        pageRef: PdfRef,
        pageBox: PdfRect?,
        open: Boolean
    ): PdfRef {
        val box = popupBox(parentBox, pageBox)
        val popup = LinkedHashMap<PdfName, PdfObject>()
        popup[PdfName("Type")] = PdfName("Annot")
        popup[PdfName("Subtype")] = PdfName("Popup")
        popup[PdfName("Rect")] = PdfArray(listOf(real(box.left), real(box.bottom), real(box.right), real(box.top)))
        popup[PdfName("Parent")] = parentRef
        popup[PdfName("Open")] = PdfBoolean(open)
        popup[PdfName("P")] = pageRef
        return builder.add(PdfDict(popup))
    }

    /**
     * Places the popup beside its parent, preferring the right side at the
     * parent's bottom, and keeps it inside the page when the box is known.
     */
    private fun popupBox(parent: PdfRect, pageBox: PdfRect?): PdfRect {
        val bounds = pageBox ?: PdfRect(0.0, 0.0, 612.0, 792.0)
        var left = parent.right + 8.0
        if (left + POPUP_WIDTH > bounds.right) left = parent.left - 8.0 - POPUP_WIDTH
        if (left < bounds.left) left = bounds.left
        var bottom = parent.bottom
        if (bottom + POPUP_HEIGHT > bounds.top) bottom = bounds.top - POPUP_HEIGHT
        if (bottom < bounds.bottom) bottom = bounds.bottom
        return PdfRect(left, bottom, left + POPUP_WIDTH, bottom + POPUP_HEIGHT)
    }

    /** A reply's icon sits over the upper-left corner of the annotation it answers. */
    private fun replyBox(target: PdfRect?): PdfRect {
        val anchor = target ?: return PdfRect(0.0, 0.0, REPLY_SIZE, REPLY_SIZE)
        return PdfRect(anchor.left, anchor.top - REPLY_SIZE, anchor.left + REPLY_SIZE, anchor.top)
    }

    /** The classic piece-of-paper note icon, drawn in the label's own box. */
    private fun noteIcon(w: Double, h: Double, color: PdfColor): String = buildString {
        val fold = minOf(w, h) * 0.35
        append("q ")
        append(rgb(color))
        append(" 0 0 "); append(num(w)); append(' '); append(num(h)); append(" re f")
        append(" 0 0 0 RG 0.75 w 0.375 0.375 "); append(num(w - 0.75)); append(' '); append(num(h - 0.75))
        append(" re S")
        append(" 0.75 w "); append(num(w - fold)); append(' '); append(num(h))
        append(" m "); append(num(w - fold)); append(' '); append(num(h - fold))
        append(" l "); append(num(w)); append(' '); append(num(h - fold)); append(" l S Q")
    }

    /** /C of an annotation dictionary already copied to the builder (§12.5.2). */
    private fun annotColor(dict: PdfDict?): PdfColor? {
        val values = (dict?.get("C") as? PdfArray)?.items ?: return null
        val c = ArrayList<Double>(values.size)
        for (value in values) {
            val number = when (value) {
                is PdfReal -> value.value
                is PdfInteger -> value.value.toDouble()
                else -> return null
            }
            c += number
        }
        return when (c.size) {
            1 -> PdfColor(c[0], c[0], c[0])
            3 -> PdfColor(c[0], c[1], c[2])
            else -> null
        }
    }

    /** Entries shared by every new annotation (§12.5.2, Table 164). */
    private fun common(
        annot: LinkedHashMap<PdfName, PdfObject>,
        subtype: String,
        box: PdfRect,
        contents: String?,
        title: String?,
        pageRef: PdfRef,
        subject: String? = null,
        creation: Boolean = false,
        flags: Int = PdfAnnotation.PRINT
    ) {
        annot[PdfName("Type")] = PdfName("Annot")
        annot[PdfName("Subtype")] = PdfName(subtype)
        annot[PdfName("Rect")] = PdfArray(
            listOf(real(box.left), real(box.bottom), real(box.right), real(box.top))
        )
        annot[PdfName("F")] = PdfInteger(flags.toLong())
        annot[PdfName("M")] = date
        annot[PdfName("P")] = pageRef
        if (contents != null) annot[PdfName("Contents")] = textString(contents)
        if (title != null) annot[PdfName("T")] = textString(title)
        if (subject != null) annot[PdfName("Subj")] = textString(subject)
        if (creation) annot[PdfName("CreationDate")] = date
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
}
