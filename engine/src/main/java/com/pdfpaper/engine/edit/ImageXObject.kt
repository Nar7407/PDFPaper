package com.pdfpaper.engine.edit

import com.pdfpaper.engine.image.PdfImages
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.write.PdfDocumentBuilder

/**
 * Builds image XObjects from editor [ImageData] (§8.9.5).
 *
 * JPEG bytes are embedded verbatim under /DCTDecode so no quality is lost.
 * Decoded rasters are written as Flate-compressed DeviceRGB samples, with a
 * DeviceGray /SMask when any pixel is not fully opaque (§8.9.6.4).
 */
internal object ImageXObjects {

    fun create(builder: PdfDocumentBuilder, data: ImageData): PdfRef = when (data.format) {
        ImageFormat.JPEG -> createJpeg(builder, data)
        ImageFormat.PNG -> createRaster(builder, data)
    }

    private fun createJpeg(builder: PdfDocumentBuilder, data: ImageData): PdfRef {
        val bytes = data.encoded ?: error("JPEG image without encoded bytes")
        val components = PdfImages.jpegInfo(bytes)?.get(2) ?: 3
        val entries = LinkedHashMap<PdfName, PdfObject>()
        entries[PdfName("Type")] = PdfName("XObject")
        entries[PdfName("Subtype")] = PdfName("Image")
        entries[PdfName("Width")] = PdfInteger(data.width.toLong())
        entries[PdfName("Height")] = PdfInteger(data.height.toLong())
        entries[PdfName("ColorSpace")] = PdfName(deviceSpace(components))
        entries[PdfName("BitsPerComponent")] = PdfInteger(8)
        entries[PdfName("Filter")] = PdfName("DCTDecode")
        return builder.addStream(PdfDict(entries), bytes)
    }

    private fun createRaster(builder: PdfDocumentBuilder, data: ImageData): PdfRef {
        val argb = data.argb ?: error("raster image without pixels")
        val pixelCount = data.width * data.height
        val rgb = ByteArray(pixelCount * 3)
        val alpha = ByteArray(pixelCount)
        var hasAlpha = false
        for (i in 0 until pixelCount) {
            val pixel = argb[i]
            rgb[i * 3] = (pixel ushr 16 and 0xFF).toByte()
            rgb[i * 3 + 1] = (pixel ushr 8 and 0xFF).toByte()
            rgb[i * 3 + 2] = (pixel and 0xFF).toByte()
            val a = pixel ushr 24 and 0xFF
            alpha[i] = a.toByte()
            if (a != 0xFF) hasAlpha = true
        }

        val smask = if (hasAlpha) builder.addStream(grayscaleDict(data), Flate.encode(alpha)) else null

        val entries = LinkedHashMap<PdfName, PdfObject>()
        entries[PdfName("Type")] = PdfName("XObject")
        entries[PdfName("Subtype")] = PdfName("Image")
        entries[PdfName("Width")] = PdfInteger(data.width.toLong())
        entries[PdfName("Height")] = PdfInteger(data.height.toLong())
        entries[PdfName("ColorSpace")] = PdfName("DeviceRGB")
        entries[PdfName("BitsPerComponent")] = PdfInteger(8)
        entries[PdfName("Filter")] = PdfName("FlateDecode")
        if (smask != null) entries[PdfName("SMask")] = smask
        return builder.addStream(PdfDict(entries), Flate.encode(rgb))
    }

    private fun grayscaleDict(data: ImageData): PdfDict {
        val entries = LinkedHashMap<PdfName, PdfObject>()
        entries[PdfName("Type")] = PdfName("XObject")
        entries[PdfName("Subtype")] = PdfName("Image")
        entries[PdfName("Width")] = PdfInteger(data.width.toLong())
        entries[PdfName("Height")] = PdfInteger(data.height.toLong())
        entries[PdfName("ColorSpace")] = PdfName("DeviceGray")
        entries[PdfName("BitsPerComponent")] = PdfInteger(8)
        entries[PdfName("Filter")] = PdfName("FlateDecode")
        return PdfDict(entries)
    }

    private fun deviceSpace(components: Int): String = when (components) {
        1 -> "DeviceGray"
        4 -> "DeviceCMYK"
        else -> "DeviceRGB"
    }
}
