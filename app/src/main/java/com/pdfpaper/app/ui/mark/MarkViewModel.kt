package com.pdfpaper.app.ui.mark

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.render.PageBitmapRenderer
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.mark.PdfMarks
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The document a mark screen works on. */
data class MarkDocumentState(
    val path: String = "",
    val fileName: String = "",
    val pageCount: Int = 0,
    /** The rendered first page, used by the live preview. */
    val preview: Bitmap? = null,
    /** The page box the marks are placed on, in points. */
    val box: MarkBox = MarkBox(),
    val loading: Boolean = false
)

/** What a mark screen is doing right now. */
data class MarkRunState(
    /** True while the engine is writing the marked document. */
    val working: Boolean = false,
    /** True once the marked document has been written to the chosen place. */
    val saved: Boolean = false,
    /** True when there are bytes waiting to be saved. */
    val hasResult: Boolean = false,
    /** The copy of the marked document in the app's cache, once it is saved. */
    val savedPath: String? = null,
    val error: String? = null
)

/** The state every mark screen shows. */
interface MarkScreenState {
    val document: MarkDocumentState
    val run: MarkRunState
}

/**
 * The shared part of the three mark screens (T44).
 *
 * It opens the document, renders the preview of its first page, runs the
 * engine on the background thread, keeps the bytes that came out and writes
 * them to the place the user picks. The three screens add their own options
 * on top of this.
 */
abstract class MarkViewModel<T : MarkScreenState>(application: Application) : AndroidViewModel(application) {

    /** The state of the screen. */
    protected abstract val flow: MutableStateFlow<T>

    /**
     * The state of the screen, for the screen to read.
     *
     * The wrapper is made on first use, not while this class is constructed:
     * a subclass sets [flow] after the base class is built, so reading it
     * during construction would see nothing.
     */
    private val readOnlyFlow: StateFlow<T> by lazy { flow.asStateFlow() }

    /** The state of the screen, for the screen to read. */
    val state: StateFlow<T> get() = readOnlyFlow

    /** The bytes the last run produced; null when there is nothing to save. */
    private var resultBytes: ByteArray? = null

    /** Puts a new document into the state. */
    protected abstract fun withDocument(state: T, document: MarkDocumentState): T

    /** Puts a new run state into the state. */
    protected abstract fun withRun(state: T, run: MarkRunState): T

    /** Stores the plain options of the screen, so the next run starts here. */
    protected abstract fun persist(state: T)

    /**
     * Changes the options and remembers them. The result of the last run is
     * dropped: it belongs to the old options, so the user has to apply them
     * again before saving.
     */
    protected fun update(block: (T) -> T) {
        flow.update(block)
        if (flow.value.run.hasResult) {
            resultBytes = null
            setRun(flow.value.run.copy(hasResult = false, saved = false))
        }
        persist(flow.value)
    }

    /** Puts a new document into the state. */
    protected fun setDocument(document: MarkDocumentState) {
        flow.update { withDocument(it, document) }
    }

    /** Puts a new run state into the state. */
    protected fun setRun(run: MarkRunState) {
        flow.update { withRun(it, run) }
    }

