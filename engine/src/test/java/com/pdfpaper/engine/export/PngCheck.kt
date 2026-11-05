package com.pdfpaper.engine.export

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Reads back the engine's PNG output: validates the signature and every chunk
 * CRC, inflates IDAT and exposes the raw scanlines. Filter bytes are read (the
 * writer only emits "None", so anything else fails a test loudly).
 */
class CheckedPng(
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val colorType: Int,
    val scanlines: ByteArray,
    val chunkTypes: List<String>,
    val chunkData: Map<String, ByteArray>
) {
    /** Bytes per pixel for the accepted colour types. */
    val pixelBytes: Int
        get() = when (colorType) {
            0 -> 1
            2 -> 3
            3 -> 1
            4 -> 2
            6 -> 4
            else -> error("unexpected colour type $colorType")
        }

    fun pixel(x: Int, y: Int): ByteArray {
        val stride = 1 + width * pixelBytes
        val start = y * stride + 1 + x * pixelBytes
        return scanlines.copyOfRange(start, start + pixelBytes)
    }
}

object PngCheck {

    fun read(bytes: ByteArray): CheckedPng {
        require(bytes.size > 8) { "too short for a PNG" }
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        require(bytes.copyOfRange(0, 8).contentEquals(signature)) { "bad PNG signature" }

        var offset = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        val idat = ByteArrayOutputStream()
        val types = ArrayList<String>()
        val data = LinkedHashMap<String, ByteArray>()
        while (offset + 8 <= bytes.size) {
            val length = readInt(bytes, offset)
            require(length >= 0 && offset + 12 + length <= bytes.size) { "bad chunk length $length" }
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val payload = bytes.copyOfRange(offset + 8, offset + 8 + length)
            val expected = readInt(bytes, offset + 8 + length)
            val crc = CRC32()
            crc.update(bytes, offset + 4, 4 + length)
            require(crc.value.toInt() == expected) { "bad CRC in $type" }
            types += type
            data[type] = payload
            when (type) {
                "IHDR" -> {
                    width = readInt(payload, 0)
                    height = readInt(payload, 4)
                    bitDepth = payload[8].toInt() and 0xFF
                    colorType = payload[9].toInt() and 0xFF
                }
                "IDAT" -> idat.write(payload)
                "IEND" -> break
            }
            offset += 12 + length
        }
        require(types.firstOrNull() == "IHDR") { "IHDR must come first" }
        require(types.lastOrNull() == "IEND") { "missing IEND" }
        val raw = inflate(idat.toByteArray())
        val stride = 1 + (width * bitDepth * bands(colorType) + 7) / 8
        require(raw.size == stride * height) { "scanline size ${raw.size} != ${stride * height}" }
        for (y in 0 until height) {
            require(raw[y * stride].toInt() == 0) { "unexpected PNG filter at row $y" }
        }
        return CheckedPng(width, height, bitDepth, colorType, raw, types, data)
    }

    private fun bands(colorType: Int): Int = when (colorType) {
        0, 3 -> 1
        2 -> 3
        4 -> 2
        6 -> 4
        else -> error("unexpected colour type $colorType")
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
                continue
            }
            out.write(buffer, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}

/** Reads a 1-bit scanline as booleans (most significant bit first). */
fun CheckedPng.stencilRow(y: Int): BooleanArray {
    val stride = 1 + (width + 7) / 8
    val base = y * stride + 1
    return BooleanArray(width) { x ->
        val byte = scanlines[base + x / 8].toInt() and 0xFF
        (byte and (0x80 ushr (x % 8))) != 0
    }
}
