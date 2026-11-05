package com.pdfpaper.app.annot

import android.content.Context
import androidx.room.withTransaction
import com.pdfpaper.app.index.SearchIndexDatabase
import com.pdfpaper.app.index.SearchQueryBuilder
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.RandomAccessSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** The document identity the annotation index needs; see [AnnotationFingerprint]. */
data class AnnotationDocumentInput(
    val fingerprint: String,
    val documentUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val pageCount: Int
)

/** One annotation read from a file, before it is stored. */
data class ExtractedAnnotation(
    val kind: AnnotationKind,
    val pageIndex: Int,
    val color: String?,
    val author: String?,
    val createdAt: Long,
    val modifiedAt: Long?,
    val quotedText: String?,
    val noteText: String?,
    val bounds: String?,
    val objectNumber: Int?
)

/**
 * T56: identifies a document by its content, not by its path. A file that was
 * moved or renamed keeps its annotations; a file whose bytes changed gets a
 * new identity. The hash covers the size plus the first and last 8 KiB, so it
 * is cheap enough to compute while indexing and stable across providers.
 */
object AnnotationFingerprint {

    private const val WINDOW = 8 * 1024

    fun of(source: RandomAccessSource): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val head = source.read(0, WINDOW.coerceAtMost(source.size.toInt()))
        val tailOffset = (source.size - WINDOW).coerceAtLeast(0)
        val tail = source.read(tailOffset, (source.size - tailOffset).toInt())
        digest.update(source.size.toString().toByteArray())
        digest.update(0)
        digest.update(head)
        digest.update(0)
        digest.update(tail)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * The one place the app reads and writes the local annotation index (T56).
 * Local only: nothing is uploaded, and the database is excluded from backups
 * like the T50 page index.
 */
class AnnotationRepository private constructor(context: Context, database: SearchIndexDatabase) {

    private val appContext = context.applicationContext
    private val db = database
    private val dao = db.annotationDao()

    /**
     * Stores [rows] as the document's complete set of PDF annotations. App
     * bookmarks survive a re-index (they are not in the file), and a document
     * found under a new URI keeps its id and therefore its bookmarks.
     */
    suspend fun store(
        document: AnnotationDocumentInput,
        rows: List<ExtractedAnnotation>,
        source: String = SOURCE_FOLDER
    ): Long = io { storeInTransaction(document, rows, source) }

    /**
     * The same write, on the caller's transaction. The indexer uses this so a
     * file's page text, file row and annotations are committed together; Room
     * transactions are re-entrant, and this method must not switch dispatcher
     * inside one.
     */
    internal suspend fun storeInTransaction(
        document: AnnotationDocumentInput,
        rows: List<ExtractedAnnotation>,
        source: String
    ): Long {
        val now = System.currentTimeMillis()
        return db.withTransaction {
            val documentId = upsertDocument(document, source, now)
            // A tag is app metadata, not part of the PDF: keep it on an
            // annotation that survives the re-read (same page and object).
            val previousTags = dao.rowsForDocument(documentId)
                .filter { it.kind != AnnotationKind.BOOKMARK.name && it.objectNumber != null }
                .associate { (it.pageIndex to it.objectNumber!!) to it.tag }
            dao.deleteTextForPdfAnnotations(documentId)
            dao.deletePdfAnnotationsForDocument(documentId)
            for (row in rows) {
                val id = dao.insertAnnotation(
                    AnnotationEntity(
                        documentId = documentId,
                        kind = row.kind.name,
                        pageIndex = row.pageIndex,
                        color = row.color,
                        author = row.author,
                        createdAt = row.createdAt,
                        modifiedAt = row.modifiedAt,
                        quotedText = row.quotedText,
                        noteText = row.noteText,
                        bounds = row.bounds,
                        objectNumber = row.objectNumber,
                        tag = previousTags[row.pageIndex to row.objectNumber]
                    )
                )
                val text = searchableText(row.quotedText, row.noteText)
                if (text.isNotEmpty()) dao.insertText(AnnotationTextEntity(id, text))
            }
            documentId
        }
    }

    /**
     * Makes sure a document row exists for a file the user opened, without
     * touching its annotations. Used by the bookmark action and by the viewer's
     * own index refresh.
     */
    suspend fun ensureDocument(
        document: AnnotationDocumentInput,
        source: String = SOURCE_OPENED
    ): Long = io {
        db.withTransaction { upsertDocument(document, source, System.currentTimeMillis()) }
    }

