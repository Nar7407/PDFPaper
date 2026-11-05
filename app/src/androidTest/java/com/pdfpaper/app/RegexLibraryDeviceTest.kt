package com.pdfpaper.app

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.index.IndexRepository
import com.pdfpaper.app.index.IndexedFileEntity
import com.pdfpaper.app.index.IndexedFileStatus
import com.pdfpaper.app.index.PageMetaEntity
import com.pdfpaper.app.index.PageTextEntity
import com.pdfpaper.app.index.SearchFilters
import com.pdfpaper.app.ui.search.DEFAULT_REGEX_FLAGS
import com.pdfpaper.app.ui.search.RegexRow
import com.pdfpaper.app.ui.search.RegexViewModel
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.layout
import com.pdfpaper.engine.text.normaliseWithOffsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T65 library scope on a device: the run walks the stored page text of every
 * indexed file, reports progress, can be cancelled, and a tap re-finds the
 * stored ordinal on the opened page so the viewer gets boxes for a wrapped line.
 *
 * The tests use the production index (the view model reads it through
 * [IndexRepository]); they clean up the folder they add.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class RegexLibraryDeviceTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val repository = IndexRepository.get(context)
    private val stores = mutableListOf<ViewModelStore>()
    private val downloads = mutableListOf<Uri>()
    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var folderId: Long = -1

    @Before
    fun setUp() {
        folderId = runBlocking {
            repository.addFolder("content://pdfpaper-t65/${System.nanoTime()}", "T65 library")
        }
    }

    @After
    fun tearDown() {
        collectorScope.cancel()
        stores.forEach { it.clear() }
        if (folderId > 0) runBlocking { repository.removeFolder(folderId) }
        downloads.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    // ------------------------------------------------------------- helpers

    private fun newModel(): RegexViewModel {
        val store = ViewModelStore()
        stores += store
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                RegexViewModel(context) as T
        }
        return ViewModelProvider(store, factory)[RegexViewModel::class.java]
    }

    private fun seedFile(
        name: String,
        pageCount: Int,
        pageText: (Int) -> String,
        documentUri: String = "content://pdfpaper-t65/$name"
    ): Long = runBlocking {
        val fileId = repository.insertFile(
            IndexedFileEntity(
                folderId = folderId,
                documentUri = documentUri,
                relativePath = name,
                displayName = name,
                sizeBytes = 1_000L,
                lastModified = System.currentTimeMillis(),
                status = IndexedFileStatus.INDEXED.name,
                pageCount = pageCount,
                pagesWithoutText = 0,
                indexedAt = System.currentTimeMillis()
            )
        )
        val dao = repository.database.dao()
        repeat(pageCount) { page ->
            val rowId = dao.nextPageRowId()
            dao.insertPageText(PageTextEntity(rowId, pageText(page)))
            dao.insertPageMeta(PageMetaEntity(rowId, fileId, page))
        }
        fileId
    }

    private fun waitUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun insertDownload(fileName: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val created = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: error("the Downloads collection refused a new document")
        context.contentResolver.openOutputStream(created)!!.use { it.write(bytes) }
        context.contentResolver.update(
            created,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        downloads += created
        return created
    }

    // --------------------------------------------------------------- tests

    @Test
    fun fiftyFilesShowProgressAndFinish() {
        repeat(50) { index ->
            seedFile("t65-$index.pdf", pageCount = 1, pageText = { "Invoice AB-1 on page $it" })
        }
        val progressSeen = CopyOnWriteArrayList<Int>()
        val model = newModel()
        model.setFlags(DEFAULT_REGEX_FLAGS)
        collectorScope.launch {
            model.state.collect { state ->
                state.progress?.let { progressSeen += it.filesDone }
            }
        }
        model.setPattern("AB-\\d")
        model.run(SearchFilters(folderId = folderId))
        waitUntil("the 50-file library run") {
            !model.state.value.running && model.state.value.filesTotal == 50
        }
        val state = model.state.value
        assertEquals(null, state.error)
        assertEquals(50, state.rows.size)
        assertEquals(50, state.filesTotal)
        assertEquals("50 matches in 50 files (50 pages)", state.summary)
        assertTrue("the panel never saw progress", progressSeen.isNotEmpty())
        assertTrue("progress should reach 50", progressSeen.maxOrNull() == 50)
    }

    @Test
    fun cancelStopsTheRunAndMarksItCancelled() {
        repeat(5) { index ->
            seedFile("t65-cancel-$index.pdf", pageCount = 1, pageText = { "a".repeat(200_000) })
        }
        val model = newModel()
        model.setFlags(DEFAULT_REGEX_FLAGS)
        model.setPattern("(a+)+$")
        model.run(SearchFilters(folderId = folderId))
        model.cancel()
        waitUntil("the cancelled run") { !model.state.value.running }
        assertTrue(
            "a cancelled run must say so: ${model.state.value.summary}",
            model.state.value.cancelled
        )
    }

    @Test
    fun tappingAResultRecomputesBoxesAcrossAWrappedLine() {
        val pdf = PdfBuilder(PageSize.A4).apply {
            addPage().apply {
                drawText("Please note that the total due is", 64.0, 300.0, 12.0)
                drawText("142.50 EUR for the quarter.", 64.0, 282.0, 12.0)
            }
        }.build()
        val uri = insertDownload("pdfpaper-t65-wrapped-${System.nanoTime()}.pdf", pdf)
        // Seed exactly the text the indexer would store, and build the pattern
        // from the two words that sit on either side of the real line break.
        val layoutText = PdfDocument.open(ByteArraySource(pdf)).getOrThrow().use { document ->
            document.pages()[0].layout().text
        }
        val newline = layoutText.indexOf('\n')
        assertTrue("the fixture must wrap across lines", newline > 0)
        val beforeWord = layoutText.substring(0, newline).trim().substringAfterLast(' ')
        val afterWord = layoutText.substring(newline + 1).trim().substringBefore(' ')
        val pattern = Regex.escape(beforeWord) + "\\s+" + Regex.escape(afterWord)
        seedFile(
            name = "wrapped.pdf",
            pageCount = 1,
            pageText = { normaliseWithOffsets(layoutText).text },
            documentUri = uri.toString()
        )

        val model = newModel()
        model.setFlags(DEFAULT_REGEX_FLAGS)
        model.setPattern(pattern)
        model.run(SearchFilters(folderId = folderId))
        waitUntil("the library run") {
            !model.state.value.running && model.state.value.rows.size == 1
        }
        val row: RegexRow = model.state.value.rows.single()
        assertEquals(0, row.pageIndex)

        var ready: Triple<String, Int, String?>? = null
        model.openRow(row) { path, page, _, bounds, note ->
            ready = Triple(path, page, bounds ?: note)
        }
        waitUntil("the tap recompute") { ready != null }
        val (path, page, boundsOrNote) = ready!!
        assertEquals(uri.toString(), path)
        assertEquals(0, page)
        assertNotNull("no boxes: $boundsOrNote", boundsOrNote)
        assertTrue(
            "the tap should produce four bounds, was $boundsOrNote",
            boundsOrNote.orEmpty().split(',').size == 4
        )
    }
}
