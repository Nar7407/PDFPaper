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
import com.pdfpaper.engine.model.PdfString
import kotlin.math.pow

/**
 * Color spaces (ISO 32000-1, §8.6.3-8.6.6).
 *
 * Implemented: DeviceGray/RGB/CMYK, CalGray/CalRGB, Indexed, ICCBased
 * (resolved to its /Alternate, §8.6.5.5) and Separation/DeviceN tint
 * transforms (§8.6.6.4). Pattern is recognised but unsupported. Resolved
 * colors are device RGB + alpha in 0..1 so the engine stays platform-free.
 */
/** A resolved color space able to turn component values (0..1) into RGB. */
sealed class PdfColorSpace {
    /** Number of components accepted by [toRgb]. */
    abstract val components: Int

    /** Converts normalized components to device RGB (alpha stays 1). */
    abstract fun toRgb(values: DoubleArray): PdfColor
}

object DeviceGrayCS : PdfColorSpace() {
    override val components = 1
    override fun toRgb(values: DoubleArray): PdfColor {
        val g = values.getOrElse(0) { 0.0 }
        return PdfColor(g, g, g)
    }
}

object DeviceRgbCS : PdfColorSpace() {
    override val components = 3
    override fun toRgb(values: DoubleArray): PdfColor =
        PdfColor(values.getOrElse(0) { 0.0 }, values.getOrElse(1) { 0.0 }, values.getOrElse(2) { 0.0 })
}

object DeviceCmykCS : PdfColorSpace() {
    override val components = 4
    override fun toRgb(values: DoubleArray): PdfColor {
        val c = values.getOrElse(0) { 0.0 }
        val m = values.getOrElse(1) { 0.0 }
        val y = values.getOrElse(2) { 0.0 }
        val k = values.getOrElse(3) { 0.0 }
        return PdfColor((1 - c) * (1 - k), (1 - m) * (1 - k), (1 - y) * (1 - k))
    }
}

/** CalGray (§8.6.5.2/3): gamma-corrected gray, white point ignored (approximation). */
class CalGrayCS(private val gamma: Double) : PdfColorSpace() {
    override val components = 1
    override fun toRgb(values: DoubleArray): PdfColor {
        val v = values.getOrElse(0) { 0.0 }.coerceIn(0.0, 1.0)
        val g = v.pow(gamma)
        return PdfColor(g, g, g)
    }
}

/**
 * CalRGB (§8.6.5.3): gamma correction + 3x3 matrix. The white point is
 * ignored (documented approximation: source and destination white points are
 * both assumed D65), which matches the common case of PDFs written for sRGB.
 */
class CalRgbCS(
    private val gamma: DoubleArray,
    private val matrix: DoubleArray
) : PdfColorSpace() {
    override val components = 3
    override fun toRgb(values: DoubleArray): PdfColor {
        // Apply the decoded values first (§8.6.5.3 equations 8.4-8.6 order).
        val out = DoubleArray(3)
        for (i in 0..2) {
            val v = values.getOrElse(i) { 0.0 }.coerceIn(0.0, 1.0)
            val g = if (gamma[i] == 1.0) v else v.pow(gamma[i])
            out[i] = g
        }
        val r = (matrix[0] * out[0] + matrix[1] * out[1] + matrix[2] * out[2]).coerceIn(0.0, 1.0)
        val g = (matrix[3] * out[0] + matrix[4] * out[1] + matrix[5] * out[2]).coerceIn(0.0, 1.0)
        val b = (matrix[6] * out[0] + matrix[7] * out[1] + matrix[8] * out[2]).coerceIn(0.0, 1.0)
        return PdfColor(r, g, b)
    }
}

/** Indexed (§8.6.6.3): one component, palette lookup into a base space. */
class IndexedCS(
    private val base: PdfColorSpace,
    private val highValue: Int,
    private val lookup: ByteArray
) : PdfColorSpace() {
    override val components = 1

    override fun toRgb(values: DoubleArray): PdfColor {
        // §8.6.6.3: the single component is the index itself (not normalized).
        val raw = values.getOrElse(0) { 0.0 }
        val index = Math.round(raw).toInt().coerceIn(0, maxOf(highValue, 0))
        val baseComponents = base.components
        val sampled = DoubleArray(baseComponents) { j ->
            val pos = index * baseComponents + j
            if (pos < lookup.size) (lookup[pos].toInt() and 0xFF) / 255.0 else 0.0
        }
        return base.toRgb(sampled)
    }
}

