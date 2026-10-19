package com.pdfpaper.convert.word

import org.w3c.dom.Document
import org.w3c.dom.Element
import com.pdfpaper.convert.ooxml.childElements

/** A paragraph style with its `basedOn` chain already merged in. */
internal class ParagraphStyle(
    val id: String,
    val name: String?,
    val format: WordParaFormat,
    val runFormat: WordRunFormat
)

/** A table style's border setting, after its `basedOn` chain. */
internal class TableStyle(val borders: Boolean?)

/**
 * `word/styles.xml` and the part of `word/settings.xml` the layout needs.
 *
 * Word resolves formatting as docDefaults → paragraph style chain → character
 * style → direct formatting; the sheet keeps each step separate ([paragraph],
 * [character]) so [DocxReader] can apply them in that order. A missing
 * `styles.xml` is not an error: a document may carry all its formatting
 * directly on the runs.
 */
internal class StyleSheet(
    val docDefaults: WordRunFormat,
    val defaultParagraph: WordParaFormat,
    val paragraphStyles: Map<String, ParagraphStyle>,
    val characterStyles: Map<String, WordRunFormat>,
    val tableStyles: Map<String, TableStyle>,
    /** `w:defaultTabStop`, in points. */
    val defaultTabStop: Double?
) {

    fun paragraph(id: String?): ParagraphStyle? = id?.let { paragraphStyles[it] }

    fun character(id: String?): WordRunFormat? = id?.let { characterStyles[it] }

    /** Table borders: null when the source does not say. */
    fun tableBorders(styleId: String?): Boolean? = styleId?.let { tableStyles[it]?.borders }

    /**
     * Heading level of a paragraph style: `w:outlineLvl` (0-based) when the
     * style sets it, otherwise a name or id of the shape "Heading 1" / "heading1".
     * Levels beyond Heading 6 are clamped — Word stops creating levels there.
     */
    fun headingLevel(style: ParagraphStyle?): Int? {
        style ?: return null
        style.format.outlineLevel?.let { if (it in 0..8) return it.coerceAtMost(5) }
        for (candidate in listOfNotNull(style.name, style.id)) {
            val normalised = candidate.trim().lowercase().replace(" ", "").replace("-", "").replace("_", "")
            val match = Regex("^heading([1-9])$").find(normalised) ?: continue
            return (match.groupValues[1].toInt() - 1).coerceAtMost(5)
        }
        return null
    }

    companion object {

        fun empty(): StyleSheet = StyleSheet(
            docDefaults = WordRunFormat(),
            defaultParagraph = WordParaFormat(),
            paragraphStyles = emptyMap(),
            characterStyles = emptyMap(),
            tableStyles = emptyMap(),
            defaultTabStop = null
        )

        fun read(styles: Document?, settings: Document?): StyleSheet {
            val defaults = styles?.let { documentElement(it) }
                ?.first("docDefaults")
            val defaultRun = defaults?.first("rPrDefault")?.first("rPr").runFormat()
            val defaultParagraph = defaults?.first("pPrDefault")?.first("pPr").paraFormat()
            val defaultTabStop = settings?.let { documentElement(it) }
                ?.first("defaultTabStop")
                ?.wPoints("val")

            val root = styles?.let { documentElement(it) }
                ?: return StyleSheet(
                    defaultRun,
                    defaultParagraph,
                    emptyMap(), emptyMap(), emptyMap(), defaultTabStop
                )

            // Raw records first; the chains are resolved afterwards so a
            // basedOn cycle cannot spin.
            class Raw(
                val id: String,
                val type: String,
                val name: String?,
                val basedOn: String?,
                val paragraph: Element?,
                // A table style keeps its borders in w:style/w:tblPr, not under w:pPr.
                val table: Element?,
                val run: Element?
            )

            val raws = LinkedHashMap<String, Raw>()
            for (style in root.kids("style")) {
                val id = style.w("styleId")?.trim() ?: continue
                val name = style.first("name")?.w("val")
                raws[id] = Raw(
                    id = id,
                    type = style.w("type")?.trim()?.lowercase() ?: "paragraph",
                    name = name,
                    basedOn = style.first("basedOn")?.w("val")?.trim(),
                    paragraph = style.first("pPr"),
                    table = style.first("tblPr"),
                    run = style.first("rPr")
                )
            }

            val paragraphStyles = LinkedHashMap<String, ParagraphStyle>()
            val characterStyles = LinkedHashMap<String, WordRunFormat>()
            val tableStyles = LinkedHashMap<String, TableStyle>()

            fun paragraphChain(id: String, seen: MutableSet<String>): Pair<WordParaFormat, WordRunFormat>? {
                if (!seen.add(id)) return null
                val raw = raws[id] ?: return null
                val base = raw.basedOn?.let { paragraphChain(it, seen) }
                val format = (raw.paragraph.paraFormat().let { own ->
                    own.copy(styleId = raw.id)
                }).over(base?.first ?: defaultParagraph)
                val run = raw.run.runFormat().over(base?.second ?: defaultRun)
                return format to run
            }

            fun characterChain(id: String, seen: MutableSet<String>): WordRunFormat? {
                if (!seen.add(id)) return null
                val raw = raws[id] ?: return null
                val base = raw.basedOn?.let { characterChain(it, seen) }
                return raw.run.runFormat().over(base ?: defaultRun)
            }

            fun tableChain(id: String, seen: MutableSet<String>): TableStyle? {
                if (!seen.add(id)) return null
                val raw = raws[id] ?: return null
                val own = raw.table?.first("tblBorders")?.borderSetting()
                val base = raw.basedOn?.let { tableChain(it, seen) }
                return TableStyle(own ?: base?.borders)
            }

            for (raw in raws.values) {
                when (raw.type) {
                    "paragraph" -> paragraphChain(raw.id, HashSet())?.let { (format, run) ->
                        paragraphStyles[raw.id] = ParagraphStyle(raw.id, raw.name, format, run)
                    }
                    "character" -> characterChain(raw.id, HashSet())?.let { characterStyles[raw.id] = it }
                    "table" -> tableChain(raw.id, HashSet())?.let { tableStyles[raw.id] = it }
                }
            }

            return StyleSheet(
                docDefaults = defaultRun,
                defaultParagraph = defaultParagraph,
                paragraphStyles = paragraphStyles,
                characterStyles = characterStyles,
                tableStyles = tableStyles,
                defaultTabStop = defaultTabStop
            )
        }

        private fun documentElement(document: Document): Element? =
            document.documentElement?.takeIf { it.localName == "styles" || it.localName == "settings" }
    }
}

