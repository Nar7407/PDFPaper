package com.pdfpaper.engine.redact

import com.pdfpaper.engine.edit.Flate
import com.pdfpaper.engine.edit.ImageData
import com.pdfpaper.engine.edit.ImageFormat
import com.pdfpaper.engine.edit.ImageXObjects
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.image.RasterImage
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfBoolean
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.write.PdfDocumentBuilder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-JVM decoder used by the redactor when the caller does not supply a
 * platform decoder. JPEG and PNG work through ImageIO; JPEG 2000 works only
 * when the runtime has a JPX ImageIO plugin.
 */
class JdkImageDecoder : ExternalImageDecoder {
    override fun decode(format: String, data: ByteArray, width: Int, height: Int): ByteArray? {
        val image: BufferedImage = ImageIO.read(ByteArrayInputStream(data)) ?: return null
        if (image.width <= 0 || image.height <= 0) return null
        val argb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
        val rgba = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            val pixel = argb[i]
            rgba[i * 4] = (pixel ushr 16 and 0xFF).toByte()
            rgba[i * 4 + 1] = (pixel ushr 8 and 0xFF).toByte()
            rgba[i * 4 + 2] = (pixel and 0xFF).toByte()
            rgba[i * 4 + 3] = (pixel ushr 24 and 0xFF).toByte()
        }
        return rgba
    }
}

internal val DEFAULT_IMAGE_DECODER: ExternalImageDecoder = JdkImageDecoder()

/** Half-open pixel rectangle inside a decoded raster. */
internal data class PixelRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val isEmpty: Boolean get() = x1 <= x0 || y1 <= y0
}

/**
 * Maps page-space redaction rectangles into decoded-image sample coordinates.
 * [imageMatrix] maps image space (x/width, y/height, y down) to the stream's
 * user space. Corners are transformed rather than just their bounding box, so
 * rotated and mirrored placements are handled.
 */
internal fun pixelRegionsOf(
    marks: List<PdfRect>,
    imageMatrix: PdfMatrix,
    width: Int,
    height: Int
): List<PixelRect> {
    if (width <= 0 || height <= 0) return emptyList()
    val inverse = imageMatrix.inverse() ?: return emptyList()
    val out = ArrayList<PixelRect>(marks.size)
    for (mark in marks) {
        val corners = doubleArrayOf(
            mark.left, mark.bottom,
            mark.right, mark.bottom,
            mark.right, mark.top,
            mark.left, mark.top
        )
        var minU = Double.POSITIVE_INFINITY
        var minV = Double.POSITIVE_INFINITY
        var maxU = Double.NEGATIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        for (i in 0 until 4) {
            val p = inverse.apply(corners[i * 2], corners[i * 2 + 1])
            minU = min(minU, p[0])
            minV = min(minV, p[1])
            maxU = max(maxU, p[0])
            maxV = max(maxV, p[1])
        }
        if (!minU.isFinite() || !minV.isFinite() || !maxU.isFinite() || !maxV.isFinite()) continue
        val x0 = floor(minU * width).toInt().coerceIn(0, width)
        val x1 = ceil(maxU * width).toInt().coerceIn(0, width)
        // Image row 0 is at v=0 (top), matching the display-list unit matrix.
        val y0 = floor(minV * height).toInt().coerceIn(0, height)
        val y1 = ceil(maxV * height).toInt().coerceIn(0, height)
        val region = PixelRect(x0, y0, x1, y1)
        if (!region.isEmpty) out += region
    }
    return out
}

/** True when one redaction rectangle completely contains [bounds]. */
internal fun fullyCovered(marks: List<PdfRect>, bounds: PdfRect): Boolean {
    val eps = 1e-7
    return marks.any {
        it.left <= bounds.left + eps && it.bottom <= bounds.bottom + eps &&
            it.right >= bounds.right - eps && it.top >= bounds.top - eps
    }
}

/** True when a page-space rectangle intersects any mark. */
internal fun intersectsAny(marks: List<PdfRect>, bounds: PdfRect): Boolean {
    val eps = 1e-9
    return marks.any {
        bounds.right > it.left + eps && bounds.left < it.right - eps &&
            bounds.top > it.bottom + eps && bounds.bottom < it.top - eps
    }
}

/**
 * Creates a replacement image with every pixel inside one of [regions] blanked
 * to opaque white (or made transparent for an /ImageMask). The soft mask is
 * folded into the replacement alpha so it stays consistent with the base
 * samples.
 */
