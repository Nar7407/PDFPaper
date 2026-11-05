package com.pdfpaper.app.index

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * The one place the app reads and writes the local search index (T50).
 * Everything is app-private; the only external identifiers stored are SAF
 * document URIs the user explicitly granted.
 */
class IndexRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = SearchIndexDatabase.get(appContext)
    private val dao = db.dao()

    val database: SearchIndexDatabase get() = db

    suspend fun folders(): List<IndexFolder> = io {
        dao.folders().map { it.toModel() }
    }

    suspend fun addFolder(treeUri: String, displayName: String): Long = io {
        val inserted = dao.insertFolder(
            IndexFolderEntity(
                treeUri = treeUri,
                displayName = displayName.ifBlank { "Folder" },
                addedAt = System.currentTimeMillis()
            )
        )
        if (inserted > 0) inserted else dao.folderByUri(treeUri)?.id ?: -1L
    }

    suspend fun removeFolder(folderId: Long) = io {
        val folder = dao.folders().firstOrNull { it.id == folderId }
        db.withTransaction {
            dao.deletePageTextForFolder(folderId)
            dao.deleteFolder(folderId)
        }
        // The persisted grant is released after the rows are gone. A provider
        // that does not support release must not make the removal fail.
        if (folder != null) releaseTree(folder.treeUri)
        Unit
    }

    suspend fun updateFolderScan(folderId: Long, time: Long, error: String?) = io {
        dao.updateFolderScan(folderId, time, error)
    }

    suspend fun allFiles(): List<IndexedFile> = io {
        dao.allFiles().map { it.toModel() }
    }

    suspend fun filesInFolder(folderId: Long): List<IndexedFile> = io {
        dao.filesInFolder(folderId).map { it.toModel() }
    }

    suspend fun insertFile(file: IndexedFileEntity): Long = io { dao.insertFile(file) }

    suspend fun updateFile(file: IndexedFileEntity) = io { dao.updateFile(file) }

    suspend fun deleteFile(fileId: Long) = io {
        db.withTransaction {
            dao.deletePageTextForFile(fileId)
            dao.deleteFile(fileId)
        }
        Unit
    }

    suspend fun clearIndex() = io {
        db.withTransaction {
            dao.deleteAllPageText()
            dao.deleteAllPageMeta()
            dao.deleteAllFiles()
        }
        // VACUUM returns the deleted pages to the filesystem and the WAL
        // checkpoint truncates the sidecar, so "Delete index" also drops the
        // reported size instead of leaving the old pages on disk. Both are
        // optional for correctness, so a device that refuses them is ignored.
        runCatching { db.openHelper.writableDatabase.execSQL("VACUUM") }
        runCatching {
            db.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { it.moveToFirst() }
        }
        Unit
    }

    suspend fun statusFiles(): List<IndexStatusFile> = io {
        dao.statusRows().mapNotNull { row ->
            val status = runCatching { IndexedFileStatus.valueOf(row.status) }.getOrNull()
                ?: return@mapNotNull null
            IndexStatusFile(
                fileId = row.fileId,
                documentUri = row.documentUri,
                displayName = row.displayName,
                relativePath = row.relativePath,
                status = status,
                pageCount = row.pageCount,
                pagesWithoutText = row.pagesWithoutText
            )
        }
    }

    fun indexedFileCountFlow(): Flow<Int> = dao.fileCountFlow()

    suspend fun stats(): IndexStats = io {
        val counts = dao.statusCounts().associate { it.status to it.count }
        IndexStats(
            folderCount = dao.folderCount(),
            fileCount = dao.fileCount(),
            pageCount = dao.pageCount(),
            indexedCount = counts[IndexedFileStatus.INDEXED.name] ?: 0,
            lockedCount = counts[IndexedFileStatus.LOCKED.name] ?: 0,
            needsOcrCount = counts[IndexedFileStatus.NEEDS_OCR.name] ?: 0,
            errorCount = (counts[IndexedFileStatus.ERROR.name] ?: 0) +
                (counts[IndexedFileStatus.TOO_LARGE.name] ?: 0),
            databaseBytes = databaseBytes()
        )
    }

    /**
     * The stored text of one indexed file's pages, in page order (T65).
     */
    suspend fun pageTexts(fileId: Long): List<PageTextRow> = io { dao.pageTexts(fileId) }

    /**
     * The files a library regex run can search: indexed, and with at least one
     * page that actually holds text. Folder and date filters are honoured.
     */
    suspend fun indexedFiles(filters: SearchFilters = SearchFilters()): List<FileSearchRow> = io {
        dao.fileRows(filters.folderId, cutoffFor(filters.date)).filter { row ->
            row.status == IndexedFileStatus.INDEXED.name &&
                row.pageCount > row.pagesWithoutText
        }
    }

    /**
     * The files a library regex run must report instead of searching: locked,
     * needing OCR, failed or too large. The summary names them so a run never
     * looks complete when it is not.
     */
    suspend fun unsearchableFiles(filters: SearchFilters = SearchFilters()): List<FileSearchRow> = io {
        dao.fileRows(filters.folderId, cutoffFor(filters.date)).filter { row ->
            row.status in UNSEARCHABLE_STATUSES
        }
    }

    suspend fun search(
        input: String,
        filters: SearchFilters = SearchFilters(),
        limit: Int = 100
    ): List<SearchResultGroup> = io {
        val parsed = SearchQueryBuilder.parse(input)
        val match = parsed.matchExpression ?: return@io emptyList()
        val cutoff = cutoffFor(filters.date)
        val hits = if (filters.fileNameOnly) {
            fileNameHits(parsed.plainTerms, filters, cutoff)
        } else {
            dao.search(match, filters.folderId, cutoff, limit)
        }
        group(hits)
    }

    private fun fileNameHits(
        terms: List<String>,
        filters: SearchFilters,
        cutoff: Long
    ): List<SearchHit> {
        if (terms.isEmpty()) return emptyList()
        return dao.fileRows(filters.folderId, cutoff)
            .asSequence()
            .filter { row -> terms.all { row.displayName.contains(it, ignoreCase = true) } }
            .map { row ->
                SearchHit(
                    fileId = row.fileId,
                    documentUri = row.documentUri,
                    displayName = row.displayName,
                    relativePath = row.relativePath,
                    folderId = row.folderId,
                    folderName = row.folderName,
                    lastModified = row.lastModified,
                    pageIndex = 0,
                    markedSnippet = markFirst(row.displayName, terms)
                )
            }
            .toList()
    }

    private fun group(hits: List<SearchHit>): List<SearchResultGroup> {
        val grouped = LinkedHashMap<Long, MutableList<SearchHit>>()
        for (hit in hits) {
            grouped.getOrPut(hit.fileId) { ArrayList() } += hit
        }
        return grouped.map { (fileId, pages) ->
            val first = pages.first()
            SearchResultGroup(
                fileId = fileId,
                documentUri = first.documentUri,
                displayName = first.displayName,
                relativePath = first.relativePath,
                folderId = first.folderId,
                folderName = first.folderName,
                lastModified = first.lastModified,
                hits = pages
            )
        }
    }

    private fun markFirst(text: String, terms: List<String>): String {
        for (term in terms) {
            val at = text.indexOf(term, ignoreCase = true)
            if (at >= 0) {
                return text.substring(0, at) +
                    SnippetHighlighter.MARK_START +
                    text.substring(at, at + term.length) +
                    SnippetHighlighter.MARK_END +
                    text.substring(at + term.length)
            }
        }
        return text
    }

    private fun cutoffFor(filter: DateFilter): Long {
        val now = System.currentTimeMillis()
        return when (filter) {
            DateFilter.ANY -> 0L
            DateFilter.LAST_7_DAYS -> now - 7L * 24L * 60L * 60L * 1000L
            DateFilter.LAST_30_DAYS -> now - 30L * 24L * 60L * 60L * 1000L
            DateFilter.THIS_YEAR -> LocalDate.now()
                .withDayOfYear(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }
    }

    private fun databaseBytes(): Long {
        val base = appContext.getDatabasePath(SearchIndexDatabase.NAME)
        return listOf(
            base,
            File("${base.path}-wal"),
            File("${base.path}-shm"),
            File("${base.path}-journal")
        ).sumOf { if (it.exists()) it.length() else 0L }
    }

    private fun releaseTree(treeUri: String) {
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        @Volatile
        private var instance: IndexRepository? = null

        fun get(context: Context): IndexRepository =
            instance ?: synchronized(this) {
                instance ?: IndexRepository(context).also { instance = it }
            }
    }
}

private val UNSEARCHABLE_STATUSES = setOf(
    IndexedFileStatus.LOCKED.name,
    IndexedFileStatus.NEEDS_OCR.name,
    IndexedFileStatus.ERROR.name,
    IndexedFileStatus.TOO_LARGE.name
)

private fun IndexFolderEntity.toModel() = IndexFolder(
    id = id,
    treeUri = treeUri,
    displayName = displayName,
    addedAt = addedAt,
    lastScanAt = lastScanAt,
    lastError = lastError
)

private fun IndexedFileEntity.toModel() = IndexedFile(
    id = id,
    folderId = folderId,
    documentUri = documentUri,
    relativePath = relativePath,
    displayName = displayName,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    status = runCatching { IndexedFileStatus.valueOf(status) }
        .getOrDefault(IndexedFileStatus.ERROR),
    pageCount = pageCount,
    pagesWithoutText = pagesWithoutText,
    indexedAt = indexedAt,
    error = error
)
