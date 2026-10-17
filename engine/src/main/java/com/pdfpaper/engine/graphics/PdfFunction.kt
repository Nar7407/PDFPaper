package com.pdfpaper.engine.graphics

import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream

/**
 * PDF function objects (ISO 32000-1, §7.10). Implemented: Type 0 (sampled,
 * nearest-neighbour lookup), Type 2 (exponential interpolation, §7.10.3) and
 * Type 3 (stitching, §7.10.4). Type 4 (PostScript calculator) is not
 * implemented: [PdfFunctions.resolve] returns null and callers degrade
 * gracefully (§7.10.5).
 */
abstract class PdfFunction {
    /** Input domains per argument (§7.10.2, Table 38). */
    abstract val domain: List<DoubleArray>

    /** Output ranges per result; null when the function omits /Range. */
    abstract val range: List<DoubleArray>?

    /** Evaluates [input] (length = domain size) → outputs (length = range size). */
    abstract fun evaluate(input: DoubleArray): DoubleArray

    protected fun clampInput(x: Double, index: Int): Double {
        val d = domain.getOrElse(index) { doubleArrayOf(-1e9, 1e9) }
        return x.coerceIn(d[0], d[1])
    }

    protected fun clampOutput(values: DoubleArray): DoubleArray {
        val r = range ?: return values
        for (i in values.indices) {
            val bounds = r.getOrNull(i) ?: continue
            values[i] = values[i].coerceIn(bounds[0], bounds[1])
        }
        return values
    }
}

/** Type 2: y_j = C0_j + x_j^n × (C1_j − C0_j) (§7.10.3). */
internal class ExpFunction(
    override val domain: List<DoubleArray>,
    override val range: List<DoubleArray>?,
    private val c0: DoubleArray,
    private val c1: DoubleArray,
    private val n: Double
) : PdfFunction() {
    override fun evaluate(input: DoubleArray): DoubleArray {
        val out = DoubleArray(maxOf(c0.size, c1.size))
        for (j in out.indices) {
            val x = clampInput(input.getOrElse(j) { input.lastOrNull() ?: 0.0 }, j)
            val start = c0.getOrElse(j) { c0.lastOrNull() ?: 0.0 }
            val end = c1.getOrElse(j) { c1.lastOrNull() ?: 1.0 }
            out[j] = start + Math.pow(x.coerceAtLeast(0.0), n) * (end - start)
        }
        return clampOutput(out)
    }
}

/** Type 3: piecewise stitch of sub-functions (§7.10.4). */
internal class StitchFunction(
    override val domain: List<DoubleArray>,
    override val range: List<DoubleArray>?,
    private val functions: List<PdfFunction>,
    private val bounds: DoubleArray,
    private val encode: List<DoubleArray>
) : PdfFunction() {

    override fun evaluate(input: DoubleArray): DoubleArray {
        if (functions.isEmpty()) return DoubleArray(0)
        val x = clampInput(input.getOrElse(0) { 0.0 }, 0)
        var part = 0
        while (part < bounds.size && x >= bounds[part]) part++
        part = part.coerceAtMost(functions.size - 1)
        val low = if (part == 0) domain[0][0] else bounds[part - 1]
        val high = if (part >= bounds.size) domain[0][1] else bounds[part]
        if (high <= low) return functions[part].evaluate(doubleArrayOf(x))
        val enc = encode.getOrNull(part)
        val e0 = enc?.getOrNull(0) ?: 0.0
        val e1 = enc?.getOrNull(1) ?: 1.0
        val sub = e0 + (x - low) / (high - low) * (e1 - e0)
        return clampOutput(functions[part].evaluate(doubleArrayOf(sub)))
    }
}

/** Type 0: sampled function with nearest-neighbour interpolation (§7.10.2). */
internal class SampledFunction(
    override val domain: List<DoubleArray>,
    override val range: List<DoubleArray>?,
    private val size: IntArray,
    private val encode: List<DoubleArray>,
    private val decode: List<DoubleArray>,
    private val bitsPerSample: Int,
    private val samples: ByteArray
) : PdfFunction() {

    private val inCount = size.size
    private val outCount = maxOf(decode.size, 1)
    private val maxSample = (1 shl bitsPerSample) - 1.0

    /** Flat index of the sample at [coords] (row-major, first dimension outermost). */
    private fun sampleAt(coords: IntArray): Double {
        var index = 0
        for (i in 0 until inCount) {
            val stride = size.drop(i + 1).fold(1L) { acc, s -> acc * s }
            index += (coords[i].coerceIn(0, size[i] - 1) * stride).toInt()
        }
        return readSample(index)
    }

    private fun readSample(sampleIndex: Int): Double {
        val bitOffset = sampleIndex.toLong() * bitsPerSample
        var value = 0L
        for (b in 0 until bitsPerSample) {
            val bit = bitOffset + b
            val byteIndex = (bit / 8).toInt()
            if (byteIndex >= samples.size) return 0.0
            val bitInByte = 7 - (bit % 8).toInt()
            val set = (samples[byteIndex].toInt() shr bitInByte) and 1
            value = (value shl 1) or set.toLong()
        }
        return value.toDouble()
    }

    override fun evaluate(input: DoubleArray): DoubleArray {
        val coords = IntArray(inCount)
        for (i in 0 until inCount) {
            val x = clampInput(input.getOrElse(i) { domain[i][0] }, i)
            val d = domain[i]
            val e = encode.getOrNull(i) ?: doubleArrayOf(0.0, 1.0)
            val t = if (d[1] > d[0]) (x - d[0]) / (d[1] - d[0]) else 0.0
            val enc = e[0] + t * (e[1] - e[0])
            val maxIndex = size[i] - 1
            coords[i] = Math.round(enc * maxIndex).toInt().coerceIn(0, maxIndex)
        }
        val base = run {
            var b = 0
            for (i in 0 until inCount) {
                val stride = size.drop(i + 1).fold(1) { acc, s -> acc * s }
                b += coords[i] * stride
            }
            b
        }
        val out = DoubleArray(outCount)
        for (j in 0 until outCount) {
            // Sample values are interleaved per output component.
            val raw = readSample(base * outCount + j) / maxSample
            val dec = decode.getOrNull(j) ?: doubleArrayOf(0.0, 1.0)
            out[j] = dec[0] + raw * (dec[1] - dec[0])
        }
        return clampOutput(out)
    }
}

