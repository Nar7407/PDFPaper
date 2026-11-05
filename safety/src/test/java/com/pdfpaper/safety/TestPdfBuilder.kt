package com.pdfpaper.safety

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/** A tiny valid classic-xref PDF writer used only by the safety tests. */
internal class TestPdfBuilder {

    private val bodies = ArrayList<ByteArray>()

    fun add(body: String): Int {
        bodies += body.toByteArray(StandardCharsets.ISO_8859_1)
        return bodies.size
    }

    fun addStream(dict: String, data: ByteArray): Int {
        val out = ByteArrayOutputStream()
        out.write(dict.toByteArray(StandardCharsets.ISO_8859_1))
        out.write('\n'.code)
        out.write("stream\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(data)
        out.write("\nendstream".toByteArray(StandardCharsets.ISO_8859_1))
        bodies += out.toByteArray()
        return bodies.size
    }

    fun set(number: Int, body: String) {
        require(number in 1..bodies.size) { "No object $number" }
        bodies[number - 1] = body.toByteArray(StandardCharsets.ISO_8859_1)
    }

    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeText(text: String) {
            out.write(text.toByteArray(StandardCharsets.ISO_8859_1))
        }
        writeText("%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n")
        val offsets = IntArray(bodies.size)
        bodies.forEachIndexed { index, body ->
            offsets[index] = out.size()
            writeText("${index + 1} 0 obj\n")
            out.write(body)
            writeText("\nendobj\n")
        }
        val xref = out.size()
        writeText("xref\n0 ${bodies.size + 1}\n")
        writeText("0000000000 65535 f \n")
        offsets.forEach { offset ->
            writeText(String.format(Locale.ROOT, "%010d 00000 n \n", offset))
        }
        writeText("trailer\n<< /Size ${bodies.size + 1} /Root 1 0 R >>\n")
        writeText("startxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }
}

internal fun basePdf(text: String = "Hello"): TestPdfBuilder {
    val builder = TestPdfBuilder()
    builder.add("<< /Type /Catalog /Pages 2 0 R >>")
    builder.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    builder.add(pageBody())
    val content = "BT /F1 12 Tf 72 720 Td ($text) Tj ET"
    builder.addStream("<< /Length ${content.length} >>", content.toByteArray(StandardCharsets.ISO_8859_1))
    builder.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    return builder
}

internal fun pageBody(annots: List<Int> = emptyList(), extra: String = ""): String {
    val annotsEntry = if (annots.isEmpty()) "" else " /Annots [${annots.joinToString(" ") { "$it 0 R" }}]"
    return "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
        "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R$annotsEntry $extra >>"
}

internal fun catalogBody(extra: String = ""): String =
    "<< /Type /Catalog /Pages 2 0 R $extra >>"

internal fun linkAnnotation(uri: String, rect: String = "[70 710 300 740]"): String =
    "<< /Type /Annot /Subtype /Link /Rect $rect /A << /S /URI /URI ($uri) >> >>"

internal fun actionAnnotation(action: String, rect: String = "[70 710 300 740]"): String =
    "<< /Type /Annot /Subtype /Link /Rect $rect /A $action >>"

internal fun streamObject(dict: String, data: String): Pair<String, ByteArray> =
    dict to data.toByteArray(StandardCharsets.ISO_8859_1)
