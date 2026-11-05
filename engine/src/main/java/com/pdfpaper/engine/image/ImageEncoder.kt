package com.pdfpaper.engine.image

/**
 * The result of one host JPEG encoding: the encoded bytes plus the geometry
 * the engine has to write into the image dictionary (/Width, /Height and the
 * colour space that matches what the encoder produced).
 */
class EncodedImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    /** True when the encoder wrote a single-channel (DeviceGray) JPEG. */
    val grayscale: Boolean
)

/**
 * Host hook for JPEG encoding.
 *
 * The engine reads images (see [ExternalImageDecoder]) but cannot write them:
 * JPEG needs a platform codec. `:app` implements this with
 * `android.graphics.Bitmap`, the JVM tooling with `javax.imageio`. Returning
 * null means "this host cannot encode", and the caller keeps the original
 * image bytes instead of losing the picture.
 */
interface ImageEncoder {

    /**
     * Encodes [rgba] as JPEG.
     *
     * @param width image width in pixels.
     * @param height image height in pixels.
     * @param rgba `width * height * 4` bytes, row-major, red first, row 0 on top.
     * @param quality 1..100 on the usual JPEG quality scale.
     * @param grayscale when true the encoder should write a single-channel image.
     * @return the encoded image, or null when the host cannot encode.
     */
    fun encodeJpeg(
        width: Int,
        height: Int,
        rgba: ByteArray,
        quality: Int,
        grayscale: Boolean
    ): EncodedImage?

    /**
     * Encodes [rgba] as WebP (page export, `PdfPageExport`).
     *
     * The default returns null: WebP is not part of the JVM's standard codecs,
     * so the JVM tooling cannot write it and the export refuses the format with
     * a clear message instead of guessing. Android implements it with
     * `Bitmap.CompressFormat.WEBP_LOSSY`.
     *
     * @param quality 1..100.
     */
    fun encodeWebp(width: Int, height: Int, rgba: ByteArray, quality: Int): EncodedImage? = null
}
