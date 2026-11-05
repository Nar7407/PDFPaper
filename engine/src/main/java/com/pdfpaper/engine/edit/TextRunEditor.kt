package com.pdfpaper.engine.edit

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.text.shapeText
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Applies text-run edits to a page's content commands (task T16, §9.4).
 *
 * A run maps to exactly one string operand. Edits keep every other character in
 * the line where it was: the text matrix a show leaves behind is reproduced by a
 * compensating `TJ` shift (a numeric-only TJ moves the text matrix without
 * painting, §9.4.3), so only the edited run changes and nothing reflows.
 *
 * - replace, font can encode the text → the operand string is rewritten in place;
 * - replace/delete, otherwise → the operand is removed and, for a replacement,
 *   the new text is drawn by an appended block with the bundled fallback font;
 * - move → the operand is removed and the same text re-drawn at the new origin
 *   with the run's own font (or the fallback).
 *
 * Malformed operands are left untouched; the editor never throws on them.
 */
internal class TextRunEditor(
    private val commands: List<ContentCommand>,
    private val runs: List<ScannedTextRun>,
    private val pageIndex: Int,
    /** Allocates/reuses the page-local resource name of the fallback font. */
    private val fallbackName: () -> PdfName,
    private val reports: MutableList<TextEditReport>
) {

    private class Intent {
        var replace: String? = null
        var deleted = false
        var moved = false
        var dx = 0.0
        var dy = 0.0
    }

    fun apply(edits: List<PageEdit>): List<ContentCommand> {
        val intents = LinkedHashMap<Int, Intent>()
        for (edit in edits) {
            when (edit) {
                is ReplaceTextEdit -> intentFor(intents, edit.runId).replace = edit.text
                is DeleteTextEdit -> intentFor(intents, edit.runId).deleted = true
                is MoveTextEdit -> intentFor(intents, edit.runId).apply {
                    moved = true
                    dx += edit.dx
                    dy += edit.dy
                }
                else -> {}
            }
        }
        if (intents.isEmpty()) return commands

        val byId = runs.associateBy { it.id }
        val byCommand = HashMap<Int, MutableList<Pair<ScannedTextRun, Intent>>>()
        for ((runId, intent) in intents) {
            val run = byId[runId]
            if (run == null) {
                reports += TextEditReport(
                    pageIndex, runId, operationOf(intent), TextEditStrategy.NONE,
                    note = "run $runId not found on this page"
                )
                continue
            }
            byCommand.getOrPut(run.commandIndex) { ArrayList() } += run to intent
        }

        val appended = ArrayList<ContentCommand>()
        val output = ArrayList<ContentCommand>(commands.size + 16)
        for ((index, command) in commands.withIndex()) {
            val group = byCommand[index]
            if (group == null) {
                output += command
            } else {
                output += emit(command, group, appended)
            }
        }
        output += appended
        return output
    }

    private fun intentFor(intents: LinkedHashMap<Int, Intent>, runId: Int): Intent =
        intents.getOrPut(runId) { Intent() }

    private fun operationOf(intent: Intent): String = when {
        intent.deleted -> "delete"
        intent.replace != null -> "replace"
        intent.moved -> "move"
        else -> "none"
    }

    // -- Command rewriting ----------------------------------------------------

    private fun emit(
        command: ContentCommand,
        group: List<Pair<ScannedTextRun, Intent>>,
        appended: MutableList<ContentCommand>
    ): List<ContentCommand> = when (command.operator) {
        "Tj", "'", "\"" -> emitSingle(command, group.first())
        "TJ" -> emitArray(command, group, appended)
        else -> listOf(command)
    }

    private fun emitSingle(
        command: ContentCommand,
        entry: Pair<ScannedTextRun, Intent>
    ): List<ContentCommand> {
        val (run, intent) = entry
        if (intent.deleted) {
            report(run, "delete", TextEditStrategy.REMOVED)
            return removeShow(command, run)
        }
        if (intent.moved) {
            val text = intent.replace ?: run.run.text
            val drawn = chooseDrawing(run, text)
            report(run, if (intent.replace != null) "replace" else "move", drawn.strategy, overflow(run, drawn.newAdvance))
            val out = removeShow(command, run).toMutableList()
            out += redraw(run, drawn, intent.dx, intent.dy)
            return out
        }
        val text = intent.replace ?: return listOf(command)
        val drawn = chooseDrawing(run, text)
        if (drawn.strategy == TextEditStrategy.REWRITE) {
            report(run, "replace", drawn.strategy, overflow(run, drawn.newAdvance))
            return replaceShow(command, run, drawn.bytes!!)
        }
        report(run, "replace", drawn.strategy, overflow(run, drawn.newAdvance))
        return removeShow(command, run) + redraw(run, drawn, 0.0, 0.0)
    }

    private fun emitArray(
        command: ContentCommand,
        group: List<Pair<ScannedTextRun, Intent>>,
        appended: MutableList<ContentCommand>
    ): List<ContentCommand> {
        val array = command.operands.getOrNull(0) as? PdfArray ?: return listOf(command)
        val byIndex = HashMap<Int, Pair<ScannedTextRun, Intent>>()
        for (entry in group) byIndex[entry.first.run.source?.arrayIndex ?: -1] = entry

        val items = ArrayList<PdfObject>(array.items.size + group.size)
        for ((index, item) in array.items.withIndex()) {
            val entry = byIndex[index]
            if (entry == null || item !is PdfString) {
                items += item
                continue
            }
            val (run, intent) = entry
            when {
                intent.deleted -> {
                    report(run, "delete", TextEditStrategy.REMOVED)
                    numericShift(run, run.run.advance)?.let { items += it }
                }
                intent.moved -> {
                    val text = intent.replace ?: run.run.text
                    val drawn = chooseDrawing(run, text)
                    report(run, if (intent.replace != null) "replace" else "move", drawn.strategy, overflow(run, drawn.newAdvance))
                    numericShift(run, run.run.advance)?.let { items += it }
                    appended += redraw(run, drawn, intent.dx, intent.dy)
                }
                intent.replace != null -> {
                    val drawn = chooseDrawing(run, intent.replace!!)
                    report(run, "replace", drawn.strategy, overflow(run, drawn.newAdvance))
                    if (drawn.strategy == TextEditStrategy.REWRITE) {
                        items += PdfString(drawn.bytes!!)
                        val shift = run.run.advance - drawn.newAdvance
                        numericShift(run, shift)?.let { items += it }
                    } else {
                        numericShift(run, run.run.advance)?.let { items += it }
                        appended += redraw(run, drawn, 0.0, 0.0)
                    }
                }
                else -> items += item
            }
        }
        return listOf(ContentCommand(listOf(PdfArray(items)), "TJ"))
    }

    /** Rewrites a single-string show operator, keeping font, position and size. */
    private fun replaceShow(command: ContentCommand, run: ScannedTextRun, bytes: ByteArray): List<ContentCommand> {
        val operandIndex = run.run.source?.operandIndex ?: 0
        val ops = command.operands.toMutableList()
        if (operandIndex >= ops.size) return listOf(command)
        ops[operandIndex] = PdfString(bytes)
        val result = ArrayList<ContentCommand>(2)
        result += ContentCommand(ops, command.operator)
        val shift = run.run.advance - advanceOf(run, bytes)
        shiftCommand(run, shift)?.let { result += it }
        return result
    }

    /**
     * Removes a show operand and compensates the advance it would have added, so
     * any text that followed on the same text matrix stays exactly where it was.
     * The `'`/`"` line move is preserved by keeping the operator with an empty
     * string (§9.4.3).
     */
    private fun removeShow(command: ContentCommand, run: ScannedTextRun): List<ContentCommand> {
        val result = ArrayList<ContentCommand>(2)
        when (command.operator) {
            "Tj" -> {}
            "'" -> result += ContentCommand(listOf(PdfString(ByteArray(0))), "'")
            "\"" -> {
                val ops = command.operands.toMutableList()
                val operandIndex = run.run.source?.operandIndex ?: 2
                if (operandIndex < ops.size) ops[operandIndex] = PdfString(ByteArray(0))
                result += ContentCommand(ops, "\"")
            }
        }
        shiftCommand(run, run.run.advance)?.let { result += it }
        return result
    }

    /** A `[k] TJ` command: shifts the text matrix by [shift] text units (§9.4.3). */
    private fun shiftCommand(run: ScannedTextRun, shift: Double): ContentCommand? =
        numericShift(run, shift)?.let { ContentCommand(listOf(PdfArray(listOf(it))), "TJ") }

    /** The numeric value of a compensation shift, or null when it is a no-op. */
    private fun numericShift(run: ScannedTextRun, shift: Double): PdfReal? {
        val denom = run.run.fontSize * run.scale / 100.0
        if (shift == 0.0 || denom == 0.0) return null
        val k = -shift * 1000.0 / denom
        return if (k == 0.0) null else PdfReal(k)
    }

    // -- Drawing the replacement ----------------------------------------------

    private class Drawing(
        val strategy: TextEditStrategy,
        val bytes: ByteArray?,
        val resource: PdfName,
        val newAdvance: Double
    )

    private fun chooseDrawing(run: ScannedTextRun, text: String): Drawing {
        val encoded = run.font?.encode(text)
        if (encoded != null && run.resourceName != null) {
            return Drawing(TextEditStrategy.REWRITE, encoded, PdfName(run.resourceName), advanceOf(run, encoded))
        }
        val resource = fallbackName()
        return Drawing(
            TextEditStrategy.FALLBACK_FONT,
            WinAnsi.encode(text),
            resource,
            (EmbeddedFont.advance1000(text) ?: 0L) / 1000.0 * run.run.fontSize
        )
    }

    /** Self-contained block drawing [drawing] at the run's origin plus (dx, dy). */
    private fun redraw(run: ScannedTextRun, drawing: Drawing, dx: Double, dy: Double): List<ContentCommand> {
        val r = run.run
        val scale = ctmScale(run.ctm)
        val size = if (r.fontSize > 0.0) r.fontSize * scale else 0.0
        val angle = atan2(r.endY - r.originY, r.endX - r.originX)
        val cosA = cos(angle)
        val sinA = sin(angle)
        val out = ArrayList<ContentCommand>(9)
        out += ContentCommand(emptyList(), "q")
        out += ContentCommand(
            listOf(PdfReal(run.color.red), PdfReal(run.color.green), PdfReal(run.color.blue)), "rg"
        )
        if (r.renderMode != 0) out += ContentCommand(listOf(PdfReal(r.renderMode.toDouble())), "Tr")
        out += ContentCommand(emptyList(), "BT")
        out += ContentCommand(listOf(drawing.resource, PdfReal(size)), "Tf")
        out += ContentCommand(
            listOf(
                PdfReal(cosA), PdfReal(sinA), PdfReal(-sinA), PdfReal(cosA),
                PdfReal(r.originX + dx), PdfReal(r.originY + dy)
            ),
            "Tm"
        )
        out += ContentCommand(listOf(PdfString(drawing.bytes ?: ByteArray(0))), "Tj")
        out += ContentCommand(emptyList(), "ET")
        out += ContentCommand(emptyList(), "Q")
        return out
    }

    // -- Measurements and reporting -------------------------------------------

    private fun advanceOf(run: ScannedTextRun, bytes: ByteArray): Double {
        val font = run.font ?: return 0.0
        return shapeText(font, bytes, run.run.fontSize, run.charSpace, run.wordSpace, run.scale).advance
    }

    private fun overflow(run: ScannedTextRun, newAdvance: Double): Pair<Boolean, Double> {
        val advance = run.run.advance
        val width = runWidth(run)
        if (advance <= 1e-9 || width <= 1e-9 || !newAdvance.isFinite()) return false to 0.0
        val extra = width * (newAdvance / advance) - width
        return if (extra > 1e-9) true to extra else false to 0.0
    }

    private fun runWidth(run: ScannedTextRun): Double {
        val r = run.run
        return hypot(r.endX - r.originX, r.endY - r.originY)
    }

    private fun ctmScale(ctm: com.pdfpaper.engine.geom.PdfMatrix): Double {
        val scale = sqrt(abs(ctm.determinant))
        return if (scale > 1e-9) scale else 1.0
    }

    private fun report(
        run: ScannedTextRun,
        operation: String,
        strategy: TextEditStrategy,
        overflowResult: Pair<Boolean, Double> = false to 0.0
    ) {
        reports += TextEditReport(
            pageIndex = pageIndex,
            runId = run.id,
            operation = operation,
            strategy = strategy,
            overflow = overflowResult.first,
            overflowAmount = overflowResult.second
        )
    }
}
