package com.pdfpaper.app.annot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.index.PdfIndexExtractor
import com.pdfpaper.app.index.SearchIndexDatabase
import com.pdfpaper.engine.annot.HighlightSpec
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.layout
import com.pdfpaper.engine.write.PdfPageOps
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * T56: the hub's edit and delete actions write through the T55 save path, so a
 * deleted annotation leaves the PDF and the index together, an edited note is
 * in the file, and a bookmark can be mirrored into the document outline.
 */
@RunWith(AndroidJUnit4::class)
class AnnotationEditorDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: SearchIndexDatabase
    private lateinit var repository: AnnotationRepository
    private lateinit var editor: AnnotationEditor
    private lateinit var file: File

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SearchIndexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AnnotationRepository.forDatabase(context, db)
        editor = AnnotationEditor(context, repository)
        AppSettings.setBookmarkOutlineEnabled(context, true)
        file = File(context.filesDir, "hub-test-${System.nanoTime()}.pdf")
        file.writeBytes(pdfWithHighlight())
    }

    @After
    fun tearDown() {
        runCatching { file.setWritable(true) }
        runCatching { file.delete() }
        db.close()
    }

    /** One A4 page of text with a highlight over the first line. */
    private fun pdfWithHighlight(): ByteArray {
        val plain = PdfBuilder(PageSize.A4).apply {
            addPage().apply {
                drawText("the quick brown fox", 40.0, 60.0, 14.0)
            }
        }.build()
        return PdfDocument.open(ByteArraySource(plain)).getOrThrow().use { document ->
            PdfPageOps.edit(
                document,
                annotations = listOf(
                    HighlightSpec(
                        pageIndex = 0,
                        rects = listOf(PdfRect(30.0, 760.0, 400.0, 800.0)),
                        contents = "first note",
                        title = "Alice"
                    )
                )
            ).getOrThrow()
        }
    }

    /** Indexes the file and returns its document id and the highlight row. */
    private suspend fun indexFile(): Pair<Long, AnnotationItem> {
        val outcome = PdfIndexExtractor(context)
            .extract(file.absolutePath, file.length())
        assertTrue("expected text outcome, got $outcome", outcome is PdfIndexExtractor.Outcome.Text)
        val text = outcome as PdfIndexExtractor.Outcome.Text
        assertEquals(1, text.annotations.size)
        val documentId = repository.store(
            AnnotationDocumentInput(
                fingerprint = text.fingerprint,
                documentUri = file.absolutePath,
                displayName = file.name,
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
                pageCount = text.pageCount
            ),
            text.annotations,
            AnnotationRepository.SOURCE_OPENED
        )
        return documentId to repository.list().first { it.kind == AnnotationKind.HIGHLIGHT }
    }

    private fun annotationsInFile(): List<com.pdfpaper.engine.annot.PdfAnnotation> =
        PdfDocument.open(ByteArraySource(file.readBytes())).getOrThrow().use { document ->
            document.pages().flatMap { it.annotations() }
        }

    @Test
    fun theExtractorReadsTheQuotedTextThroughTheQuadPoints() = runBlocking {
        val (documentId, highlight) = indexFile()

        assertTrue(documentId > 0)
        assertEquals(0, highlight.pageIndex)
        assertEquals("Alice", highlight.author)
        assertEquals("first note", highlight.noteText)
        assertTrue("quoted text was \"${highlight.quotedText}\"", highlight.quotedText?.contains("quick") == true)
        assertTrue(highlight.objectNumber != null)
    }

    @Test
    fun editingTheNoteRewritesThePdfAndTheIndex() = runBlocking {
        val (_, highlight) = indexFile()
        // The app's data directory can already hold other documents (a manual
        // acceptance run copies its own PDFs there), so "the same file, not a
        // new copy" is checked as a set of names rather than an absolute count.
        val before = pdfFileNames()

        val result = editor.update(highlight, noteText = "edited in the hub")

        assertEquals("Annotation saved", result.getOrThrow())
        // The file has the new note (incremental append keeps the original bytes
        // in front, so a fresh open must read the newest revision).
        assertEquals("edited in the hub", annotationsInFile().single().contents)
        assertEquals("edited in the hub", repository.item(highlight.id)?.noteText)
        assertEquals(before, pdfFileNames())
    }

    @Test
    fun deletingTheHighlightRemovesItFromThePdfAndTheIndex() = runBlocking {
        val (_, highlight) = indexFile()

        val result = editor.delete(highlight)

        assertEquals("Annotation deleted", result.getOrThrow())
        assertEquals(0, annotationsInFile().size)
        assertEquals(0, repository.list().size)
        assertEquals(0, repository.counts().annotations)
        // The page and its text are still there.
        PdfDocument.open(ByteArraySource(file.readBytes())).getOrThrow().use { document ->
            assertEquals(1, document.pages().size)
            pdfTextIsStillThere(document)
        }
    }

    private fun pdfTextIsStillThere(document: PdfDocument) {
        val layout = document.pages().single().layout()
        assertTrue(layout.text.contains("quick"))
    }

    @Test
    fun aBookmarkIsWrittenIntoTheDocumentOutline() = runBlocking {
        val (documentId, _) = indexFile()
        repository.addBookmark(documentId, pageIndex = 0, label = "Chapter 1", color = "#F5D547")

        val written = editor.syncBookmarks(documentId).getOrThrow()

        assertTrue(written)
        val raw = String(file.readBytes(), StandardCharsets.ISO_8859_1)
        assertTrue("outline title missing", raw.contains("Chapter 1"))
        assertTrue("bibliographic marker missing", raw.contains("/PdfPaperBookmark"))
        assertTrue("outline root missing", raw.contains("/Outlines"))
        // The outline is an incremental update: the original page is untouched.
        assertEquals(1, PdfDocument.open(ByteArraySource(file.readBytes())).getOrThrow().use { it.pages().size })
    }

    @Test
    fun deletingTheBookmarkUnlinksItFromTheOutline() = runBlocking {
        val (documentId, _) = indexFile()
        repository.addBookmark(documentId, pageIndex = 0, label = "Chapter 1", color = null)
        val bookmark = repository.list().first { it.kind == AnnotationKind.BOOKMARK }
        assertTrue(editor.syncBookmarks(documentId).getOrThrow())

        val result = editor.delete(bookmark)

        assertEquals("Bookmark deleted", result.getOrThrow())
        // Only the bookmark goes: the file's own highlight is still indexed.
        assertEquals(1, repository.list().size)
        assertEquals(0, repository.list(AnnotationFilter(kinds = setOf(AnnotationKind.BOOKMARK))).size)
        val raw = String(file.readBytes(), StandardCharsets.ISO_8859_1)
        // The newer revision has an empty outline root: the item is unreachable.
        val lastOutline = raw.lastIndexOf("/Count 0")
        assertTrue("the last outline revision should be empty", lastOutline > 0)
    }

    @Test
    fun aReadOnlySourceIsRefusedBeforeAnythingIsWritten() = runBlocking {
        val (_, highlight) = indexFile()
        val before = file.readBytes()
        file.setWritable(false)

        val result = editor.update(highlight, noteText = "should not be written")

        assertTrue(result.isFailure)
        assertTrue(
            "message was ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("read-only") == true
        )
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test
    fun anAnnotationThatIsNotInTheFileAnyMoreIsNotRecreated() = runBlocking {
        val (_, highlight) = indexFile()
        // Another app removed the annotation; the hub's row is stale.
        PdfDocument.open(ByteArraySource(file.readBytes())).getOrThrow().use { document ->
            val dropped = document.pages().single().annotations().single().ref!!.objNum
            val cleaned = PdfPageOps.edit(document, droppedAnnots = setOf(dropped)).getOrThrow()
            file.writeBytes(cleaned)
        }

        val result = editor.delete(highlight)

        assertTrue(result.isFailure)
        assertTrue(
            "message was ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("no longer in the file") == true
        )
    }

    private fun pdfFileNames(): Set<String> =
        (context.filesDir.listFiles() ?: emptyArray())
            .filter { it.extension == "pdf" }
            .map { it.name }
            .toSet()

    @Test
    fun theSavedFileIsTheSameFileNotANewCopy() = runBlocking {
        val (_, highlight) = indexFile()
        val path = file.absolutePath
        val before = file.length()

        editor.update(highlight, noteText = "same file").getOrThrow()

        assertTrue(File(path).exists())
        assertTrue("the file should have grown by the appended revision", file.length() > before)
    }

    private fun rawText(target: File): String = String(target.readBytes(), StandardCharsets.ISO_8859_1)
}
