package com.pdfpaper.convert

import com.pdfpaper.convert.ooxml.PackageCheck
import com.pdfpaper.convert.pptx.PptxTemplates
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Independent structural check of a written PPTX package. It re-reads the ZIP
 * and asserts the things that decide whether PowerPoint says "repair":
 *
 *  - the generic OPC rules ([PackageCheck]): content types, well-formed XML,
 *    relationship targets,
 *  - the presentation lists exactly as many slides as there are slide parts,
 *  - every `r:embed` in a slide resolves in that slide's relationships.
 *
 * Used by the unit tests and by the corpus acceptance tool; it is deliberately
 * not part of the writer so it cannot be fooled by the writer's own helpers.
 */
object PptxValidator {

    fun validate(archive: ByteArray): List<String> {
        val (packageCheck, problems) = PackageCheck.read(archive)
        if (packageCheck == null) return problems
        validatePresentation(packageCheck.parts, packageCheck.documents, problems)
        return problems
    }

    private fun validatePresentation(
        parts: Map<String, ByteArray>,
        documents: Map<String, Document>,
        problems: MutableList<String>
    ) {
        val presentation = documents["ppt/presentation.xml"]
        if (presentation == null) {
            problems += "missing ppt/presentation.xml"
            return
        }
        val rels = PackageCheck.relationshipIds(documents["ppt/_rels/presentation.xml.rels"])

        val masterIds = presentation.getElementsByTagNameNS(PptxTemplates.NS_P, "sldMasterId")
        for (i in 0 until masterIds.length) {
            val id = (masterIds.item(i) as Element).getAttributeNS(PptxTemplates.NS_R, "id")
            if (id !in rels) problems += "sldMasterId references unknown relationship '$id'"
        }

        val slideParts = parts.keys.count { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
        val slideIds = presentation.getElementsByTagNameNS(PptxTemplates.NS_P, "sldId")
        if (slideIds.length != slideParts) {
            problems += "presentation lists ${slideIds.length} slide(s) but the package has $slideParts slide part(s)"
        }
        for (i in 0 until slideIds.length) {
            val element = slideIds.item(i) as Element
            val id = element.getAttributeNS(PptxTemplates.NS_R, "id")
            if (id !in rels) problems += "sldId references unknown relationship '$id'"
        }

        // Slides: every r:embed must resolve in that slide's rels file.
        for (name in parts.keys) {
            if (!name.startsWith("ppt/slides/slide") || !name.endsWith(".xml")) continue
            val doc = documents[name] ?: continue
            val relsName = "ppt/slides/_rels/${name.substringAfterLast('/')}.rels"
            val slideRels = PackageCheck.relationshipIds(documents[relsName])
            val embeds = doc.getElementsByTagNameNS(PptxTemplates.NS_A, "blip")
            for (i in 0 until embeds.length) {
                val id = (embeds.item(i) as Element).getAttributeNS(PptxTemplates.NS_R, "embed")
                if (id !in slideRels) problems += "$name references unknown image relationship '$id'"
            }
            val trees = doc.getElementsByTagNameNS(PptxTemplates.NS_P, "spTree")
            if (trees.length == 0) problems += "$name has no spTree"
        }
    }
}
