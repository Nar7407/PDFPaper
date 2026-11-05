package com.pdfpaper.app.ui.open

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.data.DocumentAccess
import com.pdfpaper.app.data.DocumentLocation
import com.pdfpaper.app.data.IncomingKind
import com.pdfpaper.app.data.RecentFile
import com.pdfpaper.app.data.RecentStore
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
 * Progress of one document being opened: inspect the provider's bytes, detect
 * what the file really is, convert office files to PDF, then hand the viewer
 * either a local path or a seekable content URI (task T47).
 */
data class OpenDocumentState(
    val name: String? = null,
    val stage: String = "Opening...",
    val error: String? = null,
    /** Set when the PDF is a local file (the office cache or a picker path). */
    val pdf: File? = null,
    /**
     * Set once there is a PDF to show. For a seekable `content://` PDF this is
     * the original URI; the file is not copied.
     */
    val location: DocumentLocation? = null,
    /**
     * Task T38b: the URI the reader opened, kept as the document's identity so
     * the reading session is not keyed on the cache copy the viewer reads.
     */
    val origin: String? = null
) {
    val isReady: Boolean get() = location != null
}

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
        _state.value = OpenDocumentState(
            name = fallbackName,
            stage = "Checking the document...",
            origin = uri.toString()
        )
        viewModelScope.launch {
            val app = getApplication<Application>()
            val inspected = runCatching {
                withContext(Dispatchers.IO) {
                    DocumentAccess.inspect(app, uri, fallbackName).getOrThrow()
                }
            }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    fail("Could not read ${fallbackName ?: "the document"}: " +
                        (error.message ?: "the provider refused access"))
                    return@launch
                }
            )
            _state.update { it.copy(name = inspected.displayName) }

            when (inspected.kind) {
                IncomingKind.PDF -> {
                    val location = inspected.viewerLocation
                    if (location == null) {
                        fail(
                            "${inspected.displayName} is empty and could not be opened as a PDF."
                        )
                        return@launch
                    }
                    remember(app, uri, inspected.displayName, RecentFile.KIND_PDF)
                    _state.update {
                        it.copy(
                            stage = "",
                            pdf = inspected.localFile,
                            location = DocumentLocation(
                                value = location,
                                displayName = inspected.displayName,
                                canWriteInPlace = inspected.canWriteInPlace,
                                isContent = location.startsWith("content://")
                            )
                        )
                    }
                }
                IncomingKind.OFFICE -> {
                    val file = inspected.localFile
                    if (file == null) {
                        fail("${inspected.displayName} could not be read")
                        return@launch
                    }
                    when (val detected = withContext(Dispatchers.Default) { OfficeDetect.detect(file) }) {
                        is DocumentKind.Office ->
                            convert(app, uri, file, inspected.displayName, detected.kind)
                        DocumentKind.LegacyOffice -> fail(
                            "${inspected.displayName} is an old Word 97-2003 / PowerPoint " +
                                "97-2003 file, which is not supported. Open it in Word or " +
                                "PowerPoint, save it as .docx or .pptx, then try again."
                        )
                        is DocumentKind.Other -> fail(
                            "${inspected.displayName} is not a PDF, Word document or " +
                                "PowerPoint presentation: ${detected.description}."
                        )
                        DocumentKind.Unreadable -> fail(
                            "${inspected.displayName} looks damaged and could not be read. " +
                                "Copy the file again from its original app."
                        )
                        DocumentKind.Pdf -> {
                            file.absolutePath.let { path ->
                                remember(app, uri, inspected.displayName, RecentFile.KIND_PDF)
                                _state.update {
                                    it.copy(
                                        stage = "",
                                        pdf = file,
                                        location = DocumentLocation(
                                            path,
                                            inspected.displayName,
                                            isContent = false
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                IncomingKind.LEGACY_OFFICE -> fail(
                    "${inspected.displayName} is an old Word 97-2003 / PowerPoint 97-2003 " +
                        "file, which is not supported. Open it in Word or PowerPoint, save " +
                        "it as .docx or .pptx, then try again."
                )
                IncomingKind.IMAGE -> fail(
                    "${inspected.displayName} is an image. Share images with PDFPaper to " +
                        "make a PDF from them, or open a PDF file."
                )
                IncomingKind.OTHER -> {
                    val file = inspected.localFile
                    val description = if (file != null) {
                        when (val detected = withContext(Dispatchers.Default) {
                            OfficeDetect.detect(file)
                        }) {
                            is DocumentKind.Other -> detected.description
                            DocumentKind.Unreadable -> "the file is damaged or empty"
                            else -> "the file type is not supported"
                        }
                    } else {
                        "the file type is not supported"
                    }
                    fail(
                        "${inspected.displayName} is not a PDF, Word document or PowerPoint " +
                            "presentation: $description."
                    )
                }
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
                _state.update {
                    it.copy(
                        stage = "",
                        pdf = stored.pdf,
                        location = DocumentLocation(
                            value = stored.pdf.absolutePath,
                            displayName = displayName,
                            canWriteInPlace = false,
                            isContent = false
                        )
                    )
                }
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
