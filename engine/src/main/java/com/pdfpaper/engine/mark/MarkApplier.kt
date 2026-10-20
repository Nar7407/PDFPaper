package com.pdfpaper.engine.mark

import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.edit.ImageXObjects
import com.pdfpaper.engine.edit.RasterDecoder
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.write.PdfDocumentBuilder
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Puts the added marks on the pages.
 *
 * Everything the caller adds lives in its own content stream, so the page's
 * own content stream is never touched: the added stream is put in front of it
 * (under the content) or after it (over the content), and the whole block is
 * marked as optional content (§8.11), so a reader can list and switch it off
 * and the removal function can find it again.
 *
 * The applier is bound to the builder of the write that is running, so the
 * font, the graphics state and the optional content group are created once
 * and shared by every page.
 */
internal class MarkApplier(
    /** The name of the optional content group, e.g. "PDFPaper watermark". */
    private val group: String,
    private val fontSpec: MarkFont,
    private val fontSize: Double,
    private val color: PdfColor,
    private val opacity: Double
) {

    /** The property name the content is marked with. */
    val propertyName: String = MarkGroups.propertyName(group)

    private var builder: PdfDocumentBuilder? = null
    private var font: MarkTextFont? = null
    private var ocg: PdfRef? = null
    private var graphicsState: Pair<PdfName, PdfRef>? = null
    private var image: Triple<PdfName, PdfRef, Int>? = null
    private var imageHeight = 0

    /** Binds the applier to the write that is running. */
    fun bind(builder: PdfDocumentBuilder) {
        this.builder = builder
    }

    /** The size of the added text, in points. */
    fun fontSize(): Double = fontSize

    /** The colour of the added text. */
    fun color(): PdfColor = color

    /** The opacity of the added marks. */
    fun opacity(): Double = opacity

    /** The font for the added text, created on first use. */
    fun font(texts: List<String>): MarkTextFont? {
        font?.let { return it }
        val target = builder ?: return null
        val created = when (val spec = fontSpec) {
            is MarkFont.Standard -> MarkFonts.standard(target, spec.font)
            is MarkFont.Embedded -> MarkFonts.embedded(target, spec, texts)
        }
        font = created
        return created
    }

    /** The optional content group the added marks live in (§8.11). */
    fun ocg(): PdfRef? {
        ocg?.let { return it }
        val target = builder ?: return null
        val ref = target.add(
            PdfDict(
                linkedMapOf(
                    PdfName("Type") to PdfName("OCG"),
                    PdfName("Name") to PdfString(group)
                )
            )
        )
        ocg = ref
        return ref
    }

    /** The graphics state that carries the opacity. */
    fun graphicsState(): Pair<PdfName, PdfRef>? {
        graphicsState?.let { return it }
        val target = builder ?: return null
        val level = opacity.coerceIn(0.0, 1.0)
        val ref = target.add(
            PdfDict(
                linkedMapOf(
                    PdfName("Type") to PdfName("ExtGState"),
                    PdfName("ca") to PdfReal(level),
                    PdfName("CA") to PdfReal(level)
                )
            )
        )
        val pair = PdfName("PGS1") to ref
        graphicsState = pair
        return pair
    }

    /**
     * The watermark image, added as an XObject on first use. Returns the
     * resource name, the reference and the pixel width.
     */
    fun image(bytes: ByteArray, decoder: RasterDecoder?): Triple<PdfName, PdfRef, Int>? {
        image?.let { return it }
        val target = builder ?: return null
        val data = EditImages.decode(bytes, decoder ?: EditImages.defaultDecoder()).getOrNull() ?: return null
        val ref = ImageXObjects.create(target, data)
        val triple = Triple(PdfName("PFIm1"), ref, data.width)
        image = triple
        imageHeight = data.height
        return triple
    }

    /** The pixel height of the watermark image, when there is one. */
    fun imageHeight(): Int = imageHeight

    /**
     * Adds [content] to the page: the page's own content is kept as it was and
     * the added stream is placed before or after it.
     */
    fun applyToPage(
        entries: LinkedHashMap<PdfName, PdfObject>,
        content: ByteArray,
        behind: Boolean,
        resources: Map<PdfName, PdfObject>
    ) {
        val target = builder ?: return
        val original = entries[PdfName("Contents")]
        val added = target.addStream(PdfDict(emptyMap()), content)
        entries[PdfName("Contents")] = when {
            original == null -> PdfArray(listOf(added))
            // A page can already have several content streams; the added one
            // goes in front of or behind them, never around them.
            original is PdfArray ->
                if (behind) PdfArray(listOf(added) + original.items)
                else PdfArray(original.items + added)
            behind -> PdfArray(listOf(added, original))
            else -> PdfArray(listOf(original, added))
        }
        val existing = entries[PdfName("Resources")] as? PdfDict
        val merged = LinkedHashMap<PdfName, PdfObject>(existing?.entries ?: emptyMap())
        for ((key, value) in resources) {
            val old = merged[key]
            merged[key] = if (old is PdfDict && value is PdfDict) {
                val dict = LinkedHashMap<PdfName, PdfObject>(old.entries)
                dict.putAll(value.entries)
                PdfDict(dict)
            } else {
                value
            }
        }
        entries[PdfName("Resources")] = PdfDict(merged)
    }
}

