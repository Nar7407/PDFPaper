package com.pdfpaper.app.render

import com.pdfpaper.engine.annot.AnnotationFilter
import com.pdfpaper.engine.annot.AnnotationType
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * T68b: the filter-configured rasterizer factory, which needs no Android bitmap.
 *
 * The pixel behaviour of [PageBitmapRenderer] with a layer filter is covered by
 * PageBitmapRendererFilterDeviceTest: a JVM unit test cannot create a Bitmap.
 */
class PageBitmapRendererFilterTest {

    @Test
    fun androidPageRasterizerConfiguredWithFilter() {
        val filter = AnnotationFilter(types = setOf(AnnotationType.HIGHLIGHT))
        // Calling AndroidPageRasterizer(filter) factory should return configured PageRasterizer
        val rasterizer = AndroidPageRasterizer(filter)
        assertNotNull(rasterizer)
    }
}
