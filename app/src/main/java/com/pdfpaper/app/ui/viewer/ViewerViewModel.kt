package com.pdfpaper.app.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.edit.EditableBounds
import com.pdfpaper.app.edit.EditableObject
import com.pdfpaper.app.edit.EditableStyle
import com.pdfpaper.app.edit.EditingRuntime
import com.pdfpaper.app.edit.PdfEngine
import com.pdfpaper.app.render.PageBitmapRenderer
import com.pdfpaper.engine.annot.AnnotationSpec
import com.pdfpaper.engine.annot.InkSpec
import com.pdfpaper.engine.annot.NoteSpec
import com.pdfpaper.engine.form.FieldKind
import com.pdfpaper.engine.form.PdfField
import com.pdfpaper.engine.form.formFields
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.security.PdfPasswordException
import com.pdfpaper.engine.text.PageLayout
import com.pdfpaper.engine.text.PageMatch
import com.pdfpaper.engine.text.TextRect
import com.pdfpaper.engine.text.layout
import com.pdfpaper.engine.util.LruCache
import com.pdfpaper.engine.write.PdfPageOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ViewerUiState(
    val isLoading: Boolean = true,
    val displayName: String? = null,
    val pageCount: Int = 0,
    val error: String? = null,
    /** True when the document is encrypted and awaits a password (T13). */
    val requiresPassword: Boolean = false,
    /** Set after a rejected password attempt. */
    val passwordError: String? = null
)

/** Text search state for the viewer (T11). */
data class ViewerSearchState(
    val query: String = "",
    val matches: List<PageMatch> = emptyList(),
    val activeIndex: Int = -1,
    val running: Boolean = false
) {
    val activeMatch: PageMatch? get() = matches.getOrNull(activeIndex)
}

/** One page's current text selection (T11). */
data class ViewerSelection(
    val pageIndex: Int,
    val start: Int,
    val end: Int,
    val rects: List<TextRect>,
    val text: String
)

/** Annotation tool selected in the viewer's edit mode (T12). */
enum class AnnotationTool { HIGHLIGHT, INK, NOTE }

/** Object-editing tool selected in the viewer's Edit toolbar. */
enum class ObjectEditTool { NONE, SELECT, TEXT, IMAGE }

/** Objects exposed by the app-side [PdfEngine] edit contract. */
data class ObjectEditState(
    val objects: List<EditableObject> = emptyList(),
    val selectedId: String? = null,
    val tool: ObjectEditTool = ObjectEditTool.NONE,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val fake: Boolean = false,
    val engineName: String = "",
    val saving: Boolean = false
) {
    val selected: EditableObject? get() = objects.firstOrNull { it.id == selectedId }
}

/** A freehand stroke still being drawn, in page user space. */
data class InkDraft(val pageIndex: Int, val points: List<Double>)

/** Output bytes waiting for the user to choose a destination (SAF CreateDocument). */
class ViewerExport(val bytes: ByteArray, val suggestedName: String)

/** Viewer edit mode: pending annotations, form fields and their new values (T12). */
data class ViewerEditState(
    val active: Boolean = false,
    val tool: AnnotationTool = AnnotationTool.HIGHLIGHT,
    val annotations: List<AnnotationSpec> = emptyList(),
    val inkDraft: InkDraft? = null,
    val fields: List<PdfField> = emptyList(),
    val fieldValues: Map<String, String> = emptyMap(),
    val loadingFields: Boolean = false,
    val saving: Boolean = false
) {
    val hasPendingChanges: Boolean get() = annotations.isNotEmpty() || fieldValues.isNotEmpty()
}

/** One cached page bitmap plus the render plan that produced it. */
private class CachedPage(val plan: PageRenderPlan, val bitmap: Bitmap)

