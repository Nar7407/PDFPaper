package com.pdfpaper.convert

import com.pdfpaper.convert.pptx.EMU_PER_POINT
import com.pdfpaper.convert.pptx.Emu
import com.pdfpaper.convert.pptx.PictureShape
import com.pdfpaper.convert.pptx.PptxDeck
import com.pdfpaper.convert.pptx.PptxWriter
import com.pdfpaper.convert.pptx.Slide
import com.pdfpaper.convert.pptx.SlideShape
import com.pdfpaper.convert.pptx.TextBoxShape
import com.pdfpaper.convert.raster.PngWriter
import com.pdfpaper.convert.raster.SoftwareRenderer
import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.graphics.DrawRasterImage
import com.pdfpaper.engine.graphics.DrawTextRun
import com.pdfpaper.engine.graphics.FillPath
import com.pdfpaper.engine.graphics.FillStrokePath
import com.pdfpaper.engine.graphics.StrokePath
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.image.RasterImage
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.render.renderDisplayList
import com.pdfpaper.engine.text.PdfTextRun
import java.io.File
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** How a PDF page becomes a slide. */
class PdfToPptxOptions(
    /**
     * Render each page to a picture and put invisible (selectable) text boxes
     * on top: exact layout, but no editable shapes. Without it, text and
     * images become editable shapes and vector art is skipped.
     *
     * The engine has no glyph rasterizer, so the "rendered" page contains no
     * text — fidelity mode trades visible text for exact vector/image layout.
     */
    val fidelity: Boolean = false,
    /** Raster resolution for [fidelity], in dots per inch. */
    val dpi: Double = 150.0,
    val maxPixels: Int = PageGeometry.DEFAULT_MAX_PIXELS,
    val maxPages: Int = Int.MAX_VALUE,
    val title: String? = null
)

class PdfToPptxResult(val bytes: ByteArray, val report: ConversionReport)

/**
 * Converts a PDF to PPTX (T17): one slide per page, slide size taken from the
 * page box, text runs to text boxes, images to pictures, vector art either
 * skipped (and reported) or folded into a rendered page picture.
 *
 * This class only builds the deck model and the loss report; [PptxWriter] does
 * the packaging.
 */
object PdfToPptx {

    fun convertFile(input: File, options: PdfToPptxOptions = PdfToPptxOptions()): PdfToPptxResult {
        val document = PdfDocument.open(FileSource(input)).getOrThrow()
        return document.use { convert(it, options, input.name) }
    }

