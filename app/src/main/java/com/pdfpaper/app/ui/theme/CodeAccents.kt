package com.pdfpaper.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The Code style accent colours, handed to the chrome so different elements
 * (tool icons, section headers) get different accents — the syntax-highlight
 * look of an editor, spread over calm, readable roles. Outside Code style the
 * local holds no colours and every accent reads as Unspecified, which leaves
 * the neutral themes untouched.
 */
@Immutable
class CodeAccents private constructor(private val colors: List<Color>) {

    /** A stable accent for [key]: the same key always gets the same colour. */
    fun forKey(key: String): Color {
        if (colors.isEmpty()) return Color.Unspecified
        val index = (key.hashCode() and 0x7FFFFFFF) % colors.size
        return colors[index]
    }

    companion object {
        val None = CodeAccents(emptyList())

        /** The accents of [palette], or [None] when it has none (non-code themes). */
        fun of(palette: CodePalette?): CodeAccents =
            if (palette == null) None else CodeAccents(
                palette.accents.map { Color(it.toULong() shl 32) }
            )
    }
}

/** Provided by the app theme inside [CodeAccents.of]'s palettes. */
val LocalCodeAccents = staticCompositionLocalOf { CodeAccents.None }
