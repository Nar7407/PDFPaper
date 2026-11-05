package com.pdfpaper.convert.word

import com.pdfpaper.convert.DocxToPdfException
import com.pdfpaper.convert.NoteBag
import com.pdfpaper.convert.ooxml.PackageCheck
import com.pdfpaper.convert.ooxml.childElements
import com.pdfpaper.convert.ooxml.descendants
import com.pdfpaper.convert.ooxml.first
import com.pdfpaper.convert.ooxml.kids
import com.pdfpaper.convert.raster.PngReader
import com.pdfpaper.convert.raster.PngResult
import com.pdfpaper.engine.image.PdfImages
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Reads a WordprocessingML package into a [WordDocument].
 *
 * The reader is a straight walk of `w:body` in document order: paragraphs,
 * tables, breaks and pictures come out in reading order, styles and numbering
 * are resolved on the way, and everything the converter does not implement is
 * collected in [WordDocument.notes] instead of being silently reordered or
 * dropped.
 */
internal object DocxReader {

    private const val REL_OFFICE_DOCUMENT = "officeDocument"
    private const val DEFAULT_CELL_MARGIN = 5.4

    fun read(archive: ByteArray): WordDocument {
        val (package_, problems) = PackageCheck.read(archive)
        if (package_ == null) {
            throw DocxToPdfException(problems.firstOrNull() ?: "not a readable .docx package")
        }
        val mainName = mainDocumentName(package_) ?: "word/document.xml"
        val main = package_.documents[mainName]
            ?: throw DocxToPdfException("the package has no word/document.xml")
        val body = main.documentElement?.first("body")
            ?: throw DocxToPdfException("word/document.xml has no w:body")
        val rels = package_.documents[relsName(mainName)]

        val notes = NoteBag()
        val theme = Theme.read(package_)
        theme?.applyTo(package_.documents["word/styles.xml"])
        theme?.applyTo(package_.documents["word/numbering.xml"])
        theme?.applyTo(main)
        val sheet = StyleSheet.read(
            package_.documents["word/styles.xml"],
            package_.documents["word/settings.xml"]
        )
        val numbering = Numbering.read(package_.documents["word/numbering.xml"])
        val counters = ListCounters(numbering)
        val images = ImageCache(package_, rels, notes)

        val segments = splitSections(body)
        val sections = ArrayList<WordSectionContent>()
        for (segment in segments) {
            val section = sectionProperties(segment.properties)
            if (section.columns > 1) {
                notes.add("a multi-column section is laid out as a single column")
            }
            val blocks = ArrayList<WordBlock>()
            for (element in segment.blocks) {
                when (element.localName) {
                    "p" -> blocks += WordParagraphBlock(paragraph(element, sheet, counters, notes, images))
                    "tbl" -> blocks += WordTableBlock(table(element, sheet, counters, notes, images))
                    "sdt" -> {
                        val content = element.first("sdtContent") ?: continue
                        for (child in content.childElements()) {
                            when (child.localName) {
                                "p" -> blocks += WordParagraphBlock(paragraph(child, sheet, counters, notes, images))
                                "tbl" -> blocks += WordTableBlock(table(child, sheet, counters, notes, images))
                            }
                        }
                    }
                }
            }
            // The paragraph that carries the inline sectPr belongs to the
            // section it ends, so it is parsed last, with the section boundary
            // already known.
            segment.boundaryParagraph?.let {
                blocks += WordParagraphBlock(paragraph(it, sheet, counters, notes, images))
            }
            sections += WordSectionContent(section, blocks)
        }

        if (package_.parts.containsKey("word/header1.xml") || package_.parts.keys.any { it.startsWith("word/header") }) {
            notes.add("headers and footers are not converted")
        }
        if (package_.parts.keys.any { it.startsWith("word/footnotes") || it.startsWith("word/endnotes") }) {
            notes.add("footnotes and endnotes are not converted")
        }

        val title = package_.documents["docProps/core.xml"]
            ?.documentElement
            ?.let { core ->
                core.childElements().firstOrNull { it.localName == "title" }?.textContent?.trim()
            }

        return WordDocument(
            sections = sections,
            defaultSize = sheet.docDefaults.size ?: 11.0,
            defaultFamily = sheet.docDefaults.family,
            title = title?.ifEmpty { null },
            tabStop = sheet.defaultTabStop ?: 36.0,
            notes = notes.toList()
        )
    }

