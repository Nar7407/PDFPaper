package com.pdfpaper.convert

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** One indirect object body for [buildTestPdf]; strings default to pure ASCII. */
class TestObj(val num: Int, val gen: Int, val body: ByteArray) {
    constructor(num: Int, gen: Int, body: String) : this(num, gen, body.toByteArray(StandardCharsets.US_ASCII))
}

/**
 * Builds a small, structurally valid PDF with a real xref table (§7.5.4-5).
 * A trimmed copy of the engine's own test helper, kept local so the convert
 * tests do not depend on engine test sources.
 */
fun buildTestPdf(objects: List<TestObj>, rootRef: String = "1 0 R", trailerExtra: String = ""): ByteArray {
    val out = ByteArrayOutputStream()
    fun write(s: String) = out.write(s.toByteArray(StandardCharsets.US_ASCII))
    write("%PDF-1.4\n")
    val offsets = mutableMapOf<Int, Long>()
    val gens = mutableMapOf<Int, Int>()
    for (o in objects) {
        offsets[o.num] = out.size().toLong()
        gens[o.num] = o.gen
        write("${o.num} ${o.gen} obj\n")
        out.write(o.body)
        write("\nendobj\n")
    }
    val maxNum = objects.maxOf { it.num }
    val xrefAt = out.size().toLong()
    write("xref\n0 ${maxNum + 1}\n")
    for (i in 0..maxNum) {
        val off = offsets[i]
        if (off == null) write("0000000000 00000 f \n") else write("%010d %05d n \n".format(off, gens[i]))
    }
    write("trailer\n<< /Size ${maxNum + 1} /Root $rootRef$trailerExtra >>\n")
    write("startxref\n$xrefAt\n%%EOF\n")
    return out.toByteArray()
}

/** A stream object body with a plain /Length and no filter. */
fun streamObj(num: Int, dict: String, data: ByteArray): TestObj {
    val out = ByteArrayOutputStream()
    out.write("$dict\nstream\n".toByteArray(StandardCharsets.US_ASCII))
    out.write(data)
    out.write("\nendstream".toByteArray(StandardCharsets.US_ASCII))
    return TestObj(num, 0, out.toByteArray())
}

private const val PAGE_TEMPLATE =
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] " +
        "/Resources << /Font << /F1 5 0 R >>%s >> /Contents 4 0 R >>"

/**
 * One page, 200x100 pt, with standard-14 Helvetica text and a red filled
 * rectangle. No embedded font, so the engine falls back to its built-in
 * metrics — exactly what a "simple" PDF looks like.
 */
fun textAndVectorPdf(text: String = "Hello", fontSize: Int = 24): ByteArray {
    val content = "BT /F1 $fontSize Tf 20 40 Td ($text) Tj ET\n1 0 0 rg 10 10 60 40 re f\n"
    return buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(3, 0, PAGE_TEMPLATE.format("")),
            streamObj(4, "<< /Length ${content.length} >>", content.toByteArray(StandardCharsets.US_ASCII)),
            TestObj(5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        )
    )
}

/** One page with a 2x2 raw RGB image XObject drawn at 100x50 pt. */
fun imagePagePdf(): ByteArray {
    val rgb = byteArrayOf(
        255.toByte(), 0, 0, 0, 255.toByte(), 0,
        0, 0, 255.toByte(), 255.toByte(), 255.toByte(), 0
    )
    val content = "q 100 0 0 50 50 20 cm /Im1 Do Q\n"
    return buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(3, 0, PAGE_TEMPLATE.format(" /XObject << /Im1 6 0 R >>")),
            streamObj(4, "<< /Length ${content.length} >>", content.toByteArray(StandardCharsets.US_ASCII)),
            TestObj(5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
            streamObj(
                6,
                "<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace /DeviceRGB " +
                    "/BitsPerComponent 8 /Length ${rgb.size} >>",
                rgb
            )
        )
    )
}

/** Two pages of 200x100 pt, each with one text run. */
fun twoPagePdf(): ByteArray {
    fun pageContent(text: String): ByteArray = "BT /F1 12 Tf 10 80 Td ($text) Tj ET".toByteArray(StandardCharsets.US_ASCII)
    val first = pageContent("Page one")
    val second = pageContent("Page two")
    return buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>"),
            TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] " +
                "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>"),
            streamObj(4, "<< /Length ${first.size} >>", first),
            TestObj(5, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
            TestObj(6, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] " +
                "/Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>"),
            streamObj(7, "<< /Length ${second.size} >>", second)
        )
    )
}
