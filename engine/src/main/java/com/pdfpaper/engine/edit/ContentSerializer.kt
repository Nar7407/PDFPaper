package com.pdfpaper.engine.edit

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.model.PdfInlineImage
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.write.PdfSerializer
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Re-serialises parsed content commands (ISO 32000-1, §7.8.2) back to bytes.
 *
 * Operands keep their meaning through [PdfSerializer]; only the whitespace
 * between tokens changes. The inline-image alias table from §8.9.7 Table 93 is
 * applied on the way out so BI dictionaries stay abbreviated.
 */
internal object ContentSerializer {

    private val INLINE_ALIAS = mapOf(
        "BitsPerComponent" to "BPC",
        "ColorSpace" to "CS",
        "Decode" to "D",
        "DecodeParms" to "DP",
        "Filter" to "F",
        "Height" to "H",
        "ImageMask" to "IM",
        "Interpolate" to "I",
        "Width" to "W"
    )

    fun serialize(commands: List<ContentCommand>): ByteArray {
        val out = ByteArrayOutputStream(1024)
        write(commands, out)
        return out.toByteArray()
    }

    fun write(commands: List<ContentCommand>, out: ByteArrayOutputStream) {
        for (command in commands) {
            for (operand in command.operands) {
                if (operand is PdfInlineImage) {
                    writeInlineImage(operand, out)
                } else {
                    out.write(PdfSerializer.toBytes(operand))
                }
                out.write(' '.code)
            }
            out.write(command.operator.toByteArray(StandardCharsets.US_ASCII))
            out.write('\n'.code)
        }
    }

    private fun writeInlineImage(image: PdfInlineImage, out: ByteArrayOutputStream) {
        ascii("BI ", out)
        for ((key, value) in image.dict.entries) {
            val abbreviated = PdfName(INLINE_ALIAS[key.name] ?: key.name)
            out.write(PdfSerializer.toBytes(abbreviated))
            out.write(' '.code)
            out.write(PdfSerializer.toBytes(value))
            out.write(' '.code)
        }
        ascii("ID ", out)
        out.write(image.data)
        ascii("\nEI", out)
    }

    private fun ascii(text: String, out: ByteArrayOutputStream) {
        out.write(text.toByteArray(StandardCharsets.US_ASCII))
    }
}
