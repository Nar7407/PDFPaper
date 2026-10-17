package com.pdfpaper.engine.graphics

import com.pdfpaper.engine.annot.AnnotationType
import com.pdfpaper.engine.annot.PdfAnnotation
import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
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

    /**
     * Builds a display list that paints the page's annotations (§12.5). An
     * annotation with a normal appearance stream is replayed through the
     * appearance transform (§12.5.5); one without gets a synthetic appearance
     * from its geometry so highlight, underline, strikeout, ink, note, stamp and
     * link still render. Hidden annotations are skipped.
     */
    fun buildAnnotations(annotations: List<PdfAnnotation>): DisplayList {
        currentResources = page.resources
        for (annot in annotations) paintAnnotation(annot)
        return DisplayList(commands, warnings)
    }

    private fun paintAnnotation(annot: PdfAnnotation) {
        if (annot.hidden) return
        val rect = annot.rect ?: return
        val ap = annot.appearance
        if (ap != null && (ap.dict["Subtype"] as? PdfName)?.name == "Form") {
            val bbox = (ap.dict["BBox"] as? PdfArray)?.items?.mapNotNull { toDouble(it) }
            val state = GraphicsState()
            state.ctm = appearanceMatrix(rect, bbox)
            drawForm(ap, state, 0)
            return
        }
        when (annot.type) {
            AnnotationType.HIGHLIGHT -> paintQuads(annot, rect)
            AnnotationType.UNDERLINE, AnnotationType.SQUIGGLY ->
                paintQuadLine(annot, rect, atBottom = true)
            AnnotationType.STRIKEOUT -> paintQuadLine(annot, rect, atBottom = false)
            AnnotationType.INK -> paintInk(annot)
            AnnotationType.LINK ->
                if (annot.hasVisibleBorder) {
                    paintBox(rect, annot.color ?: PdfColor.BLACK, annot.borderWidth ?: 1.0, filled = false)
                }
            AnnotationType.TEXT -> paintNoteIcon(rect, annot.color ?: DEFAULT_NOTE_COLOR)
            AnnotationType.STAMP ->
                paintBox(rect, annot.color ?: DEFAULT_STAMP_COLOR, annot.borderWidth ?: 1.0, filled = true)
            AnnotationType.FREE_TEXT, AnnotationType.SQUARE ->
                paintBox(rect, annot.color ?: PdfColor.BLACK, annot.borderWidth ?: 1.0, filled = false)
            AnnotationType.CIRCLE ->
                paintCircle(rect, annot.color ?: PdfColor.BLACK, annot.borderWidth ?: 1.0)
            AnnotationType.WIDGET ->
                paintBox(rect, annot.color ?: DEFAULT_WIDGET_COLOR, annot.borderWidth ?: 1.0, filled = false)
            else -> {} // unsupported subtypes paint nothing rather than guess
        }
    }

    /** Highlight: fill each quadrilateral with the annotation colour (Table 164). */
    private fun paintQuads(annot: PdfAnnotation, fallback: PdfRect) {
        val color = annot.color ?: DEFAULT_HIGHLIGHT_COLOR
        val rects = annot.quadBounds().ifEmpty { listOf(fallback) }
        for (r in rects) {
            if (r.isEmpty) continue
            commands += SaveGraphicsState(PdfMatrix.IDENTITY)
            commands += SetBlendMode("Multiply", PdfMatrix.IDENTITY)
            commands += FillPath(
                PdfPath.rectangle(r.left, r.bottom, r.right, r.top),
                PdfMatrix.IDENTITY, color, HIGHLIGHT_ALPHA, false
            )
            commands += RestoreGraphicsState(PdfMatrix.IDENTITY)
        }
    }

    /** Underline / strikeout: a rule along the bottom edge or the midline. */
    private fun paintQuadLine(annot: PdfAnnotation, fallback: PdfRect, atBottom: Boolean) {
        val color = annot.color ?: DEFAULT_HIGHLIGHT_COLOR
        val width = (annot.borderWidth ?: 0.0).takeIf { it > 0.0 } ?: 1.0
        val rects = annot.quadBounds().ifEmpty { listOf(fallback) }
        for (r in rects) {
            if (r.isEmpty) continue
            val y = if (atBottom) r.bottom + r.height * 0.1 else r.bottom + r.height * 0.5
            val path = PdfPath(listOf(MoveTo(r.left, y), LineTo(r.right, y)))
            commands += StrokePath(path, PdfMatrix.IDENTITY, color, 1.0, LineStyle(width = width))
        }
    }

    /** Ink: stroke each /InkList polyline (§12.5.6.13). */
    private fun paintInk(annot: PdfAnnotation) {
        val color = annot.color ?: PdfColor.BLACK
        val width = (annot.borderWidth ?: 0.0).takeIf { it > 0.0 } ?: 1.0
        for (stroke in annot.inkLists) {
            if (stroke.size < 4) continue
            val segments = ArrayList<PathSegment>(stroke.size / 2)
            var i = 0
            while (i + 1 < stroke.size) {
                val x = stroke[i]
                val y = stroke[i + 1]
                segments += if (segments.isEmpty()) MoveTo(x, y) else LineTo(x, y)
                i += 2
            }
            commands += StrokePath(
                PdfPath(segments), PdfMatrix.IDENTITY, color, 1.0,
                LineStyle(width = width, cap = 1, join = 1)
            )
        }
    }

    private fun paintNoteIcon(rect: PdfRect, color: PdfColor) {
        if (rect.isEmpty) return
        val box = PdfPath.rectangle(rect.left, rect.bottom, rect.right, rect.top)
        commands += FillPath(box, PdfMatrix.IDENTITY, color, 1.0, false)
        commands += StrokePath(box, PdfMatrix.IDENTITY, PdfColor.BLACK, 1.0, LineStyle(width = 0.75))
        val fold = minOf(rect.width, rect.height) * 0.35
        val foldPath = PdfPath(
            listOf(
                MoveTo(rect.right - fold, rect.top),
                LineTo(rect.right - fold, rect.top - fold),
                LineTo(rect.right, rect.top - fold)
            )
        )
        commands += StrokePath(foldPath, PdfMatrix.IDENTITY, PdfColor.BLACK, 1.0, LineStyle(width = 0.75))
    }

    private fun paintBox(rect: PdfRect, color: PdfColor, width: Double, filled: Boolean) {
        if (rect.isEmpty) return
        val path = PdfPath.rectangle(rect.left, rect.bottom, rect.right, rect.top)
        if (filled) commands += FillPath(path, PdfMatrix.IDENTITY, color, 0.35, false)
        commands += StrokePath(path, PdfMatrix.IDENTITY, color, 1.0, LineStyle(width = width.coerceAtLeast(0.5)))
    }

    /** Circle approximated with four cubic Béziers (§8.5.2.2). */
    private fun paintCircle(rect: PdfRect, color: PdfColor, width: Double) {
        if (rect.isEmpty) return
        val cx = rect.centerX
        val cy = rect.centerY
        val rx = rect.width / 2.0
        val ry = rect.height / 2.0
        val k = 0.5522847498307936
        val path = PdfPath(
            listOf(
                MoveTo(cx, cy + ry),
                CurveTo(cx + k * rx, cy + ry, cx + rx, cy + k * ry, cx + rx, cy),
                CurveTo(cx + rx, cy - k * ry, cx + k * rx, cy - ry, cx, cy - ry),
                CurveTo(cx - k * rx, cy - ry, cx - rx, cy - k * ry, cx - rx, cy),
                CurveTo(cx - rx, cy + k * ry, cx - k * rx, cy + ry, cx, cy + ry),
                ClosePath
            )
        )
        commands += StrokePath(path, PdfMatrix.IDENTITY, color, 1.0, LineStyle(width = width.coerceAtLeast(0.5)))
    }

    /**
     * Appearance → page matrix for an annotation (§12.5.5): the appearance's
     * /BBox is scaled and translated onto the annotation /Rect, then the
     * appearance's own /Matrix is applied by [drawForm].
     */
    private fun appearanceMatrix(rect: PdfRect, bbox: List<Double>?): PdfMatrix {
        val b = if (bbox != null && bbox.size >= 4) bbox else
            listOf(0.0, 0.0, rect.width, rect.height)
        val bw = b[2] - b[0]
        val bh = b[3] - b[1]
        val sx = if (bw != 0.0) rect.width / bw else 1.0
        val sy = if (bh != 0.0) rect.height / bh else 1.0
        return PdfMatrix.translation(rect.left, rect.bottom) *
            PdfMatrix.scaling(sx, sy) *
            PdfMatrix.translation(-b[0], -b[1])
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
                    if (name != null) {
                        val blend = applyExtGState(name, resources, state)
                        // /BM takes effect from here, not only at the next q,
                        // so it goes out as its own command (§11.6.3).
                        if (blend != null) commands += SetBlendMode(blend, state.ctm)
                    } else {
                        warnOnce("gs-noname", "gs without a name")
                    }
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

    /**
     * /ExtGState entries that affect painting (§8.4.5, Table 58).
     * Returns the blend mode when the entry changed it, so the caller can emit
     * a [SetBlendMode] command; null when there was no /BM.
     */
    private fun applyExtGState(name: String, resources: PdfDict?, state: GraphicsState): String? {
        val dict = dictEntry(resources, "ExtGState", name) ?: run {
            warnOnce("extgstate-$name", "ExtGState '/$name' not found in resources")
            return null
        }
        toDouble(dict["ca"])?.let { alpha -> state.fillAlpha = alpha.coerceIn(0.0, 1.0) }
        toDouble(dict["CA"])?.let { alpha -> state.strokeAlpha = alpha.coerceIn(0.0, 1.0) }
        val blend = (dict["BM"] as? PdfName)?.name
        if (blend != null) state.blendMode = blend
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
        return blend
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
        const val HIGHLIGHT_ALPHA = 0.5
        val DEFAULT_HIGHLIGHT_COLOR = PdfColor(1.0, 0.92, 0.23)
        val DEFAULT_NOTE_COLOR = PdfColor(1.0, 0.87, 0.0)
        val DEFAULT_STAMP_COLOR = PdfColor(0.85, 0.2, 0.2)
        val DEFAULT_WIDGET_COLOR = PdfColor(0.6, 0.6, 0.6)
    }
}
