package com.pdfpaper.convert.docx

import com.pdfpaper.convert.ooxml.OpcPackage
import com.pdfpaper.convert.ooxml.XmlBuilder

/**
 * Writes a [DocxDocument] as a WordprocessingML package (ISO 29500-1 §17).
 *
 * Only the parts Word and LibreOffice actually require are emitted, every
 * relationship target is a part that exists, and the children of each
 * properties element are written in the order the schema fixes — the three
 * things that decide between "opens" and "repair". The package is
 * deterministic: the same document always yields the same bytes.
 */
object DocxWriter {

    fun write(document: DocxDocument): ByteArray {
        val pkg = OpcPackage()
        pkg.addXml(OpcPackage.CONTENT_TYPES, contentTypes(document))
        pkg.addXml("_rels/.rels", DocxTemplates.ROOT_RELS)
        pkg.addXml("docProps/core.xml", DocxTemplates.coreProps(document.title))
        val text = countText(document)
        pkg.addXml(
            "docProps/app.xml",
            DocxTemplates.appProps(document.pageCount, text.second, text.first, text.third)
        )
        pkg.addXml("word/document.xml", documentXml(document))
        pkg.addXml("word/_rels/document.xml.rels", documentRels(document))
        pkg.addXml("word/styles.xml", DocxTemplates.STYLES)
        pkg.addXml("word/settings.xml", DocxTemplates.SETTINGS)
        pkg.addXml("word/numbering.xml", DocxTemplates.numbering(document.numbering))
        for ((name, bytes) in document.media) pkg.add(name, bytes)
        return pkg.bytes()
    }

    // --------------------------------------------------------- package parts

    private fun contentTypes(document: DocxDocument): String {
        val xml = XmlBuilder().declaration()
        xml.open("Types", "xmlns" to DocxTemplates.NS_CT)
        xml.empty("Default", "Extension" to "rels", "ContentType" to DocxTemplates.CT_RELS)
        xml.empty("Default", "Extension" to "xml", "ContentType" to "application/xml")
        for (extension in mediaExtensions(document)) {
            xml.empty("Default", "Extension" to extension, "ContentType" to imageContentType(extension))
        }
        xml.empty("Override", "PartName" to "/word/document.xml", "ContentType" to DocxTemplates.CT_DOCUMENT)
        xml.empty("Override", "PartName" to "/word/styles.xml", "ContentType" to DocxTemplates.CT_STYLES)
        xml.empty("Override", "PartName" to "/word/settings.xml", "ContentType" to DocxTemplates.CT_SETTINGS)
        xml.empty("Override", "PartName" to "/word/numbering.xml", "ContentType" to DocxTemplates.CT_NUMBERING)
        xml.empty("Override", "PartName" to "/docProps/core.xml", "ContentType" to DocxTemplates.CT_CORE_PROPS)
        xml.empty("Override", "PartName" to "/docProps/app.xml", "ContentType" to DocxTemplates.CT_EXTENDED_PROPS)
        xml.close("Types")
        return xml.build()
    }

    private fun mediaExtensions(document: DocxDocument): List<String> =
        document.media.keys.map { it.substringAfterLast('.', "").lowercase() }
            .filter { it.isNotEmpty() }.toSortedSet().toList()

