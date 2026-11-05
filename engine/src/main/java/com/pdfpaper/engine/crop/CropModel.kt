package com.pdfpaper.engine.crop

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.mark.PageSelection

/**
 * Margin presets for the crop screens, in points (§14.11.2 works in user
 * space; 1 pt = 1/72 in, so 1 cm = 28.346… pt).
 */
data class MarginPreset(
    val id: String,
    val label: String,
    /** Margins for left, bottom, right and top, in points. */
    val margins: DoubleArray
) {
    override fun equals(other: Any?): Boolean = other is MarginPreset && id == other.id
    override fun hashCode(): Int = id.hashCode()

    companion object {
        /** The presets the app and the CLI offer, in display order. */
        val PRESETS = listOf(
            MarginPreset("none", "No margins", doubleArrayOf(0.0, 0.0, 0.0, 0.0)),
            MarginPreset("quarter-inch", "1/4 in", doubleArrayOf(18.0, 18.0, 18.0, 18.0)),
            MarginPreset("half-inch", "1/2 in", doubleArrayOf(36.0, 36.0, 36.0, 36.0)),
            MarginPreset("one-inch", "1 in", doubleArrayOf(72.0, 72.0, 72.0, 72.0)),
            MarginPreset("one-cm", "1 cm", doubleArrayOf(28.3465, 28.3465, 28.3465, 28.3465)),
            MarginPreset("one-point-five-cm", "1.5 cm", doubleArrayOf(42.5197, 42.5197, 42.5197, 42.5197)),
            MarginPreset("two-cm", "2 cm", doubleArrayOf(56.693, 56.693, 56.693, 56.693))
        )

        /** Preset by id; null for an unknown id. */
        fun fromId(id: String): MarginPreset? = PRESETS.firstOrNull { it.id == id }
    }
}

/**
 * How the new CropBox is chosen (§14.11.2).
 *
 * Everything stays in unrotated user space (y up): margins are trimmed inward
 * from each page's own current CropBox (the MediaBox on a page that was never
 * cropped), exactly like Acrobat's crop dialog, and the MediaBox itself is
 * never touched - a normal crop is non-destructive.
 */
sealed interface CropSpec {

    /**
     * The same margins (left, bottom, right, top in points) are trimmed from
     * every selected page's current CropBox.
     */
    data class Margins(val left: Double, val bottom: Double, val right: Double, val top: Double) : CropSpec {
        val four: DoubleArray get() = doubleArrayOf(left, bottom, right, top)
    }

    /**
     * One absolute CropBox per selected page in user space (the draggable
     * handles of the crop screen; page index → box).
     */
    data class PerPage(val boxes: Map<Int, PdfRect>) : CropSpec
}

/** One crop request: [spec] applied to the [pages] chosen here. */
class CropRequest(
    val spec: CropSpec,
    val pages: PageSelection = PageSelection.All
)

/** The result of an auto-crop run ([PdfCrop.auto]). */
class AutoCropResult(
    /**
     * Proposed CropBoxes by page index, in user space. A page with no content
     * found is absent from this map (see [emptyPages]); the caller is free to
     * accept the proposal as-is or hand-edit it (e.g. feed it through
     * [pdfpaper.engine.mark.PageSelection.Custom].
     */
    val boxes: Map<Int, PdfRect>,
    /**
     * The size all proposed boxes were grown to when the caller asked for
     * `sameSize` (in points); null when it was not requested.
     */
    val uniformSize: CropSize?,
    /** Pages that are blank at the detection tolerance and got no proposal. */
    val emptyPages: List<Int>,
    /**
     * Pages whose MediaBox is too small for the uniform size, so their box
     * could not reach it. Only filled when [uniformSize] is set.
     */
    val unevenPages: List<Int>,
    /** Notes for the caller (one per blank or uneven page). */
    val warnings: List<String>
)

/** A width/height pair in points; crop boxes of this size look "the same size". */
data class CropSize(val width: Double, val height: Double)

/**
 * The outcome of [PdfCrop.apply]: the new file, the boxes written (page index
 * → CropBox), and what the run did.
 *
 * [unredacted] is only filled when the caller asked to remove hidden content
 * and the T28 rewriter could not remove something safely (for example a JPEG
 * without a decoder). In that case [hiddenContentRemoved] is false: the file is
 * still cropped, but the content outside the box may still be present.
 */
class CropResult(
    val bytes: ByteArray,
    val cropBoxes: Map<Int, PdfRect>,
    val hiddenContentRemoved: Boolean = false,
    val warnings: List<String> = emptyList(),
    val unredacted: List<String> = emptyList()
) {
    val changedPages: Int get() = cropBoxes.size
    val complete: Boolean get() = unredacted.isEmpty()
}
