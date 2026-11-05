package com.pdfpaper.app.signatures

import com.pdfpaper.app.render.AndroidRasterDecoder
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.sign.SignatureArtwork
import com.pdfpaper.engine.sign.SignaturePoint
import com.pdfpaper.engine.sign.SignatureStroke

/**
 * Converts the editor's lightweight draft into the engine's T26 artwork.
 *
 * Drawn points are already normalised; the engine normalises them again when
 * it paints the appearance, so the source coordinate system can stay simple.
 * Images are decoded through Android's codec, which also lets the engine remove
 * the white paper around a scanned signature (the extra decoder argument).
 */
fun SignatureDraft.toArtwork(): Result<SignatureArtwork> = runCatching {
    require(!isEmpty()) { "The signature is empty." }
    when (this) {
        is SignatureDraft.Drawn -> {
            val strokes = strokes
                .filter { it.isNotEmpty() }
                .map { points ->
                    SignatureStroke(
                        points.map { point ->
                            SignaturePoint(
                                x = point.x.toDouble(),
                                y = point.y.toDouble(),
                                pressure = point.pressure.toDouble().coerceIn(0.15, 2.0)
                            )
                        }
                    )
                }
            require(strokes.isNotEmpty()) { "Draw a signature first." }
            SignatureArtwork.Drawn(strokes = strokes, width = 2.2)
        }

        is SignatureDraft.Typed -> {
            val text = name.trim()
            require(text.isNotEmpty()) { "Type your name first." }
            SignatureArtwork.Typed(name = text, font = font.standardFont)
        }

        is SignatureDraft.Image -> {
            val image = EditImages.decode(bytes, AndroidRasterDecoder)
                .getOrElse { throw IllegalArgumentException("That image could not be read. Choose a PNG or JPEG.") }
            SignatureArtwork.Image(image = image, transparentBackground = true)
        }
    }
}
