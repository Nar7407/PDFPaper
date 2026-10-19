package com.pdfpaper.convert.word

import com.pdfpaper.convert.ooxml.PackageCheck
import com.pdfpaper.convert.ooxml.descendants
import com.pdfpaper.convert.ooxml.first
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * The font scheme of the package's theme (`word/theme/theme1.xml`).
 *
 * Word names its fonts through the theme (`w:asciiTheme="minorHAnsi"`) whenever
 * a document uses the default fonts, which is the normal case — the name behind
 * the attribute is only written out when the user picks a font explicitly. The
 * names therefore have to come from the theme before styles and runs can be
 * resolved, and the substitution report would otherwise never mention the
 * document's real fonts.
 *
 * Only the Latin fonts of the scheme are used: the East Asian and
 * complex-script faces belong to scripts the layout does not shape.
 */
internal class Theme(val majorLatin: String?, val minorLatin: String?) {

    /** The family a `w:asciiTheme` / `w:hAnsiTheme` value names, or null. */
    fun familyOf(themeName: String?): String? = when (themeName?.trim()) {
        "majorHAnsi", "majorAscii" -> majorLatin
        "minorHAnsi", "minorAscii" -> minorLatin
        else -> null
    }

    /**
     * Copies the theme's families into `w:ascii` / `w:hAnsi` wherever those are
     * missing, so the rest of the reader only ever sees plain font names.
     */
    fun applyTo(document: Document?) {
        document ?: return
        val fonts = document.getElementsByTagNameNS(Wml.W, "rFonts")
        for (index in 0 until fonts.length) {
            val element = fonts.item(index) as? Element ?: continue
            for ((themeName, plainName) in ATTRIBUTES) {
                if (element.w(plainName) != null) continue
                val family = familyOf(element.w(themeName)) ?: continue
                element.setAttributeNS(Wml.W, "w:$plainName", family)
            }
        }
    }

    companion object {

        private val ATTRIBUTES = listOf("asciiTheme" to "ascii", "hAnsiTheme" to "hAnsi")

        /** The theme of the package, or null when it has none (or an empty one). */
        fun read(package_: PackageCheck.Package): Theme? {
            val root = package_.documents.entries
                .firstOrNull { (name, _) -> name.contains("/theme/") && name.endsWith(".xml") }
                ?.value
                ?.documentElement
                ?: return null
            val scheme = root.descendants("fontScheme").firstOrNull() ?: return null

            fun latin(font: String): String? = scheme.descendants(font).firstOrNull()
                ?.descendants("latin")
                ?.firstOrNull()
                ?.getAttribute("typeface")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            return Theme(majorLatin = latin("majorFont"), minorLatin = latin("minorFont"))
        }
    }
}
