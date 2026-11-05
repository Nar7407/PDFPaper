package com.pdfpaper.app.index

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "index_folder",
    indices = [Index(value = ["treeUri"], unique = true)]
)
data class IndexFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeUri: String,
    val displayName: String,
    val addedAt: Long,
    val lastScanAt: Long? = null,
    val lastError: String? = null
)

@Entity(
    tableName = "indexed_file",
    foreignKeys = [
        ForeignKey(
            entity = IndexFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["documentUri"], unique = true),
        Index(value = ["folderId"]),
        Index(value = ["status"])
    ]
)
data class IndexedFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long,
    val documentUri: String,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val status: String,
    val pageCount: Int,
    val pagesWithoutText: Int,
    val indexedAt: Long,
    val error: String? = null
)

/**
 * FTS4 stores one row per page. The rowid is the join key to [PageMetaEntity];
 * it is assigned explicitly by the indexer, so a re-index can replace a file's
 * pages without relying on SQLite's implicit rowid behaviour.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "page_text")
data class PageTextEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    val text: String
)

@Entity(
    tableName = "page_meta",
    foreignKeys = [
        ForeignKey(
            entity = IndexedFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["fileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["fileId"])]
)
data class PageMetaEntity(
    @PrimaryKey val rowId: Long,
    val fileId: Long,
    val pageIndex: Int
)
