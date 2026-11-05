package com.pdfpaper.convert.pptx

import com.pdfpaper.convert.NoteBag
import com.pdfpaper.convert.ooxml.attr
import com.pdfpaper.convert.ooxml.childElements
import com.pdfpaper.convert.ooxml.descendants
import com.pdfpaper.convert.ooxml.first
import com.pdfpaper.convert.pdf.PdfColor
import org.w3c.dom.Element

/**
 * DrawingML values turned into the converter's units, plus the partial
 * formatting objects the reader merges its inheritance chain from.
 *
 * The readers match elements by *local* name (through the `ooxml` helpers), so
 * a prefix or a namespace declaration never changes what is found. Only
 * attribute lookups go through the namespace-aware [attr].
 */

internal const val P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main"
internal const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
internal const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

/** A `p:` attribute. */
internal fun Element.p(name: String): String? = attr(P_NS, "p", name)

/** An `r:` attribute, used by `r:embed`/`r:link`. */
internal fun Element.r(name: String): String? = attr(R_NS, "r", name)

/** A bare attribute, which is how DrawingML writes all of its own properties. */
internal fun Element.value(name: String): String? = getAttribute(name).ifEmpty { null }

/** EMU text ("914400") to points; null when the attribute is missing or not a number. */
internal fun emu(value: String?): Double? = value?.trim()?.toDoubleOrNull()?.div(EMU_PER_POINT.toDouble())

/** Hundredths of a point (`a:sz`, `spcPts`) to points. */
internal fun hundredths(value: String?): Double? = value?.trim()?.toDoubleOrNull()?.div(100.0)

/** Thousandths of a percent (`spcPct`) to a fraction: 150000 → 1.5. */
internal fun thousandths(value: String?): Double? = value?.trim()?.toDoubleOrNull()?.div(100000.0)

/** True for `"1"`/`"true"`, false for `"0"`/`"false"`, null for absent. */
internal fun Element.boolValue(name: String): Boolean? = when (value(name)?.trim()?.lowercase()) {
    "1", "true" -> true
    "0", "false" -> false
    else -> null
}

/** The theme's colour scheme (`a:clrScheme`) and font scheme (`a:fontScheme`). */
internal class ThemeScheme(
    private val colors: Map<String, PdfColor>,
    val majorFont: String?,
    val minorFont: String?,
    /** The master's `p:clrMap`: "bg1" → "lt1" and friends. */
    private val clrMap: Map<String, String> = emptyMap(),
    /** `a:objectDefaults/a:spDef/a:lstStyle`: run defaults of plain shapes. */
    val objectDefaults: Map<Int, PartialParagraph> = emptyMap(),
    /** `a:effectStyleLst`: the 1-based indexes that actually carry an effect. */
    val effectStyles: Set<Int> = emptySet()
) {
    /**
     * A named theme colour: "accent1", "lt1", "dk1", "hlink", … A slide's own
     * colour map (`bg1`, `tx1`, …) resolves through the master first.
     */
    fun color(name: String): PdfColor? = colors[clrMap[name] ?: name]

    /** The same scheme with the master's colour map in place. */
    fun withClrMap(map: Map<String, String>): ThemeScheme =
        ThemeScheme(colors, majorFont, minorFont, map, objectDefaults, effectStyles)

    /** `+mj-lt`/`+mn-lt` are the theme's fonts; anything else is a literal name. */
    fun resolveTypeface(typeface: String?): String? = when (typeface) {
        null -> null
        "+mj-lt", "+mj-ea", "+mj-cs" -> majorFont
        "+mn-lt", "+mn-ea", "+mn-cs" -> minorFont
        else -> typeface
    }

    companion object {
        val EMPTY = ThemeScheme(emptyMap(), null, null)

        /** Reads `ppt/theme/theme1.xml` (or wherever the package points). */
        fun read(theme: Element, notes: NoteBag): ThemeScheme {
            val colors = HashMap<String, PdfColor>()
            theme.descendants("clrScheme").firstOrNull()?.let { scheme ->
                for (entry in scheme.childElements()) {
                    // A theme entry cannot resolve a scheme name: theme *is* the scheme.
                    val value = colourOf(entry.firstChildElement(), null) ?: continue
                    colors[entry.localName] = value
                }
            }
            val fonts = theme.descendants("fontScheme").firstOrNull()
            val major = fonts?.first("majorFont")?.first("latin")?.value("typeface")
            val minor = fonts?.first("minorFont")?.first("latin")?.value("typeface")
            val scheme = ThemeScheme(colors, major, minor)
            val defaults = HashMap<Int, PartialParagraph>()
            val lstStyle = theme.descendants("objectDefaults").firstOrNull()
                ?.first("spDef")?.first("lstStyle")
            if (lstStyle != null) {
                for (level in 1..9) {
                    lstStyle.first("lvl${level}pPr")?.let {
                        defaults[level] = readParagraphProperties(it, scheme, notes)
                    }
                }
            }
            val effects = HashSet<Int>()
            val effectStyles = theme.descendants("effectStyleLst").firstOrNull()
                ?.childElements().orEmpty().filter { it.localName == "effectStyle" }
            for ((index, style) in effectStyles.withIndex()) {
                if (style.first("effectLst")?.firstChildElement() != null) effects += index + 1
            }
            return if (defaults.isEmpty() && effects.isEmpty()) scheme
            else ThemeScheme(colors, major, minor, objectDefaults = defaults, effectStyles = effects)
        }
    }
}

