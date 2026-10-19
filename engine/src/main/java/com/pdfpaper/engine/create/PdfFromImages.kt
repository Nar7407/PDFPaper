package com.pdfpaper.engine.create

import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.edit.ImageData
import java.io.File

/** How an image is placed on its page. */
enum class ImageFit {
    /** Scale uniformly until the whole image fits inside the margins (contain). */
    FIT,

    /** Scale uniformly until the image covers the margins, cropping the overflow. */
    FILL,

    /** Keep the image's pixel size (1 px = 1 pt), centred in the margins. */
    ORIGINAL
}

/** Options shared by [PdfFromImages]. [margin] is in points on every side. */
data class ImagePdfOptions(
    val pageSize: PageSize = PageSize.A4,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
    val fit: ImageFit = ImageFit.FIT,
    val margin: Double = 0.0,
    val metadata: PdfMetadata = PdfMetadata()
)

/**
 * Where one image lands on its page, in top-left page coordinates. [clip] is
 * set for [ImageFit.FILL], where the scaled image is larger than the margin
 * box and must be clipped to it.
 */
data class ImagePlacement(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val clip: PdfBox?
)

/**
 * Builds one PDF page per image.
 *
 * Pages use [ImagePdfOptions.pageSize] and [ImagePdfOptions.orientation]; the
 * image is centred inside the margin box. JPEG and PNG input are accepted;
 * a JPEG is embedded without re-encoding.
 */
object PdfFromImages {

    /** Builds the PDF from already-decoded images, one page each. */
    fun create(images: List<ImageData>, options: ImagePdfOptions = ImagePdfOptions()): ByteArray {
        require(images.isNotEmpty()) { "at least one image is required" }
        val builder = PdfBuilder(options.pageSize, options.orientation, options.metadata)
        for (image in images) {
            val page = builder.addPage()
            val placement = place(image.width, image.height, options)
            page.drawImage(
                image,
                placement.x,
                placement.y,
                placement.width,
                placement.height,
                placement.clip
            )
        }
        return builder.build()
    }

    /**
     * Decodes [files] and builds the PDF. Failures name the offending file;
     * the result never throws.
     */
    fun createFiles(files: List<File>, options: ImagePdfOptions = ImagePdfOptions()): Result<ByteArray> =
        runCatching {
            require(files.isNotEmpty()) { "no images were given" }
            val images = files.map { file ->
                require(file.isFile) { "image not found: ${file.path}" }
                EditImages.decode(file.readBytes()).getOrElse { error ->
                    throw IllegalArgumentException(
                        "${file.name}: ${error.message ?: error::class.simpleName}"
                    )
                }
            }
            create(images, options)
        }

    /**
     * Computes the placement of an image with [imageWidth] x [imageHeight]
     * samples (pixels count as points, i.e. 72 dpi for [ImageFit.ORIGINAL]).
     */
    fun place(
        imageWidth: Int,
        imageHeight: Int,
        options: ImagePdfOptions = ImagePdfOptions()
    ): ImagePlacement {
        require(imageWidth > 0 && imageHeight > 0) { "image dimensions must be positive" }
        require(options.margin.isFinite() && options.margin >= 0.0) {
            "margin must be finite and non-negative"
        }
        val page = options.pageSize.oriented(options.orientation)
        val contentWidth = page.width - 2.0 * options.margin
        val contentHeight = page.height - 2.0 * options.margin
        require(contentWidth > 0.0 && contentHeight > 0.0) { "margins leave no room on the page" }

        val scale = when (options.fit) {
            ImageFit.FIT -> minOf(contentWidth / imageWidth, contentHeight / imageHeight)
            ImageFit.FILL -> maxOf(contentWidth / imageWidth, contentHeight / imageHeight)
            ImageFit.ORIGINAL -> 1.0
        }
        val width = imageWidth * scale
        val height = imageHeight * scale
        val x = options.margin + (contentWidth - width) / 2.0
        val y = options.margin + (contentHeight - height) / 2.0
        val clip = if (options.fit == ImageFit.FILL) {
            PdfBox(options.margin, options.margin, contentWidth, contentHeight)
        } else {
            null
        }
        return ImagePlacement(x, y, width, height, clip)
    }
}
