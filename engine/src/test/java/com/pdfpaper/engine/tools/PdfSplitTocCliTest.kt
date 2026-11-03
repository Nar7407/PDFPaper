package com.pdfpaper.engine.tools

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.text.layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * T66: CLI tests for `split-toc`.
 *
 * Command format:
 * `split-toc <book.pdf> --level N --out-dir <parts/> [--duplicate-boundary] [--overwrite]`
 *
 * Verifies:
 * - Successful split writing `01 - Introduction.pdf`-style names to out-dir
 * - Verifying text of extracted pages
 * - Rejection of existing files without partial overwrite
 * - Usage errors for invalid level, missing out-dir, missing input
 * - Clear diagnostic message on documents with no outline
 * - Warnings reported on documents with out-of-order entries
 */
class PdfSplitTocCliTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runCli(vararg args: String): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runSplitToc(
            args.toList().toTypedArray(),
            PrintStream(out, true, StandardCharsets.UTF_8.name()),
            PrintStream(err, true, StandardCharsets.UTF_8.name())
        )
        return CliResult(
            code,
            out.toString(StandardCharsets.UTF_8.name()),
            err.toString(StandardCharsets.UTF_8.name())
        )
    }

    private fun createPdfWithBookmarks(pageCount: Int, titlesAndPages: List<Pair<String, Int>>): File {
        val objects = mutableListOf<TestObj>()
        val pageRefs = mutableListOf<String>()

        val fontNum = 3
        objects += TestObj(fontNum, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

        var nextNum = 4
        val contentNums = mutableListOf<Int>()
        for (i in 0 until pageCount) {
            val cNum = nextNum++
            contentNums += cNum
            val streamText = "BT /F1 12 Tf 50 700 Td (Page ${i + 1} content line) Tj ET"
            val streamBytes = streamText.toByteArray(StandardCharsets.US_ASCII)
            val header = "<< /Length ${streamBytes.size} >>\nstream\n"
            val footer = "\nendstream"
            val fullBytes = ByteArrayOutputStream().apply {
                write(header.toByteArray(StandardCharsets.US_ASCII))
                write(streamBytes)
                write(footer.toByteArray(StandardCharsets.US_ASCII))
            }.toByteArray()
            objects += TestObj(cNum, 0, fullBytes)
        }

        val pageNums = mutableListOf<Int>()
        for (i in 0 until pageCount) {
            val pNum = nextNum++
            pageNums += pNum
            pageRefs += "$pNum 0 R"
        }

        for (i in 0 until pageCount) {
            val pNum = pageNums[i]
            val cNum = contentNums[i]
            objects += TestObj(
                pNum, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 $fontNum 0 R >> >> " +
                    "/Contents $cNum 0 R >>"
            )
        }

        objects += TestObj(2, 0, "<< /Type /Pages /Kids [${pageRefs.joinToString(" ")}] /Count $pageCount >>")

        if (titlesAndPages.isNotEmpty()) {
            val outlineRootNum = nextNum++
            val itemNums = titlesAndPages.map { nextNum++ }

            objects += TestObj(
                outlineRootNum, 0,
                "<< /Type /Outlines /First ${itemNums.first()} 0 R /Last ${itemNums.last()} 0 R /Count ${itemNums.size} >>"
            )

            for (idx in titlesAndPages.indices) {
                val (title, pageIdx) = titlesAndPages[idx]
                val prev = if (idx > 0) "/Prev ${itemNums[idx - 1]} 0 R " else ""
                val next = if (idx < titlesAndPages.size - 1) "/Next ${itemNums[idx + 1]} 0 R " else ""
                val targetPageObj = pageNums[pageIdx]
                objects += TestObj(
                    itemNums[idx], 0,
                    "<< /Title ($title) /Parent $outlineRootNum 0 R /Dest [$targetPageObj 0 R /Fit] $prev$next>>"
                )
            }
            objects += TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R /Outlines $outlineRootNum 0 R >>")
        } else {
            objects += TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>")
        }

        val file = tempFolder.newFile("sample-${System.nanoTime()}.pdf")
        file.writeBytes(buildTestPdf(objects, rootRef = "1 0 R"))
        return file
    }

    @Test
    fun splitTocWritesSanitizedFilesWithNumericPrefixes() {
        val input = createPdfWithBookmarks(
            pageCount = 4,
            titlesAndPages = listOf("Introduction" to 0, "Methods & Analysis" to 2)
        )
        val outDir = tempFolder.newFolder("parts")

        val result = runCli(
            "split-toc", input.absolutePath,
            "--level", "1",
            "--out-dir", outDir.absolutePath
        )

        assertEquals(0, result.exitCode)
        val files = outDir.listFiles()?.sortedBy { it.name } ?: emptyList()
        assertEquals(2, files.size)
        assertEquals("01 - Introduction.pdf", files[0].name)
        // Sanitized name replaces & or keeps safe chars, numeric prefix width 2
        assertTrue(files[1].name.startsWith("02 - "))
        assertTrue(files[1].name.endsWith(".pdf"))

        // Verify content in part 1
        val p1Doc = PdfDocument.open(ByteArraySource(files[0].readBytes())).getOrThrow()
        p1Doc.use { doc ->
            assertEquals(2, doc.pages().size) // pages 0 and 1
            assertTrue(doc.pages()[0].layout().text.contains("Page 1 content line"))
            assertTrue(doc.pages()[1].layout().text.contains("Page 2 content line"))
        }
    }

    @Test
    fun splitTocRefusesToOverwriteExistingFileWithoutOverwriteFlag() {
        val input = createPdfWithBookmarks(
            pageCount = 4,
            titlesAndPages = listOf("Intro" to 0, "Body" to 2)
        )
        val outDir = tempFolder.newFolder("conflict-parts")
        val existing = File(outDir, "01 - Intro.pdf")
        existing.writeText("existing content")

        val result = runCli(
            "split-toc", input.absolutePath,
            "--level", "1",
            "--out-dir", outDir.absolutePath
        )

        // Must refuse to overwrite and return non-zero
        assertTrue(result.exitCode != 0)
        assertTrue(result.stderr.contains("already exists") || result.stderr.contains("refusing to overwrite"))
        assertEquals("existing content", existing.readText())
        assertFalse(File(outDir, "02 - Body.pdf").exists()) // No partial write
    }

    @Test
    fun splitTocOverwritesExistingFilesWhenFlagProvided() {
        val input = createPdfWithBookmarks(
            pageCount = 4,
            titlesAndPages = listOf("Intro" to 0, "Body" to 2)
        )
        val outDir = tempFolder.newFolder("overwrite-parts")
        val existing = File(outDir, "01 - Intro.pdf")
        existing.writeText("existing content")

        val result = runCli(
            "split-toc", input.absolutePath,
            "--level", "1",
            "--out-dir", outDir.absolutePath,
            "--overwrite"
        )

        assertEquals(0, result.exitCode)
        assertTrue(existing.length() > "existing content".length) // Overwritten with real PDF
    }

    @Test
    fun splitTocFailsWithUsageErrorOnMissingOutDir() {
        val input = createPdfWithBookmarks(4, listOf("Ch 1" to 0))
        val result = runCli("split-toc", input.absolutePath, "--level", "1")
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("--out-dir"))
    }

    @Test
    fun splitTocFailsWithUsageErrorOnInvalidLevel() {
        val input = createPdfWithBookmarks(4, listOf("Ch 1" to 0))
        val outDir = tempFolder.newFolder("out")
        val result = runCli(
            "split-toc", input.absolutePath,
            "--level", "0",
            "--out-dir", outDir.absolutePath
        )
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("level"))
    }

    @Test
    fun splitTocFailsOnMissingInputFile() {
        val missing = File(tempFolder.root, "ghost.pdf")
        val outDir = tempFolder.newFolder("out-missing")
        val result = runCli(
            "split-toc", missing.absolutePath,
            "--level", "1",
            "--out-dir", outDir.absolutePath
        )
        assertEquals(2, result.exitCode)
    }

    @Test
    fun splitTocOnEmptyOutlineReportsClearMessageAndOffersFallbacks() {
        val input = createPdfWithBookmarks(pageCount = 4, titlesAndPages = emptyList())
        val outDir = tempFolder.newFolder("out-empty")
        val result = runCli(
            "split-toc", input.absolutePath,
            "--level", "1",
            "--out-dir", outDir.absolutePath
        )
        assertTrue(result.exitCode != 0)
        // Must clearly inform user no outline was found and suggest alternative options
        val combined = result.stdout + " " + result.stderr
        assertTrue(
            combined.contains("no outline", ignoreCase = true) ||
                combined.contains("no table of contents", ignoreCase = true)
        )
        assertTrue(
            combined.contains("split every", ignoreCase = true) ||
                combined.contains("heading", ignoreCase = true)
        )
    }

    @Test
    fun splitTocOnOutOfOrderEntriesReportsWarningAndSortsPages() {
        val input = createPdfWithBookmarks(
            pageCount = 4,
            titlesAndPages = listOf("Later" to 2, "Earlier" to 0)
        )
        val outDir = tempFolder.newFolder("out-order")
        val result = runCli(
            "split-toc", input.absolutePath,
            "--level", "1",
            "--out-dir", outDir.absolutePath
        )
        assertEquals(0, result.exitCode)
        // Diagnostic warning printed
        val combined = result.stdout + " " + result.stderr
        assertTrue(combined.contains("out-of-order", ignoreCase = true) || combined.contains("sorted", ignoreCase = true))

        // Files are page sorted
        val files = outDir.listFiles()?.sortedBy { it.name } ?: emptyList()
        assertEquals(2, files.size)
        assertEquals("01 - Earlier.pdf", files[0].name)
        assertEquals("02 - Later.pdf", files[1].name)
    }

    @Test
    fun splitTocSupportsDuplicateBoundaryOption() {
        val input = createPdfWithBookmarks(
            pageCount = 5,
            titlesAndPages = listOf("Part 1" to 0, "Part 2" to 2)
        )
        val outDir = tempFolder.newFolder("out-dup")
        val result = runCli(
            "split-toc", input.absolutePath,
            "--level", "1",
            "--out-dir", outDir.absolutePath,
            "--duplicate-boundary"
        )
        assertEquals(0, result.exitCode)

        val files = outDir.listFiles()?.sortedBy { it.name } ?: emptyList()
        val doc1 = PdfDocument.open(ByteArraySource(files[0].readBytes())).getOrThrow()
        doc1.use { doc ->
            // Part 1 includes boundary page (pages 0, 1, 2)
            assertEquals(3, doc.pages().size)
        }
    }
}
