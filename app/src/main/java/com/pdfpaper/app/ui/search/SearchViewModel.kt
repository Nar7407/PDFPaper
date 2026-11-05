package com.pdfpaper.app.ui.search

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.rememberUriPermission
import com.pdfpaper.app.index.DateFilter
import com.pdfpaper.app.index.DocumentTreeWalker
import com.pdfpaper.app.index.IndexFolder
import com.pdfpaper.app.index.IndexStats
import com.pdfpaper.app.index.IndexStatusFile
import com.pdfpaper.app.index.IndexWork
import com.pdfpaper.app.index.IndexWorker
import com.pdfpaper.app.index.IndexRepository
import com.pdfpaper.app.index.SearchFilters
import com.pdfpaper.app.index.SearchResultGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class SearchUiState(
    val query: String = "",
    val folders: List<IndexFolder> = emptyList(),
    val filters: SearchFilters = SearchFilters(),
    val groups: List<SearchResultGroup> = emptyList(),
    val locked: List<IndexStatusFile> = emptyList(),
    val needsOcr: List<IndexStatusFile> = emptyList(),
    val errors: List<IndexStatusFile> = emptyList(),
    val stats: IndexStats? = null,
    val searching: Boolean = false,
    val error: String? = null,
    val indexRunning: Boolean = false,
    val indexPaused: Boolean = false,
    val indexPhase: String = "",
    val indexDone: Int = 0,
    val indexTotal: Int = 0,
    val onlyWhileCharging: Boolean = false
) {
    val hasFolders: Boolean get() = folders.isNotEmpty()
    val hasQuery: Boolean get() = query.isNotBlank()
}

