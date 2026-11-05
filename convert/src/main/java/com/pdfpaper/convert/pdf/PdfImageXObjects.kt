package com.pdfpaper.convert.pdf

import com.pdfpaper.convert.raster.PngImage
import com.pdfpaper.convert.raster.Zlib
import com.pdfpaper.engine.image.PdfImages
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.write.PdfDocumentBuilder

/**
 * Image XObjects for the PDFs the converter writes (§8.9.5).
 *
 * A JPEG travels verbatim under `/DCTDecode` — no re-encode, no generation
 * loss — and a decoded raster is written as Flate-compressed DeviceRGB with a
 * DeviceGray `/SMask` when it has transparency (§8.9.6.4). The engine's own
 * `ImageXObjects` does the same for page edits but is internal to `:engine`,
 * so the convert module keeps its own copy of these forty lines rather than
 * widening the engine's API.
 */
internal object PdfImageXObjects {

    fun create(builder: PdfDocumentBuilder, jpeg: ByteArray): PdfRef {
        val info = PdfImages.jpegInfo(jpeg) ?: error("JPEG image without an SOF segment")
        val width = info[0]
        val height = info[1]
        val components = info[2]
        val entries = LinkedHashMap<PdfName, PdfObject>()
        entries[PdfName("Type")] = PdfName("XObject")
        entries[PdfName("Subtype")] = PdfName("Image")
        entries[PdfName("Width")] = PdfInteger(width.toLong())
        entries[PdfName("Height")] = PdfInteger(height.toLong())
        entries[PdfName("ColorSpace")] = PdfName(
            when (components) {
                1 -> "DeviceGray"
                4 -> "DeviceCMYK"
                else -> "DeviceRGB"
            }
        )
        entries[PdfName("BitsPerComponent")] = PdfInteger(8)
        entries[PdfName("Filter")] = PdfName("DCTDecode")
        return builder.addStream(PdfDict(entries), jpeg)
    }

    fun create(builder: PdfDocumentBuilder, image: PngImage): PdfRef {
        val pixels = image.width * image.height
        val rgb = ByteArray(pixels * 3)
        val alpha = ByteArray(pixels)
        var hasAlpha = false
        for (i in 0 until pixels) {
            rgb[i * 3] = image.rgba[i * 4]
            rgb[i * 3 + 1] = image.rgba[i * 4 + 1]
            rgb[i * 3 + 2] = image.rgba[i * 4 + 2]
            val a = image.rgba[i * 4 + 3]
            alpha[i] = a
            if (a != 0xFF.toByte()) hasAlpha = true
        }
        val smask = if (hasAlpha) builder.addStream(grayscale(image), Zlib.encode(alpha)) else null
        val entries = LinkedHashMap<PdfName, PdfObject>()
        entries[PdfName("Type")] = PdfName("XObject")
        entries[PdfName("Subtype")] = PdfName("Image")
        entries[PdfName("Width")] = PdfInteger(image.width.toLong())
        entries[PdfName("Height")] = PdfInteger(image.height.toLong())
        entries[PdfName("ColorSpace")] = PdfName("DeviceRGB")
        entries[PdfName("BitsPerComponent")] = PdfInteger(8)
        entries[PdfName("Filter")] = PdfName("FlateDecode")
        if (smask != null) entries[PdfName("SMask")] = smask
        return builder.addStream(PdfDict(entries), Zlib.encode(rgb))
    }

    private fun grayscale(image: PngImage): PdfDict = PdfDict(
        linkedMapOf(
            PdfName("Type") to PdfName("XObject"),
            PdfName("Subtype") to PdfName("Image"),
            PdfName("Width") to PdfInteger(image.width.toLong()),
            PdfName("Height") to PdfInteger(image.height.toLong()),
            PdfName("ColorSpace") to PdfName("DeviceGray"),
            PdfName("BitsPerComponent") to PdfInteger(8),
            PdfName("Filter") to PdfName("FlateDecode")
        )
    )
}
