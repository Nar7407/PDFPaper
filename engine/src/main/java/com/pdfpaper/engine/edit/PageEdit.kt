package com.pdfpaper.engine.edit

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.model.PdfObject

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
 * Replaces the text of run [runId] (task T16). When the run's font can encode
 * every character the original operators are rewritten in place (font, position
 * and size kept); otherwise the run is removed and [text] is drawn with the
 * bundled fallback font. The chosen strategy is reported by [PdfEditOps].
 */
class ReplaceTextEdit(
    override val pageIndex: Int,
    val runId: Int,
    val text: String
) : PageEdit

/** Removes run [runId] from the page (task T16). */
class DeleteTextEdit(
    override val pageIndex: Int,
    val runId: Int
) : PageEdit

/** Shifts run [runId] by ([dx], [dy]) page user units, keeping its text (task T16). */
class MoveTextEdit(
    override val pageIndex: Int,
    val runId: Int,
    val dx: Double,
    val dy: Double
) : PageEdit

/**
 * One word of an OCR text layer (task T25), drawn invisibly with render mode 3
 * (§9.3.6). [matrix] maps text space to page user space, [size] is the word's
 * height in text-space units, and [horizontalScale] is the Tz percentage that
 * makes the run span the recognized word box exactly.
 */
class AddOcrTextEdit(
    override val pageIndex: Int,
    val text: String,
    val matrix: PdfMatrix,
    val size: Double,
    val horizontalScale: Double
) : PageEdit

/**
 * Paints an existing form XObject into page content (T26 flattening). [form]
 * may be a source [com.pdfpaper.engine.model.PdfRef] or a direct stream; it is
 * copied into the output page's resources. [bbox] is the form's own /BBox and
 * is scaled into the target rectangle ([x], [y], [width], [height]).
 *
 * This does not remove the source annotation; callers that flatten an
 * annotation pass its object number to the writer's dropped-annotation set.
 */
class PlaceFormEdit(
    override val pageIndex: Int,
    val form: PdfObject,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val bbox: PdfRect = PdfRect(0.0, 0.0, width, height)
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
