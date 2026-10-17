package com.pdfpaper.engine.test

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** One indirect object body for [buildTestPdf]; strings default to pure ASCII. */
class TestObj(val num: Int, val gen: Int, val body: ByteArray) {
    constructor(num: Int, gen: Int, body: String) : this(num, gen, body.toByteArray(StandardCharsets.US_ASCII))
}

/** Classic xref table + trailer (§7.5.4-5), offsets computed for real. */
fun buildTestPdf(objects: List<TestObj>, rootRef: String = "1 0 R"): ByteArray {
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
        if (off == null) {
            write("0000000000 00000 f \n")
        } else {
            write("%010d %05d n \n".format(off, gens[i]))
        }
    }
    write("trailer\n<< /Size ${maxNum + 1} /Root $rootRef >>\n")
    write("startxref\n$xrefAt\n%%EOF\n")
    return out.toByteArray()
}
