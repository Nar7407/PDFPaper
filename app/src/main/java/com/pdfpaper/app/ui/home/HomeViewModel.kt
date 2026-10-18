package com.pdfpaper.app.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.data.CachedPdf
import com.pdfpaper.app.data.cachePdfAndRemember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val pendingNavigation: CachedPdf? = null
)

class HomeViewModel : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun onDocumentPicked(context: Context, uri: Uri) {
        _state.update { it.copy(isLoading = true, message = null, error = null) }
        viewModelScope.launch {
            try {
                val appContext = context.applicationContext
                val cached = cachePdfAndRemember(appContext, uri)
                _state.update { it.copy(isLoading = false, pendingNavigation = cached) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to open PDF")
                }
            }
        }
    }

    fun onPickerCancelled() {
        _state.update { it.copy(message = "Selection cancelled", error = null) }
    }

    fun onNavigationConsumed() {
        _state.update { it.copy(pendingNavigation = null) }
    }

    fun clearMessages() {
        _state.update { it.copy(message = null, error = null) }
    }
}
