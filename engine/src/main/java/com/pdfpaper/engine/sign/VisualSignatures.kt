package com.pdfpaper.engine.sign

import com.pdfpaper.engine.annot.AnnotationPlacement
import com.pdfpaper.engine.annot.VisualSignatureSpec
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.edit.ImageData
import com.pdfpaper.engine.edit.ImageFormat
import com.pdfpaper.engine.edit.PlaceFormEdit
import com.pdfpaper.engine.edit.RasterDecoder
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfBoolean
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.write.PdfDocumentAssembler
import com.pdfpaper.engine.write.PdfPageOps
import com.pdfpaper.engine.write.PdfPageSelection
import com.pdfpaper.engine.write.PdfWriter
import java.util.UUID

/**
 * Places and edits visual signatures (task T26).
 *
 * A visual signature is an ordinary /Ink or /Stamp annotation whose
 * self-contained appearance draws one of three sources: freehand strokes with
 * pressure, a typed name in a standard-14 font, or a PNG/JPEG picture. It is
 * *not* a digital signature: no bytes are hashed, no certificate is used and
 * no CMS blob is written. Every [VisualSignatureResult] therefore exposes
 * [VisualSignatureResult.visualOnly] = true.
 *
 * The annotation carries `/PdfPaperVisualSignature << /VisualOnly true
 * /Source ... >>` and a stable `/NM`. [list] and [find] use those entries to
 * locate the signature after a save; [move], [resize], [rotate], [delete] and
 * [flatten] accept a [VisualSignatureRef] returned by those lookups.
 *
 * All functions return [Result] and never throw for malformed input.
 */
object VisualSignatures {

    /** Human-readable warning used by UIs and the CLI. */
    const val NOTE: String = "Visual only: this is not a cryptographic signature."

    /** Near-white pixels removed by `transparentBackground` (each RGB channel). */
    private const val WHITE_CUTOFF = 240

    /**
     * Places [artwork] on a page. [placement] is in page user space with the
     * origin at the bottom-left; [placement.width] is the unrotated width.
     *
     * With [VisualSignatureOptions.flatten] the appearance is painted into page
     * content instead of remaining an annotation. Without it the signature is
     * written as an /Ink (drawn) or /Stamp (typed/image) annotation and can be
     * edited later through [move], [resize], [rotate] and [delete].
     */
    fun place(
        document: PdfDocument,
        artwork: SignatureArtwork,
        placement: SignaturePlacement,
        options: VisualSignatureOptions = VisualSignatureOptions(),
        version: String = PdfWriter.DEFAULT_VERSION,
        /**
         * Host image decoder, needed only when [SignatureArtwork.Image] asks to
         * remove a white background from a JPEG. PNG alpha uses the decoded
         * pixels that came with the image and needs no decoder.
         */
        decoder: RasterDecoder? = null
    ): Result<VisualSignatureResult> = runCatching {
        val pages = document.pages()
        require(placement.pageIndex in pages.indices) {
            "Page ${placement.pageIndex} is out of range (0..${pages.size - 1})"
        }
        require(placement.x.isFinite() && placement.y.isFinite()) {
            "signature coordinates must be finite"
        }
        require(placement.width.isFinite() && placement.width > 0.0) {
            "signature width must be a positive finite number"
        }
        val rotation = normalizeQuarter(placement.rotation)
        val prepared = prepare(artwork, decoder)
        val marker = options.id?.takeIf { it.isNotBlank() } ?: "pdfpaper-visual-${UUID.randomUUID()}"
        require(list(document).none { it.id == marker }) {
            "a visual signature with id '$marker' already exists in this document"
        }
        val spec = VisualSignatureSpec(
            pageIndex = placement.pageIndex,
            artwork = prepared,
            x = placement.x,
            y = placement.y,
            width = placement.width,
            rotation = rotation,
            caption = options.caption,
            author = options.author,
            marker = marker
        )
        val placedBytes = PdfPageOps.applyAnnotations(document, listOf(spec), version).getOrThrow()
        val source = sourceOf(prepared)
        if (!options.flatten) {
            return@runCatching reopen(placedBytes).use { saved ->
                val info = list(saved, placement.pageIndex).firstOrNull { it.id == marker }
                    ?: error("the placed visual signature could not be read back")
                VisualSignatureResult(
                    bytes = placedBytes,
                    pageIndex = placement.pageIndex,
                    signatureId = marker,
                    source = source,
                    annotationIds = listOf(info.annotationId),
                    rect = info.rect,
                    change = VisualSignatureChange.PLACED
                )
            }
        }
        reopen(placedBytes).use { saved ->
            val info = list(saved, placement.pageIndex).firstOrNull { it.id == marker }
                ?: error("the placed visual signature could not be read back")
            val flat = flattenBytes(saved, info, version)
            VisualSignatureResult(
                bytes = flat,
                pageIndex = placement.pageIndex,
                signatureId = marker,
                source = source,
                annotationIds = emptyList(),
                rect = null,
                change = VisualSignatureChange.PLACED,
                flattened = true
            )
        }
    }

