package com.pdfpaper.engine.annot

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.model.parseDirectObject
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfSerializer
import com.pdfpaper.engine.write.PdfWriter
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.max

/**
 * T56: edits an annotation that already exists — its note text, its author and
 * its colour — without replacing the object, so a popup, replies and review
 * state that point at it keep pointing at it.
 *
 * The save is an incremental update (§7.5.6): the original bytes stay exactly
 * as they were, and only the changed annotation dictionary (plus a regenerated
 * appearance stream when the colour changed) is appended. Changing the colour
 * of a markup or ink annotation rebuilds its `/AP /N` from its own
 * `/QuadPoints` or `/InkList`, because a reader paints the appearance and would
 * otherwise keep showing the old colour (§12.5.5).
 */
object AnnotationStyle {

    /** One annotation to change. A null field means "leave it as it is". */
    data class Update(
        val pageIndex: Int,
        val objectNumber: Int,
        val contents: String? = null,
        val color: PdfColor? = null,
        val author: String? = null,
        val modified: String? = PdfDate.now()
    )

    fun applyIncremental(
        document: PdfDocument,
        originalBytes: ByteArray,
        updates: List<Update>,
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<ByteArray> = runCatching {
        require(!document.isEncrypted) { "An encrypted document cannot be saved" }
        document.file.startXrefOffset
            ?: error("This file has no startxref; it cannot be saved incrementally")
        require(updates.isNotEmpty()) { "Nothing changed; no annotations to update" }

        val base = document.file
        val startAt = maxOf(base.trailer.size, (base.entries.keys.maxOrNull() ?: 0) + 1)
        val builder = PdfDocumentBuilder(version, document, startAt)
        val root = base.trailer.root
            ?: throw IllegalStateException("No document catalog; cannot save incrementally")
        builder.root = PdfRef(root.objNum, root.gen)
        builder.info = base.trailer.raw["Info"]?.let { PdfRef.parse(it) }
        builder.id = base.trailer.raw["ID"]?.let { raw ->
            parseDirectObject(
                raw.toByteArray(StandardCharsets.ISO_8859_1)
            ).getOrNull() as? PdfArray
        }

        val pages = document.pages()
        for (update in updates) {
            val page = pages.getOrNull(update.pageIndex)
                ?: throw IllegalArgumentException("Page ${update.pageIndex} is out of range")
            val array = resolve(document, page.dict["Annots"]) as? PdfArray
                ?: throw IllegalArgumentException("Page ${update.pageIndex} has no annotations")
            val item = array.items.firstOrNull { (it as? PdfRef)?.objNum == update.objectNumber }
                ?: throw IllegalArgumentException(
                    "No annotation ${update.objectNumber} on page ${update.pageIndex}"
                )
            val ref = item as? PdfRef ?: throw IllegalArgumentException("Annotation is not indirect")
            val existing = resolve(document, item) as? PdfDict
                ?: throw IllegalArgumentException("Annotation ${update.objectNumber} is not a dictionary")

            val entries = LinkedHashMap(existing.entries)
            if (update.contents != null) {
                if (update.contents.isBlank()) {
                    entries.remove(PdfName("Contents"))
                } else {
                    entries[PdfName("Contents")] = textString(update.contents)
                }
            }
            if (update.author != null) {
                if (update.author.isBlank()) {
                    entries.remove(PdfName("T"))
                } else {
                    entries[PdfName("T")] = textString(update.author)
                }
            }
            if (update.color != null) {
                entries[PdfName("C")] = colorArray(update.color)
                val subtype = (existing["Subtype"] as? PdfName)?.name.orEmpty()
                val appearance = appearanceFor(builder, subtype, update.color, existing)
                if (appearance != null) entries[PdfName("AP")] = dict("N" to appearance)
            }
            update.modified?.let {
                entries[PdfName("M")] = PdfString(it.toByteArray(StandardCharsets.US_ASCII))
            }
            builder.put(ref.objNum, PdfDict(entries))
        }
        require(builder.entries().isNotEmpty()) { "Nothing changed; no objects to save" }
        builder.buildIncremental(originalBytes)
    }

    /** A regenerated `/AP /N` for the annotation kinds whose look is colour. */
    private fun appearanceFor(
        builder: PdfDocumentBuilder,
        subtype: String,
        color: PdfColor,
        annot: PdfDict
    ): PdfRef? = when (subtype) {
        "Highlight", "Underline", "StrikeOut", "Squiggly" ->
            markupAppearance(builder, subtype, color, annot)
        "Ink" -> inkAppearance(builder, color, annot)
        // A note, stamp, free text or picture keeps its artwork; /C is still
        // updated for readers that tint the icon or box from it.
        else -> null
    }

    private fun markupAppearance(
        builder: PdfDocumentBuilder,
        subtype: String,
        color: PdfColor,
        annot: PdfDict
    ): PdfRef? {
        val quads = quadBoxes(annot["QuadPoints"] as? PdfArray)
        val rects = if (quads.isNotEmpty()) {
            quads
        } else {
            listOfNotNull(PdfRect.of(annot["Rect"] as? PdfArray))
        }.filter { !it.isEmpty }
        if (rects.isEmpty()) return null
        val box = PdfRect.union(rects) ?: return null
        val width = max(borderWidth(annot), 1.0)

        val content = StringBuilder()
        when (subtype) {
            "Highlight" -> {
                content.append("q /GS0 gs ").append(fill(color))
                for (r in rects) {
                    content.append(' ')
                    content.append(num(r.left - box.left)).append(' ')
                    content.append(num(r.bottom - box.bottom)).append(' ')
                    content.append(num(r.width)).append(' ')
                    content.append(num(r.height)).append(" re")
                }
                content.append(" f Q")
            }
            "StrikeOut" -> {
                content.append("q ").append(stroke(color)).append(' ').append(num(width)).append(" w")
                for (r in rects) {
                    val y = num((r.bottom + r.top) / 2.0 - box.bottom)
                    content.append(' ').append(num(r.left - box.left)).append(' ').append(y).append(" m")
                    content.append(' ').append(num(r.right - box.left)).append(' ').append(y).append(" l")
                }
                content.append(" S Q")
            }
            else -> {
                // Underline and Squiggly paint the lower edge (§12.5.6.10). A
                // squiggle is drawn as a rule: the reader still shows a line
                // under the same words.
                content.append("q ").append(stroke(color)).append(' ').append(num(width)).append(" w")
                for (r in rects) {
                    val y = num(r.bottom - box.bottom)
                    content.append(' ').append(num(r.left - box.left)).append(' ').append(y).append(" m")
                    content.append(' ').append(num(r.right - box.left)).append(' ').append(y).append(" l")
                }
                content.append(" S Q")
            }
        }
        val resources = if (subtype == "Highlight") {
            dict(
                "ExtGState" to dict(
                    "GS0" to dict("ca" to real(0.5), "CA" to real(0.5), "BM" to PdfName("Multiply"))
                )
            )
        } else {
            null
        }
        return form(builder, listOf(box.left, box.bottom, box.width, box.height), resources, content.toString())
    }

    private fun inkAppearance(builder: PdfDocumentBuilder, color: PdfColor, annot: PdfDict): PdfRef? {
        val strokes = (annot["InkList"] as? PdfArray)?.items.orEmpty().mapNotNull { item ->
            val values = (item as? PdfArray)?.items.orEmpty()
            if (values.size < 4 || values.size % 2 != 0) return@mapNotNull null
            val numbers = values.map { number(it) ?: return@mapNotNull null }
            numbers
        }
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
        val width = max(borderWidth(annot), 1.0)
        val pad = width / 2.0 + 1.0
        val box = PdfRect(minX - pad, minY - pad, maxX + pad, maxY + pad)
        val content = StringBuilder("q ").append(stroke(color)).append(' ').append(num(width))
            .append(" w 1 J 1 j")
        for (stroke in strokes) {
            var i = 0
            while (i + 1 < stroke.size) {
                content.append(' ').append(num(stroke[i] - box.left))
                    .append(' ').append(num(stroke[i + 1] - box.bottom))
                    .append(if (i == 0) " m" else " l")
                i += 2
            }
            content.append(" S")
        }
        content.append(" Q")
        return form(builder, listOf(box.left, box.bottom, box.width, box.height), null, content.toString())
    }

    private fun quadBoxes(array: PdfArray?): List<PdfRect> {
        val values = array?.items ?: return emptyList()
        if (values.size < 8) return emptyList()
        val numbers = values.map { number(it) ?: return emptyList() }
        val out = ArrayList<PdfRect>(numbers.size / 8)
        var i = 0
        while (i + 7 < numbers.size) {
            out += PdfRect(
                minOf(numbers[i], numbers[i + 2], numbers[i + 4], numbers[i + 6]),
                minOf(numbers[i + 1], numbers[i + 3], numbers[i + 5], numbers[i + 7]),
                maxOf(numbers[i], numbers[i + 2], numbers[i + 4], numbers[i + 6]),
                maxOf(numbers[i + 1], numbers[i + 3], numbers[i + 5], numbers[i + 7])
            )
            i += 8
        }
        return out
    }

    private fun borderWidth(dict: PdfDict): Double {
        (dict["BS"] as? PdfDict)?.let { bs -> number(bs["W"])?.let { return it } }
        val border = dict["Border"] as? PdfArray
        return number(border?.items?.getOrNull(2)) ?: 1.0
    }

    private fun form(
        builder: PdfDocumentBuilder,
        bbox: List<Double>,
        resources: PdfDict?,
        content: String
    ): PdfRef {
        val entries = LinkedHashMap<PdfName, PdfObject>()
        entries[PdfName("Type")] = PdfName("XObject")
        entries[PdfName("Subtype")] = PdfName("Form")
        entries[PdfName("FormType")] = PdfInteger(1)
        entries[PdfName("BBox")] = PdfArray(bbox.map { PdfReal(it) })
        if (resources != null) entries[PdfName("Resources")] = resources
        entries[PdfName("Length")] = PdfInteger(0)
        return builder.addStream(
            PdfDict(entries),
            content.toByteArray(StandardCharsets.ISO_8859_1)
        )
    }

    private fun resolve(document: PdfDocument, obj: PdfObject?): PdfObject? = when (obj) {
        null -> null
        is PdfRef -> document.resolve(obj)
        else -> obj
    }

    private fun number(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }

    private fun fill(color: PdfColor): String = "${num(color.red)} ${num(color.green)} ${num(color.blue)} rg"

    private fun stroke(color: PdfColor): String = "${num(color.red)} ${num(color.green)} ${num(color.blue)} RG"

    private fun colorArray(color: PdfColor): PdfArray =
        PdfArray(listOf(PdfReal(color.red), PdfReal(color.green), PdfReal(color.blue)))

    private fun real(value: Double): PdfReal = PdfReal(value)

    private fun num(value: Double): String = PdfSerializer.formatReal(value)

    private fun dict(vararg entries: Pair<String, PdfObject>): PdfDict =
        PdfDict(entries.associate { PdfName(it.first) to it.second })

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