    fun convert(
        document: PdfDocument,
        options: PdfToPptxOptions = PdfToPptxOptions(),
        sourceName: String = "input.pdf"
    ): PdfToPptxResult {
        val pages = document.pages()
        val limit = min(pages.size, maxOf(0, options.maxPages))
        val slides = ArrayList<Slide>(limit)
        val reportPages = ArrayList<PageLosses>(limit)
        val media = MediaBag()

        var slideWidth = Emu.ofPoints(PageGeometry.DEFAULT_WIDTH)
        var slideHeight = Emu.ofPoints(PageGeometry.DEFAULT_HEIGHT)

        for (index in 0 until limit) {
            val page = pages[index]
            val box = PageGeometry.boxOf(page)
            slideWidth = Emu.ofPoints(box.rotatedWidth)
            slideHeight = Emu.ofPoints(box.rotatedHeight)
            val emuMatrix = PageGeometry.matrixFor(box, EMU_PER_POINT.toDouble(), EMU_PER_POINT.toDouble())
            val list = page.buildDisplayList(decoder = null)
            val warnings = ArrayList<String>(list.warnings)

            val shapes = ArrayList<SlideShape>()
            val skippedImages = ArrayList<String>()
            val unknownFonts = LinkedHashSet<String>()
            var nextId = 2
            var textBoxes = 0
            var pictures = 0
            var rotatedText = 0
            var vectorFills = 0
            var vectorStrokes = 0

            for (command in list.commands) {
                when (command) {
                    is FillPath -> vectorFills++
                    is FillStrokePath -> {
                        vectorFills++
                        vectorStrokes++
                    }
                    is StrokePath -> vectorStrokes++
                    else -> {}
                }
            }

            if (options.fidelity) {
                val plan = PageGeometry.plan(box, options.dpi / 72.0, options.maxPixels)
                if (plan.downscaled) {
                    warnings += "page rendered at %.0f dpi to respect the pixel budget".format(plan.scale * 72.0)
                }
                val renderer = SoftwareRenderer(plan.pixelWidth, plan.pixelHeight, plan.matrix)
                renderDisplayList(list, renderer)
                if (renderer.textRunsSkipped > 0) {
                    warnings += "background picture omits ${renderer.textRunsSkipped} text run(s): no glyph rasterizer"
                }
                val partName = "ppt/media/image${media.nextNumber()}.png"
                media.add(partName, PngWriter.encodeRgb(renderer.raster))
                shapes += PictureShape(nextId++, 0, 0, slideWidth, slideHeight, false, false, partName)
                pictures++
            }

            for (command in list.commands) {
                when (command) {
                    is DrawRasterImage -> {
                        if (options.fidelity) {
                            // Images are part of the background picture; only
                            // report the ones the rasterizer could not draw.
                            if (command.image.pixels == null) skippedImages += describeSkip(command.image)
                            continue
                        }
                        val part = media.partFor(command.image)
                        if (part == null) {
                            skippedImages += describeSkip(command.image)
                            continue
                        }
                        val placement = placementOf(emuMatrix * command.matrix)
                        if (placement == null) {
                            skippedImages += "rotated or skewed image"
                            continue
                        }
                        shapes += PictureShape(
                            nextId++, placement.x, placement.y, placement.width, placement.height,
                            placement.flipH, placement.flipV, part
                        )
                        pictures++
                        if (command.image.softMask != null && part.endsWith(".jpg")) {
                            warnings += "JPEG embedded without its soft mask (transparency lost)"
                        }
                    }
                    is DrawTextRun -> {
                        val run = command.run
                        if (run.renderMode == 7) continue // clip only, never painted
                        if (run.text.isBlank()) continue
                        val size = run.fontSize * (command.matrix * run.startMatrix).meanScale()
                        if (!size.isFinite() || size <= 0.0) {
                            warnings += "text run with non-positive size skipped"
                            continue
                        }
                        val emuBox = boundingBoxOf(run, emuMatrix)
                            ?: estimatedBoxOf(run, emuMatrix, size)?.also {
                                warnings +=
                                    "text width unavailable for '${run.text.take(20)}'; estimated from the font size"
                            }
                            ?: continue
                        val mapped = FontMapping.of(run.fontName)
                        if (!mapped.recognized) unknownFonts += (run.fontName ?: "(unnamed)")
                        val rotation = rotationOf(run, emuMatrix)
                        if (rotation != 0) rotatedText++
                        shapes += TextBoxShape(
                            id = nextId++,
                            xEmu = emuBox.x,
                            yEmu = emuBox.y,
                            wEmu = emuBox.width,
                            hEmu = emuBox.height,
                            text = run.text,
                            sizeHundredths = (size * 100.0).roundToInt().coerceIn(100, 40000),
                            bold = mapped.bold,
                            italic = mapped.italic,
                            rgb = 0x000000,
                            family = mapped.family,
                            // In fidelity mode the words already sit in the
                            // background where possible; the boxes stay
                            // selectable and invisible (mode 3 is always
                            // invisible in the source).
                            visible = !options.fidelity && run.renderMode != 3,
                            rotation = rotation
                        )
                        textBoxes++
                    }
                    else -> {}
                }
            }

            val (shadings, patterns) = scanShadings(page)
            reportPages += PageLosses(
                pageNumber = index + 1,
                widthPt = box.width,
                heightPt = box.height,
                textBoxes = textBoxes,
                pictures = pictures,
                imagesSkipped = skippedImages,
                vectorFills = vectorFills,
                vectorStrokes = vectorStrokes,
                vectorsRasterized = options.fidelity,
                shadings = shadings,
                patterns = patterns,
                unknownFonts = unknownFonts.toList(),
                rotatedText = rotatedText,
                warnings = warnings
            )
            slides += Slide(shapes)
        }

        val notes = ArrayList<String>()
        if (reportPages.sumOf { it.textBoxes } > 0) {
            notes += "text colour is not exposed by the engine's text model; every text box uses black"
            notes += "bold/italic are inferred from the font name and may be wrong"
            notes += "text box vertical placement and metrics are approximate"
        }
        if (limit < pages.size) {
            notes += "only the first $limit of ${pages.size} pages were converted (--max-pages)"
        }

        val deck = PptxDeck(
            slideWidthEmu = slideWidth,
            slideHeightEmu = slideHeight,
            slides = slides,
            media = media.parts,
            title = options.title ?: sourceName
        )
        val report = ConversionReport(
            sourceName = sourceName,
            mode = if (options.fidelity) "fidelity (page render + selectable text)" else "vector (editable shapes)",
            pages = reportPages,
            notes = notes
        )
        return PdfToPptxResult(PptxWriter.write(deck), report)
    }

