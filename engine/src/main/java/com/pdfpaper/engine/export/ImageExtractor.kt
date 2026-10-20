package com.pdfpaper.engine.export

import com.pdfpaper.engine.filter.decodeFilterChain
import com.pdfpaper.engine.filter.decodeParamsOf
import com.pdfpaper.engine.filter.filterNamesOf
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.image.PdfImages
import com.pdfpaper.engine.image.RasterImage
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfBoolean
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import java.security.MessageDigest

/** One image XObject found in the document, as the listing shows it. */
class EmbeddedImageInfo(
    val pageIndex: Int,
    val objectNumber: Int,
    val width: Int,
    val height: Int,
    val bitsPerComponent: Int,
    val colorSpace: String,
    val filter: String,
    val byteSize: Long,
    val imageMask: Boolean,
    val hasSoftMask: Boolean
)

/** What to pull out of the document. */
class ImageExtractOptions(
    /** Prefix for the file names (`<document>-img012.jpg`). */
    val documentName: String = "document",
    /** Skip images narrower or shorter than this many pixels (0 = keep all). */
    val minSize: Int = 0,
    /** Skip an image whose exported bytes equal an earlier one. */
    val skipDuplicates: Boolean = true
)

/**
 * One extracted (or skipped) image. [bytes] is null when [skipped] explains
 * why nothing was written; [format] is the file extension ("jpg", "jp2", "png").
 */
class ExtractedImage(
    val pageIndex: Int,
    val objectNumber: Int,
    val fileName: String?,
    val format: String?,
    val bytes: ByteArray?,
    val width: Int,
    val height: Int,
    val filter: String,
    val skipped: String?,
    val notes: List<String>
)

class ImageExtractResult(
    val images: List<ExtractedImage>,
    /** The same picture may be listed on more than one page. */
    val listed: List<EmbeddedImageInfo>,
    val warnings: List<String>
) {
    val exported: Int get() = images.count { it.bytes != null }
    val skipped: Int get() = images.count { it.bytes == null }
}

/**
 * Lists and extracts the image XObjects of a PDF (ISO 32000-1 §8.9.5).
 *
 * Export rules:
 *  - a single `/DCTDecode` stage (with or without something before it) writes
 *    the *original* JPEG bytes, so the file is bit-identical to the one inside
 *    the PDF; `/FlateDecode` in front of it is decoded first;
 *  - `/JPXDecode` writes the original JPEG 2000 bytes as `.jp2`;
 *  - raw / Flate / LZW / RunLength sample streams go through the engine's image
 *    decoder (any colour space it understands, 1–16 bits, /Decode, /Mask) and
 *    are written as PNG;
 *  - an `/SMask` becomes the PNG alpha channel; an `/ImageMask` becomes a
 *    1-bit black/transparent PNG;
 *  - CCITT, JBIG2 and anything else the engine cannot decode is skipped with
 *    the reason, never a crash.
 *
 * Images are de-duplicated by the SHA-256 of their exported bytes and can be
 * filtered by a minimum size.
 */
object PdfImageExtractor {

    private const val MAX_FORM_DEPTH = 8

    /** Every image XObject reachable from the pages, in page order. */
    fun list(document: PdfDocument): List<EmbeddedImageInfo> {
        val out = ArrayList<EmbeddedImageInfo>()
        val visitedForms = HashSet<Int>()
        for (page in document.pages()) {
            collect(page, page.resources, out, visitedForms, 0)
        }
        return out
    }

