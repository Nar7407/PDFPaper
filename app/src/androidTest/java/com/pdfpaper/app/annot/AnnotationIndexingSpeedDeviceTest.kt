package com.pdfpaper.app.annot

import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.index.PdfIndexExtractor
import com.pdfpaper.app.index.SearchIndexDatabase
import com.pdfpaper.engine.annot.HighlightSpec
import com.pdfpaper.engine.annot.NoteSpec
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.write.PdfPageOps
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T56 acceptance: a folder of 30 documents is extracted and indexed in one
 * pass, with the quoted text coming from the real page through the engine, and
 * the result is searchable in the same run.
 *
 * The extractor and the repository are the same objects `IndexWorker` uses, so
 * this measures the real indexing path without the WorkManager scheduling.
 */
@RunWith(AndroidJUnit4::class)
class AnnotationIndexingSpeedDeviceTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private lateinit var db: SearchIndexDatabase
    private lateinit var repository: AnnotationRepository
    private lateinit var directory: File

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SearchIndexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AnnotationRepository.forDatabase(context, db)
        directory = File(context.cacheDir, "annot-speed-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        directory.deleteRecursively()
    }

    @Test
    fun thirtyDocumentsAreIndexedWithinSeconds() = runBlocking {
        val extractor = PdfIndexExtractor(context)
        val files = (1..DOCUMENTS).map { index ->
            File(directory, "report-$index.pdf").apply { writeBytes(document(index)) }
        }

        val started = System.nanoTime()
        var annotations = 0
        for (file in files) {
            val outcome = extractor.extract(file.absolutePath, file.length())
            assertTrue("${file.name}: $outcome", outcome is PdfIndexExtractor.Outcome.Text)
            outcome as PdfIndexExtractor.Outcome.Text
            // The quoted text is read out of the page through the highlight's
            // /QuadPoints, so an empty string here means the mapping broke.
            assertTrue("${file.name} lost its quoted text", outcome.annotations.any {
                it.quotedText.orEmpty().contains("Quarterly budget")
            })
            repository.store(
                AnnotationDocumentInput(
                    fingerprint = outcome.fingerprint,
                    documentUri = file.absolutePath,
                    displayName = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    pageCount = outcome.pageCount
                ),
                outcome.annotations
            )
            annotations += outcome.annotations.size
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        Log.i("T56", "indexed 30 documents with $annotations annotations in $elapsedMs ms")

        assertEquals(DOCUMENTS, repository.counts().documents)
        assertEquals(annotations, repository.counts().annotations)
        assertEquals(
            "every document must be searchable by its quoted text",
            DOCUMENTS,
            repository.list(AnnotationFilter(query = "quarterly")).size
        )
        assertTrue("indexing took $elapsedMs ms", elapsedMs < MAX_MILLIS)
    }

    /** One page of text with a highlight over it and a note beside it. */
    private fun document(index: Int): ByteArray {
        val text = "Quarterly budget report $index"
        val plain = PdfBuilder(PageSize.A4).apply {
            addPage().drawText(text, 72.0, 120.0, size = 18.0)
        }.build()
        val document = PdfDocument.open(ByteArraySource(plain)).getOrThrow()
        return document.use { open ->
            PdfPageOps.applyAnnotations(
                open,
                listOf(
                    HighlightSpec(
                        pageIndex = 0,
                        rects = listOf(PdfRect(70.0, 716.0, 330.0, 742.0)),
                        contents = "Budget of report $index"
                    ),
                    NoteSpec(
                        pageIndex = 0,
                        rect = PdfRect(40.0, 640.0, 60.0, 660.0),
                        contents = "Follow up on report $index"
                    )
                )
            ).getOrThrow()
        }
    }

    private companion object {
        const val DOCUMENTS = 30
        /** Generous for an emulator; the real number is logged above. */
        const val MAX_MILLIS = 10_000L
    }
}
