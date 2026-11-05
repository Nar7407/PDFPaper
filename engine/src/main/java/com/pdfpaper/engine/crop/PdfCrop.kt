package com.pdfpaper.engine.crop

import com.pdfpaper.engine.export.PageRasterizer
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.mark.PageSelection
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.redact.PdfRedaction
import com.pdfpaper.engine.redact.RedactionMark
import com.pdfpaper.engine.redact.RedactionMarkType
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.write.PdfDocumentAssembler
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfObjectCopier
import com.pdfpaper.engine.write.PdfPageSelection
import com.pdfpaper.engine.write.PdfWriter
import kotlin.math.max
import kotlin.math.min

/**
 * Cropping and automatic white-margin detection (task T46, ISO 32000-1
 * §14.11.2).
 *
 * A crop only writes a new `/CropBox`; it never touches `/MediaBox`, so a
 * normal crop is non-destructive and a reader can reveal the full page again.
 * When the caller asks for [apply]'s `removeHiddenContent`, the content outside
 * the new box is rewritten through the T28 redaction path instead, so the
 * hidden text and pictures are really gone (an extraction of the saved file
 * cannot find them).
 */
object PdfCrop {

    /** Smallest CropBox this API writes; a zero-area page is refused. */
    const val MIN_CROP_POINTS: Double = 1.0

    /** Default tolerance for [auto], on the 0..255 RGB scale. */
    const val DEFAULT_TOLERANCE: Int = 10

    /** Default resolution used for detection; enough for a white-margin scan. */
    const val DEFAULT_AUTO_DPI: Int = 96

    private const val EPSILON = 0.01
    private const val LETTER_WIDTH = 612.0
    private const val LETTER_HEIGHT = 792.0

    /**
     * Writes a new PDF with the requested CropBoxes. [request] chooses the
     * pages and whether the margins are shared or per page.
     *
     * Margins are trimmed from each page's *current* CropBox (the MediaBox on
     * a page that was never cropped), so applying the same margins to pages of
     * different sizes keeps each page's own proportions. The MediaBox is
     * copied unchanged.
     *
     * With [removeHiddenContent] the run first writes the cropped file, then
     * opens it and removes everything that now sits outside the box with the
     * T28 [PdfRedaction] machinery: text runs that intersect the removed area
     * are rewritten away, images are blanked or dropped, vector marks are
     * clipped and annotations/fields outside the box are dropped. A normal
     * crop (the default) only hides that content.
     *
     * [decoder] is the host image decoder used by the redaction pass; the app
     * passes its Android decoder and the JVM CLI supplies ImageIO. When it is
     * null the engine's default is used. The function returns a [Result] and
     * never throws on malformed input.
     */
    fun apply(
        document: PdfDocument,
        request: CropRequest,
        removeHiddenContent: Boolean = false,
        decoder: ExternalImageDecoder? = null
    ): Result<CropResult> = runCatching {
        val pages = document.pages()
        require(pages.isNotEmpty()) { "The document has no pages" }
        val selected = selectedPages(request.pages, pages.size)
        require(selected.isNotEmpty()) { "No pages matched the page selection" }

        val boxes = LinkedHashMap<Int, PdfRect>()
        when (val spec = request.spec) {
            is CropSpec.Margins -> {
                validateMargins(spec)
                for (index in selected) {
                    val base = currentBox(pages[index])
                    val box = PdfRect(
                        base.left + spec.left,
                        base.bottom + spec.bottom,
                        base.right - spec.right,
                        base.top - spec.top
                    )
                    requireValidBox(box, "page ${index + 1} margins leave no page area")
                    validateInsideMedia(box, pages[index], index)
                    boxes[index] = box
                }
            }

            is CropSpec.PerPage -> {
                for (index in selected) {
                    val box = spec.boxes[index] ?: continue
                    requireValidBox(box, "page ${index + 1} has an empty crop box")
                    validateInfinite(box, index)
                    validateInsideMedia(box, pages[index], index)
                    boxes[index] = box
                }
                require(boxes.isNotEmpty()) {
                    "No selected page has a crop box; per-page mode needs at least one"
                }
            }
        }

        val hooks: Map<
            Int,
            (PdfPage, LinkedHashMap<PdfName, PdfObject>, PdfObjectCopier, PdfDocumentBuilder) -> Unit
            > = boxes.mapValues { (_, box) ->
            { _: PdfPage, entries: LinkedHashMap<PdfName, PdfObject>, _: PdfObjectCopier, _: PdfDocumentBuilder ->
                entries[PdfName("CropBox")] = cropArray(box)
            }
        }
        val selections = pages.indices.map { PdfPageSelection(document, it) }
        val assembler = PdfDocumentAssembler(
            selections = selections,
            primary = document,
            version = PdfWriter.DEFAULT_VERSION,
            pageHooks = hooks
        )
        val cropped = assembler.assemble()
        if (!removeHiddenContent) {
            return@runCatching CropResult(cropped, boxes)
        }

        // Re-open the freshly cropped file: every page now has its new CropBox,
        // and the T28 writer can work on the same coordinates a reader sees.
        val marks = ArrayList<RedactionMark>()
        PdfDocument.open(ByteArraySource(cropped)).getOrThrow().use { reopened ->
            val reopenedPages = reopened.pages()
            for ((index, box) in boxes) {
                val page = reopenedPages[index]
                val media = mediaBox(page)
                val outside = outsideRects(media, box)
                if (outside.isNotEmpty()) {
                    marks += RedactionMark(
                        pageIndex = index,
                        rects = outside,
                        type = RedactionMarkType.AREA,
                        // The strips are outside the visible crop; white keeps
                        // the page looking trimmed if someone reveals it again.
                        color = WHITE
                    )
                }
            }
            if (marks.isEmpty()) {
                return@runCatching CropResult(
                    bytes = cropped,
                    cropBoxes = boxes,
                    hiddenContentRemoved = true
                )
            }
            val redacted = if (decoder == null) {
                PdfRedaction.apply(reopened, marks)
            } else {
                PdfRedaction.apply(reopened, marks, decoder)
            }
            val warnings = redacted.warnings.filterNot { it.isBlank() } +
                if (redacted.complete) {
                    emptyList()
                } else {
                    listOf(
                        "Some hidden content could not be removed safely; " +
                            "see the details before sharing the file."
                    )
                }
            CropResult(
                bytes = redacted.bytes,
                cropBoxes = boxes,
                hiddenContentRemoved = redacted.complete,
                warnings = warnings,
                unredacted = redacted.unredacted
            )
        }
    }

