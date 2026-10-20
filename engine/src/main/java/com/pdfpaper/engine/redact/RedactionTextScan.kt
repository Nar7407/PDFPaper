package com.pdfpaper.engine.redact

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.edit.ScannedTextRun
import com.pdfpaper.engine.edit.TextRunScanner
import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.text.PdfTextRun
import com.pdfpaper.engine.text.extractText

private const val MAX_TEXT_FORM_DEPTH = 16

/**
 * Scans Form XObject streams in page content order. The page's own text is
 * deliberately excluded: callers use the existing T7/T11 extractor for that
 * and add these runs for form text, which T7 does not descend into.
 */
internal fun collectFormRuns(page: PdfPage): List<PdfTextRun> {
    val out = ArrayList<PdfTextRun>()
    val commands = page.contents().getOrNull()?.bytes?.let { parseContent(it).commands } ?: return out
    walkForForms(page, commands, page.resources, PdfMatrix.IDENTITY, HashSet(), 0, out)
    return out
}

private fun walkForForms(
    page: PdfPage,
    commands: List<ContentCommand>,
    resources: PdfDict?,
    initialCtm: PdfMatrix,
    path: MutableSet<Int>,
    depth: Int,
    out: MutableList<PdfTextRun>
) {
    if (depth >= MAX_TEXT_FORM_DEPTH) return
    var ctm = initialCtm
    val stack = ArrayDeque<PdfMatrix>()
    for (command in commands) {
        when (command.operator) {
            "q" -> stack.addLast(ctm)
            "Q" -> ctm = stack.removeLastOrNull() ?: ctm
            "cm" -> matrixOfPdf(command.operands)?.let { ctm = ctm * it }
            "Do" -> {
                val name = (command.operands.getOrNull(0) as? PdfName)?.name ?: continue
                val raw = xObjectRaw(resources, name, page) ?: continue
                val stream = (if (raw is PdfRef) page.document.resolve(raw) else raw) as? PdfStream ?: continue
                if ((stream.dict["Subtype"] as? PdfName)?.name != "Form") continue
                val objNum = (raw as? PdfRef)?.objNum
                if (objNum != null && !path.add(objNum)) continue
                try {
                    collectForm(page, stream, ctm, resources, path, depth + 1, out)
                } finally {
                    if (objNum != null) path.remove(objNum)
                }
            }
        }
    }
}

private fun collectForm(
    page: PdfPage,
    stream: PdfStream,
    ctm: PdfMatrix,
    parentResources: PdfDict?,
    path: MutableSet<Int>,
    depth: Int,
    out: MutableList<PdfTextRun>
) {
    if (depth >= MAX_TEXT_FORM_DEPTH) return
    val matrix = (stream.dict["Matrix"] as? PdfArray)?.let { matrixOfPdf(it.items) } ?: PdfMatrix.IDENTITY
    val childCtm = ctm * matrix
    val resources = resolveDict(stream.dict["Resources"], page) ?: parentResources
    val bytes = stream.decoded { ref -> page.document.resolve(ref) }.getOrNull()?.bytes ?: return
    val commands = parseContent(bytes).commands
    val warnings = ArrayList<String>()
    out += TextRunScanner.scan(commands, resources, page.document, warnings, childCtm).map { it.run }
    walkForForms(page, commands, resources, childCtm, path, depth + 1, out)
}

private fun xObjectRaw(resources: PdfDict?, name: String, page: PdfPage): PdfObject? {
    val table = when (val group = resources?.get("XObject")) {
        is PdfDict -> group
        is PdfRef -> page.document.resolve(group) as? PdfDict
        else -> null
    } ?: return null
    return table[name]
}

private fun resolveDict(obj: PdfObject?, page: PdfPage): PdfDict? = when (obj) {
    is PdfDict -> obj
    is PdfRef -> page.document.resolve(obj) as? PdfDict
    else -> null
}

/** All text runs of one page, including Form XObjects, in content order. */
internal fun collectAllRuns(page: PdfPage): List<PdfTextRun> =
    page.extractText().runs + collectFormRuns(page)

internal fun allText(page: PdfPage): String = collectAllRuns(page).joinToString("") { it.text }

internal fun PdfTextRun.rect(): PdfRect =
    PdfRect(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))

/** Matches a regular expression or plain string inside form runs. */
internal fun findInForms(
    page: PdfPage,
    regex: Regex?,
    plain: String?
): List<RedactionFindMatch> {
    val out = ArrayList<RedactionFindMatch>()
    for (run in collectFormRuns(page)) {
        if (regex != null) {
            for (match in regex.findAll(run.text)) {
                if (match.value.isEmpty()) continue
                out += RedactionFindMatch(
                    pageIndex = page.index,
                    text = match.value,
                    start = match.range.first,
                    end = match.range.last + 1,
                    rects = listOf(run.rect()),
                    inForm = true
                )
            }
        } else if (plain != null && plain.isNotEmpty()) {
            var from = 0
            while (true) {
                val at = run.text.indexOf(plain, from, ignoreCase = true)
                if (at < 0) break
                out += RedactionFindMatch(
                    pageIndex = page.index,
                    text = run.text.substring(at, at + plain.length),
                    start = at,
                    end = at + plain.length,
                    rects = listOf(run.rect()),
                    inForm = true
                )
                from = at + plain.length
            }
        }
    }
    return out
}