/** The first child that is an element. */
internal fun Element.firstChildElement(): Element? {
    var node = firstChild
    while (node != null) {
        if (node is Element) return node
        node = node.nextSibling
    }
    return null
}

/**
 * Resolves a colour element (`a:srgbClr`, `a:schemeClr`, `a:sysClr`,
 * `a:prstClr`) with the transformations DrawingML allows on it, or null when
 * there is none. Scheme names resolve through [theme]; pass null inside the
 * theme itself, where the colours are being defined.
 */
internal fun colourOf(element: Element?, theme: ThemeScheme?): PdfColor? {
    val spec = element ?: return null
    var color = when (spec.localName) {
        "srgbClr" -> PdfColor.ofHex(spec.value("val"))
        "sysClr" -> PdfColor.ofHex(spec.value("lastClr"))
        "prstClr" -> PRESET_COLORS[spec.value("val")?.trim()?.lowercase()]
        "schemeClr" -> theme?.color(spec.value("val")?.trim() ?: "")
        else -> null
    } ?: return null
    for (child in spec.childElements()) {
        color = when (child.localName) {
            "tint" -> tint(color, thousandths(child.value("val")) ?: 0.0)
            "shade" -> shade(color, thousandths(child.value("val")) ?: 0.0)
            "lumMod" -> scale(color, thousandths(child.value("val")) ?: 1.0)
            "lumOff" -> offset(color, thousandths(child.value("val")) ?: 0.0)
            else -> color
        }
    }
    return color
}

internal fun parentColour(parent: Element?, theme: ThemeScheme): PdfColor? =
    colourOf(parent?.firstChildElement(), theme)

private fun tint(color: PdfColor, amount: Double): PdfColor = PdfColor(
    (color.red + (255 - color.red) * amount).toInt().coerceIn(0, 255),
    (color.green + (255 - color.green) * amount).toInt().coerceIn(0, 255),
    (color.blue + (255 - color.blue) * amount).toInt().coerceIn(0, 255)
)

private fun shade(color: PdfColor, amount: Double): PdfColor = PdfColor(
    (color.red * (1.0 - amount)).toInt().coerceIn(0, 255),
    (color.green * (1.0 - amount)).toInt().coerceIn(0, 255),
    (color.blue * (1.0 - amount)).toInt().coerceIn(0, 255)
)

private fun scale(color: PdfColor, amount: Double): PdfColor = PdfColor(
    (color.red * amount).toInt().coerceIn(0, 255),
    (color.green * amount).toInt().coerceIn(0, 255),
    (color.blue * amount).toInt().coerceIn(0, 255)
)

private fun offset(color: PdfColor, amount: Double): PdfColor = PdfColor(
    (color.red + 255 * amount).toInt().coerceIn(0, 255),
    (color.green + 255 * amount).toInt().coerceIn(0, 255),
    (color.blue + 255 * amount).toInt().coerceIn(0, 255)
)

/** The handful of preset colours that actually appear in documents. */
private val PRESET_COLORS = mapOf(
    "black" to PdfColor(0, 0, 0),
    "white" to PdfColor(255, 255, 255),
    "red" to PdfColor(255, 0, 0),
    "green" to PdfColor(0, 128, 0),
    "blue" to PdfColor(0, 0, 255),
    "yellow" to PdfColor(255, 255, 0),
    "gray" to PdfColor(128, 128, 128),
    "ltgray" to PdfColor(192, 192, 192),
    "dkgray" to PdfColor(64, 64, 64)
)

// ------------------------------------------------------------------ formatting

/** A run format with nulls where the source said nothing. */
internal class PartialFormat(
    val size: Double? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val family: String? = null,
    val color: PdfColor? = null
) {
    /** This format over [base]: anything said here wins. */
    fun over(base: PartialFormat): PartialFormat = PartialFormat(
        size ?: base.size,
        bold ?: base.bold,
        italic ?: base.italic,
        underline ?: base.underline,
        strike ?: base.strike,
        family ?: base.family,
        color ?: base.color
    )
}

/** A bullet specification: a character, an automatic number, or explicitly none. */
internal sealed interface Bullet {
    class Char(val text: String) : Bullet
    class Auto(val type: String, val start: Int) : Bullet
    object None : Bullet
}

