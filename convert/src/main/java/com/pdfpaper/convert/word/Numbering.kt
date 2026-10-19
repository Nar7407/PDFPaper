package com.pdfpaper.convert.word

import org.w3c.dom.Document
import org.w3c.dom.Element

/** One `w:lvl` of an abstract numbering definition. */
internal class NumberingLevel(
    /** `w:numFmt/@w:val`, e.g. "bullet", "decimal", "lowerLetter", "lowerRoman". */
    val format: String,
    /** `w:lvlText`, e.g. `%1.` or a bullet character. */
    val text: String?,
    val start: Int,
    /** `w:pPr/w:ind/@w:left` of the level, in points. */
    val indentLeft: Double?,
    val hanging: Double?,
    val align: WordAlign,
    /** The level's run font, which is how Symbol/Wingdings bullets arrive. */
    val family: String?
)

internal class NumberingDefinition(
    val levels: Map<Int, NumberingLevel>,
    /** `w:lvlOverride/w:startOverride` per level. */
    val startOverrides: Map<Int, Int>
)

/**
 * `word/numbering.xml`: the definitions a `w:numPr` refers to. A document without
 * the part still reads — the paragraphs then carry no marker, which is reported.
 */
internal class Numbering(private val definitions: Map<Int, NumberingDefinition>) {

    fun definition(numId: Int): NumberingDefinition? = definitions[numId]

    /**
     * The level [level] of [numId], falling back to the nearest lower level and
     * then to level 0: Word's own behaviour when a document asks for a level the
     * definition does not have is to use what the definition does have.
     */
    fun level(numId: Int, level: Int): NumberingLevel? {
        val definition = definitions[numId] ?: return null
        for (candidate in level downTo 0) definition.levels[candidate]?.let { return it }
        return definition.levels[0]
    }

    companion object {

        fun empty(): Numbering = Numbering(emptyMap())

        fun read(document: Document?): Numbering {
            val root = document?.documentElement ?: return empty()
            val abstracts = LinkedHashMap<String, Map<Int, NumberingLevel>>()
            for (abstract in root.kids("abstractNum")) {
                val id = abstract.w("abstractNumId")?.trim() ?: continue
                val levels = LinkedHashMap<Int, NumberingLevel>()
                for (level in abstract.kids("lvl")) {
                    val index = level.w("ilvl")?.trim()?.toIntOrNull() ?: continue
                    val properties = level.first("pPr")
                    val indent = properties?.first("ind")
                    levels[index] = NumberingLevel(
                        format = level.first("numFmt")?.w("val")?.trim()?.lowercase() ?: "decimal",
                        text = level.first("lvlText")?.w("val"),
                        start = level.first("start")?.w("val")?.trim()?.toIntOrNull() ?: 1,
                        indentLeft = indent?.wPoints("left") ?: indent?.wPoints("start"),
                        hanging = indent?.wPoints("hanging"),
                        align = WordAlign.of(level.first("lvlJc")?.w("val")) ?: WordAlign.LEFT,
                        family = level.first("rPr").runFormat().family
                    )
                }
                abstracts[id] = levels
            }

            val definitions = LinkedHashMap<Int, NumberingDefinition>()
            for (num in root.kids("num")) {
                val numId = num.w("numId")?.trim()?.toIntOrNull() ?: continue
                val abstractId = num.first("abstractNumId")?.w("val")?.trim()
                val levels = abstracts[abstractId].orEmpty()
                val overrides = LinkedHashMap<Int, Int>()
                for (override in num.kids("lvlOverride")) {
                    val level = override.w("ilvl")?.trim()?.toIntOrNull() ?: continue
                    override.first("startOverride")?.w("val")?.trim()?.toIntOrNull()?.let {
                        overrides[level] = it
                    }
                }
                definitions[numId] = NumberingDefinition(levels, overrides)
            }
            return Numbering(definitions)
        }
    }
}

/**
 * Runs the counters of a document's numbered lists: every `(numId, level)` pair
 * counts its own items, a deeper level resets on every parent item, and `%N`
 * placeholders in `w:lvlText` render in the format of level N (§17.9.6, §17.9.21).
 */
internal class ListCounters(private val numbering: Numbering) {

    private val counters = HashMap<Int, IntArray>()

