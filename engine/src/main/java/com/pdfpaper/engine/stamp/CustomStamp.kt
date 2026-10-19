package com.pdfpaper.engine.stamp

import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.edit.ImageFormat
import com.pdfpaper.engine.edit.ImageIoDecoder
import com.pdfpaper.engine.edit.RasterDecoder
import com.pdfpaper.engine.graphics.PdfColor

/**
 * A stamp the user defined (task T23): either a text stamp or an image stamp.
 * Custom stamps are saved and loaded as a JSON list by [StampJson].
 *
 * For an image stamp the original file bytes are kept (JPEG or PNG), so the
 * JSON list is self-contained — no path can go stale — and the picture is
 * re-embedded exactly as written when the stamp is placed.
 *
 * Build instances through [Companion.text] and [Companion.image], which
 * validate and never throw.
 */
class CustomStamp internal constructor(
    val id: String,
    val label: String,
    val text: String? = null,
    val image: ByteArray? = null,
    val imageFormat: ImageFormat? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val textColor: PdfColor = PdfColor.BLACK,
    val borderColor: PdfColor = PdfColor.BLACK,
    val fillColor: PdfColor? = null
) {

    val isImage: Boolean get() = image != null

    /** Width / height of the placed stamp, from the picture for image stamps. */
    val aspect: Double
        get() = if (isImage && imageHeight > 0) imageWidth.toDouble() / imageHeight else DEFAULT_ASPECT

    /** The library design that paints this stamp. */
    fun toDesign(): StampDesign = StampDesign(
        id = id,
        label = label,
        group = StampGroup.CUSTOM,
        art = if (isImage) StampArt.IMAGE else StampArt.BANNER,
        textColor = textColor,
        borderColor = borderColor,
        fillColor = fillColor,
        aspect = aspect,
        text = text
    )

    override fun equals(other: Any?): Boolean = other is CustomStamp &&
        id == other.id && label == other.label && text == other.text &&
        imageFormat == other.imageFormat &&
        imageWidth == other.imageWidth && imageHeight == other.imageHeight &&
        textColor == other.textColor && borderColor == other.borderColor &&
        fillColor == other.fillColor &&
        (image == null && other.image == null || image != null && other.image != null && image.contentEquals(other.image))

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + label.hashCode()
        h = 31 * h + (text?.hashCode() ?: 0)
        h = 31 * h + (image?.contentHashCode() ?: 0)
        h = 31 * h + (imageFormat?.hashCode() ?: 0)
        h = 31 * h + imageWidth
        h = 31 * h + imageHeight
        h = 31 * h + textColor.hashCode()
        h = 31 * h + borderColor.hashCode()
        h = 31 * h + (fillColor?.hashCode() ?: 0)
        return h
    }

    override fun toString(): String = "CustomStamp($id, $label)"

    companion object {

        /** Width / height of a custom text stamp's banner. */
        const val DEFAULT_ASPECT = 2.6

        private const val MAX_ID_LENGTH = 64

        /** A text stamp; [text] is the line the banner paints. */
        fun text(
            id: String,
            label: String,
            text: String,
            textColor: PdfColor = PdfColor.BLACK,
            borderColor: PdfColor = PdfColor.BLACK,
            fillColor: PdfColor? = null
        ): Result<CustomStamp> = runCatching {
            val cleanId = validId(id)
            val cleanLabel = validLabel(label)
            require(text.isNotBlank()) { "stamp text is blank" }
            CustomStamp(
                cleanId, cleanLabel, text = text.trim(),
                textColor = textColor, borderColor = borderColor, fillColor = fillColor
            )
        }

        /**
         * An image stamp from JPEG or PNG bytes ([decoder] is the host's raster
         * decoder; the default uses the JDK ImageIO codec, unavailable on
         * Android). Failure is a [Result], never a throw.
         */
        fun image(
            id: String,
            label: String,
            bytes: ByteArray,
            decoder: RasterDecoder = ImageIoDecoder
        ): Result<CustomStamp> = runCatching {
            val cleanId = validId(id)
            val cleanLabel = validLabel(label)
            val data = EditImages.decode(bytes, decoder).getOrThrow()
            CustomStamp(
                cleanId, cleanLabel, image = bytes, imageFormat = data.format,
                imageWidth = data.width, imageHeight = data.height
            )
        }

        /** A checked stamp id: non-blank, at most 64 characters, no whitespace. */
        internal fun validId(id: String): String {
            val clean = id.trim()
            require(clean.isNotEmpty()) { "stamp id is blank" }
            require(clean.length <= MAX_ID_LENGTH) { "stamp id is longer than $MAX_ID_LENGTH characters" }
            require(clean.none { it.isWhitespace() }) { "stamp id must not contain whitespace" }
            return clean
        }

        /** A checked label, trimmed; blank labels are refused. */
        internal fun validLabel(label: String): String {
            require(label.isNotBlank()) { "stamp label is blank" }
            return label.trim()
        }
    }
}
