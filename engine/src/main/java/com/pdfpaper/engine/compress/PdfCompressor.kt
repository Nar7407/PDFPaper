package com.pdfpaper.engine.compress

import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.filter.filterNamesOf
import com.pdfpaper.engine.form.FieldKind
import com.pdfpaper.engine.form.formFields
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.image.ImageEncoder
import com.pdfpaper.engine.image.PdfImages
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.model.parseDirectObject
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.write.Deflate
import com.pdfpaper.engine.write.IndirectObject
import com.pdfpaper.engine.write.PdfCompactWriter
import com.pdfpaper.engine.write.PdfDocumentAssembler
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfPageSelection
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shrinks a PDF by rewriting it from scratch (ISO 32000-1, §7.5): the file is
 * always written again, never patched, so nothing from the old revision can
 * survive.
 *
 * What the run does, in order:
 * 1. the document is rewritten once to get a clean, self-contained copy with
 *    the objects nothing points at already dropped,
 * 2. images are decoded, scaled down to the target resolution and written
 *    again as JPEG (never scaled up; CCITT, JBIG2 and JPEG 2000 images are
 *    left alone),
 * 3. every other compressed stream is decoded and packed again with the
 *    strongest Deflate setting,
 * 4. streams with identical content share one object,
 * 5. the result is written with object streams and a cross-reference stream.
 *
 * The JPEG encoding needs a host codec: pass an [ImageEncoder] (the app uses
 * android.graphics, the JVM tooling uses javax.imageio). Without one, images
 * are kept as they are and the run still compresses the rest of the file.
 *
 * When the result is not smaller than the input, the original bytes are
 * returned and the report says so.
 */
object PdfCompressor {

    /** Filters whose streams are never rewritten. */
    private val KEEP_AS_IS_FILTERS = setOf(
        "DCTDecode", "DCT", "JPXDecode", "JPX", "CCITTFaxDecode", "CCF", "JBIG2Decode"
    )

    /** The PDF version the compact output uses. */
    private const val OUTPUT_VERSION = "1.5"

    /**
     * Compresses [document] and compares the result with [originalBytes].
     *
     * @param originalBytes the bytes of the file as the user has it; they are
     *   returned unchanged when the compression did not make the file smaller.
     */
    fun compress(
        document: PdfDocument,
        originalBytes: ByteArray,
        options: CompressOptions = CompressOptions(),
        decoder: ExternalImageDecoder? = null,
        encoder: ImageEncoder? = null
    ): Result<CompressResult> = runCatching {
        val stats = CompressStats()
        val hasSignatures = detectSignatures(document)
        val base = rewriteBase(document, options, null)
        val compressed = compressGraph(base, options, decoder, encoder, stats)
        val report = buildReport(
            stats = stats,
            originalBytes = originalBytes.size,
            compressedBytes = compressed.size,
            objectsBefore = document.file.objectCount,
            objectsAfter = stats.objectsWritten,
            usedOriginal = compressed.size >= originalBytes.size
        )
        if (compressed.size >= originalBytes.size) {
            CompressResult(
                bytes = originalBytes,
                report = report,
                hasSignatures = hasSignatures
            )
        } else {
            CompressResult(
                bytes = compressed,
                report = report,
                hasSignatures = hasSignatures
            )
        }
    }

    /**
     * Estimates how small the file would get, by compressing a sample of the
     * pages and scaling the result up. The estimate is a guide, not a promise.
     */
    fun estimate(
        document: PdfDocument,
        originalBytes: ByteArray,
        options: CompressOptions = CompressOptions(),
        decoder: ExternalImageDecoder? = null,
        encoder: ImageEncoder? = null,
        samplePages: Int = 3
    ): CompressEstimate {
        val pages = document.pages()
        val sample = pages.take(samplePages.coerceAtLeast(1))
        if (sample.isEmpty()) {
            return CompressEstimate(originalBytes.size, originalBytes.size, 0, 0)
        }
        val stats = CompressStats()
        val sampleBase = rewriteBase(document, options, sample)
        val compressed = runCatching {
            compressGraph(sampleBase, options, decoder, encoder, stats)
        }.getOrElse { return CompressEstimate(originalBytes.size, originalBytes.size, sample.size, pages.size) }
        if (sampleBase.isEmpty()) {
            return CompressEstimate(originalBytes.size, originalBytes.size, sample.size, pages.size)
        }
        val ratio = compressed.size.toDouble() / sampleBase.size.toDouble()
        val estimated = (originalBytes.size * ratio).toInt().coerceIn(0, originalBytes.size)
        return CompressEstimate(
            originalBytes = originalBytes.size,
            estimatedBytes = estimated,
            sampledPages = sample.size,
            totalPages = pages.size
        )
    }

