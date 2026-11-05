package com.pdfpaper.engine.sign

import com.pdfpaper.engine.edit.ImageData
import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor

/**
 * A signature appearance that is drawn by the engine (task T26).
 *
 * These are *visual* signatures only: the engine writes ink, text or a picture
 * into a PDF appearance stream. Nothing is hashed or cryptographically signed,
 * so [VisualSignatures] always reports `visualOnly = true`. Use the
 * `:signatures` module to inspect real digital signatures.
 */
sealed interface SignatureArtwork {

    /**
     * Freehand strokes in an arbitrary source coordinate system. The painter
     * normalises the combined bounds into the placed signature box. [pressure]
     * is a relative pen pressure: 1.0 is the nominal width, values below 1 make
     * a segment thinner and values above 1 make it thicker.
     */
    data class Drawn(
        val strokes: List<SignatureStroke>,
        val color: PdfColor = PdfColor.BLACK,
        /** Nominal stroke width in points at the placed size. */
        val width: Double = 2.0
    ) : SignatureArtwork

    /** A name written with one of the standard-14 Latin fonts. */
    data class Typed(
        val name: String,
        val font: StandardFont = StandardFont.HELVETICA_OBLIQUE,
        val color: PdfColor = PdfColor.BLACK
    ) : SignatureArtwork

    /**
     * A PNG or JPEG picture. PNG alpha is always preserved. With
     * [transparentBackground] near-white pixels (JPEG included) are made
     * transparent as well, which removes the white paper around a scan.
     */
    data class Image(
        val image: ImageData,
        val transparentBackground: Boolean = false
    ) : SignatureArtwork
}

/** One sample of a freehand stroke. */
data class SignaturePoint(
    val x: Double,
    val y: Double,
    /** Relative pen pressure; 1.0 is neutral. */
    val pressure: Double = 1.0
)

/** A polyline sampled from a finger or stylus. */
data class SignatureStroke(val points: List<SignaturePoint>)

/**
 * Where to place a signature (task T26). [x]/[y] are the bottom-left corner of
 * the placed box in page user space (origin at the page's bottom-left).
 *
 * [width] is the width of the *unrotated* signature. The height follows the
 * artwork's aspect ratio and any caption lines. [rotation] is a
 * counter-clockwise quarter turn (0, 90, 180 or 270); 90 and 270 exchange the
 * placed box's width and height, matching the stamp writer (T23).
 */
class SignaturePlacement(
    val pageIndex: Int,
    val x: Double,
    val y: Double,
    val width: Double,
    val rotation: Int = 0
)

/**
 * Optional name and date lines under the signature. A null or blank value adds
 * no line; values are rendered as supplied (the engine does not reformat a
 * date, so callers can use the locale and pattern they want).
 */
class SignatureCaption(
    val name: String? = null,
    val date: String? = null
) {
    internal fun lines(): List<String> = listOfNotNull(
        name?.trim()?.takeIf { it.isNotEmpty() },
        date?.trim()?.takeIf { it.isNotEmpty() }
    )
}

/** Options shared by the visual-signature entry points. */
class VisualSignatureOptions(
    /** Name/date text below the artwork. */
    val caption: SignatureCaption = SignatureCaption(),
    /** Author stored in the annotation's /T entry. */
    val author: String? = null,
    /** Flatten the signature into page content instead of keeping an annotation. */
    val flatten: Boolean = false,
    /** Optional stable id; a unique id is generated when omitted. */
    val id: String? = null
)

/** Which kind of source produced a visual signature. */
enum class VisualSignatureSource {
    DRAWN, TYPED, IMAGE;

    companion object {
        internal fun pdfName(source: VisualSignatureSource): String = when (source) {
            DRAWN -> "Drawn"
            TYPED -> "Typed"
            IMAGE -> "Image"
        }

        internal fun fromPdfName(name: String?): VisualSignatureSource? = when (name) {
            "Drawn" -> DRAWN
            "Typed" -> TYPED
            "Image" -> IMAGE
            else -> null
        }
    }
}

/** The operation that produced a [VisualSignatureResult]. */
enum class VisualSignatureChange { PLACED, MOVED, RESIZED, ROTATED, DELETED, FLATTENED }

/** A visual signature found in an existing document. */
class VisualSignatureInfo(
    val pageIndex: Int,
    val annotationId: Int,
    val id: String,
    val source: VisualSignatureSource?,
    val rect: PdfRect?,
    val author: String?,
    /** Always true: these annotations are visual, never cryptographic. */
    val visualOnly: Boolean = true
)

/** A reference to a placed visual signature. */
data class VisualSignatureRef(val pageIndex: Int, val annotationId: Int) {
    constructor(info: VisualSignatureInfo) : this(info.pageIndex, info.annotationId)
}

/**
 * Result of placing or changing a visual signature. [visualOnly] is always
 * true: this result must never be presented as a cryptographic signature.
 */
class VisualSignatureResult(
    val bytes: ByteArray,
    val pageIndex: Int,
    val signatureId: String,
    val source: VisualSignatureSource?,
    /** Output annotation object numbers; empty after flattening or deleting. */
    val annotationIds: List<Int>,
    /** Placed box after the operation, when a signature still exists. */
    val rect: PdfRect?,
    val change: VisualSignatureChange,
    val flattened: Boolean = false,
    val deleted: Boolean = false,
    /** True for every T26 signature: visual appearance only. */
    val visualOnly: Boolean = true,
    /** Always false: no digital signature dictionary or CMS data is written. */
    val cryptographicSignature: Boolean = false
) {
    /** Human-readable warning suitable for a UI or CLI. */
    val note: String = "Visual only: this is not a cryptographic signature."
}
