package com.pdfpaper.convert.pptx

import kotlin.math.roundToLong

/**
 * English Metric Units, the DrawingML coordinate unit: 914400 EMU per inch,
 * 12700 per point (§20.1.10.2 `ST_Coordinate`).
 */
const val EMU_PER_POINT: Long = 12700L
const val EMU_PER_INCH: Long = 914400L

object Emu {
    fun ofPoints(points: Double): Long = (points * EMU_PER_POINT).roundToLong()
    fun ofInches(inches: Double): Long = (inches * EMU_PER_INCH).roundToLong()
}

/** One drawable on a slide. Ids must be unique and ≥ 2 within a slide. */
sealed class SlideShape(val id: Int)

/**
 * A text box (`p:sp`). [sizeHundredths] is DrawingML `sz` (hundredths of a
 * point); [visible] false emits an unfilled run so the text stays selectable
 * but does not paint — the "invisible text over a picture" of fidelity mode.
 */
class TextBoxShape(
    id: Int,
    val xEmu: Long,
    val yEmu: Long,
    val wEmu: Long,
    val hEmu: Long,
    val text: String,
    val sizeHundredths: Int,
    val bold: Boolean,
    val italic: Boolean,
    /** 0xRRGGBB. */
    val rgb: Int,
    val family: String,
    val visible: Boolean = true,
    /** Rotation in 60000ths of a degree, clockwise; 0 for horizontal text. */
    val rotation: Int = 0
) : SlideShape(id)

/** A picture (`p:pic`) referencing [partName] (e.g. `ppt/media/image1.png`). */
class PictureShape(
    id: Int,
    val xEmu: Long,
    val yEmu: Long,
    val wEmu: Long,
    val hEmu: Long,
    val flipH: Boolean,
    val flipV: Boolean,
    val partName: String
) : SlideShape(id)

class Slide(val shapes: List<SlideShape>)

/**
 * A complete deck ready to be written. [media] maps package part names to
 * bytes and is written verbatim; slide relationships are derived from the
 * picture shapes that reference it.
 */
class PptxDeck(
    val slideWidthEmu: Long,
    val slideHeightEmu: Long,
    val slides: List<Slide>,
    val media: Map<String, ByteArray>,
    val title: String = "Converted by PDFPaper"
)
