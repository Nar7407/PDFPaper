package com.pdfpaper.engine.edit

import com.pdfpaper.engine.graphics.PdfColor

/**
 * One editing operation queued for a page (task T17). `pageIndex` is a
 * zero-based index into the source document's pages, matching
 * [com.pdfpaper.engine.page.pages].
 */
sealed interface PageEdit {
    val pageIndex: Int
}

/** Text drawn at baseline ([x], [y]) in page user space (§9.4.4). */
class AddTextEdit(
    override val pageIndex: Int,
    val text: String,
    val x: Double,
    val y: Double,
    val font: EditFont = EditFont.Standard(StandardFont.HELVETICA),
    val size: Double = 12.0,
    val color: PdfColor = PdfColor.BLACK
) : PageEdit

/** An image placed with its lower-left corner at ([x], [y]) and size [width]×[height]. */
class AddImageEdit(
    override val pageIndex: Int,
    val image: ImageData,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
) : PageEdit

/** Shifts the placed image [imageId] by ([dx], [dy]) in page space. */
class MoveImageEdit(
    override val pageIndex: Int,
    val imageId: String,
    val dx: Double,
    val dy: Double
) : PageEdit

/** Retargets the placed image [imageId] to the rectangle ([x], [y])–([x]+[width], [y]+[height]). */
class ResizeImageEdit(
    override val pageIndex: Int,
    val imageId: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
) : PageEdit

/** Swaps the pixel data behind [imageId], keeping its placement. */
class ReplaceImageEdit(
    override val pageIndex: Int,
    val imageId: String,
    val image: ImageData
) : PageEdit

/** Removes the placed image [imageId] without disturbing the rest of the page. */
class DeleteImageEdit(
    override val pageIndex: Int,
    val imageId: String
) : PageEdit

/**
 * Ordered, undoable list of pending [PageEdit]s (task T17). [undo] moves the
 * newest edit onto the redo stack; [redo] puts it back. Adding a new edit
 * clears the redo stack, matching editor convention.
 */
class EditList {

    private val done = ArrayList<PageEdit>()
    private val redoable = ArrayList<PageEdit>()

    val size: Int get() = done.size
    val canUndo: Boolean get() = done.isNotEmpty()
    val canRedo: Boolean get() = redoable.isNotEmpty()

    fun add(edit: PageEdit) {
        done += edit
        redoable.clear()
    }

    fun undo(): PageEdit? {
        val edit = done.removeLastOrNull() ?: return null
        redoable += edit
        return edit
    }

    fun redo(): PageEdit? {
        val edit = redoable.removeLastOrNull() ?: return null
        done += edit
        return edit
    }

    /** The edits that will be written, oldest first. */
    fun edits(): List<PageEdit> = done.toList()

    fun clear() {
        done.clear()
        redoable.clear()
    }
}
