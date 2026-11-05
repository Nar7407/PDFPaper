package com.pdfpaper.app.ui.theme

import kotlin.random.Random

/**
 * The app-chrome theme the reader picked (Settings → Appearance). Only
 * CODE_STYLE repaints the shell in a palette; the rest stay neutral. The
 * reader's page filters (night, sepia, AMOLED page mode) are separate.
 */
enum class AppearanceChoice(val label: String) {
    /** The system dark theme decides. */
    FOLLOW_SYSTEM("Follow system"),

    LIGHT("Light"),

    DARK("Dark"),

    AMOLED_BLACK("AMOLED black"),

    /** Code-editor palettes for the app chrome. */
    CODE_STYLE("Code style")
}

/** How the Code style palette advances on a cold start. */
enum class PaletteRotation(val label: String) {
    /** The next palette in the list, wrapping around. */
    IN_ORDER("In order"),

    /** A random palette that is never the last one. */
    SHUFFLE("Shuffle")
}

/**
 * One Code style palette, inspired by code-editor colour schemes. The names
 * and colours are PDFPaper's own; no editor theme is copied. Colours are
 * ARGB as Long so the model stays pure Kotlin and unit-testable.
 */
data class CodePalette(
    val name: String,
    /** True for dark palettes: the status-bar icons must be light. */
    val darkBackground: Boolean,
    val background: Long,
    val surface: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    val blue: Long,
    val green: Long,
    val orange: Long,
    val purple: Long,
    val yellow: Long
) {
    /** The five syntax-highlight accents, in a stable order. */
    val accents: List<Long> = listOf(blue, green, orange, purple, yellow)

    /** The stable index of this palette inside [CodePalettes.ALL]. */
    val index: Int
        get() = CodePalettes.ALL.indexOfFirst { it.name == name }
}

/** The six palettes and the contrast maths the UI and its tests share. */
object CodePalettes {

    /** Stored last index when no palette was ever used. */
    const val NO_LAST_INDEX = -1

    /** WCAG AA: the minimum contrast ratio for body text. */
    const val MIN_CONTRAST = 4.5

    val MIDNIGHT_BLUE = CodePalette(
        name = "Midnight Blue",
        darkBackground = true,
        background = 0xFF0B1020,
        surface = 0xFF141D33,
        textPrimary = 0xFFE8EDF8,
        textSecondary = 0xFFA3B0C9,
        blue = 0xFF6FA8FF,
        green = 0xFF74D3A5,
        orange = 0xFFF2A368,
        purple = 0xFFB99BF7,
        yellow = 0xFFE7CB79
    )

    val EMBER = CodePalette(
        name = "Ember",
        darkBackground = true,
        background = 0xFF171110,
        surface = 0xFF221914,
        textPrimary = 0xFFF5E9DF,
        textSecondary = 0xFFB8A395,
        blue = 0xFF86AED4,
        green = 0xFFA3C191,
        orange = 0xFFF28C4E,
        purple = 0xFFD3A2CF,
        yellow = 0xFFE7BE6B
    )

    val FOREST = CodePalette(
        name = "Forest",
        darkBackground = true,
        background = 0xFF0B120E,
        surface = 0xFF121C15,
        textPrimary = 0xFFE6F0E8,
        textSecondary = 0xFF9FB5A5,
        blue = 0xFF82B4C4,
        green = 0xFF8BD48F,
        orange = 0xFFDAA96F,
        purple = 0xFFC0A8DB,
        yellow = 0xFFD8CE84
    )

    val VIOLET_NIGHT = CodePalette(
        name = "Violet Night",
        darkBackground = true,
        background = 0xFF130F1E,
        surface = 0xFF1D162C,
        textPrimary = 0xFFEFE9F9,
        textSecondary = 0xFFB0A4C6,
        blue = 0xFF8FAAF5,
        green = 0xFF8FD9B0,
        orange = 0xFFE9A882,
        purple = 0xFFC6A6F5,
        yellow = 0xFFE0CD8E
    )

    val SAND = CodePalette(
        name = "Sand",
        darkBackground = false,
        background = 0xFFF3EBDC,
        surface = 0xFFFCF7EE,
        textPrimary = 0xFF2E2A22,
        textSecondary = 0xFF635B4B,
        blue = 0xFF1B5FA8,
        green = 0xFF256B3E,
        orange = 0xFF96400A,
        purple = 0xFF6A3FA0,
        yellow = 0xFF6F5810
    )

    val PAPER_LIGHT = CodePalette(
        name = "Paper Light",
        darkBackground = false,
        background = 0xFFF7F7F4,
        surface = 0xFFFFFFFF,
        textPrimary = 0xFF1B1B1B,
        textSecondary = 0xFF5A5A5A,
        blue = 0xFF0B5CA8,
        green = 0xFF146B3C,
        orange = 0xFF9C4208,
        purple = 0xFF5B33A0,
        yellow = 0xFF75590A
    )

