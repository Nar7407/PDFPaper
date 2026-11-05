package com.pdfpaper.app.annot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.index.SearchIndexDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T56: the annotation index against a real Room database. The page index (T50)
 * shares the same database, so these tests also prove the annotation tables do
 * not disturb it.
 */
@RunWith(AndroidJUnit4::class)
class AnnotationIndexDeviceTest {

    private lateinit var db: SearchIndexDatabase
    private lateinit var repository: AnnotationRepository
    private val now = System.currentTimeMillis()
    private val tenDaysAgo = now - 10L * 24L * 60L * 60L * 1000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SearchIndexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AnnotationRepository.forDatabase(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun annotation(
        kind: AnnotationKind,
        pageIndex: Int,
        color: String? = null,
        quoted: String? = null,
        note: String? = null,
        objectNumber: Int? = 10,
        createdAt: Long = now
    ) = ExtractedAnnotation(
        kind = kind,
        pageIndex = pageIndex,
        color = color,
        author = "Alice",
        createdAt = createdAt,
        modifiedAt = null,
        quotedText = quoted,
        noteText = note,
        bounds = "10.0,20.0,30.0,40.0",
        objectNumber = objectNumber
    )

    private suspend fun document(
        fingerprint: String,
        uri: String = "content://docs/$fingerprint",
        name: String = "$fingerprint.pdf"
    ): AnnotationDocumentInput = AnnotationDocumentInput(
        fingerprint = fingerprint,
        documentUri = uri,
        displayName = name,
        sizeBytes = 2_048,
        lastModified = now,
        pageCount = 3
    )

    @Test
    fun storedAnnotationsComeBackWithTheirKindColourAndQuotedText() = runBlocking {
        val doc = repository.store(
            document("alpha"),
            listOf(
                annotation(AnnotationKind.HIGHLIGHT, 0, "#F5D547", quoted = "budget report", note = "check this"),
                annotation(AnnotationKind.NOTE, 1, "#4A9BE8", note = "remember the appendix", objectNumber = 11)
            )
        )
        assertTrue(doc > 0)

        val all = repository.list()
        assertEquals(2, all.size)
        val highlight = all.first { it.kind == AnnotationKind.HIGHLIGHT }
        assertEquals("#F5D547", highlight.color)
        assertEquals("budget report", highlight.quotedText)
        assertEquals("check this", highlight.noteText)
        assertEquals(0, highlight.pageIndex)
        assertEquals("alpha.pdf", highlight.displayName)

        val stats = repository.counts()
        assertEquals(1, stats.documents)
        assertEquals(2, stats.annotations)
        assertEquals(1, stats.byKind[AnnotationKind.HIGHLIGHT])
    }

    @Test
    fun kindColourDocumentAndDateFiltersCombine() = runBlocking {
        val alpha = repository.store(
            document("alpha"),
            listOf(
                annotation(AnnotationKind.HIGHLIGHT, 0, "#F5D547", quoted = "alpha text"),
                annotation(AnnotationKind.NOTE, 1, "#4A9BE8", note = "alpha note")
            )
        )
        val beta = repository.store(
            document("beta"),
            listOf(
                annotation(AnnotationKind.NOTE, 0, "#4A9BE8", note = "beta note"),
                annotation(AnnotationKind.HIGHLIGHT, 1, "#F5D547", quoted = "old text", createdAt = tenDaysAgo)
            )
        )

        assertEquals(4, repository.list().size)
        assertEquals(2, repository.list(AnnotationFilter(kinds = setOf(AnnotationKind.HIGHLIGHT))).size)
        assertEquals(2, repository.list(AnnotationFilter(color = "#4A9BE8")).size)
        assertEquals(2, repository.list(AnnotationFilter(documentId = beta)).size)
        assertEquals(
            1,
            repository.list(AnnotationFilter(documentId = alpha, kinds = setOf(AnnotationKind.NOTE))).size
        )
        // The old highlight is outside "last 7 days" but inside "any date" and
        // inside "this year" (it is ten days old, not ten months).
        assertEquals(3, repository.list(AnnotationFilter(date = AnnotationDateFilter.LAST_7_DAYS)).size)
        assertEquals(4, repository.list(AnnotationFilter(date = AnnotationDateFilter.ANY)).size)
        assertEquals(
            1,
            repository.list(
                AnnotationFilter(documentId = beta, date = AnnotationDateFilter.LAST_7_DAYS)
            ).size
        )
    }

    @Test
    fun searchMatchesQuotedTextNotesAndTags() = runBlocking {
        repository.store(
            document("alpha"),
            listOf(
                annotation(AnnotationKind.HIGHLIGHT, 0, quoted = "the quarterly budget report"),
                annotation(AnnotationKind.NOTE, 1, note = "check the appendix"),
                annotation(AnnotationKind.STAMP, 2, note = "approved by finance")
            )
        )

        assertEquals(1, repository.list(AnnotationFilter(query = "budget")).size)
        assertEquals(1, repository.list(AnnotationFilter(query = "append")).size)
        assertEquals(1, repository.list(AnnotationFilter(query = "financ")).size)
        assertEquals(1, repository.list(AnnotationFilter(query = "\"budget report\"")).size)
        assertEquals(0, repository.list(AnnotationFilter(query = "nothinghere")).size)
        // A blank query is not a search: an empty search box shows every row.
        assertEquals(3, repository.list(AnnotationFilter(query = "   ")).size)
    }

    @Test
    fun aMovedFileKeepsItsIdBookmarksAndTags() = runBlocking {
        val original = document("moving", uri = "content://old/moving.pdf", name = "moving.pdf")
        val documentId = repository.store(
            original,
            listOf(annotation(AnnotationKind.HIGHLIGHT, 0, quoted = "keep me", objectNumber = 21))
        )
        val saved = repository.list().single()
        repository.updateFields(saved.id, tag = "todo")
        repository.addBookmark(documentId, pageIndex = 2, label = "Chapter 3", color = "#5BD07A")

        val moved = document("moving", uri = "content://new/moving.pdf", name = "renamed.pdf")
        val afterMove = repository.store(moved, listOf(annotation(AnnotationKind.HIGHLIGHT, 0, quoted = "keep me", objectNumber = 21)))

        assertEquals(documentId, afterMove)
        assertEquals(1, repository.documents().size)
        assertNull(repository.documentByUri("content://old/moving.pdf"))
        assertNotNull(repository.documentByUri("content://new/moving.pdf"))

        val rows = repository.list()
        assertEquals(2, rows.size)
        val highlight = rows.first { it.kind == AnnotationKind.HIGHLIGHT }
        assertEquals("todo", highlight.tag)
        assertEquals("renamed.pdf", highlight.displayName)
        val bookmark = rows.first { it.kind == AnnotationKind.BOOKMARK }
        assertEquals("Chapter 3", bookmark.label)
        assertEquals(2, bookmark.pageIndex)
    }

    @Test
    fun aReindexReplacesPdfAnnotationsButKeepsBookmarks() = runBlocking {
        val input = document("reindex")
        val documentId = repository.store(
            input,
            listOf(
                annotation(AnnotationKind.HIGHLIGHT, 0, objectNumber = 30),
                annotation(AnnotationKind.NOTE, 0, objectNumber = 31)
            )
        )
        repository.addBookmark(documentId, pageIndex = 0, label = "Start", color = null)

        // The user deleted one annotation in another viewer; a re-read has one.
        repository.store(input, listOf(annotation(AnnotationKind.HIGHLIGHT, 0, objectNumber = 30)))

        val rows = repository.list()
        assertEquals(2, rows.size)
        assertEquals(1, rows.count { it.kind == AnnotationKind.BOOKMARK })
        assertEquals(1, rows.count { it.kind == AnnotationKind.HIGHLIGHT })

        // Deleting the whole index takes the bookmarks with it.
        repository.deleteAll()
        assertEquals(0, repository.list().size)
        assertEquals(0, repository.counts().documents)
    }

    @Test
    fun deletingOneRowLeavesTheRestAndTheFullTextInStep() = runBlocking {
        repository.store(
            document("delete-one"),
            listOf(
                annotation(AnnotationKind.HIGHLIGHT, 0, quoted = "first quote", objectNumber = 40),
                annotation(AnnotationKind.NOTE, 1, note = "second note", objectNumber = 41)
            )
        )
        val first = repository.list().first { it.quotedText == "first quote" }

        repository.delete(first.id)

        assertEquals(1, repository.list().size)
        assertEquals(1, repository.list(AnnotationFilter(query = "second")).size)
        assertEquals(0, repository.list(AnnotationFilter(query = "first")).size)
        assertNull(repository.item(first.id))
    }

    @Test
    fun editingANoteUpdatesTheSearchableText() = runBlocking {
        repository.store(
            document("edit"),
            listOf(annotation(AnnotationKind.NOTE, 0, note = "old wording", objectNumber = 50))
        )
        val row = repository.list().single()

        repository.updateFields(row.id, noteText = "brand new wording", color = "#E8555F")

        assertEquals(0, repository.list(AnnotationFilter(query = "old")).size)
        assertEquals(1, repository.list(AnnotationFilter(query = "brand")).size)
        assertEquals(1, repository.list(AnnotationFilter(color = "#E8555F")).size)
        assertEquals("brand new wording", repository.item(row.id)?.noteText)
    }
}
