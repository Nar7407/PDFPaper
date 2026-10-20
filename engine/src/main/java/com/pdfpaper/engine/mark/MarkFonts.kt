package com.pdfpaper.engine.mark

import com.pdfpaper.engine.edit.Standard14
import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.edit.TrueTypeMetrics
import com.pdfpaper.engine.edit.WinAnsi
import com.pdfpaper.engine.edit.addStandardFont
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.write.Deflate
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfSerializer
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * A font prepared for text that PDFPaper adds: the resource name the page
 * uses, the font object itself, and the two things the writer needs — how to
 * put the text into a content stream and how wide it will be.
 */
internal class MarkTextFont(
    val resourceName: PdfName,
    val fontRef: PdfRef,
    private val writeOperand: (String, ByteArrayOutputStream) -> Unit,
    private val measure: (String, Double) -> Double
) {

    /** Writes the text as a PDF string operand (a literal or a hex string). */
    fun writeString(text: String, out: ByteArrayOutputStream) = writeOperand(text, out)

    /** The width of [text] in points at [size]. */
    fun width(text: String, size: Double): Double = measure(text, size)
}

/**
 * Builds the fonts for the added marks.
 *
 * The standard 14 fonts need no font program: the reader already has them,
 * and the text is written with the WinAnsi encoding (§9.6.2.2). They only
 * cover Latin, so for other scripts the caller hands over a TrueType file,
 * which is embedded as a Type0 font with an Identity-H encoding (§9.7.5) and
 * a ToUnicode map, so the text stays searchable and copyable.
 */
internal object MarkFonts {

    /** The standard-14 font, written with the WinAnsi encoding. */
    fun standard(
        builder: PdfDocumentBuilder,
        font: StandardFont,
        resourceName: String = "PF1"
    ): MarkTextFont {
        val ref = addStandardFont(builder, font)
        return MarkTextFont(
            resourceName = PdfName(resourceName),
            fontRef = ref,
            writeOperand = { text, out ->
                PdfSerializer.writeString(WinAnsi.encode(text), out)
            },
            measure = { text, size -> Standard14.width(font, text, size) }
        )
    }

    /**
     * The caller's TrueType font, embedded into the file.
     *
     * The codes are the font's own glyph numbers, so the text needs no
     * shaping table; characters the font does not have are drawn with the
     * question mark. Returns null when the file cannot be read as a font.
     */
    fun embedded(
        builder: PdfDocumentBuilder,
        spec: MarkFont.Embedded,
        texts: List<String>,
        resourceName: String = "PF1"
    ): MarkTextFont? {
        val metrics = runCatching { TrueTypeMetrics(spec.bytes) }.getOrNull() ?: return null
        if (metrics.numGlyphs <= 0) return null

        val used = LinkedHashMap<Int, Int>() // glyph id -> unicode code point
        val questionGlyph = metrics.gidFor('?'.code)
        for (text in texts) {
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                index += Character.charCount(codePoint)
                val gid = metrics.gidFor(codePoint)
                if (gid > 0) {
                    used[gid] = codePoint
                } else if (questionGlyph > 0) {
                    used[questionGlyph] = '?'.code
                }
            }
        }
        if (used.isEmpty()) return null

