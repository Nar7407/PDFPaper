package com.pdfpaper.app.ui.recent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.data.RecentFile
import com.pdfpaper.app.data.RecentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecentUiState(
    val items: List<RecentFile> = emptyList(),
    /** A recent the user tapped; the screen hands it to the open flow. */
    val pendingOpen: RecentFile? = null
)

/** Backs the Recent tab with the app-private recent list (max 20). */
class RecentViewModel(application: Application) : AndroidViewModel(application) {

    private val store = RecentStore(application)
    private val _state = MutableStateFlow(RecentUiState(items = store.list()))
    val state: StateFlow<RecentUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { store.list() }
            _state.update { it.copy(items = items) }
        }
    }

    /**
     * Hands the entry to the open flow, which copies the file and decides how
     * to show it. The stored kind only says how it was opened last time; the
     * bytes get the final word, so a renamed file still behaves.
     */
    fun open(recent: RecentFile) {
        _state.update { it.copy(pendingOpen = recent) }
    }

    fun onOpenConsumed() {
        _state.update { it.copy(pendingOpen = null) }
    }

    fun clear() {
        store.clear()
        refresh()
    }
}
