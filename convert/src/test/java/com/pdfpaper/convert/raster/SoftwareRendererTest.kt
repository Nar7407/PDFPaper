package com.pdfpaper.convert.raster

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.graphics.LineStyle
import com.pdfpaper.engine.graphics.LineTo
import com.pdfpaper.engine.graphics.MoveTo
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.graphics.PdfPath
import com.pdfpaper.engine.text.PdfTextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SoftwareRendererTest {

    private val red = PdfColor(1.0, 0.0, 0.0)
    private val black = PdfColor(0.0, 0.0, 0.0)

    private fun pixel(renderer: SoftwareRenderer, x: Int, y: Int): Int =
        renderer.raster.pixels[y * renderer.width + x]

    @Test
    fun `fills a rectangle and leaves the outside alone`() {
        val renderer = SoftwareRenderer(100, 100, PdfMatrix.IDENTITY)
        renderer.fillPath(PdfPath.rectangle(10.0, 10.0, 50.0, 50.0), PdfMatrix.IDENTITY, red, 1.0, false)
        assertEquals(0xFFFF0000.toInt(), pixel(renderer, 30, 30))
        assertEquals(Raster.WHITE, pixel(renderer, 5, 5))
        assertEquals(Raster.WHITE, pixel(renderer, 60, 30))
    }

    @Test
    fun `base matrix scales geometry`() {
        val renderer = SoftwareRenderer(100, 100, PdfMatrix.scaling(2.0, 2.0))
        renderer.fillPath(PdfPath.rectangle(5.0, 5.0, 20.0, 20.0), PdfMatrix.IDENTITY, red, 1.0, false)
        // 5..20 user units becomes 10..40 device pixels.
        assertEquals(0xFFFF0000.toInt(), pixel(renderer, 25, 25))
        assertEquals(Raster.WHITE, pixel(renderer, 5, 5))
    }

    @Test
    fun `clip limits later fills`() {
        val renderer = SoftwareRenderer(100, 100, PdfMatrix.IDENTITY)
        renderer.clip(PdfPath.rectangle(0.0, 0.0, 50.0, 50.0), PdfMatrix.IDENTITY, false)
        renderer.fillPath(PdfPath.rectangle(0.0, 0.0, 100.0, 100.0), PdfMatrix.IDENTITY, red, 1.0, false)
        assertEquals(0xFFFF0000.toInt(), pixel(renderer, 25, 25))
        assertEquals(Raster.WHITE, pixel(renderer, 75, 75))
    }

    @Test
    fun `restore drops the clip`() {
        val renderer = SoftwareRenderer(100, 100, PdfMatrix.IDENTITY)
        renderer.saveState()
        renderer.clip(PdfPath.rectangle(0.0, 0.0, 50.0, 50.0), PdfMatrix.IDENTITY, false)
        renderer.restoreState()
        renderer.fillPath(PdfPath.rectangle(0.0, 0.0, 100.0, 100.0), PdfMatrix.IDENTITY, red, 1.0, false)
        assertEquals(0xFFFF0000.toInt(), pixel(renderer, 75, 75))
    }

    @Test
    fun `strokes a thick line`() {
        val renderer = SoftwareRenderer(100, 100, PdfMatrix.IDENTITY)
        val path = PdfPath(listOf(MoveTo(10.0, 50.0), LineTo(90.0, 50.0)))
        renderer.strokePath(path, PdfMatrix.IDENTITY, black, 1.0, LineStyle(width = 10.0))
        assertEquals(0xFF000000.toInt(), pixel(renderer, 50, 50))
        assertNotEquals(0xFF000000.toInt(), pixel(renderer, 50, 80))
    }

    @Test
    fun `even-odd fill leaves a hole`() {
        val renderer = SoftwareRenderer(100, 100, PdfMatrix.IDENTITY)
        val outer = PdfPath.rectangle(10.0, 10.0, 90.0, 90.0).segments +
            PdfPath.rectangle(40.0, 40.0, 60.0, 60.0).segments
        renderer.fillPath(PdfPath(outer), PdfMatrix.IDENTITY, red, 1.0, true)
        assertEquals(0xFFFF0000.toInt(), pixel(renderer, 20, 20))
        assertEquals(Raster.WHITE, pixel(renderer, 50, 50))
    }

    @Test
    fun `text is counted, not drawn`() {
        val renderer = SoftwareRenderer(100, 100, PdfMatrix.IDENTITY)
        val run = PdfTextRun(
            text = "Hi",
            fontSize = 12.0,
            fontName = "Helvetica",
            x0 = 0.0, y0 = 0.0, x1 = 10.0, y1 = 12.0
        )
        renderer.drawText(run, PdfMatrix.IDENTITY)
        assertEquals(1, renderer.textRunsSkipped)
        assertEquals(Raster.WHITE, pixel(renderer, 50, 50))
    }
}
