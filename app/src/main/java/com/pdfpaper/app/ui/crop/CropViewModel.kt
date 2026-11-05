package com.pdfpaper.app.ui.crop

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.render.AndroidImageDecoder
import com.pdfpaper.app.render.AndroidPageRasterizer
import com.pdfpaper.app.render.PageBitmapRenderer
import com.pdfpaper.engine.crop.CropRequest
import com.pdfpaper.engine.crop.CropResult
import com.pdfpaper.engine.crop.CropSpec
import com.pdfpaper.engine.crop.MarginPreset
import com.pdfpaper.engine.crop.PdfCrop
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.mark.PageSelection
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.render.PageRenderPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One page as the crop editor needs it: boxes in unrotated PDF user space. */
data class CropPageInfo(
    val index: Int,
    val media: PdfRect,
    val current: PdfRect,
    val rotate: Int
)

/** All pages vs. one box per page. */
enum class CropEditMode(val label: String) {
    ALL("Apply to all pages"),
    PER_PAGE("Per page")
}

/** Everything the crop screen shows. */
data class CropUiState(
    val path: String = "",
    val fileName: String = "",
    val pages: List<CropPageInfo> = emptyList(),
    val currentPage: Int = 0,
    val bitmap: Bitmap? = null,
    val plan: PageRenderPlan? = null,
    val pageMode: CropPageMode = CropPageMode.ALL,
    val customPages: String = "",
    val editMode: CropEditMode = CropEditMode.ALL,
    val margins: CropMargins = CropMargins.ZERO,
    val boxes: Map<Int, PdfRect> = emptyMap(),
    val tolerance: Int = PdfCrop.DEFAULT_TOLERANCE,
    val sameSize: Boolean = true,
    val removeHidden: Boolean = false,
    val showGrid: Boolean = true,
    val loading: Boolean = false,
    val rendering: Boolean = false,
    val autoRunning: Boolean = false,
    val applying: Boolean = false,
    val autoSummary: String? = null,
    val autoWarnings: List<String> = emptyList(),
    val result: CropResult? = null,
    val error: String? = null,
    val saved: Boolean = false
) {
    fun page(index: Int = currentPage): CropPageInfo? = pages.getOrNull(index)

    /**
     * The crop box shown for a page. In "all pages" mode it comes from the
     * shared margins; in per-page mode from the map, defaulting to the page's
     * current (unchanged) CropBox.
     */
    fun boxFor(index: Int = currentPage): PdfRect? {
        val info = page(index) ?: return null
        return if (editMode == CropEditMode.ALL) {
            cropBoxFromMargins(info.current, margins)
        } else {
            boxes[index] ?: info.current
        }
    }

    /** The selection the Apply button will use. */
    fun selection(): PageSelection = parseCropPages(pageMode, customPages)

    /** True when custom mode has no usable page number yet. */
    fun selectionIsEmpty(): Boolean =
        selection().let { it is PageSelection.Custom && it.pages.isEmpty() }
}

/**
 * The crop editor's state holder (T46). Rendering and PDF work run off the
 * main thread; only one preview render is in flight at a time and a page change
 * cancels the previous one, the same rule the viewer uses.
 */
class CropViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(CropUiState())
    val state: StateFlow<CropUiState> = _state.asStateFlow()

    private var renderJob: Job? = null
    private var workJob: Job? = null

    /**
     * Bumped by every edit and every load. Auto-crop and apply capture it and
     * ignore their result when it changed meanwhile, so a slow run can never
     * overwrite a box the user edited while it was working.
     */
    private var editGeneration = 0

    /**
     * Opens [path]. [initialBoxes] is the T57 handoff: the viewer's fitted
     * content boxes, already in user space, seeded as one box per page.
     */
    fun load(path: String, initialBoxes: Map<Int, PdfRect> = emptyMap()) {
        if (path.isBlank()) {
            _state.update { it.copy(error = "No document to crop.") }
            return
        }
        editGeneration++
        workJob?.cancel()
        renderJob?.cancel()
        _state.update {
            it.copy(
                path = path,
                fileName = File(path).name,
                pages = emptyList(),
                currentPage = 0,
                bitmap = null,
                plan = null,
                pageMode = CropPageMode.ALL,
                customPages = "",
                editMode = CropEditMode.ALL,
                margins = CropMargins.ZERO,
                boxes = emptyMap(),
                removeHidden = false,
                loading = true,
                rendering = false,
                autoRunning = false,
                applying = false,
                result = null,
                error = null,
                saved = false,
                autoSummary = null,
                autoWarnings = emptyList()
            )
        }
        workJob = viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                runCatching {
                    PdfDocument.open(FileSource(File(path))).getOrThrow().use { document ->
                        document.pages().map { page ->
                            val box = PageGeometry.boxOf(page)
                            CropPageInfo(
                                index = page.index,
                                media = PdfRect.of(page.mediaBox)
                                    ?: PdfRect(box.left, box.bottom, box.right, box.top),
                                current = PdfRect(box.left, box.bottom, box.right, box.top),
                                rotate = page.rotate
                            )
                        }
                    }
                }
            }
            pages.onSuccess { list ->
                if (list.isEmpty()) {
                    _state.update { it.copy(loading = false, error = "This PDF has no pages.") }
                    return@onSuccess
                }
                val seeded = initialBoxes.filterKeys { it in list.indices }
                _state.update {
                    it.copy(
                        pages = list,
                        currentPage = 0,
                        loading = false,
                        margins = CropMargins.ZERO,
                        boxes = seeded,
                        editMode = if (seeded.isEmpty()) CropEditMode.ALL else CropEditMode.PER_PAGE
                    )
                }
                renderPage(0)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = "Could not read this PDF: ${error.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    /** Renders the current page at a preview scale; the plan maps handles. */
    fun renderPage(index: Int) {
        val pages = _state.value.pages
        if (index !in pages.indices) return
        val path = _state.value.path
        renderJob?.cancel()
        _state.update {
            it.copy(
                currentPage = index,
                bitmap = null,
                plan = null,
                rendering = true,
                error = null
            )
        }
        renderJob = viewModelScope.launch {
            val rendered = withContext(Dispatchers.IO) {
                runCatching {
                    PdfDocument.open(FileSource(File(path))).getOrThrow().use { document ->
                        val page = document.pages()[index]
                        PageBitmapRenderer.render(
                            page = page,
                            scale = PREVIEW_SCALE,
                            maxPixels = PREVIEW_MAX_PIXELS,
                            decoder = AndroidImageDecoder
                        )
                    }
                }
            }
            rendered.onSuccess { result ->
                _state.update {
                    if (it.path == path && it.currentPage == index) {
                        it.copy(
                            bitmap = result.bitmap,
                            plan = result.plan,
                            rendering = false
                        )
                    } else {
                        it
                    }
                }
            }.onFailure { error ->
                _state.update {
                    if (it.path == path && it.currentPage == index) {
                        it.copy(
                            rendering = false,
                            error = "Could not render page ${index + 1}: ${error.message ?: "unknown error"}"
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun previousPage() {
        if (_state.value.currentPage > 0) renderPage(_state.value.currentPage - 1)
    }

    fun nextPage() {
        if (_state.value.currentPage < _state.value.pages.lastIndex) {
            renderPage(_state.value.currentPage + 1)
        }
    }

    fun setPageMode(mode: CropPageMode) {
        editGeneration++
        _state.update { it.copy(pageMode = mode, result = null, error = null) }
    }

    fun setCustomPages(text: String) {
        editGeneration++
        _state.update {
            it.copy(customPages = text, pageMode = CropPageMode.CUSTOM, result = null, error = null)
        }
    }

    /**
     * Switching modes carries the boxes across, so the preview never jumps:
     * "all pages" derives the shared margins from the visible page, and
     * "per page" seeds every page with the box it currently shows.
     */
    fun setEditMode(mode: CropEditMode) {
        editGeneration++
        _state.update { current ->
            if (mode == current.editMode) return@update current
            if (mode == CropEditMode.PER_PAGE) {
                val seeded = current.pages.associate { info ->
                    info.index to (current.boxFor(info.index) ?: info.current)
                }
                current.copy(editMode = mode, boxes = seeded, result = null, error = null)
            } else {
                val info = current.page() ?: return@update current
                val box = current.boxFor()
                val margins = box?.let { marginsFor(info.current, it) } ?: CropMargins.ZERO
                current.copy(editMode = mode, margins = margins, result = null, error = null)
            }
        }
    }

    fun setPreset(preset: MarginPreset) {
        editGeneration++
        val margins = CropMargins(
            preset.margins[0],
            preset.margins[1],
            preset.margins[2],
            preset.margins[3]
        )
        _state.update { current ->
            when (current.editMode) {
                CropEditMode.ALL -> current.copy(margins = margins, result = null, error = null)
                CropEditMode.PER_PAGE -> {
                    val info = current.page() ?: return@update current
                    val box = cropBoxFromMargins(info.current, margins) ?: return@update current
                    current.copy(
                        boxes = current.boxes + (info.index to box),
                        result = null,
                        error = null
                    )
                }
            }
        }
    }

    /** A corner drag (or an automated box) changes one page's box. */
    fun setCropBox(box: PdfRect) {
        editGeneration++
        _state.update { current ->
            val info = current.page() ?: return@update current
            when (current.editMode) {
                CropEditMode.ALL -> {
                    val margins = marginsFor(info.current, box) ?: return@update current
                    current.copy(margins = margins, result = null, error = null)
                }
                CropEditMode.PER_PAGE ->
                    current.copy(
                        boxes = current.boxes + (info.index to box),
                        result = null,
                        error = null
                    )
            }
        }
    }

    fun resetCurrent() {
        editGeneration++
        _state.update { current ->
            val info = current.page() ?: return@update current
            when (current.editMode) {
                CropEditMode.ALL ->
                    current.copy(margins = CropMargins.ZERO, result = null, error = null)
                CropEditMode.PER_PAGE ->
                    current.copy(
                        boxes = current.boxes - info.index,
                        result = null,
                        error = null
                    )
            }
        }
    }

    fun setTolerance(value: Int) {
        editGeneration++
        _state.update { it.copy(tolerance = value.coerceIn(0, 255), error = null) }
    }

    fun setSameSize(value: Boolean) {
        editGeneration++
        _state.update { it.copy(sameSize = value) }
    }

    fun setRemoveHidden(value: Boolean) {
        editGeneration++
        _state.update { it.copy(removeHidden = value, result = null) }
    }

    fun setShowGrid(value: Boolean) {
        _state.update { it.copy(showGrid = value) }
    }

    /** Runs engine white-margin detection and fills the per-page boxes. */
    fun autoCrop() {
        val current = _state.value
        if (current.autoRunning || current.applying || current.pages.isEmpty()) return
        if (current.selectionIsEmpty()) {
            _state.update { it.copy(error = "Enter page numbers such as 1,3-5.") }
            return
        }
        workJob?.cancel()
        val generation = ++editGeneration
        _state.update {
            it.copy(autoRunning = true, result = null, error = null, autoSummary = null)
        }
        workJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PdfDocument.open(FileSource(File(current.path))).getOrThrow().use { document ->
                        PdfCrop.auto(
                            document = document,
                            rasterizer = AndroidPageRasterizer,
                            tolerance = current.tolerance,
                            dpi = AUTO_DPI,
                            pages = current.selection(),
                            sameSize = current.sameSize
                        ).getOrThrow()
                    }
                }
            }
            if (editGeneration != generation) {
                _state.update { it.copy(autoRunning = false) }
                return@launch
            }
            result.onSuccess { auto ->
                val detected = auto.boxes.size
                val summary = buildString {
                    append("Detected content on $detected ")
                    append(if (detected == 1) "page" else "pages")
                    if (auto.emptyPages.isNotEmpty()) {
                        append("; blank: ")
                        append(auto.emptyPages.joinToString(", ") { (it + 1).toString() })
                    }
                    auto.uniformSize?.let {
                        append("; common size ${formatPoints(it.width)} × ${formatPoints(it.height)} pt")
                    }
                }
                if (detected == 0) {
                    _state.update {
                        it.copy(
                            autoRunning = false,
                            autoWarnings = auto.warnings,
                            error = "No content was found at tolerance ${current.tolerance}."
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            autoRunning = false,
                            boxes = auto.boxes,
                            editMode = CropEditMode.PER_PAGE,
                            autoSummary = summary,
                            autoWarnings = auto.warnings,
                            error = null
                        )
                    }
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        autoRunning = false,
                        error = "Auto-crop failed: ${error.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    /** Crops with the current settings. A normal crop only hides; the switch deletes. */
    fun applyCrop() {
        val current = _state.value
        if (current.applying || current.autoRunning || current.pages.isEmpty()) return
        if (current.selectionIsEmpty()) {
            _state.update { it.copy(error = "Enter page numbers such as 1,3-5.") }
            return
        }
        val spec = when (current.editMode) {
            CropEditMode.ALL -> {
                val box = current.boxFor()
                if (box == null) {
                    _state.update { it.copy(error = "Those margins leave no page area.") }
                    return
                }
                CropSpec.Margins(
                    current.margins.left,
                    current.margins.bottom,
                    current.margins.right,
                    current.margins.top
                )
            }
            CropEditMode.PER_PAGE -> {
                val selection = current.selection()
                val boxes = LinkedHashMap<Int, PdfRect>()
                for (info in current.pages) {
                    if (!selection.includes(info.index)) continue
                    boxes[info.index] = current.boxFor(info.index) ?: info.current
                }
                if (boxes.isEmpty()) {
                    _state.update { it.copy(error = "No page has a crop box.") }
                    return
                }
                CropSpec.PerPage(boxes)
            }
        }
        workJob?.cancel()
        val generation = ++editGeneration
        _state.update { it.copy(applying = true, error = null, result = null) }
        workJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PdfDocument.open(FileSource(File(current.path))).getOrThrow().use { document ->
                        PdfCrop.apply(
                            document = document,
                            request = CropRequest(spec, current.selection()),
                            removeHiddenContent = current.removeHidden,
                            decoder = AndroidImageDecoder
                        ).getOrThrow()
                    }
                }
            }
            if (editGeneration != generation) {
                _state.update { it.copy(applying = false) }
                return@launch
            }
            result.onSuccess { cropped ->
                _state.update { it.copy(applying = false, result = cropped) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        applying = false,
                        error = "Could not crop this PDF: ${error.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    /** Writes the cropped bytes through the SAF URI the screen picked. */
    fun saveTo(uri: Uri) {
        val result = _state.value.result ?: return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { out -> out.write(result.bytes) }
                        ?: error("The storage provider did not open a file")
                }
            }
            outcome.onSuccess {
                _state.update { it.copy(saved = true, error = null) }
            }.onFailure { error ->
                _state.update { it.copy(error = "Could not save: ${error.message ?: "unknown error"}") }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private companion object {
        const val PREVIEW_SCALE = 1.4
        const val PREVIEW_MAX_PIXELS = 8_000_000
        const val AUTO_DPI = 96
    }
}