    // ---------------------------------------------------------------- sections

    private class Segment(
        val properties: Element?,
        /** The paragraph whose `w:pPr/w:sectPr` ends this segment, if any. */
        val boundaryParagraph: Element?,
        val blocks: List<Element>
    )

    /**
     * Splits `w:body` into sections. A `w:sectPr` inside a paragraph applies to
     * everything up to and including that paragraph; the body-level `w:sectPr`
     * applies to the rest (§17.6.18).
     */
    private fun splitSections(body: Element): List<Segment> {
        val bodySection = body.first("sectPr")
        val segments = ArrayList<Segment>()
        var current = ArrayList<Element>()
        for (element in body.childElements()) {
            when (element.localName) {
                "sectPr" -> {} // the body-level one, handled below
                "p" -> {
                    val section = element.first("pPr")?.first("sectPr")
                    if (section != null) {
                        segments += Segment(section, element, current)
                        current = ArrayList()
                    } else {
                        current += element
                    }
                }
                "tbl", "sdt" -> current += element
            }
        }
        segments += Segment(bodySection, null, current)
        return segments
    }

    private fun sectionProperties(element: Element?): WordSection {
        element ?: return WordSection.default()
        val size = element.first("pgSz")
        val margin = element.first("pgMar")
        val type = element.first("type")?.w("val")?.trim()?.lowercase()
        val columns = element.first("cols")
        return WordSection(
            pageWidth = size?.wPoints("w") ?: 612.0,
            pageHeight = size?.wPoints("h") ?: 792.0,
            marginLeft = margin?.wPoints("left") ?: 72.0,
            marginRight = margin?.wPoints("right") ?: 72.0,
            marginTop = margin?.wPoints("top") ?: 72.0,
            marginBottom = margin?.wPoints("bottom") ?: 72.0,
            startsNewPage = type == null || type != "continuous",
            columns = columns?.w("num")?.trim()?.toIntOrNull() ?: 1,
            columnSpace = columns?.wPoints("space") ?: 36.0
        )
    }

    // -------------------------------------------------------------- paragraphs

