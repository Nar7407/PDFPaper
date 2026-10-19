package com.pdfpaper.convert.word

import org.w3c.dom.Element
import com.pdfpaper.convert.ooxml.attr

/**
 * Namespaces and DOM conveniences for reading WordprocessingML. The reader is
 * deliberately namespace-aware but tolerant about prefixes: a `w:val` attribute
 * is looked up in the namespace, then by qualified name, then bare, because
 * real files (and hand-written fixtures) show all three.
 *
 * The element helpers ([kids], [first], [descendants]) are in `ooxml` because
 * every OOXML reader needs them.
 */
internal object Wml {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"

    /** Namespace of `w:` attributes; the `r:` namespace is used for `r:embed`. */
    fun attr(element: Element, ns: String, name: String): String? = when (ns) {
        W -> element.attr(W, "w", name)
        R -> element.attr(R, "r", name)
        else -> element.attr(ns, "", name)
    }
}

/** `w:` attribute. */
internal fun Element.w(name: String): String? = Wml.attr(this, Wml.W, name)

/** `r:` attribute. */
internal fun Element.r(name: String): String? = Wml.attr(this, Wml.R, name)

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

