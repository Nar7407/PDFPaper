package com.pdfpaper.app.ui.pages

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.AnnotationLayerStore
import com.pdfpaper.app.data.DocumentFingerprints
import com.pdfpaper.app.data.DocumentLookStore
import com.pdfpaper.app.data.LookResolution
import com.pdfpaper.app.data.EinkResolution
import com.pdfpaper.app.ui.viewer.AnnotationLayersState
import com.pdfpaper.app.render.PageBitmapRenderer
import com.pdfpaper.engine.annot.AnnotationFilter
import com.pdfpaper.engine.eink.EinkSettings
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.ColorTransform
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.render.PageTheme
import com.pdfpaper.engine.security.PdfPasswordException
import com.pdfpaper.engine.util.LruCache
import com.pdfpaper.engine.write.PdfPageOps
import com.pdfpaper.engine.write.PdfPageSelection
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

private const val THUMB_PIXEL_WIDTH = 320

/** A document opened by the organizer (the document being edited, plus any merged in). */
class LoadedDocument(
    val name: String,
    val document: PdfDocument,
    val pages: List<PdfPage>,
    val boxes: List<PageBox>
)

/** Output bytes waiting for the user to choose a destination (SAF CreateDocument). */
class PendingExport(val bytes: ByteArray, val suggestedName: String)