    /** Every palette, in the order the rotation walks through. */
    val ALL: List<CodePalette> = listOf(
        MIDNIGHT_BLUE,
        EMBER,
        FOREST,
        VIOLET_NIGHT,
        SAND,
        PAPER_LIGHT
    )

    /** The palette at [index], or the first one when the index is stale. */
    fun at(index: Int): CodePalette = ALL[index.coerceIn(0, ALL.size - 1)]

    /** WCAG 2.1 relative luminance of an ARGB colour; alpha is ignored. */
    fun relativeLuminance(argb: Long): Double {
        fun channel(value: Long): Double {
            val c = (value and 0xFF).toDouble() / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(argb shr 16) +
            0.7152 * channel(argb shr 8) +
            0.0722 * channel(argb)
    }

    /** The WCAG contrast ratio of two ARGB colours, from 1 to 21. */
    fun contrastRatio(a: Long, b: Long): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Every text-over-background pair the chrome can paint: both text colours
     * and every accent as text on both backgrounds, plus the background as the
     * content of an accent-filled button. The UI unit test walks this list.
     */
    fun checkedPairs(palette: CodePalette): List<Pair<String, Pair<Long, Long>>> = buildList {
        val backgrounds = listOf("background" to palette.background, "surface" to palette.surface)
        val inks = listOf(
            "textPrimary" to palette.textPrimary,
            "textSecondary" to palette.textSecondary
        ) + palette.accents.mapIndexed { i, c -> ACCENT_NAMES[i] to c }
        for ((inkName, ink) in inks) {
            for ((surfaceName, surface) in backgrounds) {
                add("$inkName on $surfaceName" to (ink to surface))
            }
        }
        for (name in ACCENT_NAMES) {
            val accent = when (name) {
                "blue" -> palette.blue
                "green" -> palette.green
                "orange" -> palette.orange
                "purple" -> palette.purple
                else -> palette.yellow
            }
            add("$name button text" to (palette.background to accent))
        }
    }

    private val ACCENT_NAMES = listOf("blue", "green", "orange", "purple", "yellow")
}

/**
 * How the Code style palette advances on a cold start. The choice is made once
 * per app launch (see the app theme), so the colours never change while a
 * document is open.
 */
object CodePaletteRotation {

    /**
     * In order: the palette after [lastIndex], wrapping around. A last index
     * that is missing or out of range starts the list at 0.
     */
    fun nextInOrder(count: Int, lastIndex: Int): Int =
        if (lastIndex in 0 until count) (lastIndex + 1) % count else 0

    /**
     * Shuffle: a random palette that is never [lastIndex]. The first launch
     * (last index missing) may return any palette; a one-palette list can only
     * return 0.
     */
    fun nextShuffle(count: Int, lastIndex: Int, random: Random): Int {
        if (count <= 1) return 0
        val previous = if (lastIndex in 0 until count) lastIndex else CodePalettes.NO_LAST_INDEX
        if (previous < 0) return random.nextInt(count)
        var next = random.nextInt(count)
        while (next == previous) next = random.nextInt(count)
        return next
    }

    /**
     * The palette index for this launch. With [changeEachLaunch] off the
     * reader's own [chosenIndex] is used every time, so nothing rotates.
     */
    fun indexForLaunch(
        count: Int,
        lastIndex: Int,
        changeEachLaunch: Boolean,
        rotation: PaletteRotation,
        chosenIndex: Int,
        random: Random
    ): Int {
        val chosen = chosenIndex.coerceIn(0, count - 1)
        if (!changeEachLaunch) return chosen
        return when (rotation) {
            PaletteRotation.IN_ORDER -> nextInOrder(count, lastIndex)
            PaletteRotation.SHUFFLE -> nextShuffle(count, lastIndex, random)
        }
    }
}

/**
 * What a choice means once the system dark theme is known. The app theme turns
 * this into a Material colour scheme; it is decided here so the mapping is
 * unit-testable without a device.
 */
data class AppearanceTheme(
    val choice: AppearanceChoice,
    val darkBackground: Boolean,
    val palette: CodePalette?
) {
    /** True when the status and navigation bar icons must be dark. */
    val lightBarIcons: Boolean = !darkBackground

    companion object {
        /**
         * Resolves a choice. [systemDark] is only read for FOLLOW_SYSTEM; the
         * other choices ignore it, so the shell never follows the system by
         * accident.
         */
        fun resolve(
            choice: AppearanceChoice,
            systemDark: Boolean,
            palette: CodePalette?
        ): AppearanceTheme = AppearanceTheme(
            choice = choice,
            darkBackground = when (choice) {
                AppearanceChoice.FOLLOW_SYSTEM -> systemDark
                AppearanceChoice.LIGHT -> false
                AppearanceChoice.DARK, AppearanceChoice.AMOLED_BLACK -> true
                AppearanceChoice.CODE_STYLE -> palette?.darkBackground ?: true
            },
            palette = palette
        )
    }
}
