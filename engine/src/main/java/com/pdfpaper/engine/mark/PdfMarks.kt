package com.pdfpaper.engine.mark

import com.pdfpaper.engine.edit.RasterDecoder
import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.write.PdfDocumentAssembler
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfObjectCopier
import com.pdfpaper.engine.write.PdfPageSelection
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max

/**
 * The page hook the assembler calls: it may rewrite the copied page
 * dictionary, which is how the marks are added and taken away.
 */
private typealias PageHook = (
    PdfPage,
    LinkedHashMap<PdfName, PdfObject>,
    PdfObjectCopier,
    PdfDocumentBuilder
) -> Unit

/**
 * Adds watermarks, headers, footers, page numbers and Bates numbers to a PDF,
 * and takes them away again.
 *
 * Every mark is drawn in its own content stream and marked as optional content
 * (§8.11), so:
 * - a reader lists it in its layers panel under a name such as
 *   "PDFPaper watermark" and can switch it off,
 * - the page's own content stream is never rewritten, and
 * - [removeAdded] finds exactly what PDFPaper added and removes it again,
 *   leaving the original page content as it was.
 */
object PdfMarks {

    /** The PDF version the rewritten file uses. */
    private const val VERSION = "1.7"

    /** The corner margin for a watermark placed in a corner, in points. */
    private const val CORNER_MARGIN = 36.0

    /** The distance between two tiled watermarks, in points. */
    private const val TILE_WIDTH = 220.0
    private const val TILE_HEIGHT = 140.0

    /**
     * Adds a watermark to the selected pages.
     *
     * @param decoder the host decoder for an image watermark; null uses the
     *   engine's own image reader.
     */
    fun applyWatermark(
        document: PdfDocument,
        options: WatermarkOptions,
        decoder: RasterDecoder? = null
    ): Result<ByteArray> = runCatching {
        val pages = document.pages()
        val selected = pages.filter { options.pages.includes(it.index) }
        if (selected.isEmpty()) return@runCatching rewrite(document, emptyMap())

        val applier = MarkApplier(
            group = MarkGroups.WATERMARK,
            fontSpec = options.font,
            fontSize = options.fontSize,
            color = options.color,
            opacity = options.opacity
        )
        val text = (options.content as? WatermarkContent.Text)?.text.orEmpty()
        val imageBytes = (options.content as? WatermarkContent.Image)?.bytes

        rewrite(
            document = document,
            overlays = selected.associate { page -> page.index to page },
            applier = applier,
            behind = options.behindContent,
            draw@{ page, painter, resources ->
                when {
                    imageBytes != null -> {
                        val added = applier.image(imageBytes, decoder) ?: return@draw null
                        resources[PdfName("XObject")] = PdfDict(mapOf(added.first to added.second))
                        val box = pageBox(page)
                        val width = box.width * options.imageWidthFraction.coerceIn(0.05, 1.0)
                        val height = if (applier.imageHeight() > 0) {
                            width * applier.imageHeight() / added.third
                        } else {
                            width
                        }
                        val body = ByteArrayOutputStream()
                        for (place in imagePlacements(box, options.position, width, height)) {
                            painter.drawImage(
                                out = body,
                                imageName = added.first,
                                width = width,
                                height = height,
                                x = place.first,
                                y = place.second,
                                rotationDegrees = options.rotationDegrees
                            )
                        }
                        body.toByteArray()
                    }
                    text.isNotEmpty() -> {
                        val box = pageBox(page)
                        val body = ByteArrayOutputStream()
                        when (options.position) {
                            WatermarkPosition.CENTER -> painter.drawText(
                                body, text, box.centerX, box.centerY - options.fontSize * 0.35,
                                options.rotationDegrees, MarkAlign.CENTER
                            )
                            WatermarkPosition.TILED -> {
                                val columns = max(1, ceil(box.width / TILE_WIDTH).toInt())
                                val rows = max(1, ceil(box.height / TILE_HEIGHT).toInt())
                                for (row in 0 until rows) {
                                    for (column in 0 until columns) {
                                        val x = box.left + (column + 0.5) * (box.width / columns)
                                        val y = box.bottom + (row + 0.5) * (box.height / rows) -
                                            options.fontSize * 0.35
                                        painter.drawText(
                                            body, text, x, y,
                                            options.rotationDegrees, MarkAlign.CENTER
                                        )
                                    }
                                }
                            }
                            WatermarkPosition.TOP_LEFT -> painter.drawText(
                                body, text, box.left + CORNER_MARGIN,
                                box.top - CORNER_MARGIN - options.fontSize,
                                options.rotationDegrees, MarkAlign.LEFT
                            )
                            WatermarkPosition.TOP_RIGHT -> painter.drawText(
                                body, text, box.right - CORNER_MARGIN,
                                box.top - CORNER_MARGIN - options.fontSize,
                                options.rotationDegrees, MarkAlign.RIGHT
                            )
                            WatermarkPosition.BOTTOM_LEFT -> painter.drawText(
                                body, text, box.left + CORNER_MARGIN, box.bottom + CORNER_MARGIN,
                                options.rotationDegrees, MarkAlign.LEFT
                            )
                            WatermarkPosition.BOTTOM_RIGHT -> painter.drawText(
                                body, text, box.right - CORNER_MARGIN, box.bottom + CORNER_MARGIN,
                                options.rotationDegrees, MarkAlign.RIGHT
                            )
                        }
                        body.toByteArray()
                    }
                    else -> null
                }
            },
            fontTexts = listOf(text)
        )
    }

