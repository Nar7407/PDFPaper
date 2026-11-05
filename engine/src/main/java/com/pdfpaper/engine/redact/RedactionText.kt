package com.pdfpaper.engine.redact

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.edit.ScannedTextRun
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.text.FALLBACK_FONT
import com.pdfpaper.engine.text.PdfFont
import com.pdfpaper.engine.text.PdfSimpleFont
import com.pdfpaper.engine.text.PdfType0Font
import kotlin.math.abs

/** One encoded glyph inside a show-string, with its text-space advance. */
internal class RedactionGlyph(
    val byteStart: Int,
    val byteEnd: Int,
    val text: String,
    val advance: Double,
    val removed: Boolean
)

/**
 * Splits one show-string into font glyphs and decides which glyph boxes
 * intersect a mark. The advance formula mirrors `shapeText`, so replacing a
 * removed glyph with a numeric TJ shift preserves every following glyph.
 */
internal fun glyphsOf(
    run: ScannedTextRun,
    bytes: ByteArray,
    marks: List<PdfRect>,
    expected: List<String> = emptyList()
): List<RedactionGlyph> {
    if (bytes.isEmpty()) return emptyList()
    val font: PdfFont = run.font ?: FALLBACK_FONT
    val full = run.ctm * run.run.startMatrix
    val fontSize = run.run.fontSize
    val scale = run.scale / 100.0
    var offset = 0.0
    val out = ArrayList<RedactionGlyph>()

    fun add(start: Int, end: Int, text: String, width1000: Long, isWord: Boolean) {
        val advance = (width1000 / 1000.0 * fontSize + run.charSpace +
            (if (isWord) run.wordSpace else 0.0)) * scale
        val box = glyphBox(full, offset, advance, fontSize, text)
        val removed = text.isNotEmpty() && intersectsAny(marks, box)
        out += RedactionGlyph(start, end, text, advance, removed)
        offset += advance
    }

    val type0 = font as? PdfType0Font
    if (type0 != null) {
        val cids = type0.cids(bytes)
        for ((index, cid) in cids.withIndex()) {
            add(index * 2, index * 2 + 2, type0.decode(cid), type0.width1000(cid), false)
        }
    } else {
        val simple = (font as? PdfSimpleFont) ?: FALLBACK_FONT
        for (index in bytes.indices) {
            val code = bytes[index].toInt() and 0xFF
            add(index, index + 1, simple.decode(code), simple.width1000(code), code == 32)
        }
    }
    if (expected.isNotEmpty()) {
        // Geometry alone can miss a match when the T11 highlight fragments do
        // not line up with variable-width glyphs. A searched mark also carries
        // the exact matched text, so use it to remove whole glyphs reliably.
        val text = run.run.text
        val starts = IntArray(out.size)
        var cursor = 0
        for (i in out.indices) {
            starts[i] = cursor
            cursor += out[i].text.length
        }
        for (needle in expected) {
            if (needle.isEmpty()) continue
            var from = 0
            while (true) {
                val at = text.indexOf(needle, from, ignoreCase = true)
                if (at < 0) break
                val end = at + needle.length
                for (i in out.indices) {
                    val glyphStart = starts[i]
                    val glyphEnd = glyphStart + out[i].text.length
                    if (out[i].text.isNotEmpty() && glyphStart < end && glyphEnd > at) {
                        val old = out[i]
                        out[i] = RedactionGlyph(old.byteStart, old.byteEnd, old.text, old.advance, true)
                    }
                }
                from = end.coerceAtLeast(at + 1)
            }
        }
    }
    return out
}

private fun glyphBox(
    full: PdfMatrix,
    offset: Double,
    advance: Double,
    fontSize: Double,
    text: String
): PdfRect {
    val top = if (fontSize.isFinite() && fontSize > 0.0) fontSize else 1.0
    val width = if (abs(advance) < 1e-9 && text.isNotEmpty()) 0.01 else advance
    val points = arrayOf(
        full.apply(offset, 0.0),
        full.apply(offset + width, 0.0),
        full.apply(offset + width, top),
        full.apply(offset, top)
    )
    return PdfRect(
        points.minOf { it[0] }, points.minOf { it[1] },
        points.maxOf { it[0] }, points.maxOf { it[1] }
    )
}

/** Kept byte slices and compensating text-space shifts. */
internal sealed class TextAction {
    class Text(val bytes: ByteArray) : TextAction()
    class Shift(val advance: Double) : TextAction()
}

