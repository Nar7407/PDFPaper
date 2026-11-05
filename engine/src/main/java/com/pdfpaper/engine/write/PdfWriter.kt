package com.pdfpaper.engine.write

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** One indirect object ready to be written: "<num> <gen> obj <body> endobj". */
data class IndirectObject(val num: Int, val gen: Int = 0, val obj: PdfObject)

/**
 * Assembles a valid PDF file from a set of indirect objects (ISO 32000-1,
 * §7.5.2 header, §7.5.4 cross-reference table, §7.5.5 trailer).
 *
 * Two entry points:
 * - [write] produces a brand-new file with a classic cross-reference table.
 * - [append] performs an incremental update (§7.5.6): the original bytes are
 *   copied unchanged and only the new/changed objects plus a chained xref
 *   section (with /Prev) are appended, so the previous revision is preserved.
 *
 * Streams keep their encoded bytes (see [PdfSerializer]); the output is always
 * a classic-xref file, so callers do not need to know how the input was built
 * (object streams and xref streams are flattened into plain objects).
 */
object PdfWriter {

    const val DEFAULT_VERSION = "1.7"

    /** §7.5.2 binary file marker first line, so the file survives text-mode tools. */
    private val BINARY_MARKER = byteArrayOf(
        0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A
    )

    /**
     * Writes a complete new file containing [objects], with [root] as the
     * trailer /Root. [info] and [id] are optional; [version] goes in the header.
     */
    fun write(
        objects: Collection<IndirectObject>,
        root: PdfRef,
        info: PdfRef? = null,
        id: PdfArray? = null,
        version: String = DEFAULT_VERSION
    ): ByteArray {
        val sorted = objects.sortedBy { it.num }
        require(sorted.all { it.num >= 1 }) { "Object numbers must be >= 1" }
        val size = (sorted.maxOfOrNull { it.num } ?: 0) + 1

        val out = ByteArrayOutputStream()
        ascii("%PDF-$version\n", out)
        out.write(BINARY_MARKER)

        val offsets = HashMap<Int, Int>()
        val gens = HashMap<Int, Int>()
        for (obj in sorted) {
            offsets[obj.num] = out.size()
            gens[obj.num] = obj.gen
            ascii("${obj.num} ${obj.gen} obj\n", out)
            PdfSerializer.writeObject(obj.obj, out)
            ascii("\nendobj\n", out)
        }

        val xrefAt = out.size()
        writeFullXref(out, offsets, gens, size)
        writeTrailer(out, size, root, info, id, prev = null)
        ascii("startxref\n$xrefAt\n%%EOF\n", out)
        return out.toByteArray()
    }

    /**
     * Incremental update (§7.5.6): copies [original] byte-for-byte, appends
     * [updates], then a sparse classic xref section whose /Prev chains to
     * [prevStartXref] (the offset of the previous startxref). [size] must be at
     * least the highest object number in the merged document plus one.
     */
    fun append(
        original: ByteArray,
        prevStartXref: Long,
        updates: Collection<IndirectObject>,
        root: PdfRef,
        info: PdfRef? = null,
        id: PdfArray? = null,
        size: Int
    ): ByteArray {
        require(prevStartXref >= 0) { "prevStartXref must be known for an incremental save" }
        val sorted = updates.sortedBy { it.num }
        require(sorted.isNotEmpty()) { "An incremental save needs at least one changed object" }
        val effectiveSize = maxOf(size, (sorted.maxOfOrNull { it.num } ?: 0) + 1)

        val out = ByteArrayOutputStream(original.size + 1024)
        out.write(original)
        val offsets = HashMap<Int, Int>()
        for (obj in sorted) {
            offsets[obj.num] = out.size()
            ascii("${obj.num} ${obj.gen} obj\n", out)
            PdfSerializer.writeObject(obj.obj, out)
            ascii("\nendobj\n", out)
        }

        val xrefAt = out.size()
        writeSparseXref(out, offsets, sorted)
        writeTrailer(out, effectiveSize, root, info, id, prev = prevStartXref)
        ascii("startxref\n$xrefAt\n%%EOF\n", out)
        return out.toByteArray()
    }

    // -- Cross-reference sections ---------------------------------------------

    private fun writeFullXref(
        out: ByteArrayOutputStream,
        offsets: Map<Int, Int>,
        gens: Map<Int, Int>,
        size: Int
    ) {
        ascii("xref\n0 $size\n", out)
        for (i in 0 until size) {
            val offset = offsets[i]
            if (offset == null) {
                ascii("0000000000 65535 f \n", out)
            } else {
                ascii("%010d %05d n \n".format(offset, gens[i] ?: 0), out)
            }
        }
    }

    /** Sparse section: one subsection per run of consecutive updated numbers. */
    private fun writeSparseXref(
        out: ByteArrayOutputStream,
        offsets: Map<Int, Int>,
        updates: List<IndirectObject>
    ) {
        ascii("xref\n", out)
        var i = 0
        while (i < updates.size) {
            val first = updates[i].num
            var last = first
            var j = i + 1
            while (j < updates.size && updates[j].num == last + 1) {
                last = updates[j].num
                j++
            }
            val count = last - first + 1
            ascii("$first $count\n", out)
            for (n in first..last) {
                ascii("%010d %05d n \n".format(offsets.getValue(n), updates.first { it.num == n }.gen), out)
            }
            i = j
        }
    }

    private fun writeTrailer(
        out: ByteArrayOutputStream,
        size: Int,
        root: PdfRef,
        info: PdfRef?,
        id: PdfArray?,
        prev: Long?
    ) {
        ascii("trailer\n<< /Size $size /Root ${root.objNum} ${root.gen} R", out)
        if (info != null) ascii(" /Info ${info.objNum} ${info.gen} R", out)
        if (id != null) {
            ascii(" /ID ", out)
            PdfSerializer.writeObject(id, out)
        }
        if (prev != null) ascii(" /Prev $prev", out)
        ascii(" >>\n", out)
    }

    private fun ascii(s: String, out: ByteArrayOutputStream) {
        out.write(s.toByteArray(StandardCharsets.US_ASCII))
    }
}