    /** All visual signatures in [document], optionally only on [pageIndex]. */
    fun list(document: PdfDocument, pageIndex: Int? = null): List<VisualSignatureInfo> {
        val pages = document.pages()
        val indices = if (pageIndex == null) pages.indices else listOf(pageIndex)
        val out = ArrayList<VisualSignatureInfo>()
        for (index in indices) {
            val page = pages.getOrNull(index) ?: continue
            for (annotation in page.annotations()) {
                out += infoOf(annotation, index) ?: continue
            }
        }
        return out
    }

    /** The first visual signature with [id], or null. */
    fun find(document: PdfDocument, id: String): VisualSignatureInfo? =
        list(document).firstOrNull { it.id == id }

    /** The signature [ref] points at, or null. */
    fun find(document: PdfDocument, ref: VisualSignatureRef): VisualSignatureInfo? =
        list(document, ref.pageIndex).firstOrNull { it.annotationId == ref.annotationId }

    /**
     * Shifts a placed signature by ([dx], [dy]) points. The appearance is
     * unchanged; only /Rect (and a drawn /InkList) moves.
     */
    fun move(
        document: PdfDocument,
        ref: VisualSignatureRef,
        dx: Double,
        dy: Double,
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<VisualSignatureResult> = runCatching {
        require(dx.isFinite() && dy.isFinite()) { "the move offset must be finite" }
        val info = requireInfo(document, ref)
        val old = info.rect ?: error("the visual signature has no rectangle")
        val rect = PdfRect(old.left + dx, old.bottom + dy, old.right + dx, old.top + dy)
        saveMoved(document, info, rect, 0, VisualSignatureChange.MOVED, version)
    }

    /**
     * Gives a placed signature an explicit new box in page space. The
     * appearance is stretched to the box; [width] and [height] must be
     * positive.
     */
    fun resize(
        document: PdfDocument,
        ref: VisualSignatureRef,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<VisualSignatureResult> = runCatching {
        require(x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite()) {
            "the resize box must be finite"
        }
        require(width > 0.0 && height > 0.0) { "the resize box must have a positive size" }
        val info = requireInfo(document, ref)
        val rect = PdfRect(x, y, x + width, y + height)
        saveMoved(document, info, rect, 0, VisualSignatureChange.RESIZED, version)
    }

    /**
     * Turns a placed signature by [degrees] counter-clockwise (multiples of
     * 90). For a quarter turn the box's width and height are exchanged around
     * its centre, and the appearance stream gets a matching `cm`.
     */
    fun rotate(
        document: PdfDocument,
        ref: VisualSignatureRef,
        degrees: Int,
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<VisualSignatureResult> = runCatching {
        val turn = normalizeQuarter(degrees)
        val info = requireInfo(document, ref)
        val old = info.rect ?: error("the visual signature has no rectangle")
        val swap = turn == 90 || turn == 270
        val width = if (swap) old.height else old.width
        val height = if (swap) old.width else old.height
        val rect = PdfRect(
            old.centerX - width / 2.0,
            old.centerY - height / 2.0,
            old.centerX + width / 2.0,
            old.centerY + height / 2.0
        )
        saveMoved(document, info, rect, turn, VisualSignatureChange.ROTATED, version)
    }

    /** Removes a placed visual signature, leaving the page content untouched. */
    fun delete(
        document: PdfDocument,
        ref: VisualSignatureRef,
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<VisualSignatureResult> = runCatching {
        val info = requireInfo(document, ref)
        val bytes = rewrite(
            document = document,
            placements = emptyList(),
            dropped = setOf(info.annotationId),
            version = version
        )
        VisualSignatureResult(
            bytes = bytes,
            pageIndex = info.pageIndex,
            signatureId = info.id,
            source = info.source,
            annotationIds = emptyList(),
            rect = null,
            change = VisualSignatureChange.DELETED,
            deleted = true
        )
    }

    /**
     * Paints the signature's appearance into permanent page content and removes
     * the annotation. The output has no editable visual signature left.
     */
    fun flatten(
        document: PdfDocument,
        ref: VisualSignatureRef,
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<VisualSignatureResult> = runCatching {
        val info = requireInfo(document, ref)
        VisualSignatureResult(
            bytes = flattenBytes(document, info, version),
            pageIndex = info.pageIndex,
            signatureId = info.id,
            source = info.source,
            annotationIds = emptyList(),
            rect = null,
            change = VisualSignatureChange.FLATTENED,
            flattened = true
        )
    }

    // -- Internals ------------------------------------------------------------

    private fun saveMoved(
        document: PdfDocument,
        info: VisualSignatureInfo,
        rect: PdfRect,
        rotation: Int,
        change: VisualSignatureChange,
        version: String
    ): VisualSignatureResult {
        val bytes = rewrite(
            document = document,
            placements = listOf(
                AnnotationPlacement(info.pageIndex, info.annotationId, rect, rotation)
            ),
            dropped = emptySet(),
            version = version
        )
        reopen(bytes).use { saved ->
            val updated = list(saved, info.pageIndex).firstOrNull { it.id == info.id }
                ?: error("the edited visual signature could not be read back")
            return VisualSignatureResult(
                bytes = bytes,
                pageIndex = info.pageIndex,
                signatureId = info.id,
                source = updated.source ?: info.source,
                annotationIds = listOf(updated.annotationId),
                rect = updated.rect,
                change = change
            )
        }
    }

    private fun flattenBytes(
        document: PdfDocument,
        info: VisualSignatureInfo,
        version: String
    ): ByteArray {
        val page = document.pages().getOrNull(info.pageIndex)
            ?: error("Page ${info.pageIndex} is out of range")
        val annotation = page.annotations().firstOrNull { it.ref?.objNum == info.annotationId }
            ?: error("the visual signature annotation is missing")
        val rect = annotation.rect ?: error("the visual signature has no rectangle")
        if (rect.isEmpty) error("the visual signature has an empty rectangle")
        val apDict = resolve(document, annotation.dict["AP"]) as? PdfDict
            ?: error("the visual signature has no appearance dictionary")
        val normalObj = apDict["N"] ?: error("the visual signature has no normal appearance")
        val normal = resolve(document, normalObj) as? PdfStream
            ?: error("the visual signature appearance is not a form XObject")
        val bbox = PdfRect.of(normal.dict["BBox"] as? PdfArray)
            ?: PdfRect(0.0, 0.0, rect.width, rect.height)
        val edit = PlaceFormEdit(
            pageIndex = info.pageIndex,
            form = normalObj,
            x = rect.left,
            y = rect.bottom,
            width = rect.width,
            height = rect.height,
            bbox = bbox
        )
        return PdfDocumentAssembler(
            selections = pageSelections(document),
            primary = document,
            version = version,
            edits = listOf(edit),
            droppedAnnots = setOf(info.annotationId)
        ).assemble()
    }

    private fun rewrite(
        document: PdfDocument,
        placements: List<AnnotationPlacement>,
        dropped: Set<Int>,
        version: String
    ): ByteArray = PdfDocumentAssembler(
        selections = pageSelections(document),
        primary = document,
        version = version,
        droppedAnnots = dropped,
        annotationPlacements = placements
    ).assemble()

    private fun pageSelections(document: PdfDocument): List<PdfPageSelection> {
        val pages = document.pages()
        require(pages.isNotEmpty()) { "the document has no pages" }
        return pages.indices.map { PdfPageSelection(document, it) }
    }

    private fun requireInfo(document: PdfDocument, ref: VisualSignatureRef): VisualSignatureInfo {
        require(ref.annotationId >= 1) { "the annotation id is invalid" }
        return find(document, ref)
            ?: throw IllegalArgumentException(
                "visual signature ${ref.annotationId} was not found on page ${ref.pageIndex}"
            )
    }

    private fun infoOf(annotation: com.pdfpaper.engine.annot.PdfAnnotation, pageIndex: Int): VisualSignatureInfo? {
        val marker = (annotation.dict["NM"] as? PdfString)?.text()?.takeIf { it.isNotBlank() }
            ?: return null
        val custom = annotation.dict["PdfPaperVisualSignature"] as? PdfDict ?: return null
        if ((custom["VisualOnly"] as? PdfBoolean)?.value != true) return null
        val source = VisualSignatureSource.fromPdfName((custom["Source"] as? PdfName)?.name)
        return VisualSignatureInfo(
            pageIndex = pageIndex,
            annotationId = annotation.ref?.objNum ?: 0,
            id = marker,
            source = source,
            rect = annotation.rect,
            author = annotation.title
        )
    }

    private fun sourceOf(artwork: SignatureArtwork): VisualSignatureSource = when (artwork) {
        is SignatureArtwork.Drawn -> VisualSignatureSource.DRAWN
        is SignatureArtwork.Typed -> VisualSignatureSource.TYPED
        is SignatureArtwork.Image -> VisualSignatureSource.IMAGE
    }

    /** Removes the white paper around a scan, keeping real transparency. */
    private fun prepare(artwork: SignatureArtwork, decoder: RasterDecoder?): SignatureArtwork {
        if (artwork !is SignatureArtwork.Image || !artwork.transparentBackground) return artwork
        val image = artwork.image
        val argb: IntArray
        val width: Int
        val height: Int
        if (image.argb != null) {
            argb = image.argb
            width = image.width
            height = image.height
        } else {
            val encoded = image.encoded ?: error("the signature image has no pixel data")
            val raster = decoder?.decode(encoded)
                ?: error("a raster decoder is required to remove the white background from this image")
            argb = raster.argb
            width = raster.width
            height = raster.height
        }
        require(width > 0 && height > 0 && argb.size == width * height) {
            "the signature image has inconsistent dimensions"
        }
        val out = argb.copyOf()
        for (index in out.indices) {
            val pixel = out[index]
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha == 0) continue
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            if (red >= WHITE_CUTOFF && green >= WHITE_CUTOFF && blue >= WHITE_CUTOFF) {
                out[index] = 0
            }
        }
        return SignatureArtwork.Image(
            ImageData(ImageFormat.PNG, width, height, out, encoded = null),
            transparentBackground = true
        )
    }

    private fun normalizeQuarter(degrees: Int): Int {
        require(degrees % 90 == 0) { "rotation must be a multiple of 90 degrees" }
        return ((degrees % 360) + 360) % 360
    }

    private fun reopen(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun resolve(document: PdfDocument, obj: PdfObject?): PdfObject? =
        if (obj is com.pdfpaper.engine.model.PdfRef) document.resolve(obj) else obj
}