    private fun paragraph(
        element: Element,
        sheet: StyleSheet,
        counters: ListCounters,
        notes: NoteBag,
        images: ImageCache
    ): WordParagraph {
        val properties = element.first("pPr")
        val styleId = properties?.first("pStyle")?.w("val")?.trim()
        val style = sheet.paragraph(styleId)
        if (styleId != null && style == null && styleId != "Normal") {
            notes.add("a paragraph style is not defined and was ignored: $styleId")
        }
        val format = properties.paraFormat().over(style?.format ?: sheet.defaultParagraph)
        val runBase = style?.runFormat ?: sheet.docDefaults
        val inline = properties?.first("sectPr")
        val section = inline?.let { sectionProperties(it) }

        val runs = ArrayList<WordRun>()
        var inInstruction = false
        var sawField = false
        var sawHyperlink = false

        fun walk(runContainer: Element) {
            for (child in runContainer.childElements()) {
                when (child.localName) {
                    "r" -> {
                        if (inInstruction) {
                            // The field's instruction text is not content; the
                            // cached result between `separate` and `end` is.
                            if (child.first("instrText") != null) continue
                        }
                        val parts = parseRun(child, format, runBase, sheet, notes, images)
                        if (inInstruction) {
                            for (part in parts) {
                                if (part is WordBreakRun) runs += part
                            }
                        } else {
                            runs += parts
                        }
                    }
                    "hyperlink" -> {
                        sawHyperlink = true
                        walk(child)
                    }
                    "ins" -> walk(child)
                    "del" -> notes.add("tracked deletions are dropped")
                    "fldSimple" -> {
                        sawField = true
                        walk(child)
                    }
                    "fldChar" -> {
                        sawField = true
                        when (child.w("fldCharType")?.trim()?.lowercase()) {
                            "begin" -> inInstruction = true
                            "separate" -> inInstruction = false
                            "end" -> inInstruction = false
                        }
                    }
                    "instrText" -> sawField = true
                }
            }
        }
        walk(element)
        if (sawField) {
            notes.add("fields are shown as the text Word last saved for them")
        }
        if (sawHyperlink) {
            notes.add("hyperlinks are kept as plain text")
        }
        if (runs.any { it is WordTextRun && it.format.smallCaps == true }) {
            notes.add("small caps are drawn as lower case")
        }
        if (runs.any { it is WordImageRun && it.anchor?.behindText == true }) {
            notes.add("a floating picture is drawn behind the text without reflowing it")
        } else if (runs.any { it is WordImageRun && it.anchor != null }) {
            notes.add("text does not wrap around floating pictures")
        }

        var indentLeft = format.indentLeft ?: 0.0
        var indentFirst = format.indentFirst ?: 0.0
        var marker: WordMarker? = null
        val reference = format.numbering
        if (reference != null) {
            val level = counters.level(reference.numId, reference.level)
            val text = counters.next(reference.numId, reference.level)
            if (level == null || text == null) {
                notes.add("a list paragraph has no numbering definition (numId ${reference.numId})")
            } else {
                if (format.indentLeft == null) indentLeft = level.indentLeft ?: 36.0
                if (format.indentFirst == null) indentFirst = -(level.hanging ?: 18.0)
                marker = WordMarker(text, indentLeft, -indentFirst, level.align, level.family)
            }
        }

        return WordParagraph(
            runs = runs,
            baseFormat = format.runDefaults ?: runBase,
            alignment = format.alignment ?: WordAlign.LEFT,
            indentLeft = indentLeft,
            indentRight = format.indentRight ?: 0.0,
            indentFirst = indentFirst,
            spaceBefore = format.spaceBefore ?: 0.0,
            spaceAfter = format.spaceAfter ?: 0.0,
            lineHeight = format.lineHeight,
            marker = marker,
            headingLevel = sheet.headingLevel(style),
            pageBreakBefore = format.pageBreakBefore ?: false,
            keepWithNext = format.keepWithNext ?: false,
            sectionEnd = section
        )
    }

    // -------------------------------------------------------------------- runs

    private fun parseRun(
        element: Element,
        paragraphFormat: WordParaFormat,
        paragraphRunBase: WordRunFormat,
        sheet: StyleSheet,
        notes: NoteBag,
        images: ImageCache
    ): List<WordRun> {
        val properties = element.first("rPr")
        val characterStyle = sheet.character(properties?.first("rStyle")?.w("val")?.trim())
        val format = properties.runFormat()
            .over(characterStyle)
            .over(paragraphFormat.runDefaults)
            .over(paragraphRunBase)

        val runs = ArrayList<WordRun>()
        for (child in element.childElements()) {
            when (child.localName) {
                "t" -> {
                    val text = child.textContent ?: ""
                    if (text.isNotEmpty()) runs += WordTextRun(text, format)
                }
                "tab" -> runs += WordTabRun(format)
                "br", "cr" -> {
                    val type = child.w("type")?.trim()?.lowercase()
                    if (type == "column") notes.add("a column break is treated as a page break")
                    runs += WordBreakRun(page = type == "page" || type == "column", format = format)
                }
                "noBreakHyphen" -> runs += WordTextRun("-", format)
                "softHyphen" -> {}
                "drawing" -> images.run(child, format, runs)
                "pict" -> notes.add("VML pictures are not converted")
                "sym" -> {
                    val family = child.w("font")?.trim()
                    val code = child.w("char")?.trim()?.toIntOrNull(16)
                    if (code != null) {
                        val text = ListCounters.bulletText(code.toChar().toString(), family)
                        runs += WordTextRun(text, if (family != null) format.copy(family = family) else format)
                    } else {
                        notes.add("a symbol run has no character and was dropped")
                    }
                }
                "footnoteReference", "endnoteReference" -> {
                    notes.add("footnote and endnote references are dropped")
                }
                "commentReference", "annotationRef" -> {}
            }
        }
        return runs
    }

