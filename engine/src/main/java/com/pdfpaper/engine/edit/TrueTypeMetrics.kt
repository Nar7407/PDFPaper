package com.pdfpaper.engine.edit

/**
 * Minimal TrueType reader: only the tables the writer needs to embed a simple
 * TrueType font as /FontFile2 — metrics (`head`, `hhea`, `hmtx`, `maxp`) and a
 * Unicode → glyph-id mapping (`cmap`, §9.6.5). The font program itself is
 * treated as opaque bytes; no glyph outlines are interpreted.
 *
 * All offsets are bounds-checked: a malformed font yields zeroed metrics rather
 * than an exception (AGENTS rule 5).
 */
internal class TrueTypeMetrics(private val bytes: ByteArray) {

    val unitsPerEm: Int
    val xMin: Int
    val yMin: Int
    val xMax: Int
    val yMax: Int
    val ascent: Int
    val descent: Int
    val numGlyphs: Int

    private val numberOfHMetrics: Int
    private val hmtxOffset: Int
    private val cmap: Cmap?

    init {
        val tables = readTableDirectory()
        val head = tables["head"]
        unitsPerEm = if (head != null) u16(head + 18).takeIf { it > 0 } ?: 1000 else 1000
        xMin = if (head != null) i16(head + 36) else 0
        yMin = if (head != null) i16(head + 38) else 0
        xMax = if (head != null) i16(head + 40) else 0
        yMax = if (head != null) i16(head + 42) else 0

        val hhea = tables["hhea"]
        ascent = if (hhea != null) i16(hhea + 4) else 0
        descent = if (hhea != null) i16(hhea + 6) else 0
        numberOfHMetrics = if (hhea != null) u16(hhea + 34) else 0

        val maxp = tables["maxp"]
        numGlyphs = if (maxp != null) u16(maxp + 4) else 0

        hmtxOffset = tables["hmtx"] ?: -1
        cmap = tables["cmap"]?.let { readCmap(it) }
    }

    /** Advance width in font design units (0 when the glyph is unknown). */
    fun advance(glyphId: Int): Int {
        if (glyphId <= 0 || hmtxOffset < 0 || numberOfHMetrics == 0) return 0
        val index = minOf(glyphId, numberOfHMetrics - 1)
        return u16(hmtxOffset + index * 4)
    }

    /** Advance width in 1/1000 em, the unit PDF /Widths uses (§9.6.2.1). */
    fun width1000(codePoint: Int): Int {
        val gid = gidFor(codePoint)
        if (gid <= 0) return 0
        return Math.round(advance(gid).toDouble() * 1000.0 / unitsPerEm).toInt()
    }

    /** Design-unit value scaled to 1/1000 em. */
    fun scale1000(value: Int): Int = Math.round(value.toDouble() * 1000.0 / unitsPerEm).toInt()

    fun gidFor(codePoint: Int): Int = cmap?.gidFor(codePoint) ?: 0

    // -- Table directory ------------------------------------------------------

    private fun readTableDirectory(): Map<String, Int> {
        if (bytes.size < 12) return emptyMap()
        val numTables = u16(4)
        val result = HashMap<String, Int>(numTables * 2)
        for (i in 0 until numTables) {
            val record = 12 + i * 16
            if (record + 16 > bytes.size) break
            val tag = String(bytes, record, 4, Charsets.US_ASCII)
            result[tag] = u32(record + 8).toInt()
        }
        return result
    }

    // -- cmap -----------------------------------------------------------------

    private sealed interface Cmap {
        fun gidFor(codePoint: Int): Int
    }

    private class Format4(val data: ByteArray, val offset: Int) : Cmap {
        private val segCountX2 = u16At(data, offset + 6)
        private val segCount = segCountX2 / 2
        private val endOffset = offset + 14
        private val startOffset = endOffset + segCountX2 + 2
        private val deltaOffset = startOffset + segCountX2
        private val rangeOffset = deltaOffset + segCountX2

        override fun gidFor(codePoint: Int): Int {
            if (codePoint > 0xFFFF) return 0
            for (i in 0 until segCount) {
                val end = u16At(data, endOffset + i * 2)
                if (end < codePoint) continue
                val start = u16At(data, startOffset + i * 2)
                if (start > codePoint) return 0
                val delta = i16At(data, deltaOffset + i * 2)
                val range = u16At(data, rangeOffset + i * 2)
                if (range == 0) return (codePoint + delta) and 0xFFFF
                val glyphAddress = rangeOffset + i * 2 + range + (codePoint - start) * 2
                val glyph = u16At(data, glyphAddress)
                return if (glyph == 0) 0 else (glyph + delta) and 0xFFFF
            }
            return 0
        }
    }

    private class Format12(val data: ByteArray, val offset: Int) : Cmap {
        private val groupCount = u32At(data, offset + 12).toInt().coerceAtMost(1_000_000)

        override fun gidFor(codePoint: Int): Int {
            var low = 0
            var high = groupCount - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val group = offset + 16 + mid * 12
                val start = u32At(data, group)
                val end = u32At(data, group + 4)
                when {
                    codePoint < start -> high = mid - 1
                    codePoint > end -> low = mid + 1
                    else -> {
                        val startGlyph = u32At(data, group + 8)
                        return (startGlyph + (codePoint - start)).toInt()
                    }
                }
            }
            return 0
        }
    }

    private fun readCmap(base: Int): Cmap? {
        val tableCount = u16(base + 2)
        var best: Cmap? = null
        var bestScore = -1
        for (i in 0 until tableCount) {
            val record = base + 4 + i * 8
            if (record + 8 > bytes.size) break
            val platform = u16(record)
            val encoding = u16(record + 2)
            val subOffset = base + u32(record + 4).toInt()
            if (subOffset + 2 > bytes.size) continue
            val format = u16(subOffset)
            val cmap = when (format) {
                4 -> Format4(bytes, subOffset)
                12 -> Format12(bytes, subOffset)
                else -> null
            } ?: continue
            // Prefer the Windows BMP (3,1) or Unicode (0,x) subtable over others.
            val score = when {
                platform == 3 && encoding == 1 -> 4
                platform == 3 && encoding == 10 -> 5
                platform == 0 -> 3
                platform == 3 -> 2
                else -> 1
            }
            if (score > bestScore) {
                bestScore = score
                best = cmap
            }
        }
        return best
    }

    // -- Byte readers ---------------------------------------------------------

    private fun u16(offset: Int): Int = u16At(bytes, offset)

    private fun i16(offset: Int): Int = i16At(bytes, offset)

    private fun u32(offset: Int): Long = u32At(bytes, offset)

    private companion object {
        fun u16At(data: ByteArray, offset: Int): Int {
            if (offset < 0 || offset + 2 > data.size) return 0
            return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        }

        fun i16At(data: ByteArray, offset: Int): Int {
            val value = u16At(data, offset)
            return if (value >= 0x8000) value - 0x10000 else value
        }

        fun u32At(data: ByteArray, offset: Int): Long {
            if (offset < 0 || offset + 4 > data.size) return 0
            return ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
        }
    }
}
