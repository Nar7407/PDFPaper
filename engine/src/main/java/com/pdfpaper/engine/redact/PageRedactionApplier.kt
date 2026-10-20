package com.pdfpaper.engine.redact

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.edit.ContentSerializer
import com.pdfpaper.engine.edit.Flate
import com.pdfpaper.engine.edit.Standard14
import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.edit.WinAnsi
import com.pdfpaper.engine.edit.addStandardFont
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfObjectCopier
import kotlin.math.min

/**
 * Builds the replacement /Contents and /Resources for one redacted page. It is
 * called by the assembler after boxes and surviving annotations have been
 * copied, so it only has to deal with painted page content.
 */
internal class PageRedactionApplier(
    private val builder: PdfDocumentBuilder,
    private val copier: PdfObjectCopier,
    private val page: PdfPage,
    private val marks: List<RedactionMark>,
    private val decoder: ExternalImageDecoder?,
    private val context: RedactionContext
) {

    fun apply(entries: LinkedHashMap<PdfName, PdfObject>) {
        val pageBox = PdfRect.of(page.cropBox) ?: PdfRect(0.0, 0.0, 612.0, 792.0)
        val wholePage = marks.any { it.type == RedactionMarkType.PAGE || it.rects.isEmpty() }
        val areas = if (wholePage) {
            listOf(pageBox)
        } else {
            marks.flatMap { it.rects }
        }
        val overlay = marks.mapNotNull { mark ->
            mark.overlayText?.takeIf { it.isNotBlank() }?.let { mark to it }
        }
        val expected = marks.mapNotNull { it.expectedText?.trim() }.filter { it.isNotEmpty() }.distinct()
        context.marksApplied += marks.size

        val allNames = resourceNames(page.resources)
        val names = ResourceNames(allNames)
        val resources: PdfDict
        val commands: List<ContentCommand>
        if (wholePage) {
            resources = PdfDict(emptyMap())
            commands = emptyList()
        } else {
            val bytes = page.contents().getOrNull()?.bytes
            if (bytes == null && page.dict["Contents"] != null) {
                context.cannotRedact("page ${page.index + 1}: content stream could not be decoded; all painted content was removed")
                resources = PdfDict(emptyMap())
                commands = emptyList()
            } else {
                val parsed = if (bytes == null) null else parseContent(bytes)
                val rewriter = StreamRewriter(builder, copier, page.document, decoder, context, names)
                val result = rewriter.rewrite(parsed?.commands.orEmpty(), page.resources, areas, expected)
                resources = result.resources ?: PdfDict(emptyMap())
                commands = result.commands
            }
        }

        val resourceEntries = LinkedHashMap(resources.entries)
        val cover = coverCommands(areas, marks, pageBox, overlay, resourceEntries)
        val finalCommands = commands + cover
        val data = ContentSerializer.serialize(finalCommands)
        val contentRef = builder.addStream(
            PdfDict(mapOf(PdfName("Filter") to PdfName("FlateDecode"))),
            Flate.encode(data)
        )
        entries[PdfName("Contents")] = contentRef
        entries[PdfName("Resources")] = PdfDict(resourceEntries)
    }

    private fun resourceNames(resources: PdfDict?): Set<String> {
        val names = HashSet<String>()
        if (resources == null) return names
        for (key in resources.entries.keys) names += key.name
        for (groupName in listOf("Font", "XObject", "ColorSpace", "ExtGState", "Pattern", "Shading")) {
            val group = resources[groupName] as? PdfDict ?: continue
            for (key in group.entries.keys) names += key.name
        }
        return names
    }

    /**
     * Adds cover rectangles and optional overlay text. It also creates the
     * standard-14 font lazily, merging it into the page's /Font resources.
     */
    private fun coverCommands(
        areas: List<PdfRect>,
        marks: List<RedactionMark>,
        pageBox: PdfRect,
        overlay: List<Pair<RedactionMark, String>>,
        resources: LinkedHashMap<PdfName, PdfObject>
    ): List<ContentCommand> {
        if (areas.isEmpty()) return emptyList()
        val out = ArrayList<ContentCommand>()
        for (area in areas) {
            val color = marks.firstOrNull { it.rects.contains(area) }?.color ?: PdfColor.BLACK
            out += ContentCommand(emptyList(), "q")
            out += ContentCommand(
                listOf(PdfReal(color.red), PdfReal(color.green), PdfReal(color.blue)),
                "rg"
            )
            out += ContentCommand(
                listOf(PdfReal(area.left), PdfReal(area.bottom), PdfReal(area.width), PdfReal(area.height)),
                "re"
            )
            out += ContentCommand(emptyList(), "f")
            out += ContentCommand(emptyList(), "Q")
        }

        if (overlay.isEmpty()) return out
        val fontName = freshFontName(resources)
        val fontRef = addStandardFont(builder, StandardFont.HELVETICA)
        mergeSubDictionary(resources, "Font", PdfName(fontName), fontRef)

        for ((mark, rawText) in overlay) {
            val area = if (mark.type == RedactionMarkType.PAGE) pageBox else mark.bounds ?: continue
            val text = rawText.replace('\n', ' ').replace('\r', ' ')
            if (text.isBlank() || area.width <= 0.0 || area.height <= 0.0) continue
            var size = min(12.0, area.height * 0.45)
            val widthAtOne = Standard14.width(StandardFont.HELVETICA, text, 1.0)
            if (widthAtOne > 0.0) size = min(size, area.width * 0.9 / widthAtOne)
            size = size.coerceAtLeast(1.0)
            val width = Standard14.width(StandardFont.HELVETICA, text, size)
            val x = area.centerX - width / 2.0
            val y = area.centerY - size * 0.34
            val textColor = if (luminance(mark.color) < 0.5) PdfColor.WHITE else PdfColor.BLACK
            out += ContentCommand(emptyList(), "q")
            out += ContentCommand(
                listOf(PdfReal(textColor.red), PdfReal(textColor.green), PdfReal(textColor.blue)),
                "rg"
            )
            out += ContentCommand(emptyList(), "BT")
            out += ContentCommand(listOf(PdfName(fontName), PdfReal(size)), "Tf")
            out += ContentCommand(
                listOf(PdfReal(1.0), PdfReal(0.0), PdfReal(0.0), PdfReal(1.0), PdfReal(x), PdfReal(y)),
                "Tm"
            )
            out += ContentCommand(listOf(PdfString(WinAnsi.encode(text))), "Tj")
            out += ContentCommand(emptyList(), "ET")
            out += ContentCommand(emptyList(), "Q")
        }
        return out
    }

    private fun freshFontName(resources: LinkedHashMap<PdfName, PdfObject>): String {
        var index = 1
        while (true) {
            val candidate = "PpRedactFont$index"
            val fonts = resources[PdfName("Font")]
            val exists = when (fonts) {
                is PdfDict -> fonts.entries.containsKey(PdfName(candidate))
                is PdfRef -> (builder.get(fonts.objNum) as? PdfDict)?.entries?.containsKey(PdfName(candidate)) == true
                else -> false
            }
            if (!exists) return candidate
            index++
        }
    }

    private fun mergeSubDictionary(
        resources: LinkedHashMap<PdfName, PdfObject>,
        key: String,
        name: PdfName,
        value: PdfObject
    ) {
        val current = resources[PdfName(key)]
        val entries = LinkedHashMap<PdfName, PdfObject>()
        when (current) {
            is PdfDict -> entries.putAll(current.entries)
            is PdfRef -> (builder.get(current.objNum) as? PdfDict)?.entries?.let { entries.putAll(it) }
            else -> Unit
        }
        entries[name] = value
        resources[PdfName(key)] = PdfDict(entries)
    }

    private fun luminance(color: PdfColor): Double =
        color.red * 0.299 + color.green * 0.587 + color.blue * 0.114
}