    // ------------------------------------------------------------------ tables

    private fun table(
        element: Element,
        sheet: StyleSheet,
        counters: ListCounters,
        notes: NoteBag,
        images: ImageCache
    ): WordTable {
        val properties = element.first("tblPr")
        val styleId = properties?.first("tblStyle")?.w("val")?.trim()
        val borders = properties?.first("tblBorders")?.borderSetting()
            ?: sheet.tableBorders(styleId)
            ?: false
        val indent = properties?.first("tblInd")?.wPoints("w") ?: 0.0
        val cellMargin = properties?.first("tblCellMar")
            ?.first("left")
            ?.wPoints("w")
            ?: DEFAULT_CELL_MARGIN

        val grid = element.first("tblGrid")?.kids("gridCol").orEmpty().map { it.wPoints("w") ?: 0.0 }
        if (grid.isEmpty()) notes.add("a table has no column grid; equal columns were assumed")

        val rows = ArrayList<WordTableRow>()
        for (row in element.kids("tr")) {
            val height = row.first("trPr")?.first("trHeight")?.wPoints("val")
            val cells = ArrayList<WordTableCell>()
            var columnIndex = 0
            for (cell in row.kids("tc")) {
                val cellProperties = cell.first("tcPr")
                val span = (cellProperties?.first("gridSpan")?.w("val")?.trim()?.toIntOrNull() ?: 1)
                    .coerceAtLeast(1)
                val verticalMerge = cellProperties?.first("vMerge")
                if (verticalMerge != null) {
                    notes.add("vertically merged cells are drawn as separate cells")
                }
                val width = if (grid.isEmpty()) {
                    0.0
                } else {
                    (columnIndex until (columnIndex + span)).sumOf { grid.getOrElse(it) { 0.0 } }
                }
                val shading = cellProperties?.first("shd")?.let { shd ->
                    val fill = shd.w("fill")?.trim()
                    if (fill == null || fill.equals("auto", ignoreCase = true)) null else WordColor.ofHex(fill)
                }
                val blocks = ArrayList<WordBlock>()
                for (child in cell.childElements()) {
                    when (child.localName) {
                        "p" -> blocks += WordParagraphBlock(paragraph(child, sheet, counters, notes, images))
                        "tbl" -> blocks += WordTableBlock(table(child, sheet, counters, notes, images))
                    }
                }
                cells += WordTableCell(
                    blocks = blocks,
                    width = width,
                    shading = shading,
                    spanAcross = span,
                    mergedDown = verticalMerge != null &&
                        !verticalMerge.w("val").equals("restart", ignoreCase = true)
                )
                columnIndex += span
            }
            rows += WordTableRow(cells, height)
        }

        return WordTable(
            rows = rows,
            columnWidths = grid,
            cellMargin = cellMargin,
            indentLeft = indent,
            borders = borders
        )
    }

    // ------------------------------------------------------------------ images

