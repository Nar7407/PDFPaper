package com.pdfpaper.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.pdfpaper.app.render.AndroidRasterDecoder
import com.pdfpaper.engine.create.ImageFit
import com.pdfpaper.engine.create.ImagePdfOptions
import com.pdfpaper.engine.create.PdfFromImages
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.edit.ImageData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Turns images shared with PDFPaper into a PDF using the T21 builder
 * (`PdfFromImages`). Images are decoded, optionally downsampled to [MAX_PIXELS],
 * rotated according to their EXIF orientation, and stored as PNG so transparency
 * survives. No third-party codec is involved.
 */
object ImagePdf {

    /** Decoded pixels kept per image; larger photos are sampled down. */
    const val MAX_PIXELS: Long = 24_000_000L

    /** Refuse absurd source dimensions before allocating a bitmap. */
    const val MAX_SOURCE_PIXELS: Long = 120_000_000L

    /**
     * Total decoded pixels across one share. Each image's budget is reduced
     * when many are shared, so fifty photos cannot exhaust the heap.
     */
    const val MAX_TOTAL_PIXELS: Long = 48_000_000L

    const val MIN_IMAGE_PIXELS: Long = 1_000_000L

    const val MAX_IMAGES: Int = 50

    fun create(
        context: Context,
        uris: List<Uri>,
        displayName: String? = null
    ): Result<File> = runCatching {
        require(uris.isNotEmpty()) { "No images were shared" }
        require(uris.size <= MAX_IMAGES) {
            "PDFPaper can make a PDF from at most $MAX_IMAGES images at once"
        }
        val images = ArrayList<ImageData>(uris.size)
        val perImagePixels = pixelsPerImage(uris.size)
        var total = 0L
        uris.forEachIndexed { index, uri ->
            val bytes = readBytes(context, uri)
            total += bytes.size
            if (total > DocumentAccess.MAX_SHARE_BYTES) {
                throw IOException(
                    "The shared images are larger than PDFPaper's " +
                        "${DocumentAccess.MAX_SHARE_BYTES / (1024 * 1024)} MB limit"
                )
            }
            images += decodeImage(bytes, index + 1, perImagePixels)
        }
        val pdf = PdfFromImages.create(
            images,
            ImagePdfOptions(fit = ImageFit.FIT, margin = 0.0)
        )
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        val name = sanitizeDisplayName(displayName ?: "shared-images.pdf")
            .let { if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf" }
        val target = uniqueFile(directory, name)
        target.writeBytes(pdf)
        target
    }

    /** Builds the PDF from already-encoded image bytes (used by device tests). */
    fun createFromEncoded(images: List<ByteArray>): Result<ByteArray> = runCatching {
        require(images.isNotEmpty()) { "No images were shared" }
        require(images.size <= MAX_IMAGES) { "Too many images" }
        val perImagePixels = pixelsPerImage(images.size)
        PdfFromImages.create(
            images.mapIndexed { index, bytes -> decodeImage(bytes, index + 1, perImagePixels) },
            ImagePdfOptions(fit = ImageFit.FIT, margin = 0.0)
        )
    }

    private fun pixelsPerImage(count: Int): Long =
        (MAX_TOTAL_PIXELS / count.coerceAtLeast(1))
            .coerceIn(MIN_IMAGE_PIXELS, MAX_PIXELS)

    private fun readBytes(context: Context, uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("The provider refused access to ${uri.lastPathSegment ?: "an image"}")
        input.use { return readLimited(it, DocumentAccess.MAX_IMAGE_BYTES) }
    }

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw IOException(
                    "An image is larger than PDFPaper's " +
                        "${DocumentAccess.MAX_IMAGE_BYTES / (1024 * 1024)} MB limit"
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun decodeImage(bytes: ByteArray, number: Int, maxPixels: Long): ImageData {
        if (bytes.isEmpty()) throw IOException("Image $number is empty")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Image $number is not a format Android can decode")
        }
        val sourcePixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (sourcePixels > MAX_SOURCE_PIXELS) {
            throw IOException("Image $number is too large (${bounds.outWidth} x ${bounds.outHeight})")
        }
        val orientation = exifOrientation(bytes)
        val isJpeg = isJpeg(bytes)
        val isPng = isPng(bytes)
        if ((isJpeg || isPng) && sourcePixels <= maxPixels &&
            orientation == ExifInterface.ORIENTATION_NORMAL
        ) {
            return EditImages.decode(bytes, AndroidRasterDecoder).getOrThrow()
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("Image $number could not be decoded")
        val oriented = applyOrientation(decoded, orientation)
        val keepAlpha = when {
            isJpeg -> false
            isPng -> pngHasAlpha(bytes)
            else -> true
        }
        val encoded = ByteArrayOutputStream()
        val format = if (keepAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        if (!oriented.compress(format, if (keepAlpha) 100 else 92, encoded)) {
            oriented.recycle()
            if (oriented !== decoded) decoded.recycle()
            throw IOException("Image $number could not be encoded")
        }
        if (oriented !== decoded) decoded.recycle()
        oriented.recycle()
        return EditImages.decode(encoded.toByteArray(), AndroidRasterDecoder).getOrElse {
            throw IOException("Image $number could not be prepared: ${it.message}")
        }
    }

    private fun sampleSize(width: Int, height: Int, maxPixels: Long): Int {
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) {
            sample *= 2
        }
        return sample
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return if (result === bitmap) bitmap else result
    }

    private fun exifOrientation(bytes: ByteArray): Int = runCatching {
        ByteArrayInputStream(bytes).use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** Reads the PNG IHDR colour type and any tRNS chunk before the pixels. */
    private fun pngHasAlpha(bytes: ByteArray): Boolean {
        if (bytes.size < 26 || !isPng(bytes)) return false
        val colorType = bytes[25].toInt() and 0xFF
        if (colorType == 4 || colorType == 6) return true
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val length = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            if (length < 0 || offset + 12L + length > bytes.size) return false
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (type == "tRNS") return true
            if (type == "IDAT") return false
            offset += 12 + length
        }
        return false
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            (bytes[0].toInt() and 0xFF) == 0x89 &&
            bytes[1].toInt() and 0xFF == 0x50 &&
            bytes[2].toInt() and 0xFF == 0x4E &&
            bytes[3].toInt() and 0xFF == 0x47
}
