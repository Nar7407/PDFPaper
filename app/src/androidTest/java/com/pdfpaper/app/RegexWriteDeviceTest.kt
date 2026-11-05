package com.pdfpaper.app

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.ui.search.DEFAULT_REGEX_FLAGS
import com.pdfpaper.app.ui.redact.RedactionFindMode
import com.pdfpaper.app.ui.viewer.ViewerViewModel
import com.pdfpaper.engine.annot.AnnotationType
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.extractText
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T65 Task 8 on a real provider: highlight-all writes a copy with one
 * /Highlight per match and leaves the opened file byte-identical, and the
 * redaction hand-off prefills T28's panel, which removes the matched text in
 * its own verified copy without touching the original.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class RegexWriteDeviceTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<Uri>()
    private val stores = mutableListOf<ViewModelStore>()

    private lateinit var name: String
    private lateinit var uri: Uri
    private lateinit var original: ByteArray

    private val pattern = "[A-Z]{2}-\\d{1,4}"

    @Before
    fun setUp() {
        original = PdfBuilder(PageSize.A4).apply {
            addPage().apply { drawText("Invoice AB-1234 and AB-1234", 64.0, 90.0, 12.0) }
            addPage().apply { drawText("Code XY-9 only", 64.0, 90.0, 12.0) }
            addPage().apply { drawText("Nothing to find here", 64.0, 90.0, 12.0) }
        }.build()
        name = "pdfpaper-t65-write-${System.nanoTime()}.pdf"
        uri = insertDownload(name, original)
    }

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        (opened + uri).forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    // ------------------------------------------------------------- helpers

    private fun insertDownload(fileName: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val created = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: error("the Downloads collection refused a new document")
        context.contentResolver.openOutputStream(created)!!.use { it.write(bytes) }
        context.contentResolver.update(
            created,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        opened += created
        return created
    }

    private fun newDownload(fileName: String): Uri = insertDownload(fileName, ByteArray(0))

    private fun bytesOf(location: Uri): ByteArray =
        context.contentResolver.openInputStream(location)!!.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun newModel(): ViewerViewModel {
        val store = ViewModelStore()
        stores += store
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ViewerViewModel(context) as T
        }
        return ViewModelProvider(store, factory)[ViewerViewModel::class.java]
    }

    private fun waitUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun open(model: ViewerViewModel) {
        model.load(uri.toString(), name)
        waitUntil("the document to open") {
            !model.state.value.isLoading && model.state.value.pageCount == 3
        }
        assertEquals(null, model.state.value.error)
    }

    private fun runRegex(model: ViewerViewModel) {
        model.runDocumentRegex(pattern, DEFAULT_REGEX_FLAGS)
        waitUntil("the document regex run") {
            !model.regex.value.running &&
                (model.regex.value.rows.size == 3 || model.regex.value.error != null)
        }
        assertEquals("regex error: ${model.regex.value.error}", null, model.regex.value.error)
        assertEquals(3, model.regex.value.rows.size)
    }

    private fun highlightsIn(bytes: ByteArray): Int =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow().use { document ->
            document.pages().flatMap { it.annotations() }
                .count { it.type == AnnotationType.HIGHLIGHT }
        }

    private fun textIn(bytes: ByteArray): String =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow().use { document ->
            document.pages().joinToString("\n") { page ->
                page.extractText().runs.joinToString("") { it.text }
            }
        }

    // --------------------------------------------------------------- tests

    @Test
    fun highlightAllWritesACopyAndLeavesTheOriginalByteIdentical() {
        val before = sha256(bytesOf(uri))
        val model = newModel()
        open(model)
        runRegex(model)

        model.highlightRegexMatches()
        waitUntil("the highlighted copy") { model.export.value != null }
        val pending = model.export.value!!
        assertEquals("3 highlights", 3, pending.highlightCount)

        val destination = newDownload("pdfpaper-t65-highlighted-${System.nanoTime()}.pdf")
        model.completeExport(destination)
        waitUntil("the highlighted copy to be written") {
            model.export.value == null && model.message.value == "Saved a copy with 3 highlights"
        }
        assertEquals("Saved a copy with 3 highlights", model.message.value)

        assertEquals("the opened file must not change", before, sha256(bytesOf(uri)))
        val copy = bytesOf(destination)
        assertEquals(3, highlightsIn(copy))
        assertTrue("the copy keeps the text", textIn(copy).contains("AB-1234"))
    }

    @Test
    fun redactionHandoffPrefillsThePanelAndItsVerifyRemovesTheText() {
        val before = sha256(bytesOf(uri))
        val model = newModel()
        open(model)
        runRegex(model)

        model.handOffRegexToRedactions()
        val redaction = model.redaction.value
        assertTrue("the redaction panel should open", redaction.active)
        assertEquals(RedactionFindMode.REGEX, redaction.find.mode)
        assertEquals(pattern, redaction.find.query)

        model.findRedactions()
        waitUntil("the redaction find") {
            !model.redaction.value.find.running && model.redaction.value.find.searched
        }
        assertEquals(
            "redaction find error: ${model.redaction.value.find.error}",
            null,
            model.redaction.value.find.error
        )
        assertTrue("the redaction find should see matches", model.redaction.value.find.matches.isNotEmpty())

        model.markRedactionFindMatches()
        assertTrue("the found matches should become marks", model.redaction.value.marks.isNotEmpty())

        model.applyRedactions()
        waitUntil("the verified redaction") {
            model.redaction.value.outcome != null || model.redaction.value.error != null
        }
        assertNotNull(
            "redaction failed: ${model.redaction.value.error}",
            model.redaction.value.outcome
        )

        assertEquals("the opened file must not change", before, sha256(bytesOf(uri)))

        model.saveRedactionCopy()
        waitUntil("the redacted bytes") { model.export.value != null }
        val destination = newDownload("pdfpaper-t65-redacted-${System.nanoTime()}.pdf")
        model.completeExport(destination)
        waitUntil("the redacted copy to be written") {
            model.export.value == null && model.message.value == "Saved a verified redacted copy"
        }

        val text = textIn(bytesOf(destination))
        assertTrue("the redacted copy must not keep AB-1234", !text.contains("AB-1234"))
        assertTrue("the redacted copy must not keep XY-9", !text.contains("XY-9"))
    }
}
