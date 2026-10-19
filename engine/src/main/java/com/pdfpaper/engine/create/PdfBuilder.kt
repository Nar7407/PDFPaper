package com.pdfpaper.engine.create

import com.pdfpaper.engine.edit.ImageData
import com.pdfpaper.engine.edit.ImageXObjects
import com.pdfpaper.engine.edit.Standard14
import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.edit.WinAnsi
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfSerializer
import java.nio.charset.StandardCharsets
import java.util.IdentityHashMap

/**
 * Creates a new PDF document from scratch (ISO 32000-1 §7.5, §7.7, §8).
 *
 * The document has a default [pageSize] and [orientation]; [addPage] appends
 * pages that inherit them or override either. Pages are painted through
 * [PdfPageBuilder] with text, images, lines and rectangles. [metadata] is
 * written to the /Info dictionary.
 *
 * The output uses only standard-14 fonts and writes their AFM widths, so no
 * font program is embedded and every viewer can open it. Content streams are
 * uncompressed ASCII; image data is embedded by the engine's image writer.
 *
 * Usage:
 * ```
 * val pdf = PdfBuilder(PageSize.A4)
 *     .apply { addPage().drawText("Hello", 72.0, 72.0, 24.0) }
 *     .build()
 * ```
 */
class PdfBuilder(
    val pageSize: PageSize = PageSize.A4,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
    var metadata: PdfMetadata = PdfMetadata()
) {

    private val pages = ArrayList<PdfPageBuilder>()

    val pageCount: Int get() = pages.size

    /**
     * Appends a page. [size] and [orientation] default to the document's
     * values; passing either overrides it for this page only.
     */
    fun addPage(
        size: PageSize = pageSize,
        orientation: PageOrientation = this.orientation
    ): PdfPageBuilder {
        val page = PdfPageBuilder(size, orientation)
        pages += page
        return page
    }

    /**
     * Serialises the document. At least one page is required; a document with
     * no pages would have nothing for a viewer to show.
     */
    fun build(): ByteArray {
        require(pages.isNotEmpty()) { "a PDF needs at least one page" }

        val document = PdfDocumentBuilder()
        val pagesNumber = document.allocate()
        val pagesRef = PdfRef(pagesNumber, 0)
        document.root = document.add(
            PdfDict(
                linkedMapOf(
                    PdfName("Type") to PdfName("Catalog"),
                    PdfName("Pages") to pagesRef
                )
            )
        )
        writeInfo(document)

        val fonts = HashMap<StandardFont, PdfRef>()
        val images = IdentityHashMap<ImageData, PdfRef>()
        val kids = ArrayList<PdfObject>()

        for (page in pages) {
            val usedFonts = LinkedHashSet<StandardFont>()
            val usedImages = ArrayList<ImageData>()
            for (op in page.ops) {
                when (op) {
                    is DrawOp.Text -> usedFonts += op.font
                    is DrawOp.Image -> if (usedImages.none { it === op.image }) usedImages += op.image
                    else -> {}
                }
            }

            val fontNames = LinkedHashMap<StandardFont, String>()
            usedFonts.forEachIndexed { index, font ->
                fontNames[font] = "F${index + 1}"
                fonts.getOrPut(font) { addFont(document, font) }
            }
            val imageNames = LinkedHashMap<ImageData, String>()
            usedImages.forEachIndexed { index, image ->
                imageNames[image] = "Im${index + 1}"
                images.getOrPut(image) { ImageXObjects.create(document, image) }
            }

            val resources = LinkedHashMap<PdfName, PdfObject>()
            if (fontNames.isNotEmpty()) {
                resources[PdfName("Font")] = PdfDict(
                    fontNames.entries.associate { (font, name) ->
                        PdfName(name) to fonts.getValue(font)
                    }
                )
            }
            if (imageNames.isNotEmpty()) {
                resources[PdfName("XObject")] = PdfDict(
                    imageNames.entries.associate { (image, name) ->
                        PdfName(name) to images.getValue(image)
                    }
                )
            }
            if (fontNames.isNotEmpty() || imageNames.isNotEmpty()) {
                resources[PdfName("ProcSet")] = PdfArray(
                    listOf(PdfName("PDF"), PdfName("Text"), PdfName("ImageB"), PdfName("ImageC"), PdfName("ImageI"))
                )
            }

            val content = render(page, fontNames, imageNames)
            val contentRef = document.addStream(
                PdfDict(linkedMapOf(PdfName("Length") to PdfInteger(content.size.toLong()))),
                content
            )
            val pageRef = document.add(
                PdfDict(
                    linkedMapOf(
                        PdfName("Type") to PdfName("Page"),
                        PdfName("Parent") to pagesRef,
                        PdfName("MediaBox") to PdfArray(
                            listOf(
                                PdfInteger(0),
                                PdfInteger(0),
                                PdfReal(round(page.width)),
                                PdfReal(round(page.height))
                            )
                        ),
                        PdfName("Resources") to PdfDict(resources),
                        PdfName("Contents") to contentRef
                    )
                )
            )
            kids += pageRef
        }

        document.put(
            pagesNumber,
            PdfDict(
                linkedMapOf(
                    PdfName("Type") to PdfName("Pages"),
                    PdfName("Kids") to PdfArray(kids),
                    PdfName("Count") to PdfInteger(kids.size.toLong())
                )
            )
        )
        return document.build()
    }

    // -- Info -----------------------------------------------------------------

    private fun writeInfo(document: PdfDocumentBuilder) {
        val entries = LinkedHashMap<PdfName, PdfObject>()
        fun put(key: String, value: String?) {
            value?.takeIf { it.isNotBlank() }?.let { entries[PdfName(key)] = pdfText(it) }
        }
        put("Title", metadata.title)
        put("Author", metadata.author)
        put("Subject", metadata.subject)
        put("Keywords", metadata.keywords)
        put("Creator", metadata.creator)
        put("Producer", metadata.producer)
        if (entries.isNotEmpty()) {
            document.info = document.add(PdfDict(entries))
        }
    }

    /** Latin-1 text stays readable; anything else uses UTF-16BE with a BOM. */
    private fun pdfText(value: String): PdfString =
        if (value.all { it.code in 32..126 }) {
            PdfString(value.toByteArray(StandardCharsets.US_ASCII))
        } else {
            PdfString(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + value.toByteArray(Charsets.UTF_16BE))
        }

    // -- Fonts ----------------------------------------------------------------

    /** A standard-font dictionary with AFM widths (§9.6.2.2, Table 111). */
    private fun addFont(document: PdfDocumentBuilder, font: StandardFont): PdfRef {
        val widths = PdfArray(
            (32..255).map { code -> PdfInteger(Standard14.width1000(font, code).toLong()) }
        )
        return document.add(
            PdfDict(
                linkedMapOf(
                    PdfName("Type") to PdfName("Font"),
                    PdfName("Subtype") to PdfName("Type1"),
                    PdfName("BaseFont") to PdfName(font.baseFont),
                    PdfName("Encoding") to PdfName("WinAnsiEncoding"),
                    PdfName("FirstChar") to PdfInteger(32),
                    PdfName("LastChar") to PdfInteger(255),
                    PdfName("Widths") to widths
                )
            )
        )
    }

    // -- Content --------------------------------------------------------------

    /** One ASCII content stream per page; PDF y is up, the page API is top-down. */
    private fun render(
        page: PdfPageBuilder,
        fontNames: Map<StandardFont, String>,
        imageNames: Map<ImageData, String>
    ): ByteArray {
        val out = StringBuilder()
        val pageHeight = page.height
        fun y(topDown: Double): String = number(pageHeight - topDown)

        for (op in page.ops) {
            when (op) {
                is DrawOp.Text -> {
                    val name = fontNames[op.font] ?: continue
                    out.append("BT\n")
                    out.append('/').append(name).append(' ').append(number(op.size)).append(" Tf\n")
                    color(out, op.color)
                    out.append(" rg\n")
                    out.append("1 0 0 1 ").append(number(op.x)).append(' ')
                        .append(y(op.baselineY)).append(" Tm\n")
                    hex(out, op.text)
                    out.append(" Tj\nET\n")
                }

                is DrawOp.Image -> {
                    val name = imageNames[op.image] ?: continue
                    out.append("q\n")
                    op.clip?.let { clip ->
                        out.append(number(clip.x)).append(' ').append(y(clip.y + clip.height)).append(' ')
                            .append(number(clip.width)).append(' ').append(number(clip.height))
                            .append(" re W n\n")
                    }
                    out.append(number(op.width)).append(" 0 0 ").append(number(op.height)).append(' ')
                        .append(number(op.x)).append(' ').append(y(op.y + op.height)).append(" cm\n")
                    out.append('/').append(name).append(" Do\nQ\n")
                }

                is DrawOp.Line -> {
                    out.append("q\n")
                    out.append(number(op.width)).append(" w 1 J 1 j\n")
                    color(out, op.color)
                    out.append(" RG\n")
                    out.append(number(op.x1)).append(' ').append(y(op.y1)).append(" m ")
                    out.append(number(op.x2)).append(' ').append(y(op.y2)).append(" l S\nQ\n")
                }

                is DrawOp.Rect -> {
                    out.append("q\n")
                    op.fill?.let {
                        color(out, it)
                        out.append(" rg\n")
                    }
                    op.stroke?.let {
                        out.append(number(op.strokeWidth)).append(" w\n")
                        color(out, it)
                        out.append(" RG\n")
                    }
                    out.append(number(op.x)).append(' ').append(y(op.y + op.height)).append(' ')
                        .append(number(op.width)).append(' ').append(number(op.height)).append(" re\n")
                    out.append(
                        when {
                            op.fill != null && op.stroke != null -> "B\n"
                            op.fill != null -> "f\n"
                            else -> "S\n"
                        }
                    )
                    out.append("Q\n")
                }
            }
        }
        return out.toString().toByteArray(StandardCharsets.US_ASCII)
    }

    private fun color(out: StringBuilder, color: PdfColor) {
        out.append(number(color.red.coerceIn(0.0, 1.0))).append(' ')
            .append(number(color.green.coerceIn(0.0, 1.0))).append(' ')
            .append(number(color.blue.coerceIn(0.0, 1.0)))
    }

    /** WinAnsi hex string (§7.3.4.3), so no delimiter can leak from the text. */
    private fun hex(out: StringBuilder, text: String) {
        out.append('<')
        for (byte in WinAnsi.encode(text)) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        out.append('>')
    }

    private fun number(value: Double): String = PdfSerializer.formatReal(value)

    private fun round(value: Double): Double = Math.round(value * 1000.0) / 1000.0

    private companion object {
        const val HEX = "0123456789ABCDEF"
    }
}
