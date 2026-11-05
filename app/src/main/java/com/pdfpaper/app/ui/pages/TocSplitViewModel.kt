package com.pdfpaper.app.ui.pages

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.pdfpaper.app.data.ExportFolder
import com.pdfpaper.app.data.SafExportFolder
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.split.TocSplit
import com.pdfpaper.engine.split.TocSplitIssue
import com.pdfpaper.engine.split.TocSplitOptions
import com.pdfpaper.engine.split.TocSplitPart
import com.pdfpaper.engine.split.TocSplitSource
import com.pdfpaper.engine.split.TocSplitWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The "Split by chapters" screen state (T66). */
data class TocSplitUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val fileName: String = "",
    val fileSizeBytes: Long = 0L,
    val pageCount: Int = 0,
    val level: Int = 1,
    val source: TocSplitSource = TocSplitSource.OUTLINE,
    val duplicateBoundary: Boolean = false,
    val parts: List<EditableTocSplitPart> = emptyList(),
    val issues: List<TocSplitIssue> = emptyList(),
    val showNoOutlineDialog: Boolean = false,
    val conflicts: List<String> = emptyList(),
    val isExporting: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val cancelled: Boolean = false,
    val summary: String? = null,
    val message: String? = null
)

/**
 * Splits an open document at its table of contents (T66). The document is
 * opened read-only for the preview and again for each export run, so the
 * screen never keeps a second copy of a 1,000-page book alive while the user
 * edits ranges; each part is handed to the folder or ZIP as soon as it is
 * built.
 */