    private fun imageContentType(extension: String): String = when (extension) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "tif", "tiff" -> "image/tiff"
        else -> "application/octet-stream"
    }

    /** Relationship ids: rId1..rId3 for the fixed parts, then one per image. */
    private class ImageRel(val id: String, val partName: String)

    private fun imageRels(document: DocxDocument): List<ImageRel> {
        val rels = ArrayList<ImageRel>()
        for (block in document.blocks) {
            val blockImages = (block as? DocImageBlock)?.images ?: continue
            for (image in blockImages) {
                if (rels.none { it.partName == image.partName }) {
                    rels.add(ImageRel("rId${rels.size + 4}", image.partName))
                }
            }
        }
        return rels
    }

    private fun documentRels(document: DocxDocument): String {
        val xml = XmlBuilder().declaration()
        xml.open("Relationships", "xmlns" to DocxTemplates.NS_REL)
        xml.empty("Relationship", "Id" to "rId1", "Type" to DocxTemplates.REL_STYLES, "Target" to "styles.xml")
        xml.empty("Relationship", "Id" to "rId2", "Type" to DocxTemplates.REL_SETTINGS, "Target" to "settings.xml")
        xml.empty("Relationship", "Id" to "rId3", "Type" to DocxTemplates.REL_NUMBERING, "Target" to "numbering.xml")
        for (rel in imageRels(document)) {
            // Media always lives in word/media, one level below the document.
            xml.empty(
                "Relationship", "Id" to rel.id,
                "Type" to DocxTemplates.REL_IMAGE, "Target" to rel.partName.removePrefix("word/")
            )
        }
        xml.close("Relationships")
        return xml.build()
    }

    // ------------------------------------------------------------ body parts

    private fun documentXml(document: DocxDocument): String {
        val xml = XmlBuilder().declaration()
        xml.open(
            "w:document",
            "xmlns:w" to DocxTemplates.NS_W,
            "xmlns:r" to DocxTemplates.NS_R,
            "xmlns:wp" to DocxTemplates.NS_WP,
            "xmlns:a" to DocxTemplates.NS_A,
            "xmlns:pic" to DocxTemplates.NS_PIC
        )
        xml.open("w:body")
        val rels = imageRels(document)
        val lastSection = document.blocks.indexOfLast {
            it is DocParagraphBlock && it.paragraph.sectionEnd != null
        }
        var pictureId = 0
        for ((index, block) in document.blocks.withIndex()) {
            when (block) {
                is DocParagraphBlock -> paragraph(xml, block.paragraph, bodySection = index == lastSection)
                is DocTableBlock -> table(xml, block)
                is DocImageBlock -> {
                    val ids = block.images.map { image ->
                        val rel = rels.firstOrNull { it.partName == image.partName }?.id ?: "rId1"
                        image to rel
                    }
                    // wp:docPr ids are unique for the whole document, so a
                    // page with several pictures takes a range of them.
                    picture(xml, ids, pictureId + 1)
                    pictureId += maxOf(1, block.images.size)
                }
            }
        }
        if (document.blocks.lastOrNull() !is DocParagraphBlock) {
            // A table or picture may not be the last thing before sectPr in a
            // document Word is happy to open unchanged.
            emptyParagraph(xml)
        }
        val finalSection = (document.blocks.getOrNull(lastSection) as? DocParagraphBlock)
            ?.paragraph?.sectionEnd ?: document.finalSection
        sectionProps(xml, finalSection)
        xml.close("w:body")
        xml.close("w:document")
        return xml.build()
    }

    private fun emptyParagraph(xml: XmlBuilder) {
        xml.open("w:p")
        xml.close("w:p")
    }

    private fun paragraph(xml: XmlBuilder, p: DocParagraph, bodySection: Boolean) {
        xml.open("w:p")
        val props: List<() -> Unit> = buildList {
            p.styleId?.let { id -> add { xml.empty("w:pStyle", "w:val" to id) } }
            if (p.keepWithNext) add { xml.empty("w:keepNext") }
            p.frame?.let { frame ->
                add {
                    xml.empty(
                        "w:framePr",
                        "w:w" to frame.width.toString(), "w:h" to frame.height.toString(),
                        "w:hRule" to "atLeast",
                        "w:x" to frame.x.toString(), "w:y" to frame.y.toString(),
                        "w:hAnchor" to "page", "w:vAnchor" to "page",
                        "w:wrap" to "none"
                    )
                }
            }
            p.list?.let { ref ->
                add {
                    xml.open("w:numPr")
                    xml.empty("w:ilvl", "w:val" to ref.level.toString())
                    xml.empty("w:numId", "w:val" to ref.numId.toString())
                    xml.close("w:numPr")
                }
            }
            if (p.spaceBefore != 0 || p.spaceAfter != 0 || p.lineHeight != null || p.spacingExplicit) {
                add {
                    val attrs = ArrayList<Pair<String, String>>()
                    if (p.spaceBefore != 0 || p.spacingExplicit) attrs.add("w:before" to p.spaceBefore.toString())
                    if (p.spaceAfter != 0 || p.spacingExplicit) attrs.add("w:after" to p.spaceAfter.toString())
                    p.lineHeight?.let {
                        attrs.add("w:line" to it.toString())
                        attrs.add("w:lineRule" to "exact")
                    }
                    xml.empty("w:spacing", *attrs.toTypedArray())
                }
            }
            if (p.indentLeft != 0 || p.indentFirstLine != 0) {
                add {
                    val attrs = ArrayList<Pair<String, String>>()
                    if (p.indentLeft != 0) attrs.add("w:left" to p.indentLeft.toString())
                    if (p.indentFirstLine > 0) attrs.add("w:firstLine" to p.indentFirstLine.toString())
                    if (p.indentFirstLine < 0) attrs.add("w:hanging" to (-p.indentFirstLine).toString())
                    xml.empty("w:ind", *attrs.toTypedArray())
                }
            }
            p.alignment?.let { align -> add { xml.empty("w:jc", "w:val" to align.value) } }
            p.sectionEnd?.let { section -> if (!bodySection) add { sectionProps(xml, section) } }
        }
        if (props.isNotEmpty()) {
            xml.open("w:pPr")
            for (write in props) write()
            xml.close("w:pPr")
        }
        for (run in p.runs) run(xml, run)
        xml.close("w:p")
    }

    /** One `w:r`; tabs and line breaks become `w:tab` / `w:br`. */
    private fun run(xml: XmlBuilder, run: DocRun) {
        if (run.text.isEmpty()) return
        xml.open("w:r")
        val hasProps = run.family != null || run.sizeHalfPoints != null ||
            run.bold || run.italic || run.underline || run.colorHex != null
        if (hasProps) {
            xml.open("w:rPr")
            run.family?.let {
                xml.empty("w:rFonts", "w:ascii" to it, "w:hAnsi" to it, "w:cs" to it)
            }
            if (run.bold) xml.empty("w:b")
            if (run.italic) xml.empty("w:i")
            run.colorHex?.let { xml.empty("w:color", "w:val" to it) }
            run.sizeHalfPoints?.let {
                xml.empty("w:sz", "w:val" to it.toString())
                xml.empty("w:szCs", "w:val" to it.toString())
            }
            if (run.underline) xml.empty("w:u", "w:val" to "single")
            xml.close("w:rPr")
        }
        var index = 0
        val text = StringBuilder()
        fun flush() {
            if (text.isEmpty()) return
            xml.leafText("w:t", text.toString(), "xml:space" to "preserve")
            text.setLength(0)
        }
        while (index < run.text.length) {
            val ch = run.text[index]
            when {
                ch == '\t' -> {
                    flush()
                    xml.empty("w:tab")
                }
                ch == '\n' || ch == '\r' || ch == '\u000B' -> {
                    flush()
                    xml.empty("w:br")
                }
                ch == '\u00AD' -> {}
                else -> text.append(ch)
            }
            index++
        }
        flush()
        xml.close("w:r")
    }

    private fun table(xml: XmlBuilder, block: DocTableBlock) {
        xml.open("w:tbl")
        xml.open("w:tblPr")
        xml.empty("w:tblW", "w:w" to "0", "w:type" to "auto")
        xml.empty("w:tblLayout", "w:type" to "fixed")
        xml.empty("w:tblLook", "w:val" to "04A0", "w:firstRow" to "1", "w:lastRow" to "0",
            "w:firstColumn" to "1", "w:lastColumn" to "0", "w:noHBand" to "0", "w:noVBand" to "1")
        xml.close("w:tblPr")
        xml.open("w:tblGrid")
        for (width in block.columnWidths) xml.empty("w:gridCol", "w:w" to width.toString())
        xml.close("w:tblGrid")
        for (row in block.rows) {
            xml.open("w:tr")
            xml.open("w:trPr")
            xml.empty("w:cantSplit")
            xml.close("w:trPr")
            for ((column, cell) in row.withIndex()) {
                xml.open("w:tc")
                xml.open("w:tcPr")
                xml.empty("w:tcW", "w:w" to block.columnWidths[column].toString(), "w:type" to "dxa")
                xml.close("w:tcPr")
                // A cell always holds at least one paragraph.
                paragraph(xml, cell, bodySection = false)
                xml.close("w:tc")
            }
            xml.close("w:tr")
        }
        xml.close("w:tbl")
    }

    private fun sectionProps(xml: XmlBuilder, section: DocSection) {
        xml.open("w:sectPr")
        xml.empty("w:type", "w:val" to "nextPage")
        xml.empty("w:pgSz", "w:w" to section.pageWidth.toString(), "w:h" to section.pageHeight.toString())
        xml.empty(
            "w:pgMar",
            "w:top" to section.marginTop.toString(),
            "w:right" to section.marginRight.toString(),
            "w:bottom" to section.marginBottom.toString(),
            "w:left" to section.marginLeft.toString(),
            "w:header" to "708", "w:footer" to "708", "w:gutter" to "0"
        )
        if (section.columns > 1) {
            xml.empty("w:cols", "w:num" to section.columns.toString(), "w:space" to section.columnSpace.toString())
        } else {
            xml.empty("w:cols", "w:space" to section.columnSpace.toString())
        }
        xml.empty("w:docGrid", "w:linePitch" to "360")
        xml.close("w:sectPr")
    }

    // ---------------------------------------------------------------- images

    /**
     * Pictures in one paragraph: a run per drawing, in order, so a page's
     * background and its figures stay a single line of text flow. [imageIds]
     * are (image, relationship id) pairs and [firstId] the first `wp:docPr` id,
     * which also fixes the stacking order of overlapping floating pictures.
     */
    private fun picture(xml: XmlBuilder, imageIds: List<Pair<DocImage, String>>, firstId: Int) {
        xml.open("w:p")
        for ((index, entry) in imageIds.withIndex()) {
            val image = entry.first
            val relId = entry.second
            val id = firstId + index
            xml.open("w:r")
            xml.open("w:drawing")
            val anchor = image.anchor
            if (anchor == null) {
                xml.open("wp:inline", "distT" to "0", "distB" to "0", "distL" to "0", "distR" to "0")
            } else {
                xml.open(
                    "wp:anchor",
                    "distT" to "0", "distB" to "0", "distL" to "0", "distR" to "0",
                    "simplePos" to "0", "relativeHeight" to (251658240 + id).toString(),
                    "behindDoc" to (if (anchor.behindText) "1" else "0"),
                    "locked" to "0", "layoutInCell" to "1", "allowOverlap" to "1"
                )
                xml.empty("wp:simplePos", "x" to "0", "y" to "0")
                xml.open("wp:positionH", "relativeFrom" to "page")
                xml.leafText("wp:posOffset", emuOfTwips(anchor.x).toString())
                xml.close("wp:positionH")
                xml.open("wp:positionV", "relativeFrom" to "page")
                xml.leafText("wp:posOffset", emuOfTwips(anchor.y).toString())
                xml.close("wp:positionV")
            }
            val widthEmu = emuOfTwips(image.widthTwips)
            val heightEmu = emuOfTwips(image.heightTwips)
            xml.empty("wp:extent", "cx" to widthEmu.toString(), "cy" to heightEmu.toString())
            xml.empty("wp:effectExtent", "l" to "0", "t" to "0", "r" to "0", "b" to "0")
            if (anchor != null) xml.empty("wp:wrapNone")
            xml.empty("wp:docPr", "id" to id.toString(), "name" to "Picture $id")
            xml.open("wp:cNvGraphicFramePr")
            xml.empty("a:graphicFrameLocks", "noChangeAspect" to "1")
            xml.close("wp:cNvGraphicFramePr")
            xml.open("a:graphic")
            xml.open("a:graphicData", "uri" to "http://schemas.openxmlformats.org/drawingml/2006/picture")
            xml.open("pic:pic")
            xml.open("pic:nvPicPr")
            xml.empty("pic:cNvPr", "id" to id.toString(), "name" to "Picture $id")
            xml.empty("pic:cNvPicPr")
            xml.close("pic:nvPicPr")
            xml.open("pic:blipFill")
            xml.empty("a:blip", "r:embed" to relId)
            xml.open("a:stretch")
            xml.empty("a:fillRect")
            xml.close("a:stretch")
            xml.close("pic:blipFill")
            xml.open("pic:spPr")
            val xfrm = ArrayList<Pair<String, String>>()
            if (image.flipH) xfrm.add("flipH" to "1")
            if (image.flipV) xfrm.add("flipV" to "1")
            xml.open("a:xfrm", *xfrm.toTypedArray())
            xml.empty("a:off", "x" to "0", "y" to "0")
            xml.empty("a:ext", "cx" to widthEmu.toString(), "cy" to heightEmu.toString())
            xml.close("a:xfrm")
            xml.open("a:prstGeom", "prst" to "rect")
            xml.empty("a:avLst")
            xml.close("a:prstGeom")
            xml.close("pic:spPr")
            xml.close("pic:pic")
            xml.close("a:graphicData")
            xml.close("a:graphic")
            if (anchor == null) xml.close("wp:inline") else xml.close("wp:anchor")
            xml.close("w:drawing")
            xml.close("w:r")
        }
        xml.close("w:p")
    }

    private fun emuOfTwips(twips: Int): Long = twips.toLong() * EMU_PER_TWIP

    // ----------------------------------------------------------- app.xml stats

    /** (words, paragraphs, characters) over every paragraph in the document. */
    private fun countText(document: DocxDocument): Triple<Int, Int, Int> {
        var paragraphs = 0
        var words = 0
        var characters = 0
        fun count(runs: List<DocRun>) {
            paragraphs++
            for (run in runs) {
                characters += run.text.length
                words += run.text.split(' ', '\t', '\n', '\r', '\u000B').count { it.isNotEmpty() }
            }
        }
        for (block in document.blocks) {
            when (block) {
                is DocParagraphBlock -> count(block.paragraph.runs)
                is DocTableBlock -> for (row in block.rows) for (cell in row) count(cell.runs)
                is DocImageBlock -> {}
            }
        }
        return Triple(words, paragraphs, characters)
    }
}
