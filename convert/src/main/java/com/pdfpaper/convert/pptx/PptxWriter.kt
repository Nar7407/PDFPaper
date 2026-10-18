package com.pdfpaper.convert.pptx

import com.pdfpaper.convert.ooxml.OpcPackage
import com.pdfpaper.convert.ooxml.XmlBuilder

/**
 * Writes a [PptxDeck] as a PresentationML package (ISO 29500-1 §13).
 *
 * Only the parts PowerPoint and LibreOffice actually require are emitted, and
 * every relationship target is a part that exists — the two things that make
 * the difference between "opens" and "repair". The package is deterministic:
 * the same deck always yields the same bytes.
 */
object PptxWriter {

    private const val SLIDE_MASTER_ID = 2147483648L
    private const val SLIDE_ID_BASE = 256
    private const val NOTES_W = 6858000L
    private const val NOTES_H = 9144000L

    fun write(deck: PptxDeck): ByteArray {
        val pkg = OpcPackage()
        val slides = deck.slides
        val slideCount = slides.size

        pkg.addXml(OpcPackage.CONTENT_TYPES, contentTypes(deck, slideCount))
        pkg.addXml("_rels/.rels", PptxTemplates.ROOT_RELS)
        pkg.addXml("docProps/core.xml", PptxTemplates.coreProps(deck.title))
        pkg.addXml("docProps/app.xml", PptxTemplates.appProps(slideCount))
        pkg.addXml("ppt/presentation.xml", presentationXml(deck))
        pkg.addXml("ppt/_rels/presentation.xml.rels", presentationRels(slideCount))
        pkg.addXml("ppt/presProps.xml", PptxTemplates.PRES_PROPS)
        pkg.addXml("ppt/theme/theme1.xml", PptxTemplates.THEME)
        pkg.addXml("ppt/slideMasters/slideMaster1.xml", PptxTemplates.SLIDE_MASTER)
        pkg.addXml("ppt/slideMasters/_rels/slideMaster1.xml.rels", PptxTemplates.SLIDE_MASTER_RELS)
        pkg.addXml("ppt/slideLayouts/slideLayout1.xml", PptxTemplates.SLIDE_LAYOUT)
        pkg.addXml("ppt/slideLayouts/_rels/slideLayout1.xml.rels", PptxTemplates.SLIDE_LAYOUT_RELS)

        for ((index, slide) in slides.withIndex()) {
            val number = index + 1
            val rels = slideRels(slide)
            pkg.addXml("ppt/slides/slide$number.xml", slideXml(slide, rels))
            if (rels.isNotEmpty()) {
                pkg.addXml("ppt/slides/_rels/slide$number.xml.rels", slideRelsXml(rels))
            }
        }
        for ((name, bytes) in deck.media) pkg.add(name, bytes)
        return pkg.bytes()
    }

    // --------------------------------------------------------- package parts

