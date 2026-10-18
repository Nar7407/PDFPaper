package com.pdfpaper.engine.edit

import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.write.PdfDocumentAssembler
import com.pdfpaper.engine.write.PdfPageSelection
import com.pdfpaper.engine.write.PdfWriter

/** One image drawn on a page, as reported by [PdfEditOps.listImages]. */
class PlacedImage(
    /** Stable id usable in the image-management edits. */
    val id: String,
    /** XObject resource name (`/Im0` → "Im0"). */
    val resourceName: String,
    /** Placement rectangle in page user space. */
    val bounds: PdfRect,
    /** Sample width, or 0 when the XObject could not be read. */
    val width: Int,
    /** Sample height, or 0 when the XObject could not be read. */
    val height: Int
)

/**
 * Public entry points for engine editing (task T17): list the images on a page
 * and save a document with a list of [PageEdit]s applied. All results are
 * [Result]-based; malformed input never throws.
 */
object PdfEditOps {

    /** Images drawn in the page's own content stream, in draw order. */
    fun listImages(document: PdfDocument, pageIndex: Int): List<PlacedImage> {
        val page = document.pages().getOrNull(pageIndex) ?: return emptyList()
        val xobjects = xObjectTable(page)
        val commands = parseContent(page.contents().getOrNull()?.bytes ?: ByteArray(0)).commands
        return ImagePlacements.scan(commands, { name -> isImageObject(page, xobjects[name]) }).map { placement ->
            val dict = xobjects[placement.name]?.let { resolveObject(page, it)?.dict }
            PlacedImage(
                id = placement.id,
                resourceName = placement.name,
                bounds = placement.bounds,
                width = intOf(dict?.get("Width")) ?: 0,
                height = intOf(dict?.get("Height")) ?: 0
            )
        }
    }

    /**
     * Writes a new document containing every page of [document] with [edits]
     * applied. Adding content appends a content stream; image edits rewrite the
     * affected page's content stream. Uses a full save via [PdfWriter].
     */
    fun save(
        document: PdfDocument,
        edits: List<PageEdit>,
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<ByteArray> = runCatching {
        val pages = document.pages()
        val valid = edits.filter { it.pageIndex in pages.indices }
        val selections = pages.mapIndexed { index, _ -> PdfPageSelection(document, index) }
        PdfDocumentAssembler(selections, document, version, edits = valid).assemble()
    }

    // -- Helpers --------------------------------------------------------------

    private fun xObjectTable(page: PdfPage): Map<String, PdfObject> {
        val table = resolveDict(page, page.resources?.get("XObject")) ?: return emptyMap()
        val result = HashMap<String, PdfObject>()
        for ((name, value) in table.entries) result[name.name] = value
        return result
    }

    private fun isImageObject(page: PdfPage, obj: PdfObject?): Boolean {
        val stream = when (obj) {
            is PdfStream -> obj
            is PdfRef -> page.document.resolve(obj) as? PdfStream
            else -> null
        }
        return (stream?.dict?.get("Subtype") as? PdfName)?.name == "Image"
    }

    private fun resolveDict(page: PdfPage, obj: PdfObject?): PdfDict? = when (obj) {
        is PdfDict -> obj
        is PdfRef -> page.document.resolve(obj) as? PdfDict
        else -> null
    }

    private fun resolveObject(page: PdfPage, obj: PdfObject): PdfStream? = when (obj) {
        is PdfStream -> obj
        is PdfRef -> page.document.resolve(obj) as? PdfStream
        else -> null
    }

    private fun intOf(obj: PdfObject?): Int? = when (obj) {
        is PdfInteger -> obj.value.toInt()
        is PdfReal -> obj.value.toInt()
        else -> null
    }
}
