package com.pdfpaper.app.edit

import android.content.Context
import android.content.pm.ApplicationInfo

/** The kind of object the viewer's edit layer can place on a page. */
enum class EditableObjectType { TEXT, IMAGE }

/** Fonts offered by the add/edit text UI. All are platform fonts. */
enum class EditFontFamily(val label: String) {
    SANS("Sans serif"),
    SERIF("Serif"),
    MONO("Monospace")
}

/** Text styling exposed by the UI and stored with an editable object. */
data class EditableStyle(
    val fontFamily: EditFontFamily = EditFontFamily.SANS,
    val fontSize: Float = 18f,
    /** ARGB colour, e.g. 0xFF111111 for the default ink. */
    val color: Long = 0xFF111111
)

/**
 * A rectangle in page fractions (0..1, origin top-left). Fractions keep the
 * overlay independent of render resolution and zoom.
 */
data class EditableBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    fun movedBy(dx: Float, dy: Float): EditableBounds {
        val w = width
        val h = height
        val newLeft = (left + dx).coerceIn(0f, (1f - w).coerceAtLeast(0f))
        val newTop = (top + dy).coerceIn(0f, (1f - h).coerceAtLeast(0f))
        return EditableBounds(newLeft, newTop, newLeft + w, newTop + h)
    }

    fun resized(handle: EditableHandle, dx: Float, dy: Float, minSize: Float = 0.04f): EditableBounds {
        var l = left
        var t = top
        var r = right
        var b = bottom
        when (handle) {
            EditableHandle.TOP_LEFT -> { l += dx; t += dy }
            EditableHandle.TOP_RIGHT -> { r += dx; t += dy }
            EditableHandle.BOTTOM_LEFT -> { l += dx; b += dy }
            EditableHandle.BOTTOM_RIGHT -> { r += dx; b += dy }
            EditableHandle.NONE -> return this
        }
        l = l.coerceIn(0f, 1f - minSize)
        t = t.coerceIn(0f, 1f - minSize)
        r = r.coerceIn(l + minSize, 1f)
        b = b.coerceIn(t + minSize, 1f)
        return EditableBounds(l, t, r, b)
    }

    companion object {
        /** A sensible text box for a tap at ([x], [y]). */
        fun around(x: Float, y: Float, width: Float = 0.44f, height: Float = 0.09f): EditableBounds {
            val left = (x - width / 2f).coerceIn(0f, (1f - width).coerceAtLeast(0f))
            val top = (y - height / 2f).coerceIn(0f, (1f - height).coerceAtLeast(0f))
            return EditableBounds(left, top, left + width, top + height)
        }
    }
}

/** Corner grips drawn around the selected object. */
enum class EditableHandle { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * One editable item on a page. The viewer owns selection and gestures; this
 * data class is the contract the engine layer exposes to the UI.
 */
data class EditableObject(
    val id: String,
    val type: EditableObjectType,
    val pageIndex: Int,
    val bounds: EditableBounds,
    /** Text for TEXT objects, or a content URI string for IMAGE objects. */
    val content: String,
    val style: EditableStyle = EditableStyle()
)

/** What the active engine can actually do. */
data class EditableCapabilities(
    val addText: Boolean = false,
    val editText: Boolean = false,
    val addImage: Boolean = false,
    val editImage: Boolean = false,
    val move: Boolean = false,
    val resize: Boolean = false,
    val undoRedo: Boolean = false,
    val save: Boolean = false
) {
    val any: Boolean
        get() = addText || editText || addImage || editImage || move || resize || undoRedo || save
}

/**
 * App-side editing contract. It deliberately lives outside :engine so the UI
 * can be built and demonstrated before the PDF engine gains content editing.
 * A future engine adapter can implement the same interface and replace
 * [FakePdfEngine] without changing the viewer.
 */
interface PdfEngine {
    val name: String
    val isFake: Boolean
    val capabilities: EditableCapabilities

    fun allObjects(): List<EditableObject>
    fun addText(pageIndex: Int, bounds: EditableBounds, content: String, style: EditableStyle): EditableObject?
    fun addImage(pageIndex: Int, bounds: EditableBounds, contentUri: String, style: EditableStyle): EditableObject?
    fun updateText(id: String, content: String, style: EditableStyle): EditableObject?
    fun updateImage(id: String, contentUri: String): EditableObject?
    fun updateBounds(id: String, bounds: EditableBounds): EditableObject?
    fun delete(id: String): Boolean
    fun undo(): Boolean
    fun redo(): Boolean
    val canUndo: Boolean
    val canRedo: Boolean
    fun clear()
    /** Returns true when the engine accepted the save request. */
    fun save(): Boolean
}

/**
 * In-memory demo engine used in debug builds. It does not write bytes to the
 * PDF; the debug menu and the edit toolbar label it clearly so a demo can
 * never be mistaken for a real content edit.
 */
class FakePdfEngine : PdfEngine {
    override val name: String = "FakeEngine (demo)"
    override val isFake: Boolean = true
    override val capabilities: EditableCapabilities = EditableCapabilities(
        addText = true,
        editText = true,
        addImage = true,
        editImage = true,
        move = true,
        resize = true,
        undoRedo = true,
        save = true
    )