    /** True when the document carries a digital signature. */
    fun detectSignatures(document: PdfDocument): Boolean =
        runCatching { document.formFields().any { it.kind == FieldKind.SIGNATURE } }.getOrDefault(false)

    // -- Internals ------------------------------------------------------------

    /**
     * The first rewrite: a clean copy of the document with the catalogue
     * rebuilt, the inherited page attributes made explicit and the objects
     * nothing points at dropped.
     */
    private fun rewriteBase(
        document: PdfDocument,
        options: CompressOptions,
        pages: List<PdfPage>?
    ): ByteArray {
        val list = pages ?: document.pages()
        val selections = list.map { PdfPageSelection(document, it.index) }
        return PdfDocumentAssembler(
            selections = selections,
            primary = document,
            version = OUTPUT_VERSION,
            preserveInfo = !options.stripMetadata,
            preserveMetadata = !options.stripMetadata,
            preserveOpenAction = true,
            preserveCatalogActions = true,
            preserveOutlines = true,
            preserveNamedDestinations = true,
            preserveAcroForm = true,
            preserveOptionalContent = true,
            preserveEmbeddedFiles = true,
            preserveJavaScriptNames = true,
            preserveThumbnails = !options.stripThumbnails,
            garbageCollect = true
        ).assemble()
    }

    /** The second pass: rewrite the streams and write the compact file. */
    private fun compressGraph(
        baseBytes: ByteArray,
        options: CompressOptions,
        decoder: ExternalImageDecoder?,
        encoder: ImageEncoder?,
        stats: CompressStats
    ): ByteArray {
        val base = PdfDocument.open(ByteArraySource(baseBytes)).getOrThrow()
        base.use {
            val placements = CompressPlacementScanner(base).scan()
            val builder = PdfDocumentBuilder(OUTPUT_VERSION)
            val copier = CompressGraphCopier(base, builder) { objNum, stream ->
                transformStream(
                    source = base,
                    objNum = objNum,
                    stream = stream,
                    placements = placements,
                    options = options,
                    decoder = decoder,
                    encoder = encoder,
                    stats = stats
                )
            }

            val rootRef = base.file.trailer.root
                ?: error("The document has no /Root and cannot be rewritten")
            val root = copier.copyIndirect(rootRef.objNum)
                ?: error("The document's catalogue could not be copied")
            builder.root = root

            val infoRef = base.file.trailer.raw["Info"]?.let { PdfRef.parse(it) }
            builder.info = infoRef?.let { copier.copyIndirect(it.objNum) }
            val idArray = base.file.trailer.raw["ID"]?.let { raw ->
                parseDirectObject(raw.toByteArray(StandardCharsets.ISO_8859_1)).getOrNull() as? PdfArray
            }
            builder.id = idArray

            stats.duplicatesShared = copier.duplicatesShared
            stats.objectsWritten = builder.entries().size

            val objects = builder.entries().map { (num, obj) -> IndirectObject(num, 0, obj) }
            return PdfCompactWriter.write(
                objects = objects,
                root = root,
                info = builder.info,
                id = builder.id,
                version = OUTPUT_VERSION,
                useObjectStreams = options.useObjectStreams,
                useXrefStreams = options.useXrefStreams
            )
        }
    }

    /**
     * Rewrites one stream: images are re-encoded, everything else is packed
     * again. The original stream is returned when nothing better is available.
     */
    private fun transformStream(
        source: PdfDocument,
        objNum: Int,
        stream: PdfStream,
        placements: Map<Int, CompressPlacementScanner.Placement>,
        options: CompressOptions,
        decoder: ExternalImageDecoder?,
        encoder: ImageEncoder?,
        stats: CompressStats
    ): PdfStream {
        val category = categoryOf(stream.dict)
        stats.beforeBytes.merge(category, stream.data.size, Int::plus)
        val result = if (isImage(stream.dict)) {
            transformImage(source, objNum, stream, placements[objNum], options, decoder, encoder, stats)
        } else {
            transformDataStream(source, stream, stats)
        } ?: stream
        stats.afterBytes.merge(category, result.data.size, Int::plus)
        return result
    }

