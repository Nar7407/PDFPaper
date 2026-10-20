package com.pdfpaper.app.signatures

import com.pdfpaper.engine.edit.StandardFont

/**
 * The four typed-signature looks offered by the visual signature pad.
 *
 * The engine's signature appearance uses the standard-14 fonts, so each style
 * maps to a font that is guaranteed to render without embedding a font file.
 * The Compose preview uses a matching generic family for the same reason.
 */
enum class SignatureFontStyle(
    val label: String,
    val standardFont: StandardFont
) {
    CLASSIC("Classic", StandardFont.TIMES_ITALIC),
    MODERN("Modern", StandardFont.HELVETICA),
    FORMAL("Formal", StandardFont.TIMES_ROMAN),
    SCRIPT("Script", StandardFont.HELVETICA_OBLIQUE)
}

/** One sampled point of a drawn signature, normalised to the pad (0..1). */
data class SignatureSample(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f
)

/** The signature artwork the user is currently editing. */
sealed interface SignatureDraft {
    data class Drawn(val strokes: List<List<SignatureSample>>) : SignatureDraft

    data class Typed(
        val name: String,
        val font: SignatureFontStyle = SignatureFontStyle.SCRIPT
    ) : SignatureDraft

    data class Image(val bytes: ByteArray) : SignatureDraft
}

/** A signature saved in app-private storage and reusable from the viewer. */
data class SavedSignature(
    val id: String,
    val createdAt: Long,
    val draft: SignatureDraft
) {
    val title: String
        get() = when (val value = draft) {
            is SignatureDraft.Drawn -> "Drawn signature"
            is SignatureDraft.Typed -> value.name.trim().ifBlank { "Typed signature" }
            is SignatureDraft.Image -> "Image signature"
        }

    val kindLabel: String
        get() = when (draft) {
            is SignatureDraft.Drawn -> "Drawn"
            is SignatureDraft.Typed -> "Typed"
            is SignatureDraft.Image -> "Image"
        }
}

/** True when there is nothing worth previewing or signing. */
fun SignatureDraft.isEmpty(): Boolean = when (this) {
    is SignatureDraft.Drawn -> strokes.all { it.isEmpty() }
    is SignatureDraft.Typed -> name.isBlank()
    is SignatureDraft.Image -> bytes.isEmpty()
}

/**
 * A stable preview aspect for a draft. The engine derives the real height from
 * the artwork, but the placement overlay only needs an honest-looking box; an
 * unusually tall signature is clamped so it never covers a page edge to edge.
 */
fun SignatureDraft.previewAspect(): Float = when (this) {
    is SignatureDraft.Drawn -> {
        val all = strokes.flatten()
        if (all.isEmpty()) {
            3f
        } else {
            val width = all.maxOf { it.x } - all.minOf { it.x }
            val height = all.maxOf { it.y } - all.minOf { it.y }
            if (width <= 0.001f || height <= 0.001f) 3f
            else (width / height).coerceIn(1.8f, 6.5f)
        }
    }
    is SignatureDraft.Typed -> 3.4f
    is SignatureDraft.Image -> 2.8f
}

/** Label used by the "save this signature" control. */
fun SignatureDraft.suggestedTitle(): String = when (this) {
    is SignatureDraft.Drawn -> "Drawn signature"
    is SignatureDraft.Typed -> name.trim().ifBlank { "Typed signature" }
    is SignatureDraft.Image -> "Image signature"
}