    private var objects: List<EditableObject> = emptyList()
    private val undoStack = ArrayDeque<List<EditableObject>>()
    private val redoStack = ArrayDeque<List<EditableObject>>()
    private var nextId = 1

    override fun allObjects(): List<EditableObject> = objects

    override fun addText(
        pageIndex: Int,
        bounds: EditableBounds,
        content: String,
        style: EditableStyle
    ): EditableObject? {
        if (content.isBlank()) return null
        return mutate {
            EditableObject(
                id = "text-${nextId++}",
                type = EditableObjectType.TEXT,
                pageIndex = pageIndex,
                bounds = bounds,
                content = content.trim(),
                style = style
            )
        }
    }

    override fun addImage(
        pageIndex: Int,
        bounds: EditableBounds,
        contentUri: String,
        style: EditableStyle
    ): EditableObject? {
        if (contentUri.isBlank()) return null
        return mutate {
            EditableObject(
                id = "image-${nextId++}",
                type = EditableObjectType.IMAGE,
                pageIndex = pageIndex,
                bounds = bounds,
                content = contentUri,
                style = style
            )
        }
    }

    override fun updateText(id: String, content: String, style: EditableStyle): EditableObject? {
        if (content.isBlank()) return null
        return update(id) { it.copy(content = content.trim(), style = style) }
    }

    override fun updateImage(id: String, contentUri: String): EditableObject? {
        if (contentUri.isBlank()) return null
        return update(id) { it.copy(content = contentUri) }
    }

    override fun updateBounds(id: String, bounds: EditableBounds): EditableObject? =
        update(id) { it.copy(bounds = bounds) }

    override fun delete(id: String): Boolean {
        if (objects.none { it.id == id }) return false
        pushUndo()
        objects = objects.filterNot { it.id == id }
        redoStack.clear()
        return true
    }

    override fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.addLast(objects)
        objects = undoStack.removeLast()
        return true
    }

    override fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.addLast(objects)
        objects = redoStack.removeLast()
        return true
    }

    override val canUndo: Boolean get() = undoStack.isNotEmpty()
    override val canRedo: Boolean get() = redoStack.isNotEmpty()

    override fun clear() {
        objects = emptyList()
        undoStack.clear()
        redoStack.clear()
    }

    override fun save(): Boolean = true

    private fun update(id: String, transform: (EditableObject) -> EditableObject): EditableObject? {
        val current = objects.firstOrNull { it.id == id } ?: return null
        pushUndo()
        val updated = transform(current)
        objects = objects.map { if (it.id == id) updated else it }
        redoStack.clear()
        return updated
    }

    private fun mutate(create: () -> EditableObject): EditableObject {
        pushUndo()
        val created = create()
        objects = objects + created
        redoStack.clear()
        return created
    }

    private fun pushUndo() {
        undoStack.addLast(objects)
    }
}

/** Release fallback: the real engine has no content-editing API yet. */
class UnsupportedPdfEngine : PdfEngine {
    override val name: String = "PDF engine (coming soon)"
    override val isFake: Boolean = false
    override val capabilities: EditableCapabilities = EditableCapabilities()
    override fun allObjects(): List<EditableObject> = emptyList()
    override fun addText(
        pageIndex: Int,
        bounds: EditableBounds,
        content: String,
        style: EditableStyle
    ): EditableObject? = null
    override fun addImage(
        pageIndex: Int,
        bounds: EditableBounds,
        contentUri: String,
        style: EditableStyle
    ): EditableObject? = null
    override fun updateText(id: String, content: String, style: EditableStyle): EditableObject? = null
    override fun updateImage(id: String, contentUri: String): EditableObject? = null
    override fun updateBounds(id: String, bounds: EditableBounds): EditableObject? = null
    override fun delete(id: String): Boolean = false
    override fun undo(): Boolean = false
    override fun redo(): Boolean = false
    override val canUndo: Boolean get() = false
    override val canRedo: Boolean get() = false
    override fun clear() = Unit
    override fun save(): Boolean = false
}

/** Chooses the demo engine in debuggable builds and the honest fallback otherwise. */
object EditingRuntime {
    const val DEMO_NOTICE =
        "FakeEngine is active. Text and image edits are simulated in memory and " +
            "are not written to the PDF."

    fun isDemo(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun create(context: Context): PdfEngine =
        if (isDemo(context)) FakePdfEngine() else UnsupportedPdfEngine()
}
