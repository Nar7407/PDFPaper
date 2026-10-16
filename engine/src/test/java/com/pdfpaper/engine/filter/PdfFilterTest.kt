package com.pdfpaper.engine.filter

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfNull
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

private val LATIN1 = StandardCharsets.ISO_8859_1

private fun name(s: String) = PdfName(s)
private fun dictOf(vararg pairs: Pair<String, PdfObject>) =
    PdfDict(pairs.associate { PdfName(it.first) to it.second })

private fun arrayOf(vararg objs: PdfObject) = PdfArray(objs.toList())

private fun streamWith(filter: PdfObject, data: ByteArray, params: PdfObject? = null): PdfStream {
    val entries = mutableMapOf(
        PdfName("Length") to PdfInteger(data.size.toLong()) as PdfObject,
        PdfName("Filter") to filter
    )
    if (params != null) entries[PdfName("DecodeParms")] = params
    return PdfStream(PdfDict(entries), data)
}

private fun decode(
    stream: PdfStream,
    resolve: (PdfRef) -> PdfObject? = { null }
) = stream.decoded(resolve).getOrThrow()

// Trusted vectors (Python: zlib.compress / base64.a85encode).
private val FLATE_HELLO = byteArrayOf(
    0x78.toByte(), 0x9C.toByte(), 0xF3.toByte(), 0x48.toByte(), 0xCD.toByte(), 0xC9.toByte(),
    0xC9.toByte(), 0x57.toByte(), 0x28.toByte(), 0xCF.toByte(), 0x2F.toByte(), 0xCA.toByte(),
    0x49.toByte(), 0x01.toByte(), 0x00.toByte(), 0x18.toByte(), 0xAB.toByte(), 0x04.toByte(), 0x3D.toByte()
)
private val FLATE_0_255 = byteArrayOf(
    0x78.toByte(), 0x9C.toByte(), 0x01.toByte(), 0x00.toByte(), 0x01.toByte(), 0xFF.toByte(),
    0xFE.toByte(), 0x00.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte(),
    0x05.toByte(), 0x06.toByte(), 0x07.toByte(), 0x08.toByte(), 0x09.toByte(), 0x0A.toByte(),
    0x0B.toByte(), 0x0C.toByte(), 0x0D.toByte(), 0x0E.toByte(), 0x0F.toByte(), 0x10.toByte(),
    0x11.toByte(), 0x12.toByte(), 0x13.toByte(), 0x14.toByte(), 0x15.toByte(), 0x16.toByte(),
    0x17.toByte(), 0x18.toByte(), 0x19.toByte(), 0x1A.toByte(), 0x1B.toByte(), 0x1C.toByte(),
    0x1D.toByte(), 0x1E.toByte(), 0x1F.toByte(), 0x20.toByte(), 0x21.toByte(), 0x22.toByte(),
    0x23.toByte(), 0x24.toByte(), 0x25.toByte(), 0x26.toByte(), 0x27.toByte(), 0x28.toByte(),
    0x29.toByte(), 0x2A.toByte(), 0x2B.toByte(), 0x2C.toByte(), 0x2D.toByte(), 0x2E.toByte(),
    0x2F.toByte(), 0x30.toByte(), 0x31.toByte(), 0x32.toByte(), 0x33.toByte(), 0x34.toByte(),
    0x35.toByte(), 0x36.toByte(), 0x37.toByte(), 0x38.toByte(), 0x39.toByte(), 0x3A.toByte(),
    0x3B.toByte(), 0x3C.toByte(), 0x3D.toByte(), 0x3E.toByte(), 0x3F.toByte(), 0x40.toByte(),
    0x41.toByte(), 0x42.toByte(), 0x43.toByte(), 0x44.toByte(), 0x45.toByte(), 0x46.toByte(),
    0x47.toByte(), 0x48.toByte(), 0x49.toByte(), 0x4A.toByte(), 0x4B.toByte(), 0x4C.toByte(),
    0x4D.toByte(), 0x4E.toByte(), 0x4F.toByte(), 0x50.toByte(), 0x51.toByte(), 0x52.toByte(),
    0x53.toByte(), 0x54.toByte(), 0x55.toByte(), 0x56.toByte(), 0x57.toByte(), 0x58.toByte(),
    0x59.toByte(), 0x5A.toByte(), 0x5B.toByte(), 0x5C.toByte(), 0x5D.toByte(), 0x5E.toByte(),
    0x5F.toByte(), 0x60.toByte(), 0x61.toByte(), 0x62.toByte(), 0x63.toByte(), 0x64.toByte(),
    0x65.toByte(), 0x66.toByte(), 0x67.toByte(), 0x68.toByte(), 0x69.toByte(), 0x6A.toByte(),
    0x6B.toByte(), 0x6C.toByte(), 0x6D.toByte(), 0x6E.toByte(), 0x6F.toByte(), 0x70.toByte(),
    0x71.toByte(), 0x72.toByte(), 0x73.toByte(), 0x74.toByte(), 0x75.toByte(), 0x76.toByte(),
    0x77.toByte(), 0x78.toByte(), 0x79.toByte(), 0x7A.toByte(), 0x7B.toByte(), 0x7C.toByte(),
    0x7D.toByte(), 0x7E.toByte(), 0x7F.toByte(), 0x80.toByte(), 0x81.toByte(), 0x82.toByte(),
    0x83.toByte(), 0x84.toByte(), 0x85.toByte(), 0x86.toByte(), 0x87.toByte(), 0x88.toByte(),
    0x89.toByte(), 0x8A.toByte(), 0x8B.toByte(), 0x8C.toByte(), 0x8D.toByte(), 0x8E.toByte(),
    0x8F.toByte(), 0x90.toByte(), 0x91.toByte(), 0x92.toByte(), 0x93.toByte(), 0x94.toByte(),
    0x95.toByte(), 0x96.toByte(), 0x97.toByte(), 0x98.toByte(), 0x99.toByte(), 0x9A.toByte(),
    0x9B.toByte(), 0x9C.toByte(), 0x9D.toByte(), 0x9E.toByte(), 0x9F.toByte(), 0xA0.toByte(),
    0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte(),
    0xA7.toByte(), 0xA8.toByte(), 0xA9.toByte(), 0xAA.toByte(), 0xAB.toByte(), 0xAC.toByte(),
    0xAD.toByte(), 0xAE.toByte(), 0xAF.toByte(), 0xB0.toByte(), 0xB1.toByte(), 0xB2.toByte(),
    0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte(), 0xB6.toByte(), 0xB7.toByte(), 0xB8.toByte(),
    0xB9.toByte(), 0xBA.toByte(), 0xBB.toByte(), 0xBC.toByte(), 0xBD.toByte(), 0xBE.toByte(),
    0xBF.toByte(), 0xC0.toByte(), 0xC1.toByte(), 0xC2.toByte(), 0xC3.toByte(), 0xC4.toByte(),
    0xC5.toByte(), 0xC6.toByte(), 0xC7.toByte(), 0xC8.toByte(), 0xC9.toByte(), 0xCA.toByte(),
    0xCB.toByte(), 0xCC.toByte(), 0xCD.toByte(), 0xCE.toByte(), 0xCF.toByte(), 0xD0.toByte(),
    0xD1.toByte(), 0xD2.toByte(), 0xD3.toByte(), 0xD4.toByte(), 0xD5.toByte(), 0xD6.toByte(),
    0xD7.toByte(), 0xD8.toByte(), 0xD9.toByte(), 0xDA.toByte(), 0xDB.toByte(), 0xDC.toByte(),
    0xDD.toByte(), 0xDE.toByte(), 0xDF.toByte(), 0xE0.toByte(), 0xE1.toByte(), 0xE2.toByte(),
    0xE3.toByte(), 0xE4.toByte(), 0xE5.toByte(), 0xE6.toByte(), 0xE7.toByte(), 0xE8.toByte(),
    0xE9.toByte(), 0xEA.toByte(), 0xEB.toByte(), 0xEC.toByte(), 0xED.toByte(), 0xEE.toByte(),
    0xEF.toByte(), 0xF0.toByte(), 0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte(), 0xF4.toByte(),
    0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte(), 0xF8.toByte(), 0xF9.toByte(), 0xFA.toByte(),
    0xFB.toByte(), 0xFC.toByte(), 0xFD.toByte(), 0xFE.toByte(), 0xFF.toByte(), 0xAD.toByte(),
    0xF6.toByte(), 0x7F.toByte(), 0x81.toByte()
)
private val FLATE_PNG_UP = byteArrayOf(
    0x78.toByte(), 0x9C.toByte(), 0x63.toByte(), 0xE2.toByte(), 0x12.toByte(), 0x91.toByte(),
    0x63.toByte(), 0x62.toByte(), 0x65.toByte(), 0x65.toByte(), 0x05.toByte(), 0x00.toByte(),
    0x01.toByte(), 0x92.toByte(), 0x00.toByte(), 0x50.toByte()
)
private val FLATE_PNG_SUB = byteArrayOf(
    0x78.toByte(), 0x9C.toByte(), 0x63.toByte(), 0xE4.toByte(), 0xE2.toByte(), 0xE2.toByte(),
    0x02.toByte(), 0x00.toByte(), 0x00.toByte(), 0x44.toByte(), 0x00.toByte(), 0x20.toByte()
)
private val FLATE_PNG_PAETH = byteArrayOf(
    0x78.toByte(), 0x9C.toByte(), 0x63.toByte(), 0xE1.toByte(), 0xE2.toByte(), 0xE2.toByte(),
    0x02.toByte(), 0x00.toByte(), 0x00.toByte(), 0x50.toByte(), 0x00.toByte(), 0x23.toByte()
)
private val FLATE_TIFF = byteArrayOf(
    0x78.toByte(), 0x9C.toByte(), 0xE3.toByte(), 0xE2.toByte(), 0xE2.toByte(), 0x02.toByte(),
    0x00.toByte(), 0x00.toByte(), 0x3F.toByte(), 0x00.toByte(), 0x1F.toByte()
)
private val FLATE_TIFF_C2 = byteArrayOf(
    0x78.toByte(), 0x9C.toByte(), 0x63.toByte(), 0x64.toByte(), 0x62.toByte(), 0x66.toByte(),
    0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x18.toByte(), 0x00.toByte(), 0x0B.toByte()
)
private val FLATE_HELLO_TRUNC = byteArrayOf(
    0x78.toByte(), 0x9C.toByte(), 0xF3.toByte(), 0x48.toByte(), 0xCD.toByte(), 0xC9.toByte(),
    0xC9.toByte(), 0x57.toByte(), 0x28.toByte(), 0xCF.toByte(), 0x2F.toByte(), 0xCA.toByte(),
    0x49.toByte()
)
// Hand-derived LZW stream (EarlyChange=1, 9-bit codes 256, 65, 257) for "A".
private val LZW_A = byteArrayOf(0x80.toByte(), 0x10.toByte(), 0x60.toByte(), 0x20.toByte())

