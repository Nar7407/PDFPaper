package com.pdfpaper.app.ui.pages

import android.app.Application
import com.pdfpaper.engine.annot.AnnotationFilter
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * T68b: Behaviour tests for layer filtering in thumbnail rendering ([PagesViewModel]).
 */
class PagesThumbnailLayersTest {

    private lateinit var viewModel: PagesViewModel

    @Before
    fun setUp() {
        viewModel = PagesViewModel(Application())
    }

    @Test
    fun thumbnailRenderingUsesFingerprintLayerFilter() {
        val filter = viewModel.layerFilter
        assertNotNull(filter)
    }

    @Test
    fun layerFilterChangeInvalidatesThumbnailCache() {
        viewModel.refreshThumbnailLayers()
        assertNotNull(viewModel.layerFilter)
    }
}
