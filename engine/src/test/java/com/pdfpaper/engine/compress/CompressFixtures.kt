package com.pdfpaper.engine.compress

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.write.PdfDocumentBuilder
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Test documents for the compressor: a text-only page, a scanned page with a
 * 300 dpi picture, a photo-heavy file and a file that uses the same picture
 * twice. The pictures are deterministic noise, so they behave like photos
 * (the deflate step cannot shrink them) and the tests stay reproducible.
 */
object CompressFixtures {

    /** One page of a test document. */
    class Page(
        val text: String? = null,
        val image: Image? = null,
        /** The size the image is drawn at, in points. */
        val imageSize: Pair<Double, Double>? = null
    )

    /** A raw RGB image with a deterministic pattern. */
    class Image(val width: Int, val height: Int, val seed: Int) {
        /** The uncompressed RGB samples. */
        fun samples(): ByteArray {
            val out = ByteArray(width * height * 3)
            var state = seed.toLong() or 1L
            var i = 0
            while (i < out.size) {
                state = state * 6364136223846793005L + 1442695040888963407L
                val value = ((state ushr 33) and 0xFF).toInt()
                // A little structure so the picture is not pure noise.
                val x = (i / 3) % width
                val y = (i / 3) / width
                val gradient = ((x + y) * 3) and 0xFF
                out[i] = ((value + gradient) and 0xFF).toByte()
                i++
            }
            return out
        }
    }

