package com.pdfpaper.engine.text

import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.page.PdfPage

/** Result of extracting one page: runs in reading order plus warnings. */
class PageText(val runs: List<PdfTextRun>, val warnings: List<String>)

/**
 * Page text extraction (ISO 32000-1, §9.4 text operators over §9.6/§9.7
 * fonts). Reading order = content-stream order. Never throws: malformed
 * content yields whatever ran, with warnings.
 */
fun PdfPage.extractText(): PageText {
    val warnings = ArrayList<String>()
    val contents = contents().fold(
        onSuccess = { it },
        onFailure = { warnings += "contents failed: ${it.message}"; null }
    ) ?: return PageText(emptyList(), warnings)
    warnings += contents.warnings.map { "content: $it" }

    val parsed = parseContent(contents.bytes)
    warnings += parsed.warnings.map { "content: $it" }

    val fonts = PdfFontCache(document, warnings)
    val machine = TextMachine({ name -> fonts.fontFor(resources, name) }, warnings)
    val runs = ArrayList<PdfTextRun>()

    // Minimal graphics state: q/Q/cm move the CTM so run bboxes are in page
    // space (§8.4); the text state is saved/restored with it (§9.3). Text
    // operators go to the shared machine.
    var ctm = PdfMatrix.IDENTITY
    val stack = ArrayDeque<Pair<PdfMatrix, TextStateSnapshot>>()
    for (command in parsed.commands) {
        when (command.operator) {
            "q" -> stack.addLast(ctm to machine.snapshot())
            "Q" -> stack.removeLastOrNull()?.let { (savedCtm, savedText) ->
                ctm = savedCtm
                machine.restore(savedText)
            }
            "cm" -> matrixOf(command.operands)?.let { ctm = ctm * it }
            else -> machine.handle(command, ctm, runs)
        }
    }
    return PageText(runs, warnings)
}

internal fun matrixOf(operands: List<PdfObject>): PdfMatrix? {
    val vals = operands.take(6).map { operand ->
        when (operand) {
            is PdfInteger -> operand.value.toDouble()
            is PdfReal -> operand.value
            else -> null
        }
    }
    if (vals.any { it == null }) return null
    return PdfMatrix(vals[0]!!, vals[1]!!, vals[2]!!, vals[3]!!, vals[4]!!, vals[5]!!)
}
