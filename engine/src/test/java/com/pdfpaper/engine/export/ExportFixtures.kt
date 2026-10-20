package com.pdfpaper.engine.export

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.write.PdfDocumentBuilder
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Test documents for page export and image extraction. Images are described by
 * their exact PDF dictionary and payload, so a test controls the filter,
 * colour space, bit depth and mask instead of relying on a codec.
 */
object ExportFixtures {

    class Doc {
        val builder = PdfDocumentBuilder("1.7")

        fun addImage(dict: Map<String, PdfObject>, data: ByteArray): PdfRef =
            builder.addStream(PdfDict(dict.mapKeys { PdfName(it.key) }), data)

        /**
         * Writes the document. Every page lists the image refs to place under
         * /XObject (Im0, Im1, ...) and draws each over the whole page.
         */
        fun build(
            pages: List<Pair<DoubleArray, List<PdfRef>>>,
            rotates: List<Int> = List(pages.size) { 0 }
        ): ByteArray {
            val pagesRef = PdfRef(builder.allocate(), 0)
            val kids = ArrayList<PdfObject>()
            pages.forEachIndexed { index, (box, images) ->
                val pageRef = PdfRef(builder.allocate(), 0)
                val resources = LinkedHashMap<PdfName, PdfObject>()
                val content = StringBuilder()
                if (images.isNotEmpty()) {
                    val xobjects = LinkedHashMap<PdfName, PdfObject>()
                    images.forEachIndexed { imageIndex, ref ->
                        xobjects[PdfName("Im$imageIndex")] = ref
                        content.append("q 1 0 0 1 0 0 cm /Im$imageIndex Do Q\n")
                    }
                    resources[PdfName("XObject")] = PdfDict(xobjects)
                }
                val contentRef = builder.addStream(
                    PdfDict(mapOf(PdfName("Type") to PdfName("Content"))),
                    content.toString().toByteArray(Charsets.US_ASCII)
                )
                val entries = LinkedHashMap<PdfName, PdfObject>()
                entries[PdfName("Type")] = PdfName("Page")
                entries[PdfName("Parent")] = pagesRef
                entries[PdfName("MediaBox")] = PdfArray(box.map { PdfInteger(it.toLong()) })
                entries[PdfName("Resources")] = PdfDict(resources)
                entries[PdfName("Contents")] = contentRef
                val rotate = rotates.getOrElse(index) { 0 }
                if (rotate != 0) entries[PdfName("Rotate")] = PdfInteger(rotate.toLong())
                builder.put(pageRef.objNum, PdfDict(entries))
                kids += pageRef
            }
            builder.put(
                pagesRef.objNum,
                PdfDict(
                    mapOf(
                        PdfName("Type") to PdfName("Pages"),
                        PdfName("Kids") to PdfArray(kids),
                        PdfName("Count") to PdfInteger(kids.size.toLong())
                    )
                )
            )
            builder.root = builder.add(
                PdfDict(
                    mapOf(
                        PdfName("Type") to PdfName("Catalog"),
                        PdfName("Pages") to pagesRef
                    )
                )
            )
            return builder.build()
        }
    }

    /** An image dictionary with the common entries filled in. */
    fun imageDict(
        width: Int,
        height: Int,
        colorSpace: PdfObject? = PdfName("DeviceRGB"),
        bits: Int = 8,
        filter: PdfObject? = null,
        extra: Map<String, PdfObject> = emptyMap()
    ): Map<String, PdfObject> {
        val dict = LinkedHashMap<String, PdfObject>()
        dict["Type"] = PdfName("XObject")
        dict["Subtype"] = PdfName("Image")
        dict["Width"] = PdfInteger(width.toLong())
        dict["Height"] = PdfInteger(height.toLong())
        if (colorSpace != null) dict["ColorSpace"] = colorSpace
        dict["BitsPerComponent"] = PdfInteger(bits.toLong())
        if (filter != null) dict["Filter"] = filter
        dict.putAll(extra)
        return dict
    }

    /** Opens a fixture as a document. */
    fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrElse { throw AssertionError("fixture did not open", it) }

    fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n > 0) out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    /** Three 8-bit RGB rows: red, green, blue. */
    fun rgbSamples(): ByteArray = byteArrayOf(
        255.toByte(), 0, 0, 0, 255.toByte(), 0, 0, 0, 255.toByte()
    )

    fun graySamples(): ByteArray = byteArrayOf(0, 128.toByte(), 255.toByte())

    /** A two-entry palette: black and pure red. */
    fun indexedLookup(): ByteArray = byteArrayOf(0, 0, 0, 255.toByte(), 0, 0)

    /** Gray samples with the top two bits used (16-bit values). */
    fun gray16Samples(): ByteArray {
        val out = ByteArray(8)
        // 0x0000, 0x7FFF, 0xFFFF, 0x4000 as big-endian 16-bit samples.
        val values = intArrayOf(0x0000, 0x7FFF, 0xFFFF, 0x4000)
        values.forEachIndexed { index, value ->
            out[index * 2] = (value ushr 8).toByte()
            out[index * 2 + 1] = value.toByte()
        }
        return out
    }

    /** A fake but well-formed-enough DCT payload (never decoded by these tests). */
    val JPEG_BYTES: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        0xFF.toByte(), 0xD9.toByte()
    )

    val JPX_BYTES: ByteArray = byteArrayOf(
        0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20,
        0x0D, 0x0A, 0x87.toByte(), 0x0A
    )

    /** A stream payload that is not a valid image at all. */
    val GARBAGE: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    fun page(box: DoubleArray = doubleArrayOf(0.0, 0.0, 612.0, 792.0)): DoubleArray = box
}
