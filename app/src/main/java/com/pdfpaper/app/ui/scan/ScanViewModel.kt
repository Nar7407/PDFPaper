package com.pdfpaper.app.ui.scan

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.render.AndroidRasterDecoder
import com.pdfpaper.engine.create.ImagePdfOptions
import com.pdfpaper.engine.create.PdfFromImages
import com.pdfpaper.engine.create.PageOrientation
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.edit.ImageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

data class ScanUiState(
    val pages: List<Uri> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val readyBytes: ByteArray? = null,
    val pendingBytes: ByteArray? = null,
    val pendingName: String = "scan.pdf",
    val createdPath: String? = null
) {
    val hasScan: Boolean get() = pages.isNotEmpty()
}

/**
 * Turns the JPEG pages returned by the ML Kit Document Scanner into a PDF with
 * the engine's [PdfFromImages] (task UI-2). The scanner owns capture, crop and
 * filters; this model only assembles and saves the result.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    fun scanFailed(message: String) {
        _state.update { it.copy(busy = false, error = message) }
    }

    /** Called with the scanner's page image URIs. */
    fun onScanned(uris: List<Uri>) {
        if (uris.isEmpty()) {
            scanFailed("No pages were scanned.")
            return
        }
        viewModelScope.launch {
            _state.value = ScanUiState(pages = uris, busy = true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val images = uris.map { uri -> decode(uri) }
                    PdfFromImages.create(
                        images,
                        ImagePdfOptions(
                            pageSize = PageSize.A4,
                            orientation = PageOrientation.PORTRAIT
                        )
                    )
                }
            }
            result.fold(
                onSuccess = { bytes ->
                    _state.update {
                        it.copy(
                            busy = false,
                            readyBytes = bytes,
                            pendingName = "scan-${System.currentTimeMillis().toString().takeLast(6)}.pdf"
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            busy = false,
                            error = error.message ?: "Could not read the scanned pages."
                        )
                    }
                }
            )
        }
    }

    /** Opens the save dialog for the assembled scan. */
    fun saveAsPdf() {
        val bytes = _state.value.readyBytes ?: return
        // A fresh array makes the screen's LaunchedEffect run again after a
        // cancelled save dialog.
        _state.update { it.copy(pendingBytes = bytes.copyOf(), error = null) }
    }

    fun saveTo(uri: Uri) {
        val snapshot = _state.value
        val bytes = snapshot.pendingBytes ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri, "w")?.use { stream ->
                        stream.write(bytes)
                        stream.flush()
                    } ?: error("Could not open the destination for writing.")
                    val directory = File(getApplication<Application>().cacheDir, "scans").apply {
                        mkdirs()
                    }
                    val file = File(directory, snapshot.pendingName)
                    file.writeBytes(bytes)
                    file
                }
            }
            result.fold(
                onSuccess = { file ->
                    _state.update { it.copy(busy = false, createdPath = file.absolutePath) }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(busy = false, error = error.message ?: "Could not save the scan.")
                    }
                }
            )
        }
    }

    private fun decode(uri: Uri): ImageData {
        val resolver = getApplication<Application>().contentResolver
        val input = resolver.openInputStream(uri) ?: error("Could not read a scanned page.")
        val bytes = input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_PAGE_BYTES) { "A scanned page is larger than 32 MB." }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        return EditImages.decode(bytes, AndroidRasterDecoder).getOrElse { failure ->
            error("A scanned page could not be decoded: ${failure.message ?: "unknown format"}")
        }
    }

    private companion object {
        const val MAX_PAGE_BYTES = 32 * 1024 * 1024
    }
}
