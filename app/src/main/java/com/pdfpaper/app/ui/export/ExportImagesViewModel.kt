package com.pdfpaper.app.ui.export

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.data.CacheExportFolder
import com.pdfpaper.app.data.ExportFolder
import com.pdfpaper.app.data.SafExportFolder
import com.pdfpaper.app.render.AndroidImageEncoder
import com.pdfpaper.app.render.AndroidPageRasterizer
import com.pdfpaper.engine.export.ExportedPage
import com.pdfpaper.engine.export.ImageExtractOptions
import com.pdfpaper.engine.export.ImageFormat
import com.pdfpaper.engine.export.PageExportOptions
import com.pdfpaper.engine.export.PageRange
import com.pdfpaper.engine.export.PdfImageExtractor
import com.pdfpaper.engine.export.PdfPageExport
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What the screen exports: the pages as images, or the pictures inside. */
enum class ExportMode(val label: String) {
    PAGES("Page images"),
    EMBEDDED("Embedded images")
}

/** Everything the export screen shows. */
data class ExportImagesUiState(
    val path: String = "",
    val fileName: String = "",
    val fileSizeBytes: Long = 0,
    val loading: Boolean = false,
    val pageCount: Int = 0,
    val imageCount: Int = 0,
    val mode: ExportMode = ExportMode.PAGES,
    val rangeText: String = "all",
    val dpi: Int = 150,
    val dpiText: String = "150",
    val format: ImageFormat = ImageFormat.PNG,
    val quality: Int = 90,
    val minSize: Int = 0,
    val keepDuplicates: Boolean = false,
    val exporting: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val cancelled: Boolean = false,
    val error: String? = null,
    val summary: String? = null,
    val conflicts: List<String> = emptyList(),
    val failedWrites: Int = 0
) {
    /** Text under the progress bar: "12 of 30 pages". */
    val progressLabel: String
        get() = when (mode) {
            ExportMode.PAGES -> "$done of $total page(s)"
            ExportMode.EMBEDDED -> if (total > 0) "$done of $total image(s)" else "Looking for images…"
        }
}

/**
 * Drives "Export images": renders pages at the chosen DPI through the same
 * renderer the viewer uses, or pulls the embedded pictures out through the
 * engine, and writes the files to a folder the user picked with the Storage
 * Access Framework.
 *
 * The work runs on a cancellable job one file at a time; the state reports
 * progress. Existing files are never overwritten without confirmation: the
 * view model compares the planned names with the folder's contents first and
 * exposes them as [ExportImagesUiState.conflicts] until the user agrees.
 */
class ExportImagesViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ExportImagesUiState())
    val state: StateFlow<ExportImagesUiState> = _state.asStateFlow()

    private var exportJob: Job? = null
    private var cancelRequested = false
    private var pendingFolder: ExportFolder? = null
    private var pendingShare = false

    /** Opens the document and counts the pages and the embedded images. */
    fun load(path: String) {
        if (path.isBlank()) return
        val file = File(path)
        _state.update {
            it.copy(
                path = path,
                fileName = file.name,
                fileSizeBytes = if (file.exists()) file.length() else 0,
                loading = true,
                error = null,
                summary = null,
                conflicts = emptyList(),
                done = 0
            )
        }
        viewModelScope.launch {
            val counts = withContext(Dispatchers.IO) {
                runCatching {
                    val document = PdfDocument.open(FileSource(file)).getOrThrow()
                    try {
                        document.pages().size to PdfImageExtractor.list(document).size
                    } finally {
                        document.close()
                    }
                }
            }
            if (!isActive) return@launch
            counts.fold(
                onSuccess = { (pages, images) ->
                    _state.update {
                        it.copy(loading = false, pageCount = pages, imageCount = images)
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "Could not open that PDF."
                        )
                    }
                }
            )
        }
    }

    fun setMode(mode: ExportMode) = _state.update { it.copy(mode = mode, error = null) }

    fun setRangeText(text: String) = _state.update { it.copy(rangeText = text, error = null) }

    fun setDpi(dpi: Int) = _state.update {
        it.copy(dpi = dpi, dpiText = dpi.toString(), error = null)
    }

    fun setDpiText(text: String) {
        val parsed = text.filter { it.isDigit() }.take(4).toIntOrNull()
        _state.update {
            it.copy(dpiText = text, dpi = parsed ?: it.dpi, error = null)
        }
    }

    fun setFormat(format: ImageFormat) = _state.update { it.copy(format = format) }

    fun setQuality(quality: Int) = _state.update {
        it.copy(quality = quality.coerceIn(1, 100))
    }

    fun setMinSize(text: String) {
        val parsed = text.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0
        _state.update { it.copy(minSize = parsed) }
    }

    fun setKeepDuplicates(keep: Boolean) = _state.update { it.copy(keepDuplicates = keep) }

    /** Starts an export into the tree the user picked (ACTION_OPEN_DOCUMENT_TREE). */
    fun exportToTree(treeUri: Uri) {
        val folder = SafExportFolder(getApplication(), treeUri)
        pendingShare = false
        exportTo(folder)
    }

    /** Runs the same export into the app cache and hands a ZIP to another app. */
    fun share() {
        val directory = File(
            getApplication<Application>().cacheDir,
            "exports/${System.currentTimeMillis()}"
        )
        pendingShare = true
        exportTo(CacheExportFolder(directory))
    }

    /** Confirms overwriting the files the state listed in [ExportImagesUiState.conflicts]. */
    fun confirmOverwrite() {
        val folder = pendingFolder ?: return
        _state.update { it.copy(conflicts = emptyList()) }
        run(folder, overwrite = true)
    }

    /** Dismisses the overwrite question and leaves the folder untouched. */
    fun dismissConflicts() {
        pendingFolder = null
        pendingShare = false
        _state.update { it.copy(conflicts = emptyList()) }
    }

    fun cancel() {
        if (!_state.value.exporting) return
        cancelRequested = true
        _state.update { it.copy(cancelled = true) }
        exportJob?.cancel()
    }

    fun dismissSummary() = _state.update { it.copy(summary = null, error = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    // ------------------------------------------------------------- internals

    private fun exportTo(folder: ExportFolder) {
        val snapshot = _state.value
        if (snapshot.exporting) return
        if (snapshot.path.isBlank()) {
            _state.update { it.copy(error = "Open a PDF first.") }
            return
        }
        val invalid = validationError(snapshot)
        if (invalid != null) {
            _state.update { it.copy(error = invalid) }
            return
        }
        pendingFolder = folder
        val conflicts = conflictsFor(folder, snapshot)
        if (conflicts.isNotEmpty()) {
            _state.update {
                it.copy(conflicts = conflicts, error = null, summary = null)
            }
            return
        }
        run(folder, overwrite = false)
    }

    private fun validationError(snapshot: ExportImagesUiState): String? {
        if (snapshot.mode == ExportMode.PAGES) {
            if (snapshot.dpi !in 1..1200) return "Choose a DPI between 1 and 1200."
            val range = PageRange.parse(snapshot.rangeText)
                .getOrElse { return it.message ?: "That page range is not valid." }
            if (range is PageRange.Custom && range.pages.size > 50_000) {
                return "That range is too large."
            }
        }
        return null
    }

    /** Exact names for page export; a prefix check for extracted images. */
    private fun conflictsFor(folder: ExportFolder, snapshot: ExportImagesUiState): List<String> {
        val existing = folder.existingNames()
        if (existing.isEmpty()) return emptyList()
        val document = PdfPageExport.sanitizeName(snapshot.fileName)
        return when (snapshot.mode) {
            ExportMode.PAGES -> {
                val range = PageRange.parse(snapshot.rangeText).getOrNull() ?: return emptyList()
                (1..snapshot.pageCount)
                    .filter { range.includes(it - 1) }
                    .map { PdfPageExport.pageFileName(document, it, snapshot.format) }
                    .filter { it in existing }
            }
            ExportMode.EMBEDDED -> existing.filter { it.startsWith("$document-img") }.sorted()
        }
    }

    private fun run(folder: ExportFolder, overwrite: Boolean) {
        val snapshot = _state.value
        exportJob?.cancel()
        cancelRequested = false
        exportJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    exporting = true,
                    cancelled = false,
                    error = null,
                    summary = null,
                    done = 0,
                    failedWrites = 0,
                    total = selectedPageCount(it)
                )
            }
            val written = ArrayList<String>()
            var failed = 0
            try {
                val outcome = withContext(Dispatchers.Default) {
                    val document = PdfDocument.open(FileSource(File(snapshot.path))).getOrThrow()
                    try {
                        when (snapshot.mode) {
                            ExportMode.PAGES -> exportPages(document, snapshot) { name, bytes, mime ->
                                if (folder.write(name, mime, bytes) == null) failed++ else written += name
                                _state.update { state -> state.copy(done = state.done + 1) }
                            }
                            ExportMode.EMBEDDED -> exportEmbedded(document, snapshot) { name, bytes, mime ->
                                if (folder.write(name, mime, bytes) == null) failed++ else written += name
                                _state.update { state -> state.copy(done = state.done + 1) }
                            }
                        }
                    } finally {
                        document.close()
                    }
                }
                if (!isActive) return@launch
                outputSummary(outcome, written, failed, snapshot, overwrite)
            } catch (cancelled: CancellationException) {
                _state.update {
                    it.copy(
                        exporting = false,
                        cancelled = true,
                        summary = "Stopped after ${it.done} file(s)."
                    )
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(exporting = false, error = error.message ?: "The export failed.")
                }
            }
        }
    }

    private fun outputSummary(
        outcome: String,
        written: List<String>,
        failed: Int,
        snapshot: ExportImagesUiState,
        overwrite: Boolean
    ) {
        if (pendingShare) {
            pendingShare = false
            if (outcome == CANCELLED) {
                _state.update { it.copy(exporting = false, cancelled = true, summary = null) }
                return
            }
            if (written.isEmpty()) {
                _state.update { it.copy(exporting = false, summary = "Nothing to share.") }
                return
            }
            val zip = zipAndShare(written)
            _state.update {
                it.copy(
                    exporting = false,
                    failedWrites = failed,
                    summary = if (zip == null) {
                        "Could not prepare the files for sharing."
                    } else {
                        "Sharing ${written.size} file(s) as ${zip.name}."
                    }
                )
            }
            return
        }
        _state.update {
            it.copy(
                exporting = false,
                cancelled = outcome == CANCELLED,
                failedWrites = failed,
                summary = if (outcome == CANCELLED) {
                    "Stopped after ${written.size} file(s)."
                } else {
                    "Wrote ${written.size} file(s) to ${pendingFolder?.displayPath ?: "the chosen folder"}" +
                        if (failed > 0) " ($failed could not be written)." else "."
                }
            )
        }
        if (!overwrite) pendingFolder = null
    }

    /** Cached files → one ZIP in the cache → ACTION_SEND through the FileProvider. */
    private fun zipAndShare(files: List<String>): File? {
        val context = getApplication<Application>()
        val first = File(files.first())
        val zip = File(first.parentFile, "${PdfPageExport.sanitizeName(_state.value.fileName)}-images.zip")
        try {
            ZipOutputStream(zip.outputStream()).use { out ->
                for (path in files) {
                    val file = File(path)
                    if (!file.isFile) continue
                    out.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        } catch (error: Exception) {
            return null
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        }.getOrNull() ?: return null
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share images").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
        return zip
    }

    private fun exportPages(
        document: PdfDocument,
        snapshot: ExportImagesUiState,
        write: (String, ByteArray, String) -> Unit
    ): String {
        val range = PageRange.parse(snapshot.rangeText)
            .getOrElse { throw IllegalArgumentException(it.message ?: "bad page range") }
        val options = PageExportOptions(
            documentName = snapshot.fileName,
            dpi = snapshot.dpi,
            format = snapshot.format,
            pages = range,
            quality = snapshot.quality,
            maxPixels = APP_MAX_PIXELS
        )
        val summary = PdfPageExport.export(
            document = document,
            options = options,
            rasterizer = AndroidPageRasterizer,
            encoder = AndroidImageEncoder,
            sink = { page: ExportedPage ->
                write(page.fileName, page.bytes, page.format.mimeType)
            },
            isCancelled = { cancelRequested }
        ).getOrElse { throw IllegalStateException(it.message ?: "export failed") }
        return if (summary.cancelled) CANCELLED else "OK"
    }

    private fun exportEmbedded(
        document: PdfDocument,
        snapshot: ExportImagesUiState,
        write: (String, ByteArray, String) -> Unit
    ): String {
        if (cancelRequested) return CANCELLED
        val result = PdfImageExtractor.extract(
            document = document,
            options = ImageExtractOptions(
                documentName = snapshot.fileName,
                minSize = snapshot.minSize,
                skipDuplicates = !snapshot.keepDuplicates
            )
        ).getOrElse { throw IllegalStateException(it.message ?: "extraction failed") }
        _state.update { it.copy(total = result.images.count { image -> image.bytes != null }) }
        for (image in result.images) {
            if (cancelRequested) return CANCELLED
            val bytes = image.bytes ?: continue
            val name = image.fileName ?: continue
            val mime = when (image.format) {
                "jpg" -> "image/jpeg"
                "jp2" -> "image/jp2"
                else -> "image/png"
            }
            write(name, bytes, mime)
        }
        return "OK"
    }

    private fun selectedPageCount(snapshot: ExportImagesUiState): Int {
        if (snapshot.mode == ExportMode.EMBEDDED) return snapshot.imageCount
        val range = PageRange.parse(snapshot.rangeText).getOrNull() ?: return snapshot.pageCount
        return (0 until snapshot.pageCount).count { range.includes(it) }
    }

    private companion object {
        /**
         * App-side cap: a 300 dpi A4 page is 8.7 MP; 24 MP keeps one page at
         * ~96 MB of bitmap plus buffer, which fits a phone. The engine's own
         * default is 64 MP for hosts with more headroom.
         */
        const val APP_MAX_PIXELS = 24_000_000L

        const val CANCELLED = "cancelled"
    }
}