    /**
     * Adds a header and/or a footer with page numbers to the selected pages.
     *
     * @param fileName the name of the file, used by the {filename} token.
     */
    fun applyHeaderFooter(
        document: PdfDocument,
        options: HeaderFooterOptions,
        fileName: String? = null
    ): Result<ByteArray> = runCatching {
        val pages = document.pages()
        val selected = pages.filter { options.pages.includes(it.index) }
        if (selected.isEmpty() || (options.header.isNullOrBlank() && options.footer.isNullOrBlank())) {
            return@runCatching rewrite(document, emptyMap())
        }

        val applier = MarkApplier(
            group = MarkGroups.HEADER_FOOTER,
            fontSpec = options.font,
            fontSize = options.fontSize,
            color = options.color,
            opacity = 1.0
        )
        val title = documentTitle(document)
        val date = LocalDate.now().toString()
        val total = pages.size

        rewrite(
            document = document,
            overlays = selected.associate { page -> page.index to page },
            applier = applier,
            behind = false,
            draw@{ page, painter, _ ->
                if (options.skipFirstPage && page.index == 0) return@draw ByteArray(0)
                val number = options.startNumber + page.index
                val box = pageBox(page)
                val body = ByteArrayOutputStream()
                val x = when (options.align) {
                    MarkAlign.LEFT -> box.left + options.marginLeft
                    MarkAlign.CENTER -> (box.left + options.marginLeft + box.right - options.marginRight) / 2.0
                    MarkAlign.RIGHT -> box.right - options.marginRight
                }
                options.header?.let {
                    painter.drawText(
                        body,
                        expand(it, number, total, date, fileName, title, options.format),
                        x,
                        box.top - options.marginTop,
                        align = options.align
                    )
                }
                options.footer?.let {
                    painter.drawText(
                        body,
                        expand(it, number, total, date, fileName, title, options.format),
                        x,
                        box.bottom + options.marginBottom,
                        align = options.align
                    )
                }
                body.toByteArray()
            },
            fontTexts = listOfNotNull(
                options.header,
                options.footer
            ).flatMap { listOf(it) }
        )
    }

    /** Adds Bates numbers to the selected pages. */
    fun applyBates(
        document: PdfDocument,
        options: BatesOptions
    ): Result<ByteArray> = runCatching {
        val pages = document.pages()
        val selected = pages.filter { options.pages.includes(it.index) }
        if (selected.isEmpty()) return@runCatching rewrite(document, emptyMap())

        val applier = MarkApplier(
            group = MarkGroups.BATES,
            fontSpec = options.font,
            fontSize = options.fontSize,
            color = options.color,
            opacity = 1.0
        )
        val stamps = selected.mapIndexed { index, _ -> batesText(options, options.startNumber + index) }

        rewrite(
            document = document,
            overlays = selected.associate { page -> page.index to page },
            applier = applier,
            behind = false,
            draw@{ page, painter, _ ->
                val index = selected.indexOfFirst { it.index == page.index }
                val stamp = stamps.getOrElse(index) { "" }
                val box = pageBox(page)
                val body = ByteArrayOutputStream()
                val align = batesAlign(options.placement)
                val x = when (align) {
                    MarkAlign.LEFT -> box.left + options.marginLeft
                    MarkAlign.CENTER -> (box.left + options.marginLeft + box.right - options.marginRight) / 2.0
                    MarkAlign.RIGHT -> box.right - options.marginRight
                }
                val y = when (options.placement) {
                    BatesPlacement.TOP_LEFT, BatesPlacement.TOP_CENTER, BatesPlacement.TOP_RIGHT ->
                        box.top - options.marginTop
                    else -> box.bottom + options.marginBottom
                }
                painter.drawText(body, stamp, x, y, align = align)
                body.toByteArray()
            },
            fontTexts = stamps
        )
    }

