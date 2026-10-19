package com.pdfpaper.engine.ocr

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.edit.AddOcrTextEdit
import com.pdfpaper.engine.edit.ImagePlacements
import com.pdfpaper.engine.edit.PdfEditOps
import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.image.PdfImages
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.extractText
import kotlin.math.hypot

/** What happened to one page during [PdfOcr.makeSearchable] (T25). */
enum class OcrPageStatus {
    /** Invisible words were added. */
    ADDED,
    /** The page already had selectable text and was not OCR'd. */
    SKIPPED_HAS_TEXT,
    /** The page draws no usable image. */
    NO_IMAGE,
    /** The provider threw; the page is unchanged. */
    PROVIDER_FAILED,
    /** The provider recognized nothing usable; the page is unchanged. */
    NO_WORDS
}

/**
 * Per-page outcome of [PdfOcr.makeSearchable] (T25). [wordCount] and the
 * confidences describe the words actually added, so a skipped or failed page
 * reports zero words and null confidence.
 */
class OcrPageReport(
    val pageIndex: Int,
    val status: OcrPageStatus,
    /** Sample width of the OCR image, when one was found. */
    val imageWidth: Int? = null,
    /** Sample height of the OCR image, when one was found. */
    val imageHeight: Int? = null,
    val wordCount: Int = 0,
    /** Mean provider confidence over the added words, or null when none. */
    val meanConfidence: Double? = null,
    /** Lowest provider confidence over the added words, or null when none. */
    val minConfidence: Double? = null,
    val problems: List<String> = emptyList()
)

/**
 * Result of [PdfOcr.makeSearchable]: the new file bytes plus one report per
 * page that was considered, in page order (T25).
 */
class SearchableResult(
    val bytes: ByteArray,
    val pages: List<OcrPageReport>
) {
    val pagesAdded: Int get() = pages.count { it.status == OcrPageStatus.ADDED }
    val wordsAdded: Int get() = pages.sumOf { it.wordCount }
}

/**
 * Shared text layer for scanning (task T25, ISO 32000-1 §9.4, §9.7.5).
 *
 * [makeSearchable] asks the host [OcrProvider] about each scanned page — a
 * page with no extractable text, or a page the caller selects explicitly — and
 * writes one invisible word per recognized word with render mode 3 (§9.3.6).
 * The original page, including the scanned image, is copied unchanged; only a
 * new content stream and the shared text font are appended. Search, selection
 * and copy work because the words carry a ToUnicode CMap. Malformed input and
 * provider failures are reported, never thrown.
 */
object PdfOcr {

    /**
     * Saves [document] with an invisible OCR text layer.
     *
     * When [pages] is null every page without extractable text is processed;
     * otherwise exactly the listed page indices (zero-based) are processed even
     * if they already have text. The provider is called once per processed
     * page with the page's largest image and [language]. Every result is a new
     * file; a page that is skipped or fails is copied unchanged.
     */
    fun makeSearchable(
        document: PdfDocument,
        provider: OcrProvider,
        language: String = "eng",
        pages: List<Int>? = null
    ): Result<SearchableResult> = runCatching {
        val allPages = document.pages()
        val selected = pages?.toSet()
        val edits = ArrayList<AddOcrTextEdit>()
        val reports = ArrayList<OcrPageReport>()
        for (page in allPages) {
            if (selected != null && page.index !in selected) continue
            reports += processPage(page, provider, language, force = selected != null, edits)
        }
        val bytes = PdfEditOps.save(document, edits).getOrThrow()
        SearchableResult(bytes, reports)
    }

    // -- One page ---------------------------------------------------------------