    /** Loads and decodes picture parts once each, whatever the part's format. */
    private class ImageCache(
        private val package_: PackageCheck.Package,
        private val rels: Document?,
        private val notes: NoteBag
    ) {
        private val loaded = HashMap<String, WordImage?>()

        /** Adds a [WordImageRun] for the first `a:blip` of [drawing]. */
        fun run(drawing: Element, format: WordRunFormat, into: MutableList<WordRun>) {
            val drawingElement = drawing.childElements().firstOrNull {
                it.localName == "inline" || it.localName == "anchor"
            }
            if (drawingElement == null) {
                notes.add("a drawing without a picture was skipped")
                return
            }
            val blips = drawingElement.descendants("blip")
            if (blips.size > 1) {
                notes.add("only the first picture of a grouped drawing is converted")
            }
            val targets = relationshipTargets(blips.firstOrNull()?.r("embed"))
            if (targets == null) {
                notes.add("a picture has no relationship and was skipped")
                return
            }
            val image = load(targets) ?: return
            val extent = drawingElement.descendants("extent").firstOrNull()
            val cx = extent?.getAttribute("cx")?.toDoubleOrNull() ?: 0.0
            val cy = extent?.getAttribute("cy")?.toDoubleOrNull() ?: 0.0
            val width = if (cx > 0) cx / EMU_PER_POINT else image.pixelWidth * 0.75
            val height = if (cy > 0) cy / EMU_PER_POINT else image.pixelHeight * 0.75

            var anchor: WordAnchor? = null
            if (drawingElement.localName == "anchor") {
                val behind = drawingElement.w("behindDoc")?.trim() == "1" ||
                    drawingElement.w("behindDoc")?.trim()?.lowercase() == "true"
                val horizontal = drawingElement.first("positionH")
                val vertical = drawingElement.first("positionV")
                anchor = WordAnchor(
                    offsetX = offsetOf(horizontal),
                    offsetY = offsetOf(vertical),
                    alignX = WordAlign.of(horizontal?.first("align")?.textContent?.trim()),
                    alignY = vertical?.first("align")?.textContent?.trim()?.lowercase(),
                    fromPageX = horizontal?.w("relativeFrom")?.trim() == "page",
                    fromPageY = vertical?.w("relativeFrom")?.trim() == "page",
                    behindText = behind
                )
            }
            into += WordImageRun(image, width, height, anchor, format)
        }

        private fun offsetOf(position: Element?): Double? =
            position?.first("posOffset")?.textContent?.trim()?.toDoubleOrNull()?.div(EMU_PER_POINT)

        private fun relationshipTargets(id: String?): String? {
            id ?: return null
            val relsDocument = rels ?: return null
            for (relationship in relsDocument.documentElement.childElements()) {
                if (relationship.localName != "Relationship") continue
                if (relationship.getAttribute("Id") != id) continue
                val mode = relationship.getAttribute("TargetMode")
                if (mode == "External") {
                    notes.add("an externally linked picture is not converted")
                    return null
                }
                return PackageCheck.resolveTarget("word", relationship.getAttribute("Target"))
            }
            return null
        }

        private fun load(partName: String): WordImage? {
            if (loaded.containsKey(partName)) return loaded[partName]
            val bytes = package_.parts[partName]
            val image = if (bytes == null) {
                notes.add("a picture part is missing from the package: $partName")
                null
            } else {
                decode(partName, bytes)
            }
            loaded[partName] = image
            return image
        }

        private fun decode(partName: String, bytes: ByteArray): WordImage? {
            if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
                val info = PdfImages.jpegInfo(bytes)
                if (info == null) {
                    notes.add("a JPEG picture could not be read and was skipped")
                    return null
                }
                return WordImage(partName, "JPEG", info[0], info[1], jpeg = bytes)
            }
            if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) {
                return when (val result = PngReader.read(bytes)) {
                    is PngResult.Ok -> WordImage(
                        partName, "PNG", result.image.width, result.image.height, raster = result.image
                    )
                    is PngResult.Failed -> {
                        notes.add("a PNG picture was skipped (${result.reason})")
                        null
                    }
                }
            }
            val extension = partName.substringAfterLast('.', "").uppercase()
            notes.add("a ${if (extension.isEmpty()) "picture" else extension} picture is not converted")
            return null
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun mainDocumentName(package_: PackageCheck.Package): String? {
        val rels = package_.documents["_rels/.rels"] ?: return null
        for (relationship in rels.documentElement.childElements()) {
            if (relationship.localName != "Relationship") continue
            val type = relationship.getAttribute("Type")
            if (!type.endsWith(REL_OFFICE_DOCUMENT)) continue
            val target = relationship.getAttribute("Target")
            if (target.isEmpty()) continue
            return PackageCheck.resolveTarget("", target)
        }
        return null
    }

    private fun relsName(partName: String): String {
        val directory = partName.substringBeforeLast('/', "")
        val file = partName.substringAfterLast('/')
        return if (directory.isEmpty()) "_rels/$file.rels" else "$directory/_rels/$file.rels"
    }
}
