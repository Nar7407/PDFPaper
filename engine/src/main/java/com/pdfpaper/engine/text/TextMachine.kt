package com.pdfpaper.engine.text

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.PdfPage

/**
 * One text-showing operand turned into positioned Unicode text (§9.4.3).
 * No glyphs are rendered — [x0]..[y1] is an axis-aligned box in page space.
 */
class PdfTextRun(
    val text: String,
    /** Font size from the last Tf (untransformed text-space units). */
    val fontSize: Double,
    val fontName: String?,
    /** Axis-aligned bbox corners in page/user space (PDF coordinates, y up). */
    val x0: Double,
    val y0: Double,
    val x1: Double,
    val y1: Double,
    /**
     * Baseline start/end in page space, i.e. the text origin and origin +
     * advance mapped through (CTM ∘ Tm) (§9.4.4). The renderer uses the
     * direction start→end for the glyph rotation and the bbox for hit tests.
     */
    val originX: Double = x0,
    val originY: Double = y0,
    val endX: Double = x1,
    val endY: Double = y1,
    /** Embedded font program for rendering (§9.8), or null → system font. */
    val fontProgram: ByteArray? = null,
    /** "truetype" | "opentype" | "cff" | "type1" when [fontProgram] is set. */
    val fontFormat: String? = null,
    /** Text rendering mode from Tr (§9.3.6); 3 = invisible, 7 = add to clip. */
    val renderMode: Int = 0
) {
    override fun toString(): String =
        "PdfTextRun(\"$text\", size=$fontSize, [$x0,$y0 → $x1,$y1])"
}

/**
 * Per-document font resource cache: /Resources /Font name → [PdfFont]
 * (ISO 32000-1, §7.8.3, §9.6/§9.7). Caches per resource dictionary identity,
 * so page-level and form-XObject-level resources both work. Never returns
 * null: an unknown name yields the WinAnsi fallback font plus one warning.
 */
class PdfFontCache(private val doc: PdfDocument, private val warnings: MutableList<String>) {

    private val perResources = HashMap<PdfDict, MutableMap<String, PdfFont>>()
    private val warnedNames = HashMap<String, MutableSet<String>>()

    fun fontFor(resources: PdfDict?, name: String): PdfFont {
        val fontDict = fontDictOf(resources) ?: return fallback(resources, name)
        val cache = perResources.getOrPut(fontDict) { HashMap() }
        cache[name]?.let { return it }
        val resolved = FontResolver.resolve(fontDict[name], doc)
        if (resolved == null) return fallback(resources, name)
        if (resolved.degraded && warnOnce(resources, name)) {
            warnings += "font '/$name' degraded by missing data, continuing with fallback widths/encoding"
        }
        cache[name] = resolved
        return resolved
    }

    private fun fontDictOf(resources: PdfDict?): PdfDict? {
        val obj = resources?.get("Font") ?: return null
        return when (obj) {
            is PdfDict -> obj
            is PdfRef -> doc.resolve(obj) as? PdfDict
            else -> null
        }
    }

    private fun fallback(resources: PdfDict?, name: String): PdfFont {
        if (warnOnce(resources, name)) {
            warnings += "font '/$name' missing or unusable, extraction falls back to WinAnsi"
        }
        return FALLBACK_FONT
    }

    private fun warnOnce(resources: PdfDict?, name: String): Boolean {
        val key = (resources ?: EMPTY_DICT).entries.hashCode().toString()
        return warnedNames.getOrPut(key) { HashSet() }.add(name)
    }

    private companion object {
        val EMPTY_DICT = PdfDict(emptyMap())
    }
}

/**
 * Snapshot of the text state, taken by q and restored by Q (§9.3: the text
 * state is part of the graphics state, §8.4.1).
 */
class TextStateSnapshot internal constructor(
    internal val lineMat: PdfMatrix,
    internal val textMat: PdfMatrix,
    internal val font: PdfFont?,
    internal val fontSize: Double,
    internal val leading: Double,
    internal val charSpace: Double,
    internal val wordSpace: Double,
    internal val scale: Double,
    internal val rise: Double,
    internal val renderMode: Int
)

