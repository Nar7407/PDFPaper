package com.pdfpaper.app.office

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.data.RecentFile
import com.pdfpaper.app.data.RecentStore
import com.pdfpaper.app.ui.open.OpenDocumentState
import com.pdfpaper.app.ui.open.OpenDocumentViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole open flow on a device, exactly as the screens drive it: copy the
 * picked file, detect what it is, convert office formats, store the PDF with
 * its conversion notes and remember the original name and type in Recent.
 */
@RunWith(AndroidJUnit4::class)
class OpenDocumentFlowTest {

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val application: Application
        get() = instrumentation.targetContext.applicationContext as Application

    /** Copies a test asset into the app's own cache, as the picker's copy would. */
    private fun copyAsset(name: String): File {
        val target = File(application.cacheDir, "flow-samples/$name")
        target.parentFile?.mkdirs()
        instrumentation.context.assets.open("office/$name").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    /** Runs the flow to its end and returns the settled state. */
    private fun open(name: String): OpenDocumentState = runBlocking {
        val source = copyAsset(name)
        val viewModel = OpenDocumentViewModel(application)
        viewModel.open(Uri.fromFile(source), name)
        withTimeout(30_000) {
            viewModel.state.first { it.pdf != null || it.error != null }
        }
    }

    @Test
    fun openingTheWordSampleReachesAViewerReadyPdfWithNotes() {
        val state = open("notes.docx")

        assertNull(state.error)
        val pdf = state.pdf
        assertNotNull(pdf)
        assertEquals("%PDF-", String(pdf!!.readBytes().copyOf(5), Charsets.US_ASCII))

        val converted = OfficeCache.read(pdf.absolutePath)
        assertNotNull(converted)
        assertEquals(OfficeKind.WORD, converted!!.kind)
        assertEquals("notes.docx", converted.sourceName)
        assertTrue(
            "expected conversion notes, got ${converted.warnings}",
            converted.warnings.isNotEmpty()
        )

        val recent = RecentStore(application).list().firstOrNull { it.displayName == "notes.docx" }
        assertEquals(RecentFile.KIND_DOCX, recent?.kind)
    }

    @Test
    fun openingTheLegacySampleStopsWithAnExplanation() {
        val state = open("legacy-sample.doc")

        assertNull(state.pdf)
        val error = state.error
        assertNotNull(error)
        assertTrue(error!!, error.contains("not supported"))
        assertTrue(error.contains(".docx"))
    }
}
