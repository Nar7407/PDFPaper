package com.pdfpaper.engine.ocr

/**
 * One word as recognized by an [OcrProvider], in the pixel coordinate system of
 * its [OcrImage]: origin at the top-left sample, x to the right, y down.
 * [top] is the upper edge and [bottom] the lower edge, so [height] is
 * `bottom - top` (T25).
 */
data class OcrBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top

    /** True when the box has no positive area. */
    val isEmpty: Boolean get() = width <= 0.0 || height <= 0.0
}

/**
 * One recognized word (T25): its text, the box it occupies in [OcrImage]
 * pixels, and the provider's confidence in the range 0..1.
 */
data class OcrWord(
    val text: String,
    val bounds: OcrBounds,
    val confidence: Double
)

/**
 * The image handed to an [OcrProvider] for one page (T25).
 *
 * [pixels] is 32-bit RGBA, row-major, with row 0 at the top (`R`, `G`, `B`,
 * `A` bytes in that order). When the engine could not decode the embedded
 * codec itself, [pixels] is null and [encodedData] holds the still-encoded
 * DCT (JPEG) or JPX (JPEG 2000) bytes; a host implementation (for example the
 * Android provider in `:app`) must decode those with its platform codec.
 */
class OcrImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray?,
    /** "DCT" or "JPX" when [pixels] is absent, otherwise null. */
    val encodedFormat: String? = null,
    val encodedData: ByteArray? = null
) {
    /** True when [encodedData] must be decoded before the samples are usable. */
    val requiresDecoder: Boolean get() = pixels == null && encodedData != null
}

/**
 * OCR engine supplied by the host (T25). The engine calls [recognize] for one
 * page image at a time and adds the returned words as an invisible text layer.
 * The implementation lives in `:app`, so the pure-JVM engine depends on no OCR
 * library.
 *
 * [language] is passed through from the caller, e.g. `"eng"` or `"eng+hin"`.
 * Returning an empty list is valid (the page is reported as having no words).
 * Throwing is caught per page and reported, so one bad page does not abort the
 * document.
 */
fun interface OcrProvider {
    fun recognize(image: OcrImage, language: String): List<OcrWord>
}
