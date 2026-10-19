package com.pdfpaper.convert.pdf

import com.pdfpaper.convert.raster.Zlib
import com.pdfpaper.convert.word.ImageOp
import com.pdfpaper.convert.word.LayoutPage
import com.pdfpaper.convert.word.PdfOp
import com.pdfpaper.convert.word.RuleOp
import com.pdfpaper.convert.word.TextOp
import com.pdfpaper.convert.word.WordColor
import com.pdfpaper.convert.word.WordImage
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.write.PdfDocumentBuilder

/**
 * Writes the laid-out pages into a PDF file (ISO 32000-1 §7.5).
 *
 * The file is assembled through the engine's [PdfDocumentBuilder] — the same
 * serializer the page operations use — and every part of it is written here:
 * the page tree, one content stream per page, standard-font dictionaries with
 * their widths, and image XObjects. Content streams use only ASCII operators
 * and hex strings, so a text encoding mistake cannot produce an unreadable
 * stream.
 */
internal object PdfDocumentWriter {

    fun write(pages: List<LayoutPage>, title: String?, producer: String = "PDFPaper"): ByteArray {
        val builder = PdfDocumentBuilder()
        val pagesNumber = builder.allocate()
        val pagesRef = PdfRef(pagesNumber, 0)
        builder.root = builder.add(
            PdfDict(
                linkedMapOf(
                    PdfName("Type") to PdfName("Catalog"),
                    PdfName("Pages") to pagesRef
                )
            )
        )

        val info = LinkedHashMap<PdfName, PdfObject>()
        info[PdfName("Producer")] = text(producer)
        info[PdfName("Creator")] = text(producer)
        title?.takeIf { it.isNotBlank() }?.let { info[PdfName("Title")] = text(it) }
        builder.info = builder.add(PdfDict(info))

        val fonts = HashMap<StandardFont, PdfRef>()
        val images = HashMap<WordImage, PdfRef>()
        val kids = ArrayList<PdfObject>()

        for (page in pages) {
            val usedFonts = LinkedHashSet<StandardFont>()
            val usedImages = LinkedHashSet<WordImage>()
            for (op in page.ops) {
                when (op) {
                    is TextOp -> usedFonts += op.font
                    is ImageOp -> usedImages += op.image
                    else -> {}
                }
            }
            val fontNames = LinkedHashMap<StandardFont, String>()
            usedFonts.sortedBy { it.ordinal }.forEachIndexed { index, font ->
                fontNames[font] = "F${index + 1}"
                fonts.getOrPut(font) { fontDictionary(builder, font) }
            }
            val imageNames = LinkedHashMap<WordImage, String>()
            usedImages.sortedBy { it.partName }.forEachIndexed { index, image ->
                imageNames[image] = "Im${index + 1}"
                images.getOrPut(image) {
                    when {
                        image.jpeg != null -> PdfImageXObjects.create(builder, image.jpeg)
                        image.raster != null -> PdfImageXObjects.create(builder, image.raster)
                        else -> error("picture ${image.partName} has no pixels")
                    }
                }
            }

            val resources = LinkedHashMap<PdfName, PdfObject>()
            if (fontNames.isNotEmpty()) {
                resources[PdfName("Font")] = PdfDict(
                    fontNames.map { (font, name) -> PdfName(name) to (fonts.getValue(font) as PdfObject) }.toMap()
                )
            }
            if (imageNames.isNotEmpty()) {
                resources[PdfName("XObject")] = PdfDict(
                    imageNames.map { (image, name) -> PdfName(name) to (images.getValue(image) as PdfObject) }.toMap()
                )
            }

            val content = content(page, fontNames, imageNames)
            val contentRef = builder.addStream(
                PdfDict(
                    linkedMapOf(
                        PdfName("Filter") to PdfName("FlateDecode"),
                        PdfName("Length") to PdfInteger(content.size.toLong())
                    )
                ),
                Zlib.encode(content)
            )
            val pageRef = builder.add(
                PdfDict(
                    linkedMapOf(
                        PdfName("Type") to PdfName("Page"),
                        PdfName("Parent") to pagesRef,
                        PdfName("MediaBox") to PdfArray(
                            listOf(
                                PdfInteger(0), PdfInteger(0),
                                PdfReal(round(page.width)), PdfReal(round(page.height))
                            )
                        ),
                        PdfName("Resources") to PdfDict(resources),
                        PdfName("Contents") to contentRef
                    )
                )
            )
            kids += pageRef
        }

        builder.put(
            pagesNumber,
            PdfDict(
                linkedMapOf(
                    PdfName("Type") to PdfName("Pages"),
                    PdfName("Kids") to PdfArray(kids),
                    PdfName("Count") to PdfInteger(kids.size.toLong())
                )
            )
        )
        return builder.build()
    }

