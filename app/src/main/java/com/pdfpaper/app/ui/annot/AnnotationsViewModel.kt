package com.pdfpaper.app.ui.annot

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pdfpaper.app.annot.AnnotationDateFilter
import com.pdfpaper.app.annot.AnnotationDocument
import com.pdfpaper.app.annot.AnnotationEditor
import com.pdfpaper.app.annot.AnnotationExport
import com.pdfpaper.app.annot.AnnotationExportFormat
import com.pdfpaper.app.annot.AnnotationExportOptions
import com.pdfpaper.app.annot.AnnotationFilter
import com.pdfpaper.app.annot.AnnotationGroupUi
import com.pdfpaper.app.annot.AnnotationGrouping
import com.pdfpaper.app.annot.AnnotationItem
import com.pdfpaper.app.annot.AnnotationKind
import com.pdfpaper.app.annot.AnnotationRepository
import com.pdfpaper.app.annot.AnnotationStats
import com.pdfpaper.app.annot.groupAnnotations
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.index.IndexWork
import com.pdfpaper.app.index.IndexWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the hub is doing in the background (T56). */
data class AnnotationIndexing(
    val running: Boolean = false,
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0
) {
    val label: String
        get() = when {
            !running -> ""
            total > 0 -> "Indexing ${done.coerceAtMost(total)} of $total files"
            phase.isNotBlank() -> phase
            else -> "Indexing"
        }
}

/** Where an export takes its rows from. */
enum class AnnotationExportScope(val label: String) {
    SELECTED("Selected"),
    DOCUMENT("This document"),
    EVERYTHING("Everything")
}

/** A rendered export, ready for a share sheet or a SAF create. */
data class AnnotationExportPayload(
    val fileName: String,
    val text: String,
    val format: AnnotationExportFormat
)

data class AnnotationsUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val items: List<AnnotationItem> = emptyList(),
    val filtered: List<AnnotationItem> = emptyList(),
    val documents: List<AnnotationDocument> = emptyList(),
    val stats: AnnotationStats = AnnotationStats(0, 0, 0, emptyMap()),
    val filter: AnnotationFilter = AnnotationFilter(),
    val grouping: AnnotationGrouping = AnnotationGrouping.DOCUMENT,
    val groups: List<AnnotationGroupUi> = emptyList(),
    val selecting: Boolean = false,
    val selection: Set<Long> = emptySet(),
    val indexing: AnnotationIndexing = AnnotationIndexing(),
    val message: String? = null,
    val error: String? = null
) {
    val isEmpty: Boolean get() = !loading && filtered.isEmpty()
}

/**
 * T56: the Annotations hub. Reads only the local Room index (T50's database,
 * extended with annotations), so opening this tab never touches a PDF until the
 * user edits or deletes something.
 */
class AnnotationsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AnnotationRepository.get(application)
    private val editor = AnnotationEditor(application)

    private val _state = MutableStateFlow(AnnotationsUiState())
    val state: StateFlow<AnnotationsUiState> = _state.asStateFlow()

    private var queryJob: Job? = null
    private var wasRunning = false
    private var lastFinishedWorkId: java.util.UUID? = null

    init {
        _state.update {
            it.copy(
                grouping = runCatching {
                    AnnotationGrouping.valueOf(AppSettings.annotationGrouping(getApplication()))
                }.getOrDefault(AnnotationGrouping.DOCUMENT)
            )
        }
        reload()
        viewModelScope.launch {
            IndexWork.observe(getApplication()).collect { infos -> onWorkInfo(infos) }
        }
    }

    fun reload() {
        viewModelScope.launch {
            runCatching {
                val filter = _state.value.filter
                val items = repository.list(filter)
                val documents = repository.documents()
                val stats = repository.counts()
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        items = items,
                        filtered = items,
                        documents = documents,
                        stats = stats,
                        groups = groupAnnotations(items, it.grouping)
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(loading = false, error = error.message ?: "The index could not be read")
                }
            }
        }
    }

    // ---------------------------------------------------------------- filters

    fun onQueryChange(query: String) {
        _state.update { it.copy(filter = it.filter.copy(query = query)) }
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(250)
            reload()
        }
    }

    fun toggleKind(kind: AnnotationKind) {
        _state.update { current ->
            val kinds = current.filter.kinds.toMutableSet()
            if (!kinds.add(kind)) kinds.remove(kind)
            current.copy(filter = current.filter.copy(kinds = kinds))
        }
        reload()
    }

    fun setColor(color: String?) {
        _state.update { it.copy(filter = it.filter.copy(color = color)) }
        reload()
    }

    fun setDocument(documentId: Long?) {
        _state.update { it.copy(filter = it.filter.copy(documentId = documentId)) }
        reload()
    }

    fun setDate(date: AnnotationDateFilter) {
        _state.update { it.copy(filter = it.filter.copy(date = date)) }
        reload()
    }

    fun clearFilters() {
        _state.update {
            it.copy(filter = AnnotationFilter(query = it.filter.query))
        }
        reload()
    }

    fun setGrouping(grouping: AnnotationGrouping) {
        AppSettings.setAnnotationGrouping(getApplication(), grouping.name)
        _state.update { it.copy(grouping = grouping, groups = groupAnnotations(it.filtered, grouping)) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }

    // --------------------------------------------------------------- selection

    fun startSelecting() = _state.update { it.copy(selecting = true) }

    fun cancelSelecting() = _state.update { it.copy(selecting = false, selection = emptySet()) }

    fun toggleSelection(id: Long) = _state.update {
        val selection = it.selection.toMutableSet()
        if (!selection.add(id)) selection.remove(id)
        it.copy(selection = selection)
    }

    fun selectAllVisible() = _state.update {
        it.copy(selection = it.filtered.map { item -> item.id }.toSet())
    }

    fun selectDocument(documentId: Long) = _state.update {
        it.copy(
            selection = it.filtered.filter { item -> item.documentId == documentId }
                .map { item -> item.id }
                .toSet()
        )
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    // ----------------------------------------------------------------- actions

    fun saveNote(item: AnnotationItem, text: String) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = if (item.kind.isBookmark) {
                editor.update(item, label = text.trim())
            } else {
                editor.update(item, noteText = text.trim())
            }
            finish(result)
        }
    }

    fun setColor(item: AnnotationItem, color: String) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch { finish(editor.update(item, color = color)) }
    }

    fun setTag(item: AnnotationItem, tag: String) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch { finish(editor.update(item, tag = tag.trim())) }
    }

    fun delete(item: AnnotationItem) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch { finish(editor.delete(item)) }
    }

    fun deleteSelection() {
        val ids = _state.value.selection
        if (ids.isEmpty()) return
        val items = _state.value.items.filter { it.id in ids }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            var deleted = 0
            var failure: String? = null
            for (item in items) {
                editor.delete(item).fold(
                    onSuccess = { deleted++ },
                    onFailure = { failure = it.message }
                )
            }
            _state.update { it.copy(busy = false, selecting = false, selection = emptySet()) }
            reload()
            _state.update {
                it.copy(
                    message = if (failure != null) {
                        "$deleted deleted; ${items.size - deleted} could not be changed: $failure"
                    } else {
                        "$deleted deleted"
                    }
                )
            }
        }
    }

    /** The text the Copy action puts on the clipboard. */
    fun copyText(item: AnnotationItem): String {
        val text = item.text.trim()
        return if (text.isEmpty()) {
            "${item.kind.label} · ${item.displayName} · page ${item.pageIndex + 1}"
        } else {
            text
        }
    }

    // ------------------------------------------------------------------ export

    fun buildExport(
        scope: AnnotationExportScope,
        options: AnnotationExportOptions,
        documentId: Long? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): AnnotationExportPayload? {
        val current = _state.value
        val items = when (scope) {
            AnnotationExportScope.SELECTED -> current.items.filter { it.id in current.selection }
            AnnotationExportScope.DOCUMENT -> {
                val id = documentId ?: current.filter.documentId
                if (id == null) current.items else current.items.filter { it.documentId == id }
            }
            AnnotationExportScope.EVERYTHING -> current.items
        }
        if (items.isEmpty()) return null
        return AnnotationExportPayload(
            fileName = AnnotationExport.fileName(items, options.format, nowMillis),
            text = AnnotationExport.render(items, options, nowMillis),
            format = options.format
        )
    }

    /** Writes an export into the app cache and offers it through the share sheet. */
    fun shareExport(payload: AnnotationExportPayload) {
        viewModelScope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) { cacheExport(payload) }
                val context = getApplication<Application>()
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = if (payload.format == AnnotationExportFormat.MARKDOWN) {
                        "text/markdown"
                    } else {
                        "text/plain"
                    }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, payload.fileName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "Share annotations").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }.onFailure { error ->
                _state.update {
                    it.copy(error = error.message ?: "The export could not be shared")
                }
            }
        }
    }

    /** Writes an export to the file the user picked through SAF. */
    fun saveExport(uri: Uri, payload: AnnotationExportPayload) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(payload.text.toByteArray(Charsets.UTF_8))
                    } ?: error("The chosen file could not be opened for writing")
                }
            }.fold(
                onSuccess = {
                    _state.update {
                        it.copy(busy = false, message = "Saved ${payload.fileName}")
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(busy = false, error = error.message ?: "The export could not be saved")
                    }
                }
            )
        }
    }

    private fun cacheExport(payload: AnnotationExportPayload): File {
        val directory = File(getApplication<Application>().cacheDir, "annotations")
        directory.mkdirs()
        val file = File(directory, payload.fileName)
        file.writeText(payload.text, Charsets.UTF_8)
        return file
    }

    // ------------------------------------------------------------------- index

    fun deleteIndex() {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { repository.deleteAll() }
                .onSuccess {
                    _state.update {
                        it.copy(
                            busy = false,
                            selection = emptySet(),
                            selecting = false,
                            message = "The annotation index was deleted"
                        )
                    }
                    reload()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(busy = false, error = error.message ?: "The index could not be deleted")
                    }
                }
        }
    }

    private fun finish(result: Result<String>) {
        result.fold(
            onSuccess = { message ->
                _state.update { it.copy(busy = false, message = message) }
                reload()
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(busy = false, error = error.message ?: "That change could not be saved")
                }
            }
        )
    }

    private fun onWorkInfo(infos: List<WorkInfo>) {
        val running = infos.any {
            it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.BLOCKED
        }
        val active = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        val progress = active?.progress
        val phase = progress?.getString(IndexWorker.KEY_PHASE).orEmpty()
        val done = progress?.getInt(IndexWorker.KEY_DONE, 0) ?: 0
        val total = progress?.getInt(IndexWorker.KEY_TOTAL, 0) ?: 0
        _state.update {
            it.copy(indexing = AnnotationIndexing(running, phase, done, total))
        }
        val finished = infos.firstOrNull {
            it.state == WorkInfo.State.SUCCEEDED ||
                it.state == WorkInfo.State.FAILED ||
                it.state == WorkInfo.State.CANCELLED
        }
        val refreshAfterFinish = finished != null && finished.id != lastFinishedWorkId
        if (finished != null) lastFinishedWorkId = finished.id
        if (refreshAfterFinish || (wasRunning && !running)) reload()
        wasRunning = running
    }
}