/** Paragraph properties with nulls where the source said nothing. */
internal class PartialParagraph(
    val align: SlideAlign? = null,
    val marginLeft: Double? = null,
    val indent: Double? = null,
    val spaceBefore: Double? = null,
    val spaceBeforePercent: Double? = null,
    val spaceAfter: Double? = null,
    val spaceAfterPercent: Double? = null,
    val lineMultiple: Double? = null,
    val linePoints: Double? = null,
    val bullet: Bullet? = null,
    val format: PartialFormat? = null
) {
    fun over(base: PartialParagraph): PartialParagraph = PartialParagraph(
        align ?: base.align,
        marginLeft ?: base.marginLeft,
        indent ?: base.indent,
        spaceBefore ?: base.spaceBefore,
        spaceBeforePercent ?: base.spaceBeforePercent,
        spaceAfter ?: base.spaceAfter,
        spaceAfterPercent ?: base.spaceAfterPercent,
        lineMultiple ?: base.lineMultiple,
        linePoints ?: base.linePoints,
        bullet ?: base.bullet,
        (format ?: PartialFormat()).over(base.format ?: PartialFormat())
    )
}

/** Reads an `a:lvlXpPr` or `a:pPr` element. */
internal fun readParagraphProperties(
    element: Element?,
    theme: ThemeScheme,
    notes: NoteBag
): PartialParagraph {
    if (element == null) return PartialParagraph()
    val align = when (element.value("algn")) {
        "ctr" -> SlideAlign.CENTER
        "r" -> SlideAlign.RIGHT
        "just", "dist" -> SlideAlign.JUSTIFY
        "l" -> SlideAlign.LEFT
        else -> null
    }
    fun spacing(name: String): Pair<Double?, Double?> {
        val child = element.first(name) ?: return null to null
        return hundredths(child.first("spcPts")?.value("val")) to
            thousandths(child.first("spcPct")?.value("val"))
    }
    val (beforePoints, beforePercent) = spacing("spcBef")
    val (afterPoints, afterPercent) = spacing("spcAft")
    val line = element.first("lnSpc")
    val bullet = when {
        element.first("buNone") != null -> Bullet.None
        element.first("buChar") != null -> when (val char = element.first("buChar")?.value("char")) {
            null -> Bullet.None
            else -> Bullet.Char(char)
        }
        element.first("buAutoNum") != null -> {
            val auto = element.first("buAutoNum")
            Bullet.Auto(
                auto?.value("type") ?: "arabicPeriod",
                auto?.value("startAt")?.trim()?.toIntOrNull() ?: 1
            )
        }
        else -> null
    }
    return PartialParagraph(
        align = align,
        marginLeft = emu(element.value("marL")),
        indent = emu(element.value("indent")),
        spaceBefore = beforePoints,
        spaceBeforePercent = beforePercent,
        spaceAfter = afterPoints,
        spaceAfterPercent = afterPercent,
        lineMultiple = thousandths(line?.first("spcPct")?.value("val")),
        linePoints = hundredths(line?.first("spcPts")?.value("val")),
        bullet = bullet,
        format = readRunFormat(element.first("defRPr"), theme, notes)
    )
}

/** Reads `a:rPr` or `a:defRPr`. */
internal fun readRunFormat(element: Element?, theme: ThemeScheme, notes: NoteBag): PartialFormat? {
    if (element == null) return null
    val underline = when (element.value("u")?.trim()?.lowercase()) {
        null -> null
        "none" -> false
        else -> true
    }
    val strike = when (element.value("strike")?.trim()?.lowercase()) {
        null -> null
        "nostrike" -> false
        else -> true
    }
    if (element.descendants("alpha").isNotEmpty()) {
        notes.add("transparency is not converted; text and shapes are drawn opaque")
    }
    return PartialFormat(
        size = hundredths(element.value("sz")),
        bold = element.boolValue("b"),
        italic = element.boolValue("i"),
        underline = underline,
        strike = strike,
        family = theme.resolveTypeface(element.first("latin")?.value("typeface")),
        color = parentColour(element.first("solidFill"), theme)
    )
}

// ------------------------------------------------------------------ numbering

/** Formats an `a:buAutoNum` value. */
internal fun autoNumber(type: String, value: Int): String {
    val base = when {
        type.endsWith("Period") -> type.removeSuffix("Period")
        type.endsWith("ParenR") -> type.removeSuffix("ParenR")
        type.endsWith("Plain") -> type.removeSuffix("Plain")
        else -> type
    }
    val suffix = when {
        type.endsWith("Period") -> "."
        type.endsWith("ParenR") -> ")"
        else -> ""
    }
    return when (base) {
        "romanLc" -> roman(value).lowercase() + suffix
        "romanUc" -> roman(value) + suffix
        "alphaLc", "alphaLcParenBoth" -> letters(value).lowercase() + suffix
        "alphaUc" -> letters(value) + suffix
        "arabic", "arabicDb", "circleNum" -> "$value$suffix"
        else -> "$value$suffix"
    }
}

private fun roman(value: Int): String {
    if (value <= 0 || value > 3999) return value.toString()
    val numerals = listOf(
        1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
        50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
    )
    var rest = value
    val out = StringBuilder()
    for ((amount, text) in numerals) {
        while (rest >= amount) {
            out.append(text)
            rest -= amount
        }
    }
    return out.toString()
}

private fun letters(value: Int): String {
    if (value <= 0) return value.toString()
    val out = StringBuilder()
    var rest = value
    while (rest > 0) {
        val index = (rest - 1) % 26
        out.insert(0, 'a' + index)
        rest = (rest - 1) / 26
    }
    return out.toString()
}