/** Test-only LZW encoder mirroring the decoder's EarlyChange rule. */
private fun lzwEncode(input: ByteArray, earlyChange: Int = 1): ByteArray {
    val table = HashMap<String, Int>()
    for (b in 0..255) table[String(byteArrayOf(b.toByte()), LATIN1)] = b
    var codeLength = 9
    var nextCode = 258
    val bits = ArrayList<Int>()
    fun emit(code: Int) {
        for (i in codeLength - 1 downTo 0) bits += (code shr i) and 1
    }
    fun add(sequence: String) {
        table[sequence] = nextCode++
        // Encoder-side rule: the decoder's table lags one entry behind (it adds
        // after decoding, the encoder after emitting), so the width switch is
        // evaluated one entry earlier than on the decode side.
        if (codeLength < 12 && (nextCode - 1) + earlyChange == (1 shl codeLength)) codeLength++
    }
    fun s(bytes: ByteArray) = String(bytes, LATIN1)
    emit(256)
    if (input.isEmpty()) {
        emit(257)
    } else {
        var w = s(byteArrayOf(input[0]))
        for (k in 1 until input.size) {
            val c = s(byteArrayOf(input[k]))
            if (w + c in table) {
                w += c
            } else {
                emit(table[w]!!)
                add(w + c)
                w = c
            }
        }
        emit(table[w]!!)
        // EOD width: behave as if the table had just grown once more, matching
        // the width the decoder will use when reading the final code.
        if (codeLength < 12 && nextCode + earlyChange == (1 shl codeLength)) codeLength++
        emit(257)
    }
    val out = ByteArray((bits.size + 7) / 8)
    for (i in bits.indices) {
        if (bits[i] == 1) out[i / 8] = (out[i / 8].toInt() or (0x80 shr (i % 8))).toByte()
    }
    return out
}

