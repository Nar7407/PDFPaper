package com.pdfpaper.engine.annot

import com.pdfpaper.engine.sign.SignatureArtwork
import com.pdfpaper.engine.sign.SignatureCaption
import com.pdfpaper.engine.sign.VisualSignatureSource

/**
 * A visual (non-cryptographic) signature annotation (task T26). Drawn
 * signatures become an /Ink annotation; typed names and pictures become /Stamp
 * annotations. Both carry a generated appearance stream that draws the whole
 * signature, including optional name/date captions.
 *
 * The public entry point is `com.pdfpaper.engine.sign.VisualSignatures`; this
 * spec only connects the painter to [AnnotationWriter].
 */
internal class VisualSignatureSpec(
    override val pageIndex: Int,
    val artwork: SignatureArtwork,
    val x: Double,
    val y: Double,
    /** Unrotated width in points. */
    val width: Double,
    val rotation: Int,
    val caption: SignatureCaption,
    val author: String?,
    /** Stable /NM used to find the annotation after a save. */
    val marker: String
) : AnnotationSpec {

    val source: VisualSignatureSource
        get() = when (artwork) {
            is SignatureArtwork.Drawn -> VisualSignatureSource.DRAWN
            is SignatureArtwork.Typed -> VisualSignatureSource.TYPED
            is SignatureArtwork.Image -> VisualSignatureSource.IMAGE
        }
}
