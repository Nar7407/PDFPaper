package com.pdfpaper.engine.image

import com.pdfpaper.engine.filter.decodeFilterChain
import com.pdfpaper.engine.filter.decodeParamsOf
import com.pdfpaper.engine.filter.filterNamesOf
import com.pdfpaper.engine.graphics.ColorSpaces
import com.pdfpaper.engine.graphics.DeviceGrayCS
import com.pdfpaper.engine.graphics.IndexedCS
import com.pdfpaper.engine.graphics.PdfColorSpace
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

/**
 * A decoded raster image in 32-bit RGBA order (row-major, first sample row
 * first). When [pixels] is null the data needs a platform decoder (DCT/JPX,
 * §8.9.5.4): [externalFormat] names the codec and [externalData] holds the
 * still-encoded bytes. [stencil] marks an /ImageMask: RGB is zero and the
 * alpha channel carries the mask (the renderer paints it with the current
 * fill color, §8.9.6.2).
 */
class RasterImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray?,
    val softMask: RasterImage? = null,
    val stencil: Boolean = false,
    val externalFormat: String? = null,
    val externalData: ByteArray? = null,
    val warnings: List<String> = emptyList()
) {
    /** True when [pixels] is absent and a host decoder must supply them. */
    val requiresExternalDecoder: Boolean get() = pixels == null && externalData != null

    /** RGBA at ([x], [y]) or null when the pixels are not local. */
    fun pixelAt(x: Int, y: Int): IntArray? {
        val data = pixels ?: return null
        if (x < 0 || y < 0 || x >= width || y >= height) return null
        val i = (y * width + x) * 4
        return intArrayOf(
            data[i].toInt() and 0xFF,
            data[i + 1].toInt() and 0xFF,
            data[i + 2].toInt() and 0xFF,
            data[i + 3].toInt() and 0xFF
        )
    }
}

/**
 * Host hook for codecs the pure-JVM engine cannot run (DCTDecode/JPEG,
 * JPXDecode/JPEG 2000, §8.9.5.4). `:app` supplies `android.graphics`
 * decoding; tests supply a JVM implementation. Implementations return
 * [width]×[height] RGBA bytes or null on failure.
 */
interface ExternalImageDecoder {
    /** [format] is "DCT" or "JPX". */
    fun decode(format: String, data: ByteArray, width: Int, height: Int): ByteArray?
}

/** Decoding outcome: [image] is null when nothing usable could be built. */
class ImageDecodeResult(val image: RasterImage?, val warnings: List<String>)

/**
 * Image XObject / inline image decoding (ISO 32000-1, §8.9.5-8.9.6).
 *
 * Locally decoded: raw sample streams (any filter supported by the filter
 * package, i.e. Flate/LZW/ASCII/RunLength and their chains) for 1/2/4/8/16
 * bits per component, Indexed/Separation/Cal/Device color spaces, /Decode
 * remapping, /ImageMask stencils, /Mask color key and stencil-mask images and
 * /SMask soft masks. DCT and JPX streams keep their encoded bytes for
 * [ExternalImageDecoder]; CCITT/JBIG2 are reported as unsupported.
 */
object PdfImages {

    /** Safety bound: 40M pixels ≈ 160 MB RGBA. */
    private const val MAX_PIXELS = 40_000_000

    private val SOF_MARKERS = intArrayOf(
        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF
    )

    /** Image dictionary keys abbreviated in inline images (§8.9.7, Table 93). */
    private val INLINE_ALIASES = mapOf(
        "BPC" to "BitsPerComponent",
        "CS" to "ColorSpace",
        "D" to "Decode",
        "DP" to "DecodeParms",
        "F" to "Filter",
        "H" to "Height",
        "IM" to "ImageMask",
        "I" to "Interpolate",
        "W" to "Width"
    )

    /** Expands BI-dictionary abbreviations to their full names (§8.9.7). */
    fun normalizeInlineDict(dict: PdfDict): PdfDict {
        var changed = false
        val entries = LinkedHashMap<PdfName, PdfObject>()
        for ((key, value) in dict.entries) {
            val full = INLINE_ALIASES[key.name]
            if (full != null && !dict.entries.containsKey(PdfName(full))) {
                entries[PdfName(full)] = value
                changed = true
            } else {
                entries[key] = value
            }
        }
        return if (changed) PdfDict(entries) else dict
    }

