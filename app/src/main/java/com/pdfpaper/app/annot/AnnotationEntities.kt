package com.pdfpaper.app.annot

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * T56: the annotation index. A document row is keyed by a content fingerprint
 * (size plus a hash of the head and tail bytes), so a file that was moved or
 * renamed is re-linked to the annotations and bookmarks it already had.
 */
@Entity(
    tableName = "annotation_document",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["documentUri"])
    ]
)
data class AnnotationDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    val documentUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val pageCount: Int,
    val indexedAt: Long,
    /** "FOLDER" (found by the indexer) or "OPENED" (opened in the viewer). */
    val source: String
)

/**
 * One annotation or bookmark. `objectNumber` is the annotation's object number
 * in the file that was read (null for an app bookmark), which is what an edit
 * from the hub passes to the engine.
 */
@Entity(
    tableName = "annotation_index",
    foreignKeys = [
        ForeignKey(
            entity = AnnotationDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["documentId"]),
        Index(value = ["kind"]),
        Index(value = ["color"])
    ]
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "documentId") val documentId: Long,
    val kind: String,
    val pageIndex: Int,
    val color: String? = null,
    val author: String? = null,
    val createdAt: Long,
    val modifiedAt: Long? = null,
    val quotedText: String? = null,
    val noteText: String? = null,
    /** "l,b,r,t" in PDF user space, or null when the annotation has no box. */
    val bounds: String? = null,
    val objectNumber: Int? = null,
    val label: String? = null,
    val tag: String? = null
)

/**
 * FTS4 over the quoted text and the note text. The rowid is the annotation id
 * and is maintained explicitly, the same way `page_text` is (T50), so a
 * re-index replaces one file's rows without touching the others.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "annotation_text")
data class AnnotationTextEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    val text: String
)
