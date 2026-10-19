package com.pdfpaper.convert.ooxml

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The checks every OOXML package has to pass, whatever kind of document it is:
 * the archive reads, `[Content_Types].xml` covers every part, every XML part is
 * well-formed and every relationship target exists.
 *
 * Both the PPTX and the DOCX validators start here and then add the rules of
 * their own part structure. Sharing keeps the two from drifting apart, and the
 * checks stay independent of the writers that produced the package.
 */
internal object PackageCheck {

    private const val RELS_DIR = "/_rels/"

    class Package(val parts: Map<String, ByteArray>, val documents: Map<String, Document>)

    /** Reads and inspects [archive]; null means "too broken to check further". */
    fun read(archive: ByteArray): Pair<Package?, MutableList<String>> {
        val problems = ArrayList<String>()
        val parts = try {
            OpcPackage.read(archive)
        } catch (t: Throwable) {
            return null to mutableListOf("not a readable ZIP package: ${t.message}")
        }
        if (parts.isEmpty()) {
            // An empty ZIP and something that is not a ZIP at all read the
            // same way; the signature is what tells the two apart.
            val looksLikeZip = archive.size >= 4 &&
                archive[0] == 'P'.code.toByte() && archive[1] == 'K'.code.toByte()
            return null to mutableListOf(if (looksLikeZip) "package is empty" else "not a readable ZIP package")
        }
        val contentTypes = parts[OpcPackage.CONTENT_TYPES]
            ?: return null to mutableListOf("missing [Content_Types].xml")

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
            if (!overrides.containsKey("/$name") && !defaults.containsKey(extension)) {
                problems += "part not covered by [Content_Types].xml: $name"
            }
        }

        for ((name, doc) in documents) {
            if (!name.endsWith(".rels")) continue
            val baseDir = sourceBaseDir(name)
            for (relationship in doc.documentElement.childElements()) {
                if (relationship.localName != "Relationship") continue
                if (relationship.getAttribute("TargetMode") == "External") continue
                val target = relationship.getAttribute("Target")
                if (!parts.containsKey(resolveTarget(baseDir, target))) {
                    problems += "$name references missing part '$target'"
                }
            }
        }

        return Package(parts, documents) to problems
    }

    fun parse(bytes: ByteArray): Document? = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.isExpandEntityReferences = false
        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (t: Throwable) {
        null
    }

    /** Ids of the relationships in a `.rels` document, or empty when missing. */
    fun relationshipIds(rels: Document?): Set<String> {
        if (rels == null) return emptySet()
        val ids = HashSet<String>()
        for (element in rels.documentElement.childElements()) {
            if (element.localName == "Relationship") ids += element.getAttribute("Id")
        }
        return ids
    }

    fun sourceBaseDir(relsName: String): String {
        if (relsName == "_rels/.rels") return ""
        val index = relsName.lastIndexOf(RELS_DIR)
        return if (index >= 0) relsName.substring(0, index) else ""
    }

    /** Resolves a relationship target against the part that holds the rels. */
    fun resolveTarget(baseDir: String, target: String): String {
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
}

/** Child elements in document order, without the whitespace text nodes. */
internal fun Element.childElements(): List<Element> {
    val out = ArrayList<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node is Element) out.add(node)
    }
    return out
}