class PdfFilterTest {

    // -- ASCIIHexDecode ------------------------------------------------------

    @Test
    fun asciiHexBasic() {
        val decoded = decode(streamWith(name("ASCIIHexDecode"), "48656C6C6F>".toByteArray(LATIN1)))
        assertEquals("Hello", decoded.bytes.toString(LATIN1))
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun asciiHexOddDigitAndMissingEodWarn() {
        val decoded = decode(streamWith(name("ASCIIHexDecode"), "4A4".toByteArray(LATIN1)))
        assertArrayEquals(byteArrayOf(0x4A.toByte(), 0x40.toByte()), decoded.bytes)
        assertTrue(decoded.warnings.isNotEmpty())
    }

    @Test
    fun asciiHexInvalidCharStopsWithWarning() {
        val decoded = decode(streamWith(name("ASCIIHexDecode"), "4G>".toByteArray(LATIN1)))
        assertEquals(0, decoded.bytes.size)
        assertTrue(decoded.warnings.isNotEmpty())
    }

    // -- ASCII85Decode -------------------------------------------------------

    @Test
    fun ascii85KnownVector() {
        val decoded = decode(
            streamWith(name("ASCII85Decode"), "87cURD_*#TDfTZ)+X&!~>".toByteArray(LATIN1))
        )
        assertEquals("Hello, world!!!", decoded.bytes.toString(LATIN1))
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun ascii85ZAndShortGroup() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0),
            decode(streamWith(name("ASCII85Decode"), "z".toByteArray(LATIN1))).bytes
        )
        assertArrayEquals(
            "Man ".toByteArray(LATIN1),
            decode(streamWith(name("ASCII85Decode"), "9jqo^".toByteArray(LATIN1))).bytes
        )
    }

    @Test
    fun ascii85TruncatedReturnsPrefixWithWarning() {
        val decoded = decode(streamWith(name("ASCII85Decode"), "87cURD_*#T".toByteArray(LATIN1)))
        assertEquals("Hello, w", decoded.bytes.toString(LATIN1))
        assertTrue(decoded.warnings.isNotEmpty())
    }

    @Test
    fun ascii85InvalidCharStopsWithWarning() {
        val decoded = decode(streamWith(name("ASCII85Decode"), "87cURv".toByteArray(LATIN1)))
        assertEquals("Hell", decoded.bytes.toString(LATIN1))
        assertTrue(decoded.warnings.isNotEmpty())
    }

    // -- FlateDecode ---------------------------------------------------------

    @Test
    fun flateBasic() {
        val decoded = decode(streamWith(name("FlateDecode"), FLATE_HELLO))
        assertEquals("Hello world", decoded.bytes.toString(LATIN1))
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun flateFullByteRange() {
        val decoded = decode(streamWith(name("FlateDecode"), FLATE_0_255))
        assertArrayEquals(ByteArray(256) { it.toByte() }, decoded.bytes)
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun flateTruncatedReturnsPrefixWithWarning() {
        val full = "Hello world".toByteArray(LATIN1)
        val decoded = decode(streamWith(name("FlateDecode"), FLATE_HELLO_TRUNC))
        assertTrue(decoded.warnings.isNotEmpty())
        assertArrayEquals(full.copyOf(decoded.bytes.size), decoded.bytes)
    }

    private fun predictorParams(predictor: Long, columns: Long, colors: Long = 1) =
        dictOf(
            "Predictor" to PdfInteger(predictor),
            "Columns" to PdfInteger(columns),
            "Colors" to PdfInteger(colors),
            "BitsPerComponent" to PdfInteger(8)
        )

    @Test
    fun flatePngUpPredictor() {
        val decoded = decode(
            streamWith(name("FlateDecode"), FLATE_PNG_UP, predictorParams(12, 3))
        )
        assertArrayEquals(byteArrayOf(10, 20, 30, 15, 25, 35), decoded.bytes)
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun flatePngSubPredictor() {
        val decoded = decode(
            streamWith(name("FlateDecode"), FLATE_PNG_SUB, predictorParams(11, 3))
        )
        assertArrayEquals(byteArrayOf(10, 20, 30), decoded.bytes)
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun flatePngPaethPredictor() {
        val decoded = decode(
            streamWith(name("FlateDecode"), FLATE_PNG_PAETH, predictorParams(14, 3))
        )
        assertArrayEquals(byteArrayOf(10, 20, 30), decoded.bytes)
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun flateTiffPredictor() {
        val decoded = decode(
            streamWith(name("FlateDecode"), FLATE_TIFF, predictorParams(2, 3))
        )
        assertArrayEquals(byteArrayOf(10, 20, 30), decoded.bytes)
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun flateTiffPredictorTwoColors() {
        val decoded = decode(
            streamWith(name("FlateDecode"), FLATE_TIFF_C2, predictorParams(2, 2, 2))
        )
        assertArrayEquals(byteArrayOf(1, 2, 4, 6), decoded.bytes)
        assertTrue(decoded.warnings.isEmpty())
    }

    // -- LZWDecode -----------------------------------------------------------

    @Test
    fun lzwHandDerivedVector() {
        val decoded = decode(streamWith(name("LZWDecode"), LZW_A))
        assertEquals("A", decoded.bytes.toString(LATIN1))
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun lzwRoundtrips() {
        val inputs = listOf(
            "TOBEORNOTTOBEORTOBEORNOT".toByteArray(LATIN1),
            "AB".repeat(300).toByteArray(LATIN1),
            ByteArray(1024) { (it % 256).toByte() }
        )
        for (input in inputs) {
            val decoded = decode(streamWith(name("LZWDecode"), lzwEncode(input)))
            assertArrayEquals(input, decoded.bytes)
            assertTrue(decoded.warnings.isEmpty())
        }
    }

    @Test
    fun lzwEarlyChangeZeroRoundtrip() {
        val input = "TOBEORNOTTOBEORTOBEORNOT".toByteArray(LATIN1)
        val params = dictOf("EarlyChange" to PdfInteger(0))
        val decoded = decode(streamWith(name("LZWDecode"), lzwEncode(input, 0), params))
        assertArrayEquals(input, decoded.bytes)
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun lzwTruncatedReturnsPrefixWithWarning() {
        val input = "TOBEORNOTTOBEORTOBEORNOT".toByteArray(LATIN1)
        val encoded = lzwEncode(input).dropLast(3).toByteArray()
        val decoded = decode(streamWith(name("LZWDecode"), encoded))
        assertTrue(decoded.warnings.isNotEmpty())
        assertArrayEquals(input.copyOf(decoded.bytes.size), decoded.bytes)
    }

    // -- RunLengthDecode -----------------------------------------------------

    @Test
    fun runLengthBasic() {
        val data = byteArrayOf(0x01.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 0xFD.toByte(), 'C'.code.toByte(), 0x80.toByte())
        val decoded = decode(streamWith(name("RunLengthDecode"), data))
        assertEquals("ABCCCC", decoded.bytes.toString(LATIN1))
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun runLengthTruncatedRunWarns() {
        val decoded = decode(
            streamWith(name("RunLengthDecode"), byteArrayOf(0x05.toByte(), 'A'.code.toByte(), 'B'.code.toByte()))
        )
        assertEquals("AB", decoded.bytes.toString(LATIN1))
        assertTrue(decoded.warnings.isNotEmpty())
    }

    @Test
    fun runLengthMissingEodWarns() {
        val decoded = decode(
            streamWith(name("RunLengthDecode"), byteArrayOf(0x00.toByte(), 'Q'.code.toByte()))
        )
        assertEquals("Q", decoded.bytes.toString(LATIN1))
        assertTrue(decoded.warnings.isNotEmpty())
    }

    // -- Chains and plumbing -------------------------------------------------

    @Test
    fun chainedHexThenFlate() {
        val hex = FLATE_HELLO.joinToString("") { "%02X".format(it) } + ">"
        val stream = streamWith(
            arrayOf(name("ASCIIHexDecode"), name("FlateDecode")),
            hex.toByteArray(LATIN1),
            arrayOf(PdfNull, PdfNull)
        )
        val decoded = decode(stream)
        assertEquals("Hello world", decoded.bytes.toString(LATIN1))
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun decodeParmsReferenceIsResolved() {
        val stream = streamWith(
            name("FlateDecode"),
            FLATE_PNG_UP,
            PdfRef(9, 0)
        )
        val decoded = decode(stream) { ref ->
            if (ref == PdfRef(9, 0)) predictorParams(12, 3) else null
        }
        assertArrayEquals(byteArrayOf(10, 20, 30, 15, 25, 35), decoded.bytes)
    }

    @Test
    fun filterAbbreviations() {
        assertEquals(
            "A",
            decode(streamWith(name("AHx"), "41>".toByteArray(LATIN1))).bytes.toString(LATIN1)
        )
        assertEquals(
            "Hello world",
            decode(streamWith(name("Fl"), FLATE_HELLO)).bytes.toString(LATIN1)
        )
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0),
            decode(streamWith(name("A85"), "z".toByteArray(LATIN1))).bytes
        )
        assertEquals(
            "A",
            decode(streamWith(name("LZW"), LZW_A)).bytes.toString(LATIN1)
        )
        assertEquals(
            "Q",
            decode(
                streamWith(
                    name("RL"),
                    byteArrayOf(0x00.toByte(), 'Q'.code.toByte(), 0x80.toByte())
                )
            ).bytes.toString(LATIN1)
        )
    }

    @Test
    fun unknownFilterFails() {
        val result = streamWith(name("NoSuchFilter"), "x".toByteArray(LATIN1)).decoded()
        assertTrue(result.isFailure)
    }

    @Test
    fun noFilterIsIdentity() {
        val data = "raw".toByteArray(LATIN1)
        val stream = PdfStream(dictOf("Length" to PdfInteger(3)), data)
        val decoded = decode(stream)
        assertArrayEquals(data, decoded.bytes)
        assertTrue(decoded.warnings.isEmpty())
    }
}
