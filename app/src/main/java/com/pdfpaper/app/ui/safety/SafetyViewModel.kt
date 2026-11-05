package com.pdfpaper.app.ui.safety

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.data.DocumentAccess
import com.pdfpaper.app.data.SafetyScanCache
import com.pdfpaper.safety.PdfSafety
import com.pdfpaper.safety.SafetyReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the safety details screen (and the viewer banner) knows right now. */
data class SafetyUiState(
    val loading: Boolean = false,
    val report: SafetyReport? = null,
    val error: String? = null
)

/**
 * Runs the static safety check off the main thread (T49). The viewer asks for
 * a scan when a document opens; the details screen reuses the cached report or
 * starts one if the viewer has not finished yet.
 */
class SafetyViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SafetyUiState())
    val state: StateFlow<SafetyUiState> = _state.asStateFlow()

    private var scannedPath: String? = null
    private var running: kotlinx.coroutines.Job? = null

    fun scan(path: String, force: Boolean = false) {
        if (path.isBlank()) {
            _state.value = SafetyUiState(error = "No document selected")
            return
        }
        if (!force && scannedPath == path && _state.value.report != null) return
        running?.cancel()
        scannedPath = path
        val cached = if (force) null else SafetyScanCache.get(path)
        if (cached != null) {
            _state.value = SafetyUiState(loading = false, report = cached)
            return
        }
        _state.value = SafetyUiState(loading = true)
        running = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                DocumentAccess.openSource(getApplication(), path).fold(
                    onSuccess = { source -> PdfSafety.scan(source) },
                    onFailure = { error -> Result.failure<SafetyReport>(error) }
                )
            }
            result.fold(
                onSuccess = { report ->
                    SafetyScanCache.put(path, report)
                    _state.value = SafetyUiState(loading = false, report = report)
                },
                onFailure = { error ->
                    _state.value = SafetyUiState(
                        loading = false,
                        error = error.message ?: "The safety check could not finish"
                    )
                }
            )
        }
    }

    fun clear() {
        running?.cancel()
        running = null
        scannedPath = null
        _state.value = SafetyUiState()
    }
}
