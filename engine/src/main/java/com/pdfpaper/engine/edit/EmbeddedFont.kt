package com.pdfpaper.engine.edit

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.write.PdfDocumentBuilder

/**
 * The fallback TrueType font bundled with the engine (Noto Sans Regular, SIL
 * Open Font License 1.1 — see NotoSans-OFL.txt). It is embedded whole as
 * /FontFile2 with WinAnsi widths taken from the font's own `hmtx` table, so a
 * saved file renders without the viewer having the font (§9.6.5, §9.8.2).
 */
internal object EmbeddedFont {

    const val BASE_FONT = "NotoSans-Regular"
    private const val RESOURCE = "/com/pdfpaper/engine/edit/NotoSans-Regular.ttf"
    private const val FIRST_CHAR = 32
    private const val LAST_CHAR = 255

    private val bytes: ByteArray? by lazy {
        EmbeddedFont::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
    }
    private val metrics: TrueTypeMetrics? by lazy {
        bytes?.let { runCatching { TrueTypeMetrics(it) }.getOrNull() }
    }

    val available: Boolean get() = bytes != null && metrics != null

    fun fontDict(builder: PdfDocumentBuilder): PdfDict {
        val fontBytes = bytes ?: error("fallback font NotoSans-Regular.ttf is not bundled")
        val m = metrics ?: error("fallback font NotoSans-Regular.ttf could not be read")

        val widths = PdfArray(
            (FIRST_CHAR..LAST_CHAR).map { code ->
                PdfInteger(m.width1000(WinAnsi.unicodeOf(code)).toLong())
            }
        )
        val descriptor = PdfDict(
            linkedMapOf<PdfName, PdfObject>(
                PdfName("Type") to PdfName("FontDescriptor"),
                PdfName("FontName") to PdfName(BASE_FONT),
                PdfName("Flags") to PdfInteger(32),
                PdfName("FontBBox") to PdfArray(
                    listOf(
                        PdfInteger(m.scale1000(m.xMin).toLong()),
                        PdfInteger(m.scale1000(m.yMin).toLong()),
                        PdfInteger(m.scale1000(m.xMax).toLong()),
                        PdfInteger(m.scale1000(m.yMax).toLong())
                    )
                ),
                PdfName("ItalicAngle") to PdfInteger(0),
                PdfName("Ascent") to PdfInteger(m.scale1000(m.ascent).toLong()),
                PdfName("Descent") to PdfInteger(m.scale1000(m.descent).toLong()),
                PdfName("CapHeight") to PdfInteger(m.scale1000(m.ascent).toLong()),
                PdfName("StemV") to PdfInteger(80),
                PdfName("FontFile2") to fontFile(builder, fontBytes)
            )
        )
        val descriptorRef = builder.add(descriptor)

        return PdfDict(
            linkedMapOf<PdfName, PdfObject>(
                PdfName("Type") to PdfName("Font"),
                PdfName("Subtype") to PdfName("TrueType"),
                PdfName("BaseFont") to PdfName(BASE_FONT),
                PdfName("FirstChar") to PdfInteger(FIRST_CHAR.toLong()),
                PdfName("LastChar") to PdfInteger(LAST_CHAR.toLong()),
                PdfName("Widths") to widths,
                PdfName("Encoding") to PdfName("WinAnsiEncoding"),
                PdfName("FontDescriptor") to descriptorRef
            )
        )
    }

    private fun fontFile(builder: PdfDocumentBuilder, fontBytes: ByteArray) = builder.addStream(
        PdfDict(
            linkedMapOf<PdfName, PdfObject>(
                PdfName("Length1") to PdfInteger(fontBytes.size.toLong()),
                PdfName("Filter") to PdfName("FlateDecode")
            )
        ),
        Flate.encode(fontBytes)
    )
}