        val toUnicode = builder.addStream(PdfDict(emptyMap()), toUnicodeCMap(used))
        val descriptor = builder.add(
            PdfDict(
                linkedMapOf(
                    PdfName("Type") to PdfName("FontDescriptor"),
                    PdfName("FontName") to PdfName(spec.baseName),
                    PdfName("Flags") to PdfInteger(4), // symbolic
                    PdfName("FontBBox") to PdfArray(
                        listOf(
                            PdfInteger(metrics.scale1000(metrics.xMin).toLong()),
                            PdfInteger(metrics.scale1000(metrics.yMin).toLong()),
                            PdfInteger(metrics.scale1000(metrics.xMax).toLong()),
                            PdfInteger(metrics.scale1000(metrics.yMax).toLong())
                        )
                    ),
                    PdfName("ItalicAngle") to PdfInteger(0),
                    PdfName("Ascent") to PdfInteger(metrics.scale1000(metrics.ascent).toLong()),
                    PdfName("Descent") to PdfInteger(metrics.scale1000(metrics.descent).toLong()),
                    PdfName("CapHeight") to PdfInteger(metrics.scale1000(metrics.ascent).toLong()),
                    PdfName("StemV") to PdfInteger(80),
                    PdfName("FontFile2") to builder.addStream(
                        PdfDict(
                            linkedMapOf(
                                PdfName("Length1") to PdfInteger(spec.bytes.size.toLong()),
                                PdfName("Filter") to PdfName("FlateDecode")
                            )
                        ),
                        Deflate.encode(spec.bytes)
                    )
                )
            )
        )
        // /W [ gid [w] gid2 [w2] ... ] — the widths of the glyphs in use.
        val widthEntries = ArrayList<PdfObject>()
        for (gid in used.keys.sorted()) {
            widthEntries += PdfInteger(gid.toLong())
            widthEntries += PdfArray(
                listOf(PdfInteger(widthOf(metrics, gid, used.getValue(gid)).toLong()))
            )
        }
        val widths = PdfArray(widthEntries)
        val descendant = builder.add(
            PdfDict(
                linkedMapOf(
                    PdfName("Type") to PdfName("Font"),
                    PdfName("Subtype") to PdfName("CIDFontType2"),
                    PdfName("BaseFont") to PdfName(spec.baseName),
                    PdfName("CIDSystemInfo") to PdfDict(
                        linkedMapOf(
                            PdfName("Registry") to PdfString("Adobe".toByteArray(StandardCharsets.US_ASCII)),
                            PdfName("Ordering") to PdfString("Identity".toByteArray(StandardCharsets.US_ASCII)),
                            PdfName("Supplement") to PdfInteger(0)
                        )
                    ),
                    PdfName("FontDescriptor") to descriptor,
                    PdfName("DW") to PdfInteger(1000),
                    PdfName("W") to widths,
                    PdfName("CIDToGIDMap") to PdfName("Identity")
                )
            )
        )
        val fontRef = builder.add(
            PdfDict(
                linkedMapOf(
                    PdfName("Type") to PdfName("Font"),
                    PdfName("Subtype") to PdfName("Type0"),
                    PdfName("BaseFont") to PdfName(spec.baseName),
                    PdfName("Encoding") to PdfName("Identity-H"),
                    PdfName("DescendantFonts") to PdfArray(listOf(descendant)),
                    PdfName("ToUnicode") to toUnicode
                )
            )
        )

        return MarkTextFont(
            resourceName = PdfName(resourceName),
            fontRef = fontRef,
            writeOperand = { text, out ->
                out.write('<'.code)
                var index = 0
                while (index < text.length) {
                    val codePoint = text.codePointAt(index)
                    index += Character.charCount(codePoint)
                    val gid = metrics.gidFor(codePoint).takeIf { it > 0 } ?: questionGlyph
                    out.write("%04X".format(gid).toByteArray(StandardCharsets.US_ASCII))
                }
                out.write('>'.code)
            },
            measure = { text, size ->
                var total = 0.0
                var index = 0
                while (index < text.length) {
                    val codePoint = text.codePointAt(index)
                    index += Character.charCount(codePoint)
                    val gid = metrics.gidFor(codePoint).takeIf { it > 0 } ?: questionGlyph
                    total += widthOf(metrics, gid, codePoint) * size / 1000.0
                }
                total
            }
        )
    }

    private fun widthOf(metrics: TrueTypeMetrics, gid: Int, codePoint: Int): Int {
        val byCode = metrics.width1000(codePoint)
        if (byCode > 0) return byCode
        return metrics.scale1000(metrics.advance(gid))
    }

    /**
     * The ToUnicode map (§9.10.3): one `bfchar` entry per used glyph, so the
     * text can be searched and copied out of the finished file.
     */
    private fun toUnicodeCMap(used: Map<Int, Int>): ByteArray {
        val out = StringBuilder(256 + used.size * 24)
        out.append("/CIDInit /ProcSet findresource begin\n")
        out.append("12 dict begin\n")
        out.append("begincmap\n")
        out.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        out.append("/CMapName /Adobe-Identity-UCS def\n")
        out.append("/CMapType 2 def\n")
        out.append("1 begincodespacerange\n")
        out.append("<0000> <FFFF>\n")
        out.append("endcodespacerange\n")
        val entries = used.entries.sortedBy { it.key }
        for (block in entries.chunked(100)) {
            out.append("${block.size} beginbfchar\n")
            for (entry in block) {
                val codePoint = entry.value
                val unicode = if (codePoint > 0xFFFF) {
                    val high = Character.highSurrogate(codePoint)
                    val low = Character.lowSurrogate(codePoint)
                    "%04X%04X".format(high.code, low.code)
                } else {
                    "%04X".format(codePoint)
                }
                out.append("<%04X> <%s>\n".format(entry.key, unicode))
            }
            out.append("endbfchar\n")
        }
        out.append("endcmap\n")
        out.append("CMapName currentdict /CMap defineresource pop\n")
        out.append("end\n")
        out.append("end\n")
        return out.toString().toByteArray(StandardCharsets.US_ASCII)
    }
}
