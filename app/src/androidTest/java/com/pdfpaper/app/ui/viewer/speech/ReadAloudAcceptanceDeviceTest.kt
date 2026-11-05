package com.pdfpaper.app.ui.viewer.speech

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.speech.ChapterSource
import com.pdfpaper.engine.speech.SpeechChapter
import com.pdfpaper.engine.speech.SpeechExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T34 acceptance: the committed fixtures really are the documents the reading
 * rules are judged on. The engine is the same one the service uses, so this
 * checks the speaking order and the chapter sources without a speech engine.
 */
@RunWith(AndroidJUnit4::class)
class ReadAloudAcceptanceDeviceTest {

    private fun open(asset: String): PdfDocument {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "t34-$asset-${System.nanoTime()}.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("t34/$asset.pdf")
            .use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        return PdfDocument.open(FileSource(file)).getOrThrow()
    }

    @Test
    fun thePaperIsReadColumnByColumnAndKeepsItsOutlineChapters() {
        val document = open("t34-paper")
        val page = SpeechExtractor.extract(document.pages().first()).getOrThrow()
        val spoken = page.paragraphs.joinToString(" ") { it.text }

        // The title is spoken once, before either column, and is not split.
        assertTrue(spoken.startsWith("Listening to a Two-Column Page"))
        // Left column: Introduction and Method. Right column: Results and
        // Discussion. The order is the reading order, not the PDF object order.
        val introduction = spoken.indexOf("Introduction")
        val method = spoken.indexOf("Method")
        val results = spoken.indexOf("Results")
        val discussion = spoken.indexOf("Discussion")
        assertTrue("introduction before method", introduction < method)
        assertTrue("left column before the right: $spoken", method < results)
        assertTrue("results before discussion", results < discussion)

        val chapters = SpeechExtractor.chapters(document)
        assertEquals(
            listOf("Introduction", "Method", "Results", "Discussion"),
            chapters.map { it.title }
        )
        assertTrue(chapters.all { it.source == ChapterSource.OUTLINE })

        document.close()
    }

    @Test
    fun theBookGetsItsChaptersFromHeadings() {
        val document = open("t34-book")
        assertTrue("the book has no outline", SpeechExtractor.chapters(document).isEmpty())

        var chapters = emptyList<SpeechChapter>()
        for (page in document.pages()) {
            val extracted = SpeechExtractor.extract(page).getOrThrow()
            chapters = SpeechExtractor.mergeHeadings(chapters, extracted)
        }

        assertEquals(
            listOf(
                "Chapter One: The Lamp",
                "Chapter Two: Morning",
                "Chapter Three: The Crossing"
            ),
            chapters.map { it.title }
        )
        assertTrue(chapters.all { it.source == ChapterSource.HEADING })

        document.close()
    }
}