    private fun processPage(
        page: PdfPage,
        provider: OcrProvider,
        language: String,
        force: Boolean,
        edits: MutableList<AddOcrTextEdit>
    ): OcrPageReport {
        if (!force && page.extractText().runs.any { it.text.isNotBlank() }) {
            return OcrPageReport(page.index, OcrPageStatus.SKIPPED_HAS_TEXT)
        }
        val pageImage = findPageImage(page)
            ?: return OcrPageReport(page.index, OcrPageStatus.NO_IMAGE)
        val image = pageImage.image

        val words = try {
            provider.recognize(image, language)
        } catch (t: Throwable) {
            return OcrPageReport(
                page.index,
                OcrPageStatus.PROVIDER_FAILED,
                image.width,
                image.height,
                problems = listOf(t.message ?: t::class.simpleName ?: "provider failed")
            )
        }

        val confidences = ArrayList<Double>()
        for (word in words) {
            val edit = textEdit(page.index, word, pageImage) ?: continue
            edits += edit
            confidences += word.confidence
        }
        if (confidences.isEmpty()) {
            return OcrPageReport(page.index, OcrPageStatus.NO_WORDS, image.width, image.height)
        }
        return OcrPageReport(
            page.index,
            OcrPageStatus.ADDED,
            image.width,
            image.height,
            wordCount = confidences.size,
            meanConfidence = confidences.average(),
            minConfidence = confidences.min()
        )
    }

    // -- Finding the scan -------------------------------------------------------

    /** The largest usable image: its CTM (standard PDF image space, y up) and pixels. */
    private class PageImage(val ctm: PdfMatrix, val image: OcrImage)

    private class Candidate(val ctm: PdfMatrix, val stream: PdfStream, val resources: PdfDict?) {
        /** Area of the placed unit square in page space, used to rank candidates. */
        val area: Double
            get() = ImagePlacements.boundsOf(ctm).let { it.width * it.height }
    }

    /**
     * Walks the page content and any nested form XObjects, collecting image
     * draws with the CTM in effect (§8.9.5.2, §8.10.1). Inline images are not
     * collected; a scan embedded as an ordinary image XObject (the usual case)
     * is.
     */
    private fun findPageImage(page: PdfPage): PageImage? {
        val doc = page.document
        val candidates = ArrayList<Candidate>()
        val commands = parseContent(page.contents().getOrNull()?.bytes ?: ByteArray(0)).commands
        walkImages(doc, commands, page.resources, PdfMatrix.IDENTITY, 0, candidates)

        candidates.sortByDescending { it.area }
        for (candidate in candidates) {
            if (!candidate.area.isFinite() || candidate.area <= 0.0) continue
            val decoded = PdfImages.decodeImage(
                candidate.stream.dict,
                candidate.stream.data,
                candidate.resources,
                doc,
                decoder = null
            )
            val raster = decoded.image ?: continue
            if (raster.stencil || raster.width <= 0 || raster.height <= 0) continue
            if (raster.pixels == null && raster.externalData == null) continue
            return PageImage(
                candidate.ctm,
                OcrImage(
                    raster.width,
                    raster.height,
                    raster.pixels,
                    raster.externalFormat,
                    raster.externalData
                )
            )
        }
        return null
    }

    private fun walkImages(
        doc: PdfDocument,
        commands: List<ContentCommand>,
        resources: PdfDict?,
        startCtm: PdfMatrix,
        depth: Int,
        out: MutableList<Candidate>
    ) {
        var ctm = startCtm
        val stack = ArrayDeque<PdfMatrix>()
        for (command in commands) {
            when (command.operator) {
                "q" -> stack.addLast(ctm)
                "Q" -> ctm = stack.removeLastOrNull() ?: ctm
                "cm" -> ImagePlacements.matrixOf(command.operands)?.let { ctm = ctm * it }
                "Do" -> {
                    val name = (command.operands.firstOrNull() as? PdfName)?.name ?: continue
                    val xobjects = resolveDict(doc, resources?.get("XObject")) ?: continue
                    val entry = xobjects.entries.entries.firstOrNull { it.key.name == name }?.value
                        ?: continue
                    val stream = resolveStream(doc, entry) ?: continue
                    when ((stream.dict["Subtype"] as? PdfName)?.name) {
                        "Image" -> out += Candidate(ctm, stream, resources)
                        "Form" -> if (depth < MAX_FORM_DEPTH) {
                            val matrix = ImagePlacements.matrixOf(
                                (stream.dict["Matrix"] as? PdfArray)?.items ?: emptyList()
                            ) ?: PdfMatrix.IDENTITY
                            val inner = resolveDict(doc, stream.dict["Resources"]) ?: resources
                            val bytes = stream.decoded { doc.resolve(it) }.getOrNull()?.bytes
                                ?: continue
                            walkImages(
                                doc,
                                parseContent(bytes).commands,
                                inner,
                                ctm * matrix,
                                depth + 1,
                                out
                            )
                        }
                    }
                }
            }
        }
    }