    /**
     * Extracts the images. [decoder] is needed for DCT/JPX *decoding* only
     * when a soft mask or an unusual colour space forces it; the JPEG/JP2
     * pass-through never decodes.
     */
    fun extract(
        document: PdfDocument,
        options: ImageExtractOptions = ImageExtractOptions(),
        decoder: ExternalImageDecoder? = null
    ): Result<ImageExtractResult> = runCatching {
        val listed = list(document)
        val results = ArrayList<ExtractedImage>()
        val warnings = ArrayList<String>()
        val seen = HashMap<String, String>()
        var counter = 0
        val visitedForms = HashSet<Int>()
        var found = 0
        for (page in document.pages()) {
            val occurrences = ArrayList<Occurrence>()
            collectOccurrences(page, page.resources, occurrences, visitedForms, 0)
            for (occurrence in occurrences) {
                found++
                counter++
                val info = infoOf(page.index, occurrence, document)
                val baseName = "${PdfPageExport.sanitizeName(options.documentName)}-img" +
                    counter.toString().padStart(3, '0')
                if (info.width < options.minSize || info.height < options.minSize) {
                    results += ExtractedImage(
                        page.index, info.objectNumber, null, null, null, info.width, info.height,
                        info.filter, "smaller than ${options.minSize} px", emptyList()
                    )
                    continue
                }
                val extracted = exportOne(page.index, document, occurrence, info, decoder)
                if (extracted.skipped != null || extracted.bytes == null) {
                    results += extracted
                    continue
                }
                val hash = sha256(extracted.bytes)
                val duplicateOf = seen[hash]
                if (duplicateOf != null && options.skipDuplicates) {
                    results += ExtractedImage(
                        page.index, info.objectNumber, null, null, null, info.width, info.height,
                        info.filter, "duplicate of $duplicateOf", extracted.notes
                    )
                    continue
                }
                if (duplicateOf == null) seen[hash] = "$baseName.${extracted.format}"
                results += ExtractedImage(
                    page.index,
                    info.objectNumber,
                    "$baseName.${extracted.format}",
                    extracted.format,
                    extracted.bytes,
                    info.width,
                    info.height,
                    info.filter,
                    null,
                    extracted.notes
                )
            }
        }
        if (found == 0) warnings += "No image XObjects found."
        ImageExtractResult(results, listed, warnings)
    }

    // ------------------------------------------------------------- discovery

    private class Occurrence(
        val dict: PdfDict,
        val stream: PdfStream,
        val objectNumber: Int,
        val resources: PdfDict?
    )

    private fun collect(
        page: PdfPage,
        resources: PdfDict?,
        out: MutableList<EmbeddedImageInfo>,
        visitedForms: MutableSet<Int>,
        depth: Int
    ) {
        val occurrences = ArrayList<Occurrence>()
        collectOccurrences(page, resources, occurrences, visitedForms, depth)
        for (occurrence in occurrences) {
            out += infoOf(page.index, occurrence, page.document)
        }
    }

    private fun collectOccurrences(
        page: PdfPage,
        resources: PdfDict?,
        out: MutableList<Occurrence>,
        visitedForms: MutableSet<Int>,
        depth: Int
    ) {
        if (depth > MAX_FORM_DEPTH) return
        val doc = page.document
        val xObjects = resolveDict(resources?.get("XObject"), doc) ?: return
        for ((_, value) in xObjects.entries) {
            val objNum = (value as? PdfRef)?.objNum ?: 0
            val stream = when (value) {
                is PdfRef -> doc.resolve(value) as? PdfStream
                is PdfStream -> value
                else -> null
            } ?: continue
            when ((stream.dict["Subtype"] as? PdfName)?.name) {
                "Image" -> if (out.none { it.objectNumber == objNum && objNum != 0 }) {
                    out += Occurrence(stream.dict, stream, objNum, resources)
                }
                "Form" -> {
                    if (objNum != 0 && !visitedForms.add(objNum)) continue
                    if (objNum == 0 && !visitedForms.add(System.identityHashCode(stream))) continue
                    val formResources = resolveDict(stream.dict["Resources"], doc) ?: resources
                    collectOccurrences(page, formResources, out, visitedForms, depth + 1)
                }
            }
        }
    }

