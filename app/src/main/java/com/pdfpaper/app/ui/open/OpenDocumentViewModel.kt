package com.pdfpaper.app.ui.open

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.data.RecentFile
import com.pdfpaper.app.data.RecentStore
import com.pdfpaper.app.data.cacheDocumentFromUri
import com.pdfpaper.app.data.rememberUriPermission
import com.pdfpaper.app.office.DocumentKind
import com.pdfpaper.app.office.OfficeCache
import com.pdfpaper.app.office.OfficeDetect
import com.pdfpaper.app.office.OfficeKind
import com.pdfpaper.app.office.OfficeToPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Progress of one document being opened: copy from the provider, detect what
 * it really is, convert office files to PDF, then hand the viewer a file.
 */
data class OpenDocumentState(
    val name: String? = null,
    val stage: String = "Opening...",
    val error: String? = null,
    /** Set once there is a PDF to show; the screen then replaces itself. */
    val pdf: File? = null
)

/**
 * The one path all three entrances share - the picker in the workspace header,
 * the Recent list and a VIEW intent from another app. Nothing is trusted from
 * the file name: the copied bytes decide whether the viewer opens the file
 * directly, the converter turns it into a PDF first, or the user gets an
 * explanation.
 */
class OpenDocumentViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(OpenDocumentState())
    val state: StateFlow<OpenDocumentState> = _state.asStateFlow()

    fun open(uri: Uri, fallbackName: String? = null) {
        if (uri.toString().isBlank()) {
            _state.value = OpenDocumentState(stage = "", error = "No file was picked")
            return
        }
        _state.value = OpenDocumentState(name = fallbackName, stage = "Copying the document...")
        viewModelScope.launch {
            val app = getApplication<Application>()
            val copied = runCatching {
                withContext(Dispatchers.IO) { cacheDocumentFromUri(app, uri, fallbackName) }
            }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    fail("Could not read ${fallbackName ?: "the document"}: " +
                        (error.message ?: "the provider refused access"))
                    return@launch
                }
            )
            _state.update { it.copy(name = copied.displayName, stage = "Checking the file...") }

            when (val detected = withContext(Dispatchers.Default) { OfficeDetect.detect(copied.file) }) {
                DocumentKind.Pdf -> {
                    remember(app, uri, copied.displayName, RecentFile.KIND_PDF)
                    _state.update { it.copy(stage = "", pdf = copied.file) }
                }
                is DocumentKind.Office ->
                    convert(app, uri, copied.file, copied.displayName, detected.kind)
                DocumentKind.LegacyOffice -> fail(
                    "${copied.displayName} is an old Word 97-2003 / PowerPoint 97-2003 file, " +
                        "which is not supported. Open it in Word or PowerPoint, save it as " +
                        ".docx or .pptx, then try again."
                )
                is DocumentKind.Other -> fail(
                    "${copied.displayName} is not a PDF, Word document or PowerPoint " +
                        "presentation: ${detected.description}."
                )
                DocumentKind.Unreadable -> fail(
                    "${copied.displayName} looks damaged and could not be read. " +
                        "Copy the file again from its original app."
                )
            }
        }
    }

    private suspend fun convert(
        app: Application,
        uri: Uri,
        source: File,
        displayName: String,
        kind: OfficeKind
    ) {
        _state.update { it.copy(stage = "Converting the ${kind.label.lowercase()} to PDF...") }
        withContext(Dispatchers.Default) { OfficeToPdf.convert(source, kind) }.fold(
            onSuccess = { conversion ->
                val stored = withContext(Dispatchers.IO) {
                    OfficeCache.store(app.cacheDir, displayName, kind, conversion.pdf, conversion.warnings)
                }
                remember(app, uri, displayName, kind.recentValue)
                _state.update { it.copy(stage = "", pdf = stored.pdf) }
            },
            onFailure = { error ->
                fail(error.message ?: "The document could not be converted")
            }
        )
    }

    private fun remember(app: Application, uri: Uri, displayName: String, kind: String) {
        rememberUriPermission(app, uri)
        runCatching {
            RecentStore(app).add(uri.toString(), displayName, kind)
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(stage = "", error = message) }
    }
}