    private fun resolveDict(doc: PdfDocument, obj: PdfObject?): PdfDict? = when (obj) {
        is PdfDict -> obj
        is PdfRef -> doc.resolve(obj) as? PdfDict
        else -> null
    }

    private fun resolveStream(doc: PdfDocument, obj: PdfObject?): PdfStream? = when (obj) {
        is PdfStream -> obj
        is PdfRef -> doc.resolve(obj) as? PdfStream
        else -> null
    }

    // -- One word ---------------------------------------------------------------

    /**
     * Maps one recognized word box (image pixels, y down) to an invisible
     * `Tj`. The text matrix follows the image's own axes, the font size is the
     * word's height in page units, and Tz stretches the run to the box width
     * exactly, so selection rectangles match what the provider saw.
     */
    private fun textEdit(pageIndex: Int, word: OcrWord, pageImage: PageImage): AddOcrTextEdit? {
        val text = layerText(word.text) ?: return null
        val image = pageImage.image
        val width = image.width.toDouble()
        val height = image.height.toDouble()
        val left = word.bounds.left.coerceIn(0.0, width)
        val top = word.bounds.top.coerceIn(0.0, height)
        val right = word.bounds.right.coerceIn(0.0, width)
        val bottom = word.bounds.bottom.coerceIn(0.0, height)
        if (right <= left || bottom <= top) return null

        val ctm = pageImage.ctm
        val lengthX = hypot(ctm.a, ctm.b)
        val lengthY = hypot(ctm.c, ctm.d)
        if (lengthX <= 0.0 || lengthY <= 0.0) return null
        val unitsPerPixelX = lengthX / width
        val unitsPerPixelY = lengthY / height
        val size = (bottom - top) * unitsPerPixelY
        val boxWidth = (right - left) * unitsPerPixelX
        if (!size.isFinite() || size <= 0.0) return null
        if (!boxWidth.isFinite() || boxWidth <= 0.0) return null

        // Tz makes the run's advance (one em per character) equal boxWidth.
        val scale = 100.0 * boxWidth / (text.length * size)
        if (!scale.isFinite() || scale <= 0.0) return null

        // Image space is y up with row 0 at the top: pixel y maps to 1 - y/h.
        val origin = ctm.apply(left / width, 1.0 - bottom / height)
        val matrix = PdfMatrix(
            ctm.a / lengthX, ctm.b / lengthX, // image +x direction, unit length
            ctm.c / lengthY, ctm.d / lengthY, // image +y (up) direction, unit length
            origin[0], origin[1]
        )
        return AddOcrTextEdit(pageIndex, text, matrix, size, scale)
    }

    /**
     * Normalises OCR text to one line: whitespace runs become single spaces,
     * control characters and characters outside the BMP are dropped. Null when
     * nothing is left.
     */
    private fun layerText(raw: String): String? {
        val out = StringBuilder(raw.length)
        var pendingSpace = false
        for (ch in raw) {
            val code = ch.code
            when {
                ch.isWhitespace() -> if (out.isNotEmpty()) pendingSpace = true
                code < 0x20 || code in 0x7F..0x9F || code in 0xD800..0xDFFF -> {}
                else -> {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    out.append(ch)
                }
            }
        }
        return out.toString().ifEmpty { null }
    }

    private const val MAX_FORM_DEPTH = 16
}
