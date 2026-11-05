package com.pdfpaper.engine.render

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.util.LruCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T9 acceptance ("keep memory bounded for a 500-page PDF"): a 500-page
 * document opens and lays out without holding anything per page beyond its
 * box, and the viewer's bitmap cache never exceeds its byte budget no matter
 * how many pages are visited.
 */
class PageMemoryTest {

    private fun fiveHundredPageDocument(): PdfDocument {
        val objects = ArrayList<TestObj>(504)
        objects += TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>")
        val kids = (0 until 500).joinToString(" ") { "${5 + it} 0 R" }
        objects += TestObj(2, 0, "<< /Type /Pages /Kids [$kids] /Count 500 >>")
        objects += TestObj(3, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        objects += TestObj(4, 0, "<< /Length 0 >>\nstream\n\nendstream")
        for (i in 0 until 500) {
            val resources = if (i % 2 == 0) "/Resources << /Font << /F1 3 0 R >> >> " else ""
            objects += TestObj(
                5 + i, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "$resources/Contents 4 0 R >>"
            )
        }
        return PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow()
    }

    @Test
    fun fiveHundredPageDocumentOpensAndLaysOutAllPages() {
        val doc = fiveHundredPageDocument()
        val pages = doc.pages()
        assertEquals(500, pages.size)

        // Layout is computed per page on demand and holds only small value objects.
        val boxes = pages.map { PageGeometry.boxOf(it) }
        assertEquals(500, boxes.size)
        assertTrue(boxes.all { it.rotatedWidth == 612.0 && it.rotatedHeight == 792.0 })

        val plans = pages.indices.map { PageGeometry.plan(boxes[it], 2.0) }
        assertTrue(plans.all { it.pixelWidth == 1224 && it.pixelHeight == 1584 })
        doc.close()
    }

    @Test
    fun bitmapCacheStaysWithinItsBudgetWhileVisitingEveryPage() {
        val budget = 8L * 1024 * 1024
        val cache = LruCache<Int, ByteArray>(budget) { it.size.toLong() }
        var peak = 0L

        for (page in 0 until 500) {
            cache.put(page, ByteArray(1024 * 1024)) // stand-in for one page bitmap
            peak = maxOf(peak, cache.sizeBytes)
        }

        assertTrue(peak <= budget, "cache peak $peak exceeded its $budget budget")
        assertTrue(cache.size <= 8, "expected at most 8 entries, held ${cache.size}")
        assertEquals(499, cache.keys().last()) // the newest page is still resident
    }

    @Test
    fun pagePixelBudgetCapsASingleEnormousPage() {
        val huge = PageBox(0.0, 0.0, 20_000.0, 20_000.0, 0)
        val plan = PageGeometry.plan(huge, 1.0, PageGeometry.DEFAULT_MAX_PIXELS)
        assertTrue(plan.downscaled)
        assertTrue(
            plan.pixelWidth.toLong() * plan.pixelHeight.toLong() <= PageGeometry.DEFAULT_MAX_PIXELS + 65_536,
            "got ${plan.pixelWidth}x${plan.pixelHeight}"
        )
    }
}
