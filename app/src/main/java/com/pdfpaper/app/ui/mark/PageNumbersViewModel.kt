package com.pdfpaper.app.ui.mark

import android.app.Application
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.PageNumbersPreset
import com.pdfpaper.engine.mark.HeaderFooterOptions
import com.pdfpaper.engine.mark.MarkAlign
import com.pdfpaper.engine.mark.MarkFont
import com.pdfpaper.engine.mark.NumberFormat
import com.pdfpaper.engine.mark.PdfMarks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Everything the page-number screen shows. */
data class PageNumbersUiState(
    override val document: MarkDocumentState = MarkDocumentState(),
    override val run: MarkRunState = MarkRunState(),
    val header: String = "",
    val footer: String = "{page}",
    val format: NumberFormat = NumberFormat.DECIMAL,
    val align: MarkAlign = MarkAlign.CENTER,
    val fontSize: Double = 10.0,
    val marginTop: Double = 36.0,
    val marginBottom: Double = 36.0,
    val marginLeft: Double = 54.0,
    val marginRight: Double = 54.0,
    /** The number of the first page. */
    val startNumber: Int = 1,
    /** True leaves the first page without a header or a footer. */
    val skipFirstPage: Boolean = false,
    val rangeMode: PageRangeMode = PageRangeMode.ALL,
    val rangeText: String = ""
) : MarkScreenState

/**
 * The page-number screen (T44): a header, a footer and the page numbers, with
 * the margins they are written in and the pages they go on.
 */
class PageNumbersViewModel(application: Application) : MarkViewModel<PageNumbersUiState>(application) {

    override val flow = MutableStateFlow(PageNumbersUiState())

    init {
        val preset = AppSettings.pageNumbersPreset(application)
        flow.update { state ->
            state.copy(
                header = preset.header,
                footer = preset.footer,
                format = enumOrDefault(preset.format, NumberFormat.DECIMAL),
                align = enumOrDefault(preset.align, MarkAlign.CENTER),
                fontSize = preset.fontSize,
                marginTop = preset.marginTop,
                marginBottom = preset.marginBottom,
                marginLeft = preset.marginLeft,
                marginRight = preset.marginRight,
                startNumber = preset.startNumber,
                skipFirstPage = preset.skipFirstPage,
                rangeMode = enumOrDefault(preset.rangeMode, PageRangeMode.ALL),
                rangeText = preset.rangeText
            )
        }
    }

    fun setHeader(header: String) = update { it.copy(header = header) }

    fun setFooter(footer: String) = update { it.copy(footer = footer) }

    fun setFormat(format: NumberFormat) = update { it.copy(format = format) }

    fun setAlign(align: MarkAlign) = update { it.copy(align = align) }

    fun setFontSize(size: Double) = update {
        it.copy(fontSize = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE))
    }

    fun setMarginTop(margin: Double) = update {
        it.copy(marginTop = margin.coerceIn(MIN_MARGIN, MAX_MARGIN))
    }

    fun setMarginBottom(margin: Double) = update {
        it.copy(marginBottom = margin.coerceIn(MIN_MARGIN, MAX_MARGIN))
    }

    fun setMarginLeft(margin: Double) = update {
        it.copy(marginLeft = margin.coerceIn(MIN_MARGIN, MAX_MARGIN))
    }

    fun setMarginRight(margin: Double) = update {
        it.copy(marginRight = margin.coerceIn(MIN_MARGIN, MAX_MARGIN))
    }

    fun setStartNumber(number: Int) = update {
        it.copy(startNumber = number.coerceIn(MIN_START_NUMBER, MAX_START_NUMBER))
    }

    fun setSkipFirstPage(skip: Boolean) = update { it.copy(skipFirstPage = skip) }

    fun setRangeMode(mode: PageRangeMode) = update { it.copy(rangeMode = mode) }

    fun setRangeText(text: String) = update { it.copy(rangeText = text) }

    /** Writes the header, the footer and the page numbers to the document. */
    fun apply() {
        val state = flow.value
        if (state.header.isBlank() && state.footer.isBlank()) return
        val options = HeaderFooterOptions(
            header = state.header.ifBlank { null },
            footer = state.footer.ifBlank { null },
            format = state.format,
            font = fontFor(state.header + state.footer),
            fontSize = state.fontSize,
            align = state.align,
            marginTop = state.marginTop,
            marginBottom = state.marginBottom,
            marginLeft = state.marginLeft,
            marginRight = state.marginRight,
            startNumber = state.startNumber,
            skipFirstPage = state.skipFirstPage,
            pages = pageSelectionFor(state.rangeMode, state.rangeText)
        )
        runMark { document ->
            PdfMarks.applyHeaderFooter(document, options, state.document.fileName)
        }
    }

    override fun withDocument(state: PageNumbersUiState, document: MarkDocumentState) =
        state.copy(document = document)

    override fun withRun(state: PageNumbersUiState, run: MarkRunState) = state.copy(run = run)

    override fun persist(state: PageNumbersUiState) {
        AppSettings.setPageNumbersPreset(
            getApplication(),
            PageNumbersPreset(
                header = state.header,
                footer = state.footer,
                format = state.format.name,
                align = state.align.name,
                fontSize = state.fontSize,
                marginTop = state.marginTop,
                marginBottom = state.marginBottom,
                marginLeft = state.marginLeft,
                marginRight = state.marginRight,
                startNumber = state.startNumber,
                skipFirstPage = state.skipFirstPage,
                rangeMode = state.rangeMode.name,
                rangeText = state.rangeText
            )
        )
    }

    /** The font the lines are written with: the standard one for Latin text. */
    private fun fontFor(text: String): MarkFont =
        MarkFontProvider.forText(getApplication(), text)

    private companion object {
        const val MIN_FONT_SIZE = 6.0
        const val MAX_FONT_SIZE = 36.0
        const val MIN_MARGIN = 0.0
        const val MAX_MARGIN = 144.0
        const val MIN_START_NUMBER = 0
        const val MAX_START_NUMBER = 9999
    }
}
