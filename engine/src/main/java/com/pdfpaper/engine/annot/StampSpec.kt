package com.pdfpaper.engine.annot

import com.pdfpaper.engine.edit.ImageData
import com.pdfpaper.engine.stamp.StampDesign

/**
 * A /Stamp (§12.5.6.7) placed on a page: [design] painted as the annotation's
 * own appearance stream (§12.5.5).
 *
 * [x] and [y] are the bottom-left corner of the placed box in PDF user space
 * (points from the page's lower-left corner). [size] is the width of the
 * *unrotated* stamp in points — the height follows the design's aspect ratio.
 * [rotation] turns the stamp counter-clockwise in whole quarter turns; a
 * quarter turn exchanges the box's width and height. [opacity] is 0..1.
 *
 * A dynamic design gets its "By <author> at <date>" line from [author] and
 * [timestamp] (null means the moment of placement); [image] is required when
 * the design paints a picture (custom image stamps) and is embedded in the
 * appearance.
 */
class StampSpec(
    override val pageIndex: Int,
    val design: StampDesign,
    val x: Double,
    val y: Double,
    val size: Double,
    val rotation: Int = 0,
    val opacity: Double = 1.0,
    val author: String? = null,
    val timestamp: Long? = null,
    val image: ImageData? = null
) : AnnotationSpec