    /**
     * Removes everything PDFPaper added: the watermarks, the headers, the
     * footers, the page numbers and the Bates numbers. The page's own content
     * is left exactly as it was.
     */
    fun removeAdded(document: PdfDocument): Result<ByteArray> = runCatching {
        val markers = MarkGroups.all.map { "/OC /${MarkGroups.propertyName(it)} BDC" }
        val pages = document.pages()
        val hooks = HashMap<Int, PageHook>()
        for (page in pages) {
            hooks[page.index] = { _, entries, _, builder -> stripAdded(entries, builder, markers) }
        }
        PdfDocumentAssembler(
            selections = pages.map { PdfPageSelection(document, it.index) },
            primary = document,
            version = VERSION,
            preserveOptionalContent = true,
            preserveOutlines = true,
            preserveNamedDestinations = true,
            preserveAcroForm = true,
            garbageCollect = true,
            pageHooks = hooks,
            catalogExtras = { _, catalog -> removeGroups(catalog) }
        ).assemble()
    }

    // -- Internals ------------------------------------------------------------

    /**
     * Rewrites the document with the added content on the given pages.
     *
     * The assembler is given one hook per page; the hook draws the mark, adds
     * its content stream and merges the resources it needs.
     */
    private fun rewrite(
        document: PdfDocument,
        overlays: Map<Int, PdfPage>,
        applier: MarkApplier? = null,
        behind: Boolean = false,
        draw: ((PdfPage, MarkPainter, MutableMap<PdfName, PdfObject>) -> ByteArray?)? = null,
        fontTexts: List<String> = emptyList()
    ): ByteArray {
        val pages = document.pages()
        val hooks = HashMap<Int, PageHook>()
        for (page in pages) {
            if (!overlays.containsKey(page.index)) continue
            hooks[page.index] = hook@{ target, entries, _, builder ->
                val mark = applier ?: return@hook
                mark.bind(builder)
                // A font that cannot be read is a failure, not a page without
                // a mark: the caller asked for something to be drawn.
                val font = mark.font(fontTexts)
                    ?: error("The font for the added text could not be read")
                val graphicsState = mark.graphicsState() ?: error("The graphics state could not be made")
                val resources = LinkedHashMap<PdfName, PdfObject>()
                resources[PdfName("Font")] = PdfDict(mapOf(font.resourceName to font.fontRef))
                resources[PdfName("ExtGState")] = PdfDict(
                    mapOf(graphicsState.first to graphicsState.second)
                )
                mark.ocg()?.let { ref ->
                    resources[PdfName("Properties")] = PdfDict(
                        mapOf(PdfName(mark.propertyName) to ref)
                    )
                }
                val painter = MarkPainter(
                    font = font,
                    fontSize = mark.fontSize(),
                    color = mark.color(),
                    opacity = mark.opacity(),
                    gsName = graphicsState.first
                )
                val body = draw?.invoke(target, painter, resources) ?: return@hook
                if (body.isEmpty()) return@hook
                mark.applyToPage(
                    entries = entries,
                    content = markContent(mark.propertyName, body),
                    behind = behind,
                    resources = resources
                )
            }
        }
        return PdfDocumentAssembler(
            selections = pages.map { PdfPageSelection(document, it.index) },
            primary = document,
            version = VERSION,
            preserveOptionalContent = true,
            preserveOutlines = true,
            preserveNamedDestinations = true,
            preserveAcroForm = true,
            garbageCollect = true,
            pageHooks = hooks,
            catalogExtras = { builder, catalog ->
                if (applier != null) addGroup(catalog, applier)
            }
        ).assemble()
    }

