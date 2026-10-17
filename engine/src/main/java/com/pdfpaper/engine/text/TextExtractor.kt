package com.pdfpaper.engine.text

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.PdfPage

/**
 * Text extraction (ISO 32000-1, §9.4 text state and text operators, §9.6/§9.7
 * font model): walks the content stream in order and turns text-showing
 * operators into positioned Unicode runs. No glyphs are rendered — each
 * [PdfTextRun] carries its text, font, font size and an axis-aligned bounding
 * box computed from the text/graphics matrices.
 *
 * Supported here: BT/ET, Tf, Td, TD, Tm, T*, Tj, TJ, ' , ", Tc, Tw, Tz, TL,
 * Ts (Tr has no effect on extraction), plus the minimal q/Q/cm tracking the
 * bounding boxes need. Reading order = content-stream order.
 */
class PdfTextRun(
    val text: String,
    /** Font size from the last Tf (user-space units, may be 0 for degenerate files). */
    val fontSize: Double,
    val fontName: String?,
    /** Axis-aligned bbox corners in user space (PDF coordinates, y up). */
    val x0: Double,
    val y0: Double,
    val x1: Double,
    val y1: Double
) {
    override fun toString(): String =
        "PdfTextRun(\"$text\", size=$fontSize, [$x0,$y0 → $x1,$y1])"
}

/** Result of extracting one page: runs in reading order plus warnings. */
class PageText(val runs: List<PdfTextRun>, val warnings: List<String>)

