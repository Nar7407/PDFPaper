package com.pdfpaper.engine.edit

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal

/** One placed image found in a page content stream. */
class ImagePlacement(
    /** Index of the `Do` command in the scanned list. */
    val index: Int,
    /** Stable id: the XObject name, suffixed `#k` when the same image is drawn more than once. */
    val id: String,
    /** XObject resource name (`/Im0` → "Im0"). */
    val name: String,
    /** CTM in effect when the image was drawn; maps the image's unit square to page space. */
    val ctm: PdfMatrix
) {
    /** Placement rectangle in page user space (§8.9.5.2). */
    val bounds: PdfRect = ImagePlacements.boundsOf(ctm)
}

/**
 * Walks content commands tracking the graphics state enough to locate image
 * `Do` operators and their placement matrix (§8.9.5.2, §8.4.4). `q`/`Q` nesting
 * is honoured; malformed/unbalanced input degrades gracefully.
 */
internal object ImagePlacements {

    fun scan(commands: List<ContentCommand>, isImage: (String) -> Boolean): List<ImagePlacement> {
        val counts = HashMap<String, Int>()
        for (command in commands) {
            if (command.operator != "Do") continue
            val name = nameOf(command.operands) ?: continue
            if (isImage(name)) counts[name] = (counts[name] ?: 0) + 1
        }

        val seen = HashMap<String, Int>()
        val placements = ArrayList<ImagePlacement>()
        var ctm = PdfMatrix.IDENTITY
        val stack = ArrayDeque<PdfMatrix>()
        for ((index, command) in commands.withIndex()) {
            when (command.operator) {
                "q" -> stack.addLast(ctm)
                "Q" -> ctm = stack.removeLastOrNull() ?: ctm
                "cm" -> matrixOf(command.operands)?.let { ctm = ctm * it }
                "Do" -> {
                    val name = nameOf(command.operands) ?: continue
                    if (!isImage(name)) continue
                    val occurrence = seen.getOrDefault(name, 0)
                    seen[name] = occurrence + 1
                    val id = if (counts[name] == 1) name else "$name#$occurrence"
                    placements += ImagePlacement(index, id, name, ctm)
                }
            }
        }
        return placements
    }

    fun nameOf(operands: List<PdfObject>): String? = (operands.firstOrNull() as? PdfName)?.name

    fun matrixOf(operands: List<PdfObject>): PdfMatrix? {
        if (operands.size < 6) return null
        val values = DoubleArray(6)
        for (i in 0 until 6) values[i] = number(operands[i]) ?: return null
        return PdfMatrix(values[0], values[1], values[2], values[3], values[4], values[5])
    }

    /** Axis-aligned rect of the unit square under [ctm] (the image placement). */
    fun boundsOf(ctm: PdfMatrix): PdfRect {
        val corners = arrayOf(ctm.apply(0.0, 0.0), ctm.apply(1.0, 0.0), ctm.apply(1.0, 1.0), ctm.apply(0.0, 1.0))
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (corner in corners) {
            minX = minOf(minX, corner[0])
            minY = minOf(minY, corner[1])
            maxX = maxOf(maxX, corner[0])
            maxY = maxOf(maxY, corner[1])
        }
        return PdfRect(minX, minY, maxX, maxY)
    }

    /** Six [PdfReal] operands for a `cm` operator. */
    fun matrixOperands(matrix: PdfMatrix): List<PdfObject> = listOf(
        PdfReal(matrix.a), PdfReal(matrix.b), PdfReal(matrix.c),
        PdfReal(matrix.d), PdfReal(matrix.e), PdfReal(matrix.f)
    )

    private fun number(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }
}

/**
 * A mutable view of one page's content commands used to apply image edits.
 * Move/resize bracket the image with an isolated `q`/`cm`/`Q` so only that
 * occurrence changes; delete turns the `Do` into the no-op `n`.
 */
internal class ContentModel(
    commands: List<ContentCommand>,
    private val isImage: (String) -> Boolean
) {
    val commands: MutableList<ContentCommand> = ArrayList(commands)

    fun placements(): List<ImagePlacement> = ImagePlacements.scan(commands, isImage)

    fun move(imageId: String, dx: Double, dy: Double): Boolean {
        val placement = placements().firstOrNull { it.id == imageId } ?: return false
        val target = PdfMatrix.translation(dx, dy) * placement.ctm
        return transform(placement, target)
    }

    fun resize(imageId: String, x: Double, y: Double, width: Double, height: Double): Boolean {
        val placement = placements().firstOrNull { it.id == imageId } ?: return false
        val target = PdfMatrix.translation(x, y) * PdfMatrix.scaling(width, height)
        return transform(placement, target)
    }

    fun delete(imageId: String): Boolean {
        val placement = placements().firstOrNull { it.id == imageId } ?: return false
        commands[placement.index] = ContentCommand(emptyList(), "n")
        return true
    }

    fun setImageName(imageId: String, newName: String): Boolean {
        val placement = placements().firstOrNull { it.id == imageId } ?: return false
        commands[placement.index] = ContentCommand(listOf(PdfName(newName)), "Do")
        return true
    }

    private fun transform(placement: ImagePlacement, target: PdfMatrix): Boolean {
        val inverse = placement.ctm.inverse() ?: return false
        // The compensating matrix composes to `target` when applied before the
        // image (state.ctm * T). Bracketed by q/Q so later draws are unaffected.
        val compensating = inverse * target
        val index = placement.index
        commands.add(index, ContentCommand(ImagePlacements.matrixOperands(compensating), "cm"))
        commands.add(index, ContentCommand(emptyList(), "q"))
        commands.add(index + 3, ContentCommand(emptyList(), "Q"))
        return true
    }
}