/** Resolution of function dictionaries/streams (§7.10.1). */
object PdfFunctions {

    fun resolve(obj: PdfObject?, doc: PdfDocument): PdfFunction? {
        val dict: PdfDict = when (obj) {
            null -> return null
            is PdfRef -> {
                when (val resolved = doc.resolve(obj)) {
                    is PdfDict -> resolved
                    is PdfStream -> resolved.dict
                    else -> return null
                }
            }
            is PdfStream -> obj.dict
            is PdfDict -> obj
            else -> return null
        }
        val type = (dict["FunctionType"] as? PdfInteger)?.value?.toInt() ?: return null
        return when (type) {
            2 -> exp(dict)
            3 -> stitch(dict, doc)
            0 -> if (obj is PdfStream) sampled(obj, doc) else null
            else -> null // Type 4 (PostScript calculator): intentionally unsupported
        }
    }

    internal fun numbers(obj: PdfObject?): DoubleArray = (obj as? PdfArray)?.items
        ?.mapNotNull { toDouble(it) }?.toDoubleArray() ?: DoubleArray(0)

    internal fun domainOf(dict: PdfDict): List<DoubleArray> = pairsOf(dict["Domain"])
    internal fun rangeOf(dict: PdfDict): List<DoubleArray>? =
        dict["Range"]?.let { pairsOf(it) }

    private fun pairsOf(obj: PdfObject?): List<DoubleArray> {
        val nums = numbers(obj)
        return nums.toList().chunked(2).mapNotNull { if (it.size == 2) it.toDoubleArray() else null }
    }

    private fun exp(dict: PdfDict): PdfFunction? {
        val c0Raw = numbers(dict["C0"])
        val c1Raw = numbers(dict["C1"])
        val c0 = if (c0Raw.isEmpty()) doubleArrayOf(0.0) else c0Raw
        val c1 = if (c1Raw.isEmpty()) doubleArrayOf(1.0) else c1Raw
        val n = toDouble(dict["N"]) ?: return null
        val domain = domainOf(dict).ifEmpty { listOf(doubleArrayOf(0.0, 1.0)) }
        return ExpFunction(domain, rangeOf(dict), c0, c1, n)
    }

    private fun stitch(dict: PdfDict, doc: PdfDocument): PdfFunction? {
        val fns = (dict["Functions"] as? PdfArray)?.items
            ?.map { resolve(it, doc) ?: return null } ?: return null
        val domain = domainOf(dict).ifEmpty { listOf(doubleArrayOf(0.0, 1.0)) }
        val bounds = numbers(dict["Bounds"])
        val encode = pairsOf(dict["Encode"])
        if (bounds.size != fns.size - 1) return null
        return StitchFunction(domain, rangeOf(dict), fns, bounds, encode)
    }

    private fun sampled(stream: PdfStream, doc: PdfDocument): PdfFunction? {
        val dict = stream.dict
        val size = numbers(dict["Size"]).map { it.toInt() }.toIntArray()
        val bits = (toDouble(dict["BitsPerSample"]) ?: 8.0).toInt()
        if (size.isEmpty() || bits <= 0 || bits > 32) return null
        val samples = stream.decoded { doc.resolve(it) }.getOrNull()?.bytes ?: return null
        val domain = domainOf(dict).ifEmpty { size.map { doubleArrayOf(0.0, 1.0) } }
        val range = rangeOf(dict) ?: List(numbers(dict["Range"]).size / 2) { doubleArrayOf(0.0, 1.0) }
        val encode = pairsOf(dict["Encode"]).ifEmpty { size.map { doubleArrayOf(0.0, (it - 1).toDouble()) } }
        val decode = pairsOf(dict["Decode"])
        return SampledFunction(domain, range, size, encode, decode, bits, samples)
    }

    private fun toDouble(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }
}
