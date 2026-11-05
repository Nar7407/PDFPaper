package com.pdfpaper.engine.render

import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.graphics.ClipPath
import com.pdfpaper.engine.graphics.DisplayList
import com.pdfpaper.engine.graphics.DrawRasterImage
import com.pdfpaper.engine.graphics.DrawTextRun
import com.pdfpaper.engine.graphics.FillPath
import com.pdfpaper.engine.graphics.FillStrokePath
import com.pdfpaper.engine.graphics.LineStyle
import com.pdfpaper.engine.graphics.MoveTo
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.graphics.PdfPath
import com.pdfpaper.engine.graphics.RestoreGraphicsState
import com.pdfpaper.engine.graphics.SaveGraphicsState
import com.pdfpaper.engine.graphics.StrokePath
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.image.RasterImage
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.text.PdfTextRun
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T9 acceptance: replaying a display list calls the [Renderer] in content
 * order with the right primitives, so the platform surface (Canvas in :app)
 * only has to draw what it is given.
 */
class DisplayListRendererTest {

    private class Recorder : Renderer {
        val events = ArrayList<String>()
        val matrices = ArrayList<PdfMatrix>()

        override fun saveState() {
            events += "save"
        }

        override fun restoreState() {
            events += "restore"
        }

        override fun clip(path: PdfPath, matrix: PdfMatrix, evenOdd: Boolean) {
            events += "clip(evenOdd=$evenOdd,segments=${path.segments.size})"
            matrices += matrix
        }

        override fun fillPath(
            path: PdfPath,
            matrix: PdfMatrix,
            color: PdfColor,
            alpha: Double,
            evenOdd: Boolean
        ) {
            events += "fill(${color.red},${color.green},${color.blue}@$alpha)"
            matrices += matrix
        }

        override fun strokePath(
            path: PdfPath,
            matrix: PdfMatrix,
            color: PdfColor,
            alpha: Double,
            lineStyle: LineStyle
        ) {
            events += "stroke(w=${lineStyle.width}@$alpha)"
            matrices += matrix
        }

        override fun drawImage(image: RasterImage, matrix: PdfMatrix, fillColor: PdfColor, alpha: Double) {
            events += "image(${image.width}x${image.height},stencil=${image.stencil})"
            matrices += matrix
        }

        override fun drawText(run: PdfTextRun, matrix: PdfMatrix) {
            events += "text(${run.text})"
            matrices += matrix
        }

        override fun setBlendMode(mode: String?) {
            events += "blend($mode)"
        }
    }

    private fun openPdf(objects: List<TestObj>): PdfDocument =
        PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow()

    private fun page(content: String, resourceEntries: String = ""): List<TestObj> = listOf(
        TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
        TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
        TestObj(
            3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << $resourceEntries >> /Contents 4 0 R >>"
        ),
        TestObj(
            4, 0,
            ("<< /Length ${content.toByteArray(StandardCharsets.ISO_8859_1).size} >>\nstream\n$content\nendstream")
        )
    )

    private fun display(content: String, resources: String = ""): DisplayList =
        openPdf(page(content, resources)).use { doc -> doc.pages().first().buildDisplayList() }

    @Test
    fun replaysContentStreamInOrder() {
        val recorder = Recorder()
        display("q 0 0 50 50 re W n 10 10 20 20 re f Q 0 0 10 10 re B").render(recorder)

        assertEquals(
            listOf(
                "save",
                "clip(evenOdd=false,segments=5)",
                "fill(0.0,0.0,0.0@1.0)",
                "restore",
                "fill(0.0,0.0,0.0@1.0)",
                "stroke(w=1.0@1.0)"
            ),
            recorder.events
        )
    }

    @Test
    fun fillStrokeCarriesSeparateColoursAndAlpha() {
        val recorder = Recorder()
        display("1 0 0 rg /GS1 gs 0 0 10 10 re B", "/ExtGState << /GS1 << /ca 0.4 /CA 0.9 >> >>")
            .render(recorder)
        assertEquals(listOf("fill(1.0,0.0,0.0@0.4)", "stroke(w=1.0@0.9)"), recorder.events)
    }

    @Test
    fun clipUsesEvenOddFlagAndMatrix() {
        val recorder = Recorder()
        display("q 1 0 0 2 5 6 cm 0 0 10 10 re W* n Q 0 0 1 1 re f").render(recorder)
        assertEquals("clip(evenOdd=true,segments=5)", recorder.events[1])
        // matrices[0] is the clip (issued under the cm), matrices[1] the fill
        // after Q restored the identity CTM.
        assertEquals(PdfMatrix(1.0, 0.0, 0.0, 2.0, 5.0, 6.0), recorder.matrices[0])
        assertEquals(PdfMatrix.IDENTITY, recorder.matrices[1])
    }

