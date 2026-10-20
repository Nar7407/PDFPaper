package com.pdfpaper.engine.sanitize

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.edit.ContentSerializer
import com.pdfpaper.engine.edit.Flate
import com.pdfpaper.engine.edit.ScannedTextRun
import com.pdfpaper.engine.edit.TextRunScanner
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
import com.pdfpaper.engine.redact.rewriteShowCommand
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfObjectCopier

internal class SanitizeContentContext {
    val warnings = LinkedHashSet<String>()
    var hiddenTextRuns: Int = 0
    var hiddenLayerSections: Int = 0

    fun warn(message: String) {
        warnings += message
    }
}

internal class SanitizedStream(
    val commands: List<ContentCommand>,
    val resources: PdfDict,
    val changed: Boolean
)

/**
 * Rewrites one content stream (and the Form XObjects it can reach) for T29:
 * hidden optional-content sections and hidden text are removed, while every
 * visible operator is preserved. Form XObjects are rewritten only when one of
 * those removals changes them; otherwise the original object is copied and
 * sharing is kept.
 */
internal class SanitizeContentRewriter(
    private val builder: PdfDocumentBuilder,
    private val copier: PdfObjectCopier,
    private val document: PdfDocument,
    private val pageBox: PdfRect,
    private val removeHiddenText: Boolean,
    private val layers: OptionalContentFilter?,
    private val context: SanitizeContentContext
) {

    fun rewrite(
        commands: List<ContentCommand>,
        resources: PdfDict?,
        ctm: PdfMatrix,
        depth: Int = 0,
        path: MutableSet<Int> = HashSet()
    ): SanitizedStream {
        val filtered: List<ContentCommand>
        var changed = false
        if (layers != null) {
            val result = filterMarkedContent(commands, resources)
            filtered = result.commands
            changed = result.changed
        } else {
            filtered = commands
        }

        val placements = HashMap<String, PdfMatrix>()
        trackFormPlacements(filtered, resources, ctm, placements)
        val copiedResources = copyResources(resources, ctm, placements, depth, path)

        val hiddenMarks = ArrayList<PdfRect>()
        val runsByCommand = HashMap<Int, MutableList<ScannedTextRun>>()
        if (removeHiddenText) {
            val warnings = ArrayList<String>()
            val runs = TextRunScanner.scan(filtered, resources, document, warnings, ctm)
            if (warnings.isNotEmpty()) {
                for (warning in warnings.take(8)) context.warn(warning)
            }
            for (run in runs) {
                runsByCommand.getOrPut(run.commandIndex) { ArrayList() } += run
                if (isHidden(run)) {
                    hiddenMarks += hiddenMarkFor(run)
                }
            }
            context.hiddenTextRuns += hiddenMarks.size
        }

        if (hiddenMarks.isEmpty()) {
            return SanitizedStream(filtered, PdfDict(copiedResources), changed)
        }

        val output = ArrayList<ContentCommand>(filtered.size)
        for ((index, command) in filtered.withIndex()) {
            if (command.operator !in TEXT_OPERATORS) {
                output += command
                continue
            }
            val runs = runsByCommand[index]
            if (runs == null || runs.isEmpty()) {
                output += command
                continue
            }
            val rewritten = rewriteShowCommand(command, runs, hiddenMarks)
            if (rewritten != null) {
                output += rewritten
                changed = true
            } else {
                output += command
                val hiddenHere = runs.any { isHidden(it) }
                if (hiddenHere) context.warn("hidden text command '${command.operator}' could not be rewritten")
            }
        }
        return SanitizedStream(output, PdfDict(copiedResources), changed)
    }

    // -- Hidden optional content (§8.11) --------------------------------------

    private class MarkedFrame(val hidden: Boolean, val dropMarker: Boolean)

    private class FilteredCommands(val commands: List<ContentCommand>, val changed: Boolean)

    private fun filterMarkedContent(commands: List<ContentCommand>, resources: PdfDict?): FilteredCommands {
        val out = ArrayList<ContentCommand>(commands.size)
        val stack = ArrayDeque<MarkedFrame>()
        var changed = false
        for (command in commands) {
            when (command.operator) {
                "BDC", "BMC" -> {
                    val isOc = command.operator == "BDC" && layers?.isHidden(command, document, resources) == true
                    val tag = (command.operands.getOrNull(0) as? PdfName)?.name
                    val ocMarker = command.operator == "BDC" && tag == "OC"
                    val hiddenByParent = stack.any { it.hidden }
                    stack.addLast(MarkedFrame(hiddenByParent || isOc, ocMarker))
                    if (hiddenByParent) {
                        changed = true
                        continue
                    }
                    if (isOc) {
                        changed = true
                        context.hiddenLayerSections++
                        continue
                    }
                    if (ocMarker) {
                        // A visible layer stays visible; drop only its label.
                        changed = true
                        continue
                    }
                    out += command
                }
                "EMC" -> {
                    val frame = stack.removeLastOrNull()
                    if (frame == null) {
                        out += command
                    } else if (frame.hidden || frame.dropMarker) {
                        changed = true
                    } else {
                        out += command
                    }
                }
                else -> {
                    if (stack.any { it.hidden }) changed = true else out += command
                }
            }
        }
        return FilteredCommands(out, changed)
    }

    // -- Hidden text ----------------------------------------------------------

    private fun isHidden(run: ScannedTextRun): Boolean {
        val mode = run.run.renderMode
        if (mode == 3 || mode == 7) return true
        val color = run.color
        if (color.alpha <= 0.01) return true
        if (color.red >= WHITE_CUTOFF && color.green >= WHITE_CUTOFF && color.blue >= WHITE_CUTOFF) return true
        val rect = rectOf(run)
        return !intersectsPage(rect)
    }

    private fun hiddenMarkFor(run: ScannedTextRun): PdfRect {
        val rect = rectOf(run)
        val pad = 0.01
        return PdfRect(rect.left - pad, rect.bottom - pad, rect.right + pad, rect.top + pad)
    }

    private fun rectOf(run: ScannedTextRun): PdfRect = PdfRect(
        minOf(run.run.x0, run.run.x1),
        minOf(run.run.y0, run.run.y1),
        maxOf(run.run.x0, run.run.x1),
        maxOf(run.run.y0, run.run.y1)
    )

    private fun intersectsPage(rect: PdfRect): Boolean {
        if (pageBox.isEmpty) return true
        val eps = 1e-7
        return rect.right > pageBox.left + eps &&
            rect.left < pageBox.right - eps &&
            rect.top > pageBox.bottom + eps &&
            rect.bottom < pageBox.top - eps
    }

    // -- Forms and resources --------------------------------------------------

    private fun copyResources(
        resources: PdfDict?,
        ctm: PdfMatrix,
        placements: Map<String, PdfMatrix>,
        depth: Int,
        path: MutableSet<Int>
    ): LinkedHashMap<PdfName, PdfObject> {
        val out = LinkedHashMap<PdfName, PdfObject>()
        if (resources == null) return out
        for ((key, value) in resources.entries) {
            if (key.name == "XObject" || (layers != null && key.name == "Properties")) continue
            copier.copyValue(value)?.let { out[key] = it }
        }
        val table = xObjectTable(resources) ?: return out
        val entries = LinkedHashMap<PdfName, PdfObject>()
        for ((name, raw) in table.entries) {
            val stream = document.resolveObject(raw) as? PdfStream
            val replacement = if (stream != null && (stream.dict["Subtype"] as? PdfName)?.name == "Form") {
                val childCtm = placements[name.name] ?: (ctm * formMatrix(stream.dict))
                processForm(raw, stream, childCtm, resources, depth, path)
            } else {
                copyXObject(raw)
            }
            if (replacement != null) entries[name] = replacement
        }
        if (entries.isNotEmpty()) out[PdfName("XObject")] = PdfDict(entries)
        return out
    }

    private fun processForm(
        raw: PdfObject,
        stream: PdfStream,
        childCtm: PdfMatrix,
        parentResources: PdfDict?,
        depth: Int,
        path: MutableSet<Int>
    ): PdfObject? {
        val objNum = (raw as? PdfRef)?.objNum
        if (depth >= MAX_FORM_DEPTH) {
            context.warn("form XObject nesting deeper than $MAX_FORM_DEPTH; left unchanged")
            return copyXObject(raw)
        }
        if (objNum != null && !path.add(objNum)) {
            context.warn("cyclic form XObject $objNum; left unchanged")
            return copyXObject(raw)
        }
        try {
            val bytes = stream.decoded { ref -> document.resolve(ref) }.getOrNull()?.bytes
            if (bytes == null) {
                context.warn("form XObject could not be decoded; left unchanged")
                return copyXObject(raw)
            }
            val parsed = parseContent(bytes)
            val childResources = document.resolveDict(stream.dict["Resources"]) ?: parentResources
            val child = rewrite(parsed.commands, childResources, childCtm, depth + 1, path)
            if (!child.changed) return copyXObject(raw)

            val dict = copier.copyDict(
                stream.dict,
                skipKeys = setOf("Length", "Filter", "DecodeParms", "Resources")
            ) ?: PdfDict(emptyMap())
            val entries = LinkedHashMap(dict.entries)
            entries[PdfName("Type")] = PdfName("XObject")
            entries[PdfName("Subtype")] = PdfName("Form")
            entries[PdfName("Resources")] = child.resources
            entries[PdfName("Filter")] = PdfName("FlateDecode")
            val data = ContentSerializer.serialize(child.commands)
            return builder.addStream(PdfDict(entries), Flate.encode(data))
        } finally {
            if (objNum != null) path.remove(objNum)
        }
    }

    private fun trackFormPlacements(
        commands: List<ContentCommand>,
        resources: PdfDict?,
        initialCtm: PdfMatrix,
        out: MutableMap<String, PdfMatrix>
    ) {
        var ctm = initialCtm
        val stack = ArrayDeque<PdfMatrix>()
        for (command in commands) {
            when (command.operator) {
                "q" -> stack.addLast(ctm)
                "Q" -> ctm = stack.removeLastOrNull() ?: ctm
                "cm" -> matrixOf(command.operands)?.let { ctm = ctm * it }
                "Do" -> {
                    val name = (command.operands.getOrNull(0) as? PdfName)?.name ?: continue
                    val raw = xObjectRaw(resources, name) ?: continue
                    val stream = document.resolveObject(raw) as? PdfStream ?: continue
                    if ((stream.dict["Subtype"] as? PdfName)?.name != "Form") continue
                    out[name] = ctm * formMatrix(stream.dict)
                }
            }
        }
    }

    private fun copyXObject(raw: PdfObject): PdfObject? {
        val copied = copier.copyValue(raw) ?: return null
        return if (copied is PdfStream) builder.add(copied) else copied
    }

    private fun xObjectTable(resources: PdfDict?): PdfDict? =
        document.resolveDict(resources?.get("XObject"))

    private fun xObjectRaw(resources: PdfDict?, name: String): PdfObject? =
        xObjectTable(resources)?.entries?.get(PdfName(name))

    private fun formMatrix(dict: PdfDict): PdfMatrix =
        (document.resolveArray(dict["Matrix"]))?.let { matrixOf(it.items) } ?: PdfMatrix.IDENTITY

    private fun matrixOf(items: List<PdfObject>): PdfMatrix? {
        if (items.size < 6) return null
        val values = DoubleArray(6) { index ->
            when (val value = items[index]) {
                is PdfInteger -> value.value.toDouble()
                is PdfReal -> value.value
                else -> return null
            }
        }
        if (values.any { !it.isFinite() }) return null
        return PdfMatrix(values[0], values[1], values[2], values[3], values[4], values[5])
    }

    private companion object {
        const val MAX_FORM_DEPTH = 16
        const val WHITE_CUTOFF = 0.99
        val TEXT_OPERATORS = setOf("Tj", "'", "\"", "TJ")
    }
}
