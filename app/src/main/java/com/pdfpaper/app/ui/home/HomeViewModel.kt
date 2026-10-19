package com.pdfpaper.app.ui.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Home only keeps a note for the user; opening a document is the open flow's job. */
data class HomeUiState(
    val message: String? = null
)

class HomeViewModel : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun onPickerCancelled() {
        _state.update { it.copy(message = "Selection cancelled") }
    }

    fun clearMessages() {
        _state.update { it.copy(message = null) }
    }
}
