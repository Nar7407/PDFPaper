package com.pdfpaper.app.ui.snip

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.ocr.MlKitOcrProvider
import com.pdfpaper.app.render.AndroidImageDecoder
import com.pdfpaper.app.render.PageBitmapRenderer
import com.pdfpaper.app.ui.viewer.pageFractionToUserSpace
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.ocr.OcrImage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.table.Table
import com.pdfpaper.engine.table.TableExtractor
import com.pdfpaper.engine.table.TableResult
import com.pdfpaper.engine.table.TableSource
import com.pdfpaper.engine.table.TableWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One page rectangle that has already been read into a table (T60). */
data class SnipRegion(
    val page: Int,
    val rect: PdfRect,
    val table: Table?,
    val confidence: Double,
    val source: TableSource
)

/** Everything the snip screen shows (T60). */
data class SnipUiState(
    val path: String = "",
    val fileName: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val bitmap: Bitmap? = null,
    val plan: PageRenderPlan? = null,
    val rendering: Boolean = false,
    /** The marquee the user drew on the current page. */
    val selection: PdfRect? = null,
    /** Live detection of [selection]. */
    val detection: TableResult? = null,
    /** Pages already read and stitched into the result. */
    val regions: List<SnipRegion> = emptyList(),
    val settings: SnipSettings = SnipSettings(),
    val busy: Boolean = false,
    val ocrRunning: Boolean = false,
    /** True when the selection had no text layer, so OCR is the only way. */
    val ocrNeeded: Boolean = false,
    val output: String = "",
    val error: String? = null,
    /** A one-shot status line ("Copied", "Saved"). */
    val message: String? = null,
    val saved: Boolean = false
) {
    val hasTable: Boolean get() = detection?.table != null

    /** The tables that would be stitched: completed pages plus the live one. */
    fun tables(): List<Table> {
        val done = regions.mapNotNull { it.table }
        val live = detection?.table
        return if (live != null) done + live else done
    }

    /** True when a multi-page table is being assembled. */
    val multiPage: Boolean get() = regions.isNotEmpty()

    /** The confidence of the whole selection (the weakest page). */
    fun confidence(): Double {
        val values = regions.map { it.confidence } + listOfNotNull(detection?.confidence)
        return values.minOrNull() ?: 0.0
    }

    fun source(): TableSource =
        if (regions.any { it.source == TableSource.OCR } || detection?.source == TableSource.OCR) {
            TableSource.OCR
        } else {
            TableSource.TEXT
        }

    fun canAddNextPage(): Boolean = currentPage + 1 < pageCount && detection?.table != null
}

/**
 * The snip tool's state holder (T60). Table detection runs on the engine off the
 * main thread; only one render and one detection run at a time, and a page
 * change cancels the previous render.
 */
class SnipViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SnipUiState())
    val state: StateFlow<SnipUiState> = _state.asStateFlow()

    private var renderJob: Job? = null
    private var workJob: Job? = null

    fun load(path: String) {
        if (path.isBlank()) {
            _state.update { it.copy(error = "No document to snip.") }
            return
        }
        renderJob?.cancel()
        workJob?.cancel()
        _state.update {
            SnipUiState(
                path = path,
                fileName = File(path).name,
                settings = AppSettings.snipSettings(getApplication())
            )
        }
        workJob = viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    PdfDocument.open(FileSource(File(path))).getOrThrow().use { it.pages().size }
                }
            }
            count.onSuccess { total ->
                if (total == 0) {
                    _state.update { it.copy(error = "This PDF has no pages.") }
                } else {
                    _state.update { it.copy(pageCount = total) }
                    renderPage(0)
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(error = "Could not read this PDF: ${error.message ?: "unknown error"}")
                }
            }
        }
    }

    fun renderPage(index: Int) {
        val current = _state.value
        if (index !in 0 until current.pageCount) return
        val path = current.path
        renderJob?.cancel()
        _state.update {
            it.copy(
                currentPage = index,
                bitmap = null,
                plan = null,
                selection = null,
                detection = null,
                ocrNeeded = false,
                rendering = true,
                error = null,
                message = null
            )
        }
        renderJob = viewModelScope.launch {
            val rendered = withContext(Dispatchers.IO) {
                runCatching {
                    PdfDocument.open(FileSource(File(path))).getOrThrow().use { document ->
                        PageBitmapRenderer.render(
                            page = document.pages()[index],
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
                        it.copy(bitmap = result.bitmap, plan = result.plan, rendering = false)
                    } else {
                        it
                    }
                }
            }.onFailure { error ->
                _state.update {
                    if (it.path == path && it.currentPage == index) {
                        it.copy(
                            rendering = false,
                            error = "Could not render page ${index + 1}: " +
                                (error.message ?: "unknown error")
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
        renderPage(_state.value.currentPage + 1)
    }

    /** The user finished a marquee drag. */
    fun selectRect(rect: PdfRect?) {
        _state.update {
            it.copy(
                selection = rect,
                detection = null,
                ocrNeeded = false,
                output = "",
                error = null,
                message = if (rect == null) "Drag a bigger box around the table." else null
            )
        }
        if (rect != null) detect()
    }

    /** Runs text-layer detection on the current selection. */
    fun detect() {
        val current = _state.value
        val rect = current.selection ?: return
        if (current.busy) return
        val page = current.currentPage
        val path = current.path
        _state.update { it.copy(busy = true, detection = null, error = null, ocrNeeded = false) }
        workJob?.cancel()
        workJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PdfDocument.open(FileSource(File(path))).getOrThrow().use { document ->
                        TableExtractor.extract(
                            page = document.pages()[page],
                            region = rect,
                            options = current.settings.options.engine(),
                            decoder = AndroidImageDecoder
                        ).getOrThrow()
                    }
                }
            }
            result.onSuccess { detection ->
                val empty = detection.table == null &&
                    detection.words.none { it.text.isNotBlank() }
                _state.update {
                    if (it.path == path && it.currentPage == page) {
                        it.copy(
                            busy = false,
                            detection = detection,
                            ocrNeeded = empty,
                            output = outputOf(it.copy(detection = detection)),
                            error = when {
                                detection.table != null -> null
                                empty -> "This page has no text layer. Run OCR to read the table."
                                else -> "No table detected in the box. Plain text is offered instead."
                            }
                        )
                    } else {
                        it
                    }
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(busy = false, error = "Detection failed: ${error.message ?: "unknown error"}")
                }
            }
        }
    }

    /**
     * Reads the current page with ML Kit and re-runs the same structure code on
     * the OCR word boxes, so a scanned table gets the same detection with a
     * lower confidence.
     */
    fun runOcr(language: String = "eng") {
        val current = _state.value
        val rect = current.selection ?: return
        val bitmap = current.bitmap ?: return
        val plan = current.plan ?: return
        if (current.ocrRunning) return
        val page = current.currentPage
        val path = current.path
        _state.update { it.copy(ocrRunning = true, error = null, message = null) }
        workJob?.cancel()
        workJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pixels = rgbaOf(bitmap)
                    val words = MlKitOcrProvider().use { provider ->
                        provider.recognize(
                            OcrImage(bitmap.width, bitmap.height, pixels),
                            language
                        )
                    }
                    val mapped = words.mapNotNull { word ->
                        val a = pageFractionToUserSpace(
                            plan,
                            (word.bounds.left / bitmap.width).toFloat(),
                            (word.bounds.top / bitmap.height).toFloat()
                        ) ?: return@mapNotNull null
                        val b = pageFractionToUserSpace(
                            plan,
                            (word.bounds.right / bitmap.width).toFloat(),
                            (word.bounds.bottom / bitmap.height).toFloat()
                        ) ?: return@mapNotNull null
                        TableWord(
                            text = word.text,
                            x0 = a[0],
                            y0 = a[1],
                            x1 = b[0],
                            y1 = b[1],
                            confidence = word.confidence
                        )
                    }
                    PdfDocument.open(FileSource(File(path))).getOrThrow().use { document ->
                        TableExtractor.extract(
                            page = document.pages()[page],
                            region = rect,
                            options = current.settings.options.engine(),
                            ocrWords = mapped,
                            decoder = AndroidImageDecoder
                        ).getOrThrow()
                    }
                }
            }
            result.onSuccess { detection ->
                _state.update {
                    if (it.path == path && it.currentPage == page) {
                        it.copy(
                            ocrRunning = false,
                            detection = detection,
                            ocrNeeded = false,
                            output = outputOf(it.copy(detection = detection)),
                            message = if (detection.table == null) {
                                "OCR found no table in the box."
                            } else {
                                "Read with OCR: the confidence is lower."
                            }
                        )
                    } else {
                        it
                    }
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        ocrRunning = false,
                        error = "OCR could not run: ${error.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    /** Keeps the current page's table and moves to the next page. */
    fun addNextPage() {
        val current = _state.value
        val detection = current.detection ?: return
        val table = detection.table ?: return
        val rect = current.selection ?: return
        val region = SnipRegion(
            page = current.currentPage,
            rect = rect,
            table = table,
            confidence = detection.confidence,
            source = detection.source
        )
        val regions = current.regions.filterNot { it.page == region.page } + region
        _state.update { it.copy(regions = regions, output = outputOf(it.copy(regions = regions))) }
        if (current.currentPage + 1 < current.pageCount) {
            renderPage(current.currentPage + 1)
        }
    }

    fun clearRegions() {
        _state.update { it.copy(regions = emptyList(), output = outputOf(it.copy(regions = emptyList()))) }
    }

    fun setFormat(format: SnipFormat) {
        val settings = _state.value.settings.copy(format = format)
        AppSettings.setSnipSettings(getApplication(), settings)
        _state.update { it.copy(settings = settings, output = outputOf(it.copy(settings = settings))) }
    }

    fun setOptions(options: SnipOptions) {
        val settings = _state.value.settings.copy(options = options)
        AppSettings.setSnipSettings(getApplication(), settings)
        _state.update { it.copy(settings = settings, output = outputOf(it.copy(settings = settings))) }
        // A merge setting changes the detected grid, so re-detect.
        if (_state.value.selection != null) detect()
    }

    /** The text Copy/Share/Save will use. */
    fun currentOutput(): String = outputOf(_state.value)

    /** The suggested file name for Save. */
    fun suggestedName(): String = snipFileName(_state.value.fileName, _state.value.settings.format)

    fun saveTo(uri: Uri) {
        val text = currentOutput()
        if (text.isEmpty()) {
            _state.update { it.copy(error = "There is nothing to save.") }
            return
        }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("The storage provider did not open a file")
                }
            }
            outcome.onSuccess {
                _state.update { it.copy(saved = true, error = null, message = "Saved") }
            }.onFailure { error ->
                _state.update { it.copy(error = "Could not save: ${error.message ?: "unknown error"}") }
            }
        }
    }

    fun markCopied() {
        _state.update { it.copy(message = "Copied") }
    }

    /** A short status line, used when an action cannot start. */
    fun showMessage(text: String) {
        _state.update { it.copy(message = text) }
    }
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun outputOf(state: SnipUiState): String {
        val tables = state.tables()
        if (tables.isEmpty()) {
            val detection = state.detection ?: return ""
            return plainTextOf(detection)
        }
        return formatSnip(tables, state.settings)
    }

    private fun rgbaOf(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = ByteArray(width * height * 4)
        for (i in pixels.indices) {
            val color = pixels[i]
            val o = i * 4
            out[o] = ((color ushr 16) and 0xFF).toByte()
            out[o + 1] = ((color ushr 8) and 0xFF).toByte()
            out[o + 2] = (color and 0xFF).toByte()
            out[o + 3] = ((color ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private companion object {
        const val PREVIEW_SCALE = 1.6
        const val PREVIEW_MAX_PIXELS = 9_000_000
    }
}
