package com.pdfpaper.engine.tools

import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.pageTree
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.page.PageTreeResult
import com.pdfpaper.engine.text.extractText
import com.pdfpaper.engine.text.layout
import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.write.PdfPageOps
import com.pdfpaper.engine.write.PdfPageSelection
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

/**
 * Performance harness (T14): the committed `/testdata` corpus is deliberately
 * tiny structural fixtures, so this tool *generates* a scaled-up synthetic
 * corpus (many objects, large text/graphics content, big filtered streams and
 * an object stream), then times every engine phase against it and reports the
 * heap high-water mark. It powers the T14 "profile and fix the top hotspots"
 * work and can be rerun with `./gradlew :engine:perfCheck`.
 *
 * Usage: PerfCheck <outDir> <reportFile>
 */

private val LATIN1 = StandardCharsets.ISO_8859_1
private val ASCII = StandardCharsets.US_ASCII

fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: PerfCheck <outDir> <reportFile>" }
    val outDir = File(args[0])
    outDir.mkdirs()
    val reportFile = File(args[1])

    val scenarios = generate(outDir)
    val results = scenarios.map { profile(it) }

    val lines = buildList {
        add("PDFPaper performance report")
        add("Engine: ${engineVersion()}")
        add("Note: /testdata holds tiny structural fixtures; this synthetic corpus scales each")
        add("engine phase so hotspots show up. Times are wall-clock milliseconds.")
        add("")
        for (r in results) {
            add("== ${r.name} (${r.fileSize / 1024} KiB, ${r.heapsBefore} heap after open) ==")
            add("   ${r.detail}")
            for (p in r.phases) {
                add("   %-14s %8d ms   heap peak %6d KiB".format(p.name, p.millis, p.heapPeakKiB))
            }
            add("   %-14s %8d ms".format("TOTAL", r.phases.sumOf { it.millis }))
            add("")
        }
        add("Phase totals across the synthetic corpus:")
        val byName = LinkedHashMap<String, Long>()
        for (r in results) for (p in r.phases) byName.merge(p.name, p.millis, Long::plus)
        for ((name, total) in byName.entries.sortedByDescending { it.value }) {
            add("  %-14s %8d ms".format(name, total))
        }
    }
    reportFile.parentFile?.mkdirs()
    reportFile.writeText(lines.joinToString("\n") + "\n")
    println(lines.joinToString("\n"))
    println("Report: ${reportFile.absolutePath}")
}

private data class PhaseResult(val name: String, val millis: Long, val heapPeakKiB: Long)
private data class ScenarioResult(
    val name: String,
    val fileSize: Long,
    val heapsBefore: String,
    val detail: String,
    val phases: List<PhaseResult>
)

private class Profiler {
    val phases = ArrayList<PhaseResult>()
    private var peak = usedHeapKiB()

    fun <T> phase(name: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        peak = maxOf(peak, usedHeapKiB())
        phases += PhaseResult(name, ms, peak)
        return result
    }

    fun finish(name: String, file: File, detail: String, before: Long): ScenarioResult =
        ScenarioResult(name, file.length(), "${before / 1024} KiB", detail, phases)
}