    private fun contentTypes(deck: PptxDeck, slideCount: Int): String {
        val xml = XmlBuilder().declaration()
        xml.open(
            "Types",
            "xmlns" to PptxTemplates.NS_CT
        )
        xml.empty("Default", "Extension" to "rels", "ContentType" to PptxTemplates.CT_RELS)
        xml.empty("Default", "Extension" to "xml", "ContentType" to "application/xml")
        val extensions = deck.media.keys.map { it.substringAfterLast('.', "").lowercase() }
            .filter { it.isNotEmpty() }.toSortedSet()
        for (extension in extensions) {
            val type = when (extension) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "tif", "tiff" -> "image/tiff"
                else -> "application/octet-stream"
            }
            xml.empty("Default", "Extension" to extension, "ContentType" to type)
        }
        xml.empty("Override", "PartName" to "/ppt/presentation.xml", "ContentType" to PptxTemplates.CT_PRESENTATION)
        xml.empty("Override", "PartName" to "/ppt/slideMasters/slideMaster1.xml", "ContentType" to PptxTemplates.CT_SLIDE_MASTER)
        xml.empty("Override", "PartName" to "/ppt/slideLayouts/slideLayout1.xml", "ContentType" to PptxTemplates.CT_SLIDE_LAYOUT)
        for (i in 1..slideCount) {
            xml.empty("Override", "PartName" to "/ppt/slides/slide$i.xml", "ContentType" to PptxTemplates.CT_SLIDE)
        }
        xml.empty("Override", "PartName" to "/ppt/theme/theme1.xml", "ContentType" to PptxTemplates.CT_THEME)
        xml.empty("Override", "PartName" to "/ppt/presProps.xml", "ContentType" to PptxTemplates.CT_PRES_PROPS)
        xml.empty("Override", "PartName" to "/docProps/core.xml", "ContentType" to PptxTemplates.CT_CORE_PROPS)
        xml.empty("Override", "PartName" to "/docProps/app.xml", "ContentType" to PptxTemplates.CT_EXTENDED_PROPS)
        xml.close("Types")
        return xml.build()
    }

    private fun presentationXml(deck: PptxDeck): String {
        val xml = XmlBuilder().declaration()
        xml.open(
            "p:presentation",
            "xmlns:a" to PptxTemplates.NS_A,
            "xmlns:r" to PptxTemplates.NS_R,
            "xmlns:p" to PptxTemplates.NS_P,
            "saveSubsetFonts" to "1"
        )
        xml.open("p:sldMasterIdLst")
        xml.empty("p:sldMasterId", "id" to SLIDE_MASTER_ID.toString(), "r:id" to "rId1")
        xml.close("p:sldMasterIdLst")
        xml.open("p:sldIdLst")
        for (i in deck.slides.indices) {
            xml.empty("p:sldId", "id" to (SLIDE_ID_BASE + i).toString(), "r:id" to "rId${i + 2}")
        }
        xml.close("p:sldIdLst")
        xml.empty("p:sldSz", "cx" to deck.slideWidthEmu.toString(), "cy" to deck.slideHeightEmu.toString())
        xml.empty("p:notesSz", "cx" to NOTES_W.toString(), "cy" to NOTES_H.toString())
        xml.close("p:presentation")
        return xml.build()
    }

    private fun presentationRels(slideCount: Int): String {
        val xml = XmlBuilder().declaration()
        xml.open("Relationships", "xmlns" to PptxTemplates.NS_REL)
        xml.empty(
            "Relationship", "Id" to "rId1",
            "Type" to PptxTemplates.REL_SLIDE_MASTER, "Target" to "slideMasters/slideMaster1.xml"
        )
        for (i in 1..slideCount) {
            xml.empty(
                "Relationship", "Id" to "rId${i + 1}",
                "Type" to PptxTemplates.REL_SLIDE, "Target" to "slides/slide$i.xml"
            )
        }
        xml.empty(
            "Relationship", "Id" to "rId${slideCount + 2}",
            "Type" to PptxTemplates.REL_PRES_PROPS, "Target" to "presProps.xml"
        )
        xml.close("Relationships")
        return xml.build()
    }

    private class SlideRel(val id: String, val partName: String)

    /** Relationship ids for the media a slide references, in shape order. */
    private fun slideRels(slide: Slide): List<SlideRel> {
        val rels = ArrayList<SlideRel>()
        for (shape in slide.shapes) {
            if (shape is PictureShape && rels.none { it.partName == shape.partName }) {
                rels.add(SlideRel("rId${rels.size + 1}", shape.partName))
            }
        }
        return rels
    }

    private fun slideRelsXml(rels: List<SlideRel>): String {
        val xml = XmlBuilder().declaration()
        xml.open("Relationships", "xmlns" to PptxTemplates.NS_REL)
        for (rel in rels) {
            // Media always lives in ppt/media, one level above the slides.
            val target = "../" + rel.partName.removePrefix("ppt/")
            xml.empty(
                "Relationship", "Id" to rel.id,
                "Type" to PptxTemplates.REL_IMAGE, "Target" to target
            )
        }
        xml.close("Relationships")
        return xml.build()
    }

    private fun slideXml(slide: Slide, rels: List<SlideRel>): String {
        val xml = XmlBuilder().declaration()
        xml.open(
            "p:sld",
            "xmlns:a" to PptxTemplates.NS_A,
            "xmlns:r" to PptxTemplates.NS_R,
            "xmlns:p" to PptxTemplates.NS_P
        )
        xml.open("p:cSld")
        xml.open("p:spTree")
        xml.open("p:nvGrpSpPr")
        xml.empty("p:cNvPr", "id" to "1", "name" to "")
        xml.empty("p:cNvGrpSpPr")
        xml.empty("p:nvPr")
        xml.close("p:nvGrpSpPr")
        xml.open("p:grpSpPr")
        xml.open("a:xfrm")
        xml.empty("a:off", "x" to "0", "y" to "0")
        xml.empty("a:ext", "cx" to "0", "cy" to "0")
        xml.empty("a:chOff", "x" to "0", "y" to "0")
        xml.empty("a:chExt", "cx" to "0", "cy" to "0")
        xml.close("a:xfrm")
        xml.close("p:grpSpPr")
        for (shape in slide.shapes) {
            when (shape) {
                is TextBoxShape -> textBox(xml, shape)
                is PictureShape -> picture(xml, shape, rels)
            }
        }
        xml.close("p:spTree")
        xml.close("p:cSld")
        xml.open("p:clrMapOvr")
        xml.empty("a:masterClrMapping")
        xml.close("p:clrMapOvr")
        xml.close("p:sld")
        return xml.build()
    }

    private fun textBox(xml: XmlBuilder, shape: TextBoxShape) {
        xml.open("p:sp")
        xml.open("p:nvSpPr")
        xml.empty("p:cNvPr", "id" to shape.id.toString(), "name" to "TextBox ${shape.id}")
        xml.empty("p:cNvSpPr", "txBox" to "1")
        xml.empty("p:nvPr")
        xml.close("p:nvSpPr")
        xml.open("p:spPr")
        if (shape.rotation != 0) {
            xml.open("a:xfrm", "rot" to shape.rotation.toString())
        } else {
            xml.open("a:xfrm")
        }
        xml.empty("a:off", "x" to shape.xEmu.toString(), "y" to shape.yEmu.toString())
        xml.empty("a:ext", "cx" to shape.wEmu.toString(), "cy" to shape.hEmu.toString())
        xml.close("a:xfrm")
        xml.open("a:prstGeom", "prst" to "rect")
        xml.empty("a:avLst")
        xml.close("a:prstGeom")
        xml.empty("a:noFill")
        xml.close("p:spPr")
        xml.open("p:txBody")
        xml.open(
            "a:bodyPr", "wrap" to "none",
            "lIns" to "0", "tIns" to "0", "rIns" to "0", "bIns" to "0"
        )
        xml.empty("a:noAutofit")
        xml.close("a:bodyPr")
        xml.empty("a:lstStyle")
        xml.open("a:p")
        xml.open("a:r")
        val rPr = ArrayList<Pair<String, String>>()
        rPr.add("lang" to "en-US")
        rPr.add("sz" to shape.sizeHundredths.toString())
        if (shape.bold) rPr.add("b" to "1")
        if (shape.italic) rPr.add("i" to "1")
        rPr.add("dirty" to "0")
        xml.open("a:rPr", *rPr.toTypedArray())
        val hex = "%06X".format(shape.rgb and 0xFFFFFF)
        if (shape.visible) {
            xml.open("a:solidFill")
            xml.empty("a:srgbClr", "val" to hex)
            xml.close("a:solidFill")
        } else {
            // Fully transparent fill: the run stays for selection/search but
            // paints nothing over the fidelity background.
            xml.open("a:solidFill")
            xml.open("a:srgbClr", "val" to hex)
            xml.empty("a:alpha", "val" to "0")
            xml.close("a:srgbClr")
            xml.close("a:solidFill")
        }
        xml.empty("a:latin", "typeface" to shape.family)
        xml.close("a:rPr")
        xml.leafText("a:t", shape.text, "xml:space" to "preserve")
        xml.close("a:r")
        xml.close("a:p")
        xml.close("p:txBody")
        xml.close("p:sp")
    }

    private fun picture(xml: XmlBuilder, shape: PictureShape, rels: List<SlideRel>) {
        val relId = rels.firstOrNull { it.partName == shape.partName }?.id ?: "rId1"
        xml.open("p:pic")
        xml.open("p:nvPicPr")
        xml.empty("p:cNvPr", "id" to shape.id.toString(), "name" to "Picture ${shape.id}")
        xml.open("p:cNvPicPr")
        xml.empty("a:picLocks", "noChangeAspect" to "1")
        xml.close("p:cNvPicPr")
        xml.empty("p:nvPr")
        xml.close("p:nvPicPr")
        xml.open("p:blipFill")
        xml.empty("a:blip", "r:embed" to relId)
        xml.open("a:stretch")
        xml.empty("a:fillRect")
        xml.close("a:stretch")
        xml.close("p:blipFill")
        xml.open("p:spPr")
        val xfrm = ArrayList<Pair<String, String>>()
        if (shape.flipH) xfrm.add("flipH" to "1")
        if (shape.flipV) xfrm.add("flipV" to "1")
        xml.open("a:xfrm", *xfrm.toTypedArray())
        xml.empty("a:off", "x" to shape.xEmu.toString(), "y" to shape.yEmu.toString())
        xml.empty("a:ext", "cx" to shape.wEmu.toString(), "cy" to shape.hEmu.toString())
        xml.close("a:xfrm")
        xml.open("a:prstGeom", "prst" to "rect")
        xml.empty("a:avLst")
        xml.close("a:prstGeom")
        xml.close("p:spPr")
        xml.close("p:pic")
    }
}