private fun actionsFor(glyphs: List<RedactionGlyph>, source: ByteArray): List<TextAction> {
    val actions = ArrayList<TextAction>()
    var index = 0
    while (index < glyphs.size) {
        if (!glyphs[index].removed) {
            val startIndex = index
            while (index < glyphs.size && !glyphs[index].removed) index++
            val start = glyphs[startIndex].byteStart.coerceIn(0, source.size)
            val end = glyphs[index - 1].byteEnd.coerceIn(start, source.size)
            if (end > start) actions += TextAction.Text(source.copyOfRange(start, end))
        } else {
            var removed = 0.0
            while (index < glyphs.size && glyphs[index].removed) {
                removed += glyphs[index].advance
                index++
            }
            actions += TextAction.Shift(removed)
        }
    }
    return actions
}

/**
 * Rewrites one content command's show operator. Returns null when no glyph
 * intersects a mark, in which case the caller keeps the original command.
 */
internal fun rewriteShowCommand(
    command: ContentCommand,
    runs: List<ScannedTextRun>,
    marks: List<PdfRect>,
    expected: List<String> = emptyList()
): List<ContentCommand>? {
    if (runs.isEmpty()) return null
    return when (command.operator) {
        "Tj", "'", "\"" -> rewriteSingle(command, runs.first(), marks, expected)
        "TJ" -> rewriteTj(command, runs, marks, expected)
        else -> null
    }
}

private fun rewriteSingle(
    command: ContentCommand,
    run: ScannedTextRun,
    marks: List<PdfRect>,
    expected: List<String>
): List<ContentCommand>? {
    val source = when (command.operator) {
        "Tj", "'" -> (command.operands.getOrNull(0) as? PdfString)?.bytes
        "\"" -> (command.operands.getOrNull(2) as? PdfString)?.bytes
        else -> null
    } ?: return null
    val glyphs = glyphsOf(run, source, marks, expected)
    val actions = actionsFor(glyphs, source)
    if (actions.none { it is TextAction.Shift }) return null

    val out = ArrayList<ContentCommand>(actions.size)
    var first = true
    val lineMove = command.operator == "'" || command.operator == "\""
    for (action in actions) {
        when (action) {
            is TextAction.Text -> {
                if (first) {
                    if (command.operator == "Tj") {
                        out += ContentCommand(listOf(PdfString(action.bytes)), "Tj")
                    } else {
                        val operands = command.operands.toMutableList()
                        val index = if (command.operator == "\"") 2 else 0
                        if (index < operands.size) operands[index] = PdfString(action.bytes)
                        out += ContentCommand(operands, command.operator)
                    }
                } else {
                    out += ContentCommand(listOf(PdfString(action.bytes)), "Tj")
                }
            }
            is TextAction.Shift -> {
                if (first && lineMove) {
                    // Preserve the line move (and the Tw/Tc operands of "), but
                    // show no text before the compensating shift.
                    val operands = command.operands.toMutableList()
                    val index = if (command.operator == "\"") 2 else 0
                    if (index < operands.size) operands[index] = PdfString(ByteArray(0))
                    out += ContentCommand(operands, command.operator)
                }
                out += shiftCommand(action.advance, run)
            }
        }
        first = false
    }
    return out
}

private fun rewriteTj(
    command: ContentCommand,
    runs: List<ScannedTextRun>,
    marks: List<PdfRect>,
    expected: List<String>
): List<ContentCommand>? {
    val array = command.operands.getOrNull(0) as? PdfArray ?: return null
    val byIndex = runs.mapNotNull { run ->
        val index = run.run.source?.arrayIndex ?: return@mapNotNull null
        if (index < 0) null else index to run
    }.toMap()
    if (byIndex.isEmpty()) return null
    val newItems = ArrayList<PdfObject>()
    var changed = false
    for ((index, item) in array.items.withIndex()) {
        if (item !is PdfString) {
            newItems += item
            continue
        }
        val run = byIndex[index]
        if (run == null) {
            newItems += item
            continue
        }
        val glyphs = glyphsOf(run, item.bytes, marks, expected)
        val actions = actionsFor(glyphs, item.bytes)
        if (actions.none { it is TextAction.Shift }) {
            newItems += item
            continue
        }
        changed = true
        for (action in actions) {
            when (action) {
                is TextAction.Text -> if (action.bytes.isNotEmpty()) newItems += PdfString(action.bytes)
                is TextAction.Shift -> newItems += PdfReal(shiftNumber(action.advance, run))
            }
        }
    }
    return if (changed) listOf(ContentCommand(listOf(PdfArray(newItems)), "TJ")) else null
}

private fun shiftCommand(advance: Double, run: ScannedTextRun): ContentCommand =
    ContentCommand(listOf(PdfArray(listOf(PdfReal(shiftNumber(advance, run))))), "TJ")

private fun shiftNumber(advance: Double, run: ScannedTextRun): Double {
    val den = run.run.fontSize * run.scale / 100.0
    if (!den.isFinite() || abs(den) < 1e-9) return 0.0
    return -advance * 1000.0 / den
}
