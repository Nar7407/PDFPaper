package com.pdfpaper.engine.annot

import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfSerializer
import java.nio.charset.StandardCharsets

/**
 * Moves, resizes or turns one annotation while the assembler copies it (T26).
 * [pageIndex] and [objNum] identify the source annotation. [rect] is the new
 * page-space box; [rotation] is an extra counter-clockwise quarter turn applied
 * to the appearance (0, 90, 180 or 270).
 */
internal class AnnotationPlacement(
    val pageIndex: Int,
    val objNum: Int,
    val rect: PdfRect,
    val rotation: Int = 0
)

/**
 * Applies [AnnotationPlacement]s to annotation dictionaries that were already
 * copied into the output builder. Rotating wraps the normal appearance stream
 * in a `cm` and updates its /BBox, so the annotation keeps rendering in every
 * viewer without the engine having to know how the signature was painted.
 */
internal class AnnotationTransformer(private val builder: PdfDocumentBuilder) {

    fun place(copied: PdfObject, placement: AnnotationPlacement): PdfObject {
        val ref = copied as? PdfRef
        val dict = when (copied) {
            is PdfRef -> builder.get(copied.objNum) as? PdfDict
            is PdfDict -> copied
            else -> null
        } ?: return copied
        val oldRect = PdfRect.of(dict["Rect"] as? PdfArray) ?: placement.rect
        if (oldRect.isEmpty) return copied

        val entries = LinkedHashMap(dict.entries)
        entries[PdfName("Rect")] = rectArray(placement.rect)

        val quarter = quarterTurn(placement.rotation)
        if (quarter != 0) {
            val ap = rotateAppearance(dict, oldRect, quarter)
                ?: throw IllegalStateException("the signature annotation has no rotatable appearance stream")
            entries[PdfName("AP")] = ap
        }
        transformInkList(entries, oldRect, placement.rect, quarter)

        val updated = PdfDict(entries)
        if (ref != null) builder.put(ref.objNum, updated)
        return if (ref != null) ref else updated
    }

    /**
     * Wraps /AP /N in a rotation transform. The form's /BBox is replaced by
     * the turned box, so the annotation rectangle maps the visible content
     * exactly as it did before the turn (§12.5.5).
     */
    private fun rotateAppearance(dict: PdfDict, oldRect: PdfRect, quarter: Int): PdfDict? {
        val apDict = resolveDict(dict["AP"]) ?: return null
        val stream = resolve(apDict["N"]) as? PdfStream ?: return null
        val bbox = PdfRect.of(stream.dict["BBox"] as? PdfArray) ?: oldRect
        if (bbox.isEmpty) return null

        val bytes = stream.decoded { ref -> resolve(ref) }.getOrThrow().bytes
        val w = bbox.width
        val h = bbox.height
        val newBBox: PdfRect
        val turn: String
        when (quarter) {
            90 -> {
                newBBox = PdfRect(0.0, 0.0, h, w)
                turn = "0 1 -1 0 $h 0"
            }
            180 -> {
                newBBox = PdfRect(0.0, 0.0, w, h)
                turn = "-1 0 0 -1 $w $h"
            }
            else -> {
                newBBox = PdfRect(0.0, 0.0, h, w)
                turn = "0 -1 1 0 0 $w"
            }
        }
        val content = buildString {
            append("q 1 0 0 1 ")
            append(number(-bbox.left)).append(' ').append(number(-bbox.bottom)).append(" cm ")
            append(turn).append(" cm\n")
            append(String(bytes, StandardCharsets.ISO_8859_1))
            append("\nQ")
        }.toByteArray(StandardCharsets.ISO_8859_1)

        val streamEntries = LinkedHashMap(stream.dict.entries)
        streamEntries.remove(PdfName("Filter"))
        streamEntries.remove(PdfName("DecodeParms"))
        streamEntries.remove(PdfName("DP"))
        streamEntries.remove(PdfName("Length"))
        streamEntries.remove(PdfName("BBox"))
        streamEntries.remove(PdfName("Matrix"))
        streamEntries[PdfName("BBox")] = rectArray(newBBox)
        streamEntries[PdfName("Length")] = PdfInteger(0)
        val newRef = builder.addStream(PdfDict(streamEntries), content)

        val apEntries = LinkedHashMap(apDict.entries)
        apEntries[PdfName("N")] = newRef
        return PdfDict(apEntries)
    }

    /** Keeps a drawn /InkList consistent with the moved/resized/turned box. */
    private fun transformInkList(
        entries: LinkedHashMap<PdfName, PdfObject>,
        oldRect: PdfRect,
        newRect: PdfRect,
        quarter: Int
    ) {
        val ink = entries[PdfName("InkList")] as? PdfArray ?: return
        if (oldRect.isEmpty) return
        val localWidth = if (quarter == 90 || quarter == 270) oldRect.height else oldRect.width
        val localHeight = if (quarter == 90 || quarter == 270) oldRect.width else oldRect.height
        if (localWidth <= 0.0 || localHeight <= 0.0) return
        val out = ArrayList<PdfObject>(ink.items.size)
        for (item in ink.items) {
            val points = item as? PdfArray ?: continue
            val values = ArrayList<PdfObject>(points.items.size)
            var i = 0
            while (i + 1 < points.items.size) {
                val x = number(points.items[i]) ?: return
                val y = number(points.items[i + 1]) ?: return
                val u = ((x - oldRect.left) / oldRect.width).coerceIn(-2.0, 3.0)
                val v = ((y - oldRect.bottom) / oldRect.height).coerceIn(-2.0, 3.0)
                val turned = when (quarter) {
                    90 -> doubleArrayOf((1.0 - v) * localWidth, u * localHeight)
                    180 -> doubleArrayOf((1.0 - u) * localWidth, (1.0 - v) * localHeight)
                    270 -> doubleArrayOf(v * localWidth, (1.0 - u) * localHeight)
                    else -> doubleArrayOf(u * localWidth, v * localHeight)
                }
                values += PdfReal(newRect.left + turned[0] * newRect.width / localWidth)
                values += PdfReal(newRect.bottom + turned[1] * newRect.height / localHeight)
                i += 2
            }
            out += PdfArray(values)
        }
        entries[PdfName("InkList")] = PdfArray(out)
    }

    private fun resolveDict(obj: PdfObject?): PdfDict? = when (obj) {
        null -> null
        is PdfDict -> obj
        is PdfRef -> resolve(obj) as? PdfDict
        else -> null
    }

    private fun resolve(obj: PdfObject?): PdfObject? {
        var current = obj
        val seen = HashSet<Int>()
        while (current is PdfRef) {
            if (!seen.add(current.objNum)) return null
            current = builder.get(current.objNum)
        }
        return current
    }

    private fun rectArray(rect: PdfRect): PdfArray = PdfArray(
        listOf(PdfReal(rect.left), PdfReal(rect.bottom), PdfReal(rect.right), PdfReal(rect.top))
    )

    private fun number(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }

    private fun number(value: Double): String = PdfSerializer.formatReal(value)

    private fun quarterTurn(rotation: Int): Int {
        val normalised = ((rotation % 360) + 360) % 360
        return ((normalised + 45) / 90 * 90) % 360
    }
}
