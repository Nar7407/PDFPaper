package com.pdfpaper.app.annot

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.index.IndexedFileStatus
import com.pdfpaper.app.index.SearchIndexDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T56 upgraded the T50 index database from version 1 to 2. The user's page
 * index must survive that upgrade: the migration is applied to a real version-1
 * file and Room then validates the result against the current entities.
 */
@RunWith(AndroidJUnit4::class)
class AnnotationMigrationDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var name: String
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        name = "migration-test-${System.nanoTime()}.db"
        databaseFile = context.getDatabasePath(name)
        databaseFile.parentFile?.mkdirs()
        deleteFiles()
        writeVersionOneDatabase()
    }

    @After
    fun tearDown() {
        deleteFiles()
    }

    private fun deleteFiles() {
        databaseFile.delete()
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
    }

    /** The T50 schema exactly as Room created it at version 1. */
    private fun writeVersionOneDatabase() {
        val db = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `index_folder` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
                "NOT NULL, `treeUri` TEXT NOT NULL, `displayName` TEXT NOT NULL, `addedAt` INTEGER " +
                "NOT NULL, `lastScanAt` INTEGER, `lastError` TEXT)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_index_folder_treeUri` " +
                "ON `index_folder` (`treeUri`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `indexed_file` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
                "NOT NULL, `folderId` INTEGER NOT NULL, `documentUri` TEXT NOT NULL, " +
                "`relativePath` TEXT NOT NULL, `displayName` TEXT NOT NULL, `sizeBytes` INTEGER " +
                "NOT NULL, `lastModified` INTEGER NOT NULL, `status` TEXT NOT NULL, `pageCount` " +
                "INTEGER NOT NULL, `pagesWithoutText` INTEGER NOT NULL, `indexedAt` INTEGER NOT " +
                "NULL, `error` TEXT, FOREIGN KEY(`folderId`) REFERENCES `index_folder`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_indexed_file_documentUri` " +
                "ON `indexed_file` (`documentUri`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_indexed_file_folderId` ON `indexed_file` (`folderId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_indexed_file_status` ON `indexed_file` (`status`)"
        )
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `page_text` USING FTS4(`text` TEXT NOT NULL, " +
                "tokenize=unicode61)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `page_meta` (`rowId` INTEGER NOT NULL, `fileId` INTEGER " +
                "NOT NULL, `pageIndex` INTEGER NOT NULL, PRIMARY KEY(`rowId`), " +
                "FOREIGN KEY(`fileId`) REFERENCES `indexed_file`(`id`) ON UPDATE NO ACTION " +
                "ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_page_meta_fileId` ON `page_meta` (`fileId`)"
        )
        db.execSQL(
            "INSERT INTO `index_folder` (`id`, `treeUri`, `displayName`, `addedAt`) " +
                "VALUES (1, 'content://tree/docs', 'Documents', 1000)"
        )
        db.execSQL(
            "INSERT INTO `indexed_file` (`id`, `folderId`, `documentUri`, `relativePath`, " +
                "`displayName`, `sizeBytes`, `lastModified`, `status`, `pageCount`, " +
                "`pagesWithoutText`, `indexedAt`) VALUES (1, 1, 'content://docs/report.pdf', " +
                "'report.pdf', 'report.pdf', 2048, 1000, 'INDEXED', 1, 0, 1000)"
        )
        db.execSQL("INSERT INTO `page_text` (`rowid`, `text`) VALUES (1, 'budget report')")
        db.execSQL("INSERT INTO `page_meta` (`rowId`, `fileId`, `pageIndex`) VALUES (1, 1, 0)")
        db.version = 1
        db.close()
    }

    @Test
    fun thePageIndexSurvivesAndTheAnnotationTablesWork() = runBlocking {
        val db = Room.databaseBuilder(context, SearchIndexDatabase::class.java, name)
            .addMigrations(SearchIndexDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.dao()
            assertEquals(1, dao.fileCount())
            assertEquals(1, dao.pageCount())
            assertEquals("report.pdf", dao.allFiles().single().displayName)
            assertEquals(IndexedFileStatus.INDEXED.name, dao.allFiles().single().status)
            assertEquals(1, dao.search("budget", null, 0, 10).size)

            // The new tables exist and are usable in the migrated file.
            val annotationDao = db.annotationDao()
            assertEquals(0, annotationDao.allDocuments().size)
            val documentId = annotationDao.insertDocument(
                AnnotationDocumentEntity(
                    fingerprint = "fp",
                    documentUri = "content://docs/report.pdf",
                    displayName = "report.pdf",
                    sizeBytes = 2048,
                    lastModified = 1000,
                    pageCount = 1,
                    indexedAt = 2000,
                    source = AnnotationRepository.SOURCE_FOLDER
                )
            )
            assertTrue(documentId > 0)
            annotationDao.insertAnnotation(
                AnnotationEntity(
                    documentId = documentId,
                    kind = AnnotationKind.HIGHLIGHT.name,
                    pageIndex = 0,
                    color = "#F5D547",
                    author = "Alice",
                    createdAt = 3000,
                    modifiedAt = null,
                    quotedText = "budget report",
                    noteText = null,
                    bounds = null,
                    objectNumber = 12
                )
            )
            assertEquals(1, annotationDao.annotationCount())
        } finally {
            db.close()
        }
    }
}