/** True when the element draws something (used for `w:tblBorders`). */
internal fun Element.borderSetting(): Boolean? {
    var anyDrawn = false
    var anyStated = false
    for (edge in childElements()) {
        val value = edge.w("val")?.trim()?.lowercase() ?: continue
        anyStated = true
        if (value != "none" && value != "nil") anyDrawn = true
    }
    return if (!anyStated) null else anyDrawn
}

/** `w:rPr` → [WordRunFormat]. Everything nullable: the merge order is outside. */
internal fun Element?.runFormat(): WordRunFormat {
    this ?: return WordRunFormat()
    var format = WordRunFormat()
    for (child in childElements()) {
        format = when (child.localName) {
            "rFonts" -> format.copy(family = (child.w("ascii") ?: child.w("hAnsi"))?.trim())
            "b" -> format.copy(bold = child.wOn())
            "i" -> format.copy(italic = child.wOn())
            "u" -> {
                val value = child.w("val")?.trim()?.lowercase()
                format.copy(underline = value == null || (value != "none" && value != "nil"))
            }
            "strike", "dstrike" -> format.copy(strike = child.wOn())
            "caps" -> format.copy(allCaps = child.wOn())
            "smallCaps" -> format.copy(smallCaps = child.wOn())
            "color" -> {
                val value = child.w("val")?.trim()
                if (value.equals("auto", ignoreCase = true)) format else format.copy(color = WordColor.ofHex(value))
            }
            "sz" -> format.copy(size = child.wHalfPoints("val"))
            "spacing" -> format.copy(tracking = child.wPoints("val"))
            "vertAlign" -> when (child.w("val")?.trim()?.lowercase()) {
                "superscript" -> format.copy(superscript = true, subscript = false)
                "subscript" -> format.copy(subscript = true, superscript = false)
                "baseline" -> format.copy(superscript = false, subscript = false)
                else -> format
            }
            else -> format
        }
    }
    return format
}

/** `w:pPr` → [WordParaFormat]. */
internal fun Element?.paraFormat(): WordParaFormat {
    this ?: return WordParaFormat()
    var format = WordParaFormat()
    for (child in childElements()) {
        format = when (child.localName) {
            "pStyle" -> format.copy(styleId = child.w("val")?.trim())
            "jc" -> format.copy(alignment = WordAlign.of(child.w("val")))
            "ind" -> {
                val left = child.wPoints("left") ?: child.wPoints("start")
                val right = child.wPoints("right") ?: child.wPoints("end")
                val firstLine = child.wPoints("firstLine")
                val hanging = child.wPoints("hanging")
                format.copy(
                    indentLeft = left ?: format.indentLeft,
                    indentRight = right ?: format.indentRight,
                    indentFirst = when {
                        firstLine != null -> firstLine
                        hanging != null -> -hanging
                        else -> format.indentFirst
                    }
                )
            }
            "spacing" -> {
                val before = child.wPoints("before")
                val after = child.wPoints("after")
                val line = child.w("line")?.trim()?.toDoubleOrNull()
                val rule = child.w("lineRule")?.trim()?.lowercase() ?: "auto"
                val height = when {
                    line == null -> null
                    rule == "exact" -> WordLineHeight.Exact(line / TWIPS_PER_PT)
                    rule == "atleast" -> WordLineHeight.AtLeast(line / TWIPS_PER_PT)
                    else -> WordLineHeight.Multiple(line / 240.0)
                }
                format.copy(
                    spaceBefore = before ?: format.spaceBefore,
                    spaceAfter = after ?: format.spaceAfter,
                    lineHeight = height ?: format.lineHeight
                )
            }
            "pageBreakBefore" -> format.copy(pageBreakBefore = child.wOn())
            "keepNext" -> format.copy(keepWithNext = child.wOn())
            "keepLines" -> format.copy(keepLines = child.wOn())
            "outlineLvl" -> format.copy(outlineLevel = child.w("val")?.trim()?.toIntOrNull())
            "numPr" -> {
                val level = child.first("ilvl")?.w("val")?.trim()?.toIntOrNull() ?: 0
                val numId = child.first("numId")?.w("val")?.trim()?.toIntOrNull()
                if (numId != null && numId != 0) format.copy(numbering = WordListRef(numId, level))
                else format.copy(numbering = null)
            }
            "rPr" -> format.copy(runDefaults = child.runFormat().over(format.runDefaults))
            else -> format
        }
    }
    return format
}
