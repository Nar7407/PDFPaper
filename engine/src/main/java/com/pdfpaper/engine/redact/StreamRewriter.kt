package com.pdfpaper.engine.redact

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.edit.ContentSerializer
import com.pdfpaper.engine.edit.Flate
import com.pdfpaper.engine.edit.ScannedTextRun
import com.pdfpaper.engine.edit.TextRunScanner
import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.image.PdfImages
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInlineImage
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfObjectCopier
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class RedactionContext {
    val warnings = LinkedHashSet<String>()
    val unredacted = LinkedHashSet<String>()
    var marksApplied = 0
    var textCommandsRewritten = 0
    var imagesBlanked = 0
    var imagesRemoved = 0
    var vectorsClipped = 0

    fun warn(message: String) {
        warnings += message
    }

    fun cannotRedact(message: String) {
        unredacted += message
        warnings += message
    }
}

internal data class StreamResult(
    val commands: List<ContentCommand>,
    val resources: PdfDict?
)

/**
 * Recursive content-stream rewriter for one page (T28).
 *
 * All coordinates given to [rewrite] are in that stream's own user space; form
 * marks are inverse-transformed before recursing, and every changed Form
 * XObject is cloned so a resource shared with another page is never altered.
 */
internal class StreamRewriter(
    val builder: PdfDocumentBuilder,
    private val copier: PdfObjectCopier,
    private val doc: PdfDocument,
    private val decoder: ExternalImageDecoder?,
    private val context: RedactionContext,
    private val names: ResourceNames
) {

    fun rewrite(
        commands: List<ContentCommand>,
        resources: PdfDict?,
        marks: List<PdfRect>,
        expected: List<String> = emptyList(),
        depth: Int = 0
    ): StreamResult {
        val baseResources = copyResources(resources)
        val xobjects = LinkedHashMap<PdfName, PdfObject>()
        val output = ArrayList<ContentCommand>()
        val path = PathBuffer()

        val runsByCommand = HashMap<Int, MutableList<ScannedTextRun>>()
        if (depth < MAX_FORM_DEPTH) {
            val warnings = ArrayList<String>()
            for (run in TextRunScanner.scan(commands, resources, doc, warnings)) {
                runsByCommand.getOrPut(run.commandIndex) { ArrayList() } += run
            }
        }

        var ctm = PdfMatrix.IDENTITY
        var lineWidth = 1.0
        val ctmStack = ArrayDeque<PdfMatrix>()
        val widthStack = ArrayDeque<Double>()

        fun addXObject(name: String, value: PdfObject): Boolean {
            if (value is PdfStream) {
                xobjects[PdfName(name)] = builder.add(value)
                return true
            }
            if (value is PdfRef || value is PdfDict || value is PdfArray) {
                xobjects[PdfName(name)] = value
                return true
            }
            return false
        }

        for ((index, command) in commands.withIndex()) {
            when (command.operator) {
                "q" -> {
                    ctmStack.addLast(ctm)
                    widthStack.addLast(lineWidth)
                }
                "Q" -> {
                    ctm = ctmStack.removeLastOrNull() ?: ctm
                    lineWidth = widthStack.removeLastOrNull() ?: lineWidth
                }
                "cm" -> matrixOfPdf(command.operands)?.let { ctm = ctm * it }
                "w" -> number(command.operands.getOrNull(0))?.let { lineWidth = it }
                "m", "l" -> {
                    path.addPoint(ctm, command.operands.getOrNull(0), command.operands.getOrNull(1))
                    path.commands += command
                }
                "c" -> {
                    path.addPoint(ctm, command.operands.getOrNull(0), command.operands.getOrNull(1))
                    path.addPoint(ctm, command.operands.getOrNull(2), command.operands.getOrNull(3))
                    path.addPoint(ctm, command.operands.getOrNull(4), command.operands.getOrNull(5))
                    path.commands += command
                }
                "v", "y" -> {
                    path.addPoint(ctm, command.operands.getOrNull(0), command.operands.getOrNull(1))
                    path.addPoint(ctm, command.operands.getOrNull(2), command.operands.getOrNull(3))
                    path.commands += command
                }
                "re" -> {
                    val x = number(command.operands.getOrNull(0))
                    val y = number(command.operands.getOrNull(1))
                    val w = number(command.operands.getOrNull(2))
                    val h = number(command.operands.getOrNull(3))
                    if (x != null && y != null && w != null && h != null) {
                        path.addPoint(ctm, x, y)
                        path.addPoint(ctm, x + w, y)
                        path.addPoint(ctm, x + w, y + h)
                        path.addPoint(ctm, x, y + h)
                    }
                    path.commands += command
                }
                "h" -> path.commands += command
                "W", "W*" -> {
                    // Clipping paths are left intact; the visible cover hides
                    // their effect inside the marked area.
                    output += path.commands
                    output += command
                    path.clear()
                }
                "n" -> {
                    output += path.commands
                    output += command
                    path.clear()
                }
                "S", "s", "f", "F", "f*", "B", "B*", "b", "b*" -> {
                    val intersect = path.intersects(
                        marks,
                        if (command.operator in setOf("S", "s", "B", "B*", "b", "b*")) lineWidth else 0.0,
                        ctm
                    )
                    if (intersect) {
                        val clip = clipOutside(marks, ctm)
                        if (clip != null) {
                            output += clip
                            output += path.commands
                            output += command
                            output += ContentCommand(emptyList(), "Q")
                            context.vectorsClipped++
                        } else {
                            output += path.commands
                            output += command
                        }
                    } else {
                        output += path.commands
                        output += command
                    }
                    path.clear()
                }
                "sh" -> {
                    val clip = clipOutside(marks, ctm)
                    if (clip != null) {
                        output += clip
                        output += command
                        output += ContentCommand(emptyList(), "Q")
                        context.vectorsClipped++
                    } else {
                        output += command
                    }
                }
                "Do" -> {
                    val name = (command.operands.getOrNull(0) as? PdfName)?.name
                    val raw = name?.let { xObjectRaw(resources, it) }
                    val stream = raw?.let { copier.resolve(it) as? PdfStream }
                    if (name == null || raw == null || stream == null) {
                        output += command
                        continue
                    }
                    when ((stream.dict["Subtype"] as? PdfName)?.name) {
                        "Image" -> {
                            val result = handleImage(raw, stream, name, ctm, marks, resources)
                            when (result.kind) {
                                ImageAction.KEEP -> {
                                    if (addXObject(name, result.value!!)) output += command
                                }
                                ImageAction.RENAME -> {
                                    if (addXObject(result.name!!, result.value!!)) {
                                        output += ContentCommand(listOf(PdfName(result.name)), "Do")
                                    }
                                }
                                ImageAction.DROP -> Unit
                                ImageAction.CLIP -> {
                                    if (addXObject(name, result.value!!)) {
                                        val clip = clipOutside(marks, ctm)
                                        if (clip != null) {
                                            output += clip
                                            output += command
                                            output += ContentCommand(emptyList(), "Q")
                                        } else {
                                            output += command
                                        }
                                    }
                                }
                            }
                        }
                        "Form" -> {
                            val form = handleForm(raw, stream, name, ctm, marks, expected, resources, depth)
                            when {
                                form.drop -> {
                                    // Entire form could not be decoded and
                                    // intersects a mark: drop it rather than
                                    // leaving raw content behind.
                                }
                                !form.changed -> {
                                    if (addXObject(name, form.value!!)) output += command
                                }
                                else -> {
                                    val fresh = names.fresh()
                                    if (addXObject(fresh, form.value!!)) {
                                        output += ContentCommand(listOf(PdfName(fresh)), "Do")
                                    }
                                }
                            }
                        }
                        else -> {
                            val copied = copyXObject(raw)
                            if (copied != null && addXObject(name, copied)) output += command
                            else output += command
                        }
                    }
                }
                "ID" -> {
                    val inline = command.operands.firstOrNull() as? PdfInlineImage
                    if (inline != null && intersectsAny(marks, boundsOfUnitSquare(ctm))) {
                        handleInlineImage(inline, command, ctm, marks, resources, xobjects, output)
                    } else {
                        output += command
                    }
                }
                "Tj", "'", "\"", "TJ" -> {
                    val runs = runsByCommand[index]
                    val rewritten = if (runs != null) rewriteShowCommand(command, runs, marks, expected) else null
                    if (rewritten != null) {
                        output += rewritten
                        context.textCommandsRewritten++
                    } else {
                        output += command
                    }
                }
                else -> output += command
            }
        }
        output += path.commands

        if (xobjects.isNotEmpty()) {
            val existing = (baseResources[PdfName("XObject")] as? PdfDict)?.entries.orEmpty()
            baseResources[PdfName("XObject")] = PdfDict(existing + xobjects)
        }
        return StreamResult(output, PdfDict(baseResources))
    }

    private fun copyResources(resources: PdfDict?): LinkedHashMap<PdfName, PdfObject> {
        val out = LinkedHashMap<PdfName, PdfObject>()
        if (resources == null) return out
        for ((key, value) in resources.entries) {
            if (key.name == "XObject") continue
            val copied = copier.copyValue(value)
            if (copied != null) out[key] = copied
        }
        return out
    }

    private enum class ImageAction { KEEP, RENAME, DROP, CLIP }

    private class ImageResult(
        val kind: ImageAction,
        val name: String? = null,
        val value: PdfObject? = null
    )

    private fun handleImage(
        raw: PdfObject,
        stream: PdfStream,
        name: String,
        ctm: PdfMatrix,
        marks: List<PdfRect>,
        resources: PdfDict?
    ): ImageResult {
        val placement = imageUnitMatrix(ctm)
        val bounds = boundsOfUnitSquare(placement)
        if (!intersectsAny(marks, bounds)) {
            return ImageResult(ImageAction.KEEP, value = copyXObject(raw))
        }
        if (fullyCovered(marks, bounds)) {
            context.imagesRemoved++
            return ImageResult(ImageAction.DROP)
        }
        val decoded = PdfImages.decodeImage(stream.dict, stream.data, resources, doc, decoder)
        val image = decoded.image
        if (image == null || image.pixels == null) {
            context.cannotRedact(
                "image '/$name' intersects a redaction mark but its pixels could not be decoded" +
                    if (image?.externalFormat != null) " (${image.externalFormat} needs an external decoder)" else ""
            )
            return ImageResult(ImageAction.CLIP, value = copyXObject(raw))
        }
        val regions = pixelRegionsOf(marks, placement, image.width, image.height)
        if (regions.isEmpty()) {
            context.cannotRedact("image '/$name' intersects a mark but no pixel region could be mapped")
            return ImageResult(ImageAction.CLIP, value = copyXObject(raw))
        }
        val blank = blankImage(builder, image, regions)
        if (blank == null) {
            context.cannotRedact("image '/$name' could not be re-encoded after blanking")
            return ImageResult(ImageAction.CLIP, value = copyXObject(raw))
        }
        context.imagesBlanked++
        return ImageResult(ImageAction.RENAME, name = names.fresh("ImgR"), value = blank)
    }

    private fun handleInlineImage(
        inline: PdfInlineImage,
        command: ContentCommand,
        ctm: PdfMatrix,
        marks: List<PdfRect>,
        resources: PdfDict?,
        xobjects: LinkedHashMap<PdfName, PdfObject>,
        output: MutableList<ContentCommand>
    ) {
        val bounds = boundsOfUnitSquare(ctm)
        if (fullyCovered(marks, bounds)) {
            context.imagesRemoved++
            return
        }
        val dict = PdfImages.normalizeInlineDict(inline.dict)
        val decoded = PdfImages.decodeImage(dict, inline.data, resources, doc, decoder)
        val image = decoded.image
        if (image?.pixels == null) {
            context.cannotRedact("inline image intersects a redaction mark but could not be decoded")
            val clip = clipOutside(marks, ctm)
            if (clip != null) {
                output += clip
                output += command
                output += ContentCommand(emptyList(), "Q")
            } else {
                output += command
            }
            return
        }
        val regions = pixelRegionsOf(marks, imageUnitMatrix(ctm), image.width, image.height)
        val blank = blankImage(builder, image, regions)
        if (blank == null) {
            context.cannotRedact("inline image could not be re-encoded after blanking")
            output += command
            return
        }
        val fresh = names.fresh("ImgR")
        xobjects[PdfName(fresh)] = blank
        output += ContentCommand(listOf(PdfName(fresh)), "Do")
        context.imagesBlanked++
    }

    private class FormResult(
        val value: PdfObject?,
        val changed: Boolean,
        val drop: Boolean = false
    )

    private fun handleForm(
        raw: PdfObject,
        stream: PdfStream,
        name: String,
        ctm: PdfMatrix,
        marks: List<PdfRect>,
        expected: List<String>,
        parentResources: PdfDict?,
        depth: Int
    ): FormResult {
        if (depth >= MAX_FORM_DEPTH) {
            context.cannotRedact("form '/$name' exceeds the redaction recursion limit")
            return FormResult(null, changed = false, drop = true)
        }
        val matrix = formMatrix(stream.dict)
        val childCtm = ctm * matrix
        val formBounds = formBounds(stream.dict)
        val parentBounds = formBounds?.let { transformRect(it, childCtm) }
        if (parentBounds != null && !intersectsAny(marks, parentBounds)) {
            return FormResult(copyXObject(raw), changed = false)
        }
        val inverse = childCtm.inverse()
        if (inverse == null) {
            context.cannotRedact("form '/$name' has a singular placement matrix")
            return FormResult(null, changed = false, drop = true)
        }
        val childMarks = marks.map { transformRect(it, inverse) }
        val bytes = stream.decoded { ref -> doc.resolve(ref) }.getOrNull()?.bytes
        if (bytes == null) {
            context.cannotRedact("form '/$name' could not be decoded; it was removed from the redacted area")
            return FormResult(null, changed = false, drop = true)
        }
        val parsed = parseContent(bytes)
        val childResources = resolveDict(stream.dict["Resources"]) ?: parentResources
        val result = rewrite(parsed.commands, childResources, childMarks, expected, depth + 1)

        val dict = copier.copyDict(
            stream.dict,
            skipKeys = setOf("Length", "Filter", "DecodeParms", "Resources")
        ) ?: PdfDict(emptyMap())
        val entries = LinkedHashMap(dict.entries)
        entries[PdfName("Type")] = PdfName("XObject")
        entries[PdfName("Subtype")] = PdfName("Form")
        entries[PdfName("Resources")] = result.resources ?: PdfDict(emptyMap())
        entries[PdfName("Filter")] = PdfName("FlateDecode")
        val data = ContentSerializer.serialize(result.commands)
        return FormResult(builder.addStream(PdfDict(entries), Flate.encode(data)), changed = true)
    }

    private fun copyXObject(raw: PdfObject): PdfObject? {
        val copied = copier.copyValue(raw) ?: return null
        return if (copied is PdfStream) builder.add(copied) else copied
    }

    private fun xObjectRaw(resources: PdfDict?, name: String): PdfObject? {
        val table = when (val group = resources?.get("XObject")) {
            is PdfDict -> group
            is PdfRef -> doc.resolve(group) as? PdfDict
            else -> null
        } ?: return null
        return table[name]
    }

    private fun resolveDict(obj: PdfObject?): PdfDict? = when (obj) {
        is PdfDict -> obj
        is PdfRef -> doc.resolve(obj) as? PdfDict
        else -> null
    }

    private fun formMatrix(dict: PdfDict): PdfMatrix {
        val array = dict["Matrix"] as? PdfArray ?: return PdfMatrix.IDENTITY
        return matrixOfPdf(array.items) ?: PdfMatrix.IDENTITY
    }

    private fun formBounds(dict: PdfDict): PdfRect? {
        val obj = dict["BBox"] ?: return null
        val array = when (obj) {
            is PdfArray -> obj
            is PdfRef -> doc.resolve(obj) as? PdfArray
            else -> null
        } ?: return null
        return PdfRect.of(array)
    }

    private fun transformRect(rect: PdfRect, matrix: PdfMatrix): PdfRect {
        val points = arrayOf(
            matrix.apply(rect.left, rect.bottom),
            matrix.apply(rect.right, rect.bottom),
            matrix.apply(rect.right, rect.top),
            matrix.apply(rect.left, rect.top)
        )
        return PdfRect(
            points.minOf { it[0] }, points.minOf { it[1] },
            points.maxOf { it[0] }, points.maxOf { it[1] }
        )
    }

    /**
     * `q`, a huge rectangle with [marks] as even-odd holes, `W* n` in the
     * current user space. The caller appends `Q` after the paint operation.
     */
    private fun clipOutside(marks: List<PdfRect>, ctm: PdfMatrix): List<ContentCommand>? {
        val inverse = ctm.inverse() ?: return null
        val out = ArrayList<ContentCommand>()
        out += ContentCommand(emptyList(), "q")
        val huge = 1_000_000.0
        out += ContentCommand(
            listOf(PdfReal(-huge), PdfReal(-huge), PdfReal(2 * huge), PdfReal(2 * huge)),
            "re"
        )
        for (mark in marks) {
            val corners = arrayOf(
                inverse.apply(mark.left, mark.bottom),
                inverse.apply(mark.right, mark.bottom),
                inverse.apply(mark.right, mark.top),
                inverse.apply(mark.left, mark.top)
            )
            out += ContentCommand(listOf(PdfReal(corners[0][0]), PdfReal(corners[0][1])), "m")
            for (i in 1 until corners.size) {
                out += ContentCommand(listOf(PdfReal(corners[i][0]), PdfReal(corners[i][1])), "l")
            }
            out += ContentCommand(emptyList(), "h")
        }
        out += ContentCommand(emptyList(), "W*")
        out += ContentCommand(emptyList(), "n")
        return out
    }

    private fun number(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }

    private class PathBuffer {
        val commands = ArrayList<ContentCommand>()
        private var x0 = Double.POSITIVE_INFINITY
        private var y0 = Double.POSITIVE_INFINITY
        private var x1 = Double.NEGATIVE_INFINITY
        private var y1 = Double.NEGATIVE_INFINITY

        fun addPoint(ctm: PdfMatrix, xObj: PdfObject?, yObj: PdfObject?) {
            val x = number(xObj) ?: return
            val y = number(yObj) ?: return
            addPoint(ctm, x, y)
        }

        fun addPoint(ctm: PdfMatrix, x: Double, y: Double) {
            val p = ctm.apply(x, y)
            x0 = min(x0, p[0])
            y0 = min(y0, p[1])
            x1 = max(x1, p[0])
            y1 = max(y1, p[1])
        }

        fun intersects(marks: List<PdfRect>, strokeWidth: Double, ctm: PdfMatrix): Boolean {
            if (x0 > x1 || y0 > y1) return false
            val pad = abs(strokeWidth) * ctm.meanScale() / 2.0 + 0.5
            return intersectsAny(marks, PdfRect(x0 - pad, y0 - pad, x1 + pad, y1 + pad))
        }

        fun clear() {
            commands.clear()
            x0 = Double.POSITIVE_INFINITY
            y0 = Double.POSITIVE_INFINITY
            x1 = Double.NEGATIVE_INFINITY
            y1 = Double.NEGATIVE_INFINITY
        }

        private fun number(obj: PdfObject?): Double? = when (obj) {
            is PdfInteger -> obj.value.toDouble()
            is PdfReal -> obj.value
            else -> null
        }
    }

    private companion object {
        const val MAX_FORM_DEPTH = 16
    }
}