    /**
     * Re-encodes one image: scaled down to the target resolution and written
     * as JPEG. Returns null when the image has to stay as it is.
     */
    private fun transformImage(
        source: PdfDocument,
        objNum: Int,
        stream: PdfStream,
        placement: CompressPlacementScanner.Placement?,
        options: CompressOptions,
        decoder: ExternalImageDecoder?,
        encoder: ImageEncoder?,
        stats: CompressStats
    ): PdfStream? {
        val dict = stream.dict
        if (dict["ImageMask"] != null) {
            stats.skip("One-colour stencil masks are kept as they are.")
            return null
        }
        if (dict["SMask"] != null) {
            stats.skip("Images with a soft mask are kept as they are.")
            return null
        }
        if (dict["Mask"] is PdfRef) {
            stats.skip("Images with a stencil mask are kept as they are.")
            return null
        }
        val filters = filterNamesOf(dict)
        if (filters.any { it in KEEP_AS_IS_FILTERS }) {
            stats.skip("Scanned (CCITT/JBIG2) and JPEG 2000 images are kept as they are.")
            return null
        }
        if (!isEncodableColorSpace(dict["ColorSpace"], source)) {
            stats.skip("Images in colour spaces that JPEG cannot carry are kept as they are.")
            return null
        }
        if (encoder == null) {
            stats.skip("This device has no JPEG encoder, so images are kept as they are.")
            return null
        }
        val decoded = PdfImages.decodeImage(dict, stream.data, null, source, decoder, 0)
        val image = decoded.image
        if (image == null || image.pixels == null) {
            stats.skip("An image could not be decoded, so it was kept unchanged.")
            return null
        }

        val scale = downscaleFactor(image.width, image.height, placement, options.targetImageDpi)
        var width = image.width
        var height = image.height
        var pixels = image.pixels
        if (scale < 1.0) {
            width = max(1, (image.width * scale).roundToInt())
            height = max(1, (image.height * scale).roundToInt())
            pixels = ImageResampler.downscale(image.width, image.height, pixels, width, height)
            stats.imagesDownsampled++
        }
        if (options.grayscale) {
            pixels = ImageResampler.toGrayscale(pixels)
        }
        val encoded = encoder.encodeJpeg(width, height, pixels, options.jpegQuality, options.grayscale)
        if (encoded == null) {
            stats.skip("An image could not be encoded, so it was kept unchanged.")
            return null
        }
        val entries = LinkedHashMap<PdfName, PdfObject>()
        entries[PdfName("Type")] = PdfName("XObject")
        entries[PdfName("Subtype")] = PdfName("Image")
        entries[PdfName("Width")] = PdfInteger(encoded.width.toLong())
        entries[PdfName("Height")] = PdfInteger(encoded.height.toLong())
        entries[PdfName("ColorSpace")] =
            PdfName(if (encoded.grayscale) "DeviceGray" else "DeviceRGB")
        entries[PdfName("BitsPerComponent")] = PdfInteger(8)
        entries[PdfName("Filter")] = PdfName("DCTDecode")
        val replacement = PdfStream(PdfDict(entries), encoded.bytes)
        if (replacement.data.size >= stream.data.size) {
            stats.skip("Some images were already smaller than a re-encoded copy.")
            return null
        }
        stats.imagesRecompressed++
        return replacement
    }

    /**
     * The factor an image is scaled down by. Images are never scaled up, and
     * an image whose placement is unknown keeps its pixel size.
     */
    private fun downscaleFactor(
        pixelWidth: Int,
        pixelHeight: Int,
        placement: CompressPlacementScanner.Placement?,
        targetDpi: Int
    ): Double {
        if (placement == null) return 1.0
        val widthPoints = placement.widthPoints
        val heightPoints = placement.heightPoints
        var dpi = 0.0
        if (widthPoints > 0.0) {
            dpi = max(dpi, pixelWidth / (widthPoints / 72.0))
        }
        if (heightPoints > 0.0) {
            dpi = max(dpi, pixelHeight / (heightPoints / 72.0))
        }
        if (dpi <= targetDpi) return 1.0
        return targetDpi / dpi
    }