    /** Adds the group to the catalogue's optional content list. */
    private fun addGroup(
        catalog: LinkedHashMap<PdfName, PdfObject>,
        applier: MarkApplier
    ) {
        val existing = catalog[PdfName("OCProperties")] as? PdfDict
        val existingGroups = (existing?.get("OCGs") as? PdfArray)?.items.orEmpty()
        val ref = applier.ocg() ?: return
        if (existingGroups.any { it == ref }) return
        val groups = PdfArray(existingGroups + ref)
        val configuration = (existing?.get("D") as? PdfDict)?.let { current ->
            val entries = LinkedHashMap<PdfName, PdfObject>(current.entries)
            val order = (current["Order"] as? PdfArray)?.items.orEmpty()
            val on = (current["ON"] as? PdfArray)?.items.orEmpty()
            entries[PdfName("Order")] = PdfArray(order + ref)
            entries[PdfName("ON")] = PdfArray(on + ref)
            PdfDict(entries)
        } ?: PdfDict(
            linkedMapOf(
                PdfName("Order") to PdfArray(listOf(ref)),
                PdfName("ON") to PdfArray(listOf(ref))
            )
        )
        catalog[PdfName("OCProperties")] = PdfDict(
            linkedMapOf(
                PdfName("Type") to PdfName("OCProperties"),
                PdfName("OCGs") to groups,
                PdfName("D") to configuration
            )
        )
    }

    /** Takes the PDFPaper groups out of the catalogue's optional content list. */
    private fun removeGroups(catalog: LinkedHashMap<PdfName, PdfObject>) {
        val properties = catalog[PdfName("OCProperties")] as? PdfDict ?: return
        val groups = properties["OCGs"] as? PdfArray ?: return
        val kept = groups.items.filterNot { isPdfPaperGroup(it) }
        if (kept.size == groups.items.size) return
        val entries = LinkedHashMap<PdfName, PdfObject>(properties.entries)
        if (kept.isEmpty()) {
            entries.remove(PdfName("OCGs"))
        } else {
            entries[PdfName("OCGs")] = PdfArray(kept)
        }
        val configuration = entries[PdfName("D")] as? PdfDict
        if (configuration != null) {
            val configEntries = LinkedHashMap<PdfName, PdfObject>(configuration.entries)
            for (key in listOf("Order", "ON", "OFF")) {
                val array = configuration[key] as? PdfArray ?: continue
                val filtered = array.items.filterNot { isPdfPaperGroup(it) }
                if (filtered.size != array.items.size) {
                    configEntries[PdfName(key)] = PdfArray(filtered)
                }
            }
            entries[PdfName("D")] = PdfDict(configEntries)
        }
        if (entries.containsKey(PdfName("OCGs"))) {
            catalog[PdfName("OCProperties")] = PdfDict(entries)
        } else {
            catalog.remove(PdfName("OCProperties"))
        }
    }

    /** True when the optional content group is one of PDFPaper's own. */
    private fun isPdfPaperGroup(value: PdfObject): Boolean {
        val dict = value as? PdfDict ?: return false
        val name = (dict["Name"] as? PdfString)?.text() ?: return false
        return name in MarkGroups.all
    }

    /**
     * Takes the added content out of a page: the content streams PDFPaper
     * added are dropped and the page's own stream is left as it was.
     */
    private fun stripAdded(
        entries: LinkedHashMap<PdfName, PdfObject>,
        builder: PdfDocumentBuilder,
        markers: List<String>
    ) {
        val contents = entries[PdfName("Contents")]
        val kept: List<PdfObject> = when (contents) {
            null -> emptyList()
            is PdfArray -> contents.items.filterNot { item ->
                val stream = when (item) {
                    is PdfStream -> item
                    is PdfRef -> builder.get(item.objNum) as? PdfStream
                    else -> null
                }
                stream != null && isAddedContent(stream, markers)
            }
            else -> listOf(contents)
        }
        when {
            kept.isEmpty() -> entries.remove(PdfName("Contents"))
            kept.size == 1 -> entries[PdfName("Contents")] = kept.first()
            else -> entries[PdfName("Contents")] = PdfArray(kept)
        }
        // The optional content properties of the added groups go as well.
        val resources = entries[PdfName("Resources")] as? PdfDict
        val properties = resources?.get("Properties") as? PdfDict
        if (properties != null) {
            val keptProperties = LinkedHashMap<PdfName, PdfObject>(properties.entries)
            for (group in MarkGroups.all) {
                keptProperties.remove(PdfName(MarkGroups.propertyName(group)))
            }
            val newResources = LinkedHashMap<PdfName, PdfObject>(resources.entries)
            if (keptProperties.isEmpty()) {
                newResources.remove(PdfName("Properties"))
            } else {
                newResources[PdfName("Properties")] = PdfDict(keptProperties)
            }
            entries[PdfName("Resources")] = PdfDict(newResources)
        }
    }

