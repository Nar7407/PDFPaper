package com.pdfpaper.app.ui.compress

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.render.AndroidImageDecoder
import com.pdfpaper.app.render.AndroidImageEncoder
import com.pdfpaper.engine.compress.CompressEstimate
import com.pdfpaper.engine.compress.CompressOptions
import com.pdfpaper.engine.compress.CompressPreset
import com.pdfpaper.engine.compress.CompressResult
import com.pdfpaper.engine.compress.PdfCompressor
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The choices the screen offers. [Custom] edits the values by hand; the other
 * three stand for the engine's presets.
 */
enum class CompressMode(val label: String, val preset: CompressPreset?) {
    LIGHT("Light", CompressPreset.LIGHT),
    BALANCED("Balanced", CompressPreset.BALANCED),
    STRONG("Strong", CompressPreset.STRONG),
    CUSTOM("Custom", null);

    /** The one-line note shown under the choice. */
    val description: String
        get() = preset?.description ?: "Set the resolution, the quality and the extras yourself."
}

/**
 * Everything the compress screen shows.
 *
 * [options] is the setting the current choice stands for: the preset's own
 * values, or the hand-made ones in [targetDpi], [jpegQuality], [grayscale] and
 * [stripMetadata].
 */
data class CompressUiState(
    val path: String = "",
    val fileName: String = "",
    val fileSizeBytes: Int = 0,
    val mode: CompressMode = CompressMode.BALANCED,
    val targetDpi: Int = 150,
    val jpegQuality: Int = 75,
    val grayscale: Boolean = false,
    val stripMetadata: Boolean = false,
    val loading: Boolean = false,
    val estimate: CompressEstimate? = null,
    val estimating: Boolean = false,
    val compressing: Boolean = false,
    val cancelled: Boolean = false,
    val result: CompressResult? = null,
    val error: String? = null,
    val hasSignatures: Boolean = false,
    val saved: Boolean = false
) {
    /** The options of the current choice. */
    val options: CompressOptions
        get() {
            val preset = mode.preset ?: return CompressOptions(
                targetImageDpi = targetDpi,
                jpegQuality = jpegQuality,
                grayscale = grayscale,
                stripMetadata = stripMetadata
            )
            return preset.options()
        }
}

/**
 * Drives the "Compress PDF" screen: it opens the document, keeps the size
 * estimate in step with the chosen options and runs the compression itself.
 *
 * The estimate is re-run whenever the options change, a moment after the last
 * change, so dragging a slider does not start a run per pixel. The
 * compression runs on its own job and can be stopped; a stopped run never
 * publishes a result.
 */
class CompressViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(CompressUiState())
    val state: StateFlow<CompressUiState> = _state.asStateFlow()

    private var estimateJob: Job? = null
    private var compressJob: Job? = null

    /** Counts the compression runs, so a stopped run cannot clear the state of a newer one. */
    private var compressRun = 0

    /** Opens the document and starts the first estimate. */
    fun load(path: String) {
        if (path.isBlank()) return
        estimateJob?.cancel()
        compressJob?.cancel()
        compressRun++
        val file = File(path)
        _state.update {
            it.copy(
                path = path,
                fileName = file.name,
                fileSizeBytes = if (file.exists()) file.length().toInt() else 0,
                loading = true,
                compressing = false,
                estimating = false,
                error = null,
                result = null,
                estimate = null,
                saved = false,
                cancelled = false
            )
        }
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) {
                guarded {
                    val document = PdfDocument.open(FileSource(file)).getOrThrow()
                    try {
                        PdfCompressor.detectSignatures(document)
                    } finally {
                        document.close()
                    }
                }
            }
            if (!isActive) return@launch
            opened.fold(
                onSuccess = { signed ->
                    _state.update { it.copy(loading = false, hasSignatures = signed) }
                    refreshEstimate()
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            estimating = false,
                            error = error.message ?: "Could not open that PDF."
                        )
                    }
                }
            )
        }
    }

    /** Picks one of the presets, or the custom mode. */
    fun selectPreset(mode: CompressMode) {
        val preset = mode.preset
        _state.update {
            if (preset == null) {
                it.copy(mode = mode, error = null)
            } else {
                val options = preset.options()
                it.copy(
                    mode = mode,
                    targetDpi = options.targetImageDpi,
                    jpegQuality = options.jpegQuality,
                    grayscale = options.grayscale,
                    stripMetadata = options.stripMetadata,
                    error = null
                )
            }
        }
        refreshEstimate()
    }

    /** Sets the resolution the images are scaled down to. */
    fun setTargetDpi(dpi: Int) {
        _state.update {
            it.copy(
                mode = CompressMode.CUSTOM,
                targetDpi = dpi.coerceIn(MIN_TARGET_DPI, MAX_TARGET_DPI),
                error = null
            )
        }
        refreshEstimate()
    }

    /** Sets the quality of the re-encoded JPEG images. */
    fun setQuality(quality: Int) {
        _state.update {
            it.copy(
                mode = CompressMode.CUSTOM,
                jpegQuality = quality.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY),
                error = null
            )
        }
        refreshEstimate()
    }

    /** Turns the images grey before they are written again. */
    fun setGrayscale(grayscale: Boolean) {
        _state.update { it.copy(mode = CompressMode.CUSTOM, grayscale = grayscale, error = null) }
        refreshEstimate()
    }

    /** Removes the document information dictionary and the XMP metadata. */
    fun setStripMetadata(strip: Boolean) {
        _state.update { it.copy(mode = CompressMode.CUSTOM, stripMetadata = strip, error = null) }
        refreshEstimate()
    }

    /** Re-runs the size estimate for the current options. */
    fun refreshEstimate() {
        estimateJob?.cancel()
        val snapshot = _state.value
        if (snapshot.path.isBlank()) return
        estimateJob = viewModelScope.launch {
            _state.update { it.copy(estimating = true, estimate = null) }
            delay(ESTIMATE_DEBOUNCE_MS)
            val estimate = withContext(Dispatchers.IO) {
                guarded {
                    val file = File(snapshot.path)
                    val bytes = file.readBytes()
                    val document = PdfDocument.open(FileSource(file)).getOrThrow()
                    try {
                        PdfCompressor.estimate(
                            document = document,
                            originalBytes = bytes,
                            options = snapshot.options,
                            decoder = AndroidImageDecoder,
                            encoder = AndroidImageEncoder
                        )
                    } finally {
                        document.close()
                    }
                }
            }
            if (!isActive) return@launch
            estimate.fold(
                onSuccess = { value -> _state.update { it.copy(estimating = false, estimate = value) } },
                onFailure = { _state.update { it.copy(estimating = false, estimate = null) } }
            )
        }
    }

    /** Runs the compression in the background. */
    fun compress() {
        val snapshot = _state.value
        if (snapshot.compressing || snapshot.path.isBlank()) return
        compressJob?.cancel()
        val run = ++compressRun
        compressJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    compressing = true,
                    cancelled = false,
                    error = null,
                    result = null,
                    saved = false
                )
            }
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val bytes = File(snapshot.path).readBytes()
                    withContext(Dispatchers.Default) {
                        val document = PdfDocument.open(FileSource(File(snapshot.path))).getOrThrow()
                        try {
                            PdfCompressor.compress(
                                document = document,
                                originalBytes = bytes,
                                options = snapshot.options,
                                decoder = AndroidImageDecoder,
                                encoder = AndroidImageEncoder
                            )
                        } finally {
                            document.close()
                        }
                    }
                }
                if (!isActive) return@launch
                outcome.fold(
                    onSuccess = { result ->
                        _state.update {
                            it.copy(result = result, hasSignatures = result.hasSignatures)
                        }
                    },
                    onFailure = { error ->
                        _state.update {
                            it.copy(error = error.message ?: "Could not compress the PDF.")
                        }
                    }
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(error = t.message ?: "Could not compress the PDF.") }
            } finally {
                if (run == compressRun) _state.update { it.copy(compressing = false) }
            }
        }
    }

    /** Stops a running compression. */
    fun cancel() {
        compressJob?.cancel()
        compressJob = null
        compressRun++
        _state.update { it.copy(compressing = false, cancelled = true) }
    }

    /** Clears the finished result so the screen can be used again. */
    fun dismissResult() {
        _state.update { it.copy(result = null, saved = false) }
    }

    /** Writes the compressed bytes to the document URI the user picked. */
    fun saveTo(uri: Uri) {
        val result = _state.value.result ?: return
        viewModelScope.launch {
            val written = withContext(Dispatchers.IO) {
                guarded {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { it.write(result.bytes) }
                        ?: error("Could not open the destination for writing.")
                }
            }
            if (!isActive) return@launch
            written.fold(
                onSuccess = { _state.update { it.copy(saved = true, error = null) } },
                onFailure = { error ->
                    _state.update { it.copy(saved = false, error = error.message ?: "Could not save the PDF.") }
                }
            )
        }
    }

    /** Runs [block], keeping a cancelled run out of the failure path. */
    private suspend fun <T> guarded(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        Result.failure(t)
    }

    private companion object {
        /** How long the options have to rest before the estimate runs. */
        const val ESTIMATE_DEBOUNCE_MS = 300L
    }
}