    @Test
    fun emptyPathsProduceNoPaintCalls() {
        val empty = PdfPath(emptyList())
        val list = DisplayList(
            listOf(
                FillPath(empty, PdfMatrix.IDENTITY, PdfColor.BLACK, 1.0, false),
                StrokePath(empty, PdfMatrix.IDENTITY, PdfColor.BLACK, 1.0, LineStyle()),
                ClipPath(empty, PdfMatrix.IDENTITY, false),
                FillStrokePath(
                    empty, PdfMatrix.IDENTITY, PdfColor.BLACK, 1.0,
                    PdfColor.BLACK, 1.0, false, LineStyle()
                )
            ),
            emptyList()
        )
        val recorder = Recorder()
        renderDisplayList(list, recorder)
        assertTrue(recorder.events.isEmpty(), "expected no draw calls, got ${recorder.events}")
    }

    @Test
    fun textAndImageCommandsAreForwarded() {
        val run = PdfTextRun("Hi", 12.0, "Helvetica", 10.0, 20.0, 30.0, 32.0)
        val image = RasterImage(2, 1, ByteArray(8), stencil = true)
        val list = DisplayList(
            listOf(
                DrawTextRun(run, PdfMatrix.IDENTITY),
                DrawRasterImage(image, PdfMatrix.scaling(10.0, 10.0), PdfColor(1.0, 0.0, 0.0), 1.0)
            ),
            emptyList()
        )
        val recorder = Recorder()
        list.render(recorder)
        assertEquals(listOf("text(Hi)", "image(2x1,stencil=true)"), recorder.events)
        assertEquals(1, recorder.matrices.count { it == PdfMatrix.scaling(10.0, 10.0) })
    }

    @Test
    fun blankTextRunsAreSkipped() {
        val run = PdfTextRun("", 12.0, null, 0.0, 0.0, 0.0, 0.0)
        val recorder = Recorder()
        renderDisplayList(
            DisplayList(listOf(DrawTextRun(run, PdfMatrix.IDENTITY)), emptyList()),
            recorder
        )
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun invisibleAndClipOnlyTextIsNotPainted() {
        val invisible = PdfTextRun("secret", 12.0, null, 0.0, 0.0, 6.0, 12.0, renderMode = 3)
        val clipOnly = PdfTextRun("clip", 12.0, null, 0.0, 0.0, 6.0, 12.0, renderMode = 7)
        val visible = PdfTextRun("shown", 12.0, null, 0.0, 0.0, 6.0, 12.0)
        val recorder = Recorder()
        renderDisplayList(
            DisplayList(
                listOf(
                    DrawTextRun(invisible, PdfMatrix.IDENTITY),
                    DrawTextRun(clipOnly, PdfMatrix.IDENTITY),
                    DrawTextRun(visible, PdfMatrix.IDENTITY)
                ),
                emptyList()
            ),
            recorder
        )
        assertEquals(listOf("text(shown)"), recorder.events)
    }

    @Test
    fun blendModeCapturedAtSaveIsForwarded() {
        val recorder = Recorder()
        // /BM set before q is captured on the save command (T8 behaviour).
        display("/GS1 gs q 0 0 10 10 re f Q", "/ExtGState << /GS1 << /BM /Multiply >> >>")
            .render(recorder)
        assertEquals(
            listOf("blend(Multiply)", "save", "blend(Multiply)", "fill(0.0,0.0,0.0@1.0)", "restore"),
            recorder.events
        )
    }

    @Test
    fun blendModeSetAfterSaveIsForwardedAtTheGs() {
        val recorder = Recorder()
        display("q /GS1 gs 0 0 10 10 re f Q", "/ExtGState << /GS1 << /BM /Multiply >> >>")
            .render(recorder)
        assertEquals(
            listOf("save", "blend(Multiply)", "fill(0.0,0.0,0.0@1.0)", "restore"),
            recorder.events
        )
    }

    @Test
    fun saveRestoreBracketAppearEvenWithNoPaint() {
        val recorder = Recorder()
        renderDisplayList(
            DisplayList(
                listOf(
                    SaveGraphicsState(PdfMatrix.IDENTITY),
                    RestoreGraphicsState(PdfMatrix.IDENTITY)
                ),
                emptyList()
            ),
            recorder
        )
        assertEquals(listOf("save", "restore"), recorder.events)
    }

    @Test
    fun singleSegmentPathIsPainted() {
        val path = PdfPath(listOf(MoveTo(1.0, 2.0)))
        val recorder = Recorder()
        renderDisplayList(
            DisplayList(listOf(FillPath(path, PdfMatrix.IDENTITY, PdfColor.WHITE, 1.0, true)), emptyList()),
            recorder
        )
        assertEquals(listOf("fill(1.0,1.0,1.0@1.0)"), recorder.events)
    }
}
