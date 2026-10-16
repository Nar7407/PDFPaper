package com.pdfpaper.app.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ViewerUiState(
    val isLoading: Boolean = true,
    val displayName: String? = null,
    val sizeBytes: Long? = null,
    val error: String? = null
)

class ViewerViewModel : ViewModel() {

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    fun load(path: String) {
        _state.update { ViewerUiState(isLoading = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val file = File(path)
                if (!file.isFile) {
                    ViewerUiState(
                        isLoading = false,
                        error = "File not found: $path"
                    )
                } else {
                    ViewerUiState(
                        isLoading = false,
                        displayName = file.name,
                        sizeBytes = file.length()
                    )
                }
            }
            _state.update { result }
        }
    }
}
