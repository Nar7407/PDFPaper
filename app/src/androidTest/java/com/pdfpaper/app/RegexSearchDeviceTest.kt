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
import com.pdfpaper.app.ui.viewer.ViewerViewModel
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T65 document scope on a device: the run visits every page, each row carries
 * boxes from its own page, and next/previous walk the flat list with wraparound.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class RegexSearchDeviceTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<Uri>()
    private val stores = mutableListOf<ViewModelStore>()

    private lateinit var name: String
    private lateinit var uri: Uri

    @Before
    fun setUp() {
        val bytes = PdfBuilder(PageSize.A4).apply {
            addPage().apply { drawText("AB-1234 first", 64.0, 90.0, 12.0) }
            addPage().apply { drawText("nothing on this page", 64.0, 90.0, 12.0) }
            addPage().apply { drawText("AB-1234 second", 64.0, 90.0, 12.0) }
        }.build()
        name = "pdfpaper-t65-search-${System.nanoTime()}.pdf"
        uri = insertDownload(name, bytes)
    }

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        opened.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

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

    @Test
    fun documentRunFindsTheRightPagesAndBoxes() {
        val model = newModel()
        open(model)
        model.runDocumentRegex("AB-\\d{4}", DEFAULT_REGEX_FLAGS)
        waitUntil("the document regex run") {
            !model.regex.value.running && model.regex.value.rows.isNotEmpty()
        }
        val state = model.regex.value
        assertEquals(null, state.error)
        assertEquals(listOf(0, 2), state.rows.map { it.pageIndex })
        assertTrue("every row needs boxes", state.rows.all { it.rects.isNotEmpty() })
        assertEquals("2 matches on 2 pages", state.summary)
    }

    @Test
    fun nextAndPreviousWalkTheFlatList() {
        val bytes = PdfBuilder(PageSize.A4).apply {
            repeat(3) { page -> addPage().apply { drawText("XY-1 here", 64.0, 90.0, 12.0) } }
        }.build()
        val extra = insertDownload("pdfpaper-t65-walk-${System.nanoTime()}.pdf", bytes)
        val model = newModel()
        model.load(extra.toString(), "walk.pdf")
        waitUntil("the walk document") {
            !model.state.value.isLoading && model.state.value.pageCount == 3
        }
        model.runDocumentRegex("XY-\\d", DEFAULT_REGEX_FLAGS)
        waitUntil("three matches") {
            !model.regex.value.running && model.regex.value.rows.size == 3
        }
        assertEquals(0, model.regex.value.activeIndex)
        model.regexNext()
        assertEquals(1, model.regex.value.activeIndex)
        model.regexPrevious()
        assertEquals(0, model.regex.value.activeIndex)
        model.setRegexActive(2)
        model.regexNext()
        assertEquals("next after the last wraps", 0, model.regex.value.activeIndex)
    }

    @Test
    fun testerReportsTheVisiblePage() {
        val model = newModel()
        open(model)
        model.regexTester("AB-\\d{4}", DEFAULT_REGEX_FLAGS)
        waitUntil("the tester line") { model.regex.value.tester != null }
        val tester = model.regex.value.tester
        assertNotNull(tester)
        assertEquals(null, tester!!.error)
        assertTrue(
            "unexpected tester line: ${tester.text}",
            tester.text.startsWith("1 match on page 1")
        )
    }
}