    /** True when the stream is one of the content streams PDFPaper added. */
    private fun isAddedContent(stream: PdfStream, markers: List<String>): Boolean {
        val raw = String(stream.data, StandardCharsets.ISO_8859_1)
        if (markers.any { raw.contains(it) }) return true
        val decoded = stream.decoded { null }.getOrNull()?.bytes ?: return false
        val text = String(decoded, StandardCharsets.ISO_8859_1)
        return markers.any { text.contains(it) }
    }

    // -- Text helpers ---------------------------------------------------------

    /**
     * Where an image watermark is drawn: the bottom-left corner of each copy.
     * The same places the text watermark uses, so both kinds of mark behave
     * the same way.
     */
    private fun imagePlacements(
        box: PdfRect,
        position: WatermarkPosition,
        width: Double,
        height: Double
    ): List<Pair<Double, Double>> = when (position) {
        WatermarkPosition.CENTER -> listOf(
            box.centerX - width / 2.0 to box.centerY - height / 2.0
        )
        WatermarkPosition.TILED -> {
            val columns = max(1, ceil(box.width / TILE_WIDTH).toInt())
            val rows = max(1, ceil(box.height / TILE_HEIGHT).toInt())
            val out = ArrayList<Pair<Double, Double>>()
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val x = box.left + (column + 0.5) * (box.width / columns) - width / 2.0
                    val y = box.bottom + (row + 0.5) * (box.height / rows) - height / 2.0
                    out += x to y
                }
            }
            out
        }
        WatermarkPosition.TOP_LEFT -> listOf(
            box.left + CORNER_MARGIN to box.top - CORNER_MARGIN - height
        )
        WatermarkPosition.TOP_RIGHT -> listOf(
            box.right - CORNER_MARGIN - width to box.top - CORNER_MARGIN - height
        )
        WatermarkPosition.BOTTOM_LEFT -> listOf(
            box.left + CORNER_MARGIN to box.bottom + CORNER_MARGIN
        )
        WatermarkPosition.BOTTOM_RIGHT -> listOf(
            box.right - CORNER_MARGIN - width to box.bottom + CORNER_MARGIN
        )
    }

    /** The text of a Bates number, e.g. "ABC-000042-X". */
    internal fun batesText(options: BatesOptions, number: Int): String {
        val digits = options.digits.coerceIn(1, 12)
        val padded = number.toString().padStart(digits, '0')
        return options.prefix + padded + options.suffix
    }

    private fun batesAlign(placement: BatesPlacement): MarkAlign = when (placement) {
        BatesPlacement.TOP_LEFT, BatesPlacement.BOTTOM_LEFT -> MarkAlign.LEFT
        BatesPlacement.TOP_CENTER, BatesPlacement.BOTTOM_CENTER -> MarkAlign.CENTER
        BatesPlacement.TOP_RIGHT, BatesPlacement.BOTTOM_RIGHT -> MarkAlign.RIGHT
    }

    /** Replaces the {page}, {pages}, {date}, {filename} and {title} tokens. */
    internal fun expand(
        template: String,
        page: Int,
        total: Int,
        date: String,
        fileName: String?,
        title: String,
        format: NumberFormat
    ): String = template
        .replace(MarkTokens.PAGE, format.format(page, total))
        .replace(MarkTokens.PAGES, total.toString())
        .replace(MarkTokens.DATE, date)
        .replace(MarkTokens.FILENAME, fileName.orEmpty())
        .replace(MarkTokens.TITLE, title)

    /** The document's title from its information dictionary. */
    private fun documentTitle(document: PdfDocument): String {
        val raw = document.file.trailer.raw["Info"] ?: return ""
        val ref = PdfRef.parse(raw) ?: return ""
        val info = document.resolve(ref) as? PdfDict ?: return ""
        return (info["Title"] as? PdfString)?.text().orEmpty()
    }
}