    private fun upsertDocument(
        document: AnnotationDocumentInput,
        source: String,
        now: Long
    ): Long {
        val byFingerprint = dao.documentByFingerprint(document.fingerprint)
        if (byFingerprint != null) {
            // Same bytes at (possibly) a new place: re-link, keep the id.
            if (byFingerprint.documentUri != document.documentUri ||
                byFingerprint.displayName != document.displayName ||
                byFingerprint.sizeBytes != document.sizeBytes ||
                byFingerprint.lastModified != document.lastModified
            ) {
                dao.updateDocumentLocation(
                    byFingerprint.id,
                    document.documentUri,
                    document.displayName,
                    document.sizeBytes,
                    document.lastModified,
                    now
                )
            }
            if (source == SOURCE_FOLDER && byFingerprint.source != SOURCE_FOLDER) {
                dao.updateDocument(byFingerprint.copy(source = SOURCE_FOLDER))
            }
            return byFingerprint.id
        }
        val byUri = dao.documentByUri(document.documentUri)
        if (byUri != null) {
            // Same path, different bytes: the file was edited or replaced.
            dao.updateDocument(
                byUri.copy(
                    fingerprint = document.fingerprint,
                    displayName = document.displayName,
                    sizeBytes = document.sizeBytes,
                    lastModified = document.lastModified,
                    pageCount = document.pageCount,
                    indexedAt = now,
                    source = if (source == SOURCE_FOLDER) SOURCE_FOLDER else byUri.source
                )
            )
            return byUri.id
        }
        return dao.insertDocument(
            AnnotationDocumentEntity(
                fingerprint = document.fingerprint,
                documentUri = document.documentUri,
                displayName = document.displayName,
                sizeBytes = document.sizeBytes,
                lastModified = document.lastModified,
                pageCount = document.pageCount,
                indexedAt = now,
                source = source
            )
        )
    }

    suspend fun documents(): List<AnnotationDocument> = io { dao.allDocuments().map { it.toModel() } }

    suspend fun document(documentId: Long): AnnotationDocument? = io {
        dao.document(documentId)?.toModel()
    }

    /** Remembers the new size/time after a save wrote the file (T55/T56). */
    suspend fun recordSaved(documentId: Long, sizeBytes: Long, lastModified: Long) = io {
        val existing = dao.document(documentId) ?: return@io
        dao.updateDocumentLocation(
            documentId,
            existing.documentUri,
            existing.displayName,
            sizeBytes,
            lastModified,
            System.currentTimeMillis()
        )
        Unit
    }

    suspend fun documentByUri(documentUri: String): AnnotationDocument? = io {
        dao.documentByUri(documentUri)?.toModel()
    }

    suspend fun list(
        filter: AnnotationFilter = AnnotationFilter(),
        limit: Int = 5000
    ): List<AnnotationItem> = io {
        val rows = if (filter.query.isNotBlank()) {
            val match = SearchQueryBuilder.parse(filter.query).matchExpression
            if (match.isNullOrBlank()) return@io emptyList<AnnotationItem>()
            val ids = dao.matchIds(match)
            if (ids.isEmpty()) emptyList() else dao.rowsByIds(ids)
        } else {
            dao.allRows()
        }
        val cutoff = cutoffFor(filter.date)
        rows.asSequence()
            .filter { row ->
                val kind = runCatching { AnnotationKind.valueOf(row.kind) }.getOrNull()
                    ?: return@filter false
                if (filter.kinds.isNotEmpty() && kind !in filter.kinds) return@filter false
                if (filter.color != null && row.color != filter.color) return@filter false
                if (filter.documentId != null && row.documentId != filter.documentId) return@filter false
                if ((row.modifiedAt ?: row.createdAt) < cutoff) return@filter false
                true
            }
            .map { it.toItem() }
            .take(limit)
            .toList()
    }

    suspend fun item(id: Long): AnnotationItem? = io {
        val entity = dao.annotation(id) ?: return@io null
        val document = dao.document(entity.documentId) ?: return@io null
        AnnotationRow(
            id = entity.id,
            documentId = entity.documentId,
            documentUri = document.documentUri,
            displayName = document.displayName,
            pageIndex = entity.pageIndex,
            kind = entity.kind,
            color = entity.color,
            author = entity.author,
            createdAt = entity.createdAt,
            modifiedAt = entity.modifiedAt,
            quotedText = entity.quotedText,
            noteText = entity.noteText,
            bounds = entity.bounds,
            objectNumber = entity.objectNumber,
            label = entity.label,
            tag = entity.tag
        ).toItem()
    }

    suspend fun bookmarkCount(documentId: Long): Int = io {
        dao.bookmarkIdsForDocument(documentId).size
    }

    /** Adds an app bookmark for a page; returns its row id. */
    suspend fun addBookmark(
        documentId: Long,
        pageIndex: Int,
        label: String?,
        color: String?
    ): Long = io {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val id = dao.insertAnnotation(
                AnnotationEntity(
                    documentId = documentId,
                    kind = AnnotationKind.BOOKMARK.name,
                    pageIndex = pageIndex,
                    color = color,
                    createdAt = now,
                    modifiedAt = now,
                    label = label?.takeIf { it.isNotBlank() },
                    author = null
                )
            )
            val text = searchableText(label, null)
            if (text.isNotEmpty()) dao.insertText(AnnotationTextEntity(id, text))
            id
        }
    }

