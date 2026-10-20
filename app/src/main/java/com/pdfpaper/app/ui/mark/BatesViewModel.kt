package com.pdfpaper.app.ui.mark

import android.app.Application
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.BatesPreset
import com.pdfpaper.engine.mark.BatesOptions
import com.pdfpaper.engine.mark.BatesPlacement
import com.pdfpaper.engine.mark.MarkFont
import com.pdfpaper.engine.mark.PdfMarks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Everything the Bates-number screen shows. */
data class BatesUiState(
    override val document: MarkDocumentState = MarkDocumentState(),
    override val run: MarkRunState = MarkRunState(),
    val prefix: String = "",
    val suffix: String = "",
    /** How many digits the number has. */
    val digits: Int = 6,
    /** The number of the first page. */
    val startNumber: Int = 1,
    val placement: BatesPlacement = BatesPlacement.BOTTOM_RIGHT,
    val fontSize: Double = 9.0,
    val rangeMode: PageRangeMode = PageRangeMode.ALL,
    val rangeText: String = ""
) : MarkScreenState

/**
 * The Bates-number screen (T44): a fixed prefix, a number that counts up and
 * a fixed suffix, stamped on every page for legal files.
 */
class BatesViewModel(application: Application) : MarkViewModel<BatesUiState>(application) {

    override val flow = MutableStateFlow(BatesUiState())

    init {
        val preset = AppSettings.batesPreset(application)
        flow.update { state ->
            state.copy(
                prefix = preset.prefix,
                suffix = preset.suffix,
                digits = preset.digits,
                startNumber = preset.startNumber,
                placement = enumOrDefault(preset.placement, BatesPlacement.BOTTOM_RIGHT),
                fontSize = preset.fontSize,
                rangeMode = enumOrDefault(preset.rangeMode, PageRangeMode.ALL),
                rangeText = preset.rangeText
            )
        }
    }

    fun setPrefix(prefix: String) = update { it.copy(prefix = prefix) }

    fun setSuffix(suffix: String) = update { it.copy(suffix = suffix) }

    fun setDigits(digits: Int) = update {
        it.copy(digits = digits.coerceIn(MIN_DIGITS, MAX_DIGITS))
    }

    fun setStartNumber(number: Int) = update {
        it.copy(startNumber = number.coerceIn(MIN_START_NUMBER, MAX_START_NUMBER))
    }

    fun setPlacement(placement: BatesPlacement) = update { it.copy(placement = placement) }

    fun setFontSize(size: Double) = update {
        it.copy(fontSize = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE))
    }

    fun setRangeMode(mode: PageRangeMode) = update { it.copy(rangeMode = mode) }

    fun setRangeText(text: String) = update { it.copy(rangeText = text) }

    /** Writes the Bates numbers to the document. */
    fun apply() {
        val state = flow.value
        val options = BatesOptions(
            prefix = state.prefix,
            digits = state.digits,
            startNumber = state.startNumber,
            suffix = state.suffix,
            placement = state.placement,
            font = fontFor(state.prefix + state.suffix),
            fontSize = state.fontSize,
            pages = pageSelectionFor(state.rangeMode, state.rangeText)
        )
        runMark { document -> PdfMarks.applyBates(document, options) }
    }

    override fun withDocument(state: BatesUiState, document: MarkDocumentState) =
        state.copy(document = document)

    override fun withRun(state: BatesUiState, run: MarkRunState) = state.copy(run = run)

    override fun persist(state: BatesUiState) {
        AppSettings.setBatesPreset(
            getApplication(),
            BatesPreset(
                prefix = state.prefix,
                suffix = state.suffix,
                digits = state.digits,
                startNumber = state.startNumber,
                placement = state.placement.name,
                fontSize = state.fontSize,
                rangeMode = state.rangeMode.name,
                rangeText = state.rangeText
            )
        )
    }

    /** The font the number is written with: the standard one for Latin text. */
    private fun fontFor(text: String): MarkFont =
        MarkFontProvider.forText(getApplication(), text)

    private companion object {
        const val MIN_DIGITS = 1
        const val MAX_DIGITS = 12
        const val MIN_FONT_SIZE = 6.0
        const val MAX_FONT_SIZE = 36.0
        const val MIN_START_NUMBER = 0
        const val MAX_START_NUMBER = 999_999
    }
}
