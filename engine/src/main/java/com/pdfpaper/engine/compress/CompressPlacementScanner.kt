package com.pdfpaper.engine.compress

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import java.io.ByteArrayOutputStream

/**
 * Finds out how large each image is drawn, so the compressor can tell a
 * 600 dpi scan from a 72 dpi logo before it scales anything down.
 *
 * The scan walks the content streams of every page (and of the form XObjects
 * they draw, with the form matrix applied) and records the biggest placement
 * of every image object. Placement sizes are in PDF points (1/72 inch), the
 * same unit the content stream uses.
 */
internal class CompressPlacementScanner(private val document: PdfDocument) {

    /** The biggest placement found for one image object, in points. */
    class Placement(var widthPoints: Double = 0.0, var heightPoints: Double = 0.0)

    private val placements = HashMap<Int, Placement>()

    /** Image object number to the largest placement of that image. */
    fun scan(): Map<Int, Placement> {
        for (page in document.pages()) {
            scanPage(page)
        }
        return placements
    }

    private fun scanPage(page: PdfPage) {
        val content = page.contents().getOrNull()?.bytes ?: return
        walk(parseContent(content).commands, page.resources, PdfMatrix.IDENTITY, HashSet())
    }

    private fun walk(
        commands: List<ContentCommand>,
        resources: PdfDict?,
        ctm: PdfMatrix,
        visiting: MutableSet<Int>
    ) {
        val xObjects = xObjectTable(resources)
        val stack = ArrayDeque<PdfMatrix>()
        var matrix = ctm
        for (command in commands) {
            when (command.operator) {
                "q" -> stack.addLast(matrix)
                "Q" -> matrix = stack.removeLastOrNull() ?: matrix
                "cm" -> matrixOf(command.operands)?.let { matrix = matrix * it }
                "Do" -> {
                    val name = (command.operands.firstOrNull() as? PdfName)?.name ?: continue
                    val entry = xObjects[name] ?: continue
                    val stream = resolve(entry) as? PdfStream ?: continue
                    val subtype = (stream.dict["Subtype"] as? PdfName)?.name
                    if (subtype == "Image") {
                        val objNum = (entry as? PdfRef)?.objNum
                        if (objNum != null) record(objNum, matrix)
                    } else if (subtype == "Form") {
                        val key = System.identityHashCode(stream)
                        if (!visiting.add(key)) continue
                        walkForm(stream, matrix, resources, visiting)
                        visiting.remove(key)
                    }
                }
            }
        }
    }

    private fun walkForm(
        form: PdfStream,
        ctm: PdfMatrix,
        inheritedResources: PdfDict?,
        visiting: MutableSet<Int>
    ) {
        val bytes = formContent(form) ?: return
        val formMatrix = (form.dict["Matrix"] as? PdfArray)?.let { matrixOf(it.items) }
        val resources = (form.dict["Resources"] as? PdfDict) ?: inheritedResources
        val combined = if (formMatrix != null) ctm * formMatrix else ctm
        walk(parseContent(bytes).commands, resources, combined, visiting)
    }

    private fun formContent(form: PdfStream): ByteArray? {
        val raw = form.dict["Contents"] ?: return null
        val streams = when (val obj = resolve(raw)) {
            null -> return null
            is PdfStream -> listOf(obj)
            is PdfArray -> obj.items.mapNotNull { resolve(it) as? PdfStream }
            else -> return null
        }
        if (streams.isEmpty()) return null
        val out = ByteArrayOutputStream()
        for (stream in streams) {
            val decoded = stream.decoded { ref -> document.resolve(ref) }.getOrNull() ?: continue
            if (out.size() > 0) out.write('\n'.code)
            out.write(decoded.bytes)
        }
        return out.toByteArray()
    }

    private fun record(objNum: Int, matrix: PdfMatrix) {
        val bounds = boundsOf(matrix)
        val placement = placements.getOrPut(objNum) { Placement() }
        if (bounds.width > placement.widthPoints) placement.widthPoints = bounds.width
        if (bounds.height > placement.heightPoints) placement.heightPoints = bounds.height
    }

    private fun xObjectTable(resources: PdfDict?): Map<String, PdfObject> {
        val table = resolve(resources?.get("XObject")) as? PdfDict ?: return emptyMap()
        val out = HashMap<String, PdfObject>()
        for ((name, value) in table.entries) out[name.name] = value
        return out
    }

    private fun resolve(value: PdfObject?): PdfObject? = when (value) {
        null -> null
        is PdfRef -> document.resolve(value)
        else -> value
    }

    private fun matrixOf(operands: List<PdfObject>): PdfMatrix? {
        if (operands.size < 6) return null
        val values = DoubleArray(6)
        for (i in 0 until 6) values[i] = numberValue(operands[i]) ?: return null
        return PdfMatrix(values[0], values[1], values[2], values[3], values[4], values[5])
    }

    private fun numberValue(value: PdfObject?): Double? = when (value) {
        is PdfInteger -> value.value.toDouble()
        is PdfReal -> value.value
        else -> null
    }

    private fun boundsOf(matrix: PdfMatrix): PdfRect {
        val corners = arrayOf(
            matrix.apply(0.0, 0.0),
            matrix.apply(1.0, 0.0),
            matrix.apply(1.0, 1.0),
            matrix.apply(0.0, 1.0)
        )
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (corner in corners) {
            minX = minOf(minX, corner[0])
            minY = minOf(minY, corner[1])
            maxX = maxOf(maxX, corner[0])
            maxY = maxOf(maxY, corner[1])
        }
        return PdfRect(minX, minY, maxX, maxY)
    }
}