    /**
     * Detects the white margins of the selected pages from a rendered raster
     * and proposes a CropBox for each. [rasterizer] is the host painting hook
     * (`JvmPageRasterizer` in the CLI, `AndroidPageRasterizer` in the app).
     *
     * A pixel is content when it differs from white by more than [tolerance]
     * on any RGB channel; alpha is ignored. Pages that are blank at that
     * tolerance get no proposal and are listed in [AutoCropResult.emptyPages].
     *
     * With [sameSize] every proposal is grown around its content to the
     * largest proposed width and height, so pages of different sizes come out
     * looking the same. A page whose current CropBox is too small for that size
     * is clamped and listed in [AutoCropResult.unevenPages]. The proposal never
     * reveals anything a previous crop hid: it always stays inside the page's
     * current CropBox (and therefore inside the MediaBox).
     */
    fun auto(
        document: PdfDocument,
        rasterizer: PageRasterizer,
        tolerance: Int = DEFAULT_TOLERANCE,
        dpi: Int = DEFAULT_AUTO_DPI,
        pages: PageSelection = PageSelection.All,
        sameSize: Boolean = false
    ): Result<AutoCropResult> = runCatching {
        require(tolerance in 0..255) { "The tolerance must be between 0 and 255" }
        require(dpi in 36..600) { "The detection resolution must be between 36 and 600 dpi" }
        val allPages = document.pages()
        require(allPages.isNotEmpty()) { "The document has no pages" }
        val selected = selectedPages(pages, allPages.size)
        require(selected.isNotEmpty()) { "No pages matched the page selection" }

        val warnings = ArrayList<String>()
        val emptyPages = ArrayList<Int>()
        val proposals = LinkedHashMap<Int, PdfRect>()
        for (index in selected) {
            val page = allPages[index]
            val box = PageGeometry.boxOf(page)
            val plan = PageGeometry.plan(box, dpi / 72.0)
            val raster = try {
                rasterizer.rasterize(page, plan)
            } catch (t: Throwable) {
                warnings += "page ${index + 1}: the preview could not be rendered (${t.message ?: "unknown error"})"
                null
            }
            if (raster == null) {
                warnings += "page ${index + 1}: no proposal because the page could not be rendered"
                continue
            }
            val borders = CropDetection.findContentBorders(raster, tolerance)
                ?: run {
                    emptyPages += index
                    warnings += "page ${index + 1}: blank at tolerance $tolerance, no crop proposed"
                    null
                }
                ?: continue
            val detected = CropDetection.toUserRect(borders, plan) ?: run {
                warnings += "page ${index + 1}: the detected box could not be mapped back to the page"
                null
            }
            if (detected == null) continue
            val pageBox = PdfRect(box.left, box.bottom, box.right, box.top)
            val clamped = intersect(detected, pageBox)
            if (clamped == null || clamped.width < MIN_CROP_POINTS || clamped.height < MIN_CROP_POINTS) {
                warnings += "page ${index + 1}: the detected content leaves no usable page area"
                continue
            }
            proposals[index] = clamped
        }

        var uniform: CropSize? = null
        val uneven = ArrayList<Int>()
        if (sameSize && proposals.isNotEmpty()) {
            val width = proposals.values.maxOf { it.width }
            val height = proposals.values.maxOf { it.height }
            uniform = CropSize(width, height)
            for ((index, proposal) in proposals.entries.toList()) {
                val page = allPages[index]
                val pageBox = PageGeometry.boxOf(page)
                val base = PdfRect(pageBox.left, pageBox.bottom, pageBox.right, pageBox.top)
                val targetWidth = min(width, base.width)
                val targetHeight = min(height, base.height)
                val x = (proposal.centerX - targetWidth / 2.0)
                    .coerceIn(base.left, max(base.left, base.right - targetWidth))
                val y = (proposal.centerY - targetHeight / 2.0)
                    .coerceIn(base.bottom, max(base.bottom, base.top - targetHeight))
                val grown = PdfRect(x, y, x + targetWidth, y + targetHeight)
                proposals[index] = grown
                if (targetWidth + EPSILON < width || targetHeight + EPSILON < height) {
                    uneven += index
                }
            }
            for (index in uneven) {
                warnings += "page ${index + 1}: the page is smaller than " +
                    "${formatNumber(width)} x ${formatNumber(height)} pt, so its crop box is smaller"
            }
        }

        AutoCropResult(
            boxes = proposals,
            uniformSize = uniform,
            emptyPages = emptyPages,
            unevenPages = uneven,
            warnings = warnings
        )
    }