internal fun blankImage(
    builder: PdfDocumentBuilder,
    image: RasterImage,
    regions: List<PixelRect>
): PdfRef? {
    if (regions.isEmpty()) return null
    val pixels = image.pixels ?: return null
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0 || pixels.size < width * height * 4) return null

    fun inRegion(x: Int, y: Int): Boolean = regions.any {
        x >= it.x0 && x < it.x1 && y >= it.y0 && y < it.y1
    }

    fun maskAlpha(x: Int, y: Int): Int {
        val mask = image.softMask ?: return 255
        if (mask.width != width || mask.height != height) return 255
        val m = mask.pixelAt(x, y) ?: return 255
        return if (mask.stencil) m[3] else m[0]
    }

    if (image.stencil) {
        val rowBytes = (width + 7) / 8
        val bits = ByteArray(rowBytes * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = (y * width + x) * 4
                val alpha = if (inRegion(x, y)) 0 else {
                    ((pixels[offset + 3].toInt() and 0xFF) * maskAlpha(x, y) + 127) / 255
                }
                if (alpha >= 128) {
                    bits[y * rowBytes + x / 8] =
                        (bits[y * rowBytes + x / 8].toInt() or (1 shl (7 - (x % 8)))).toByte()
                }
            }
        }
        val entries = LinkedHashMap<PdfName, PdfObject>()
        entries[PdfName("Type")] = PdfName("XObject")
        entries[PdfName("Subtype")] = PdfName("Image")
        entries[PdfName("Width")] = PdfInteger(width.toLong())
        entries[PdfName("Height")] = PdfInteger(height.toLong())
        entries[PdfName("ImageMask")] = PdfBoolean(true)
        entries[PdfName("BitsPerComponent")] = PdfInteger(1)
        entries[PdfName("Decode")] = PdfArray(listOf(PdfInteger(0), PdfInteger(1)))
        entries[PdfName("Filter")] = PdfName("FlateDecode")
        return builder.addStream(PdfDict(entries), Flate.encode(bits))
    }

    val argb = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val offset = (y * width + x) * 4
            val index = y * width + x
            if (inRegion(x, y)) {
                // White is intentional: the visible cover uses the mark colour,
                // but a viewer that ignores it still shows no original pixels.
                argb[index] = 0xFFFFFFFF.toInt()
            } else {
                val r = pixels[offset].toInt() and 0xFF
                val g = pixels[offset + 1].toInt() and 0xFF
                val b = pixels[offset + 2].toInt() and 0xFF
                val a = pixels[offset + 3].toInt() and 0xFF
                val alpha = (a * maskAlpha(x, y) + 127) / 255
                argb[index] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
    val data = ImageData(ImageFormat.PNG, width, height, argb, null)
    return ImageXObjects.create(builder, data)
}

/** Name generator local to one page rewrite. */
internal class ResourceNames(private val existing: Set<String>) {
    private var next = 1
    private val used = HashSet(existing)

    fun fresh(prefix: String = "PpR"): String {
        while (true) {
            val candidate = "$prefix${next++}"
            if (used.add(candidate)) return candidate
        }
    }
}

internal fun imageUnitMatrix(ctm: PdfMatrix): PdfMatrix =
    ctm * PdfMatrix.translation(0.0, 1.0) * PdfMatrix.scaling(1.0, -1.0)

internal fun matrixOfPdf(operands: List<PdfObject>): PdfMatrix? {
    if (operands.size < 6) return null
    val values = DoubleArray(6)
    for (i in 0 until 6) {
        values[i] = when (val value = operands[i]) {
            is PdfInteger -> value.value.toDouble()
            is com.pdfpaper.engine.model.PdfReal -> value.value
            else -> return null
        }
    }
    return PdfMatrix(values[0], values[1], values[2], values[3], values[4], values[5])
}

internal fun boundsOfUnitSquare(matrix: PdfMatrix): PdfRect {
    val points = arrayOf(
        matrix.apply(0.0, 0.0),
        matrix.apply(1.0, 0.0),
        matrix.apply(1.0, 1.0),
        matrix.apply(0.0, 1.0)
    )
    return PdfRect(
        points.minOf { it[0] }, points.minOf { it[1] },
        points.maxOf { it[0] }, points.maxOf { it[1] }
    )
}