/** 2-D affine matrix [a b c d e f] (§8.3.3): x' = a·x + c·y + e. */
internal class Mat(
    val a: Double, val b: Double, val c: Double,
    val d: Double, val e: Double, val f: Double
) {
    /**
     * Product this·other where [other] applies FIRST (column-vector
     * convention) — matches the "operand applied, then current matrix" rule
     * the spec defines for cm/Td/glyph advances.
     */
    operator fun times(other: Mat): Mat {
        val aa = a * other.a + c * other.b
        val bb = b * other.a + d * other.b
        val ac = a * other.c + c * other.d
        val bd = b * other.c + d * other.d
        val ae = a * other.e + c * other.f + e
        val bf = b * other.e + d * other.f + f
        return Mat(aa, bb, ac, bd, ae, bf)
    }

    fun apply(x: Double, y: Double): DoubleArray =
        doubleArrayOf(a * x + c * y + e, b * x + d * y + f)

    companion object {
        val IDENTITY = Mat(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        fun translation(tx: Double, ty: Double): Mat =
            Mat(1.0, 0.0, 0.0, 1.0, tx, ty)
    }
}

/** Extract a page's text (§9.4.4). Never throws for malformed content. */
fun PdfPage.extractText(): PageText {
    val warnings = ArrayList<String>()
    val contents = contents().fold(
        onSuccess = { it },
        onFailure = { warnings += "contents failed: ${it.message}"; null }
    ) ?: return PageText(emptyList(), warnings)
    if (contents.warnings.isNotEmpty()) {
        warnings += contents.warnings.map { "content: $it" }
    }

    val parsed = parseContent(contents.bytes)
    if (parsed.warnings.isNotEmpty()) {
        warnings += parsed.warnings.map { "content: $it" }
    }

    val state = ExtractorState(this, warnings)
    for (command in parsed.commands) state.execute(command)
    return PageText(state.runs, warnings)
}

/** Mutable per-page extraction state. */
private class ExtractorState(private val page: PdfPage, private val warnings: MutableList<String>) {

    // --- graphics state (§8.4): minimal for text positioning -----------
    private var ctm = Mat.IDENTITY
    private val ctmStack = ArrayDeque<Mat>()

    // --- text state (§9.4.4) -------------------------------------------
    private var lineMat = Mat.IDENTITY
    private var textMat = Mat.IDENTITY
    private var font: PdfFont? = null
    private var fontSize = 0.0
    private var leading = 0.0
    private var charSpace = 0.0
    private var wordSpace = 0.0
    private var scale = 100.0
    private var rise = 0.0

    // --- fonts (§7.8.3 /Resources /Font) -------------------------------
    private val fontResources: Map<String, PdfObject> = loadFontResources()
    private val fontCache = HashMap<String, PdfFont>()
    private val warnedFonts = HashSet<String>()

    val runs = ArrayList<PdfTextRun>()

    private fun loadFontResources(): Map<String, PdfObject> {
        val resources = page.resources ?: return emptyMap()
        val fontsObj = resources["Font"] ?: return emptyMap()
        val resolved: PdfDict? = when (fontsObj) {
            is PdfDict -> fontsObj
            is PdfRef -> page.document.resolve(fontsObj) as? PdfDict
            else -> null
        }
        return resolved?.entries?.mapKeys { it.key.name } ?: emptyMap()
    }

    private fun resolveFontOrNull(name: String): PdfFont? {
        val cached = fontCache[name]
        if (cached != null) return cached
        val resolved = FontResolver.resolve(fontResources[name], page.document)
        if (resolved != null) {
            if (resolved.degraded && warnedFonts.add(name)) {
                warnings += "font '/$name' degraded by missing data, continuing with fallback widths/encoding"
            }
            fontCache[name] = resolved
            return resolved
        }
        if (warnedFonts.add(name)) {
            warnings += "font '/$name' missing or unusable, extraction falls back to WinAnsi"
        }
        fontCache[name] = FALLBACK_FONT
        return FALLBACK_FONT
    }

    fun execute(command: ContentCommand) {
        val ops = command.operands
        when (command.operator) {
            // --- text objects (§9.4.1) ---------------------------------
            "BT" -> {
                lineMat = Mat.IDENTITY
                textMat = lineMat
            }
            "ET" -> {}

            // --- text state (§9.4.3, Table 105) ------------------------
            "Tf" -> {
                val name = (ops.getOrNull(0) as? PdfName)?.name
                val size = number(ops.getOrNull(1))
                if (name != null) font = resolveFontOrNull(name)
                if (size != null) fontSize = size
            }
            "Tc" -> number(ops.getOrNull(0))?.let { charSpace = it }
            "Tw" -> number(ops.getOrNull(0))?.let { wordSpace = it }
            "Tz" -> number(ops.getOrNull(0))?.let { scale = it }
            "TL" -> number(ops.getOrNull(0))?.let { leading = it }
            "Ts" -> number(ops.getOrNull(0))?.let { rise = it }
            "Tr" -> {} // rendering mode: irrelevant for extraction

            // --- text positioning (§9.4.4, Table 108) ------------------
            "Td" -> {
                val tx = number(ops.getOrNull(0)) ?: return
                val ty = number(ops.getOrNull(1)) ?: return
                moveToTextLine(tx, ty)
            }
            "TD" -> {
                val ty = number(ops.getOrNull(1)) ?: return
                leading = -ty
                val tx = number(ops.getOrNull(0)) ?: 0.0
                moveToTextLine(tx, ty)
            }
            "Tm" -> {
                val vals = ops.take(6).map { number(it) }
                if (vals.all { it != null }) {
                    lineMat = Mat(
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
            "Tj" -> resolveStringOperand(ops.getOrNull(0))?.let { showString(it) }
            "'" -> {
                moveToTextLine(0.0, -leading)
                resolveStringOperand(ops.getOrNull(0))?.let { showString(it) }
            }
            "\"" -> {
                val w = number(ops.getOrNull(0))
                val c = number(ops.getOrNull(1))
                if (w != null) wordSpace = w
                if (c != null) charSpace = c
                moveToTextLine(0.0, -leading)
                resolveStringOperand(ops.getOrNull(2))?.let { showString(it) }
            }
            "TJ" -> {
                val arr = ops.getOrNull(0) as? PdfArray ?: return
                for (item in arr.items) {
                    when (item) {
                        is PdfString -> showString(item.bytes)
                        is PdfInteger, is PdfReal -> {
                            val n = number(item) ?: 0.0
                            shift(-(n / 1000.0) * fontSize * scale / 100.0)
                        }
                        else -> {}
                    }
                }
            }

            // --- graphics state (§8.4): the pieces text bboxes depend on
            "q" -> ctmStack.addLast(ctm)
            "Q" -> if (ctmStack.isNotEmpty()) ctm = ctmStack.removeLast()
            "cm" -> {
                val vals = ops.take(6).map { number(it) }
                if (vals.all { it != null }) {
                    ctm = ctm * Mat(
                        vals[0]!!, vals[1]!!, vals[2]!!,
                        vals[3]!!, vals[4]!!, vals[5]!!
                    )
                }
            }
            else -> {} // paths, colors, images...: T8's domain; ignored here
        }
    }

    /** Td/TD/T* core: translate the line matrix, then Tm = Tlm. */
    private fun moveToTextLine(tx: Double, ty: Double) {
        lineMat = lineMat * Mat.translation(tx, ty)
        textMat = lineMat
    }

    private fun shift(tx: Double) {
        textMat = textMat * Mat.translation(tx, 0.0)
    }

    private fun resolveStringOperand(op: PdfObject?): ByteArray? = (op as? PdfString)?.bytes

    /**
     * Shows one string operand (§9.4.3): decodes each code through the font,
     * advances Tm per glyph, and emits a single positioned run for the whole
     * operand. Simple fonts consume one byte per code (§9.6); Type0 fonts
     * consume two bytes per CID (§9.7.5).
     */
    private fun showString(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val font = font ?: FALLBACK_FONT
        val target = font as? PdfType0Font
        val startMat = textMat
        val sb = StringBuilder()
        var advance = 0.0

        fun glyph(text: String, width1000: Long, isWord: Boolean) {
            sb.append(text)
            val w = width1000 / 1000.0 * fontSize
            val tx = (w + charSpace + (if (isWord) wordSpace else 0.0)) * scale / 100.0
            advance += tx
            textMat = textMat * Mat.translation(tx, 0.0)
        }

        if (target != null) {
            val type0 = target
            val cids = type0.cids(bytes)
            for (cid in cids) {
                glyph(type0.decode(cid), type0.width1000(cid), isWord = false)
            }
        } else {
            val simple = (font as? PdfSimpleFont) ?: FALLBACK_FONT
            for (b in bytes) {
                val code = b.toInt() and 0xFF
                glyph(simple.decode(code), simple.width1000(code), isWord = code == 32)
            }
        }

        if (sb.isEmpty()) return
        emitRun(sb.toString(), startMat, advance)
    }

    /** Bounding box: text-space quad (0..advance, rise..rise+size) mapped by
     *  startTm then CTM, reduced to an axis-aligned rectangle. */
    private fun emitRun(text: String, startMat: Mat, advance: Double) {
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
        runs += PdfTextRun(
            text = text,
            fontSize = fontSize,
            fontName = font?.name,
            x0 = xs.min(),
            y0 = ys.min(),
            x1 = xs.max(),
            y1 = ys.max()
        )
    }

    private fun number(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }
}