/**
 * Text-object interpreter shared by page text extraction and the display-list
 * builder (§9.4). Consumes only text operators: BT/ET, Tf, Td, TD, Tm, T*,
 * Tj, TJ, ', ", Tc, Tw, Tz, TL, Ts, Tr. The caller owns the graphics state
 * (q/Q/cm) and passes the CTM current at this command.
 */
class TextMachine(
    private val fontFor: (String) -> PdfFont,
    private val warnings: MutableList<String>
) {

    private var lineMat = PdfMatrix.IDENTITY
    private var textMat = PdfMatrix.IDENTITY
    private var font: PdfFont? = null
    private var fontSize = 0.0
    private var leading = 0.0
    private var charSpace = 0.0
    private var wordSpace = 0.0
    private var scale = 100.0
    private var rise = 0.0

    /** Text rendering mode from Tr (§9.3.6); 3 = invisible, 7 = clip. */
    private var renderMode = 0

    /** Current text state for q. */
    fun snapshot(): TextStateSnapshot =
        TextStateSnapshot(lineMat, textMat, font, fontSize, leading, charSpace, wordSpace, scale, rise, renderMode)

    /** Restores the text state saved by [snapshot] for Q. */
    fun restore(snapshot: TextStateSnapshot) {
        lineMat = snapshot.lineMat
        textMat = snapshot.textMat
        font = snapshot.font
        fontSize = snapshot.fontSize
        leading = snapshot.leading
        charSpace = snapshot.charSpace
        wordSpace = snapshot.wordSpace
        scale = snapshot.scale
        rise = snapshot.rise
        renderMode = snapshot.renderMode
    }

    /**
     * Handles one command; appends any produced runs to [out].
     * Returns true when the operator was a text operator (consumed).
     */
    fun handle(command: ContentCommand, ctm: PdfMatrix, out: MutableList<PdfTextRun>): Boolean {
        val ops = command.operands
        when (command.operator) {
            // --- text objects (§9.4.1) ---------------------------------
            "BT" -> {
                lineMat = PdfMatrix.IDENTITY
                textMat = lineMat
            }
            "ET" -> {}

            // --- text state (§9.4.3, Table 105) ------------------------
            "Tf" -> {
                val name = (ops.getOrNull(0) as? PdfName)?.name
                val size = number(ops.getOrNull(1))
                if (name != null) font = fontFor(name)
                if (size != null) fontSize = size
            }
            "Tc" -> number(ops.getOrNull(0))?.let { charSpace = it }
            "Tw" -> number(ops.getOrNull(0))?.let { wordSpace = it }
            "Tz" -> number(ops.getOrNull(0))?.let { scale = it }
            "TL" -> number(ops.getOrNull(0))?.let { leading = it }
            "Ts" -> number(ops.getOrNull(0))?.let { rise = it }
            "Tr" -> number(ops.getOrNull(0))?.let { renderMode = it.toInt() }

            // --- text positioning (§9.4.4, Table 108) ------------------
            "Td" -> {
                val tx = number(ops.getOrNull(0)) ?: return true
                val ty = number(ops.getOrNull(1)) ?: return true
                moveToTextLine(tx, ty)
            }
            "TD" -> {
                val ty = number(ops.getOrNull(1)) ?: return true
                leading = -ty
                moveToTextLine(number(ops.getOrNull(0)) ?: 0.0, ty)
            }
            "Tm" -> {
                val vals = ops.take(6).map { number(it) }
                if (vals.all { it != null }) {
                    lineMat = PdfMatrix(
                        vals[0]!!, vals[1]!!, vals[2]!!,
                        vals[3]!!, vals[4]!!, vals[5]!!
                    )
                    textMat = lineMat
                } else {
                    warnings += "Tm with non-numeric operand ignored"
                }
            }
            "T*" -> moveToTextLine(0.0, -leading)

            // --- text showing (§9.4.3, Table 106) ----------------------
            "Tj" -> stringOf(ops.getOrNull(0))?.let { showString(it, ctm, out) }
            "'" -> {
                moveToTextLine(0.0, -leading)
                stringOf(ops.getOrNull(0))?.let { showString(it, ctm, out) }
            }
            "\"" -> {
                number(ops.getOrNull(0))?.let { wordSpace = it }
                number(ops.getOrNull(1))?.let { charSpace = it }
                moveToTextLine(0.0, -leading)
                stringOf(ops.getOrNull(2))?.let { showString(it, ctm, out) }
            }
            "TJ" -> {
                val arr = ops.getOrNull(0) as? PdfArray ?: return true
                for (item in arr.items) {
                    when (item) {
                        is PdfString -> showString(item.bytes, ctm, out)
                        is PdfInteger, is PdfReal -> {
                            val n = number(item) ?: 0.0
                            shift(-(n / 1000.0) * fontSize * scale / 100.0)
                        }
                        else -> {}
                    }
                }
            }

            else -> return false
        }
        return true
    }

    /** Td/TD/T* core: translate the line matrix, then Tm = Tlm. */
    private fun moveToTextLine(tx: Double, ty: Double) {
        lineMat = lineMat * PdfMatrix.translation(tx, ty)
        textMat = lineMat
    }

    private fun shift(tx: Double) {
        textMat = textMat * PdfMatrix.translation(tx, 0.0)
    }

    /**
     * Shows one string operand (§9.4.3): decodes each code through the font,
     * advances Tm per glyph, and emits one run per operand. Simple fonts
     * consume one byte per code (§9.6); Type0 fonts two bytes per CID (§9.7.5).
     */
    private fun showString(bytes: ByteArray, ctm: PdfMatrix, out: MutableList<PdfTextRun>) {
        if (bytes.isEmpty()) return
        val activeFont = font ?: FALLBACK_FONT
        val startMat = textMat
        val sb = StringBuilder()
        var advance = 0.0

        fun glyph(text: String, width1000: Long, isWord: Boolean) {
            sb.append(text)
            val w = width1000 / 1000.0 * fontSize
            val tx = (w + charSpace + (if (isWord) wordSpace else 0.0)) * scale / 100.0
            advance += tx
            textMat = textMat * PdfMatrix.translation(tx, 0.0)
        }

        val type0 = activeFont as? PdfType0Font
        if (type0 != null) {
            for (cid in type0.cids(bytes)) {
                glyph(type0.decode(cid), type0.width1000(cid), isWord = false)
            }
        } else {
            val simple = (activeFont as? PdfSimpleFont) ?: FALLBACK_FONT
            for (b in bytes) {
                val code = b.toInt() and 0xFF
                glyph(simple.decode(code), simple.width1000(code), isWord = code == 32)
            }
        }

        if (sb.isEmpty()) return
        val full = ctm * startMat
        val top = rise + fontSize
        val xs = doubleArrayOf(
            full.apply(0.0, rise)[0], full.apply(advance, rise)[0],
            full.apply(advance, top)[0], full.apply(0.0, top)[0]
        )
        val ys = doubleArrayOf(
            full.apply(0.0, rise)[1], full.apply(advance, rise)[1],
            full.apply(advance, top)[1], full.apply(0.0, top)[1]
        )
        out += PdfTextRun(
            text = sb.toString(),
            fontSize = fontSize,
            fontName = activeFont.name,
            x0 = xs.min(), y0 = ys.min(), x1 = xs.max(), y1 = ys.max(),
            originX = full.apply(0.0, rise)[0],
            originY = full.apply(0.0, rise)[1],
            endX = full.apply(advance, rise)[0],
            endY = full.apply(advance, rise)[1],
            fontProgram = activeFont.embeddedProgram,
            fontFormat = activeFont.embeddedFormat,
            renderMode = renderMode
        )
    }

    private fun stringOf(op: PdfObject?): ByteArray? = (op as? PdfString)?.bytes

    private fun number(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }
}