    /** Builds a PDF with the given pages. */
    fun build(pages: List<Page>, extraCatalog: Map<PdfName, PdfObject> = emptyMap()): ByteArray {
        val builder = PdfDocumentBuilder("1.7")
        val pagesRef = PdfRef(builder.allocate(), 0)
        val kids = ArrayList<PdfObject>()

        for (page in pages) {
            val pageRef = PdfRef(builder.allocate(), 0)
            val content = StringBuilder()
            val resources = LinkedHashMap<PdfName, PdfObject>()

            if (page.text != null) {
                content.append("BT /F1 24 Tf 72 700 Td (").append(page.text).append(") Tj ET\n")
                resources[PdfName("Font")] = PdfDict(
                    mapOf(
                        PdfName("F1") to PdfDict(
                            mapOf(
                                PdfName("Type") to PdfName("Font"),
                                PdfName("Subtype") to PdfName("Type1"),
                                PdfName("BaseFont") to PdfName("Helvetica"),
                                PdfName("Encoding") to PdfName("WinAnsiEncoding")
                            )
                        )
                    )
                )
            }

            if (page.image != null) {
                val imageRef = builder.addStream(
                    PdfDict(
                        mapOf(
                            PdfName("Type") to PdfName("XObject"),
                            PdfName("Subtype") to PdfName("Image"),
                            PdfName("Width") to PdfInteger(page.image.width.toLong()),
                            PdfName("Height") to PdfInteger(page.image.height.toLong()),
                            PdfName("ColorSpace") to PdfName("DeviceRGB"),
                            PdfName("BitsPerComponent") to PdfInteger(8),
                            PdfName("Filter") to PdfName("FlateDecode")
                        )
                    ),
                    deflate(page.image.samples())
                )
                val (width, height) = page.imageSize ?: (612.0 to 792.0)
                content.append("q ")
                    .append(width).append(" 0 0 ").append(height)
                    .append(" 0 0 cm /Im1 Do Q\n")
                resources[PdfName("XObject")] = PdfDict(mapOf(PdfName("Im1") to imageRef))
            }

            val contentRef = builder.addStream(
                PdfDict(mapOf(PdfName("Type") to PdfName("Content"))),
                content.toString().toByteArray(Charsets.US_ASCII)
            )

            val entries = LinkedHashMap<PdfName, PdfObject>()
            entries[PdfName("Type")] = PdfName("Page")
            entries[PdfName("Parent")] = pagesRef
            entries[PdfName("MediaBox")] = PdfArray(
                listOf(
                    PdfInteger(0),
                    PdfInteger(0),
                    PdfInteger(612),
                    PdfInteger(792)
                )
            )
            entries[PdfName("Resources")] = PdfDict(resources)
            entries[PdfName("Contents")] = contentRef
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

        val catalog = LinkedHashMap<PdfName, PdfObject>()
        catalog[PdfName("Type")] = PdfName("Catalog")
        catalog[PdfName("Pages")] = pagesRef
        catalog.putAll(extraCatalog)
        builder.root = builder.add(PdfDict(catalog))
        return builder.build()
    }

    /** A page with only text. */
    fun textOnly(): ByteArray = build(
        listOf(
            Page(text = "Hello PDFPaper"),
            Page(text = "Second page of text")
        )
    )

    /**
     * A scanned page: a 2550 x 3300 picture (300 dpi on a letter page) that
     * is drawn over the whole page.
     */
    fun scanned(): ByteArray = build(
        listOf(Page(image = Image(2550, 3300, seed = 7)))
    )

    /** Three photo pages, each with its own picture. */
    fun photoHeavy(): ByteArray = build(
        listOf(
            Page(image = Image(1600, 1200, seed = 11), imageSize = 612.0 to 459.0),
            Page(image = Image(1200, 1600, seed = 23), imageSize = 459.0 to 612.0),
            Page(image = Image(2000, 1500, seed = 41), imageSize = 612.0 to 459.0)
        )
    )

    /**
     * The same picture used on two pages, written as two separate objects:
     * the compressor has to notice that they are identical.
     */
    fun duplicatedImage(): ByteArray = build(
        listOf(
            Page(image = Image(800, 600, seed = 5)),
            Page(image = Image(800, 600, seed = 5))
        )
    )

    /** A picture whose data is broken: the run must not fall over. */
    fun brokenImage(): ByteArray {
        val builder = PdfDocumentBuilder("1.7")
        val pagesRef = PdfRef(builder.allocate(), 0)
        val pageRef = PdfRef(builder.allocate(), 0)
        val imageRef = builder.addStream(
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("XObject"),
                    PdfName("Subtype") to PdfName("Image"),
                    PdfName("Width") to PdfInteger(64),
                    PdfName("Height") to PdfInteger(64),
                    PdfName("ColorSpace") to PdfName("DeviceRGB"),
                    PdfName("BitsPerComponent") to PdfInteger(8),
                    PdfName("Filter") to PdfName("FlateDecode")
                )
            ),
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        )
        val contentRef = builder.addStream(
            PdfDict(mapOf(PdfName("Type") to PdfName("Content"))),
            "q 612 0 0 792 0 0 cm /Im1 Do Q".toByteArray(Charsets.US_ASCII)
        )
        builder.put(
            pageRef.objNum,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Page"),
                    PdfName("Parent") to pagesRef,
                    PdfName("MediaBox") to PdfArray(
                        listOf(PdfInteger(0), PdfInteger(0), PdfInteger(612), PdfInteger(792))
                    ),
                    PdfName("Resources") to PdfDict(
                        mapOf(PdfName("XObject") to PdfDict(mapOf(PdfName("Im1") to imageRef)))
                    ),
                    PdfName("Contents") to contentRef
                )
            )
        )
        builder.put(
            pagesRef.objNum,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Pages"),
                    PdfName("Kids") to PdfArray(listOf(pageRef)),
                    PdfName("Count") to PdfInteger(1)
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

    /** A document with a signature field in its form. */
    fun withSignatureField(): ByteArray {
        val builder = PdfDocumentBuilder("1.7")
        val pagesRef = PdfRef(builder.allocate(), 0)
        val pageRef = PdfRef(builder.allocate(), 0)
        val contentRef = builder.addStream(
            PdfDict(mapOf(PdfName("Type") to PdfName("Content"))),
            "BT /F1 24 Tf 72 700 Td (Signed document) Tj ET".toByteArray(Charsets.US_ASCII)
        )
        val fieldRef = builder.add(
            PdfDict(
                mapOf(
                    PdfName("FT") to PdfName("Sig"),
                    PdfName("T") to PdfString("Signature1"),
                    PdfName("Type") to PdfName("Annot"),
                    PdfName("Subtype") to PdfName("Widget"),
                    PdfName("Rect") to PdfArray(
                        listOf(PdfInteger(72), PdfInteger(600), PdfInteger(300), PdfInteger(640))
                    ),
                    PdfName("F") to PdfInteger(4)
                )
            )
        )
        builder.put(
            pageRef.objNum,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Page"),
                    PdfName("Parent") to pagesRef,
                    PdfName("MediaBox") to PdfArray(
                        listOf(PdfInteger(0), PdfInteger(0), PdfInteger(612), PdfInteger(792))
                    ),
                    PdfName("Resources") to PdfDict(
                        mapOf(
                            PdfName("Font") to PdfDict(
                                mapOf(
                                    PdfName("F1") to PdfDict(
                                        mapOf(
                                            PdfName("Type") to PdfName("Font"),
                                            PdfName("Subtype") to PdfName("Type1"),
                                            PdfName("BaseFont") to PdfName("Helvetica")
                                        )
                                    )
                                )
                            )
                        )
                    ),
                    PdfName("Contents") to contentRef,
                    PdfName("Annots") to PdfArray(listOf(fieldRef))
                )
            )
        )
        builder.put(
            pagesRef.objNum,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Pages"),
                    PdfName("Kids") to PdfArray(listOf(pageRef)),
                    PdfName("Count") to PdfInteger(1)
                )
            )
        )
        val acroForm = builder.add(
            PdfDict(
                mapOf(
                    PdfName("Fields") to PdfArray(listOf(fieldRef))
                )
            )
        )
        builder.root = builder.add(
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Catalog"),
                    PdfName("Pages") to pagesRef,
                    PdfName("AcroForm") to acroForm
                )
            )
        )
        return builder.build()
    }

    /** zlib compression for the fixtures. */
    fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buffer = ByteArray(16 * 1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            if (count <= 0) break
            out.write(buffer, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }
}