    // ---------------------------------------------------------------- helpers

    private val WHITE = com.pdfpaper.engine.graphics.PdfColor(1.0, 1.0, 1.0)

    private fun selectedPages(selection: PageSelection, count: Int): List<Int> =
        (0 until count).filter { selection.includes(it) }

    private fun validateMargins(margins: CropSpec.Margins) {
        val values = margins.four
        require(values.all { it.isFinite() }) { "The margins must be finite numbers" }
        require(values.all { it >= 0.0 }) { "The margins cannot be negative" }
    }

    private fun requireValidBox(box: PdfRect, message: String) {
        require(box.width >= MIN_CROP_POINTS && box.height >= MIN_CROP_POINTS) { message }
    }

    private fun validateInfinite(box: PdfRect, index: Int) {
        require(
            box.left.isFinite() && box.bottom.isFinite() &&
                box.right.isFinite() && box.top.isFinite()
        ) { "Page ${index + 1} has a crop box that is not a finite rectangle" }
    }

    /** A CropBox has to stay inside the MediaBox (§14.11.2, Table 30). */
    private fun validateInsideMedia(box: PdfRect, page: PdfPage, index: Int) {
        val media = mediaBox(page)
        require(
            box.left >= media.left - EPSILON &&
                box.bottom >= media.bottom - EPSILON &&
                box.right <= media.right + EPSILON &&
                box.top <= media.top + EPSILON
        ) {
            "Page ${index + 1}: the crop box must stay inside the media box " +
                "[${formatNumber(media.left)} ${formatNumber(media.bottom)} " +
                "${formatNumber(media.right)} ${formatNumber(media.top)}]"
        }
    }

    /** The box margins are trimmed from: current CropBox, else MediaBox, else Letter. */
    private fun currentBox(page: PdfPage): PdfRect =
        PdfRect.of(page.cropBox)
            ?: PdfRect.of(page.mediaBox)
            ?: PdfRect(0.0, 0.0, LETTER_WIDTH, LETTER_HEIGHT)

    private fun mediaBox(page: PdfPage): PdfRect =
        PdfRect.of(page.mediaBox) ?: currentBox(page)

    private fun cropArray(box: PdfRect): PdfArray = PdfArray(
        listOf(
            PdfReal(box.left),
            PdfReal(box.bottom),
            PdfReal(box.right),
            PdfReal(box.top)
        )
    )

    private fun intersect(a: PdfRect, b: PdfRect): PdfRect? {
        val left = max(a.left, b.left)
        val bottom = max(a.bottom, b.bottom)
        val right = min(a.right, b.right)
        val top = min(a.top, b.top)
        if (right <= left || top <= bottom) return null
        return PdfRect(left, bottom, right, top)
    }

    /**
     * The parts of [media] that lie outside [crop], as up to four rectangles.
     * Together they exactly cover `media - crop` (no overlap between them).
     */
    private fun outsideRects(media: PdfRect, crop: PdfRect): List<PdfRect> {
        val out = ArrayList<PdfRect>(4)
        if (crop.left > media.left + EPSILON) {
            out += PdfRect(media.left, media.bottom, min(crop.left, media.right), media.top)
        }
        if (crop.right < media.right - EPSILON) {
            out += PdfRect(max(crop.right, media.left), media.bottom, media.right, media.top)
        }
        val innerLeft = max(media.left, crop.left)
        val innerRight = min(media.right, crop.right)
        if (innerRight > innerLeft + EPSILON) {
            if (crop.bottom > media.bottom + EPSILON) {
                out += PdfRect(innerLeft, media.bottom, innerRight, min(crop.bottom, media.top))
            }
            if (crop.top < media.top - EPSILON) {
                out += PdfRect(innerLeft, max(crop.top, media.bottom), innerRight, media.top)
            }
        }
        return out.filter { it.width > 0.0 && it.height > 0.0 }
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(java.util.Locale.US, "%.1f", value)
}