class TocSplitViewModel(
    application: Application,
    /**
     * Resolves the folder to write into. The picked tree is a hint for the
     * default SAF folder; a test supplies its own folder and needs no tree at
     * all, which is why the tree is nullable here. The default factory refuses
     * a missing tree, so the screen always writes where the user pointed.
     */
    private val exportFolderFactory: (Uri?) -> ExportFolder = { uri ->
        SafExportFolder(application, uri ?: error("Choose a folder first"))
    }
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(TocSplitUiState())
    val state: StateFlow<TocSplitUiState> = _state.asStateFlow()

    /** The file behind the preview, re-read for each export run. */
    private var filePath: String? = null

    /** Set by Cancel; checked between parts. */
    @Volatile
    private var cancelRequested = false

    /** The tree the user picked when the conflict dialog appeared. */
    private var pendingTreeUri: Uri? = null

    // -- Loading --------------------------------------------------------------

    fun load(path: String) {
        _state.update {
            it.copy(
                loading = true,
                error = null,
                parts = emptyList(),
                issues = emptyList(),
                showNoOutlineDialog = false,
                summary = null,
                message = null,
                cancelled = false
            )
        }
        val file = File(path)
        if (!file.isFile) {
            _state.update {
                it.copy(
                    loading = false,
                    error = "There is no file at $path",
                    fileName = file.name,
                    pageCount = 0,
                    parts = emptyList()
                )
            }
            return
        }
        filePath = path
        val bytes = runCatching { file.readBytes() }.getOrElse { error ->
            _state.update {
                it.copy(
                    loading = false,
                    error = error.message ?: "This file could not be read",
                    fileName = file.name
                )
            }
            return
        }
        val plan = runCatching { planFor(bytes, TocSplitSource.OUTLINE, _state.value.level) }.getOrElse { error ->
            _state.update {
                it.copy(
                    loading = false,
                    error = error.message ?: "This document could not be read",
                    fileName = file.name,
                    fileSizeBytes = bytes.size.toLong()
                )
            }
            return
        }
        _state.update {
            it.copy(
                loading = false,
                error = null,
                fileName = file.name,
                fileSizeBytes = bytes.size.toLong(),
                pageCount = plan.pageCount,
                source = plan.plan.source,
                level = plan.plan.requestedLevel,
                parts = plan.parts,
                issues = plan.plan.issues,
                showNoOutlineDialog = offersFallback(plan.plan.issues)
            )
        }
    }

    /** Level 1 keeps top-level chapters, level 2 also their children, and so on. */
    fun setLevel(level: Int) {
        if (level < 1) return
        _state.update { it.copy(level = level) }
        replan(_state.value.source)
    }

    fun setDuplicateBoundary(duplicate: Boolean) {
        _state.update {
            it.copy(
                duplicateBoundary = duplicate,
                parts = TocSplitModel.applyDuplicateBoundary(it.parts, duplicate)
            )
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun dismissNoOutline() {
        _state.update { it.copy(showNoOutlineDialog = false) }
    }

    /** T33's heading detector, for a document without a usable outline. */
    fun retryWithHeadings() {
        _state.update { it.copy(showNoOutlineDialog = false) }
        replan(TocSplitSource.HEADINGS)
    }

    /** Plain "every N pages" parts, offered when there is no outline at all. */
    fun retryWithFixedPages(pageCount: Int) {
        val perPart = pageCount.coerceAtLeast(1)
        val total = _state.value.pageCount
        if (total <= 0) return
        val parts = ArrayList<EditableTocSplitPart>()
        var start = 0
        var index = 0
        while (start < total) {
            val end = minOf(start + perPart - 1, total - 1)
            parts += EditableTocSplitPart(
                id = "fixed_$index",
                title = if (end == start) "Page ${start + 1}" else "Pages ${start + 1}-${end + 1}",
                pages = start..end,
                fileName = ""
            )
            start = end + 1
            index++
        }
        val estimated = TocSplitModel.recomputeEstimates(
            TocSplitModel.withFileNames(parts),
            _state.value.fileSizeBytes,
            total
        )
        _state.update {
            it.copy(
                parts = estimated,
                issues = if (it.issues.isEmpty()) listOf(TocSplitIssue.NoOutline) else it.issues,
                showNoOutlineDialog = false,
                source = TocSplitSource.OUTLINE
            )
        }
    }

    // -- Preview edits --------------------------------------------------------

    fun togglePart(id: String) {
        _state.update { it.copy(parts = TocSplitModel.toggleCheck(it.parts, id)) }
    }

    fun renamePart(id: String, title: String) {
        _state.update { it.copy(parts = TocSplitModel.rename(it.parts, id, title)) }
    }

    fun mergeParts(firstId: String, secondId: String): Boolean =
        TocSplitModel.merge(_state.value.parts, firstId, secondId).fold(
            onSuccess = { merged ->
                _state.update { it.copy(parts = merged) }
                true
            },
            onFailure = { error ->
                _state.update { it.copy(message = error.message ?: "These chapters cannot be merged") }
                false
            }
        )

    fun splitPart(
        id: String,
        splitAtPage: Int,
        firstTitle: String,
        secondTitle: String
    ): Boolean = TocSplitModel.split(_state.value.parts, id, splitAtPage, firstTitle, secondTitle)
        .fold(
            onSuccess = { split ->
                _state.update { it.copy(parts = split) }
                true
            },
            onFailure = { error ->
                _state.update { it.copy(message = error.message ?: "That page cannot split this chapter") }
                false
            }
        )

    // -- Export ---------------------------------------------------------------

    /**
     * Writes the checked chapters into [treeUri]. Existing names are reported
     * back instead of written; only [overwrite] (the user's confirmation) lets
     * this replace them.
     */
    fun exportToFolder(treeUri: Uri?, overwrite: Boolean = false) {
        if (_state.value.isExporting) return
        val parts = TocSplitModel.activeParts(_state.value.parts)
        if (parts.isEmpty()) {
            _state.update { it.copy(message = "Choose at least one chapter to split") }
            return
        }
        val folder = exportFolderFactory(treeUri)
        val conflicts = TocSplitModel.findConflicts(_state.value.parts, folder.existingNames())
        if (conflicts.isNotEmpty() && !overwrite) {
            pendingTreeUri = treeUri
            _state.update { it.copy(conflicts = conflicts) }
            return
        }
        pendingTreeUri = null
        cancelRequested = false
        _state.update {
            it.copy(
                conflicts = emptyList(),
                isExporting = true,
                done = 0,
                total = parts.size,
                cancelled = false,
                summary = null,
                message = null
            )
        }
        writeInto(folder, parts) { where -> "Wrote ${parts.size} file(s) to $where" }
    }

    /** The user chose Replace in the conflict dialog. */
    fun confirmOverwrite() {
        val tree = pendingTreeUri ?: return
        _state.update { it.copy(conflicts = emptyList()) }
        exportToFolder(tree, overwrite = true)
    }

    fun dismissConflicts() {
        pendingTreeUri = null
        _state.update { it.copy(conflicts = emptyList()) }
    }

    /**
     * Stops after the part that is being written; parts already on disk stay
     * there and the summary says how many were written.
     */
    fun cancel() {
        cancelRequested = true
        _state.update { it.copy(cancelled = true) }
    }

    /**
     * Packs the checked chapters into one ZIP in the app cache and returns it,
     * or null when there is nothing to write. Used by "Share as ZIP".
     */
    fun exportToZipSync(): File? {
        val parts = TocSplitModel.activeParts(_state.value.parts)
        val path = filePath ?: return null
        if (parts.isEmpty()) return null
        val dir = cacheDirectory() ?: return null
        val zip = File(dir, "split-by-chapters-${System.currentTimeMillis()}.zip")
        val written = runCatching {
            val engineParts = engineParts(parts)
            val bytes = File(path).readBytes()
            val document = PdfDocument.open(ByteArraySource(bytes)).getOrElse { throw it }
            document.use { doc ->
                ZipOutputStream(zip.outputStream().buffered()).use { out ->
                    TocSplitWriter.write(
                        document = doc,
                        parts = engineParts,
                        sink = { output ->
                            out.putNextEntry(ZipEntry(output.part.fileName))
                            out.write(output.bytes)
                            out.closeEntry()
                        },
                        isCancelled = { cancelRequested }
                    )
                }
            }
        }
        if (written.isFailure) {
            runCatching { zip.delete() }
            _state.update { it.copy(message = written.exceptionOrNull()?.message ?: "The ZIP could not be written") }
            return null
        }
        _state.update { it.copy(summary = "Packed ${parts.size} file(s) into ${zip.name}") }
        return zip
    }

    private fun writeInto(folder: ExportFolder, parts: List<EditableTocSplitPart>, summary: (String) -> String) {
        val path = filePath
        var written = 0
        val failure = runCatching {
            if (path == null) error("The document is no longer open")
            val engineParts = engineParts(parts)
            val bytes = File(path).readBytes()
            val document = PdfDocument.open(ByteArraySource(bytes)).getOrElse { throw it }
            document.use { doc ->
                TocSplitWriter.write(
                    document = doc,
                    parts = engineParts,
                    sink = { output ->
                        folder.write(output.part.fileName, "application/pdf", output.bytes)
                            ?: error("Could not write ${output.part.fileName}")
                        written++
                    },
                    isCancelled = { cancelRequested },
                    onProgress = { done, total -> _state.update { it.copy(done = done, total = total) } }
                )
            }
        }.exceptionOrNull()
        val stopped = failure != null || written < parts.size
        _state.update {
            it.copy(
                isExporting = false,
                done = written,
                total = parts.size,
                cancelled = cancelRequested,
                summary = if (!stopped) summary(folder.displayPath) else null,
                message = when {
                    failure != null -> failure.message ?: "The split could not be finished"
                    stopped && cancelRequested -> "Stopped after $written of ${parts.size} file(s)"
                    stopped -> "Stopped after $written of ${parts.size} file(s)"
                    else -> null
                }
            )
        }
    }

    // -- Internals ------------------------------------------------------------

    private class Planned(
        val plan: com.pdfpaper.engine.split.TocSplitPlan,
        val parts: List<EditableTocSplitPart>,
        val pageCount: Int
    )

    private fun planFor(bytes: ByteArray, source: TocSplitSource, level: Int): Planned {
        val document = PdfDocument.open(ByteArraySource(bytes)).getOrElse { throw it }
        return document.use { doc ->
            val options = TocSplitOptions(
                level = level,
                duplicateBoundaryPage = _state.value.duplicateBoundary,
                source = source
            )
            val plan = if (source == TocSplitSource.HEADINGS) {
                TocSplit.planHeadings(doc, options)
            } else {
                TocSplit.planOutline(doc, options)
            }
            val pageCount = doc.pages().size
            val editable = plan.parts.map {
                EditableTocSplitPart(it.id, it.title, it.pages, it.fileName, it.checked)
            }
            val estimated = TocSplitModel.recomputeEstimates(editable, bytes.size.toLong(), pageCount)
            Planned(plan, estimated, pageCount)
        }
    }

    private fun replan(source: TocSplitSource) {
        val path = filePath ?: return
        val bytes = runCatching { File(path).readBytes() }.getOrNull() ?: return
        val planned = runCatching { planFor(bytes, source, _state.value.level) }.getOrElse { error ->
            _state.update { it.copy(error = error.message ?: "This document could not be read") }
            return
        }
        _state.update {
            it.copy(
                source = planned.plan.source,
                level = planned.plan.requestedLevel,
                pageCount = planned.pageCount,
                parts = planned.parts,
                issues = planned.plan.issues,
                showNoOutlineDialog = offersFallback(planned.plan.issues),
                summary = null
            )
        }
    }

    private fun offersFallback(issues: List<TocSplitIssue>): Boolean =
        issues.any { it is TocSplitIssue.NoOutline || it is TocSplitIssue.NoUsableEntries }

    private fun engineParts(parts: List<EditableTocSplitPart>): List<TocSplitPart> =
        parts.map { TocSplitPart(it.id, it.title, it.pages, it.fileName, it.checked) }

    private fun cacheDirectory(): File? {
        val app = getApplication<Application>()
        val cache = runCatching { app.cacheDir }.getOrNull()
        val dir = cache ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        return if (dir.exists() || dir.mkdirs()) dir else null
    }
}
