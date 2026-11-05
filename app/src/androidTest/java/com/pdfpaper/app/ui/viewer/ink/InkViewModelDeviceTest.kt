package com.pdfpaper.app.ui.viewer.ink

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.AutosaveMode
import com.pdfpaper.app.ui.viewer.ViewerViewModel
import com.pdfpaper.engine.annot.InkSpec
import com.pdfpaper.engine.annot.InkTool
import com.pdfpaper.engine.annot.PdfPaperInk
import com.pdfpaper.engine.annot.StoredInkStroke
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.crop.CropRequest
import com.pdfpaper.engine.crop.CropSpec
import com.pdfpaper.engine.crop.PdfCrop
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.write.PdfPageOps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * T54 on-device acceptance: the stylus ink state machine on a real Android
 * runtime — draw, undo/redo, point and stroke erasers, lasso select/move,
 * save as `/Ink`, and reopening the result as editable pressure strokes.
 *
 * The viewer's own coordinate mapping is used throughout (fractions of the
 * fitted page), not raw PDF coordinates, so a rotated or cropped page is
 * exercised by the same path the finger/stylus takes.
 */
@RunWith(AndroidJUnit4::class)
class InkViewModelDeviceTest {

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    /** The app ships with in-place saving; do not leave the test mode behind. */
    @After
    fun restoreAutosaveMode() {
        AppSettings.setAutosaveMode(context, AutosaveMode.IN_PLACE)
    }