/**
 * Draws the marks: text and images at a place on the page, with a colour, an
 * opacity and a rotation.
 */
internal class MarkPainter(
    private val font: MarkTextFont,
    private val fontSize: Double,
    private val color: PdfColor,
    private val opacity: Double,
    private val gsName: PdfName
) {

    /** Writes one line of text with its baseline at ([x], [y]). */
    fun drawText(
        out: ByteArrayOutputStream,
        text: String,
        x: Double,
        y: Double,
        rotationDegrees: Double = 0.0,
        align: MarkAlign = MarkAlign.LEFT
    ) {
        if (text.isEmpty()) return
        val width = font.width(text, fontSize)
        val startX = when (align) {
            MarkAlign.LEFT -> x
            MarkAlign.CENTER -> x - width / 2.0
            MarkAlign.RIGHT -> x - width
        }
        out.write("q\n".toByteArray(StandardCharsets.US_ASCII))
        if (opacity < 1.0) {
            out.write("/${gsName.name} gs\n".toByteArray(StandardCharsets.US_ASCII))
        }
        val rotation = Math.toRadians(rotationDegrees)
        val cosA = cos(rotation)
        val sinA = sin(rotation)
        out.write(
            "%.6f %.6f %.6f %.6f %.4f %.4f cm\n".format(cosA, sinA, -sinA, cosA, startX, y)
                .toByteArray(StandardCharsets.US_ASCII)
        )
        out.write("BT\n".toByteArray(StandardCharsets.US_ASCII))
        out.write(
            "/${font.resourceName.name} ${number(fontSize)} Tf\n".toByteArray(StandardCharsets.US_ASCII)
        )
        out.write(
            "%.4f %.4f %.4f rg\n".format(color.red, color.green, color.blue)
                .toByteArray(StandardCharsets.US_ASCII)
        )
        out.write("1 0 0 1 0 0 Tm\n".toByteArray(StandardCharsets.US_ASCII))
        font.writeString(text, out)
        out.write(" Tj\n".toByteArray(StandardCharsets.US_ASCII))
        out.write("ET\nQ\n".toByteArray(StandardCharsets.US_ASCII))
    }

    /** Writes an image placement of [width] x [height] points at ([x], [y]). */
    fun drawImage(
        out: ByteArrayOutputStream,
        imageName: PdfName,
        width: Double,
        height: Double,
        x: Double,
        y: Double,
        rotationDegrees: Double = 0.0
    ) {
        out.write("q\n".toByteArray(StandardCharsets.US_ASCII))
        if (opacity < 1.0) {
            out.write("/${gsName.name} gs\n".toByteArray(StandardCharsets.US_ASCII))
        }
        val rotation = Math.toRadians(rotationDegrees)
        val cosA = cos(rotation)
        val sinA = sin(rotation)
        out.write(
            "%.6f %.6f %.6f %.6f 0 0 cm\n".format(cosA, sinA, -sinA, cosA)
                .toByteArray(StandardCharsets.US_ASCII)
        )
        out.write(
            "%.4f 0 0 %.4f %.4f %.4f cm\n".format(width, height, x, y)
                .toByteArray(StandardCharsets.US_ASCII)
        )
        out.write("/${imageName.name} Do\n".toByteArray(StandardCharsets.US_ASCII))
        out.write("Q\n".toByteArray(StandardCharsets.US_ASCII))
    }

    private fun number(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
        else "%.4f".format(value)
}

/**
 * The page box the marks are placed on: the crop box when the page has one,
 * the media box otherwise.
 */
internal fun pageBox(page: PdfPage): PdfRect =
    PdfRect.of(page.cropBox) ?: PdfRect.of(page.mediaBox)
        ?: PdfRect(0.0, 0.0, 612.0, 792.0)

/** The marked-content wrapper that ties the drawing to the group. */
internal fun markContent(
    propertyName: String,
    body: ByteArray
): ByteArray {
    val out = ByteArrayOutputStream(body.size + 64)
    out.write("/OC /$propertyName BDC\n".toByteArray(StandardCharsets.US_ASCII))
    out.write(body)
    out.write("EMC\n".toByteArray(StandardCharsets.US_ASCII))
    return out.toByteArray()
}
