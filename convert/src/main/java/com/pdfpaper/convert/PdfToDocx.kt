package com.pdfpaper.convert

import com.pdfpaper.convert.docx.DocSection
import com.pdfpaper.convert.docx.DocxBlockBuilder
import com.pdfpaper.convert.docx.DocxConversionReport
import com.pdfpaper.convert.docx.DocxDocument
import com.pdfpaper.convert.docx.DocxMode
import com.pdfpaper.convert.docx.DocxWriter
import com.pdfpaper.convert.docx.PageAnalysis
import com.pdfpaper.convert.docx.PageContent
import com.pdfpaper.convert.docx.Twips
import com.pdfpaper.convert.media.ImageBag
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageGeometry
import java.io.File
import kotlin.math.min

/** How a PDF page becomes a document. */
class PdfToDocxOptions(
    /** Flow (editable text, reflowed) or exact (absolute page layout). */
    val mode: DocxMode = DocxMode.FLOW,
    /** Raster resolution for the vector page pictures exact mode needs. */
    val dpi: Double = 200.0,
    val maxPixels: Int = PageGeometry.DEFAULT_MAX_PIXELS,
    val maxPages: Int = Int.MAX_VALUE,
    val title: String? = null
)

class PdfToDocxResult(val bytes: ByteArray, val report: DocxConversionReport)

/**
 * Converts a PDF to DOCX (T18).
 *
 * Flow mode produces a document someone can edit: lines are merged into
 * paragraphs, sized-up text becomes a heading, bullet and number markers become
 * real lists, runs of aligned lines become tables and pictures are placed in
 * reading order. Exact mode produces a document that looks like the page:
 * every line is a paragraph frame at its original coordinates and pictures stay
 * anchored where they were.
 *
 * This class only analyses the pages and asks [DocxBlockBuilder] for the model;
 * [DocxWriter] does the packaging.
 */
object PdfToDocx {

    fun convertFile(input: File, options: PdfToDocxOptions = PdfToDocxOptions()): PdfToDocxResult {
        val document = PdfDocument.open(FileSource(input)).getOrThrow()
        return document.use { convert(it, options, input.name) }
    }

    fun convert(
        document: PdfDocument,
        options: PdfToDocxOptions = PdfToDocxOptions(),
        sourceName: String = "input.pdf"
    ): PdfToDocxResult {
        val pages = document.pages()
        val limit = min(pages.size, maxOf(0, options.maxPages))
        val media = ImageBag("word/media/image")
        val contents = ArrayList<PageContent>(limit)
        for (index in 0 until limit) {
            contents += PageAnalysis.analyze(
                page = pages[index],
                pageNumber = index + 1,
                images = media,
                rasterizeVectors = options.mode == DocxMode.EXACT,
                dpi = options.dpi,
                maxPixels = options.maxPixels
            )
        }

        val built = DocxBlockBuilder(contents, options.mode).build()
        val docx = DocxDocument(
            blocks = built.blocks,
            media = media.parts,
            numbering = built.numbering,
            finalSection = contents.lastOrNull()?.let { letterLike(it) } ?: LETTER,
            title = options.title ?: sourceName,
            pageCount = limit
        )
        val bytes = DocxWriter.write(docx)
        return PdfToDocxResult(bytes, report(sourceName, options, built, limit, pages.size))
    }

    /** Fallback section for an empty document, sized like the last page. */
    private fun letterLike(content: PageContent): DocSection = DocSection(
        pageWidth = Twips.ofPoints(content.width),
        pageHeight = Twips.ofPoints(content.height),
        marginLeft = 720, marginRight = 720, marginTop = 720, marginBottom = 720
    )

    private val LETTER = DocSection(
        pageWidth = Twips.ofInches(8.5), pageHeight = Twips.ofInches(11.0),
        marginLeft = Twips.ofInches(1.0), marginRight = Twips.ofInches(1.0),
        marginTop = Twips.ofInches(1.0), marginBottom = Twips.ofInches(1.0)
    )

    private fun report(
        sourceName: String,
        options: PdfToDocxOptions,
        built: com.pdfpaper.convert.docx.BuiltDocument,
        pageCount: Int,
        totalPages: Int
    ): DocxConversionReport {
        val notes = ArrayList<String>()
        notes += "text colour is not part of the engine's text model: every run is written in the automatic colour (black)"
        notes += "bold and italic come from the font name, so they are approximate; underline and paragraph frames' shading are not detected"
        when (options.mode) {
            DocxMode.FLOW -> {
                notes += "each PDF page becomes its own section, which is also where the page breaks come from"
                notes += "text reflows: the line breaks inside a paragraph are not preserved"
                if (built.headings > 0) {
                    notes += "headings were chosen by size relative to the body size (%.1f pt); their sizes come from the " +
                        "target application's heading styles".format(built.bodySize)
                }
                if (built.paragraphGap > 0.05) {
                    notes += "paragraph spacing was normalised to %.1f pt after each paragraph".format(built.paragraphGap)
                }
                if (built.tables > 0) {
                    notes += "converted tables have no borders: rules are vector art and are not detected"
                }
            }
            DocxMode.EXACT -> {
                notes += "every line is a paragraph frame at its original position; the baseline placement is approximate " +
                    "and the spacing inside a line is re-laid out"
                if (built.vectorPages > 0) {
                    notes += "${built.vectorPages} page(s) carried vector art and got a rasterized page picture as a background"
                }
            }
        }
        if (pageCount < totalPages) {
            notes += "only the first $pageCount of $totalPages pages were converted (--max-pages)"
        }
        return DocxConversionReport(
            sourceName = sourceName,
            mode = when (options.mode) {
                DocxMode.FLOW -> "flow (editable text, reflowed)"
                DocxMode.EXACT -> "exact (absolute page layout)"
            },
            pages = built.stats,
            notes = notes
        )
    }
}
