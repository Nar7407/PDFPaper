package com.pdfpaper.engine.write

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Writes a complete file that packs its small objects into object streams
 * (ISO 32000-1, §7.5.7) and stores the cross-reference data in a
 * cross-reference stream (§7.5.8).
 *
 * Both are optional PDF 1.5 features that every modern reader understands and
 * that make a rewritten file noticeably smaller: the dictionary-heavy parts of
 * a document (pages, fonts, outlines) lose their per-object overhead, and the
 * cross-reference table is one compressed stream instead of a text table.
 *
 * The catalogue and the document information dictionary stay outside the
 * object streams: they are the two objects a reader looks for first, and
 * keeping them plain costs a few bytes but saves a lot of trouble.
 */
object PdfCompactWriter {

    /** §7.5.2 binary file marker first line, so the file survives text-mode tools. */
    private val BINARY_MARKER = byteArrayOf(
        0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A
    )

    /** How many objects go into one object stream. */
    private const val OBJECTS_PER_STREAM = 200

    /** The lowest PDF version that understands object and xref streams. */
    private const val MIN_VERSION = "1.5"

    /**
     * Writes [objects] as a new file with [root] as the trailer /Root.
     *
     * @param useObjectStreams pack the small objects into object streams.
     * @param useXrefStreams write the cross-reference data as a stream. Object
     *   streams can only be found through a cross-reference stream, so turning
     *   this off also turns object streams off.
     */
    fun write(
        objects: Collection<IndirectObject>,
        root: PdfRef,
        info: PdfRef? = null,
        id: PdfArray? = null,
        version: String = MIN_VERSION,
        useObjectStreams: Boolean = true,
        useXrefStreams: Boolean = true
    ): ByteArray {
        if (!useXrefStreams) {
            // A classic table cannot point into an object stream.
            return PdfWriter.write(objects, root, info, id, version)
        }

        val sorted = objects.sortedBy { it.num }
        require(sorted.all { it.num >= 1 }) { "Object numbers must be >= 1" }
        val maxNum = sorted.maxOfOrNull { it.num } ?: 0
        val headerVersion = if (version.compareTo(MIN_VERSION) < 0) MIN_VERSION else version

        val keepOutside = setOfNotNull(root.objNum, info?.objNum)
        val packable = if (useObjectStreams) {
            sorted.filter { it.obj !is PdfStream && it.num !in keepOutside }
        } else {
            emptyList()
        }
        val packedNumbers = packable.map { it.num }.toSet()
        val chunks = packable.chunked(OBJECTS_PER_STREAM)
        val firstStreamNum = maxNum + 1

        val out = ByteArrayOutputStream()
        ascii("%PDF-$headerVersion\n", out)
        out.write(BINARY_MARKER)

        val offsets = HashMap<Int, Int>()
        val packedAt = HashMap<Int, Pair<Int, Int>>()

        // Plain objects first, in number order.
        for (obj in sorted) {
            if (obj.num in packedNumbers) continue
            offsets[obj.num] = out.size()
            ascii("${obj.num} ${obj.gen} obj\n", out)
            PdfSerializer.writeObject(obj.obj, out)
            ascii("\nendobj\n", out)
        }

        // Then the object streams themselves.
        chunks.forEachIndexed { index, chunk ->
            val streamNum = firstStreamNum + index
            val (data, count, first) = buildObjectStream(chunk)
            val entries = LinkedHashMap<PdfName, PdfObject>()
            entries[PdfName("Type")] = PdfName("ObjStm")
            entries[PdfName("N")] = PdfInteger(count.toLong())
            entries[PdfName("First")] = PdfInteger(first.toLong())
            entries[PdfName("Filter")] = PdfName("FlateDecode")
            offsets[streamNum] = out.size()
            ascii("$streamNum 0 obj\n", out)
            PdfSerializer.writeObject(PdfStream(PdfDict(entries), data), out)
            ascii("\nendobj\n", out)
            chunk.forEachIndexed { position, obj -> packedAt[obj.num] = streamNum to position }
        }

        // Finally the cross-reference stream.
        val xrefNum = firstStreamNum + chunks.size
        val highest = xrefNum - 1
        val entryCount = highest + 1
        val table = ByteArrayOutputStream()
        for (num in 0 until entryCount) {
            val packed = packedAt[num]
            when {
                num == 0 -> writeXrefEntry(table, type = 0, field2 = 0, field3 = 65535)
                packed != null -> writeXrefEntry(table, type = 1, field2 = packed.first, field3 = packed.second)
                offsets.containsKey(num) ->
                    writeXrefEntry(table, type = 2, field2 = offsets.getValue(num), field3 = 0)
                else -> writeXrefEntry(table, type = 0, field2 = 0, field3 = 65535)
            }
        }
        val compressed = Deflate.encode(table.toByteArray())
        val xrefEntries = LinkedHashMap<PdfName, PdfObject>()
        xrefEntries[PdfName("Type")] = PdfName("XRef")
        xrefEntries[PdfName("Size")] = PdfInteger((highest + 2).toLong())
        xrefEntries[PdfName("Index")] = PdfArray(listOf(PdfInteger(0), PdfInteger(entryCount.toLong())))
        xrefEntries[PdfName("W")] = PdfArray(listOf(PdfInteger(1), PdfInteger(4), PdfInteger(2)))
        xrefEntries[PdfName("Root")] = root
        info?.let { xrefEntries[PdfName("Info")] = it }
        id?.let { xrefEntries[PdfName("ID")] = it }
        xrefEntries[PdfName("Filter")] = PdfName("FlateDecode")

        val xrefAt = out.size()
        ascii("$xrefNum 0 obj\n", out)
        PdfSerializer.writeObject(PdfStream(PdfDict(xrefEntries), compressed), out)
        ascii("\nendobj\n", out)
        ascii("startxref\n$xrefAt\n%%EOF\n", out)
        return out.toByteArray()
    }

    /**
     * Builds the payload of one object stream: the "number offset" header pairs
     * followed by the object bodies, compressed with Flate.
     *
     * @return the compressed bytes, the number of objects and the offset of the
     *   first body inside the uncompressed data.
     */
    private fun buildObjectStream(chunk: List<IndirectObject>): Triple<ByteArray, Int, Int> {
        val header = StringBuilder()
        val bodies = ByteArrayOutputStream()
        for (obj in chunk) {
            header.append(obj.num).append(' ').append(bodies.size()).append(' ')
            PdfSerializer.writeObject(obj.obj, bodies)
            bodies.write('\n'.code)
        }
        val headerBytes = header.toString().toByteArray(StandardCharsets.US_ASCII)
        val first = headerBytes.size
        val raw = ByteArrayOutputStream(headerBytes.size + bodies.size())
        raw.write(headerBytes)
        raw.write(bodies.toByteArray())
        return Triple(Deflate.encode(raw.toByteArray()), chunk.size, first)
    }

    /** One §7.5.8.2 cross-reference entry: type, field 2 and field 3. */
    private fun writeXrefEntry(
        out: ByteArrayOutputStream,
        type: Int,
        field2: Int,
        field3: Int
    ) {
        out.write(type and 0xFF)
        out.write((field2 ushr 24) and 0xFF)
        out.write((field2 ushr 16) and 0xFF)
        out.write((field2 ushr 8) and 0xFF)
        out.write(field2 and 0xFF)
        out.write((field3 ushr 8) and 0xFF)
        out.write(field3 and 0xFF)
    }

    private fun ascii(s: String, out: ByteArrayOutputStream) {
        out.write(s.toByteArray(StandardCharsets.US_ASCII))
    }
}