/** Separation/DeviceN (§8.6.6.4): tint values through a tint transform. */
class SeparationCS(
    private val alternate: PdfColorSpace,
    private val tintTransform: PdfFunction?,
    override val components: Int
) : PdfColorSpace() {
    override fun toRgb(values: DoubleArray): PdfColor {
        val fn = tintTransform ?: return alternate.toRgb(DoubleArray(alternate.components))
        val input = DoubleArray(components) { i -> (values.getOrElse(i) { 0.0 }).coerceIn(0.0, 1.0) }
        return alternate.toRgb(fn.evaluate(input))
    }
}

/** /Pattern is recognised but not painted (T14 candidate). */
object PatternCS : PdfColorSpace() {
    override val components = 1
    override fun toRgb(values: DoubleArray): PdfColor = PdfColor.BLACK
}

/** Result of resolving a /ColorSpace entry. */
class ResolvedColorSpace(val space: PdfColorSpace?, val warnings: List<String>)

/** Resolves color space objects from resources or dictionaries (§8.6.3). */
object ColorSpaces {

    fun resolve(
        obj: PdfObject?,
        resources: PdfDict?,
        doc: PdfDocument
    ): ResolvedColorSpace = resolveInternal(obj, resources, doc, 0, 0)

    private fun resolveInternal(
        obj: PdfObject?,
        resources: PdfDict?,
        doc: PdfDocument,
        depth: Int,
        warnDepth: Int
    ): ResolvedColorSpace {
        val warnings = ArrayList<String>()
        fun warn(message: String): ResolvedColorSpace {
            if (warnDepth < 4) warnings += message
            return ResolvedColorSpace(null, warnings)
        }
        if (depth > 8) return warn("color space nesting too deep")

        val actual = when (obj) {
            null -> return warn("missing color space")
            is PdfRef -> doc.resolve(obj) ?: return warn("dangling color space reference")
            else -> obj
        }

        when (actual) {
            is PdfName -> return nameSpace(actual.name, null, resources, doc, depth, warnings)
            is PdfArray -> {
                val head = when (val first = actual.items.firstOrNull()) {
                    is PdfName -> first.name
                    is PdfRef -> (doc.resolve(first) as? PdfName)?.name
                    else -> null
                } ?: return warn("color space array without a name")
                return nameSpace(head, actual, resources, doc, depth, warnings)
            }
            else -> return warn("unsupported color space object")
        }
    }