/**
 * Task T50: search and index control. A query is debounced, then runs against
 * the local FTS4 table; WorkManager progress is polled while a scan is active
 * so the screen shows the current file without holding the worker in memory.
 */
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = IndexRepository.get(application)
    private val _state = MutableStateFlow(
        SearchUiState(
            indexPaused = AppSettings.indexPaused(application),
            onlyWhileCharging = AppSettings.indexOnlyWhileCharging(application)
        )
    )
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var refreshJob: Job? = null
    private var wasRunning = false
    private var lastFinishedWorkId: UUID? = null

    init {
        refresh()
        // The database is the source of truth for the index. Observing it means
        // a scan that finished between two one-second progress polls still
        // updates the counts and the locked/needs-OCR lists.
        viewModelScope.launch {
            repository.indexedFileCountFlow()
                .conflate()
                .collectLatest {
                    delay(150)
                    refresh()
                }
        }
        viewModelScope.launch {
            IndexWork.observe(application).collect { infos -> onWorkInfo(infos) }
        }
    }

    fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query, error = null) }
        scheduleSearch()
    }

    fun setFolderFilter(folderId: Long?) {
        _state.update { it.copy(filters = it.filters.copy(folderId = folderId)) }
        scheduleSearch()
    }

    fun setDateFilter(date: DateFilter) {
        _state.update { it.copy(filters = it.filters.copy(date = date)) }
        scheduleSearch()
    }

    fun setFileNameOnly(fileNameOnly: Boolean) {
        _state.update { it.copy(filters = it.filters.copy(fileNameOnly = fileNameOnly)) }
        scheduleSearch()
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // T55: ask for read+write so a file under the tree can be
                    // saved back in place; a provider that persists reads only
                    // still gets the read grant.
                    rememberUriPermission(getApplication(), uri)
                    val name = DocumentTreeWalker.displayName(getApplication(), uri)
                    repository.addFolder(uri.toString(), name)
                }
            }
            result.fold(
                onSuccess = {
                    IndexWork.enqueue(getApplication(), replace = true)
                    refresh()
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            error = "This folder cannot be kept for indexing: " +
                                (error.message ?: "permission denied")
                        )
                    }
                }
            )
        }
    }

    fun removeFolder(folder: IndexFolder) {
        viewModelScope.launch {
            runCatching { repository.removeFolder(folder.id) }
                .onFailure { error ->
                    _state.update {
                        it.copy(error = error.message ?: "The folder could not be removed")
                    }
                }
            refresh()
        }
    }

    fun indexNow() {
        if (AppSettings.indexPaused(getApplication())) {
            IndexWork.resume(getApplication())
        } else {
            IndexWork.enqueue(getApplication(), replace = true)
        }
        _state.update { it.copy(indexPaused = false) }
    }

    fun pause() {
        IndexWork.pause(getApplication())
        _state.update { it.copy(indexPaused = true, indexRunning = false) }
    }

    fun resume() {
        IndexWork.resume(getApplication())
        _state.update { it.copy(indexPaused = false) }
    }

    fun cancel() {
        IndexWork.cancel(getApplication())
        _state.update { it.copy(indexRunning = false) }
    }

    fun rebuild() {
        viewModelScope.launch {
            IndexWork.cancel(getApplication())
            AppSettings.setIndexPaused(getApplication(), false)
            runCatching { IndexWork.clearIndex(getApplication()) }
                .onFailure { error ->
                    _state.update {
                        it.copy(error = error.message ?: "The index could not be cleared")
                    }
                    return@launch
                }
            _state.update { it.copy(indexPaused = false) }
            IndexWork.schedulePeriodic(getApplication())
            IndexWork.enqueue(getApplication(), replace = true)
            refresh()
        }
    }

    fun deleteIndex() {
        viewModelScope.launch {
            IndexWork.cancel(getApplication())
            runCatching { IndexWork.clearIndex(getApplication()) }
                .onFailure { error ->
                    _state.update {
                        it.copy(error = error.message ?: "The index could not be deleted")
                    }
                    return@launch
                }
            _state.update {
                it.copy(
                    groups = emptyList(),
                    locked = emptyList(),
                    needsOcr = emptyList(),
                    errors = emptyList()
                )
            }
            refresh()
        }
    }

    fun setOnlyWhileCharging(onlyCharging: Boolean) {
        AppSettings.setIndexOnlyWhileCharging(getApplication(), onlyCharging)
        IndexWork.schedulePeriodic(getApplication())
        _state.update { it.copy(onlyWhileCharging = onlyCharging) }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            runCatching {
                val folders = repository.folders()
                val stats = repository.stats()
                val statuses = repository.statusFiles()
                _state.update {
                    it.copy(
                        folders = folders,
                        stats = stats,
                        locked = statuses.filter { file -> file.status.name == "LOCKED" },
                        needsOcr = statuses.filter { file -> file.status.name == "NEEDS_OCR" },
                        errors = statuses.filter {
                            file -> file.status.name == "ERROR" ||
                                file.status.name == "TOO_LARGE"
                        }
                    )
                }
            }.onFailure { error ->
                // A newer refresh cancels this one; that is normal and must
                // not surface as an index error.
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(error = error.message ?: "The local index could not be read")
                }
            }
            if (_state.value.hasQuery) runSearch()
        }
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        val query = _state.value.query
        if (query.isBlank()) {
            _state.update { it.copy(groups = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            runSearch()
        }
    }

    private suspend fun runSearch() {
        val snapshot = _state.value
        val query = snapshot.query
        if (query.isBlank()) return
        _state.update { it.copy(searching = true, error = null) }
        val result = runCatching {
            repository.search(query, snapshot.filters)
        }
        if (_state.value.query != query) return
        result.fold(
            onSuccess = { groups ->
                _state.update { it.copy(groups = groups, searching = false) }
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(
                        searching = false,
                        error = error.message ?: "The index could not be searched"
                    )
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
            it.copy(
                indexRunning = running,
                indexPaused = AppSettings.indexPaused(getApplication()),
                indexPhase = phase,
                indexDone = done,
                indexTotal = total
            )
        }
        // A short scan can go from ENQUEUED to SUCCEEDED between polls, so
        // compare the finished work id as well as the running transition.
        val finished = infos.firstOrNull {
            it.state == WorkInfo.State.SUCCEEDED ||
                it.state == WorkInfo.State.FAILED ||
                it.state == WorkInfo.State.CANCELLED
        }
        val refreshAfterFinish = finished != null && finished.id != lastFinishedWorkId
        if (finished != null) lastFinishedWorkId = finished.id
        if (refreshAfterFinish || (wasRunning && !running)) refresh()
        wasRunning = running
    }
}