    /** JPEG dimensions/components from the SOF segment, or null (§8.9.5.4). */
    fun jpegInfo(data: ByteArray): IntArray? {
        if (data.size < 4 || (data[0].toInt() and 0xFF) != 0xFF || (data[1].toInt() and 0xFF) != 0xD8) return null
        var i = 2
        while (i + 3 < data.size) {
            if ((data[i].toInt() and 0xFF) != 0xFF) {
                i++
                continue
            }
            var marker = data[i + 1].toInt() and 0xFF
            i += 2
            while (marker == 0xFF && i < data.size) {
                marker = data[i].toInt() and 0xFF
                i++
            }
            if (marker == 0x01 || marker in 0xD0..0xD7 || marker == 0xD8) continue
            if (marker == 0xD9 || marker == 0xDA) return null // EOI / start of scan: no SOF seen
            val length = readU16(data, i) ?: return null
            if (length < 2) return null
            if (SOF_MARKERS.contains(marker)) {
                if (i + 7 >= data.size) return null
                val height = readU16(data, i + 3) ?: return null
                val width = readU16(data, i + 5) ?: return null
                val components = data[i + 7].toInt() and 0xFF
                return intArrayOf(width, height, components)
            }
            i += length
        }
        return null
    }

    private fun readU16(data: ByteArray, index: Int): Int? {
        if (index + 1 >= data.size) return null
        return ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
    }

    /**
     * Decodes an image XObject or inline image. [resources] supplies named
     * color spaces; [data] is the raw (still filtered) stream payload.
     */
    fun decodeImage(
        dict: PdfDict,
        data: ByteArray,
        resources: PdfDict?,
        doc: PdfDocument,
        decoder: ExternalImageDecoder? = null,
        depth: Int = 0
    ): ImageDecodeResult {
        val warnings = ArrayList<String>()
        val width = intOf(dict["Width"]) ?: return ImageDecodeResult(null, listOf("image without /Width"))
        val height = intOf(dict["Height"]) ?: return ImageDecodeResult(null, listOf("image without /Height"))
        if (width <= 0 || height <= 0) {
            return ImageDecodeResult(null, listOf("image with non-positive dimensions ${width}x$height"))
        }
        if (width.toLong() * height.toLong() > MAX_PIXELS) {
            return ImageDecodeResult(null, listOf("image ${width}x$height exceeds the decode size bound"))
        }
        val isMask = (dict["ImageMask"] as? PdfBoolean)?.value == true

        val filters = runCatching { filterNamesOf(dict) }.getOrElse {
            return ImageDecodeResult(null, listOf("/Filter unreadable: ${it.message}"))
        }
        val params = runCatching { decodeParamsOf(dict, filters.size) }.getOrElse { emptyList() }

        return when (filters.lastOrNull()?.let(::canonicalFilter)) {
            "DCTDecode" -> decodeDct(dict, data, filters, params, resources, doc, decoder, warnings)
            "JPXDecode" -> {
                val pixels = decoder?.decode("JPX", data, width, height)
                if (pixels == null) warnings += "JPXDecode (JPEG 2000) needs a platform decoder"
                val softMask = decodeSoftMask(dict, resources, doc, decoder, warnings)
                ImageDecodeResult(
                    RasterImage(
                        width, height, pixels,
                        softMask = softMask,
                        externalFormat = if (pixels == null) "JPX" else null,
                        externalData = if (pixels == null) data else null,
                        warnings = warnings
                    ),
                    warnings
                )
            }
            "CCITTFaxDecode" -> ImageDecodeResult(null, warnings + "CCITTFaxDecode is not supported yet")
            "JBIG2Decode" -> ImageDecodeResult(null, warnings + "JBIG2Decode is not supported yet")
            else -> decodeSamples(dict, data, filters, params, resources, doc, decoder, isMask, warnings, depth)
        }
    }

    private fun decodeDct(
        dict: PdfDict,
        data: ByteArray,
        filters: List<String>,
        params: List<PdfDict?>,
        resources: PdfDict?,
        doc: PdfDocument,
        decoder: ExternalImageDecoder?,
        warnings: MutableList<String>
    ): ImageDecodeResult {
        var jpeg = data
        if (filters.size > 1) {
            val prefixResult = runCatching { decodeFilterChain(data, filters.dropLast(1), params.dropLast(1)) }
            prefixResult.onSuccess { warnings += it.warnings }
            val prefix = prefixResult.getOrNull()
                ?: return ImageDecodeResult(null, warnings + "pre-DCT filters failed: ${prefixResult.exceptionOrNull()?.message}")
            jpeg = prefix.bytes
        }

        val info = jpegInfo(jpeg)
        if (info == null) warnings += "JPEG header not recognised, trusting /Width and /Height"
        val width = info?.get(0) ?: (intOf(dict["Width"]) ?: 0)
        val height = info?.get(1) ?: (intOf(dict["Height"]) ?: 0)
        if (width <= 0 || height <= 0) return ImageDecodeResult(null, warnings + "JPEG dimensions unavailable")

        val softMask = decodeSoftMask(dict, resources, doc, decoder, warnings)
        val pixels = decoder?.decode("DCT", jpeg, width, height)
        if (pixels != null) {
            if (pixels.size == width * height * 4) {
                return ImageDecodeResult(
                    RasterImage(width, height, pixels, softMask = softMask, warnings = warnings), warnings
                )
            }
            warnings += "platform JPEG decoder returned ${pixels.size} bytes, expected ${width * height * 4}"
        }
        warnings += "DCTDecode image kept encoded (needs a platform JPEG decoder)"
        return ImageDecodeResult(
            RasterImage(
                width, height, null,
                softMask = softMask,
                externalFormat = "DCT",
                externalData = jpeg,
                warnings = warnings
            ),
            warnings
        )
    }

