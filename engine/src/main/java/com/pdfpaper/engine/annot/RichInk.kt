package com.pdfpaper.engine.annot

import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal

/**
 * One sampled input point of a stylus stroke (T54). [pressure] is normalised to
 * 0..1; a device that reports no pressure passes 1.0. [tilt] is degrees from
 * the screen normal (0 = upright, 90 = flat) and is kept with the editable
 * stroke so a later tool can use it; the appearance uses pressure only.
 * Coordinates are PDF user space (points, origin bottom-left) after the
 * viewer's page transform.
 */
data class InkPoint(
    val x: Double,
    val y: Double,
    val pressure: Double = 1.0,
    val tilt: Double = 0.0
)

/** Stroke kinds PDFPaper writes into a rich ink annotation. */
enum class InkTool {
    PEN,
    HIGHLIGHTER
}

/**
 * A pressure-sensitive `/Ink` annotation (ISO 32000-1 §12.5.6.13) whose
 * appearance is generated as outlined polygons instead of a constant-width
 * stroke. The original sampled points, pressure and tool are also written to
 * the private `/PdfPaperInk` entry so PDFPaper can load the stroke again for
 * editing; other viewers simply use `/AP /N` and `/InkList`.
 *
 * [width] is the nominal width in points; a pen's actual width varies from
 * 35 % to 100 % with pressure. A highlighter uses a constant width.
 */
class RichInkSpec(
    override val pageIndex: Int,
    val strokes: List<List<InkPoint>>,
    val color: PdfColor = PdfColor.BLACK,
    val width: Double = 2.0,
    val opacity: Double = 1.0,
    val tool: InkTool = InkTool.PEN,
    val contents: String? = null,
    val title: String? = null
) : AnnotationSpec

/** A stroke read back from a `/PdfPaperInk` annotation. */
data class StoredInkStroke(
    val points: List<InkPoint>,
    val color: PdfColor,
    val width: Double,
    val opacity: Double,
    val tool: InkTool
)

/**
 * Reader for the private editable-ink payload. The key is intentionally a
 * direct dictionary, not a stream, because an annotation dictionary can hold
 * it without the assembler having to create another indirect object.
 *
 * Anything malformed is ignored (null / an empty list), never thrown: a PDF
 * from another producer may carry a `/PdfPaperInk` key for its own purposes.
 */
object PdfPaperInk {

    const val KEY = "PdfPaperInk"
    const val VERSION = 1

    /** Reads private data, falling back to `/InkList` for ordinary ink annotations. */
    fun read(annotation: PdfAnnotation): List<StoredInkStroke> {
        if (annotation.type != AnnotationType.INK) return emptyList()
        val private = privateStrokes(annotation.dict)
        if (private.isNotEmpty()) return private
        return annotation.inkLists.mapNotNull { flat ->
            val points = ArrayList<InkPoint>(flat.size / 2)
            var i = 0
            while (i + 1 < flat.size) {
                val x = flat[i]
                val y = flat[i + 1]
                if (!x.isFinite() || !y.isFinite()) return@mapNotNull null
                points += InkPoint(x, y, 1.0)
                i += 2
            }
            if (points.isEmpty()) null else StoredInkStroke(
                points = points,
                color = annotation.color ?: PdfColor.BLACK,
                width = (annotation.borderWidth ?: 2.0).coerceIn(0.1, 100.0),
                opacity = opacityOf(annotation.dict),
                tool = InkTool.PEN
            )
        }
    }

    private fun privateStrokes(dict: PdfDict): List<StoredInkStroke> {
        val payload = dict[KEY] as? PdfDict ?: return emptyList()
        val version = int(payload["Version"])
        if (version != null && version != VERSION) return emptyList()
        val strokes = payload["Strokes"] as? PdfArray ?: return emptyList()
        val out = ArrayList<StoredInkStroke>(strokes.items.size)
        for (item in strokes.items) {
            val stroke = item as? PdfDict ?: continue
            val points = points(stroke["P"] as? PdfArray) ?: continue
            if (points.isEmpty()) continue
            out += StoredInkStroke(
                points = points,
                color = colorOf(stroke["C"]),
                width = (number(stroke["W"]) ?: 2.0).coerceIn(0.1, 100.0),
                opacity = (number(stroke["O"]) ?: 1.0).coerceIn(0.0, 1.0),
                tool = if ((stroke["T"] as? PdfName)?.name == "Highlighter") {
                    InkTool.HIGHLIGHTER
                } else {
                    InkTool.PEN
                }
            )
        }
        return out
    }

    private fun points(array: PdfArray?): List<InkPoint>? {
        val values = array?.items ?: return null
        val stride = when {
            values.size >= 4 && values.size % 4 == 0 -> 4
            values.size >= 3 && values.size % 3 == 0 -> 3
            else -> return null
        }
        val out = ArrayList<InkPoint>(values.size / stride)
        var i = 0
        while (i + stride - 1 < values.size) {
            val x = number(values[i]) ?: return null
            val y = number(values[i + 1]) ?: return null
            val p = (number(values[i + 2]) ?: 1.0).coerceIn(0.0, 1.0)
            val tilt = if (stride == 4) {
                (number(values[i + 3]) ?: 0.0).coerceIn(0.0, 90.0)
            } else {
                0.0
            }
            if (!x.isFinite() || !y.isFinite()) return null
            out += InkPoint(x, y, p, tilt)
            i += stride
        }
        return out
    }

    private fun colorOf(obj: PdfObject?): PdfColor {
        val values = (obj as? PdfArray)?.items ?: return PdfColor.BLACK
        val c = values.map { number(it) ?: return PdfColor.BLACK }
        return when (c.size) {
            1 -> PdfColor(c[0], c[0], c[0])
            3 -> PdfColor(c[0], c[1], c[2])
            4 -> PdfColor(
                (1 - c[0]) * (1 - c[3]),
                (1 - c[1]) * (1 - c[3]),
                (1 - c[2]) * (1 - c[3])
            )
            else -> PdfColor.BLACK
        }
    }

    private fun opacityOf(dict: PdfDict): Double =
        (number(dict["CA"]) ?: 1.0).coerceIn(0.0, 1.0)

    private fun int(obj: PdfObject?): Int? = when (obj) {
        is PdfInteger -> obj.value.toIntOrNullForInk()
        is PdfReal -> obj.value.toInt()
        else -> null
    }

    private fun number(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }

    private fun Long.toIntOrNullForInk(): Int? =
        if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) null else toInt()
}
