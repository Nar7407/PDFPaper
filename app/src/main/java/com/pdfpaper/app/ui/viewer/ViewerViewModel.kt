package com.pdfpaper.app.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.render.PageBitmapRenderer
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.util.LruCache
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
import kotlin.math.abs
import kotlin.math.max

data class ViewerUiState(
    val isLoading: Boolean = true,
    val displayName: String? = null,
    val pageCount: Int = 0,
    val error: String? = null
)

/** One cached page bitmap plus the pixel width it was rendered at. */
private class CachedPage(val bitmap: Bitmap, val pixelWidth: Int)

/**
 * Viewer state for one document (T9).
 *
 * Rendering happens on a single-threaded background dispatcher: one page is
 * rasterised at a time, which bounds peak memory and serialises access to the
 * document's random-access source. Bitmaps of off-screen pages stay in a
 * byte-bounded LRU cache; pending renders for pages that scrolled away are
 * cancelled. Zoom is owned by the UI (it changes the requested pixel width),
 * so a zoom change simply re-requests the visible pages.
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

    /** Non-fatal problem worth showing (render failure, warning summary). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var document: PdfDocument? = null
    private var pages: List<PdfPage> = emptyList()

    private val cache = LruCache<Int, CachedPage>(bitmapBudgetBytes()) { it.bitmap.allocationByteCount.toLong() }

    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val jobs = HashMap<Int, Job>()
    private val requestedWidths = HashMap<Int, Int>()

    /** Embedded fonts are materialised here so android.graphics can load them. */
    private val fontDir: File by lazy { File(getApplication<Application>().cacheDir, "embedded-fonts") }

    fun load(path: String) {
        if (path.isBlank()) {
            _state.value = ViewerUiState(isLoading = false, error = "No file selected")
            return
        }
        _state.value = ViewerUiState(isLoading = true)
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    val doc = PdfDocument.open(FileSource(File(path))).getOrThrow()
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
                    _state.value = ViewerUiState(
                        isLoading = false,
                        displayName = File(path).name,
                        error = error.message ?: "Cannot open this document"
                    )
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
            if (resolutionMatches(cached.pixelWidth, width)) {
                publish(index, cached.bitmap)
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
                cache.put(index, CachedPage(rendered.bitmap, rendered.plan.pixelWidth))
                publish(index, rendered.bitmap)
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
    }

    fun dismissMessage() {
        _message.value = null
    }

    override fun onCleared() {
        renderScope.cancel()
        jobs.clear()
        _bitmaps.value = emptyMap()
        cache.clear()
        document?.close()
        document = null
        pages = emptyList()
        super.onCleared()
    }

    private fun publish(index: Int, bitmap: Bitmap) {
        _bitmaps.update { it + (index to bitmap) }
    }

    /** A cached bitmap is reusable when its width is within ~10% of the target. */
    private fun resolutionMatches(cachedWidth: Int, targetWidth: Int): Boolean =
        abs(cachedWidth - targetWidth) <= max(16, targetWidth / 10)

    private companion object {
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
