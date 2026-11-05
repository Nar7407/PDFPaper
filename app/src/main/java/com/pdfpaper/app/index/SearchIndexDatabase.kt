package com.pdfpaper.app.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pdfpaper.app.annot.AnnotationDao
import com.pdfpaper.app.annot.AnnotationDocumentEntity
import com.pdfpaper.app.annot.AnnotationEntity
import com.pdfpaper.app.annot.AnnotationTextEntity

@Database(
    entities = [
        IndexFolderEntity::class,
        IndexedFileEntity::class,
        PageTextEntity::class,
        PageMetaEntity::class,
        AnnotationDocumentEntity::class,
        AnnotationEntity::class,
        AnnotationTextEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class SearchIndexDatabase : RoomDatabase() {
    abstract fun dao(): SearchIndexDao

    /** T56: annotations, bookmarks and their full-text table. */
    abstract fun annotationDao(): AnnotationDao

    companion object {
        const val NAME = "pdfpaper-search-index.db"

        /**
         * T56 adds the annotation tables to the T50 index. The page index is
         * kept: its rows are still valid and rebuilding it would re-read every
         * document for no reason.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `annotation_document` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`fingerprint` TEXT NOT NULL, " +
                        "`documentUri` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`lastModified` INTEGER NOT NULL, " +
                        "`pageCount` INTEGER NOT NULL, " +
                        "`indexedAt` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_annotation_document_fingerprint` " +
                        "ON `annotation_document` (`fingerprint`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_annotation_document_documentUri` " +
                        "ON `annotation_document` (`documentUri`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `annotation_index` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`documentId` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`pageIndex` INTEGER NOT NULL, " +
                        "`color` TEXT, " +
                        "`author` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`modifiedAt` INTEGER, " +
                        "`quotedText` TEXT, " +
                        "`noteText` TEXT, " +
                        "`bounds` TEXT, " +
                        "`objectNumber` INTEGER, " +
                        "`label` TEXT, " +
                        "`tag` TEXT, " +
                        "FOREIGN KEY(`documentId`) REFERENCES `annotation_document`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_annotation_index_documentId` " +
                        "ON `annotation_index` (`documentId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_annotation_index_kind` " +
                        "ON `annotation_index` (`kind`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_annotation_index_color` " +
                        "ON `annotation_index` (`color`)"
                )
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `annotation_text` " +
                        "USING FTS4(`text` TEXT, tokenize=unicode61)"
                )
            }
        }

        @Volatile
        private var instance: SearchIndexDatabase? = null

        fun get(context: Context): SearchIndexDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SearchIndexDatabase::class.java,
                    NAME
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