data class PagesUiState(
    val isLoading: Boolean = true,
    val displayName: String? = null,
    val error: String? = null,
    val selection: Set<Int> = emptySet(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val exporting: Boolean = false,
    val message: String? = null,
    val pendingExport: PendingExport? = null,
    /** True when the primary document is encrypted and awaits a password (T13). */
    val requiresPassword: Boolean = false,
    /** Set after a rejected password attempt. */
    val passwordError: String? = null
)

/**
 * Organizer state for one document (T10): an ordered page [plan] with undo/redo,
 * a multi-selection, thumbnail rendering and page operations.
 *
 * The plan is immutable; every edit creates a new list that is recorded by a
 * [PagePlanHistory], so undo/redo is exact and cheap. Saving serialises the plan
 * through the engine's [PdfPageOps], which preserves links, bookmarks and form
 * fields that still point at a kept page.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // limitedParallelism
class PagesViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PagesUiState())
    val state: StateFlow<PagesUiState> = _state.asStateFlow()

    // T68b: thumbnail annotation layers, restored from the same store as the viewer.
    private val annotationLayerStore by lazy { AnnotationLayerStore(getApplication()) }
    private var layers: AnnotationLayersState = AnnotationLayersState.SHOW_ALL

    /** The filter every thumbnail render paints with. */
    val layerFilter: AnnotationFilter
        get() = layers.toEngineFilter()

    /**
     * Picks up the layer choice saved for the open document. A change drops the
     * cached bitmaps (they were painted with the old filter) and bumps the
     * generation so visible cells ask again.
     */
    fun refreshThumbnailLayers() {
        val next = annotationLayerStore.load(lookFingerprint)
        if (next == layers) return
        layers = next
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
        cache.clear()
        _thumbs.value = emptyMap()
        _themeGeneration.value += 1
    }

    private val _plan = MutableStateFlow<List<PlanPage>>(emptyList())
    val plan: StateFlow<List<PlanPage>> = _plan.asStateFlow()

    /** Box per plan entry, already carrying any rotation added this session. */
    private val _boxes = MutableStateFlow<List<PageBox>>(emptyList())
    val boxes: StateFlow<List<PageBox>> = _boxes.asStateFlow()

    private val _thumbs = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    val thumbs: StateFlow<Map<Int, Bitmap>> = _thumbs.asStateFlow()

    /** T32/T32b: the theme thumbnails are painted with. */
    private val _pageTheme = MutableStateFlow(PageTheme.OFF)
    val pageTheme: StateFlow<PageTheme> = _pageTheme.asStateFlow()

    /** T62: the document's own look, so a sepia book's thumbnails are sepia. */
    private val _look = MutableStateFlow(ColorTransform.NONE)
    val look: StateFlow<ColorTransform> = _look.asStateFlow()

    /** T63: the resolved E-Ink profile for the thumbnails. */
    private val _eink = MutableStateFlow(EinkSettings.OFF)

    private val lookStore by lazy { DocumentLookStore(getApplication()) }
    private var lookFingerprint: String? = null

    /** Bumped on a theme change so visible cells ask for a new thumbnail. */
    private val _themeGeneration = MutableStateFlow(0)
    val themeGeneration: StateFlow<Int> = _themeGeneration.asStateFlow()

    private val docs = ArrayList<LoadedDocument>()
    private lateinit var history: PagePlanHistory

    /** Path of the primary document, kept so a password retry can reopen it (T13). */
    private var currentPath: String = ""

    /** Extra documents an incoming multi-PDF share asked to merge (T47). */
    private var pendingExtras: List<String> = emptyList()

    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val renderJobs = HashMap<Int, Job>()

    /** Keyed by (docIndex, sourceIndex, rotationDelta) so a rotation re-renders. */
    private val cache = LruCache<Triple<Int, Int, Int>, Bitmap>(thumbnailBudgetBytes()) {
        it.allocationByteCount.toLong()
    }

    private val fontDir: File by lazy { File(getApplication<Application>().cacheDir, "embedded-fonts") }

    fun load(path: String, extraPaths: List<String> = emptyList()) {
        currentPath = path
        pendingExtras = extraPaths.filter { it.isNotBlank() && it != path }
        openPrimary(path, "")
    }

    /** Retries opening the primary document with [password] (T13). */
    fun unlock(password: String) = openPrimary(currentPath, password)

    /** Gives up on the password prompt. */
    fun cancelUnlock() = _state.update {
        it.copy(requiresPassword = false, passwordError = null, error = "Password required")
    }

    private fun openPrimary(path: String, password: String) {
        if (path.isBlank()) {
            _state.value = PagesUiState(isLoading = false, error = "No file selected")
            return
        }
        _state.value = PagesUiState(isLoading = true)
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) { runCatching { openDocument(path, password) } }
            opened.fold(
                onSuccess = { doc ->
                    docs.clear()
                    docs += doc
                    lookFingerprint = withContext(Dispatchers.IO) {
                        DocumentFingerprints.of(getApplication(), path)
                    }
                    layers = annotationLayerStore.load(lookFingerprint)
                    cache.clear()
                    history = PagePlanHistory(doc.pages.indices.map { PlanPage(0, it) })
                    publishPlan()
                    _state.value = PagesUiState(isLoading = false, displayName = doc.name)
                    val extras = pendingExtras
                    pendingExtras = emptyList()
                    if (extras.isNotEmpty()) appendExtras(extras)
                },
                onFailure = { error ->
                    if (error is PdfPasswordException) {
                        _state.value = PagesUiState(
                            isLoading = false,
                            requiresPassword = true,
                            passwordError = if (password.isEmpty()) null else "Incorrect password"
                        )
                    } else {
                        _state.value = PagesUiState(
                            isLoading = false,
                            error = error.message ?: "Cannot open this document"
                        )
                    }
                }
            )
        }
    }

    /**
     * Loads every extra document from a multi-PDF share and appends it, then
     * publishes one undoable merge (T47).
     */
    private fun appendExtras(paths: List<String>) {
        viewModelScope.launch {
            val newPlan = history.plan.toMutableList()
            var addedPages = 0
            var addedDocs = 0
            val failures = mutableListOf<String>()
            for (path in paths) {
                val opened = withContext(Dispatchers.IO) { runCatching { openDocument(path) } }
                opened.fold(
                    onSuccess = { doc ->
                        val docIndex = docs.size
                        docs += doc
                        newPlan += doc.pages.indices.map { PlanPage(docIndex, it) }
                        addedPages += doc.pages.size
                        addedDocs++
                    },
                    onFailure = { error ->
                        failures += if (error is PdfPasswordException) {
                            "a password-protected PDF"
                        } else {
                            error.message ?: "a PDF that could not be opened"
                        }
                    }
                )
            }
            if (addedDocs > 0) {
                history.commit(newPlan)
                publishPlan()
            }
            val summary = buildString {
                append("Merged $addedPages page(s) from ${addedDocs + 1} PDFs")
                if (failures.isNotEmpty()) {
                    append(". Skipped: ")
                    append(failures.joinToString("; "))
                }
            }
            _state.update { it.copy(message = summary) }
        }
    }

    /** Appends every page of another PDF (merge/insert). */
    fun addDocument(path: String) {
        if (docs.isEmpty()) {
            load(path)
            return
        }
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) { runCatching { openDocument(path) } }
            opened.fold(
                onSuccess = { doc ->
                    val docIndex = docs.size
                    docs += doc
                    history.commit(history.plan + doc.pages.indices.map { PlanPage(docIndex, it) })
                    publishPlan()
                    _state.update { it.copy(message = "Added ${doc.pages.size} page(s) from ${doc.name}") }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            message = if (error is PdfPasswordException) {
                                "That document is password protected"
                            } else {
                                error.message ?: "Could not open the second PDF"
                            }
                        )
                    }
                }
            )
        }
    }

    // -- Selection ------------------------------------------------------------

    fun toggleSelection(position: Int) {
        _state.update { current ->
            val next = if (position in current.selection) current.selection - position else current.selection + position
            current.copy(selection = next)
        }
    }

    fun selectAll() {
        _state.update { it.copy(selection = _plan.value.indices.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    // -- Edits ----------------------------------------------------------------

    fun rotateSelection(degrees: Int) {
        commit { plan, selection -> PagePlan.rotate(plan, selection, degrees) }
    }

    fun deleteSelection() {
        if (_state.value.selection.isEmpty()) return
        commit { plan, selection -> PagePlan.delete(plan, selection) }
        _state.update { it.copy(selection = emptySet()) }
    }

    fun moveSelectionUp() {
        commit { plan, selection -> PagePlan.moveUp(plan, selection) }
    }

    fun moveSelectionDown() {
        commit { plan, selection -> PagePlan.moveDown(plan, selection) }
    }

    /**
     * Applies the page tool the organizer was opened for (from the viewer's
     * tools menu) to the current selection. Tools that need a document picker
     * (Merge, Insert) are handled by the screen, which owns the picker.
     */
    fun applyTool(toolId: String) {
        when (toolId) {
            "rotate" -> rotateSelection(90)
            "reorder" -> moveSelectionUp()
            "delete" -> deleteSelection()
            "extract" -> requestSave(selectedOnly = true)
            "split" -> requestSplit(5)
        }
    }

    fun undo() {
        if (history.undo()) publishPlan()
    }

    fun redo() {
        if (history.redo()) publishPlan()
    }

    /** Restores the original page set and order, discarding history. */
    fun reset() {
        val original = docs.firstOrNull() ?: return
        history.reset(original.pages.indices.map { PlanPage(0, it) })
        _state.update { it.copy(selection = emptySet()) }
        publishPlan()
    }

    private inline fun commit(edit: (List<PlanPage>, Set<Int>) -> List<PlanPage>) {
        history.commit(edit(history.plan, _state.value.selection))
        publishPlan()
    }

    private fun publishPlan() {
        _plan.value = history.plan
        // Position-keyed thumbnails are stale as soon as the plan changes.
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
        _thumbs.value = emptyMap()
        _boxes.value = history.plan.map { entry ->
            val base = docs.getOrNull(entry.docIndex)?.boxes?.getOrNull(entry.sourceIndex)
            if (base == null) {
                PageBox(0.0, 0.0, PageGeometry.DEFAULT_WIDTH, PageGeometry.DEFAULT_HEIGHT, 0)
            } else {
                PageBox(base.left, base.bottom, base.right, base.top, normalize(base.rotate + entry.rotationDelta))
            }
        }
        // Selection positions may now be out of range after a delete.
        _state.update { it.copy(selection = it.selection.filter { p -> p in history.plan.indices }.toSet(), canUndo = history.canUndo, canRedo = history.canRedo) }
    }

    // -- Thumbnails -----------------------------------------------------------

    /**
     * Resolves the document's look and theme (T32/T32b, T62) for the thumbnails,
     * exactly as the viewer does for the pages. A look saved for this document
     * wins; otherwise the reader's default decides. Cached bitmaps are dropped
     * and every visible cell re-requests its thumbnail when either changes.
     */
    fun refreshLook(systemDark: Boolean) {
        val app = getApplication<Application>()
        val resolved = LookResolution.resolve(
            baseTheme = AppSettings.resolvedPageTheme(app, currentPath, systemDark),
            documentLook = lookStore.load(lookFingerprint),
            legacyMode = AppSettings.documentThemeMode(app, currentPath),
            defaultLook = AppSettings.defaultColorTransform(app),
            followSystem = AppSettings.pageThemeFollowsSystem(app),
            systemDark = systemDark
        )
        // T63: organizer thumbnails follow the same E-Ink profile as the
        // viewer, so a 1-bit library looks like the pages it opens.
        val eink = EinkResolution.resolve(
            device = AppSettings.einkSettings(app),
            enabled = AppSettings.einkEnabled(app),
            documentProfile = lookStore.loadEink(lookFingerprint)
        ).settings
        if (
            resolved.theme == _pageTheme.value &&
            resolved.transform == _look.value &&
            eink == _eink.value
        ) {
            return
        }
        _pageTheme.value = resolved.theme
        _look.value = resolved.transform
        _eink.value = eink
        _themeGeneration.value += 1
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
        cache.clear()
        _thumbs.value = emptyMap()
    }

    fun onThumbVisible(position: Int, targetPixelWidth: Int = THUMB_PIXEL_WIDTH) {
        val entry = _plan.value.getOrNull(position) ?: return
        val doc = docs.getOrNull(entry.docIndex) ?: return
        val page = doc.pages.getOrNull(entry.sourceIndex) ?: return

        cache.get(cacheKey(entry))?.let { cached ->
            _thumbs.update { it + (position to cached) }
            return
        }
        if (renderJobs[position]?.isActive == true) return
        renderJobs.remove(position)?.cancel()
        val scale = PageGeometry.scaleForPixelWidth(doc.boxes[entry.sourceIndex], targetPixelWidth).coerceIn(0.05, 4.0)
        renderJobs[position] = renderScope.launch {
            val bitmap = try {
                PageBitmapRenderer.render(
                    page,
                    scale,
                    fontDir = fontDir,
                    extraRotation = entry.rotationDelta,
                    theme = _pageTheme.value,
                    transform = _look.value,
                    eink = _eink.value,
                    annotationFilter = layerFilter
                ).bitmap
            } catch (_: Throwable) {
                null
            }
            if (bitmap != null && isActive) {
                cache.put(cacheKey(entry), bitmap)
                if (_plan.value.getOrNull(position) == entry) _thumbs.update { it + (position to bitmap) }
            }
            renderJobs.remove(position)
        }
    }

    fun onThumbHidden(position: Int) {
        renderJobs.remove(position)?.cancel()
        _thumbs.update { it - position }
    }

    // -- Export ---------------------------------------------------------------

    /** Prepares a save of the whole plan, or only the selected pages. */
    fun requestSave(selectedOnly: Boolean) {
        val entries = if (selectedOnly) {
            _plan.value.filterIndexed { index, _ -> index in _state.value.selection }
        } else {
            _plan.value
        }
        if (entries.isEmpty()) {
            _state.update { it.copy(message = "Nothing to save") }
            return
        }
        val base = docs.firstOrNull()?.name?.substringBeforeLast('.') ?: "document"
        val suffix = if (selectedOnly) "-selected" else "-edited"
        export(entries, "$base$suffix.pdf")
    }

    /** Splits the plan into parts of [everyN] pages, written to the app cache. */
    fun requestSplit(everyN: Int) {
        val entries = _plan.value
        if (entries.isEmpty() || everyN <= 0) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(getApplication<Application>().cacheDir, "split").apply { mkdirs() }
                    val base = docs.firstOrNull()?.name?.substringBeforeLast('.') ?: "document"
                    entries.chunked(everyN).mapIndexed { index, chunk ->
                        val bytes = PdfPageOps.assemble(selectionsOf(chunk), primary()).getOrThrow()
                        File(dir, "$base-part${index + 1}.pdf").apply { writeBytes(bytes) }
                    }
                }
            }
            written.fold(
                onSuccess = { files ->
                    _state.update { it.copy(exporting = false, message = "Split into ${files.size} file(s) in app cache/split/") }
                },
                onFailure = { error ->
                    _state.update { it.copy(exporting = false, message = error.message ?: "Split failed") }
                }
            )
        }
    }

    private fun export(entries: List<PlanPage>, suggestedName: String) {
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { PdfPageOps.assemble(selectionsOf(entries), primary()).getOrThrow() }
            }
            bytes.fold(
                onSuccess = { data ->
                    _state.update { it.copy(exporting = false, pendingExport = PendingExport(data, suggestedName)) }
                },
                onFailure = { error ->
                    _state.update { it.copy(exporting = false, message = error.message ?: "Save failed") }
                }
            )
        }
    }

    /** Writes the pending export to the user-chosen [uri]. */
    fun completeExport(uri: Uri) {
        val pending = _state.value.pendingExport ?: return
        _state.update { it.copy(pendingExport = null, exporting = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { it.write(pending.bytes) }
                        ?: throw IOException("Could not open the destination for writing")
                }
            }
            result.fold(
                onSuccess = { _state.update { it.copy(exporting = false, message = "Saved") } },
                onFailure = { error -> _state.update { it.copy(exporting = false, message = error.message ?: "Save failed") } }
            )
        }
    }

    fun cancelExport() {
        _state.update { it.copy(pendingExport = null) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun selectionsOf(entries: List<PlanPage>): List<PdfPageSelection> =
        entries.mapNotNull { entry ->
            val doc = docs.getOrNull(entry.docIndex) ?: return@mapNotNull null
            PdfPageSelection(doc.document, entry.sourceIndex, entry.rotationDelta)
        }

    private fun primary(): PdfDocument = docs.first().document

    private fun openDocument(path: String, password: String = ""): LoadedDocument {
        val document = PdfDocument.open(FileSource(File(path)), password).getOrThrow()
        val pages = document.pages()
        return LoadedDocument(File(path).name, document, pages, pages.map { PageGeometry.boxOf(it) })
    }

    private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360

    private fun cacheKey(entry: PlanPage): Triple<Int, Int, Int> =
        Triple(entry.docIndex, entry.sourceIndex, normalize(entry.rotationDelta))

    override fun onCleared() {
        renderScope.cancel()
        renderJobs.clear()
        _thumbs.value = emptyMap()
        cache.clear()
        docs.forEach { runCatching { it.document.close() } }
        docs.clear()
        super.onCleared()
    }

    private companion object {
        fun thumbnailBudgetBytes(): Long {
            val maxHeap = Runtime.getRuntime().maxMemory()
            return (maxHeap / 8).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024)
        }
    }
}
