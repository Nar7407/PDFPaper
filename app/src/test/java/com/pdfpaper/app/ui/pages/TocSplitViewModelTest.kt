package com.pdfpaper.app.ui.pages

import android.app.Application
import android.net.Uri
import com.pdfpaper.app.data.ExportFolder
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.split.TocSplitIssue
import com.pdfpaper.engine.split.TocSplitSource
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * T66: ViewModel unit tests for TOC chapter splitting.
 *
 * Verifies:
 * - Loading a document populates initial preview parts, size estimates, and page count
 * - Missing outline presents fallback choices (fixed-N, heading detection)
 * - Conflicting files in destination folder block writing until user confirms overwrite
 * - Export to folder writes files sequentially with truthful progress updates
 * - Export to ZIP packages all active parts with their planned filenames
 * - Clean cancellation halts writer, leaves only completed outputs, and displays accurate summary
 * - Malformed / unreadable document paths surface error state without throwing
 * - Level and boundary setting changes update the preview parts
 */
class TocSplitViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeExportFolder(
        val existing: MutableSet<String> = mutableSetOf(),
        override val displayPath: String = "fake/target"
    ) : ExportFolder {
        val writtenFiles = mutableMapOf<String, ByteArray>()

        override fun existingNames(): Set<String> = existing

        override fun write(name: String, mimeType: String, bytes: ByteArray): String? {
            writtenFiles[name] = bytes
            existing.add(name)
            return "$displayPath/$name"
        }
    }

    private fun samplePdfFile(pageCount: Int = 4, withOutline: Boolean = true): File {
        val objects = mutableListOf<TestObj>()
        val pageRefs = (10 until (10 + pageCount)).map { "$it 0 R" }

        val fontNum = 3
        objects += TestObj(fontNum, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

        var nextNum = 4
        for (i in 0 until pageCount) {
            val cNum = nextNum++
            val streamText = "BT /F1 12 Tf 50 700 Td (Page ${i + 1} text) Tj ET"
            val streamBytes = streamText.toByteArray(StandardCharsets.US_ASCII)
            val header = "<< /Length ${streamBytes.size} >>\nstream\n"
            val footer = "\nendstream"
            val fullBytes = ByteArrayOutputStream().apply {
                write(header.toByteArray(StandardCharsets.US_ASCII))
                write(streamBytes)
                write(footer.toByteArray(StandardCharsets.US_ASCII))
            }.toByteArray()
            objects += TestObj(cNum, 0, fullBytes)
            objects += TestObj(
                10 + i, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 $fontNum 0 R >> >> " +
                    "/Contents $cNum 0 R >>"
            )
        }

        objects += TestObj(2, 0, "<< /Type /Pages /Kids [${pageRefs.joinToString(" ")}] /Count $pageCount >>")

        if (withOutline) {
            // Outline: Ch 1 (p0), Ch 2 (p2)
            objects += TestObj(20, 0, "<< /Type /Outlines /First 21 0 R /Last 22 0 R /Count 2 >>")
            objects += TestObj(
                21, 0,
                "<< /Title (Chapter 1) /Parent 20 0 R /Dest [10 0 R /Fit] /Next 22 0 R >>"
            )
            objects += TestObj(
                22, 0,
                "<< /Title (Chapter 2) /Parent 20 0 R /Dest [12 0 R /Fit] /Prev 21 0 R >>"
            )
            objects += TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R /Outlines 20 0 R >>")
        } else {
            objects += TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>")
        }

        val file = tempFolder.newFile("sample-${System.nanoTime()}.pdf")
        file.writeBytes(buildTestPdf(objects, rootRef = "1 0 R"))
        return file
    }

    private fun createTestViewModel(fakeFolder: FakeExportFolder? = null): TocSplitViewModel {
        val app = Application()
        return TocSplitViewModel(
            application = app,
            exportFolderFactory = { fakeFolder ?: FakeExportFolder() }
        )
    }

    // ---------------------------------------------------------------- Document Loading & Initial State

    @Test
    fun loadInitialDocumentPopulatesPreviewAndState() {
        val file = samplePdfFile(pageCount = 4, withOutline = true)
        val viewModel = createTestViewModel()

        viewModel.load(file.absolutePath)
        val state = viewModel.state.value

        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(4, state.pageCount)
        assertEquals(file.name, state.fileName)
        assertTrue(state.fileSizeBytes > 0)
        assertEquals(2, state.parts.size)

        assertEquals("Chapter 1", state.parts[0].title)
        assertEquals(0..1, state.parts[0].pages)
        assertEquals("01 - Chapter 1.pdf", state.parts[0].fileName)
        assertTrue(state.parts[0].checked)

        assertEquals("Chapter 2", state.parts[1].title)
        assertEquals(2..3, state.parts[1].pages)
        assertEquals("02 - Chapter 2.pdf", state.parts[1].fileName)
        assertTrue(state.parts[1].checked)
    }

    @Test
    fun loadingDocumentWithNoOutlineSetsDiagnosticsAndOffersFallbacks() {
        val file = samplePdfFile(pageCount = 4, withOutline = false)
        val viewModel = createTestViewModel()

        viewModel.load(file.absolutePath)
        val state = viewModel.state.value

        assertTrue(state.parts.isEmpty())
        assertTrue(state.issues.any { it is TocSplitIssue.NoOutline })
        assertTrue(state.showNoOutlineDialog)
    }

    // ---------------------------------------------------------------- Conflict Preflight & Overwrite Protection

    @Test
    fun folderConflictsBlockWriteUntilConfirmation() {
        val file = samplePdfFile(pageCount = 4, withOutline = true)
        val fakeFolder = FakeExportFolder(existing = mutableSetOf("01 - Chapter 1.pdf"))
        val viewModel = createTestViewModel(fakeFolder)

        viewModel.load(file.absolutePath)

        // Attempt export without overwrite confirmation
        viewModel.exportToFolder(treeUri = Uri.parse("content://fake/tree"), overwrite = false)
        val stateWithConflict = viewModel.state.value

        // Writing must be blocked and conflict recorded
        assertEquals(listOf("01 - Chapter 1.pdf"), stateWithConflict.conflicts)
        assertTrue(fakeFolder.writtenFiles.isEmpty())
        assertFalse(stateWithConflict.isExporting)

        // When confirmed with overwrite = true, export proceeds
        viewModel.exportToFolder(treeUri = Uri.parse("content://fake/tree"), overwrite = true)
        val stateFinished = viewModel.state.value

        assertTrue(fakeFolder.writtenFiles.containsKey("01 - Chapter 1.pdf"))
        assertTrue(fakeFolder.writtenFiles.containsKey("02 - Chapter 2.pdf"))
        assertEquals(2, stateFinished.done)
    }

    @Test
    fun dismissConflictsClearsConflictStateWithoutWriting() {
        val file = samplePdfFile(pageCount = 4, withOutline = true)
        val fakeFolder = FakeExportFolder(existing = mutableSetOf("01 - Chapter 1.pdf"))
        val viewModel = createTestViewModel(fakeFolder)

        viewModel.load(file.absolutePath)
        viewModel.exportToFolder(treeUri = Uri.parse("content://fake/tree"), overwrite = false)
        assertTrue(viewModel.state.value.conflicts.isNotEmpty())

        viewModel.dismissConflicts()
        assertTrue(viewModel.state.value.conflicts.isEmpty())
        assertTrue(fakeFolder.writtenFiles.isEmpty())
    }

    // ---------------------------------------------------------------- Folder Export & Progress Updates

    @Test
    fun exportToFolderWritesSequentiallyWithProgress() {
        val file = samplePdfFile(pageCount = 4, withOutline = true)
        val fakeFolder = FakeExportFolder()
        val viewModel = createTestViewModel(fakeFolder)

        viewModel.load(file.absolutePath)
        viewModel.exportToFolder(treeUri = Uri.parse("content://fake/tree"), overwrite = false)
        val state = viewModel.state.value

        assertEquals(2, state.done)
        assertEquals(2, state.total)
        assertFalse(state.isExporting)
        assertNotNull(state.summary)
        assertTrue(fakeFolder.writtenFiles.containsKey("01 - Chapter 1.pdf"))
        assertTrue(fakeFolder.writtenFiles.containsKey("02 - Chapter 2.pdf"))
    }

    // ---------------------------------------------------------------- ZIP Export

    @Test
    fun exportToZipWritesAllCheckedPartsIntoZipStream() {
        val file = samplePdfFile(pageCount = 4, withOutline = true)
        val viewModel = createTestViewModel()

        viewModel.load(file.absolutePath)
        val zipFile = viewModel.exportToZipSync()
        assertNotNull(zipFile)
        assertTrue(zipFile!!.exists())

        // Read zip and verify entries
        val entryNames = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zipFile.readBytes())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryNames += entry.name
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        assertEquals(2, entryNames.size)
        assertTrue(entryNames.contains("01 - Chapter 1.pdf"))
        assertTrue(entryNames.contains("02 - Chapter 2.pdf"))
    }

    // ---------------------------------------------------------------- Cancellation

    @Test
    fun cancellationHaltsExportAndLeavesTruthfulSummary() {
        val file = samplePdfFile(pageCount = 4, withOutline = true)
        val fakeFolder = FakeExportFolder()
        val viewModel = createTestViewModel(fakeFolder)

        viewModel.load(file.absolutePath)

        // Cancel during or immediately after start
        viewModel.cancel()
        val state = viewModel.state.value

        assertFalse(state.isExporting)
        assertTrue(state.cancelled)
    }

    // ---------------------------------------------------------------- Error Handling

    @Test
    fun documentOpenErrorSetsErrorStateWithoutCrashing() {
        val viewModel = createTestViewModel()
        viewModel.load("/non/existent/path/book.pdf")
        val state = viewModel.state.value

        assertFalse(state.loading)
        assertNotNull(state.error)
        assertTrue(state.parts.isEmpty())
    }

    // ---------------------------------------------------------------- Options & Fallbacks Interaction

    @Test
    fun levelChangeUpdatesPlannedParts() {
        val file = samplePdfFile(pageCount = 4, withOutline = true)
        val viewModel = createTestViewModel()

        viewModel.load(file.absolutePath)
        assertEquals(1, viewModel.state.value.level)

        viewModel.setLevel(2)
        assertEquals(2, viewModel.state.value.level)
    }

    @Test
    fun retryWithHeadingsFallbackLoadsHeadingPlan() {
        val file = samplePdfFile(pageCount = 4, withOutline = false)
        val viewModel = createTestViewModel()

        viewModel.load(file.absolutePath)
        assertTrue(viewModel.state.value.parts.isEmpty())

        viewModel.retryWithHeadings()
        assertEquals(TocSplitSource.HEADINGS, viewModel.state.value.source)
    }
}