    /** The page's content stream: ASCII operators, hex strings, flipped y. */
    private fun content(
        page: LayoutPage,
        fontNames: Map<StandardFont, String>,
        imageNames: Map<WordImage, String>
    ): ByteArray {
        val out = StringBuilder()
        for (op in page.ops) {
            when (op) {
                is TextOp -> {
                    val name = fontNames[op.font] ?: continue
                    out.append("BT\n")
                    out.append('/').append(name).append(' ').append(number(op.size)).append(" Tf\n")
                    out.append(color(op.color)).append(" rg\n")
                    if (op.tracking != 0.0) {
                        // §9.3.2/§9.4.4: Tc is added to every glyph's advance in
                        // text space units (points), not in 1/1000 em.
                        out.append(number(op.tracking)).append(" Tc\n")
                    }
                    out.append("1 0 0 1 ")
                        .append(number(op.x)).append(' ')
                        .append(number(page.height - op.baseline)).append(" Tm\n")
                    out.append(textArray(op)).append('\n')
                    out.append("ET\n")
                    val advance = StandardFonts.width(op.font, op.text, op.size) +
                        op.tracking * op.text.length +
                        op.wordSpacing * op.text.count { it == ' ' }
                    if (op.underline) {
                        rule(
                            out, op.x, op.baseline + op.size * 0.1, advance, (op.size * 0.05).coerceAtLeast(0.3),
                            op.color, page.height
                        )
                    }
                    if (op.strike) {
                        rule(
                            out, op.x, op.baseline - op.size * 0.3, advance, (op.size * 0.05).coerceAtLeast(0.3),
                            op.color, page.height
                        )
                    }
                }
                is RuleOp -> rule(out, op.x, op.y, op.width, op.height, op.color, page.height)
                is ImageOp -> {
                    val name = imageNames[op.image] ?: continue
                    out.append("q ")
                        .append(number(op.width)).append(" 0 0 ").append(number(op.height)).append(' ')
                        .append(number(op.x)).append(' ')
                        .append(number(page.height - op.y - op.height)).append(" cm /")
                        .append(name).append(" Do Q\n")
                }
            }
        }
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    /** A `Tj`, or a `TJ` with a justification adjustment after every space. */
    private fun textArray(op: TextOp): String {
        val chunks = ArrayList<String>()
        var start = 0
        for (index in op.text.indices) {
            if (op.text[index] == ' ') {
                chunks += op.text.substring(start, index + 1)
                start = index + 1
            }
        }
        if (start < op.text.length) chunks += op.text.substring(start)
        if (chunks.size <= 1 || op.wordSpacing == 0.0 || op.size <= 0) {
            return "${hex(op.text)} Tj"
        }
        val adjustment = -op.wordSpacing * 1000.0 / op.size
        val out = StringBuilder("[")
        for ((index, chunk) in chunks.withIndex()) {
            out.append(hex(chunk))
            if (index < chunks.size - 1 || chunk.endsWith(" ")) {
                out.append(' ').append(number(adjustment))
            }
            if (index < chunks.size - 1) out.append(' ')
        }
        out.append("] TJ")
        return out.toString()
    }

    private fun rule(
        out: StringBuilder,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        color: WordColor,
        pageHeight: Double
    ) {
        if (width <= 0.0 || height <= 0.0) return
        out.append(color(color)).append(" rg ")
            .append(number(x)).append(' ')
            .append(number(pageHeight - y - height)).append(' ')
            .append(number(width)).append(' ')
            .append(number(height)).append(" re f\n")
    }

    private fun color(color: WordColor): String = "${number(color.red / 255.0)} " +
        "${number(color.green / 255.0)} ${number(color.blue / 255.0)}"

    /** A standard-font dictionary with the widths a reader needs (§9.6.2.2). */
    private fun fontDictionary(builder: PdfDocumentBuilder, font: StandardFont): PdfRef {
        val widths = PdfArray(
            (32..255).map { code -> PdfInteger(StandardFonts.width1000(font, code).toLong()) }
        )
        return builder.add(
            PdfDict(
                linkedMapOf(
                    PdfName("Type") to PdfName("Font"),
                    PdfName("Subtype") to PdfName("Type1"),
                    PdfName("BaseFont") to PdfName(font.baseName),
                    PdfName("Encoding") to PdfName("WinAnsiEncoding"),
                    PdfName("FirstChar") to PdfInteger(32),
                    PdfName("LastChar") to PdfInteger(255),
                    PdfName("Widths") to widths
                )
            )
        )
    }

    /** Hex string of the WinAnsi bytes of [text] (§7.3.4.3). */
    private fun hex(text: String): String {
        val bytes = WinAnsi.encode(text)
        val out = StringBuilder(bytes.size * 2 + 2)
        out.append('<')
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append("0123456789ABCDEF"[value shr 4])
            out.append("0123456789ABCDEF"[value and 0x0F])
        }
        out.append('>')
        return out.toString()
    }

    private fun text(value: String): PdfString =
        if (value.all { it.code in 32..126 }) {
            PdfString(value.toByteArray(Charsets.US_ASCII))
        } else {
            PdfString(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + value.toByteArray(Charsets.UTF_16BE))
        }

    private fun round(value: Double): Double = Math.round(value * 1000.0) / 1000.0

    /** A PDF real that never uses exponent notation (§7.3.3). */
    private fun number(value: Double): String {
        if (!value.isFinite()) return "0"
        val rounded = round(value)
        if (rounded == Math.floor(rounded) && kotlin.math.abs(rounded) < 1e9) {
            return rounded.toLong().toString()
        }
        return java.math.BigDecimal(rounded)
            .setScale(3, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }
}