private fun profile(file: File): ScenarioResult {
    val before = usedHeapKiB()
    val profiler = Profiler()
    val opened = profiler.phase("open") { PdfDocument.open(FileSource(file)) }
    opened.fold(
        onSuccess = { doc ->
            doc.use {
                var objectCount = 0
                var streamCount = 0
                var decodedCount = 0
                profiler.phase("objects") {
                    for (num in it.file.entries.keys) {
                        if (it.deref(num) != null) objectCount++
                    }
                }
                profiler.phase("streams") {
                    for (num in it.file.entries.keys) {
                        val obj = it.deref(num) as? PdfStream ?: continue
                        streamCount++
                        val ok = it.decodeStream(obj).isSuccess
                        if (ok) decodedCount++
                    }
                }
                val pages = profiler.phase("pages") {
                    (pageTree(it) as? PageTreeResult.Ok)?.pages ?: emptyList()
                }
                var textRuns = 0
                profiler.phase("extractText") {
                    for (p in pages) textRuns += p.extractText().runs.size
                }
                var layoutLines = 0
                profiler.phase("layout") {
                    for (p in pages) layoutLines += p.layout().lines.size
                }
                var commands = 0
                profiler.phase("displayList") {
                    for (p in pages) commands += p.buildDisplayList().commands.size
                }
                var roundTripPages = 0
                profiler.phase("roundTrip") {
                    val selections = pages.indices.map { i -> PdfPageSelection(it, i) }
                    val written = PdfPageOps.assemble(selections, it).getOrNull()
                    roundTripPages = written?.let { bytes ->
                        PdfDocument.open(com.pdfpaper.engine.io.ByteArraySource(bytes))
                            .getOrNull()?.use { reopened -> reopened.pages().size } ?: -1
                    } ?: -1
                }
                val detail = "objects=$objectCount streams=$decodedCount/$streamCount " +
                    "pages=${pages.size} textRuns=$textRuns layoutLines=$layoutLines " +
                    "commands=$commands roundTripPages=$roundTripPages"
                return profiler.finish(file.name, file, detail, before)
            }
        },
        onFailure = { e ->
            return profiler.finish(file.name, file, "OPEN FAILED: ${e.message}", before)
        }
    )
}

private fun PdfDocument.decodeStream(stream: PdfStream) =
    stream.decoded { ref: PdfRef -> deref(ref.objNum) }

private fun usedHeapKiB(): Long {
    val rt = Runtime.getRuntime()
    return (rt.totalMemory() - rt.freeMemory()) / 1024
}

// ---------------------------------------------------------------------------
// Synthetic corpus generation
// ---------------------------------------------------------------------------

/** Classic-xref PDF builder for the performance fixtures. */
private class PdfBuilder {
    val objects = LinkedHashMap<Int, ByteArray>()

    fun put(num: Int, body: String) = putBytes(num, body.toByteArray(LATIN1))

    fun putBytes(num: Int, body: ByteArray) {
        objects[num] = body
    }

    fun stream(num: Int, entries: String, data: ByteArray) {
        val head = "<< $entries /Length ${data.size} >>\nstream\n".toByteArray(LATIN1)
        val tail = "\nendstream".toByteArray(ASCII)
        val out = ByteArray(head.size + data.size + tail.size)
        System.arraycopy(head, 0, out, 0, head.size)
        System.arraycopy(data, 0, out, head.size, data.size)
        System.arraycopy(tail, 0, out, head.size + data.size, tail.size)
        objects[num] = out
    }

    fun classicBytes(rootNum: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(ASCII))
        w("%PDF-1.7\n%PDFPaper-perf\n")
        val offsets = HashMap<Int, Long>()
        for (num in objects.keys.sorted()) {
            offsets[num] = out.size().toLong()
            w("$num 0 obj\n")
            out.write(objects.getValue(num))
            w("\nendobj\n")
        }
        val maxNum = objects.keys.max()
        val xrefAt = out.size().toLong()
        w("xref\n0 ${maxNum + 1}\n")
        for (i in 0..maxNum) {
            val off = offsets[i]
            if (off == null) w("0000000000 65535 f \n") else w("%010d 00000 n \n".format(off))
        }
        w("trailer\n<< /Size ${maxNum + 1} /Root $rootNum 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
        return out.toByteArray()
    }
}

private fun generate(dir: File): List<File> {
    val files = ArrayList<File>()
    files += write(dir, "text-heavy.pdf", textHeavy(200, 300))
    files += write(dir, "graphics-heavy.pdf", graphicsHeavy(200, 1200))
    files += write(dir, "wide-objects.pdf", wideObjects(40_000))
    files += write(dir, "big-streams.pdf", bigStreams(6, 1024))
    files += write(dir, "object-stream.pdf", objectStream(8_000))
    return files
}

private fun write(dir: File, name: String, bytes: ByteArray): File {
    val file = File(dir, name)
    file.writeBytes(bytes)
    return file
}

private fun deflate(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_SPEED)
    deflater.setInput(data)
    deflater.finish()
    val out = ByteArrayOutputStream(data.size / 3 + 64)
    val buf = ByteArray(65536)
    while (!deflater.finished()) {
        val n = deflater.deflate(buf)
        out.write(buf, 0, n)
    }
    deflater.end()
    return out.toByteArray()
}

