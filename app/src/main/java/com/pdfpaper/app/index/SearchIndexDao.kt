package com.pdfpaper.app.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class FileSearchRow(
    val fileId: Long,
    val documentUri: String,
    val displayName: String,
    val relativePath: String,
    val folderId: Long,
    val folderName: String,
    val lastModified: Long,
    val status: String,
    val pageCount: Int,
    val pagesWithoutText: Int
)

data class StatusCountRow(val status: String, val count: Int)

/** One stored page's text, as the T65 library run reads it. */
data class PageTextRow(val fileId: Long, val pageIndex: Int, val text: String)

@Dao
interface SearchIndexDao {

    // ---------------------------------------------------------------- folders

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertFolder(folder: IndexFolderEntity): Long

    @Query("SELECT * FROM index_folder ORDER BY addedAt ASC, id ASC")
    fun folders(): List<IndexFolderEntity>

    @Query("SELECT * FROM index_folder WHERE treeUri = :treeUri LIMIT 1")
    fun folderByUri(treeUri: String): IndexFolderEntity?

    @Query("UPDATE index_folder SET lastScanAt = :time, lastError = :error WHERE id = :folderId")
    fun updateFolderScan(folderId: Long, time: Long, error: String?)

    @Query("DELETE FROM index_folder WHERE id = :folderId")
    fun deleteFolder(folderId: Long)

    // ------------------------------------------------------------------ files

    @Insert
    fun insertFile(file: IndexedFileEntity): Long

    @Update
    fun updateFile(file: IndexedFileEntity)

    @Query("SELECT * FROM indexed_file")
    fun allFiles(): List<IndexedFileEntity>

    @Query("SELECT * FROM indexed_file WHERE folderId = :folderId")
    fun filesInFolder(folderId: Long): List<IndexedFileEntity>

    @Query("SELECT * FROM indexed_file WHERE id = :fileId")
    fun file(fileId: Long): IndexedFileEntity?

    @Query("DELETE FROM indexed_file WHERE id = :fileId")
    fun deleteFile(fileId: Long)

    @Query(
        "SELECT f.id AS fileId, f.documentUri AS documentUri, f.displayName AS displayName, " +
            "f.relativePath AS relativePath, f.folderId AS folderId, d.displayName AS folderName, " +
            "f.lastModified AS lastModified, f.status AS status, f.pageCount AS pageCount, " +
            "f.pagesWithoutText AS pagesWithoutText " +
            "FROM indexed_file f JOIN index_folder d ON d.id = f.folderId " +
            "WHERE (:folderId IS NULL OR f.folderId = :folderId) " +
            "AND f.lastModified >= :minLastModified " +
            "ORDER BY f.lastModified DESC, f.displayName COLLATE NOCASE ASC"
    )
    fun fileRows(folderId: Long?, minLastModified: Long): List<FileSearchRow>

    @Query(
        "SELECT f.id AS fileId, f.documentUri AS documentUri, f.displayName AS displayName, " +
            "f.relativePath AS relativePath, f.folderId AS folderId, d.displayName AS folderName, " +
            "f.lastModified AS lastModified, f.status AS status, f.pageCount AS pageCount, " +
            "f.pagesWithoutText AS pagesWithoutText " +
            "FROM indexed_file f JOIN index_folder d ON d.id = f.folderId " +
            "WHERE f.status IN ('LOCKED', 'NEEDS_OCR', 'ERROR', 'TOO_LARGE') " +
            "ORDER BY f.status ASC, f.displayName COLLATE NOCASE ASC"
    )
    fun statusRows(): List<FileSearchRow>

    // ------------------------------------------------------------------ pages

    /**
     * The text of every indexed page of one file, in page order. A plain row
     * read (no `MATCH`), so FTS syntax never applies to it.
     */
    @Query(
        "SELECT m.fileId AS fileId, m.pageIndex AS pageIndex, p.text AS text " +
            "FROM page_meta m JOIN page_text p ON p.rowid = m.rowId " +
            "WHERE m.fileId = :fileId ORDER BY m.pageIndex ASC"
    )
    fun pageTexts(fileId: Long): List<PageTextRow>

    @Query("SELECT COALESCE(MAX(rowid), 0) + 1 FROM page_text")
    fun nextPageRowId(): Long

    @Insert
    fun insertPageText(page: PageTextEntity)

    @Insert
    fun insertPageMeta(meta: PageMetaEntity)

    @Query(
        "DELETE FROM page_text WHERE rowid IN " +
            "(SELECT rowId FROM page_meta WHERE fileId = :fileId)"
    )
    fun deletePageTextForFile(fileId: Long)

    @Query("DELETE FROM page_meta WHERE fileId = :fileId")
    fun deletePageMetaForFile(fileId: Long)

    @Query(
        "DELETE FROM page_text WHERE rowid IN " +
            "(SELECT rowId FROM page_meta WHERE fileId IN " +
            "(SELECT id FROM indexed_file WHERE folderId = :folderId))"
    )
    fun deletePageTextForFolder(folderId: Long)

    @Query("DELETE FROM page_text")
    fun deleteAllPageText()

    @Query("DELETE FROM page_meta")
    fun deleteAllPageMeta()

    @Query("DELETE FROM indexed_file")
    fun deleteAllFiles()

    // ---------------------------------------------------------------- search

    @Query(
        "SELECT f.id AS fileId, f.documentUri AS documentUri, f.displayName AS displayName, " +
            "f.relativePath AS relativePath, f.folderId AS folderId, d.displayName AS folderName, " +
            "f.lastModified AS lastModified, m.pageIndex AS pageIndex, " +
            "COALESCE(snippet(page_text, char(1), char(2), '…', 0, 28), '') AS markedSnippet " +
            "FROM page_text " +
            "JOIN page_meta m ON m.rowId = page_text.rowid " +
            "JOIN indexed_file f ON f.id = m.fileId " +
            "JOIN index_folder d ON d.id = f.folderId " +
            "WHERE page_text MATCH :match " +
            "AND (:folderId IS NULL OR f.folderId = :folderId) " +
            "AND f.lastModified >= :minLastModified " +
            "ORDER BY f.lastModified DESC, f.displayName COLLATE NOCASE ASC, m.pageIndex ASC " +
            "LIMIT :limit"
    )
    fun search(match: String, folderId: Long?, minLastModified: Long, limit: Int): List<SearchHit>

    // ---------------------------------------------------------------- counts

    @Query("SELECT COUNT(*) FROM index_folder")
    fun folderCount(): Int

    @Query("SELECT COUNT(*) FROM indexed_file")
    fun fileCount(): Int

    /**
     * Room invalidates this query on every write to `indexed_file`, not only
     * when the count changes. The screen uses that to refresh its stats after
     * a worker writes a file, even when a short scan ends between UI polls.
     */
    @Query("SELECT COUNT(*) FROM indexed_file")
    fun fileCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM page_meta")
    fun pageCount(): Int

    @Query("SELECT status, COUNT(*) AS count FROM indexed_file GROUP BY status")
    fun statusCounts(): List<StatusCountRow>
}
