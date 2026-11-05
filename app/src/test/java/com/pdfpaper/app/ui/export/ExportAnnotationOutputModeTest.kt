package com.pdfpaper.app.ui.export

import android.app.Application
import com.pdfpaper.app.print.PdfPrintAdapter
import com.pdfpaper.app.render.T68bFixtures
import com.pdfpaper.app.ui.viewer.AnnotationOutputMode
import com.pdfpaper.engine.annot.AnnotationFilter
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * T68b: Behaviour tests for annotation output mode in export ([ExportImagesViewModel])
 * and print ([PdfPrintAdapter]).
 */
class ExportAnnotationOutputModeTest {

    private lateinit var viewModel: ExportImagesViewModel
    private lateinit var document: PdfDocument

    @Before
    fun setUp() {
        viewModel = ExportImagesViewModel(Application())
        val bytes = T68bFixtures.createMixedAnnotationDoc()
        document = PdfDocument.open(ByteArraySource(bytes)).getOrThrow()
    }

    @Test
    fun exportAnnotationOutputModeDefaultsToAll() {
        val mode = viewModel.annotationOutputMode.value
        assertEquals(AnnotationOutputMode.ALL, mode)
    }

    @Test
    fun setAnnotationOutputModeVisibleOnlyUsesSavedFilter() {
        viewModel.setAnnotationOutputMode(AnnotationOutputMode.VISIBLE_ONLY)
        val mode = viewModel.annotationOutputMode.value
        assertEquals(AnnotationOutputMode.VISIBLE_ONLY, mode)
    }

    @Test
    fun setAnnotationOutputModeNoneUsesEmptyFilter() {
        viewModel.setAnnotationOutputMode(AnnotationOutputMode.NONE)
        val mode = viewModel.annotationOutputMode.value
        assertEquals(AnnotationOutputMode.NONE, mode)
    }

    @Test
    fun resolvePageRasterizerForOutputMode() {
        val rasterizer = viewModel.resolvePageRasterizer(AnnotationOutputMode.ALL)
        assertNotNull(rasterizer)
    }

    @Test
    fun pdfPrintAdapterResolvesFilterPerOutputMode() {
        val adapter = PdfPrintAdapter(
            document = document,
            outputMode = AnnotationOutputMode.ALL,
            activeFilter = AnnotationFilter.SHOW_ALL
        )
        val filter = adapter.resolveFilter()
        assertEquals(AnnotationFilter.SHOW_ALL, filter)
    }
}