/**
 * Viewer state for one document (T9). Rendering happens on a single-threaded
 * background dispatcher: one page is rasterised at a time, which bounds peak
 * memory and serialises access to the document's random-access source.
 * Bitmaps of off-screen pages stay in a byte-bounded LRU cache; pending
 * renders for pages that scrolled away are cancelled. Zoom is owned by the UI
 * (it changes the requested pixel width), so a zoom change simply re-requests
 * the visible pages.
 *
 * T11 adds case-insensitive search over the extracted text and word/reading
 * order selection. Text work shares the render dispatcher so the document is
 * only ever touched by one thread; layouts are cached and bounded like bitmaps.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // limitedParallelism
class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private val _pageBoxes = MutableStateFlow<List<PageBox>>(emptyList())
    val pageBoxes: StateFlow<List<PageBox>> = _pageBoxes.asStateFlow()

    /** Bitmaps currently on screen; off-screen ones live only in the cache. */
    private val _bitmaps = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    val bitmaps: StateFlow<Map<Int, Bitmap>> = _bitmaps.asStateFlow()

    /** Render plan per on-screen page: user space → bitmap pixels (T11 overlays). */
    private val _plans = MutableStateFlow<Map<Int, PageRenderPlan>>(emptyMap())
    val plans: StateFlow<Map<Int, PageRenderPlan>> = _plans.asStateFlow()

    private val _search = MutableStateFlow(ViewerSearchState())
    val search: StateFlow<ViewerSearchState> = _search.asStateFlow()

    private val _selection = MutableStateFlow<ViewerSelection?>(null)
    val selection: StateFlow<ViewerSelection?> = _selection.asStateFlow()

    /** Annotation/form edit mode (T12). */
    private val _edit = MutableStateFlow(ViewerEditState())
    val edit: StateFlow<ViewerEditState> = _edit.asStateFlow()

    /**
     * Object editing goes through the app-side [PdfEngine] contract. Debug
     * builds get the in-memory demonstration engine; release builds get an
     * honest "coming soon" implementation until :engine exposes content edits.
     */
    private val objectEngine: PdfEngine = EditingRuntime.create(getApplication())
    private val _objectEdit = MutableStateFlow(
        ObjectEditState(
            fake = objectEngine.isFake,
            engineName = objectEngine.name
        )
    )
    val objectEdit: StateFlow<ObjectEditState> = _objectEdit.asStateFlow()

    /** Bytes produced by [save], waiting for a SAF destination. */
    private val _export = MutableStateFlow<ViewerExport?>(null)
    val export: StateFlow<ViewerExport?> = _export.asStateFlow()

    /** Non-fatal problem worth showing (render failure, warning summary). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var document: PdfDocument? = null
    private var pages: List<PdfPage> = emptyList()
    private var fieldsLoaded = false

    /** Path of the document being viewed, kept so a password retry can reopen it. */
    private var currentPath: String = ""

    private val cache = LruCache<Int, CachedPage>(bitmapBudgetBytes()) { it.bitmap.allocationByteCount.toLong() }

    /** Per-page text layouts; small and bounded, re-extracted if evicted. */
    private val layouts = LruCache<Int, PageLayout>(64)

    /**
     * One dispatcher for all document access: rendering and text extraction
     * share the [PdfDocument], so they must never run at the same time.
     */
    private val documentDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val renderScope = CoroutineScope(SupervisorJob() + documentDispatcher)
    private val textScope = CoroutineScope(SupervisorJob() + documentDispatcher)
    private val jobs = HashMap<Int, Job>()
    private val requestedWidths = HashMap<Int, Int>()
    private var searchJob: Job? = null

    /** First word of the current selection, to extend from (T11). */
    private var selectionAnchor: Pair<Int, IntRange>? = null

    /** Embedded fonts are materialised here so android.graphics can load them. */
    private val fontDir: File by lazy { File(getApplication<Application>().cacheDir, "embedded-fonts") }

    fun load(path: String) {
        currentPath = path
        openWith("")
    }

    /** Retries opening the current document with [password] (T13). */
    fun unlock(password: String) = openWith(password)

    /** Gives up on the password prompt. */
    fun cancelUnlock() = _state.update {
        it.copy(requiresPassword = false, passwordError = null, error = "Password required")
    }

    private fun openWith(password: String) {
        val path = currentPath
        if (path.isBlank()) {
            _state.value = ViewerUiState(isLoading = false, error = "No file selected")
            return
        }
        clearTextState()
        _state.value = ViewerUiState(isLoading = true)
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    val doc = PdfDocument.open(FileSource(File(path)), password).getOrThrow()
                    doc to doc.pages()
                }
            }
            opened.fold(
                onSuccess = { (doc, list) ->
                    document = doc
                    pages = list
                    _pageBoxes.value = list.map { PageGeometry.boxOf(it) }
                    _state.value = ViewerUiState(
                        isLoading = false,
                        displayName = File(path).name,
                        pageCount = list.size
                    )
                },
                onFailure = { error ->
                    if (error is PdfPasswordException) {
                        _state.value = ViewerUiState(
                            isLoading = false,
                            displayName = File(path).name,
                            requiresPassword = true,
                            passwordError = if (password.isEmpty()) null else "Incorrect password"
                        )
                    } else {
                        _state.value = ViewerUiState(
                            isLoading = false,
                            displayName = File(path).name,
                            error = error.message ?: "Cannot open this document"
                        )
                    }
                }
            )
        }
    }

    /**
     * Called when a page becomes visible. Publishes a cached bitmap when it
     * already has the right resolution, otherwise schedules a render.
     */
    fun onPageVisible(index: Int, targetPixelWidth: Int) {
        if (index !in pages.indices) return
        val width = targetPixelWidth.coerceAtLeast(1)

        cache.get(index)?.let { cached ->
            if (resolutionMatches(cached.plan.pixelWidth, width)) {
                publish(index, cached.plan, cached.bitmap)
                return
            }
        }
        if (jobs[index]?.isActive == true && requestedWidths[index] == width) return

        jobs.remove(index)?.cancel()
        requestedWidths[index] = width
        val box = _pageBoxes.value.getOrNull(index) ?: return
        val scale = PageGeometry.scaleForPixelWidth(box, width).coerceIn(0.05, 12.0)
        jobs[index] = renderScope.launch {
            val rendered = try {
                PageBitmapRenderer.render(pages[index], scale, fontDir = fontDir)
            } catch (t: Throwable) {
                _message.value = "Page ${index + 1} could not be rendered: ${t.message}"
                null
            }
            if (rendered != null && isActive) {
                if (rendered.warnings.isNotEmpty()) {
                    _message.value = "Page ${index + 1}: ${rendered.warnings.first()}"
                }
                cache.put(index, CachedPage(rendered.plan, rendered.bitmap))
                publish(index, rendered.plan, rendered.bitmap)
            }
            jobs.remove(index)
            requestedWidths.remove(index)
        }
    }

    /** Called when a page scrolls out of view: drop the on-screen reference. */
    fun onPageHidden(index: Int) {
        jobs.remove(index)?.cancel()
        requestedWidths.remove(index)
        _bitmaps.update { it - index }
        _plans.update { it - index }
    }

    // ------------------------------------------------------------------ search

    /**
     * Searches the whole document for [query] (case-insensitive). A blank query
     * clears the results. Results for an older query are dropped when a newer
     * search finishes first.
     */
    fun setSearchQuery(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _search.value = ViewerSearchState(query = query)
            return
        }
        clearSelection()
        _search.value = ViewerSearchState(query = query, running = true)
        searchJob = textScope.launch {
            val found = ArrayList<PageMatch>()
            for (index in pages.indices) {
                if (!isActive || found.size >= MAX_MATCHES) break
                val layout = layoutFor(index) ?: continue
                for (match in layout.search(query, MAX_MATCHES - found.size)) {
                    found += PageMatch(index, match)
                }
            }
            if (!isActive) return@launch
            _search.update { current ->
                if (current.query != query) {
                    current
                } else {
                    current.copy(
                        matches = found,
                        activeIndex = if (found.isEmpty()) -1 else 0,
                        running = false
                    )
                }
            }
        }
    }

    fun nextMatch() = stepMatch(1)

    fun previousMatch() = stepMatch(-1)

    private fun stepMatch(delta: Int) {
        _search.update { current ->
            val count = current.matches.size
            if (count == 0) {
                current
            } else {
                val next = ((current.activeIndex + delta) % count + count) % count
                current.copy(activeIndex = next)
            }
        }
    }

    // --------------------------------------------------------------- selection

    /**
     * Selects the word nearest the page point given as a fraction of the drawn
     * page ([fractionX] across, [fractionY] down). With [extend] the selection
     * grows from the anchor word to this one, following reading order.
     */
    fun selectAt(pageIndex: Int, fractionX: Float, fractionY: Float, extend: Boolean) {
        val plan = _plans.value[pageIndex] ?: return
        val inverse = plan.matrix.inverse() ?: return
        val point = inverse.apply(
            fractionX.toDouble() * plan.pixelWidth,
            fractionY.toDouble() * plan.pixelHeight
        )
        textScope.launch {
            val layout = layoutFor(pageIndex) ?: return@launch
            val word = layout.wordAt(point[0], point[1])
            if (word.isEmpty()) return@launch
            val anchor = if (extend) {
                selectionAnchor?.takeIf { it.first == pageIndex }?.second ?: word
            } else {
                word
            }
            selectionAnchor = pageIndex to anchor
            val start = min(anchor.first, word.first)
            val end = max(anchor.last, word.last) + 1
            _selection.value = ViewerSelection(
                pageIndex, start, end, layout.rectsFor(start, end), layout.text.substring(start, end)
            )
        }
    }

    /** Selects every character on [pageIndex]. */
    fun selectAll(pageIndex: Int) {
        textScope.launch {
            val layout = layoutFor(pageIndex) ?: return@launch
            if (layout.text.isEmpty()) return@launch
            selectionAnchor = null
            _selection.value = ViewerSelection(
                pageIndex, 0, layout.text.length, layout.rectsFor(0, layout.text.length), layout.text
            )
        }
    }

    fun clearSelection() {
        selectionAnchor = null
        _selection.value = null
    }

    // -------------------------------------------------- annotations (T12)

    /** Turns edit mode on/off; entering it lazily loads the form fields. */
    fun setEditMode(active: Boolean) {
        _edit.update { it.copy(active = active, inkDraft = if (active) it.inkDraft else null) }
        _objectEdit.update {
            it.copy(
                tool = if (active) ObjectEditTool.SELECT else ObjectEditTool.NONE,
                selectedId = if (active) it.selectedId else null
            )
        }
        if (active) ensureFieldsLoaded()
    }

    /** Selects an annotation tool; this also leaves object-edit mode. */
    fun setTool(tool: AnnotationTool) {
        _edit.update { it.copy(tool = tool) }
        _objectEdit.update { it.copy(tool = ObjectEditTool.NONE) }
    }

    /** Chooses an object tool without changing the markup tool. */
    fun setObjectTool(tool: ObjectEditTool) {
        _objectEdit.update {
            it.copy(
                tool = tool,
                selectedId = if (tool == ObjectEditTool.TEXT || tool == ObjectEditTool.IMAGE) {
                    null
                } else {
                    it.selectedId
                }
            )
        }
    }

    /** Maps a tool id from All tools to the viewer's initial object mode. */
    fun startObjectEditing(toolId: String?) {
        startViewerTool(
            when (toolId) {
                "text" -> "text"
                "image" -> "image"
                "highlight" -> "highlight"
                "ink" -> "ink"
                "note" -> "note"
                else -> "select"
            }
        )
    }

    /**
     * Opens the viewer mode for a tool chosen from the tools menu: object tools
     * (add/edit text/image) switch the object tool, annotate tools switch the
     * markup tool. Either way the viewer enters edit mode.
     */
    fun startViewerTool(mode: String) {
        _edit.update { it.copy(active = true) }
        when (mode) {
            "highlight" -> {
                _edit.update { it.copy(tool = AnnotationTool.HIGHLIGHT) }
                _objectEdit.update { it.copy(tool = ObjectEditTool.NONE, selectedId = null) }
            }
            "ink" -> {
                _edit.update { it.copy(tool = AnnotationTool.INK) }
                _objectEdit.update { it.copy(tool = ObjectEditTool.NONE, selectedId = null) }
            }
            "note" -> {
                _edit.update { it.copy(tool = AnnotationTool.NOTE) }
                _objectEdit.update { it.copy(tool = ObjectEditTool.NONE, selectedId = null) }
            }
            "text" -> _objectEdit.update { it.copy(tool = ObjectEditTool.TEXT, selectedId = null) }
            "image" -> _objectEdit.update { it.copy(tool = ObjectEditTool.IMAGE, selectedId = null) }
            else -> _objectEdit.update { it.copy(tool = ObjectEditTool.SELECT) }
        }
        ensureFieldsLoaded()
    }

    /** Undo for the top bar: object edits first, then the last annotation. */
    fun undoAny() {
        if (_objectEdit.value.canUndo) undoObjectEdit() else undoAnnotation()
    }

    /** Redo for the top bar (only object edits keep a redo stack). */
    fun redoAny() {
        if (_objectEdit.value.canRedo) redoObjectEdit()
    }

    /** Drops annotations added after [count]; used when a tool is cancelled. */
    fun trimAnnotationsTo(count: Int) = _edit.update {
        it.copy(annotations = it.annotations.take(count.coerceAtLeast(0)), inkDraft = null)
    }

    fun selectObject(id: String) = _objectEdit.update { it.copy(selectedId = id) }

    fun clearObjectSelection() = _objectEdit.update { it.copy(selectedId = null) }

    /**
     * Adds a text object through [PdfEngine]. [bounds] uses page fractions so
     * it survives zoom and re-rendering.
     */
    fun addTextObject(
        pageIndex: Int,
        bounds: EditableBounds,
        content: String,
        style: EditableStyle
    ): EditableObject? {
        if (!objectEngine.capabilities.addText) {
            _message.value = "Add text is coming soon: the PDF engine has no content-editing API yet"
            return null
        }
        val created = objectEngine.addText(pageIndex, bounds, content, style) ?: return null
        publishObjects(selectedId = created.id)
        _objectEdit.update { it.copy(tool = ObjectEditTool.SELECT) }
        return created
    }

    fun addImageObject(
        pageIndex: Int,
        bounds: EditableBounds,
        contentUri: String
    ): EditableObject? {
        if (!objectEngine.capabilities.addImage) {
            _message.value = "Add image is coming soon: the PDF engine has no content-editing API yet"
            return null
        }
        val created = objectEngine.addImage(
            pageIndex = pageIndex,
            bounds = bounds,
            contentUri = contentUri,
            style = EditableStyle()
        ) ?: return null
        publishObjects(selectedId = created.id)
        _objectEdit.update { it.copy(tool = ObjectEditTool.SELECT) }
        return created
    }

    fun updateTextObject(id: String, content: String, style: EditableStyle) {
        objectEngine.updateText(id, content, style) ?: return
        publishObjects(selectedId = id)
    }

    fun replaceImageObject(id: String, contentUri: String) {
        objectEngine.updateImage(id, contentUri) ?: return
        publishObjects(selectedId = id)
    }

    fun updateObjectBounds(id: String, bounds: EditableBounds) {
        objectEngine.updateBounds(id, bounds) ?: return
        publishObjects(selectedId = id)
    }

    fun deleteObject(id: String) {
        if (!objectEngine.delete(id)) return
        publishObjects(selectedId = null)
    }

    fun undoObjectEdit() {
        if (!objectEngine.undo()) return
        publishObjects(dirty = true)
    }

    fun redoObjectEdit() {
        if (!objectEngine.redo()) return
        publishObjects(dirty = true)
    }

    /** Saves object edits when the engine can; FakeEngine only marks the demo. */
    fun saveObjectEdits() {
        val current = _objectEdit.value
        if (!current.dirty) {
            _message.value = "No object edits to save yet"
            return
        }
        if (!objectEngine.capabilities.save || !objectEngine.save()) {
            _message.value = "Saving objects is coming soon: the PDF engine cannot write them yet"
            return
        }
        _objectEdit.update { it.copy(dirty = false) }
        _message.value = if (objectEngine.isFake) {
            "Demo save: text and image edits are simulated and were not written to the PDF"
        } else {
            "Saved"
        }
    }

    /** Save button for the object toolbar: handles markup and object edits. */
    fun saveAll() {
        val objectDirty = _objectEdit.value.dirty
        val markupDirty = _edit.value.hasPendingChanges
        if (!objectDirty && !markupDirty) {
            _message.value = "Nothing to save yet"
            return
        }
        if (objectDirty) saveObjectEdits()
        if (markupDirty) save()
    }

    private fun publishObjects(
        selectedId: String? = _objectEdit.value.selectedId,
        dirty: Boolean = true
    ) {
        val objects = objectEngine.allObjects()
        _objectEdit.update {
            it.copy(
                objects = objects,
                selectedId = selectedId?.takeIf { id -> objects.any { obj -> obj.id == id } },
                canUndo = objectEngine.canUndo,
                canRedo = objectEngine.canRedo,
                dirty = it.dirty || dirty,
                fake = objectEngine.isFake,
                engineName = objectEngine.name
            )
        }
    }

    /** Turns the current selection into a highlight annotation, then clears it. */
    fun highlightSelection() {
        val current = _selection.value ?: return
        val spec = selectionToHighlight(current) ?: return
        _edit.update { it.copy(annotations = it.annotations + spec) }
        clearSelection()
    }

    fun startInk(pageIndex: Int, fractionX: Float, fractionY: Float) {
        val point = toUserSpace(pageIndex, fractionX, fractionY) ?: return
        _edit.update { it.copy(inkDraft = InkDraft(pageIndex, listOf(point[0], point[1]))) }
    }

    fun extendInk(fractionX: Float, fractionY: Float) {
        val draft = _edit.value.inkDraft ?: return
        val point = toUserSpace(draft.pageIndex, fractionX, fractionY) ?: return
        if (draft.points.size >= 2) {
            val lastX = draft.points[draft.points.size - 2]
            val lastY = draft.points[draft.points.size - 1]
            val dx = point[0] - lastX
            val dy = point[1] - lastY
            if (dx * dx + dy * dy < 1.0) return // ignore sub-point jitter
        }
        _edit.update { it.copy(inkDraft = draft.copy(points = draft.points + listOf(point[0], point[1]))) }
    }

    fun finishInk() {
        val draft = _edit.value.inkDraft ?: return
        if (draft.points.size >= 4) {
            _edit.update {
                it.copy(
                    annotations = it.annotations + InkSpec(draft.pageIndex, listOf(draft.points)),
                    inkDraft = null
                )
            }
        } else {
            _edit.update { it.copy(inkDraft = null) }
        }
    }

    /** Adds a sticky note of [text] at the tapped point (page user space). */
    fun addNote(pageIndex: Int, fractionX: Float, fractionY: Float, text: String, title: String? = null) {
        if (text.isBlank()) return
        val point = toUserSpace(pageIndex, fractionX, fractionY) ?: return
        val rect = noteRectAt(point[0], point[1], NOTE_SIZE)
        _edit.update {
            it.copy(annotations = it.annotations + NoteSpec(pageIndex, rect, text.trim(), title = title))
        }
    }

    fun undoAnnotation() = _edit.update {
        it.copy(annotations = it.annotations.dropLast(1), inkDraft = null)
    }

    fun clearAnnotations() = _edit.update { it.copy(annotations = emptyList(), inkDraft = null) }

    // --------------------------------------------------------- forms (T12)

    fun setFieldValue(name: String, value: String) = _edit.update {
        it.copy(fieldValues = it.fieldValues + (name to value))
    }

    private fun ensureFieldsLoaded() {
        if (fieldsLoaded) return
        fieldsLoaded = true
        _edit.update { it.copy(loadingFields = true) }
        textScope.launch {
            val fields = runCatching { document?.formFields() ?: emptyList() }.getOrDefault(emptyList())
            if (!isActive) return@launch
            _edit.update { it.copy(fields = fields, loadingFields = false) }
        }
    }

    // ---------------------------------------------------------- saving (T12)

    /**
     * Builds a new PDF with the pending annotations and field values and parks
     * it in [export] for the UI to write through SAF. Document access stays on
     * the shared document dispatcher.
     */
    fun save() {
        val doc = document ?: return
        val current = _edit.value
        if (!current.hasPendingChanges) {
            _message.value = "Nothing to save yet"
            return
        }
        _edit.update { it.copy(saving = true) }
        textScope.launch {
            val result = runCatching {
                PdfPageOps.edit(doc, current.annotations, current.fieldValues).getOrThrow()
            }
            if (!isActive) return@launch
            result.fold(
                onSuccess = { bytes ->
                    _edit.update { it.copy(saving = false) }
                    _export.value = ViewerExport(bytes, suggestedName())
                },
                onFailure = { error ->
                    _edit.update { it.copy(saving = false) }
                    _message.value = error.message ?: "Save failed"
                }
            )
        }
    }

    /** Writes the pending export to the user-chosen [uri]. */
    fun completeExport(uri: Uri) {
        val pending = _export.value ?: return
        _export.value = null
        _edit.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { it.write(pending.bytes) }
                        ?: throw IOException("Could not open the destination for writing")
                }
            }
            result.fold(
                onSuccess = {
                    _edit.update { it.copy(saving = false, annotations = emptyList(), fieldValues = emptyMap()) }
                    _message.value = "Saved"
                },
                onFailure = { error ->
                    _edit.update { it.copy(saving = false) }
                    _message.value = error.message ?: "Save failed"
                }
            )
        }
    }

    fun cancelExport() {
        _export.value = null
    }

    /**
     * Saves the PDF that is open right now (the banner's "Save as PDF"): reads
     * the cached file back and parks it in [export] for the same SAF flow the
     * annotation export uses.
     */
    fun saveConvertedPdf(suggestedName: String) {
        val path = currentPath
        if (path.isBlank()) return
        viewModelScope.launch {
            val bytes = runCatching {
                withContext(Dispatchers.IO) { File(path).readBytes() }
            }.getOrNull()
            if (bytes == null) {
                _message.value = "The converted PDF is no longer in the cache; open the document again"
            } else {
                _export.value = ViewerExport(bytes, suggestedName)
            }
        }
    }

    /** Maps a fractional page point to PDF user space through the render plan. */
    private fun toUserSpace(pageIndex: Int, fractionX: Float, fractionY: Float): DoubleArray? {
        val plan = _plans.value[pageIndex] ?: return null
        return pageFractionToUserSpace(plan, fractionX, fractionY)
    }

    private fun suggestedName(): String {
        val base = (_state.value.displayName ?: "document.pdf").substringBeforeLast('.')
        return "$base-annotated.pdf"
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** Shows a short snackbar message (used by the tools menu). */
    fun showMessage(text: String) {
        _message.value = text
    }

    override fun onCleared() {
        renderScope.cancel()
        textScope.cancel()
        jobs.clear()
        _bitmaps.value = emptyMap()
        _plans.value = emptyMap()
        _selection.value = null
        _edit.value = ViewerEditState()
        objectEngine.clear()
        _objectEdit.value = ObjectEditState(
            fake = objectEngine.isFake,
            engineName = objectEngine.name
        )
        _export.value = null
        cache.clear()
        layouts.clear()
        document?.close()
        document = null
        pages = emptyList()
        super.onCleared()
    }

    // ---------------------------------------------------------------- internals

    /** Text layout of one page; must be called on the document dispatcher. */
    private fun layoutFor(index: Int): PageLayout? {
        layouts.get(index)?.let { return it }
        val page = pages.getOrNull(index) ?: return null
        val layout = page.layout()
        layouts.put(index, layout)
        return layout
    }

    private fun clearTextState() {
        searchJob?.cancel()
        _search.value = ViewerSearchState()
        _selection.value = null
        selectionAnchor = null
        layouts.clear()
        fieldsLoaded = false
        _edit.value = ViewerEditState()
        objectEngine.clear()
        _objectEdit.value = ObjectEditState(
            fake = objectEngine.isFake,
            engineName = objectEngine.name
        )
        _export.value = null
    }

    private fun publish(index: Int, plan: PageRenderPlan, bitmap: Bitmap) {
        _plans.update { it + (index to plan) }
        _bitmaps.update { it + (index to bitmap) }
    }

    /** A cached bitmap is reusable when its width is within ~10% of the target. */
    private fun resolutionMatches(cachedWidth: Int, targetWidth: Int): Boolean =
        abs(cachedWidth - targetWidth) <= max(16, targetWidth / 10)

    private companion object {
        /** Upper bound on search hits kept for one query. */
        const val MAX_MATCHES: Int = 1000

        /** Default size, in points, of a newly placed sticky note (T12). */
        const val NOTE_SIZE: Double = 20.0

        /**
         * Bitmap budget: a sixth of the heap, clamped to 16..64 MB. Together
         * with one-page-at-a-time rendering this keeps a 500-page document
         * bounded regardless of page count.
         */
        fun bitmapBudgetBytes(): Long {
            val maxHeap = Runtime.getRuntime().maxMemory()
            return (maxHeap / 6).coerceIn(16L * 1024 * 1024, 64L * 1024 * 1024)
        }
    }
}
