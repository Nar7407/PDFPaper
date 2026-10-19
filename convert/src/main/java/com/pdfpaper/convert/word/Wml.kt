package com.pdfpaper.convert.word

import org.w3c.dom.Element
import com.pdfpaper.convert.ooxml.childElements

/**
 * Namespaces and DOM conveniences for reading WordprocessingML. The reader is
 * deliberately namespace-aware but tolerant about prefixes: a `w:val` attribute
 * is looked up in the namespace, then by qualified name, then bare, because
 * real files (and hand-written fixtures) show all three.
 */
internal object Wml {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"

    /** Namespace of `w:` attributes; the `r:` namespace is used for `r:embed`. */
    fun attr(element: Element, ns: String, name: String): String? {
        val qualified = when (ns) {
            W -> "w:$name"
            R -> "r:$name"
            else -> name
        }
        val value = element.getAttributeNS(ns, name)
        if (value.isNotEmpty()) return value
        val byQualified = element.getAttribute(qualified)
        if (byQualified.isNotEmpty()) return byQualified
        return element.getAttribute(name).ifEmpty { null }
    }
}

/** `w:` attribute. */
internal fun Element.w(name: String): String? = Wml.attr(this, Wml.W, name)

/** `r:` attribute. */
internal fun Element.r(name: String): String? = Wml.attr(this, Wml.R, name)

/** Child elements with the given local name. */
internal fun Element.kids(name: String): List<Element> = childElements().filter { it.localName == name }

/** The first child element with the given local name. */
internal fun Element.first(name: String): Element? = childElements().firstOrNull { it.localName == name }

/** True when a boolean WordprocessingML property is on (`w:val` absent, true or 1). */
internal fun Element.wOn(): Boolean {
    val value = w("val")?.trim()?.lowercase()
    return value == null || value == "1" || value == "true" || value == "on"
}

/** Twips to points. */
internal fun Element.wPoints(name: String): Double? = w(name)?.trim()?.toDoubleOrNull()?.div(TWIPS_PER_PT)

/** Half-points (`w:sz`) to points. */
internal fun Element.wHalfPoints(name: String): Double? = w(name)?.trim()?.toDoubleOrNull()?.div(2.0)

internal fun String?.toIntOrNullSafe(): Int? = this?.trim()?.toIntOrNull()

/** Every descendant element with the given local name, in document order. */
internal fun Element.descendants(name: String): List<Element> {
    val out = ArrayList<Element>()
    fun walk(node: Element) {
        for (child in node.childElements()) {
            if (child.localName == name) out += child
            walk(child)
        }
    }
    walk(this)
    return out
}
