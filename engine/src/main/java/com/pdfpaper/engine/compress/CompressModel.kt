package com.pdfpaper.engine.compress

/**
 * How hard the compressor works. Each preset is a fixed set of
 * [CompressOptions]; the custom mode in the app edits those values directly.
 */
enum class CompressPreset(
    val id: String,
    val label: String,
    val description: String
) {
    LIGHT(
        "light",
        "Light",
        "Keeps image detail: images stay near their printed size and are re-encoded gently."
    ),
    BALANCED(
        "balanced",
        "Balanced",
        "Images are reduced to 150 dpi and JPEG quality 75. Good for sharing."
    ),
    STRONG(
        "strong",
        "Strong",
        "Images are reduced to 110 dpi and JPEG quality 60. Smallest files, some loss."
    );

    /** The options this preset stands for. */
    fun options(): CompressOptions = when (this) {
        LIGHT -> CompressOptions(
            targetImageDpi = 200,
            jpegQuality = 85,
            grayscale = false
        )
        BALANCED -> CompressOptions(
            targetImageDpi = 150,
            jpegQuality = 75,
            grayscale = false
        )
        STRONG -> CompressOptions(
            targetImageDpi = 110,
            jpegQuality = 60,
            grayscale = false
        )
    }
}

/**
 * The knobs of one compression run.
 *
 * @param targetImageDpi images that are printed larger than this many dots per
 *   inch are scaled down to it. Images are never scaled up.
 * @param jpegQuality 1..100, the quality of the re-encoded JPEG images.
 * @param grayscale when true images are converted to grey before encoding.
 * @param stripMetadata removes the document information dictionary and the XMP
 *   metadata stream.
 * @param stripThumbnails removes the page preview images the file carries.
 * @param useObjectStreams packs the small objects into object streams (§7.5.7).
 * @param useXrefStreams writes the cross-reference data as a stream (§7.5.8).
 */
data class CompressOptions(
    val targetImageDpi: Int = 150,
    val jpegQuality: Int = 75,
    val grayscale: Boolean = false,
    val stripMetadata: Boolean = false,
    val stripThumbnails: Boolean = false,
    val useObjectStreams: Boolean = true,
    val useXrefStreams: Boolean = true
) {
    init {
        require(targetImageDpi in 40..1200) { "targetImageDpi must be 40..1200" }
        require(jpegQuality in 1..100) { "jpegQuality must be 1..100" }
    }

    /** The preset these options came from, or null for a custom setting. */
    fun presetOrNull(): CompressPreset? =
        CompressPreset.entries.firstOrNull { it.options() == this }
}

/** How many bytes one category of the file used before and after. */
data class CompressCategory(
    val label: String,
    val beforeBytes: Int,
    val afterBytes: Int
) {
    val savedBytes: Int get() = beforeBytes - afterBytes
}

/**
 * What one compression run did.
 *
 * [categories] splits the file into images, fonts and everything else, so the
 * user can see where the space went. [skipped] lists the things that were left
 * alone, in plain language.
 */
data class CompressReport(
    val originalBytes: Int,
    val compressedBytes: Int,
    val categories: List<CompressCategory>,
    val imagesRecompressed: Int,
    val imagesDownsampled: Int,
    val streamsRecompressed: Int,
    val duplicatesShared: Int,
    val unusedObjectsRemoved: Int,
    val skipped: List<String>,
    /** True when the result was not smaller and the original was kept. */
    val usedOriginal: Boolean
) {
    val savedBytes: Int get() = originalBytes - compressedBytes

    val savedPercent: Int
        get() = if (originalBytes > 0) (savedBytes * 100 / originalBytes) else 0

    val smaller: Boolean get() = !usedOriginal && compressedBytes < originalBytes
}

/**
 * The result of a compression run. [bytes] are the bytes to save: the new
 * document, or the untouched original when the run could not make it smaller.
 */
class CompressResult(
    val bytes: ByteArray,
    val report: CompressReport,
    /** True when the document carries a digital signature. */
    val hasSignatures: Boolean
) {
    val usedOriginal: Boolean get() = report.usedOriginal
}

/**
 * A size estimate taken from a sample of the document, used by the app to show
 * what a preset would roughly achieve before anything is written.
 */
data class CompressEstimate(
    val originalBytes: Int,
    val estimatedBytes: Int,
    val sampledPages: Int,
    val totalPages: Int
) {
    val estimatedSavedBytes: Int get() = originalBytes - estimatedBytes

    val estimatedSavedPercent: Int
        get() = if (originalBytes > 0) (estimatedSavedBytes * 100 / originalBytes) else 0
}