    private fun nameSpace(
        name: String,
        array: PdfArray?,
        resources: PdfDict?,
        doc: PdfDocument,
        depth: Int,
        warnings: MutableList<String>
    ): ResolvedColorSpace {
        when (name) {
            "DeviceGray", "G" -> return ResolvedColorSpace(DeviceGrayCS, warnings)
            "DeviceRGB", "RGB" -> return ResolvedColorSpace(DeviceRgbCS, warnings)
            "DeviceCMYK", "CMYK" -> return ResolvedColorSpace(DeviceCmykCS, warnings)
            "Pattern" -> return ResolvedColorSpace(PatternCS, warnings)
            "CalGray" -> {
                val dict = array?.items?.getOrNull(1) as? PdfDict
                    ?: return ResolvedColorSpace(null, warnings + "CalGray without dictionary")
                return ResolvedColorSpace(CalGrayCS(gammaOf(dict, "G")), warnings)
            }
            "CalRGB" -> {
                val dict = array?.items?.getOrNull(1) as? PdfDict
                    ?: return ResolvedColorSpace(null, warnings + "CalRGB without dictionary")
                val gamma = numbersOf(dict["Gamma"]) ?: doubleArrayOf(1.0, 1.0, 1.0)
                val matrix = numbersOf(dict["Matrix"]) ?: doubleArrayOf(
                    1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0
                )
                return ResolvedColorSpace(CalRgbCS(gamma, matrix), warnings)
            }
            "ICCBased" -> {
                // §8.6.5.5: use /Alternate when present; otherwise fall back to
                // the number of components in the profile stream.
                val stream = array?.items?.getOrNull(1)
                val dict = when (stream) {
                    is PdfStream -> stream.dict
                    is PdfRef -> (doc.resolve(stream) as? PdfStream)?.dict
                    else -> null
                } ?: return ResolvedColorSpace(null, warnings + "ICCBased without profile stream")
                val alternate = dict["Alternate"]
                if (alternate != null) {
                    val nested = resolveInternal(alternate, resources, doc, depth + 1, 0)
                    if (nested.space != null) return ResolvedColorSpace(nested.space, warnings)
                    warnings += "ICCBased /Alternate unsupported, falling back to component count"
                }
                val n = (dict["N"] as? PdfInteger)?.value?.toInt()
                val fallback = when (n) {
                    1 -> DeviceGrayCS
                    3 -> DeviceRgbCS
                    4 -> DeviceCmykCS
                    else -> null
                } ?: return ResolvedColorSpace(null, warnings + "ICCBased: unknown component count")
                return ResolvedColorSpace(fallback, warnings)
            }
            "Indexed", "I" -> {
                val items = array?.items ?: return ResolvedColorSpace(null, warnings + "Indexed without array")
                val baseResolved = resolveInternal(items.getOrNull(1), resources, doc, depth + 1, 0)
                val base = baseResolved.space
                    ?: return ResolvedColorSpace(null, warnings + "Indexed base space unsupported")
                warnings += baseResolved.warnings
                val hival = toDouble(items.getOrNull(2))?.toInt()
                    ?: return ResolvedColorSpace(null, warnings + "Indexed without /hival")
                val lookup = lookupBytes(items.getOrNull(3), doc)
                    ?: return ResolvedColorSpace(null, warnings + "Indexed lookup table missing")
                return ResolvedColorSpace(IndexedCS(base, hival, lookup), warnings)
            }
            "Separation" -> {
                val items = array?.items ?: return ResolvedColorSpace(null, warnings + "Separation without array")
                val alternate = resolveInternal(items.getOrNull(2), resources, doc, depth + 1, 0).space
                    ?: return ResolvedColorSpace(null, warnings + "Separation alternate unsupported")
                val tint = PdfFunctions.resolve(items.getOrNull(3), doc)
                if (tint == null) {
                    warnings += "Separation tint transform unsupported, using alternate space nil"
                }
                return ResolvedColorSpace(SeparationCS(alternate, tint, 1), warnings)
            }
            "DeviceN" -> {
                val items = array?.items ?: return ResolvedColorSpace(null, warnings + "DeviceN without array")
                val names = (items.getOrNull(1) as? PdfArray)?.items?.size ?: 1
                val alternate = resolveInternal(items.getOrNull(2), resources, doc, depth + 1, 0).space
                    ?: return ResolvedColorSpace(null, warnings + "DeviceN alternate unsupported")
                val tint = PdfFunctions.resolve(items.getOrNull(3), doc)
                return ResolvedColorSpace(SeparationCS(alternate, tint, names), warnings)
            }
        }
        // Named color space in /Resources /ColorSpace (§8.6.3).
        val namedDict = resources?.get("ColorSpace")
        val named = when (namedDict) {
            is PdfDict -> namedDict[name]
            is PdfRef -> (doc.resolve(namedDict) as? PdfDict)?.get(name)
            else -> null
        }
        if (named != null) return resolveInternal(named, resources, doc, depth + 1, 0)
        return ResolvedColorSpace(null, warnings + "unknown color space '/$name'")
    }

    private fun gammaOf(dict: PdfDict, key: String): Double =
        (toDouble(dict[key]) ?: 1.0).takeIf { it > 0.0 } ?: 1.0

    private fun numbersOf(obj: PdfObject?): DoubleArray? {
        val arr = obj as? PdfArray ?: return null
        val nums = arr.items.map { toDouble(it) }
        if (nums.any { it == null }) return null
        return nums.map { it!! }.toDoubleArray()
    }

    private fun lookupBytes(obj: PdfObject?, doc: PdfDocument): ByteArray? = when (obj) {
        is PdfString -> obj.bytes
        is PdfStream -> obj.decoded { doc.resolve(it) }.getOrNull()?.bytes
        is PdfRef -> when (val resolved = doc.resolve(obj)) {
            is PdfString -> resolved.bytes
            is PdfStream -> resolved.decoded { doc.resolve(it) }.getOrNull()?.bytes
            else -> null
        }
        else -> null
    }

    private fun toDouble(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }
}