    /** Bookmarks of one document, in page order; used when saving the outline. */
    suspend fun bookmarks(documentId: Long): List<AnnotationItem> = io {
        dao.rowsForDocument(documentId)
            .filter { it.kind == AnnotationKind.BOOKMARK.name }
            .map { it.toItem() }
    }

    suspend fun updateFields(
        id: Long,
        noteText: String? = null,
        color: String? = null,
        label: String? = null,
        tag: String? = null
    ): Boolean = io {
        val existing = dao.annotation(id) ?: return@io false
        val updated = existing.copy(
            noteText = noteText ?: existing.noteText,
            color = color ?: existing.color,
            label = label ?: existing.label,
            tag = tag ?: existing.tag,
            modifiedAt = System.currentTimeMillis()
        )
        db.withTransaction {
            dao.updateAnnotation(updated)
            dao.deleteText(id)
            val text = searchableText(updated.quotedText, updated.noteText, updated.label)
            if (text.isNotEmpty()) dao.insertText(AnnotationTextEntity(id, text))
        }
        true
    }

    suspend fun delete(id: Long) = io {
        db.withTransaction {
            dao.deleteText(id)
            dao.deleteAnnotationRow(id)
        }
        Unit
    }

    suspend fun deleteAll() = io {
        db.withTransaction {
            dao.deleteAllText()
            dao.deleteAllAnnotations()
            dao.deleteAllDocuments()
        }
        Unit
    }

    suspend fun counts(): AnnotationStats = io {
        val kinds = dao.kindCounts().mapNotNull { row ->
            runCatching { AnnotationKind.valueOf(row.kind) }.getOrNull()?.let { it to row.count }
        }.toMap()
        AnnotationStats(
            documents = dao.documentCount(),
            annotations = dao.annotationCount(),
            bookmarks = dao.bookmarkCount(),
            byKind = kinds
        )
    }

    /** Drops annotation documents for folders that are no longer indexed. */
    suspend fun pruneFolderDocuments() = io {
        dao.deleteOrphanedFolderDocuments()
        Unit
    }

    private fun cutoffFor(filter: AnnotationDateFilter): Long {
        val now = System.currentTimeMillis()
        return when (filter) {
            AnnotationDateFilter.ANY -> 0L
            AnnotationDateFilter.LAST_7_DAYS -> now - 7L * 24L * 60L * 60L * 1000L
            AnnotationDateFilter.LAST_30_DAYS -> now - 30L * 24L * 60L * 60L * 1000L
            AnnotationDateFilter.THIS_YEAR -> java.time.LocalDate.now()
                .withDayOfYear(1)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }
    }

    private fun searchableText(vararg parts: String?): String =
        parts.filterNotNull().filter { it.isNotBlank() }
            .joinToString("\n")
            .take(MAX_SEARCH_CHARS)

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val SOURCE_FOLDER = "FOLDER"
        const val SOURCE_OPENED = "OPENED"
        private const val MAX_SEARCH_CHARS = 200_000

        @Volatile
        private var instance: AnnotationRepository? = null

        fun get(context: Context): AnnotationRepository =
            instance ?: synchronized(this) {
                instance ?: AnnotationRepository(context, SearchIndexDatabase.get(context.applicationContext))
                    .also { instance = it }
            }

        /**
         * The same logic on a database the caller owns. Used by the connected
         * tests so they can run against an in-memory index and leave the real
         * one alone.
         */
        internal fun forDatabase(context: Context, database: SearchIndexDatabase): AnnotationRepository =
            AnnotationRepository(context, database)

        /** Pages the hub can jump to for an item, clamped to the real count. */
        fun pageFor(item: AnnotationItem, pageCount: Int): Int =
            item.pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

        /** A short one-line description for a share sheet or a list row. */
        fun summary(item: AnnotationItem): String {
            val where = "page ${item.pageIndex + 1}"
            val text = item.text.replace('\n', ' ').trim()
            return if (text.isEmpty()) "${item.kind.label} · $where" else "${item.kind.label} · $where · $text"
        }
    }
}

private fun AnnotationDocumentEntity.toModel() = AnnotationDocument(
    id = id,
    fingerprint = fingerprint,
    documentUri = documentUri,
    displayName = displayName,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    pageCount = pageCount,
    indexedAt = indexedAt,
    source = source
)

private fun AnnotationRow.toItem(): AnnotationItem = AnnotationItem(
    id = id,
    documentId = documentId,
    documentUri = documentUri,
    displayName = displayName,
    kind = runCatching { AnnotationKind.valueOf(kind) }.getOrDefault(AnnotationKind.NOTE),
    pageIndex = pageIndex,
    color = color,
    author = author,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    quotedText = quotedText,
    noteText = noteText,
    bounds = bounds,
    objectNumber = objectNumber,
    label = label,
    tag = tag
)

/** Bounds of an item as a rectangle, for the viewer's transient highlight. */
fun AnnotationItem.boundsRect(): PdfRect? = parseBounds(bounds)