    private fun infoOf(pageIndex: Int, occurrence: Occurrence, doc: PdfDocument): EmbeddedImageInfo {
        val dict = occurrence.dict
        val width = intOf(dict["Width"]) ?: 0
        val height = intOf(dict["Height"]) ?: 0
        val mask = (dict["ImageMask"] as? PdfBoolean)?.value == true
        val filters = runCatching { filterNamesOf(dict) }.getOrElse { emptyList() }
        return EmbeddedImageInfo(
            pageIndex = pageIndex,
            objectNumber = occurrence.objectNumber,
            width = width,
            height = height,
            bitsPerComponent = if (mask) 1 else (intOf(dict["BitsPerComponent"]) ?: 8),
            colorSpace = if (mask) "ImageMask" else colorSpaceName(dict["ColorSpace"], doc),
            filter = filters.joinToString("+").ifEmpty { "none" },
            byteSize = occurrence.stream.data.size.toLong(),
            imageMask = mask,
            hasSoftMask = dict["SMask"] != null
        )
    }

    // ------------------------------------------------------------- extraction

    private class Draft(val bytes: ByteArray?, val format: String?, val skipped: String?, val notes: List<String>)

    private fun exportOne(
        pageIndex: Int,
        doc: PdfDocument,
        occurrence: Occurrence,
        info: EmbeddedImageInfo,
        decoder: ExternalImageDecoder?
    ): ExtractedImage {
        val dict = occurrence.dict
        val filters = runCatching { filterNamesOf(dict) }.getOrElse {
            return skipped(pageIndex, info, "unreadable /Filter: ${it.message}")
        }
        val last = filters.lastOrNull()?.let(::canonical)
        val notes = ArrayList<String>()
        return when (last) {
            "DCTDecode" -> {
                val jpeg = prefixDecoded(occurrence.stream, filters) ?: return skipped(
                    pageIndex, info, "filters before DCTDecode could not be decoded"
                )
                if (jpeg.bytes.size < 4) return skipped(pageIndex, info, "empty JPEG data")
                val jpegInfo = PdfImages.jpegInfo(jpeg.bytes)
                if (jpegInfo == null) notes += "JPEG header not recognised; written anyway"
                if (info.hasSoftMask) notes += "soft mask not carried by the JPEG"
                notes += jpeg.warnings
                ExtractedImage(
                    pageIndex, info.objectNumber, null, "jpg", jpeg.bytes,
                    jpegInfo?.get(0) ?: info.width, jpegInfo?.get(1) ?: info.height,
                    info.filter, null, notes
                )
            }
            "JPXDecode" -> {
                val jp2 = prefixDecoded(occurrence.stream, filters) ?: return skipped(
                    pageIndex, info, "filters before JPXDecode could not be decoded"
                )
                if (jp2.bytes.isEmpty()) return skipped(pageIndex, info, "empty JPEG 2000 data")
                ExtractedImage(
                    pageIndex, info.objectNumber, null, "jp2", jp2.bytes,
                    info.width, info.height, info.filter, null, jp2.warnings
                )
            }
            "CCITTFaxDecode" -> skipped(pageIndex, info, "unsupported filter: CCITTFaxDecode")
            "JBIG2Decode" -> skipped(pageIndex, info, "unsupported filter: JBIG2Decode")
            else -> {
                val decoded = PdfImages.decodeImage(
                    dict, occurrence.stream.data, occurrence.resources, doc, decoder
                )
                decoded.warnings.mapTo(notes) { it }
                val image = decoded.image
                    ?: return skipped(pageIndex, info, "could not be decoded: " +
                        decoded.warnings.lastOrNull().orEmpty().ifEmpty { "unknown reason" })
                val png = renderToPng(image, decoder, notes)
                    ?: return skipped(pageIndex, info, "this image needs a platform decoder")
                ExtractedImage(
                    pageIndex, info.objectNumber, null, "png", png,
                    image.width, image.height, info.filter, null, notes
                )
            }
        }
    }

    private class Decoded(val bytes: ByteArray, val warnings: List<String>)

