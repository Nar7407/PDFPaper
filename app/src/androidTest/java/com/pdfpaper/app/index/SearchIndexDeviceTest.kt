package com.pdfpaper.app.index

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks for the T50 Room/FTS4 layer: the generated schema must
 * actually create an FTS4 table, snippets must carry our highlight markers,
 * the folder/date filters must work, and a 200-document index must answer a
 * query well under the one-second acceptance budget.
 */
@RunWith(AndroidJUnit4::class)
class SearchIndexDeviceTest {

    private lateinit var db: SearchIndexDatabase
    private lateinit var dao: SearchIndexDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SearchIndexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun folder(name: String, uri: String): Long =
        dao.insertFolder(
            IndexFolderEntity(
                treeUri = uri,
                displayName = name,
                addedAt = 1L
            )
        )

    private fun file(
        folderId: Long,
        name: String,
        status: IndexedFileStatus = IndexedFileStatus.INDEXED,
        modified: Long = 1_000L
    ): Long = dao.insertFile(
        IndexedFileEntity(
            folderId = folderId,
            documentUri = "content://docs/$name",
            relativePath = name,
            displayName = name,
            sizeBytes = 100L,
            lastModified = modified,
            status = status.name,
            pageCount = 1,
            pagesWithoutText = 0,
            indexedAt = 1L
        )
    )

    private fun page(rowId: Long, fileId: Long, pageIndex: Int, text: String) {
        dao.insertPageText(PageTextEntity(rowId = rowId, text = text))
        dao.insertPageMeta(PageMetaEntity(rowId = rowId, fileId = fileId, pageIndex = pageIndex))
    }

    @Test
    fun prefixAndPhraseQueriesReturnPageHitsWithMarkers() = runBlocking(Dispatchers.IO) {
        val folderId = folder("Reports", "content://tree/reports")
        val fileId = file(folderId, "budget.pdf")
        page(1L, fileId, 0, "The annual budget is ready for review")
        page(2L, fileId, 1, "Tax notes mention the budget twice")
        page(3L, fileId, 2, "Nothing relevant on this page")

        val prefix = dao.search("budg*", null, 0L, 100)
        assertEquals(2, prefix.size)
        assertEquals(listOf(0, 1), prefix.map { it.pageIndex })
        assertTrue(prefix.all { it.markedSnippet.contains(SnippetHighlighter.MARK_START) })
        assertTrue(prefix.all { it.markedSnippet.contains(SnippetHighlighter.MARK_END) })
        assertTrue(
            prefix.all {
                SnippetHighlighter.plain(it.markedSnippet).contains("budget", ignoreCase = true)
            }
        )

        val phrase = dao.search("\"annual budget\"", null, 0L, 100)
        assertEquals(1, phrase.size)
        assertEquals(0, phrase.single().pageIndex)
    }

    @Test
    fun folderAndDateFiltersNarrowTheSameQuery() = runBlocking(Dispatchers.IO) {
        val reports = folder("Reports", "content://tree/reports")
        val invoices = folder("Invoices", "content://tree/invoices")
        val recent = file(reports, "recent.pdf", modified = 10_000L)
        val old = file(invoices, "old.pdf", modified = 1_000L)
        page(1L, recent, 0, "budget report")
        page(2L, old, 0, "budget invoice")

        val inReports = dao.search("budget*", reports, 0L, 100)
        assertEquals(1, inReports.size)
        assertEquals(recent, inReports.single().fileId)

        val recentOnly = dao.search("budget*", null, 5_000L, 100)
        assertEquals(1, recentOnly.size)
        assertEquals(recent, recentOnly.single().fileId)

        val all = dao.search("budget*", null, 0L, 100)
        assertEquals(2, all.size)
    }

    @Test
    fun statusRowsListLockedAndOcrPagesWithoutDroppingTheIndex() = runBlocking(Dispatchers.IO) {
        val folderId = folder("Mixed", "content://tree/mixed")
        file(folderId, "locked.pdf", IndexedFileStatus.LOCKED)
        file(folderId, "scan.pdf", IndexedFileStatus.NEEDS_OCR)
        file(folderId, "broken.pdf", IndexedFileStatus.ERROR)
        file(folderId, "huge.pdf", IndexedFileStatus.TOO_LARGE)
        file(folderId, "ok.pdf", IndexedFileStatus.INDEXED)

        val statuses = dao.statusRows().map { it.status }.toSet()
        assertEquals(
            setOf("LOCKED", "NEEDS_OCR", "ERROR", "TOO_LARGE"),
            statuses
        )
        assertEquals(5, dao.fileCount())
        assertEquals(1, dao.folderCount())
    }

    @Test
    fun fileCountFlowNotifiesAfterAFileIsWritten() = runBlocking(Dispatchers.IO) {
        val folderId = folder("Reports", "content://tree/reports")
        val first = CompletableDeferred<Int>()
        val second = CompletableDeferred<Int>()
        val collector = launch {
            dao.fileCountFlow().collectIndexed { index, value ->
                if (index == 0) first.complete(value) else second.complete(value)
            }
        }
        assertEquals(0, withTimeout(5_000) { first.await() })
        file(folderId, "budget.pdf")
        assertEquals(1, withTimeout(5_000) { second.await() })
        collector.cancel()
    }

    @Test
    fun twoHundredDocumentsSearchWellUnderOneSecond() = runBlocking(Dispatchers.IO) {
        val folderId = folder("Library", "content://tree/library")
        var rowId = 1L
        db.withTransaction {
            for (index in 0 until 200) {
                val fileId = file(
                    folderId,
                    "document-$index.pdf",
                    modified = 1_000L + index
                )
                for (pageIndex in 0 until 5) {
                    page(
                        rowId++,
                        fileId,
                        pageIndex,
                        "Document $index page $pageIndex mentions the quarterly budget " +
                            "and the annual report in ordinary prose."
                    )
                }
            }
        }

        assertEquals(200, dao.fileCount())
        assertEquals(1_000, dao.pageCount())

        // Warm the FTS index once, then measure the query itself.
        dao.search("budget*", null, 0L, 100)
        val started = System.nanoTime()
        val hits = dao.search("budget*", null, 0L, 100)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0

        assertEquals(100, hits.size)
        assertTrue(
            "200-document search took ${"%.1f".format(elapsedMs)} ms",
            elapsedMs < 1_000.0
        )
    }
}