    /** The level a `w:numPr` refers to, for its indents and format. */
    fun level(numId: Int, level: Int): NumberingLevel? = numbering.level(numId, level)

    /** Advances the counter and returns the marker text, or null without a definition. */
    fun next(numId: Int, level: Int): String? {
        val definition = numbering.definition(numId) ?: return null
        val levelDefinition = numbering.level(numId, level) ?: return null
        val values = counters.getOrPut(numId) { IntArray(LEVELS) { UNSET } }
        val index = level.coerceIn(0, LEVELS - 1)

        for (parent in 0 until index) {
            if (values[parent] == UNSET) {
                values[parent] = numbering.level(numId, parent)?.let { it.start } ?: 1
            }
        }
        if (values[index] == UNSET) {
            values[index] = definition.startOverrides[index] ?: levelDefinition.start
        } else {
            values[index]++
        }
        for (deeper in index + 1 until LEVELS) values[deeper] = UNSET

        val text = levelDefinition.text ?: return null
        if (levelDefinition.format == "bullet" || text.length == 1 && text[0].code >= 0xF000) {
            return if (levelDefinition.format == "bullet") bulletText(text, levelDefinition.family) else text
        }
        return substitute(numId = numId, text = text, values = values, own = levelDefinition)
    }

    private fun substitute(numId: Int, text: String, values: IntArray, own: NumberingLevel): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '%' && i + 1 < text.length && text[i + 1] in '1'..'9') {
                val level = text[i + 1] - '1'
                val format = numbering.level(numId, level)?.format ?: own.format
                val value = when {
                    values[level] != UNSET -> values[level]
                    else -> numbering.level(numId, level)?.start ?: 1
                }
                out.append(formatValue(value, format))
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    companion object {
        private const val LEVELS = 9
        private const val UNSET = -1

        fun formatValue(value: Int, format: String): String = when (format) {
            "decimalZero" -> value.toString().padStart(2, '0')
            "lowerLetter" -> letters(value, upper = false)
            "upperLetter" -> letters(value, upper = true)
            "lowerRoman" -> roman(value, upper = false)
            "upperRoman" -> roman(value, upper = true)
            "none" -> ""
            // ordinal/cardinal/ideograph/... formats have no WinAnsi form here
            else -> value.toString()
        }

        /** `a`, `b`, ... `z`, `aa`, ... like Word's letter numbering. */
        fun letters(value: Int, upper: Boolean): String {
            if (value <= 0) return value.toString()
            val out = StringBuilder()
            var remaining = value
            while (remaining > 0) {
                val digit = (remaining - 1) % 26
                out.insert(0, ('a' + digit))
                remaining = (remaining - 1) / 26
            }
            val text = out.toString()
            return if (upper) text.uppercase() else text
        }

        fun roman(value: Int, upper: Boolean): String {
            if (value <= 0 || value > 3999) return value.toString()
            val table = listOf(
                1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
                50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
            )
            val out = StringBuilder()
            var remaining = value
            for ((amount, symbol) in table) {
                while (remaining >= amount) {
                    out.append(symbol)
                    remaining -= amount
                }
            }
            val text = out.toString()
            return if (upper) text else text.lowercase()
        }

        /**
         * Bullet glyphs live in the private use area of the Symbol, Wingdings and
         * Courier New fonts (`U+F0B7` is Symbol's bullet). PDF's standard fonts
         * have no private use area, so the few glyphs Word's list templates use
         * are mapped to their WinAnsi lookalikes.
         */
        fun bulletText(text: String, family: String?): String {
            val name = family?.trim()?.lowercase()
            val out = StringBuilder()
            for (ch in text) {
                val code = ch.code
                if (code < 0xF000) {
                    out.append(ch)
                    continue
                }
                out.append(
                    when {
                        name == null -> '•'
                        "symbol" in name -> when (code) {
                            0xF0B7, 0xF0A7 -> '•'
                            0xF0A8 -> '▪'
                            0xF0FC -> '✓'
                            else -> '•'
                        }
                        "wingdings" in name -> when (code) {
                            0xF0A7 -> '□'
                            0xF0D8 -> '▪'
                            else -> '▪'
                        }
                        "courier" in name -> if (code == 0xF0A7) 'o' else '•'
                        else -> '•'
                    }
                )
            }
            return out.toString()
        }
    }
}
