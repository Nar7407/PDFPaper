package com.pdfpaper.convert

import com.pdfpaper.convert.ooxml.OpcPackage
import com.pdfpaper.convert.pptx.PptxTemplates
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Independent structural check of a written OOXML package. It re-reads the ZIP
 * and asserts the things that decide whether PowerPoint says "repair":
 *
 *  - `[Content_Types].xml` exists and covers every part,
 *  - every XML part is well-formed,
 *  - every relationship target exists (and every `r:id` used resolves),
 *  - the presentation lists exactly as many slides as there are slide parts.
 *
 * Used by the unit tests and by the corpus acceptance tool; it is deliberately
 * not part of the writer so it cannot be fooled by the writer's own helpers.
 */
object PptxValidator {

    private const val RELS_DIR = "/_rels/"

    fun validate(archive: ByteArray): List<String> {
        val problems = ArrayList<String>()
        val parts = try {
            OpcPackage.read(archive)
        } catch (t: Throwable) {
            return listOf("not a readable ZIP package: ${t.message}")
        }
        if (parts.isEmpty()) return listOf("package is empty")
        val contentTypes = parts[OpcPackage.CONTENT_TYPES] ?: return listOf("missing [Content_Types].xml")

        val documents = HashMap<String, Document>()
        for ((name, bytes) in parts) {
            if (!name.endsWith(".xml") && !name.endsWith(".rels")) continue
            val doc = parse(bytes)
            if (doc == null) problems += "XML part is not well-formed: $name" else documents[name] = doc
        }

        val defaults = HashMap<String, String>()
        val overrides = HashMap<String, String>()
        val ct = parse(contentTypes)
        if (ct == null) {
            problems += "[Content_Types].xml is not well-formed"
        } else {
            for (element in ct.documentElement.childElements()) {
                when (element.localName ?: element.tagName) {
                    "Default" -> defaults[element.getAttribute("Extension").lowercase()] =
                        element.getAttribute("ContentType")
                    "Override" -> overrides[element.getAttribute("PartName")] =
                        element.getAttribute("ContentType")
                }
            }
        }

        for (name in parts.keys) {
            if (name == OpcPackage.CONTENT_TYPES) continue
            val extension = name.substringAfterLast('.', "").lowercase()
            val covered = overrides.containsKey("/$name") || defaults.containsKey(extension)
            if (!covered) problems += "part not covered by [Content_Types].xml: $name"
        }

        // Relationship targets must exist.
        for ((name, doc) in documents) {
            if (!name.endsWith(".rels")) continue
            val baseDir = sourceBaseDir(name)
            for (relationship in doc.documentElement.childElements()) {
                if (relationship.localName != "Relationship") continue
                if (relationship.getAttribute("TargetMode") == "External") continue
                val target = relationship.getAttribute("Target")
                val resolved = resolve(baseDir, target)
                if (!parts.containsKey(resolved)) {
                    problems += "$name references missing part '$target'"
                }
            }
        }

        validatePresentation(parts, documents, problems)
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
        val rels = relationshipIds(documents["ppt/_rels/presentation.xml.rels"])

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
            val slideRels = relationshipIds(documents[relsName])
            val embeds = doc.getElementsByTagNameNS(PptxTemplates.NS_A, "blip")
            for (i in 0 until embeds.length) {
                val id = (embeds.item(i) as Element).getAttributeNS(PptxTemplates.NS_R, "embed")
                if (id !in slideRels) problems += "$name references unknown image relationship '$id'"
            }
            val trees = doc.getElementsByTagNameNS(PptxTemplates.NS_P, "spTree")
            if (trees.length == 0) problems += "$name has no spTree"
        }
    }

    private fun relationshipIds(rels: Document?): Set<String> {
        if (rels == null) return emptySet()
        val ids = HashSet<String>()
        for (element in rels.documentElement.childElements()) {
            if (element.localName == "Relationship") ids += element.getAttribute("Id")
        }
        return ids
    }

    private fun parse(bytes: ByteArray): Document? = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.isExpandEntityReferences = false
        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (t: Throwable) {
        null
    }

    private fun sourceBaseDir(relsName: String): String {
        if (relsName == "_rels/.rels") return ""
        val index = relsName.lastIndexOf(RELS_DIR)
        return if (index >= 0) relsName.substring(0, index) else ""
    }

    private fun resolve(baseDir: String, target: String): String {
        val combined = if (baseDir.isEmpty()) target else "$baseDir/$target"
        val out = ArrayList<String>()
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(segment)
            }
        }
        return out.joinToString("/")
    }

    private fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element) out.add(node)
        }
        return out
    }
}