    // ------------------------------------------------------------- helpers

    private class Placement(
        val x: Long, val y: Long, val width: Long, val height: Long,
        val flipH: Boolean, val flipV: Boolean
    )

    /**
     * Image unit square → slide rectangle. The square is read exactly as the
     * display list defines it (§8.9.5.2): sample column 0 sits at unit x=0 and
     * the first sample row at unit y=0, which is the rule the renderers apply
     * (`drawBitmap(bitmap, null, UNIT_RECT)`), so a picture is flipped only
     * when the matrix itself flipped the square. Returns null when the
     * placement is rotated or skewed, which DrawingML cannot express without a
     * transform we do not compute.
     */
    private fun placementOf(matrix: PdfMatrix): Placement? {
        val p00 = matrix.apply(0.0, 0.0)
        val p10 = matrix.apply(1.0, 0.0)
        val p01 = matrix.apply(0.0, 1.0)
        val p11 = matrix.apply(1.0, 1.0)
        val width = hypot(p10[0] - p00[0], p10[1] - p00[1])
        val height = hypot(p01[0] - p00[0], p01[1] - p00[1])
        if (width < 1e-6 || height < 1e-6) return null
        if (abs(p10[1] - p00[1]) > 0.001 * width || abs(p01[0] - p00[0]) > 0.001 * height) return null
        val xs = doubleArrayOf(p00[0], p10[0], p01[0], p11[0])
        val ys = doubleArrayOf(p00[1], p10[1], p01[1], p11[1])
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        return Placement(
            x = minX.roundToLong(),
            y = minY.roundToLong(),
            width = (maxX - minX).roundToLong(),
            height = (maxY - minY).roundToLong(),
            flipH = p00[0] > p10[0],
            flipV = p00[1] > p01[1]
        )
    }

    private class EmuBox(val x: Long, val y: Long, val width: Long, val height: Long)

