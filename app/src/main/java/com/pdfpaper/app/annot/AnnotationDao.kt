package com.pdfpaper.app.annot

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** One annotation joined with its document, ready for the hub. */
data class AnnotationRow(
    val id: Long,
    val documentId: Long,
    val documentUri: String,
    val displayName: String,
    val pageIndex: Int,
    val kind: String,
    val color: String?,
    val author: String?,
    val createdAt: Long,
    val modifiedAt: Long?,
    val quotedText: String?,
    val noteText: String?,
    val bounds: String?,
    val objectNumber: Int?,
    val label: String?,
    val tag: String?
)

data class AnnotationKindCountRow(val kind: String, val count: Int)

@Dao
interface AnnotationDao {

    // ------------------------------------------------------------- documents

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertDocument(document: AnnotationDocumentEntity): Long

    @Update
    fun updateDocument(document: AnnotationDocumentEntity)

    @Query("SELECT * FROM annotation_document WHERE fingerprint = :fingerprint LIMIT 1")
    fun documentByFingerprint(fingerprint: String): AnnotationDocumentEntity?

    @Query("SELECT * FROM annotation_document WHERE documentUri = :documentUri LIMIT 1")
    fun documentByUri(documentUri: String): AnnotationDocumentEntity?

    @Query("SELECT * FROM annotation_document ORDER BY displayName COLLATE NOCASE ASC")
    fun allDocuments(): List<AnnotationDocumentEntity>

    @Query("SELECT * FROM annotation_document WHERE id = :documentId")
    fun document(documentId: Long): AnnotationDocumentEntity?

    @Query(
        "UPDATE annotation_document SET documentUri = :documentUri, displayName = :displayName, " +
            "sizeBytes = :sizeBytes, lastModified = :lastModified, indexedAt = :indexedAt " +
            "WHERE id = :documentId"
    )
    fun updateDocumentLocation(
        documentId: Long,
        documentUri: String,
        displayName: String,
        sizeBytes: Long,
        lastModified: Long,
        indexedAt: Long
    )

    @Query("DELETE FROM annotation_document WHERE id = :documentId")
    fun deleteDocument(documentId: Long)

    @Query("DELETE FROM annotation_document")
    fun deleteAllDocuments()

    // ------------------------------------------------------------ annotations

    @Insert
    fun insertAnnotation(annotation: AnnotationEntity): Long

    @Update
    fun updateAnnotation(annotation: AnnotationEntity)

    @Query("SELECT * FROM annotation_index WHERE id = :id")
    fun annotation(id: Long): AnnotationEntity?

    @Query(
        "SELECT id FROM annotation_index WHERE documentId = :documentId " +
            "AND pageIndex = :pageIndex AND objectNumber = :objectNumber LIMIT 1"
    )
    fun idForPdfAnnotation(documentId: Long, pageIndex: Int, objectNumber: Int): Long?

    @Query(
        "SELECT a.*, d.documentUri AS documentUri, d.displayName AS displayName " +
            "FROM annotation_index a JOIN annotation_document d ON d.id = a.documentId " +
            "ORDER BY d.displayName COLLATE NOCASE ASC, a.pageIndex ASC, a.createdAt ASC"
    )
    fun allRows(): List<AnnotationRow>

    @Query(
        "SELECT a.*, d.documentUri AS documentUri, d.displayName AS displayName " +
            "FROM annotation_index a JOIN annotation_document d ON d.id = a.documentId " +
            "WHERE a.documentId = :documentId " +
            "ORDER BY a.pageIndex ASC, a.createdAt ASC"
    )
    fun rowsForDocument(documentId: Long): List<AnnotationRow>

    @Query(
        "SELECT a.*, d.documentUri AS documentUri, d.displayName AS displayName " +
            "FROM annotation_index a JOIN annotation_document d ON d.id = a.documentId " +
            "WHERE a.id IN (:ids) " +
            "ORDER BY d.displayName COLLATE NOCASE ASC, a.pageIndex ASC, a.createdAt ASC"
    )
    fun rowsByIds(ids: List<Long>): List<AnnotationRow>

    @Query("DELETE FROM annotation_index WHERE id = :id")
    fun deleteAnnotationRow(id: Long)

    /** Removes a file's PDF annotations but keeps the app's own bookmarks. */
    @Query("DELETE FROM annotation_index WHERE documentId = :documentId AND kind != 'BOOKMARK'")
    fun deletePdfAnnotationsForDocument(documentId: Long)

    @Query("DELETE FROM annotation_index WHERE documentId = :documentId")
    fun deleteAllAnnotationsForDocument(documentId: Long)

    @Query("SELECT id FROM annotation_index WHERE documentId = :documentId AND kind = 'BOOKMARK'")
    fun bookmarkIdsForDocument(documentId: Long): List<Long>

    @Query(
        "DELETE FROM annotation_text WHERE rowid IN " +
            "(SELECT id FROM annotation_index WHERE documentId = :documentId)"
    )
    fun deleteTextForDocument(documentId: Long)

    /** Deletes the full-text rows of a document's PDF annotations only. */
    @Query(
        "DELETE FROM annotation_text WHERE rowid IN " +
            "(SELECT id FROM annotation_index WHERE documentId = :documentId AND kind != 'BOOKMARK')"
    )
    fun deleteTextForPdfAnnotations(documentId: Long)

    @Query("DELETE FROM annotation_index")
    fun deleteAllAnnotations()

    @Query("DELETE FROM annotation_text")
    fun deleteAllText()

    /**
     * Drops documents that were only known through a folder that is no longer
     * indexed. Documents opened directly in the viewer are kept.
     */
    @Query(
        "DELETE FROM annotation_document WHERE source = 'FOLDER' " +
            "AND documentUri NOT IN (SELECT documentUri FROM indexed_file)"
    )
    fun deleteOrphanedFolderDocuments()

    // --------------------------------------------------------------- full text

    @Insert
    fun insertText(text: AnnotationTextEntity)

    @Query("DELETE FROM annotation_text WHERE rowid = :rowId")
    fun deleteText(rowId: Long)

    @Query("SELECT rowid FROM annotation_text WHERE annotation_text MATCH :match")
    fun matchIds(match: String): List<Long>

    // ----------------------------------------------------------------- counts

    @Query("SELECT COUNT(*) FROM annotation_document")
    fun documentCount(): Int

    @Query("SELECT COUNT(*) FROM annotation_index")
    fun annotationCount(): Int

    @Query("SELECT COUNT(*) FROM annotation_index WHERE kind = 'BOOKMARK'")
    fun bookmarkCount(): Int

    @Query("SELECT kind, COUNT(*) AS count FROM annotation_index GROUP BY kind")
    fun kindCounts(): List<AnnotationKindCountRow>
}
