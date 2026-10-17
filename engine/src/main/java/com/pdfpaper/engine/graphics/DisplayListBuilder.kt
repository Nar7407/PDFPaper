package com.pdfpaper.engine.graphics

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.image.PdfImages
import com.pdfpaper.engine.image.RasterImage
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
import com.pdfpaper.engine.text.PdfFontCache
import com.pdfpaper.engine.text.PdfTextRun
import com.pdfpaper.engine.text.TextMachine
import com.pdfpaper.engine.text.TextStateSnapshot

/**
 * Interprets a page's content streams into a [DisplayList] (ISO 32000-1 §8
 * graphics, §9 text). Tolerant like the rest of the engine: bad operands are
 * skipped with a warning and interpretation continues.
 *
 * Form XObjects are expanded in place, inheriting the caller's graphics state
 * with the CTM concatenated with /Matrix, per §8.10.1 (unbalanced q/Q inside a
 * form is tolerated).
 */
internal class DisplayListBuilder(
    private val page: PdfPage,
    private val decoder: ExternalImageDecoder?
) {

    private val doc: PdfDocument = page.document
    private val warnings = ArrayList<String>()
    private val commands = ArrayList<DrawCommand>()
    private val warned = HashSet<String>()
    private val fonts = PdfFontCache(doc, warnings)
    private val machine = TextMachine({ name -> fonts.fontFor(currentResources, name) }, warnings)
    private var currentResources: PdfDict? = null

    private class SavedState(
        val graphics: GraphicsState,
        val text: TextStateSnapshot,
        val resources: PdfDict?
    )

    fun build(): DisplayList {
        val contents = page.contents().fold(
            onSuccess = { it },
            onFailure = {
                warnOnce("contents", "contents failed: ${it.message}")
                null
            }
        ) ?: return DisplayList(emptyList(), warnings)
        contents.warnings.mapTo(warnings) { "content: $it" }

        val parsed = parseContent(contents.bytes)
        parsed.warnings.mapTo(warnings) { "content: $it" }

        walk(parsed.commands, page.resources, GraphicsState(), 0)
        return DisplayList(commands, warnings)
    }

    private fun warnOnce(key: String, message: String) {
        if (warned.add(key)) warnings += message
    }

    private fun walk(
        input: List<ContentCommand>,
        resources: PdfDict?,
        initialState: GraphicsState,
        depth: Int
    ) {
        if (depth > MAX_FORM_DEPTH) {
            warnOnce("form-depth", "form XObject nesting deeper than $MAX_FORM_DEPTH, ignored")
            return
        }
        val previousResources = currentResources
        currentResources = resources
        val state = initialState
        val stack = ArrayDeque<SavedState>()
        val segments = ArrayList<PathSegment>()
        var currentX = 0.0
        var currentY = 0.0
        var pendingClip = false
        var clipEvenOdd = false

        fun pathNow(): PdfPath = PdfPath(ArrayList(segments))

        fun flushClip() {
            if (!pendingClip) return
            if (segments.isNotEmpty()) commands += ClipPath(pathNow(), state.ctm, clipEvenOdd)
            pendingClip = false
            clipEvenOdd = false
        }

        fun clearPath() {
            segments.clear()
            currentX = 0.0
            currentY = 0.0
        }

        fun closeSubpath() {
            if (segments.isNotEmpty()) segments += ClosePath
        }

        fun fill(evenOdd: Boolean) {
            commands += FillPath(pathNow(), state.ctm, state.fillColor(), state.fillAlpha, evenOdd)
        }

        fun stroke() {
            commands += StrokePath(pathNow(), state.ctm, state.strokeColor(), state.strokeAlpha, state.lineStyle)
        }

        fun both(evenOdd: Boolean) {
            commands += FillStrokePath(
                pathNow(), state.ctm,
                state.fillColor(), state.fillAlpha,
                state.strokeColor(), state.strokeAlpha,
                evenOdd, state.lineStyle
            )
        }

        fun paint(op: String) {
            val closes = op == "s" || op == "b" || op == "b*"
            if (closes) closeSubpath()
            flushClip()
            if (segments.isNotEmpty()) {
                when (op) {
                    "S", "s" -> stroke()
                    "f", "F" -> fill(false)
                    "f*" -> fill(true)
                    "B", "b" -> both(false)
                    "B*", "b*" -> both(true)
                    "n" -> {}
                }
            }
            clearPath()
        }

        fun setComponents(stroke: Boolean, operands: List<PdfObject>) {
            if (operands.any { it is PdfName }) {
                warnOnce("pattern-color", "pattern colour spaces are not supported yet")
                return
            }
            val values = operands.mapNotNull { toDouble(it) }.toDoubleArray()
            if (values.isEmpty()) return
            if (stroke) state.strokeComponents = values else state.fillComponents = values
        }

        fun setSpace(stroke: Boolean, obj: PdfObject?) {
            val resolved = ColorSpaces.resolve(obj, resources, doc)
            warnings += resolved.warnings
            val space = resolved.space
            if (space == null) {
                warnOnce("cs-unsupported", "unsupported colour space, keeping the previous one")
                return
            }
            if (stroke) state.strokeColorSpace = space else state.fillColorSpace = space
        }

        for (command in input) {
            val ops = command.operands
            when (command.operator) {
                // --- graphics state (§8.4.4) --------------------------------
                "q" -> {
                    commands += SaveGraphicsState(state.ctm, state.blendMode)
                    stack.addLast(SavedState(state.copy(), machine.snapshot(), resources))
                }
                "Q" -> {
                    val saved = stack.removeLastOrNull()
                    if (saved == null) warnOnce("q-underflow", "Q without matching q")
                    else {
                        copyInto(state, saved.graphics)
                        machine.restore(saved.text)
                        commands += RestoreGraphicsState(state.ctm)
                    }
                }
                "cm" -> matrixOf(ops)?.let { state.ctm = state.ctm * it }
                "w" -> toDouble(ops.getOrNull(0))?.let { state.lineStyle = state.lineStyle.copy(width = it) }
                "J" -> toDouble(ops.getOrNull(0))?.let { state.lineStyle = state.lineStyle.copy(cap = it.toInt()) }
                "j" -> toDouble(ops.getOrNull(0))?.let { state.lineStyle = state.lineStyle.copy(join = it.toInt()) }
                "M" -> toDouble(ops.getOrNull(0))?.let { state.lineStyle = state.lineStyle.copy(miterLimit = it) }
                "d" -> {
                    val array = (ops.getOrNull(0) as? PdfArray)?.items
                        ?.mapNotNull { toDouble(it) }?.toDoubleArray() ?: DoubleArray(0)
                    val phase = toDouble(ops.getOrNull(1)) ?: 0.0
                    state.lineStyle = state.lineStyle.copy(dashArray = array, dashPhase = phase)
                }
                "ri", "i" -> {} // rendering intent / flatness: no effect on the display list

                // --- path construction (§8.5.2) -----------------------------
                "m" -> {
                    val x = toDouble(ops.getOrNull(0)) ?: 0.0
                    val y = toDouble(ops.getOrNull(1)) ?: 0.0
                    segments += MoveTo(x, y)
                    currentX = x
                    currentY = y
                }
                "l" -> {
                    val x = toDouble(ops.getOrNull(0)) ?: currentX
                    val y = toDouble(ops.getOrNull(1)) ?: currentY
                    segments += LineTo(x, y)
                    currentX = x
                    currentY = y
                }
                "c" -> {
                    val x1 = toDouble(ops.getOrNull(0)) ?: currentX
                    val y1 = toDouble(ops.getOrNull(1)) ?: currentY
                    val x2 = toDouble(ops.getOrNull(2)) ?: currentX
                    val y2 = toDouble(ops.getOrNull(3)) ?: currentY
                    val x3 = toDouble(ops.getOrNull(4)) ?: currentX
                    val y3 = toDouble(ops.getOrNull(5)) ?: currentY
                    segments += CurveTo(x1, y1, x2, y2, x3, y3)
                    currentX = x3
                    currentY = y3
                }
                "v" -> {
                    // First control point = current point (§8.5.2.2).
                    val x2 = toDouble(ops.getOrNull(0)) ?: currentX
                    val y2 = toDouble(ops.getOrNull(1)) ?: currentY
                    val x3 = toDouble(ops.getOrNull(2)) ?: currentX
                    val y3 = toDouble(ops.getOrNull(3)) ?: currentY
                    segments += CurveTo(currentX, currentY, x2, y2, x3, y3)
                    currentX = x3
                    currentY = y3
                }
                "y" -> {
                    // Last control point = end point (§8.5.2.2).
                    val x1 = toDouble(ops.getOrNull(0)) ?: currentX
                    val y1 = toDouble(ops.getOrNull(1)) ?: currentY
                    val x3 = toDouble(ops.getOrNull(2)) ?: currentX
                    val y3 = toDouble(ops.getOrNull(3)) ?: currentY
                    segments += CurveTo(x1, y1, x3, y3, x3, y3)
                    currentX = x3
                    currentY = y3
                }
                "h" -> closeSubpath()
                "re" -> {
                    val x = toDouble(ops.getOrNull(0)) ?: 0.0
                    val y = toDouble(ops.getOrNull(1)) ?: 0.0
                    val w = toDouble(ops.getOrNull(2)) ?: 0.0
                    val h = toDouble(ops.getOrNull(3)) ?: 0.0
                    segments += MoveTo(x, y)
                    segments += LineTo(x + w, y)
                    segments += LineTo(x + w, y + h)
                    segments += LineTo(x, y + h)
                    segments += ClosePath
                    currentX = x
                    currentY = y
                }

                // --- clipping and painting (§8.5.3-8.5.4) -------------------
                "W" -> {
                    pendingClip = true
                    clipEvenOdd = false
                }
                "W*" -> {
                    pendingClip = true
                    clipEvenOdd = true
                }
                "S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n" -> paint(command.operator)

                // --- colour (§8.6.8) ----------------------------------------
                "g" -> {
                    state.fillColorSpace = DeviceGrayCS
                    setComponents(false, ops)
                }
                "G" -> {
                    state.strokeColorSpace = DeviceGrayCS
                    setComponents(true, ops)
                }
                "rg" -> {
                    state.fillColorSpace = DeviceRgbCS
                    setComponents(false, ops)
                }
                "RG" -> {
                    state.strokeColorSpace = DeviceRgbCS
                    setComponents(true, ops)
                }
                "k" -> {
                    state.fillColorSpace = DeviceCmykCS
                    setComponents(false, ops)
                }
                "K" -> {
                    state.strokeColorSpace = DeviceCmykCS
                    setComponents(true, ops)
                }
                "cs" -> setSpace(false, ops.getOrNull(0))
                "CS" -> setSpace(true, ops.getOrNull(0))
                "sc", "scn" -> setComponents(false, ops)
                "SC", "SCN" -> setComponents(true, ops)

                // --- extended graphics state (§8.4.5) -----------------------
                "gs" -> {
                    val name = (ops.getOrNull(0) as? PdfName)?.name
                    if (name != null) applyExtGState(name, resources, state) else warnOnce("gs-noname", "gs without a name")
                }

                // --- external objects (§8.8-8.10) ---------------------------
                "Do" -> {
                    val name = (ops.getOrNull(0) as? PdfName)?.name
                    if (name != null) drawXObject(name, resources, state, depth)
                    else warnOnce("do-noname", "Do without a name operand")
                }
                "ID" -> drawInlineImage((ops.firstOrNull() as? com.pdfpaper.engine.model.PdfInlineImage), resources, state)

                "sh" -> warnOnce("shading", "shading patterns (sh) are not supported yet")
                "BX", "EX", "MP", "DP", "BMC", "BDC", "EMC" -> {} // marked content / compatibility: skipped

                else -> {
                    val produced = ArrayList<PdfTextRun>()
                    machine.handle(command, state.ctm, produced)
                    for (run in produced) commands += DrawTextRun(run, state.ctm)
                }
            }
        }
        currentResources = previousResources
    }

    /** /ExtGState entries that affect painting (§8.4.5, Table 58). */
    private fun applyExtGState(name: String, resources: PdfDict?, state: GraphicsState) {
        val dict = dictEntry(resources, "ExtGState", name) ?: run {
            warnOnce("extgstate-$name", "ExtGState '/$name' not found in resources")
            return
        }
        toDouble(dict["ca"])?.let { alpha -> state.fillAlpha = alpha.coerceIn(0.0, 1.0) }
        toDouble(dict["CA"])?.let { alpha -> state.strokeAlpha = alpha.coerceIn(0.0, 1.0) }
        (dict["BM"] as? PdfName)?.name?.let { mode -> state.blendMode = mode }
        toDouble(dict["LW"])?.let { width -> state.lineStyle = state.lineStyle.copy(width = width) }
        toDouble(dict["LC"])?.let { cap -> state.lineStyle = state.lineStyle.copy(cap = cap.toInt()) }
        toDouble(dict["LJ"])?.let { join -> state.lineStyle = state.lineStyle.copy(join = join.toInt()) }
        toDouble(dict["ML"])?.let { limit -> state.lineStyle = state.lineStyle.copy(miterLimit = limit) }
        (dict["D"] as? PdfArray)?.let { dash ->
            val array = dash.items.firstOrNull()
            val phase = dash.items.getOrNull(1)
            val values = (array as? PdfArray)?.items?.mapNotNull { toDouble(it) }?.toDoubleArray()
            if (values != null) {
                state.lineStyle = state.lineStyle.copy(
                    dashArray = values,
                    dashPhase = toDouble(phase) ?: 0.0
                )
            }
        }
        if (dict["SMask"] != null) warnOnce("extgstate-smask", "ExtGState /SMask (transparency group) is not supported yet")
        if (dict["Font"] != null) warnOnce("extgstate-font", "ExtGState /Font is ignored (text state comes from Tf)")
    }

    private fun drawXObject(name: String, resources: PdfDict?, state: GraphicsState, depth: Int) {
        val stream = when (val raw = xObjectRaw(resources, name)) {
            is PdfRef -> doc.resolve(raw) as? PdfStream
            is PdfStream -> raw
            else -> null
        }
        if (stream == null) {
            warnOnce("xobject-$name", "XObject '/$name' not found in resources")
            return
        }
        val dict = stream.dict
        when ((dict["Subtype"] as? PdfName)?.name) {
            "Image" -> {
                val result = PdfImages.decodeImage(stream.dict, stream.data, resources, doc, decoder)
                warnings += result.warnings
                val image = result.image ?: return
                commands += DrawRasterImage(image, state.ctm, imageFillColor(image, state), state.fillAlpha)
            }
            "Form" -> drawForm(stream, state, depth)
            else -> warnOnce("xobject-subtype-$name", "XObject '/$name' has an unsupported subtype")
        }
    }

    /** Image XObjects are painted with the current transform (§8.9.5.2). */
    private fun drawForm(stream: PdfStream, state: GraphicsState, depth: Int) {
        val formDict = stream.dict
        val formMatrix = matrixOf((formDict["Matrix"] as? PdfArray)?.items ?: emptyList()) ?: PdfMatrix.IDENTITY
        val formCtm = state.ctm * formMatrix

        commands += SaveGraphicsState(formCtm, state.blendMode)
        val bbox = (formDict["BBox"] as? PdfArray)?.items?.mapNotNull { toDouble(it) }
        if (bbox != null && bbox.size >= 4) {
            commands += ClipPath(
                PdfPath.rectangle(bbox[0], bbox[1], bbox[2], bbox[3]),
                formCtm,
                evenOdd = false
            )
        }

        val resources = when (val resObj = formDict["Resources"]) {
            null -> currentResources // §8.10.1: a form without /Resources inherits the caller's
            is PdfDict -> resObj
            is PdfRef -> doc.resolve(resObj) as? PdfDict ?: currentResources
            else -> currentResources
        }

        val decoded = stream.decoded { doc.resolve(it) }
        val bytes = decoded.getOrNull()?.bytes
        if (decoded.isFailure || bytes == null) {
            warnOnce("form-decode-$depth", "form XObject content could not be decoded")
            commands += RestoreGraphicsState(formCtm)
            return
        }
        decoded.getOrNull()?.warnings?.mapTo(warnings) { "form: $it" }
        val parsed = parseContent(bytes)
        parsed.warnings.mapTo(warnings) { "form: $it" }

        val inner = state.copy()
        inner.ctm = formCtm
        val textSnapshot = machine.snapshot()
        walk(parsed.commands, resources, inner, depth + 1)
        machine.restore(textSnapshot)
        commands += RestoreGraphicsState(formCtm)
    }

    private fun drawInlineImage(
        inline: com.pdfpaper.engine.model.PdfInlineImage?,
        resources: PdfDict?,
        state: GraphicsState
    ) {
        if (inline == null) {
            warnOnce("inline-image", "inline image command without data")
            return
        }
        val dict = PdfImages.normalizeInlineDict(inline.dict)
        val result = PdfImages.decodeImage(dict, inline.data, resources, doc, decoder)
        warnings += result.warnings
        val image = result.image ?: return
        commands += DrawRasterImage(image, state.ctm, imageFillColor(image, state), state.fillAlpha)
    }

    private fun imageFillColor(image: RasterImage, state: GraphicsState): PdfColor =
        if (image.stencil) state.fillColor() else PdfColor.WHITE

    /** Resolves a name in a sub-dictionary of [resources] to a dictionary. */
    private fun dictEntry(resources: PdfDict?, subDict: String, name: String): PdfDict? {
        val table = when (val raw = resources?.get(subDict)) {
            is PdfDict -> raw
            is PdfRef -> doc.resolve(raw) as? PdfDict
            else -> null
        } ?: return null
        return when (val entry = table[name]) {
            is PdfDict -> entry
            is PdfRef -> doc.resolve(entry) as? PdfDict
            else -> null
        }
    }

    /** XObject table entry before resolution (may be a stream). */
    private fun xObjectRaw(resources: PdfDict?, name: String): PdfObject? {
        val table = when (val raw = resources?.get("XObject")) {
            is PdfDict -> raw
            is PdfRef -> doc.resolve(raw) as? PdfDict
            else -> null
        } ?: return null
        return table[name]
    }

    private fun copyInto(target: GraphicsState, source: GraphicsState) {
        target.ctm = source.ctm
        target.lineStyle = source.lineStyle
        target.strokeColorSpace = source.strokeColorSpace
        target.fillColorSpace = source.fillColorSpace
        target.strokeComponents = source.strokeComponents.copyOf()
        target.fillComponents = source.fillComponents.copyOf()
        target.fillAlpha = source.fillAlpha
        target.strokeAlpha = source.strokeAlpha
        target.blendMode = source.blendMode
    }

    private fun matrixOf(ops: List<PdfObject>): PdfMatrix? {
        val values = ops.take(6).map { toDouble(it) }
        if (values.size < 6 || values.any { it == null }) return null
        return PdfMatrix(values[0]!!, values[1]!!, values[2]!!, values[3]!!, values[4]!!, values[5]!!)
    }

    private fun toDouble(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }

    private companion object {
        const val MAX_FORM_DEPTH = 16
    }
}
