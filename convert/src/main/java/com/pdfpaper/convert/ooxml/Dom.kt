package com.pdfpaper.convert.ooxml

import org.w3c.dom.Element

/**
 * DOM conveniences shared by every OOXML reader. They match on *local* names,
 * which is what keeps the readers tolerant about prefixes: a file may declare
 * the same namespace with a different one, or none at all.
 */

/** Child elements with the given local name. */
internal fun Element.kids(name: String): List<Element> = childElements().filter { it.localName == name }

/** The first child element with the given local name. */
internal fun Element.first(name: String): Element? = childElements().firstOrNull { it.localName == name }

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

/** An attribute looked up in the namespace, then by qualified name, then bare. */
internal fun Element.attr(ns: String, prefix: String, name: String): String? {
    val value = getAttributeNS(ns, name)
    if (value.isNotEmpty()) return value
    val byQualified = getAttribute("$prefix:$name")
    if (byQualified.isNotEmpty()) return byQualified
    return getAttribute(name).ifEmpty { null }
}

/** `_xHHHH_`, how OOXML stores characters that XML itself cannot hold. */
private val UNDERSCORE_ESCAPE = Regex("_x([0-9A-Fa-f]{4})_")

/**
 * Element text as OOXML means it: control characters, which XML forbids, are
 * written `_xHHHH_` and have to be read back. A vertical tab is a line break in
 * DrawingML text, so it becomes one here. `_x005F_` is how the convention
 * escapes a literal underscore (ECMA-376 Part 1, §22.9.2.6).
 */
internal fun plainText(text: String): String {
    val decoded = if (!text.contains("_x")) {
        text
    } else {
        UNDERSCORE_ESCAPE.replace(text) { match ->
            val code = match.groupValues[1].toInt(16)
            when (code) {
                0x5F -> "_"
                0x0B -> "\n"
                else -> code.toChar().toString()
            }
        }
    }
    return decoded.replace('\u000B', '\n')
}