/** P pages, each with one Flate-compressed content stream full of text operands. */
private fun textHeavy(pages: Int, linesPerPage: Int): ByteArray {
    val b = PdfBuilder()
    b.put(1, "<< /Type /Catalog /Pages 2 0 R >>")
    val kids = (0 until pages).joinToString(" ") { "${5 + it * 2} 0 R" }
    b.put(2, "<< /Type /Pages /Kids [$kids] /Count $pages >>")
    b.put(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    for (i in 0 until pages) {
        val pageNum = 5 + i * 2
        val contentNum = pageNum + 1
        val sb = StringBuilder()
        for (line in 0 until linesPerPage) {
            val y = 780 - (line % 60) * 12
            sb.append("BT /F1 11 Tf 1 0 0 1 50 $y Tm (Line $line of page $i the quick brown fox jumps) Tj ET\n")
        }
        b.stream(
            contentNum, "/Filter /FlateDecode",
            deflate(sb.toString().toByteArray(ASCII))
        )
        b.put(
            pageNum,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 3 0 R >> >> /Contents $contentNum 0 R >>"
        )
    }
    return b.classicBytes(1)
}

/** P pages, each with one Flate-compressed content stream full of path operators. */
private fun graphicsHeavy(pages: Int, opsPerPage: Int): ByteArray {
    val b = PdfBuilder()
    b.put(1, "<< /Type /Catalog /Pages 2 0 R >>")
    val kids = (0 until pages).joinToString(" ") { "${4 + it * 2} 0 R" }
    b.put(2, "<< /Type /Pages /Kids [$kids] /Count $pages >>")
    for (i in 0 until pages) {
        val pageNum = 4 + i * 2
        val contentNum = pageNum + 1
        val sb = StringBuilder()
        for (op in 0 until opsPerPage) {
            val x = (op * 7) % 500
            val y = (op * 13) % 700
            when (op % 4) {
                0 -> sb.append("0.1 0.2 0.3 rg $x $y 40 25 re f\n")
                1 -> sb.append("0.5 g $x $y m ${x + 30} ${y + 30} l S\n")
                2 -> sb.append("0 0 0 RG 1 w $x $y 20 20 re S\n")
                else -> sb.append("$x $y m ${x + 10} $y ${x + 20} ${y + 10} ${x + 30} $y c S\n")
            }
        }
        b.stream(contentNum, "/Filter /FlateDecode", deflate(sb.toString().toByteArray(ASCII)))
        b.put(
            pageNum,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Contents $contentNum 0 R >>"
        )
    }
    return b.classicBytes(1)
}

/** One page plus [count] indirect dictionary objects (wide xref) referenced from the catalog. */
private fun wideObjects(count: Int): ByteArray {
    val b = PdfBuilder()
    b.put(1, "<< /Type /Catalog /Pages 2 0 R /Items 4 0 R >>")
    b.put(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    b.put(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>")
    val refs = StringBuilder("[")
    for (i in 0 until count) {
        val num = 5 + i
        b.put(num, "<< /Idx $num /Name /Obj$num /Vals [1 2 3 4 5 6 7 8] >>")
        refs.append("$num 0 R ")
    }
    refs.append("]")
    b.put(4, refs.toString())
    return b.classicBytes(1)
}

/** [count] pages each carrying a Flate-compressed [size]x[size] 8-bit gray image. */
private fun bigStreams(count: Int, size: Int): ByteArray {
    val b = PdfBuilder()
    b.put(1, "<< /Type /Catalog /Pages 2 0 R >>")
    val kids = (0 until count).joinToString(" ") { "${6 + it * 3} 0 R" }
    b.put(2, "<< /Type /Pages /Kids [$kids] /Count $count >>")
    b.put(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    for (i in 0 until count) {
        val pageNum = 6 + i * 3
        val imageNum = pageNum + 1
        val contentNum = pageNum + 2
        val raw = ByteArray(size * size) { k -> ((k * 31 + (k shr 3) + i * 7) and 0xFF).toByte() }
        b.stream(
            imageNum,
            "/Type /XObject /Subtype /Image /Width $size /Height $size " +
                "/ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode",
            deflate(raw)
        )
        val content = "q $size 0 0 $size 0 0 cm /Im0 Do Q\n"
        b.stream(contentNum, "/Filter /FlateDecode", deflate(content.toByteArray(ASCII)))
        b.put(
            pageNum,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $size $size] " +
                "/Resources << /XObject << /Im0 $imageNum 0 R >> >> /Contents $contentNum 0 R >>"
        )
    }
    return b.classicBytes(1)
}

/**
 * An xref-stream file whose payload objects ([count] of them) live in one
 * Flate-compressed object stream (§7.5.7/§7.5.8) — the common layout of
 * modern PDFs and the worst case for per-object-stream decoding.
 */
private fun objectStream(count: Int): ByteArray {
    val catalog = 1
    val objStmNum = 4
    val arrayNum = 5
    val firstCompressed = 6
    val xrefNum = firstCompressed + count

    val header = StringBuilder()
    val body = ByteArrayOutputStream()
    val refs = StringBuilder("[")
    for (i in 0 until count) {
        val objNum = firstCompressed + i
        header.append("$objNum ${body.size()} ")
        body.write("<< /Idx $objNum /Name /O$objNum /A [1 2 3 4 5 6 7 8] >>\n".toByteArray(LATIN1))
        refs.append("$objNum 0 R ")
    }
    refs.append("]")
    val first = header.toString().toByteArray(LATIN1).size
    val rawObjStm = ByteArrayOutputStream()
    rawObjStm.write(header.toString().toByteArray(LATIN1))
    body.writeTo(rawObjStm)
    val compressedObjStm = deflate(rawObjStm.toByteArray())

    val out = ByteArrayOutputStream()
    fun w(s: String) = out.write(s.toByteArray(ASCII))
    fun obj(num: Int, body: ByteArray): Long {
        val at = out.size().toLong()
        w("$num 0 obj\n")
        out.write(body)
        w("\nendobj\n")
        return at
    }
    w("%PDF-1.7\n%PDFPaper-perf\n")
    val offsets = HashMap<Int, Long>()
    offsets[catalog] = obj(catalog, "<< /Type /Catalog /Pages 2 0 R /X $arrayNum 0 R >>".toByteArray(LATIN1))
    offsets[2] = obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(LATIN1))
    offsets[3] = obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>".toByteArray(LATIN1))
    offsets[objStmNum] = obj(
        objStmNum,
        ("<< /Type /ObjStm /N $count /First $first /Filter /FlateDecode " +
            "/Length ${compressedObjStm.size} >>\nstream\n").toByteArray(LATIN1) +
            compressedObjStm + "\nendstream".toByteArray(ASCII)
    )
    offsets[arrayNum] = obj(arrayNum, refs.toString().toByteArray(LATIN1))

    // Xref stream (§7.5.8): W [1 3 2] = type, offset/stream, gen/index.
    val size = xrefNum + 1
    offsets[xrefNum] = out.size().toLong()
    val entryBytes = ByteArrayOutputStream()
    fun entry(type: Int, f2: Int, f3: Int) {
        entryBytes.write(type)
        entryBytes.write((f2 ushr 16) and 0xFF)
        entryBytes.write((f2 ushr 8) and 0xFF)
        entryBytes.write(f2 and 0xFF)
        entryBytes.write((f3 ushr 8) and 0xFF)
        entryBytes.write(f3 and 0xFF)
    }
    entry(0, 0, 0)
    for (i in 1 until firstCompressed) entry(2, offsets.getValue(i).toInt(), 0)
    for (i in 0 until count) entry(1, objStmNum, i)
    entry(2, offsets.getValue(xrefNum).toInt(), 0)
    val xrefData = entryBytes.toByteArray()
    w("$xrefNum 0 obj\n")
    w(
        "<< /Type /XRef /Size $size /Root $catalog 0 R /W [1 3 2] " +
            "/Index [0 $size] /Length ${xrefData.size} >>\nstream\n"
    )
    out.write(xrefData)
    w("\nendstream\nendobj\n")
    w("startxref\n${offsets.getValue(xrefNum)}\n%%EOF\n")
    return out.toByteArray()
}

private fun engineVersion(): String = try {
    com.pdfpaper.engine.PdfEngine().version()
} catch (_: Exception) {
    "unknown"
}
