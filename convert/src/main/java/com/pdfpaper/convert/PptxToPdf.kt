package com.pdfpaper.convert

import com.pdfpaper.convert.pdf.PdfDocumentWriter
import com.pdfpaper.convert.pptx.PptxReader
import com.pdfpaper.convert.pptx.SlideToPdf
import java.io.File

/** Thrown when a .pptx cannot be read far enough to convert. */
class PptxToPdfException(message: String) : Exception(message)

class PptxToPdfOptions(
    /** Overrides the PDF title; the file name is the default. */
    val title: String? = null
)

class PptxToPdfResult(val bytes: ByteArray, val report: PptxToPdfReport)

/**
 * Converts a PowerPoint presentation to PDF (T20).
 *
 * The deck is read into [com.pdfpaper.convert.pptx.SlideDeck] (slide size,
 * slide order, backgrounds, shapes, pictures and text with its inheritance
 * resolved) and written as one PDF page per slide with the PDF standard 14
 * fonts, so the file needs no embedded fonts and stays readable in any viewer.
 *
 * Anything the reader or the layout cannot express shows up in the report
 * instead of being silently dropped. [convert] throws [PptxToPdfException] when
 * the input is not a readable .pptx at all; everything else is a warning.
 */
object PptxToPdf {

    fun convertFile(input: File, options: PptxToPdfOptions = PptxToPdfOptions()): PptxToPdfResult =
        convert(input.readBytes(), options, input.name)

    fun convert(
        presentation: ByteArray,
        options: PptxToPdfOptions = PptxToPdfOptions(),
        sourceName: String = "input.pptx"
    ): PptxToPdfResult {
        val deck = PptxReader.read(presentation)
        val layout = SlideToPdf(deck).layout()
        val bytes = PdfDocumentWriter.write(
            pages = layout.pages,
            title = options.title ?: sourceName,
            producer = "PDFPaper"
        )
        val report = PptxToPdfReport(
            sourceName = sourceName,
            slides = layout.counts.slides,
            shapes = layout.counts.shapes,
            pictures = layout.counts.pictures,
            textBoxes = layout.counts.textBoxes,
            listItems = layout.counts.listItems,
            replacedCharacters = layout.counts.replacedCharacters,
            notes = deck.notes + layout.notes
        )
        return PptxToPdfResult(bytes, report)
    }
}

/**
 * What a PPTX→PDF conversion did and what it could not do. One flat list of
 * counts and notes, like the DOCX→PDF report: every page comes from the same
 * model, so there is no per-page split.
 */
class PptxToPdfReport(
    val sourceName: String,
    val slides: Int,
    val shapes: Int,
    /** Pictures drawn, a picture background included. */
    val pictures: Int,
    val textBoxes: Int,
    val listItems: Int,
    /** Characters that have no WinAnsi code and were drawn as '?'. */
    val replacedCharacters: Int,
    /** Everything skipped or approximated, said once each. */
    val notes: List<String> = emptyList()
) {

    /** True when at least one feature had to be dropped or approximated. */
    val hasLosses: Boolean get() = notes.isNotEmpty() || replacedCharacters > 0

    fun toText(): String {
        val sb = StringBuilder()
        sb.append("PDFPaper conversion report\n")
        sb.append("  source : ").append(sourceName).append('\n')
        sb.append("  mode   : PowerPoint presentation to PDF (standard fonts)\n")
        sb.append("  slides : ").append(slides).append('\n')
        sb.append('\n')

        sb.append("Totals\n")
        sb.append("  shapes          : ").append(shapes).append('\n')
        sb.append("  pictures        : ").append(pictures).append('\n')
        sb.append("  text boxes      : ").append(textBoxes).append('\n')
        sb.append("  list items      : ").append(listItems).append('\n')
        if (replacedCharacters > 0) {
            sb.append("  characters replaced : ").append(replacedCharacters).append('\n')
        }
        sb.append('\n')

        sb.append("What was lost or approximated\n")
        if (!hasLosses) {
            sb.append("  nothing reported\n")
        } else {
            for (note in notes) sb.append("  - ").append(note).append('\n')
        }
        return sb.toString()
    }
}
