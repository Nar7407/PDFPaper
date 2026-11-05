package com.pdfpaper.app.ui.mark

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.WatermarkPreset
import com.pdfpaper.app.data.queryDisplayName
import com.pdfpaper.app.render.AndroidRasterDecoder
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.mark.MarkFont
import com.pdfpaper.engine.mark.PdfMarks
import com.pdfpaper.engine.mark.WatermarkContent
import com.pdfpaper.engine.mark.WatermarkOptions
import com.pdfpaper.engine.mark.WatermarkPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Everything the watermark screen shows. */
data class WatermarkUiState(
    override val document: MarkDocumentState = MarkDocumentState(),
    override val run: MarkRunState = MarkRunState(),
    val text: String = "CONFIDENTIAL",
    /** True when a picture is used instead of the text. */
    val useImage: Boolean = false,
    /** The bytes of the picture, in PNG or JPEG. */
    val imageBytes: ByteArray? = null,
    /** The picture, for the preview. */
    val imageBitmap: Bitmap? = null,
    val imageName: String = "",
    val fontSize: Double = 48.0,
    val opacity: Double = 0.3,
    val rotation: Double = 45.0,
    val color: MarkColorChoice = MarkColorChoice.GREY,
    val position: WatermarkPosition = WatermarkPosition.CENTER,
    /** True puts the watermark under the text of the page. */
    val behindContent: Boolean = true,
    val rangeMode: PageRangeMode = PageRangeMode.ALL,
    val rangeText: String = ""
) : MarkScreenState

/**
 * The watermark screen (T44): a text or picture watermark, where it sits, how
 * big it is, how transparent it is and which pages it goes on.
 */
class WatermarkViewModel(application: Application) : MarkViewModel<WatermarkUiState>(application) {

    override val flow = MutableStateFlow(WatermarkUiState())

    init {
        val preset = AppSettings.watermarkPreset(application)
        flow.update { state ->
            state.copy(
                text = preset.text,
                // The picture itself is not kept between runs, so the text
                // watermark is the one that comes back.
                useImage = false,
                fontSize = preset.fontSize,
                opacity = preset.opacity,
                rotation = preset.rotation,
                color = enumOrDefault(preset.color, MarkColorChoice.GREY),
                position = enumOrDefault(preset.position, WatermarkPosition.CENTER),
                behindContent = preset.behindContent,
                rangeMode = enumOrDefault(preset.rangeMode, PageRangeMode.ALL),
                rangeText = preset.rangeText
            )
        }
    }

    fun setText(text: String) = update { it.copy(text = text) }

    fun setUseImage(useImage: Boolean) = update { it.copy(useImage = useImage) }

    fun setFontSize(size: Double) = update {
        it.copy(fontSize = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE))
    }

    fun setOpacity(opacity: Double) = update {
        it.copy(opacity = opacity.coerceIn(MIN_OPACITY, MAX_OPACITY))
    }

    fun setRotation(rotation: Double) = update {
        it.copy(rotation = rotation.coerceIn(MIN_ROTATION, MAX_ROTATION))
    }

    fun setColor(color: MarkColorChoice) = update { it.copy(color = color) }

    fun setPosition(position: WatermarkPosition) = update { it.copy(position = position) }

    fun setBehindContent(behind: Boolean) = update { it.copy(behindContent = behind) }

    fun setRangeMode(mode: PageRangeMode) = update { it.copy(rangeMode = mode) }

    fun setRangeText(text: String) = update { it.copy(rangeText = text) }

    /** Reads the picture the user picked and keeps it for the watermark. */
    fun pickImage(uri: Uri) {
        viewModelScope.launch {
            val name = queryDisplayName(getApplication(), uri) ?: "Picture"
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = getApplication<Application>().contentResolver
                        .openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not read that picture.")
                    val bytes = toSupportedImageBytes(raw)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("That picture could not be shown.")
                    bytes to bitmap
                }
            }
            outcome.fold(
                onSuccess = { (bytes, bitmap) ->
                    update {
                        it.copy(
                            useImage = true,
                            imageBytes = bytes,
                            imageBitmap = bitmap,
                            imageName = name
                        )
                    }
                },
                onFailure = { error ->
                    setRun(flow.value.run.copy(error = error.message ?: "Could not read that picture."))
                }
            )
        }
    }

    /** Writes the watermark to the document. */
    fun apply() {
        val state = flow.value
        val picture = if (state.useImage) state.imageBytes else null
        if (picture == null && state.text.isBlank()) return
        val content: WatermarkContent = if (picture != null) {
            WatermarkContent.Image(picture)
        } else {
            WatermarkContent.Text(state.text)
        }
        val options = WatermarkOptions(
            content = content,
            font = fontFor(state.text),
            fontSize = state.fontSize,
            color = state.color.toPdfColor(),
            opacity = state.opacity,
            rotationDegrees = state.rotation,
            position = state.position,
            behindContent = state.behindContent,
            pages = pageSelectionFor(state.rangeMode, state.rangeText)
        )
        runMark { document -> PdfMarks.applyWatermark(document, options, AndroidRasterDecoder) }
    }

    override fun withDocument(state: WatermarkUiState, document: MarkDocumentState) =
        state.copy(document = document)

    override fun withRun(state: WatermarkUiState, run: MarkRunState) = state.copy(run = run)

    override fun persist(state: WatermarkUiState) {
        AppSettings.setWatermarkPreset(
            getApplication(),
            WatermarkPreset(
                text = state.text,
                useImage = state.useImage,
                fontSize = state.fontSize,
                opacity = state.opacity,
                rotation = state.rotation,
                color = state.color.name,
                position = state.position.name,
                behindContent = state.behindContent,
                rangeMode = state.rangeMode.name,
                rangeText = state.rangeText
            )
        )
    }

    /** The font the text is written with: the standard one for Latin text. */
    private fun fontFor(text: String): MarkFont =
        MarkFontProvider.forText(getApplication(), text)

    /**
     * The bytes of the picture, in a format the engine understands. A PNG or
     * JPEG is kept as it is; anything else is turned into a PNG.
     */
    private fun toSupportedImageBytes(raw: ByteArray): ByteArray {
        if (EditImages.decode(raw, AndroidRasterDecoder).isSuccess) return raw
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: error("That picture format is not supported.")
        try {
            val png = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) {
                "That picture could not be converted."
            }
            return png.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val MIN_FONT_SIZE = 8.0
        const val MAX_FONT_SIZE = 144.0
        const val MIN_OPACITY = 0.05
        const val MAX_OPACITY = 1.0
        const val MIN_ROTATION = -180.0
        const val MAX_ROTATION = 180.0
    }
}
