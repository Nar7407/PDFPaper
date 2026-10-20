package com.pdfpaper.engine.edit

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.graphics.ColorSpaces
import com.pdfpaper.engine.graphics.DeviceCmykCS
import com.pdfpaper.engine.graphics.DeviceGrayCS
import com.pdfpaper.engine.graphics.DeviceRgbCS
import com.pdfpaper.engine.graphics.GraphicsState
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.graphics.PdfColorSpace
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.text.PdfFont
import com.pdfpaper.engine.text.PdfFontCache
import com.pdfpaper.engine.text.PdfTextRun
import com.pdfpaper.engine.text.TextMachine

/**
 * One T7 text run as seen by the editor (task T16): the run itself plus the
 * content location and text state needed to rewrite its operand (§9.4.3).
 */
internal class ScannedTextRun(
    val id: Int,
    val run: PdfTextRun,
    /** Index of the text-showing command in the page's command list. */
    val commandIndex: Int,
    /** /Resources /Font name that selected the run's font, e.g. "F1". */
    val resourceName: String?,
    /** Resolved font, or null when none was set before the show. */
    val font: PdfFont?,
    val charSpace: Double,
    val wordSpace: Double,
    /** Horizontal scaling Tz as a percentage (§9.3.4). */
    val scale: Double,
    val color: PdfColor,
    val ctm: PdfMatrix
)

/**
 * Walks a page's own content commands and produces the editable text runs
 * (task T16). It reuses [TextMachine] for run production (§9.4) and tracks the
 * graphics state only far enough to report each run's fill colour (§8.6) and
 * CTM (§8.4). Form XObjects are not descended into, matching T7 extraction.
 */
internal object TextRunScanner {

    fun scan(
        commands: List<ContentCommand>,
        resources: PdfDict?,
        doc: PdfDocument,
        warnings: MutableList<String>,
        initialCtm: PdfMatrix = PdfMatrix.IDENTITY
    ): List<ScannedTextRun> {
        val fonts = PdfFontCache(doc, warnings)
        val machine = TextMachine({ name -> fonts.fontFor(resources, name) }, warnings)

        val out = ArrayList<ScannedTextRun>()
        var ctm = initialCtm
        val ctmStack = ArrayDeque<PdfMatrix>()
        var state = GraphicsState()
        val stateStack = ArrayDeque<GraphicsState>()
        var resourceName: String? = null

        for ((index, command) in commands.withIndex()) {
            when (command.operator) {
                "q" -> {
                    ctmStack.addLast(ctm)
                    stateStack.addLast(state.copy())
                }
                "Q" -> {
                    ctm = ctmStack.removeLastOrNull() ?: ctm
                    stateStack.removeLastOrNull()?.let { state = it }
                }
                "cm" -> ImagePlacements.matrixOf(command.operands)?.let { ctm = ctm * it }
                "g" -> {
                    state.fillColorSpace = DeviceGrayCS
                    state.fillComponents = doubleArrayOf(number(command.operands.getOrNull(0)))
                }
                "rg" -> {
                    state.fillColorSpace = DeviceRgbCS
                    state.fillComponents = DoubleArray(3) { number(command.operands.getOrNull(it)) }
                }
                "k" -> {
                    state.fillColorSpace = DeviceCmykCS
                    state.fillComponents = DoubleArray(4) { number(command.operands.getOrNull(it)) }
                }
                "cs" -> state.fillColorSpace =
                    resolveColorSpace(command.operands.getOrNull(0) as? PdfName, resources, doc)
                "sc", "scn" -> componentsOf(command.operands)?.let { state.fillComponents = it }
                "Tf" -> resourceName = (command.operands.getOrNull(0) as? PdfName)?.name
            }

            val produced = ArrayList<PdfTextRun>()
            machine.handle(command, ctm, produced)
            for (run in produced) {
                if (run.source == null) continue
                out += ScannedTextRun(
                    id = out.size,
                    run = run,
                    commandIndex = index,
                    resourceName = resourceName,
                    font = machine.activeFont,
                    charSpace = machine.activeCharSpace,
                    wordSpace = machine.activeWordSpace,
                    scale = machine.activeScale,
                    color = state.fillColor(),
                    ctm = ctm
                )
            }
        }
        return out
    }

    private fun resolveColorSpace(name: PdfName?, resources: PdfDict?, doc: PdfDocument): PdfColorSpace? =
        when (name?.name) {
            "DeviceGray", "G" -> DeviceGrayCS
            "DeviceRGB", "RGB" -> DeviceRgbCS
            "DeviceCMYK", "CMYK" -> DeviceCmykCS
            null -> null
            else -> {
                val table = resolveDict(resources?.get("ColorSpace"), doc)
                ColorSpaces.resolve(table?.get(name.name), resources, doc).space
            }
        }

    private fun componentsOf(operands: List<PdfObject>): DoubleArray? {
        val values = operands.mapNotNull { op ->
            when (op) {
                is PdfInteger -> op.value.toDouble()
                is PdfReal -> op.value
                else -> null
            }
        }
        return if (values.isEmpty()) null else values.toDoubleArray()
    }

    private fun number(obj: PdfObject?): Double = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> 0.0
    }

    private fun resolveDict(obj: PdfObject?, doc: PdfDocument): PdfDict? = when (obj) {
        is PdfDict -> obj
        is PdfRef -> doc.resolve(obj) as? PdfDict
        else -> null
    }
}
