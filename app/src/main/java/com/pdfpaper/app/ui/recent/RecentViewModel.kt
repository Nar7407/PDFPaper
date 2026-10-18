package com.pdfpaper.app.ui.recent

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.data.CachedPdf
import com.pdfpaper.app.data.RecentFile
import com.pdfpaper.app.data.RecentStore
import com.pdfpaper.app.data.cachePdfFromUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecentUiState(
    val items: List<RecentFile> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val pendingNavigation: CachedPdf? = null
)

/** Backs the Recent tab with the app-private recent list (max 20). */
class RecentViewModel(application: Application) : AndroidViewModel(application) {

    private val store = RecentStore(application)
    private val _state = MutableStateFlow(RecentUiState(items = store.list()))
    val state: StateFlow<RecentUiState> = _state.asStateFlow()

    fun refresh() {
        _state.update { it.copy(items = store.list(), error = null) }
    }

    fun open(recent: RecentFile) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                cachePdfFromUri(
                    getApplication<Application>(),
                    Uri.parse(recent.uri),
                    recent.displayName
                )
            }.fold(
                onSuccess = { cached ->
                    _state.update {
                        it.copy(
                            loading = false,
                            pendingNavigation = cached,
                            items = store.list()
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "Could not open ${recent.displayName}: ${error.message ?: "permission denied"}"
                        )
                    }
                }
            )
        }
    }

    fun clear() {
        store.clear()
        refresh()
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun onNavigationConsumed() {
        _state.update { it.copy(pendingNavigation = null) }
    }
}
