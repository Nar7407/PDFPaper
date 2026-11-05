package com.pdfpaper.renderjvm

import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.extractText
import com.pdfpaper.renderjvm.tools.runCrop
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T46 CLI: margins/pages, per-page boxes, auto-crop and remove-hidden. */
class CropCliTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun input(): File {
        val bytes = PdfBuilder(PageSize.A4).apply {
            addPage().apply {
                drawRect(100.0, 100.0, 200.0, 200.0)
            }
        }.build()
        return temp.newFile("in.pdf").apply { writeBytes(bytes) }
    }

    private fun textInput(): File {
        val bytes = PdfBuilder(PageSize.A4).apply {
            addPage().apply {
                // Top-left coordinates: this line sits in the top margin.
                drawText("OUTSIDE", 20.0, 20.0, 12.0)
                drawText("INSIDE", 250.0, 400.0, 12.0)
            }
        }.build()
        return temp.newFile("text.pdf").apply { writeBytes(bytes) }
    }

    private fun cropBox(file: File, pageIndex: Int = 0): PdfRect =
        PdfDocument.open(FileSource(file)).getOrThrow().use { document ->
            PdfRect.of(document.pages()[pageIndex].cropBox) ?: error("no crop box")
        }

    private fun mediaBox(file: File, pageIndex: Int = 0): PdfRect =
        PdfDocument.open(FileSource(file)).getOrThrow().use { document ->
            PdfRect.of(document.pages()[pageIndex].mediaBox) ?: error("no media box")
        }

    @Test
    fun marginsAndPagesWriteTheExpectedBox() {
        val input = input()
        val out = File(temp.root, "out.pdf")
        val code = runCrop(
            arrayOf(
                "crop", input.absolutePath,
                "--margins", "10,20,30,40",
                "--pages", "all",
                "--out", out.absolutePath
            )
        )
        assertEquals(0, code)
        assertTrue(out.isFile)
        val box = cropBox(out)
        val media = mediaBox(out)
        assertEquals(10.0, box.left, 0.01)
        // Top-left origin of the builder; the PDF CropBox is bottom-left.
        assertEquals(20.0, box.bottom, 0.01)
        assertEquals(media.right - 30.0, box.right, 0.01)
        assertEquals(media.top - 40.0, box.top, 0.01)
        assertEquals(PdfRect(0.0, 0.0, media.right, media.top), media)
    }

    @Test
    fun perPageBoxesAreAccepted() {
        val input = input()
        val out = File(temp.root, "boxes.pdf")
        val code = runCrop(
            arrayOf(
                "crop", input.absolutePath,
                "--boxes", "1:50,60,300,400",
                "--out", out.absolutePath
            )
        )
        assertEquals(0, code)
        val box = cropBox(out)
        assertEquals(50.0, box.left, 0.01)
        assertEquals(60.0, box.bottom, 0.01)
        assertEquals(300.0, box.right, 0.01)
        assertEquals(400.0, box.top, 0.01)
    }

    @Test
    fun autoDetectsThePaintedRectangle() {
        val input = input()
        val out = File(temp.root, "auto.pdf")
        val code = runCrop(
            arrayOf(
                "crop", input.absolutePath,
                "--auto",
                "--tolerance", "10",
                "--dpi", "96",
                "--out", out.absolutePath
            )
        )
        assertEquals(0, code)
        val box = cropBox(out)
        // The rectangle is top-left (100,100)-(300,300): user space is y-up.
        assertEquals(100.0, box.left, 2.5)
        assertEquals(542.0, box.bottom, 2.5)
        assertEquals(300.0, box.right, 2.5)
        assertEquals(742.0, box.top, 2.5)
    }

    @Test
    fun removeHiddenDeletesTextOutsideTheBox() {
        val input = textInput()
        val out = File(temp.root, "clean.pdf")
        val code = runCrop(
            arrayOf(
                "crop", input.absolutePath,
                "--margins", "100,100,100,100",
                "--remove-hidden",
                "--out", out.absolutePath
            )
        )
        assertEquals(0, code)
        val text = PdfDocument.open(FileSource(out)).getOrThrow().use { document ->
            document.pages()[0].extractText().runs.joinToString("") { it.text }
        }
        assertFalse(text.contains("OUTSIDE"), "text=$text")
        assertTrue(text.contains("INSIDE"), "text=$text")
        val box = cropBox(out)
        assertEquals(100.0, box.left, 0.01)
        assertEquals(100.0, box.bottom, 0.01)
    }

    @Test
    fun absoluteBoxesMayHaveNegativeCoordinates() {
        val input = input()
        val out = File(temp.root, "negative-box.pdf")
        // A negative coordinate is a valid absolute box; it is rejected by the
        // engine's MediaBox check (exit 1), not by the number parser (exit 2).
        val code = runCrop(
            arrayOf(
                "crop", input.absolutePath,
                "--boxes", "1:-50,-50,100,100",
                "--out", out.absolutePath
            )
        )
        assertEquals(1, code)
        assertFalse(out.exists())
    }

    @Test
    fun badUsageStillReturnsTwoAndWritesNothing() {
        val input = input()
        val out = File(temp.root, "never.pdf")
        assertEquals(
            2,
            runCrop(arrayOf("crop", input.absolutePath, "--margins", "1,2,3", "--out", out.absolutePath))
        )
        assertEquals(
            2,
            runCrop(arrayOf("crop", input.absolutePath, "--margins", "10,10,10,10"))
        )
        assertEquals(
            2,
            runCrop(arrayOf("crop", input.absolutePath, "--auto", "--nonsense", "--out", out.absolutePath))
        )
        assertFalse(out.exists())
    }

    @Test
    fun normalCropKeepsTheHiddenText() {
        val input = textInput()
        val out = File(temp.root, "hidden.pdf")
        val code = runCrop(
            arrayOf(
                "crop", input.absolutePath,
                "--margins", "100,100,100,100",
                "--out", out.absolutePath
            )
        )
        assertEquals(0, code)
        val text = PdfDocument.open(FileSource(out)).getOrThrow().use { document ->
            document.pages()[0].extractText().runs.joinToString("") { it.text }
        }
        assertTrue(text.contains("OUTSIDE"), "a normal crop only hides: $text")
    }

    @Test
    fun presetWritesThePresetMargins() {
        val input = input()
        val out = File(temp.root, "preset.pdf")
        assertEquals(
            0,
            runCrop(
                arrayOf(
                    "crop", input.absolutePath,
                    "--preset", "half-inch",
                    "--out", out.absolutePath
                )
            )
        )
        val box = cropBox(out)
        assertEquals(36.0, box.left, 0.01)
        assertEquals(36.0, box.bottom, 0.01)
    }
}
