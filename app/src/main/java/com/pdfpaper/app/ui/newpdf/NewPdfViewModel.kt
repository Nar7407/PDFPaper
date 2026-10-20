package com.pdfpaper.app.ui.newpdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.render.AndroidRasterDecoder
import com.pdfpaper.engine.create.ImagePdfOptions
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.create.PdfFromImages
import com.pdfpaper.engine.create.PdfFromText
import com.pdfpaper.engine.create.PageOrientation
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.TextPdfOptions
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

/** How a new PDF starts. */
enum class NewPdfMode(val label: String) {
    BLANK("Blank page"),
    IMAGES("From images"),
    TEXT("From text")
}

enum class NewPdfPageSize(val label: String) {
    A4("A4"),
    LETTER("US Letter");

    fun engine(): PageSize = when (this) {
        A4 -> PageSize.A4
        LETTER -> PageSize.LETTER
    }
}

data class PickedImage(
    val uri: Uri,
    val name: String,
    val width: Int,
    val height: Int
)

data class NewPdfUiState(
    val mode: NewPdfMode = NewPdfMode.BLANK,
    val pageSize: NewPdfPageSize = NewPdfPageSize.A4,
    val landscape: Boolean = false,
    val text: String = "",
    val images: List<PickedImage> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val pendingBytes: ByteArray? = null,
    val pendingName: String = "new.pdf",
    val createdPath: String? = null
) {
    val canCreate: Boolean
        get() = !busy && when (mode) {
            NewPdfMode.BLANK -> true
            NewPdfMode.IMAGES -> images.isNotEmpty()
            NewPdfMode.TEXT -> text.isNotBlank()
        }
}

/**
 * Builds a new PDF entirely on device with the engine's create API (task
 * UI-2): a blank page, one page per picked image, or flowed text. The result
 * is written to the user's chosen document URI and to a cache copy the viewer
 * can open.
 */
class NewPdfViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(NewPdfUiState())
    val state: StateFlow<NewPdfUiState> = _state.asStateFlow()

    fun setMode(mode: NewPdfMode) = _state.update { it.copy(mode = mode, error = null) }

    fun setPageSize(size: NewPdfPageSize) = _state.update { it.copy(pageSize = size) }

    fun setLandscape(landscape: Boolean) = _state.update { it.copy(landscape = landscape) }

    fun setText(text: String) = _state.update { it.copy(text = text, error = null) }

    fun removeImage(uri: Uri) = _state.update {
        it.copy(images = it.images.filterNot { picked -> picked.uri == uri })
    }

    /** Reads and validates picked images; bad files are reported, never fatal. */
    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val known = _state.value.images.map { it.uri }.toSet()
                    val added = ArrayList<PickedImage>()
                    for (uri in uris) {
                        if (uri in known || added.any { it.uri == uri }) continue
                        added += readImage(uri)
                        if (known.size + added.size >= MAX_IMAGES) break
                    }
                    added
                }
            }
            result.fold(
                onSuccess = { added ->
                    _state.update { it.copy(busy = false, images = it.images + added) }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            busy = false,
                            error = error.message ?: "Could not read that image."
                        )
                    }
                }
            )
        }
    }

    /** Builds the PDF and hands it to the caller's save dialog. */
    fun create() {
        val snapshot = _state.value
        if (!snapshot.canCreate) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching { buildPdf(snapshot) to suggestedName(snapshot) }
            }
            result.fold(
                onSuccess = { (bytes, name) ->
                    _state.update {
                        it.copy(busy = false, pendingBytes = bytes, pendingName = name)
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(busy = false, error = error.message ?: "Could not create the PDF.")
                    }
                }
            )
        }
    }

    /** Writes [uri]'s pending bytes to the destination and to a cache copy. */
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
                    val directory = File(getApplication<Application>().cacheDir, "created").apply {
                        mkdirs()
                    }
                    val file = File(directory, safeName(snapshot.pendingName))
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
                        it.copy(busy = false, error = error.message ?: "Could not save the PDF.")
                    }
                }
            )
        }
    }

    private fun buildPdf(snapshot: NewPdfUiState): ByteArray {
        val orientation = if (snapshot.landscape) PageOrientation.LANDSCAPE else PageOrientation.PORTRAIT
        return when (snapshot.mode) {
            NewPdfMode.BLANK -> {
                val builder = PdfBuilder(snapshot.pageSize.engine(), orientation)
                builder.addPage()
                builder.build()
            }

            NewPdfMode.IMAGES -> {
                val images = snapshot.images.map { picked ->
                    val bytes = readBytes(picked.uri)
                    decodeImage(bytes)
                }
                PdfFromImages.create(
                    images,
                    ImagePdfOptions(
                        pageSize = snapshot.pageSize.engine(),
                        orientation = orientation
                    )
                )
            }

            NewPdfMode.TEXT -> PdfFromText.create(
                snapshot.text,
                TextPdfOptions(
                    pageSize = snapshot.pageSize.engine(),
                    orientation = orientation
                )
            )
        }
    }

    private fun suggestedName(snapshot: NewPdfUiState): String {
        val stamp = System.currentTimeMillis().toString().takeLast(6)
        return when (snapshot.mode) {
            NewPdfMode.BLANK -> "blank-$stamp.pdf"
            NewPdfMode.IMAGES -> "images-$stamp.pdf"
            NewPdfMode.TEXT -> "text-$stamp.pdf"
        }
    }

    private fun readImage(uri: Uri): PickedImage {
        val bytes = readBytes(uri)
        val data = decodeImage(bytes)
        return PickedImage(uri, displayName(uri), data.width, data.height)
    }

    private fun readBytes(uri: Uri): ByteArray {
        val resolver = getApplication<Application>().contentResolver
        val input = resolver.openInputStream(uri) ?: error("Could not read that image.")
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_IMAGE_BYTES) { "That image is larger than 64 MB." }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    /**
     * JPEG goes through unchanged. PNG uses the Android decoder. Anything else
     * the platform can open (for example WebP or HEIC) is re-encoded to PNG so
     * the pure-Kotlin engine has a format it understands.
     */
    private fun decodeImage(bytes: ByteArray): ImageData =
        EditImages.decode(bytes, AndroidRasterDecoder).getOrElse { first ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("This image format is not supported.")
            try {
                val png = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) {
                    "Could not convert this image."
                }
                EditImages.decode(png.toByteArray(), AndroidRasterDecoder).getOrElse {
                    error(first.message ?: "This image format is not supported.")
                }
            } finally {
                bitmap.recycle()
            }
        }

    private fun displayName(uri: Uri): String = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull() ?: "Image"

    private fun safeName(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }
            .joinToString("")
            .trim('.')
            .take(80)
        return if (cleaned.endsWith(".pdf", ignoreCase = true)) cleaned else "$cleaned.pdf"
    }

    private companion object {
        const val MAX_IMAGES = 100
        const val MAX_IMAGE_BYTES = 64 * 1024 * 1024
    }
}