    private fun decodeSamples(
        dict: PdfDict,
        data: ByteArray,
        filters: List<String>,
        params: List<PdfDict?>,
        resources: PdfDict?,
        doc: PdfDocument,
        decoder: ExternalImageDecoder?,
        isMask: Boolean,
        warnings: MutableList<String>,
        depth: Int
    ): ImageDecodeResult {
        val width = intOf(dict["Width"]) ?: return ImageDecodeResult(null, warnings + "no /Width")
        val height = intOf(dict["Height"]) ?: return ImageDecodeResult(null, warnings + "no /Height")

        val colorSpace: PdfColorSpace
        if (isMask) {
            colorSpace = DeviceGrayCS
        } else {
            val resolved = ColorSpaces.resolve(dict["ColorSpace"], resources, doc)
            warnings += resolved.warnings
            colorSpace = resolved.space ?: run {
                warnings += "image color space unresolved, assuming DeviceGray"
                DeviceGrayCS
            }
        }

        val bpc = if (isMask) 1 else (intOf(dict["BitsPerComponent"]) ?: 8)
        if (bpc !in intArrayOf(1, 2, 4, 8, 16)) {
            return ImageDecodeResult(null, warnings + "unsupported /BitsPerComponent $bpc")
        }

        val samples = runCatching { decodeFilterChain(data, filters, params) }.getOrElse {
            return ImageDecodeResult(null, warnings + "image data decoding failed: ${it.message}")
        }
        warnings += samples.warnings

        val components = colorSpace.components
        val decode = decodeArray(dict, colorSpace, bpc, isMask)
        val colorKey = (dict["Mask"] as? PdfArray)?.items?.mapNotNull { toDouble(it) }?.toDoubleArray()
        if (colorKey != null && colorKey.size < components * 2) {
            warnings += "/Mask color key array has too few values, ignored"
        }

        val pixels = unpack(
            samples.bytes, width, height, components, bpc, colorSpace, decode, isMask,
            if (colorKey != null && colorKey.size >= components * 2) colorKey else null
        ) ?: return ImageDecodeResult(null, warnings + "image sample data truncated or empty")

        val softMask = decodeSoftMask(dict, resources, doc, decoder, warnings)
        var image = RasterImage(width, height, pixels, softMask = softMask, stencil = isMask, warnings = warnings)
        image = applyStencilMask(image, dict, resources, doc, decoder, warnings, depth)
        return ImageDecodeResult(image, warnings)
    }

    /** /Decode array (§8.9.5.2, Table 89); defaults differ for Indexed and masks. */
    private fun decodeArray(dict: PdfDict, colorSpace: PdfColorSpace, bpc: Int, isMask: Boolean): DoubleArray {
        val components = if (isMask) 1 else colorSpace.components
        val explicit = (dict["Decode"] as? PdfArray)?.items?.mapNotNull { toDouble(it) }?.toDoubleArray()
        if (explicit != null && explicit.size >= components * 2) return explicit
        // Indexed: the decoded value is the palette index itself (§8.6.6.3).
        if (!isMask && colorSpace is IndexedCS) return doubleArrayOf(0.0, ((1 shl bpc) - 1).toDouble())
        return DoubleArray(components * 2) { i -> if (i % 2 == 0) 0.0 else 1.0 }
    }

