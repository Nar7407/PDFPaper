package com.pdfpaper.engine.edit

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.write.PdfDocumentBuilder
import java.nio.charset.StandardCharsets

/**
 * A shared glyph-less Type0 font for invisible OCR text (task T25,
 * ISO 32000-1 §9.7.5, §9.10.3).
 *
 * Codes are two-byte CIDs taken directly from the Unicode code point
 * (Identity-H, §9.7.5.2), so no per-page code allocation is needed. Every CID
 * has a one-em advance from /DW; the OCR writer picks a horizontal scale (Tz)
 * that stretches each word to its recognized box. The ToUnicode CMap maps each
 * CID back to its character, which makes search, selection and copy-export
 * return the real text.
 *
 * No font program is embedded: the text is drawn with render mode 3
 * (§9.3.6), so nothing is painted and viewers only need the metrics. The font
 * is created once per save and shared by every page.
 */
internal class OcrTextFont(private val builder: PdfDocumentBuilder) {

    /** Object number reserved up front; the body is written by [finish]. */
    private val fontNumber = builder.allocate()
    val fontRef: PdfRef = PdfRef(fontNumber, 0)

    private val codes = sortedSetOf<Int>()
    var used = false
        private set

    /**
     * Encodes [text] as Identity-H CIDs. Characters outside the Basic
     * Multilingual Plane (surrogate pairs) and control characters are skipped;
     * callers pass one word of already-normalised OCR text.
     */
    fun encode(text: String): ByteArray {
        val out = ByteArray(text.length * 2)
        var at = 0
        for (ch in text) {
            val code = ch.code
            if (code < 0x20 || code in 0xD800..0xDFFF) continue
            if (code in 0x7F..0x9F) continue
            codes += code
            out[at++] = (code ushr 8).toByte()
            out[at++] = code.toByte()
        }
        if (at == 0) return ByteArray(0)
        used = true
        return if (at == out.size) out else out.copyOf(at)
    }

    /** Writes the font objects; must run once all pages have encoded text. */
    fun finish() {
        if (!used) return
        val toUnicode = builder.addStream(PdfDict(emptyMap()), toUnicodeCMap())
        val descriptor = builder.add(
            PdfDict(
                linkedMapOf(
                    PdfName("Type") to PdfName("FontDescriptor"),
                    PdfName("FontName") to PdfName(BASE_FONT),
                    PdfName("Flags") to PdfInteger(32), // non-symbolic (Table 123)
                    PdfName("FontBBox") to PdfArray(
                        listOf(
                            PdfInteger(-1000), PdfInteger(-1000),
                            PdfInteger(2000), PdfInteger(2000)
                        )
                    ),
                    PdfName("ItalicAngle") to PdfInteger(0),
                    PdfName("Ascent") to PdfInteger(800),
                    PdfName("Descent") to PdfInteger(-200),
                    PdfName("CapHeight") to PdfInteger(700),
                    PdfName("StemV") to PdfInteger(80)
                )
            )
        )
        val descendant = builder.add(
            PdfDict(
                linkedMapOf(
                    PdfName("Type") to PdfName("Font"),
                    PdfName("Subtype") to PdfName("CIDFontType2"),
                    PdfName("BaseFont") to PdfName(BASE_FONT),
                    PdfName("CIDSystemInfo") to PdfDict(
                        linkedMapOf(
                            PdfName("Registry") to PdfString(ascii("Adobe")),
                            PdfName("Ordering") to PdfString(ascii("Identity")),
                            PdfName("Supplement") to PdfInteger(0)
                        )
                    ),
                    PdfName("FontDescriptor") to descriptor,
                    PdfName("DW") to PdfInteger(1000),
                    PdfName("CIDToGIDMap") to PdfName("Identity")
                )
            )
        )
        builder.put(
            fontNumber,
            PdfDict(
                linkedMapOf(
                    PdfName("Type") to PdfName("Font"),
                    PdfName("Subtype") to PdfName("Type0"),
                    PdfName("BaseFont") to PdfName(BASE_FONT),
                    PdfName("Encoding") to PdfName("Identity-H"),
                    PdfName("DescendantFonts") to PdfArray(listOf(descendant)),
                    PdfName("ToUnicode") to toUnicode
                )
            )
        )
    }

    /**
     * §9.10.3 CMap: a two-byte code space and one `bfchar` entry per used
     * character. The spec limits a `bfchar` block to 100 entries, so larger
     * sets are split into several blocks.
     */
    private fun toUnicodeCMap(): ByteArray {
        val out = StringBuilder(256 + codes.size * 24)
        out.append("/CIDInit /ProcSet findresource begin\n")
        out.append("12 dict begin\n")
        out.append("begincmap\n")
        out.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        out.append("/CMapName /Adobe-Identity-UCS def\n")
        out.append("/CMapType 2 def\n")
        out.append("1 begincodespacerange\n")
        out.append("<0000> <FFFF>\n")
        out.append("endcodespacerange\n")
        for (block in codes.chunked(100)) {
            out.append("${block.size} beginbfchar\n")
            for (code in block) out.append("<%04X> <%04X>\n".format(code, code))
            out.append("endbfchar\n")
        }
        out.append("endcmap\n")
        out.append("CMapName currentdict /CMap defineresource pop\n")
        out.append("end\n")
        out.append("end\n")
        return out.toString().toByteArray(StandardCharsets.US_ASCII)
    }

    private fun ascii(value: String): ByteArray = value.toByteArray(StandardCharsets.US_ASCII)

    private companion object {
        const val BASE_FONT = "PDFPaperOCR"
    }
}