    /** Packs a non-image stream again with the strongest Deflate setting. */
    private fun transformDataStream(
        source: PdfDocument,
        stream: PdfStream,
        stats: CompressStats
    ): PdfStream? {
        val filters = filterNamesOf(stream.dict)
        if (filters.isEmpty()) {
            val compressed = Deflate.encode(stream.data, Deflater.BEST_COMPRESSION)
            if (compressed.size >= stream.data.size) return null
            stats.streamsRecompressed++
            return flateStream(stream.dict, compressed)
        }
        if (filters.any { it in KEEP_AS_IS_FILTERS }) return null
        val decoded = runCatching {
            stream.decoded { ref -> source.resolve(ref) }.getOrNull()
        }.getOrNull() ?: return null
        val compressed = Deflate.encode(decoded.bytes, Deflater.BEST_COMPRESSION)
        if (compressed.size >= stream.data.size) return null
        stats.streamsRecompressed++
        return flateStream(stream.dict, compressed)
    }

    /** The same dictionary with a single Flate filter and no filter parameters. */
    private fun flateStream(dict: PdfDict, data: ByteArray): PdfStream {
        val entries = LinkedHashMap<PdfName, PdfObject>()
        for ((key, value) in dict.entries) {
            if (key.name == "Filter" || key.name == "DecodeParms" || key.name == "Length") continue
            entries[key] = value
        }
        entries[PdfName("Filter")] = PdfName("FlateDecode")
        return PdfStream(PdfDict(entries), data)
    }

    private fun isImage(dict: PdfDict): Boolean =
        (dict["Subtype"] as? PdfName)?.name == "Image"

    /** The report category a stream belongs to. */
    private fun categoryOf(dict: PdfDict): String {
        val subtype = (dict["Subtype"] as? PdfName)?.name
        return when {
            subtype == "Image" -> "Images"
            subtype == "FontFile" || subtype == "FontFile2" || subtype == "FontFile3" ||
                subtype == "CIDFontType0C" -> "Fonts"
            else -> "Other content"
        }
    }

    /**
     * True when the colour space can be carried by a JPEG: grey, RGB and
     * indexed versions of those. CMYK, separations and device-dependent
     * colours are left alone.
     */
    private fun isEncodableColorSpace(value: PdfObject?, source: PdfDocument): Boolean {
        val space = resolve(value, source) ?: return false
        return when (space) {
            is PdfName -> space.name in setOf("DeviceGray", "DeviceRGB", "CalGray", "CalRGB")
            is PdfArray -> {
                when ((space.items.firstOrNull() as? PdfName)?.name) {
                    "ICCBased" -> {
                        val profile = resolve(space.items.getOrNull(1), source) as? PdfDict
                        val components = (profile?.get("N") as? PdfInteger)?.value?.toInt()
                        components == 1 || components == 3
                    }
                    "Indexed" -> isEncodableColorSpace(space.items.getOrNull(1), source)
                    "CalRGB", "CalGray" -> true
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun resolve(value: PdfObject?, source: PdfDocument): PdfObject? = when (value) {
        null -> null
        is PdfRef -> source.resolve(value)
        else -> value
    }

    private fun buildReport(
        stats: CompressStats,
        originalBytes: Int,
        compressedBytes: Int,
        objectsBefore: Int,
        objectsAfter: Int,
        usedOriginal: Boolean
    ): CompressReport {
        val categories = ArrayList<CompressCategory>()
        for (label in listOf("Images", "Fonts", "Other content")) {
            val before = stats.beforeBytes[label] ?: 0
            val after = stats.afterBytes[label] ?: 0
            if (before == 0 && after == 0) continue
            categories += CompressCategory(label, before, after)
        }
        val skipped = ArrayList(stats.skipped)
        if (usedOriginal) {
            skipped += "The compressed copy was not smaller, so the original file was kept."
        }
        return CompressReport(
            originalBytes = originalBytes,
            compressedBytes = if (usedOriginal) originalBytes else compressedBytes,
            categories = categories,
            imagesRecompressed = stats.imagesRecompressed,
            imagesDownsampled = stats.imagesDownsampled,
            streamsRecompressed = stats.streamsRecompressed,
            duplicatesShared = stats.duplicatesShared,
            unusedObjectsRemoved = max(0, objectsBefore - objectsAfter),
            skipped = skipped,
            usedOriginal = usedOriginal
        )
    }

    /** Counters collected while the streams are rewritten. */
    private class CompressStats {
        val beforeBytes = HashMap<String, Int>()
        val afterBytes = HashMap<String, Int>()
        val skipped = LinkedHashSet<String>()
        var imagesRecompressed = 0
        var imagesDownsampled = 0
        var streamsRecompressed = 0
        var duplicatesShared = 0
        var objectsWritten = 0

        fun skip(message: String) {
            skipped += message
        }
    }
}
