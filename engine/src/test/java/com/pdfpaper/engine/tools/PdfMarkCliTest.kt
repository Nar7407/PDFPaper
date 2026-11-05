package com.pdfpaper.engine.tools

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.layout
import com.pdfpaper.engine.write.WriteFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The command line for the marks: a watermark, a header and footer with page
 * numbers, Bates numbers, and taking the added marks away again.
 */
class PdfMarkCliTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun source(): File {
        val file = tempFolder.newFile("input.pdf")
        file.writeBytes(WriteFixtures.threePageDoc())
        return file
    }

    private fun textOf(file: File): List<String> {
        val document = PdfDocument.open(ByteArraySource(file.readBytes())).getOrThrow()
        document.use {
            return it.pages().map { page -> page.layout().text }
        }
    }

    @Test
    fun theWatermarkCommandWritesANewFile() {
        val input = source()
        val output = File(tempFolder.root, "marked.pdf")
        val code = run(
            arrayOf(
                "mark", "watermark", input.absolutePath,
                "--text", "CONFIDENTIAL", "--out", output.absolutePath
            )
        )
        assertEquals(0, code)
        assertTrue(output.isFile)
        val texts = textOf(output)
        assertEquals(3, texts.size)
        texts.forEach { text -> assertTrue(text.contains("CONFIDENTIAL")) }
        // The original is untouched.
        assertFalse(textOf(input).any { it.contains("CONFIDENTIAL") })
    }

    @Test
    fun thePageNumberCommandUsesTheTokens() {
        val input = source()
        val output = File(tempFolder.root, "numbered.pdf")
        val code = run(
            arrayOf(
                "mark", "number-pages", input.absolutePath,
                "--footer", "Page {page} of {pages}", "--format", "Page-of",
                "--out", output.absolutePath
            )
        )
        assertEquals(0, code)
        val texts = textOf(output)
        assertTrue(texts[0].contains("Page 1 of 3"))
        assertTrue(texts[2].contains("Page 3 of 3"))
    }

    @Test
    fun theBatesCommandNumbersThePages() {
        val input = source()
        val output = File(tempFolder.root, "bates.pdf")
        val code = run(
            arrayOf(
                "mark", "bates", input.absolutePath,
                "--prefix", "ABC-", "--digits", "4", "--start", "7",
                "--out", output.absolutePath
            )
        )
        assertEquals(0, code)
        val texts = textOf(output)
        assertTrue(texts[0].contains("ABC-0007"))
        assertTrue(texts[1].contains("ABC-0008"))
    }

    @Test
    fun theRemoveCommandTakesTheMarksAwayAgain() {
        val input = source()
        val marked = File(tempFolder.root, "marked.pdf")
        val removed = File(tempFolder.root, "removed.pdf")
        assertEquals(
            0,
            run(
                arrayOf(
                    "mark", "watermark", input.absolutePath,
                    "--text", "CONFIDENTIAL", "--out", marked.absolutePath
                )
            )
        )
        assertEquals(
            0,
            run(arrayOf("mark", "remove", marked.absolutePath, "--out", removed.absolutePath))
        )
        val texts = textOf(removed)
        texts.forEach { text -> assertFalse(text.contains("CONFIDENTIAL")) }
    }

    @Test
    fun aMissingFileIsAUsageError() {
        val code = run(
            arrayOf(
                "mark", "watermark",
                File(tempFolder.root, "missing.pdf").absolutePath,
                "--text", "X", "--out", File(tempFolder.root, "out.pdf").absolutePath
            )
        )
        assertEquals(2, code)
    }

    @Test
    fun anUnknownCommandIsAUsageError() {
        assertEquals(2, run(arrayOf("mark", "sprinkle", "x.pdf", "--out", "y.pdf")))
    }
}