    private fun newModel(): Pair<ViewModelStore, ViewerViewModel> {
        // T55 writes a pending edit back to a writable file in place by
        // default. These tests need the bytes a save produces, so they use the
        // "Ask each time" mode, which hands the verified export to the caller.
        AppSettings.setAutosaveMode(context, AutosaveMode.ASK)
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ViewerViewModel(context) as T
        }
        return store to ViewModelProvider(store, factory)[ViewerViewModel::class.java]
    }

    private fun File.writePdf(bytes: ByteArray): File {
        writeBytes(bytes)
        return this
    }

    /** Keeps a copy for the manual/independent-viewer acceptance run. */
    private fun publish(bytes: ByteArray, name: String) {
        val dir = context.getExternalFilesDir(null) ?: return
        runCatching { dir.mkdirs(); File(dir, name).writeBytes(bytes) }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun open(viewModel: ViewerViewModel, file: File) {
        viewModel.load(file.absolutePath, file.name)
        waitUntil("the document to open") {
            !viewModel.state.value.isLoading
        }
        assertEquals(null, viewModel.state.value.error)
        assertEquals(1, viewModel.state.value.pageCount)
    }

    private fun beginInk(viewModel: ViewerViewModel) {
        viewModel.setEditMode(true)
        viewModel.setTool(com.pdfpaper.app.ui.viewer.AnnotationTool.INK)
        viewModel.ensureInkLoaded()
        waitUntil("the existing ink to load") { !viewModel.ink.value.loading }
    }

    private fun drawStroke(
        viewModel: ViewerViewModel,
        fromX: Float,
        toX: Float,
        y: Float,
        pressureStart: Float = 0.2f,
        pressureEnd: Float = 1.0f
    ) {
        viewModel.startInkStroke(0, fromX, y, pressureStart, 24f)
        viewModel.extendInkStroke((fromX + toX) / 2f, y, (pressureStart + pressureEnd) / 2f, 18f)
        viewModel.extendInkStroke(toX, y, pressureEnd, 12f)
        viewModel.finishInkStroke(false)
    }

    private fun save(viewModel: ViewerViewModel): ByteArray {
        viewModel.save()
        waitUntil("the saved PDF") {
            viewModel.export.value != null || viewModel.message.value != null
        }
        assertEquals(null, viewModel.message.value)
        return viewModel.export.value!!.bytes
    }

    private fun inkAnnotations(bytes: ByteArray): List<StoredInkStroke> =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow().use { document ->
            document.pages().flatMap { page ->
                page.annotations().flatMap { annotation ->
                    PdfPaperInk.read(annotation)
                }
            }
        }

    @Test
    fun drawUndoRedoErasersLassoAndEditableSave() {
        val source = File(context.cacheDir, "ink-source-${System.nanoTime()}.pdf")
        val saved = File(context.cacheDir, "ink-saved-${System.nanoTime()}.pdf")
        val reopened = File(context.cacheDir, "ink-reopened-${System.nanoTime()}.pdf")
        source.writePdf(PdfBuilder(PageSize.A4).apply { addPage() }.build())

        val (store, viewModel) = newModel()
        try {
            open(viewModel, source)
            beginInk(viewModel)
            viewModel.setInkColor(PdfColor(0.86, 0.12, 0.12))
            viewModel.setInkWidth(3.0)

            // Pen: draw, then undo and redo the stroke.
            drawStroke(viewModel, 0.2f, 0.8f, 0.5f)
            assertEquals(1, viewModel.ink.value.strokes.size)
            assertTrue(viewModel.ink.value.canUndo)
            viewModel.undoAny()
            assertEquals(0, viewModel.ink.value.strokes.size)
            assertTrue(viewModel.ink.value.canRedo)
            viewModel.redoAny()
            assertEquals(1, viewModel.ink.value.strokes.size)

            // Highlighter: a distinct tool and opacity beside the pen.
            viewModel.setInkColor(PdfColor(1.0, 0.90, 0.20))
            viewModel.setInkMode(InkToolMode.HIGHLIGHTER)
            drawStroke(viewModel, 0.2f, 0.8f, 0.35f)
            assertEquals(2, viewModel.ink.value.strokes.size)
            assertEquals(InkTool.HIGHLIGHTER, viewModel.ink.value.strokes.last().tool)
            assertEquals(InkPalette.HIGHLIGHTER_OPACITY, viewModel.ink.value.strokes.last().opacity, 0.0001)

            // Point eraser taps the pen line and removes only that stroke.
            viewModel.setInkMode(InkToolMode.ERASER_POINT)
            viewModel.startInkStroke(0, 0.5f, 0.5f, 1f, 0f)
            viewModel.finishInkStroke(false)
            assertEquals(1, viewModel.ink.value.strokes.size)
            assertEquals(InkTool.HIGHLIGHTER, viewModel.ink.value.strokes.first().tool)
            viewModel.undoAny()
            assertEquals(2, viewModel.ink.value.strokes.size)

            // Stroke eraser drags across the pen line.
            viewModel.setInkMode(InkToolMode.ERASER_STROKE)
            viewModel.startInkStroke(0, 0.4f, 0.55f, 1f, 0f)
            viewModel.extendInkStroke(0.5f, 0.5f, 1f, 0f)
            viewModel.extendInkStroke(0.6f, 0.45f, 1f, 0f)
            viewModel.finishInkStroke(false)
            assertEquals(1, viewModel.ink.value.strokes.size)
            viewModel.undoAny()
            assertEquals(2, viewModel.ink.value.strokes.size)

            // Stylus button: while any tool is active, the button erases.
            viewModel.setInkMode(InkToolMode.PEN)
            viewModel.setInkColor(PdfColor(0.10, 0.36, 0.88))
            drawStroke(viewModel, 0.1f, 0.9f, 0.7f)
            assertEquals(3, viewModel.ink.value.strokes.size)
            viewModel.startInkStroke(0, 0.5f, 0.7f, 1f, 0f, stylusButton = true)
            viewModel.finishInkStroke(false)
            assertEquals(2, viewModel.ink.value.strokes.size)
            viewModel.undoAny()
            assertEquals(3, viewModel.ink.value.strokes.size)

            // Lasso: circle the highlighter, then move the selection.
            viewModel.setInkMode(InkToolMode.LASSO)
            viewModel.startInkStroke(0, 0.1f, 0.25f, 1f, 0f)
            viewModel.extendInkStroke(0.9f, 0.25f, 1f, 0f)
            viewModel.extendInkStroke(0.9f, 0.45f, 1f, 0f)
            viewModel.extendInkStroke(0.1f, 0.45f, 1f, 0f)
            viewModel.extendInkStroke(0.1f, 0.25f, 1f, 0f)
            viewModel.finishInkStroke(false)
            assertEquals(1, viewModel.ink.value.selected.size)
            val selectedId = viewModel.ink.value.selected.first()
            val before = viewModel.ink.value.strokes.first { it.id == selectedId }.samples.first()

            viewModel.startInkStroke(0, 0.5f, 0.35f, 1f, 0f)
            viewModel.extendInkStroke(0.6f, 0.35f, 1f, 0f)
            viewModel.finishInkStroke(false)
            val after = viewModel.ink.value.strokes.first { it.id == selectedId }.samples.first()
            assertTrue(after.x > before.x + 5.0)
            assertEquals(before.y, after.y, 0.001)

            // Selection deletes the moved highlighter, and undo brings it back.
            viewModel.setInkMode(InkToolMode.LASSO)
            viewModel.selectAllInkOnPage(0)
            viewModel.deleteSelectedInk()
            assertEquals(0, viewModel.ink.value.strokes.size)
            viewModel.undoAny()
            assertEquals(3, viewModel.ink.value.strokes.size)

            // Save the three strokes as real `/Ink` annotations.
            val bytes = save(viewModel)
            saved.writePdf(bytes)
            publish(bytes, "ink-acceptance.pdf")
            val stored = inkAnnotations(bytes)
            assertEquals(3, stored.size)
            val pen = stored.first { it.points.any { p -> p.tilt > 0.0 } }
            assertTrue(pen.points.size >= 2)
            assertTrue(pen.points.any { abs(it.tilt - 12.0) < 0.001 || abs(it.tilt - 24.0) < 0.001 })
            assertTrue(stored.any { it.points.size >= 2 })
        } finally {
            store.clear()
        }

        // Reopen: the saved strokes are editable again with pressure and tilt.
        val (reopenStore, reopenModel) = newModel()
        try {
            open(reopenModel, saved)
            beginInk(reopenModel)
            assertEquals(3, reopenModel.ink.value.strokes.size)
            val restoredHighlighter = reopenModel.ink.value.strokes.first { it.highlighter }
            assertEquals(InkPalette.HIGHLIGHTER_WIDTH, restoredHighlighter.width, 0.0001)
            assertEquals(InkPalette.HIGHLIGHTER_OPACITY, restoredHighlighter.opacity, 0.0001)
            val restoredPen = reopenModel.ink.value.strokes.first { !it.highlighter }
            assertTrue(restoredPen.samples.any { abs(it.pressure - 0.2) < 0.05 || abs(it.pressure - 1.0) < 0.05 })

            // Erase one saved stroke and save again; the old annotation must go.
            val target = reopenModel.ink.value.strokes.first { it.highlighter }
            val middle = target.samples[target.samples.size / 2]
            val plan = PageGeometry.plan(reopenModel.pageBoxes.value[0], 1.0)
            val fraction = InkTransform.fractionOf(plan, middle.x, middle.y)!!
            reopenModel.setInkMode(InkToolMode.ERASER_POINT)
            reopenModel.startInkStroke(0, fraction.first, fraction.second, 1f, 0f)
            reopenModel.finishInkStroke(false)
            assertEquals(2, reopenModel.ink.value.strokes.size)
            reopened.writePdf(save(reopenModel))
            publish(reopened.readBytes(), "ink-erased-acceptance.pdf")
            assertEquals(2, inkAnnotations(reopened.readBytes()).size)
        } finally {
            reopenStore.clear()
        }
        assertEquals(
            2,
            PdfDocument.open(ByteArraySource(reopened.readBytes())).getOrThrow().use { document ->
                document.pages()[0].annotations().count { it.subtype.equals("Ink", true) }
            }
        )
        listOf(source, saved, reopened).forEach { it.delete() }
    }

    @Test
    fun strokeLandsCorrectlyOnARotatedAndCroppedPage() {
        val plain = PdfBuilder(PageSize.A4).apply { addPage() }.build()
        val rotated = PdfDocument.open(ByteArraySource(plain)).getOrThrow().use { document ->
            PdfPageOps.rotate(document, listOf(0), 90).getOrThrow()
        }
        val cropped = PdfDocument.open(ByteArraySource(rotated)).getOrThrow().use { document ->
            PdfCrop.apply(
                document,
                CropRequest(CropSpec.Margins(50.0, 50.0, 50.0, 50.0))
            ).getOrThrow().bytes
        }
        val expected = PdfDocument.open(ByteArraySource(cropped)).getOrThrow().use { document ->
            val box = PdfRect.of(document.pages()[0].cropBox!!)!!
            box.centerX to box.centerY
        }
        val file = File(context.cacheDir, "ink-rotated-${System.nanoTime()}.pdf")
        file.writePdf(cropped)

        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            beginInk(viewModel)
            val box = viewModel.pageBoxes.value[0]
            assertTrue(box.width > 0.0 && box.height > 0.0)

            viewModel.startInkStroke(0, 0.5f, 0.5f, 0.75f, 30f)
            viewModel.extendInkStroke(0.6f, 0.5f, 0.9f, 20f)
            viewModel.finishInkStroke(false)
            val saved = save(viewModel)
            publish(saved, "ink-rotated-acceptance.pdf")
            val stored = inkAnnotations(saved).single()
            assertTrue(stored.points.size >= 2)
            // The first point is the centre of the *visible* (cropped, rotated)
            // page in unrotated PDF user space, not the centre of the MediaBox.
            assertEquals(expected.first, stored.points.first().x, 0.5)
            assertEquals(expected.second, stored.points.first().y, 0.5)
            assertEquals(0.75, stored.points.first().pressure, 0.001)
            assertEquals(30.0, stored.points.first().tilt, 0.001)

            // The annotation itself must sit inside the cropped page.
            PdfDocument.open(ByteArraySource(saved)).getOrThrow().use { document ->
                val page = document.pages()[0]
                val crop = PdfRect.of(page.cropBox!!)!!
                val ink = page.annotations().single { it.subtype.equals("Ink", true) }
                assertNotNull(ink.rect)
                assertTrue(ink.rect!!.left >= crop.left - 0.01)
                assertTrue(ink.rect!!.right <= crop.right + 0.01)
                assertTrue(ink.rect!!.bottom >= crop.bottom - 0.01)
                assertTrue(ink.rect!!.top <= crop.top + 0.01)
            }
            assertFalse(saved.isEmpty())
        } finally {
            store.clear()
        }
        file.delete()
    }

    @Test
    fun plainInkAnnotationLoadsAsAnEditableStroke() {
        val source = File(context.cacheDir, "ink-plain-${System.nanoTime()}.pdf")
        val saved = File(context.cacheDir, "ink-plain-saved-${System.nanoTime()}.pdf")
        val plain = PdfBuilder(PageSize.A4).apply { addPage() }.build()
        val withInk = PdfDocument.open(ByteArraySource(plain)).getOrThrow().use { document ->
            PdfPageOps.edit(
                document,
                listOf(
                    InkSpec(
                        pageIndex = 0,
                        strokes = listOf(listOf(100.0, 100.0, 200.0, 120.0, 300.0, 100.0)),
                        color = PdfColor(0.20, 0.40, 0.90),
                        width = 4.0
                    )
                )
            ).getOrThrow()
        }
        source.writePdf(withInk)

        val (store, viewModel) = newModel()
        try {
            open(viewModel, source)
            beginInk(viewModel)
            assertEquals(1, viewModel.ink.value.strokes.size)
            val loaded = viewModel.ink.value.strokes.single()
            assertFalse(loaded.highlighter)
            assertEquals(4.0, loaded.width, 0.0001)
            assertEquals(1.0, loaded.opacity, 0.0001)
            assertEquals(0.20, loaded.color.red, 0.0001)
            assertEquals(0.40, loaded.color.green, 0.0001)
            assertEquals(0.90, loaded.color.blue, 0.0001)
            assertEquals(3, loaded.samples.size)
            assertTrue("a plain /InkList has no pressure, so the fallback is 1.0",
                loaded.samples.all { it.pressure == 1.0 && it.tilt == 0.0 })

            // Editing the loaded stroke makes the file dirty; the save must
            // replace the original annotation and write the private payload,
            // not append a duplicate.
            viewModel.setInkMode(InkToolMode.PEN)
            drawStroke(viewModel, 0.2f, 0.8f, 0.7f)
            val bytes = save(viewModel)
            saved.writePdf(bytes)
            val stored = inkAnnotations(bytes)
            assertEquals(2, stored.size)
            val restoredPlain = stored.first { it.points.size == 3 }
            assertEquals(4.0, restoredPlain.width, 0.0001)
            assertEquals(InkTool.PEN, restoredPlain.tool)
            PdfDocument.open(ByteArraySource(bytes)).getOrThrow().use { document ->
                val page = document.pages()[0]
                assertEquals(2, page.annotations().count { it.subtype.equals("Ink", true) })
                assertTrue(
                    page.annotations()
                        .filter { it.subtype.equals("Ink", true) }
                        .all { it.dict[PdfPaperInk.KEY] != null }
                )
            }
        } finally {
            store.clear()
        }
        listOf(source, saved).forEach { it.delete() }
    }

    @Test
    fun sampleUpdatesAndSmoothingStayInteractive() {
        val file = File(context.cacheDir, "ink-perf-${System.nanoTime()}.pdf")
        file.writePdf(PdfBuilder(PageSize.A4).apply { addPage() }.build())
        val (store, viewModel) = newModel()
        try {
            open(viewModel, file)
            beginInk(viewModel)
            val points = 240
            val updateTimes = ArrayList<Double>()
            val finishTimes = ArrayList<Double>()
            repeat(5) { run ->
                viewModel.startInkStroke(0, 0.1f, 0.5f, 0.5f, 20f)
                val updatesStart = System.nanoTime()
                for (i in 1..points) {
                    val x = 0.1f + 0.8f * i / points
                    val y = 0.5f + 0.04f * kotlin.math.sin(i * 0.35).toFloat()
                    val pressure = 0.3f + 0.6f * (i.toFloat() / points)
                    viewModel.extendInkStroke(x, y, pressure, 20f)
                }
                updateTimes += (System.nanoTime() - updatesStart) / 1_000_000.0 / points
                val finishStart = System.nanoTime()
                viewModel.finishInkStroke(false)
                finishTimes += (System.nanoTime() - finishStart) / 1_000_000.0
                assertEquals(run + 1, viewModel.ink.value.strokes.size)
            }
            val saved = viewModel.ink.value.strokes.last().samples.size
            Log.i(
                "InkT54Perf",
                "samples=$points updates=" +
                    updateTimes.joinToString(",") { "%.3f".format(it) } +
                    " finishes=" + finishTimes.joinToString(",") { "%.3f".format(it) } +
                    " savedPoints=$saved"
            )
            val warmFinishes = finishTimes.drop(1)
            val perUpdateMs = updateTimes.drop(1).average()
            val finishMs = warmFinishes.average()
            assertTrue("per-update cost ${perUpdateMs}ms", perUpdateMs < 4.0)
            // The lift-time fit (two RDP passes + Catmull-Rom) is the one
            // synchronous cost that is not on the drawing path. This guard is
            // deliberately loose: an emulator under load varies by 4x, and the
            // measured value is reported by the log line above. It catches a
            // pathological regression (the first implementation was 100 ms+
            // and quadratic), not normal device variance.
            assertTrue("smoothing cost ${finishMs}ms", finishMs < 250.0)
            assertTrue("saved points $saved should be fewer than $points", saved < points)
        } finally {
            store.clear()
        }
        file.delete()
    }
}