    /** Axis-aligned slide-space bounds of a run's page-space bbox. */
    private fun boundingBoxOf(run: PdfTextRun, matrix: PdfMatrix): EmuBox? {
        val corners = doubleArrayOf(
            matrix.apply(run.x0, run.y0)[0], matrix.apply(run.x0, run.y0)[1],
            matrix.apply(run.x1, run.y0)[0], matrix.apply(run.x1, run.y0)[1],
            matrix.apply(run.x1, run.y1)[0], matrix.apply(run.x1, run.y1)[1],
            matrix.apply(run.x0, run.y1)[0], matrix.apply(run.x0, run.y1)[1]
        )
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (i in 0 until 4) {
            val x = corners[i * 2]
            val y = corners[i * 2 + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val w = (maxX - minX).roundToLong()
        val h = (maxY - minY).roundToLong()
        if (w <= 0 || h <= 0) return null
        return EmuBox(minX.roundToLong(), minY.roundToLong(), w, h)
    }

    /**
     * Fallback box for a run whose engine bbox is empty because the font has
     * no /Widths (standard-14 fonts without metrics, §9.6.2.2): anchor at the
     * baseline origin and estimate the width from the font size.
     */
    private fun estimatedBoxOf(run: PdfTextRun, matrix: PdfMatrix, sizePoints: Double): EmuBox? {
        val origin = matrix.apply(run.originX, run.originY)
        val widthEmu = Emu.ofPoints(sizePoints * 0.5 * maxOf(1, run.text.length))
        val heightEmu = Emu.ofPoints(sizePoints)
        if (widthEmu <= 0 || heightEmu <= 0) return null
        return EmuBox(
            origin[0].roundToLong(),
            (origin[1] - heightEmu).roundToLong(),
            widthEmu,
            heightEmu
        )
    }

    /** DrawingML rotation for a run's baseline (60000ths of a degree, clockwise). */    private fun rotationOf(run: PdfTextRun, matrix: PdfMatrix): Int {
        val origin = matrix.apply(run.originX, run.originY)
        val end = matrix.apply(run.endX, run.endY)
        val dx = end[0] - origin[0]
        val dy = end[1] - origin[1]
        if (hypot(dx, dy) < 1e-6) return 0
        val degrees = Math.toDegrees(atan2(dy, dx))
        if (abs(degrees) < 0.5) return 0
        return (degrees * 60000.0).roundToInt()
    }

    private fun describeSkip(image: RasterImage): String {
        val format = image.externalFormat ?: "unknown"
        return when {
            image.requiresExternalDecoder -> "$format image needs a platform decoder"
            image.externalData == null && image.pixels == null -> "image has no decodable data"
            else -> "$format image could not be embedded"
        }
    }

    /** Shading (`sh`) and pattern colours the content stream asks for. */
    private fun scanShadings(page: PdfPage): Pair<Int, Int> {
        val contents = page.contents().getOrNull() ?: return 0 to 0
        val parsed = parseContent(contents.bytes)
        var shadings = 0
        var patterns = 0
        for (command in parsed.commands) {
            when (command.operator) {
                "sh" -> shadings++
                "cs", "CS" -> if ((command.operands.getOrNull(0) as? PdfName)?.name == "Pattern") patterns++
            }
        }
        return shadings to patterns
    }

    /**
     * Package parts for decoded images, shared across pages. The same
     * [RasterImage] instance (a repeated form XObject, say) is written once.
     */
    private class MediaBag {
        val parts = LinkedHashMap<String, ByteArray>()
        private val byIdentity = HashMap<Int, String>()
        private var counter = 0

        fun nextNumber(): Int = ++counter

        fun add(partName: String, bytes: ByteArray) {
            parts[partName] = bytes
        }

        fun partFor(image: RasterImage): String? {
            val key = System.identityHashCode(image)
            byIdentity[key]?.let { return it }
            val pixels = image.pixels
            val jpeg = image.externalData
            val extension: String
            val bytes: ByteArray
            if (image.externalFormat == "DCT" && jpeg != null && !image.stencil) {
                extension = "jpg"
                bytes = jpeg
            } else if (pixels != null && pixels.size >= image.width * image.height * 4) {
                extension = "png"
                bytes = PngWriter.encodeRgba(image.width, image.height, rgbaWithMask(image, pixels))
            } else {
                return null
            }
            val name = "ppt/media/image${++counter}.$extension"
            parts[name] = bytes
            byIdentity[key] = name
            return name
        }

        private fun rgbaWithMask(image: RasterImage, pixels: ByteArray): ByteArray {
            val mask = image.softMask ?: return pixels
            val maskPixels = mask.pixels ?: return pixels
            val mw = mask.width
            val mh = mask.height
            if (mw <= 0 || mh <= 0 || maskPixels.size < mw * mh * 4) return pixels
            val out = pixels.copyOf()
            for (y in 0 until image.height) {
                val my = (y.toDouble() / image.height * mh).toInt().coerceIn(0, mh - 1)
                for (x in 0 until image.width) {
                    val mx = (x.toDouble() / image.width * mw).toInt().coerceIn(0, mw - 1)
                    val mo = (my * mw + mx) * 4
                    val value = if (mask.stencil) maskPixels[mo + 3].toInt() and 0xFF
                    else maskPixels[mo].toInt() and 0xFF
                    val o = (y * image.width + x) * 4
                    out[o + 3] = ((out[o + 3].toInt() and 0xFF) * value / 255).toByte()
                }
            }
            return out
        }
    }
}
