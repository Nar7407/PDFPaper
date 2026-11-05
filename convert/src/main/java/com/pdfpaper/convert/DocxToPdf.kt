package com.pdfpaper.convert

import com.pdfpaper.convert.pdf.PdfDocumentWriter
import com.pdfpaper.convert.word.DocxReader
import com.pdfpaper.convert.word.WordLayout
import java.io.File

/** Thrown when a .docx cannot be read far enough to convert. */
class DocxToPdfException(message: String) : Exception(message)

class DocxToPdfOptions(
    /** Overrides the document title; the core properties are the default. */
    val title: String? = null
)

class DocxToPdfResult(val bytes: ByteArray, val report: DocxToPdfReport)

/**
 * Converts a Word document to PDF (T19).
 *
 * The document is read into [com.pdfpaper.convert.word.WordDocument], laid out
 * into pages bottom up (paragraphs wrap at the text width, tables place row by
 * row, pictures keep their size) and written with the PDF standard 14 fonts, so
 * the file needs no embedded fonts and stays readable in any viewer.
 *
 * Anything the reader or the layout cannot express shows up in the report
 * instead of being silently dropped. [convert] throws [DocxToPdfException] when
 * the input is not a readable .docx at all; everything else is a warning.
 */
object DocxToPdf {

    fun convertFile(input: File, options: DocxToPdfOptions = DocxToPdfOptions()): DocxToPdfResult =
        convert(input.readBytes(), options, input.name)

    fun convert(
        document: ByteArray,
        options: DocxToPdfOptions = DocxToPdfOptions(),
        sourceName: String = "input.docx"
    ): DocxToPdfResult {
        val word = DocxReader.read(document)
        val layout = WordLayout(word).layout()
        val bytes = PdfDocumentWriter.write(
            pages = layout.pages,
            title = options.title ?: word.title ?: sourceName,
            producer = "PDFPaper"
        )
        val report = DocxToPdfReport(
            sourceName = sourceName,
            pages = layout.counts.pages,
            paragraphs = layout.counts.paragraphs,
            headings = layout.counts.headings,
            listItems = layout.counts.listItems,
            tables = layout.counts.tables,
            pictures = layout.counts.images,
            replacedCharacters = layout.counts.replacedCharacters,
            notes = word.notes + layout.notes
        )
        return DocxToPdfResult(bytes, report)
    }
}

/**
 * What a DOCX→PDF conversion did and what it could not do. One flat list of
 * counts and notes: unlike the PDF→DOCX report there is no per-page split,
 * because here every page comes from the same model.
 */
class DocxToPdfReport(
    val sourceName: String,
    val pages: Int,
    val paragraphs: Int,
    val headings: Int,
    val listItems: Int,
    val tables: Int,
    val pictures: Int,
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
        sb.append("  mode   : Word document to PDF (standard fonts)\n")
        sb.append("  pages  : ").append(pages).append('\n')
        sb.append('\n')

        sb.append("Totals\n")
        sb.append("  paragraphs      : ").append(paragraphs).append('\n')
        sb.append("  headings        : ").append(headings).append('\n')
        sb.append("  list items      : ").append(listItems).append('\n')
        sb.append("  tables          : ").append(tables).append('\n')
        sb.append("  pictures        : ").append(pictures).append('\n')
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
