package com.pdfpaper.app.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.DocumentAccess
import com.pdfpaper.app.index.IndexRepository
import com.pdfpaper.app.index.SearchFilters
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.RegexFlag
import com.pdfpaper.engine.text.RegexResult
import com.pdfpaper.engine.text.RegexSearch
import com.pdfpaper.engine.text.layout
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The bytes one export needs: a suggested name, the text and its MIME type. */
data class RegexExportPayload(val fileName: String, val text: String, val mimeType: String)

/**
 * T65: the library scope. It runs over the T50 index's stored page text, so a
 * hit has no geometry; [openRow] re-finds the same instance on the page it
 * opens and hands the existing `hl` bounds to the viewer. There is exactly one
 * scope per view model, so no screen ever has to switch one.
 */
class RegexViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(
        RegexUiState(scope = RegexScope.LIBRARY, flags = AppSettings.regexFlags(application))
    )
    val state: StateFlow<RegexUiState> = _state.asStateFlow()

    private val _saved = MutableStateFlow(
        RegexPatternCodec.decode(AppSettings.savedRegexPatternsRaw(application))
    )
    val saved: StateFlow<List<RegexPattern>> = _saved.asStateFlow()

    private var runJob: Job? = null
    private var testerJob: Job? = null

    /** The pattern the current rows were produced with, so a tap re-finds them. */
    private var runPattern: String = ""

    fun setPattern(pattern: String) {
        _state.update { it.copy(pattern = pattern, error = null) }
    }

    fun setFlags(flags: Set<RegexFlag>) {
        AppSettings.setRegexFlags(getApplication(), flags)
        _state.update { it.copy(flags = flags) }
    }

    fun setUniqueOnly(unique: Boolean) {
        _state.update { it.copy(uniqueOnly = unique, activeIndex = -1) }
    }

    fun setActive(index: Int) {
        _state.update { it.copy(activeIndex = index) }
    }

    /**
     * Searches every indexed file that has text. Folder and date filters come
     * from the Search tab, so a library run searches what the tab shows.
     */
    fun run(filters: SearchFilters = SearchFilters()) {
        val pattern = _state.value.pattern
        if (pattern.isEmpty()) return
        val flags = _state.value.flags
        runPattern = pattern
        // A malformed pattern must report itself even when the index holds no
        // pages to compile against: the per-page loop below would never run.
        val probe = RegexSearch.find(pattern, "", libraryOptions(flags))
        if (probe is RegexResult.InvalidPattern) {
            _state.update {
                it.copy(running = false, rows = emptyList(), error = probe.error, filesTotal = 0)
            }
            return
        }
        runJob?.cancel()
        _state.update {
            it.copy(
                running = true,
                rows = emptyList(),
                error = null,
                truncated = false,
                timedOut = false,
                cancelled = false,
                progress = null,
                filesTotal = 0,
                unsearchable = emptyList(),
                activeIndex = -1
            )
        }
        val repository = IndexRepository.get(getApplication())
        runJob = viewModelScope.launch {
            val rows = ArrayList<RegexRow>()
            var truncated = false
            var timedOut = false
            var cancelled = false
            try {
                withContext(Dispatchers.Default) {
                    val startedAt = System.currentTimeMillis()
                    val files = repository.indexedFiles(filters)
                    val unsearchable = repository.unsearchableFiles(filters)
                        .map { it.displayName }
                    _state.update { it.copy(filesTotal = files.size, unsearchable = unsearchable) }
                    for ((index, file) in files.withIndex()) {
                        if (!isActive) {
                            cancelled = true
                            break
                        }
                        _state.update {
                            it.copy(progress = RegexProgress(index, files.size, file.displayName))
                        }
                        val pages = repository.pageTexts(file.fileId)
                        for (page in pages) {
                            if (!isActive) {
                                cancelled = true
                                break
                            }
                            val remaining = LIBRARY_MAX_MATCHES - rows.size
                            if (remaining <= 0) {
                                truncated = true
                                break
                            }
                            val remainingMs =
                                LIBRARY_RUN_TIME_MS - (System.currentTimeMillis() - startedAt)
                            if (remainingMs <= 0L) {
                                timedOut = true
                                break
                            }
                            val pageResult = libraryPageResult(
                                fileId = file.fileId,
                                documentUri = file.documentUri,
                                displayName = file.displayName,
                                pageIndex = page.pageIndex,
                                storedText = page.text,
                                pattern = pattern,
                                flags = flags,
                                maxMatches = remaining
                            )
                            if (pageResult.error != null) {
                                _state.update { it.copy(error = pageResult.error) }
                                return@withContext
                            }
                            rows += pageResult.rows
                            if (pageResult.truncated) truncated = true
                            if (pageResult.timedOut) timedOut = true
                            if (pageResult.cancelled) cancelled = true
                            if (truncated || timedOut || cancelled) break
                        }
                        _state.update {
                            it.copy(
                                progress = RegexProgress(index + 1, files.size, file.displayName),
                                rows = rows.toList()
                            )
                        }
                        if (truncated || timedOut || cancelled) break
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                withContext(NonCancellable) {
                    _state.update {
                        it.copy(
                            running = false,
                            rows = rows.toList(),
                            truncated = truncated,
                            timedOut = timedOut,
                            cancelled = cancelled,
                            progress = null
                        )
                    }
                }
            }
        }
    }

    fun cancel() {
        val job = runJob ?: return
        // A job cancelled before its body starts never reaches the finally
        // block, so the panel must be told here as well (T65 device test).
        if (job.isActive) {
            _state.update { it.copy(running = false, cancelled = true, progress = null) }
        }
        job.cancel()
    }

    fun clearResults() {
        runJob?.cancel()
        _state.update {
            it.copy(
                rows = emptyList(),
                running = false,
                error = null,
                truncated = false,
                timedOut = false,
                cancelled = false,
                progress = null,
                filesTotal = 0,
                unsearchable = emptyList(),
                activeIndex = -1,
                tester = null
            )
        }
    }

    /**
     * One line proving the pattern works: on [layoutText] when the caller has
     * one, otherwise on the first page of the first indexed file.
     */
    fun runTester(layoutText: String? = null) {
        val pattern = _state.value.pattern
        if (pattern.isEmpty()) {
            _state.update { it.copy(tester = null) }
            return
        }
        val flags = _state.value.flags
        testerJob?.cancel()
        testerJob = viewModelScope.launch {
            val sample: Pair<String, Int>? = if (layoutText != null) {
                layoutText to 0
            } else {
                val repository = IndexRepository.get(getApplication())
                withContext(Dispatchers.IO) {
                    repository.indexedFiles().firstOrNull()?.let { file ->
                        repository.pageTexts(file.fileId).firstOrNull()?.let { page ->
                            page.text to page.pageIndex
                        }
                    }
                }
            }
            if (sample == null) {
                _state.update {
                    it.copy(tester = RegexTesterResult("Nothing indexed to test against yet"))
                }
                return@launch
            }
            val startedAt = System.currentTimeMillis()
            val result = RegexSearch.find(
                pattern,
                sample.first,
                libraryOptions(flags, maxMatches = 50, runTimeMs = 300L)
            )
            val elapsed = System.currentTimeMillis() - startedAt
            val tester = when (result) {
                is RegexResult.InvalidPattern -> RegexTesterResult("", result.error)
                is RegexResult.Matches -> RegexTesterResult(
                    describeRegexTester(result.matches.size, sample.second, elapsed)
                )
            }
            _state.update { it.copy(tester = tester) }
        }
    }

    // ---------------------------------------------------------- saved patterns

    fun savedPatterns(): List<RegexPattern> = _saved.value

    fun savePattern(name: String) {
        val pattern = _state.value.pattern
        if (pattern.isEmpty()) return
        val record = RegexPattern(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { pattern },
            pattern = pattern,
            flags = _state.value.flags,
            createdAt = System.currentTimeMillis()
        )
        persistSaved(_saved.value + record)
    }

    fun applySaved(id: String) {
        val record = _saved.value.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(pattern = record.pattern, flags = record.flags, error = null) }
    }

    fun renameSaved(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        persistSaved(_saved.value.map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    fun deleteSaved(id: String) {
        persistSaved(_saved.value.filterNot { it.id == id })
    }

    private fun persistSaved(patterns: List<RegexPattern>) {
        _saved.value = patterns
        AppSettings.setSavedRegexPatternsRaw(
            getApplication(),
            RegexPatternCodec.encode(patterns)
        )
    }

    // ----------------------------------------------------------------- export

    fun exportPayload(csv: Boolean): RegexExportPayload? {
        val rows = _state.value.visibleRows
        if (rows.isEmpty()) return null
        return RegexExportPayload(
            fileName = regexFileName(RegexScope.LIBRARY, "", csv),
            text = if (csv) rowsToCsv(rows) else rowsToTxt(rows),
            mimeType = if (csv) "text/csv" else "text/plain"
        )
    }

    // ------------------------------------------------------------- tap mapping

    /**
     * Re-finds [row] on the page it opens. The index stored normalised text, so
     * the pattern is run again on the same normalisation of the page's own text
     * and the hit is mapped back to give the viewer its `hl` bounds. Every miss
     * still reports the row's page, with a note instead of boxes.
     */
    fun openRow(
        row: RegexRow,
        onReady: (path: String, pageIndex: Int, query: String, bounds: String?, note: String?) -> Unit
    ) {
        val pattern = runPattern.ifEmpty { _state.value.pattern }
        val flags = _state.value.flags
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                DocumentAccess.open(getApplication(), row.documentUri).fold(
                    onSuccess = { opened ->
                        opened.document.use { document ->
                            val page = document.pages().getOrNull(row.pageIndex)
                            if (page == null) {
                                OpenRowOutcome(
                                    null,
                                    "The file changed since it was indexed; no highlight boxes."
                                )
                            } else {
                                val layout = page.layout()
                                val range = ordinalRangeInLayout(
                                    layout.text,
                                    pattern,
                                    flags,
                                    row.ordinal
                                )
                                if (range == null) {
                                    OpenRowOutcome(
                                        null,
                                        "The file changed since it was indexed; no highlight boxes."
                                    )
                                } else {
                                    OpenRowOutcome(
                                        boundsOf(
                                            layout.rectsFor(range.first, range.last + 1)
                                        ),
                                        null
                                    )
                                }
                            }
                        }
                    },
                    onFailure = {
                        OpenRowOutcome(null, "This file cannot be opened any more.")
                    }
                )
            }
            onReady(row.documentUri, row.pageIndex, row.text, outcome.bounds, outcome.note)
        }
    }

    private data class OpenRowOutcome(val bounds: String?, val note: String?)
}