    /**
     * Opens the document at [path] and renders the preview of its first page.
     * Everything happens off the main thread; the screen shows the preview
     * as soon as it is ready.
     */
    fun load(path: String) {
        if (path.isBlank()) return
        resultBytes = null
        val file = File(path)
        setDocument(MarkDocumentState(path = path, fileName = file.name, loading = true))
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { openPreview(path) }
            outcome.fold(
                onSuccess = { preview ->
                    setDocument(
                        MarkDocumentState(
                            path = path,
                            fileName = file.name,
                            pageCount = preview.pageCount,
                            preview = preview.bitmap,
                            box = preview.box
                        )
                    )
                },
                onFailure = { error ->
                    setDocument(MarkDocumentState(path = path, fileName = file.name))
                    setRun(MarkRunState(error = error.message ?: "Could not open that PDF."))
                }
            )
        }
    }

    /**
     * Runs the engine on the document and keeps the bytes it produced. The
     * engine call happens on the background thread; a failure is reported in
     * the state instead of being thrown.
     */
    protected fun runMark(block: (PdfDocument) -> Result<ByteArray>) {
        val path = flow.value.document.path
        if (path.isBlank() || flow.value.run.working) return
        viewModelScope.launch {
            setRun(flow.value.run.copy(working = true, saved = false, hasResult = false, error = null))
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val document = PdfDocument.open(FileSource(File(path))).getOrThrow()
                    try {
                        block(document).getOrThrow()
                    } finally {
                        document.close()
                    }
                }
            }
            outcome.fold(
                onSuccess = { bytes ->
                    resultBytes = bytes
                    setRun(flow.value.run.copy(working = false, hasResult = true))
                },
                onFailure = { error ->
                    setRun(
                        flow.value.run.copy(
                            working = false,
                            error = error.message ?: "Could not add the marks to the document."
                        )
                    )
                }
            )
        }
    }

    /** Writes the bytes of the last run to the place the user picked. */
    fun saveTo(uri: Uri) {
        val bytes = resultBytes ?: return
        val name = flow.value.document.fileName
        viewModelScope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("Could not open the destination for writing.")
                    // A copy in the cache, so the marked document can be opened
                    // in the viewer right away.
                    val directory = File(getApplication<Application>().cacheDir, "marked")
                        .apply { mkdirs() }
                    val file = File(directory, cacheName(name))
                    file.writeBytes(bytes)
                    file.absolutePath
                }
            }
            written.fold(
                onSuccess = { path ->
                    setRun(flow.value.run.copy(saved = true, savedPath = path, error = null))
                },
                onFailure = { error ->
                    setRun(
                        flow.value.run.copy(
                            saved = false,
                            error = error.message ?: "Could not save the PDF."
                        )
                    )
                }
            )
        }
    }

    /**
     * Takes the watermarks, headers, footers and Bates numbers PDFPaper added
     * out of the document again. The page content is left as it was.
     */
    fun removeAdded() {
        runMark { document -> PdfMarks.removeAdded(document) }
    }

    /** Clears the finished result so the screen can be used again. */
    fun dismissResult() {
        resultBytes = null
        setRun(flow.value.run.copy(hasResult = false, saved = false))
    }

    /** The rendered first page of the document. */
    private fun openPreview(path: String): Result<PagePreview> = runCatching {
        val document = PdfDocument.open(FileSource(File(path))).getOrThrow()
        try {
            val pages = document.pages()
            val page = pages.firstOrNull() ?: error("This document has no pages.")
            val box = PageGeometry.boxOf(page)
            val scale = PageGeometry.scaleForPixelWidth(box, PREVIEW_PIXEL_WIDTH)
            val rendered = PageBitmapRenderer.render(page, scale, maxPixels = PREVIEW_MAX_PIXELS)
            PagePreview(
                bitmap = rendered.bitmap,
                box = MarkBox(box.left, box.bottom, box.right, box.top, box.rotate),
                pageCount = pages.size
            )
        } finally {
            document.close()
        }
    }

    /** The rendered first page and the box the marks go on. */
    private data class PagePreview(
        val bitmap: Bitmap,
        val box: MarkBox,
        val pageCount: Int
    )

    /** The name of the cache copy: "report.pdf" becomes "report-marked.pdf". */
    private fun cacheName(name: String): String {
        val base = name.substringBeforeLast('.', name).trim().ifEmpty { "document" }
        val safe = base.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }
            .joinToString("")
            .take(80)
        return "$safe-marked.pdf"
    }

    private companion object {
        /** The width of the preview bitmap, in pixels. */
        const val PREVIEW_PIXEL_WIDTH = 720

        /** The pixel budget of the preview bitmap. */
        const val PREVIEW_MAX_PIXELS = 4_000_000
    }
}