    /** Unpacks packed samples into RGBA, applying /Decode, masks and the color space. */
    private fun unpack(
        samples: ByteArray,
        width: Int,
        height: Int,
        components: Int,
        bpc: Int,
        colorSpace: PdfColorSpace,
        decode: DoubleArray,
        isMask: Boolean,
        colorKey: DoubleArray?
    ): ByteArray? {
        if (samples.isEmpty()) return null
        val rowBytes = (width.toLong() * components * bpc + 7) / 8
        val out = ByteArray(width * height * 4)
        val values = DoubleArray(components)
        val maxRaw = ((1L shl bpc) - 1).toDouble()
        for (y in 0 until height) {
            val rowStart = y.toLong() * rowBytes
            for (x in 0 until width) {
                for (c in 0 until components) {
                    val bitIndex = rowStart * 8 + (x.toLong() * components + c) * bpc
                    val raw = readBits(samples, bitIndex, bpc).toDouble()
                    val t = raw / maxRaw
                    val dmin = decode.getOrElse(c * 2) { 0.0 }
                    val dmax = decode.getOrElse(c * 2 + 1) { 1.0 }
                    values[c] = dmin + t * (dmax - dmin)
                }
                val offset = (y * width + x) * 4
                if (isMask) {
                    out[offset] = 0
                    out[offset + 1] = 0
                    out[offset + 2] = 0
                    out[offset + 3] = (values[0].coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt().toByte()
                } else {
                    val color = colorSpace.toRgb(values)
                    out[offset] = channel(color.red)
                    out[offset + 1] = channel(color.green)
                    out[offset + 2] = channel(color.blue)
                    out[offset + 3] = 255.toByte()
                }
                if (colorKey != null) {
                    var inside = true
                    for (c in 0 until components) {
                        if (values[c] < colorKey[c * 2] || values[c] > colorKey[c * 2 + 1]) {
                            inside = false
                            break
                        }
                    }
                    if (inside) out[offset + 3] = 0
                }
            }
        }
        return out
    }

    private fun channel(value: Double): Byte =
        (value.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt().toByte()

    private fun readBits(data: ByteArray, bitIndex: Long, count: Int): Int {
        var value = 0
        for (b in 0 until count) {
            val bit = bitIndex + b
            val byteIndex = (bit / 8).toInt()
            val bitInByte = 7 - (bit % 8).toInt()
            val set = if (byteIndex < data.size) (data[byteIndex].toInt() shr bitInByte) and 1 else 0
            value = (value shl 1) or set
        }
        return value
    }

    /** /Mask referencing a stencil image: multiplied into the alpha channel (§8.9.6.5). */
    private fun applyStencilMask(
        image: RasterImage,
        dict: PdfDict,
        resources: PdfDict?,
        doc: PdfDocument,
        decoder: ExternalImageDecoder?,
        warnings: MutableList<String>,
        depth: Int
    ): RasterImage {
        val maskObj = dict["Mask"] ?: return image
        if (maskObj is PdfArray) return image // handled during unpack
        val pixels = image.pixels ?: return image
        if (depth > 4) return image
        val stream = when (maskObj) {
            is PdfRef -> doc.resolve(maskObj) as? PdfStream
            is PdfStream -> maskObj
            else -> null
        }
        if (stream == null) {
            warnings += "/Mask reference does not resolve to an image stream"
            return image
        }
        val maskResult = decodeImage(stream.dict, stream.data, resources, doc, decoder, depth + 1)
        warnings += maskResult.warnings
        val mask = maskResult.image
        if (mask == null) return image
        if (mask.width != image.width || mask.height != image.height) {
            warnings += "/Mask dimensions differ from the image; ignored"
            return image
        }
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                // Stencil masks carry the value in alpha; plain gray/colour
                // mask images use their luminance (§8.9.6.5).
                val maskValue = maskValueOf(mask, x, y)
                val offset = (y * image.width + x) * 4
                val existing = pixels[offset + 3].toInt() and 0xFF
                pixels[offset + 3] = ((existing * maskValue) / 255).toByte()
            }
        }
        return image
    }

    private fun maskValueOf(mask: RasterImage, x: Int, y: Int): Int {
        val pixel = mask.pixelAt(x, y) ?: return 255
        return if (mask.stencil) pixel[3] else pixel[0]
    }

    private fun decodeSoftMask(
        dict: PdfDict,
        resources: PdfDict?,
        doc: PdfDocument,
        decoder: ExternalImageDecoder?,
        warnings: MutableList<String>
    ): RasterImage? {
        val obj = dict["SMask"] ?: return null
        val stream = when (obj) {
            is PdfRef -> doc.resolve(obj) as? PdfStream
            is PdfStream -> obj
            else -> null
        }
        if (stream == null) {
            warnings += "/SMask does not resolve to an image stream"
            return null
        }
        val result = decodeImage(stream.dict, stream.data, resources, doc, decoder, depth = 1)
        warnings += result.warnings
        return result.image
    }

    private fun canonicalFilter(name: String): String = when (name) {
        "DCT" -> "DCTDecode"
        "JPX" -> "JPXDecode"
        "CCF" -> "CCITTFaxDecode"
        else -> name
    }

    private fun intOf(obj: PdfObject?): Int? = when (obj) {
        is PdfInteger -> obj.value.toInt()
        is PdfReal -> obj.value.toInt()
        else -> null
    }

    private fun toDouble(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }
}