    /** Decodes the filters *before* the final DCT/JPX stage (§7.4.2). */
    private fun prefixDecoded(stream: PdfStream, filters: List<String>): Decoded? {
        if (filters.size <= 1) return Decoded(stream.data, emptyList())
        return runCatching {
            val names = filters.dropLast(1)
            val params = decodeParamsOf(stream.dict, filters.size).dropLast(1)
            val decoded = decodeFilterChain(stream.data, names, params)
            Decoded(decoded.bytes, decoded.warnings)
        }.getOrNull()
    }

    private fun renderToPng(
        image: RasterImage,
        decoder: ExternalImageDecoder?,
        notes: MutableList<String>
    ): ByteArray? {
        var raster: RasterImage = image
        if (raster.pixels == null) {
            val format = raster.externalFormat ?: return null
            val data = raster.externalData ?: return null
            val pixels = decoder?.decode(format, data, raster.width, raster.height) ?: return null
            raster = RasterImage(raster.width, raster.height, pixels, raster.softMask, raster.stencil)
        }
        val pixels = raster.pixels ?: return null
        if (raster.stencil) {
            val alpha = ByteArray(raster.width * raster.height)
            for (i in alpha.indices) alpha[i] = pixels[i * 4 + 3]
            return PngWriter.encodeStencil(raster.width, raster.height, alpha)
        }
        val mask = raster.softMask
        if (mask != null) {
            val maskPixels = mask.pixels
            if (maskPixels == null) {
                notes += "soft mask needs a platform decoder; written fully opaque"
            } else {
                for (y in 0 until raster.height) {
                    for (x in 0 until raster.width) {
                        val mx = if (mask.width == raster.width) x else (x.toLong() * mask.width / raster.width).toInt()
                        val my = if (mask.height == raster.height) y else (y.toLong() * mask.height / raster.height).toInt()
                        val m = my * mask.width + mx
                        val value = if (mask.stencil) {
                            maskPixels[m * 4 + 3].toInt() and 0xFF
                        } else {
                            val r = maskPixels[m * 4].toInt() and 0xFF
                            val g = maskPixels[m * 4 + 1].toInt() and 0xFF
                            val b = maskPixels[m * 4 + 2].toInt() and 0xFF
                            (r * 299 + g * 587 + b * 114) / 1000
                        }
                        val index = (y * raster.width + x) * 4 + 3
                        val existing = pixels[index].toInt() and 0xFF
                        pixels[index] = ((existing * value) / 255).toByte()
                    }
                }
            }
        }
        return PngWriter.encode(raster.width, raster.height, pixels)
    }

    private fun skipped(pageIndex: Int, info: EmbeddedImageInfo, reason: String): ExtractedImage =
        ExtractedImage(
            pageIndex, info.objectNumber, null, null, null, info.width, info.height,
            info.filter, reason, emptyList()
        )

    private fun canonical(name: String): String = when (name) {
        "DCT" -> "DCTDecode"
        "JPX" -> "JPXDecode"
        "CCF" -> "CCITTFaxDecode"
        else -> name
    }

    // ------------------------------------------------------------- helpers

    private fun colorSpaceName(obj: PdfObject?, doc: PdfDocument): String {
        val resolved = when (obj) {
            is PdfRef -> doc.resolve(obj)
            else -> obj
        }
        return when (resolved) {
            null -> "DeviceGray"
            is PdfName -> resolved.name
            is PdfArray -> {
                val kind = (resolved.items.firstOrNull() as? PdfName)?.name ?: return "unknown"
                if (kind == "Indexed" || kind == "I") {
                    val base = colorSpaceName(resolved.items.getOrNull(1), doc)
                    "Indexed($base)"
                } else {
                    kind
                }
            }
            else -> "unknown"
        }
    }

    private fun resolveDict(obj: PdfObject?, doc: PdfDocument): PdfDict? = when (obj) {
        is PdfRef -> doc.resolve(obj) as? PdfDict
        is PdfDict -> obj
        else -> null
    }

    private fun intOf(obj: PdfObject?): Int? = when (obj) {
        is PdfInteger -> obj.value.toInt()
        is PdfReal -> obj.value.toInt()
        else -> null
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
