package com.pdfpaper.app.data.save

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.filters.SdkSuppress
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.AutosaveMode
import com.pdfpaper.app.data.DocumentAccess
import com.pdfpaper.app.ui.viewer.ViewerSaveStatus
import com.pdfpaper.app.ui.viewer.ViewerViewModel
import com.pdfpaper.engine.annot.HighlightSpec
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.write.PdfPageOps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * T55 on a real provider: a document in the Downloads collection is annotated
 * and saved back into the *same* file, an external change is caught before
 * anything is written, the last three pre-save versions can be restored, and a
 * write that dies half way ends in a verified rewrite instead of a partial file.
 *
 * The system document picker (DocumentsUI) cannot be driven from an
 * instrumentation test, so the provider here is the Downloads collection: it
 * exercises the same `content://` read, fingerprint, append and truncate paths
 * that a SAF grant uses.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class SaveInPlaceDeviceTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private lateinit var name: String
    private lateinit var uri: Uri
    private lateinit var original: ByteArray
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        AppSettings.setAutosaveMode(context, AutosaveMode.IN_PLACE)
        original = PdfBuilder(PageSize.A4).apply { addPage() }.build()
        name = "pdfpaper-t55-${System.nanoTime()}.pdf"
        uri = insertDownload(name, original)
    }

    @After
    fun tearDown() {
        runCatching { context.contentResolver.delete(uri, null, null) }
        stores.forEach { it.clear() }
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("t55-temp") }
            ?.forEach { it.deleteRecursively() }
        // The app ships with "save in place"; leave the setting as it was found.
        AppSettings.setAutosaveMode(context, AutosaveMode.IN_PLACE)
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
        return created
    }

    /** Every document this test class created in Downloads. */
    private fun matchingDownloads(): List<Pair<Long, String>> =
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("pdfpaper-t55-%"),
            null
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1))
            }
        } ?: emptyList()

    private fun bytesOf(location: Uri): ByteArray =
        context.contentResolver.openInputStream(location)!!.use { it.readBytes() }

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

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
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
            !model.state.value.isLoading && model.state.value.pageCount == 1
        }
        assertEquals(null, model.state.value.error)
    }

    private fun addNote(model: ViewerViewModel, text: String) {
        model.setEditMode(true)
        val before = model.edit.value.annotations.size
        model.addNote(0, 0.4f, 0.7f, text, "T55 test")
        assertEquals("the note should be pending", before + 1, model.edit.value.annotations.size)
    }

    private fun saveInPlace(model: ViewerViewModel, expected: String = "Saved in place") {
        model.save()
        waitUntil("the in-place save to finish") {
            model.save.value.status == ViewerSaveStatus.SAVED ||
                model.save.value.status == ViewerSaveStatus.ERROR
        }
        assertEquals(
            "save failed: ${model.save.value.message}",
            ViewerSaveStatus.SAVED,
            model.save.value.status
        )
        assertEquals(expected, model.message.value)
        waitForReopen(model)
    }

    /**
     * A successful save closes and reopens the document. A save started while
     * that reopen is in flight is ignored (there is no document yet), so wait
     * for it to finish instead of racing it.
     */
    private fun waitForReopen(model: ViewerViewModel) {
        var sawLoading = false
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (model.state.value.isLoading) sawLoading = true
            if (sawLoading && !model.state.value.isLoading) return
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(10)
        }
        if (!sawLoading) return
        throw AssertionError("Timed out waiting for the document to reopen")
    }

    private fun notesInFile(location: String): List<String> {
        val opened = DocumentAccess.open(context, location).getOrThrow()
        return opened.document.use { document ->
            document.pages().flatMap { it.annotations() }.map { it.contents ?: "" }
        }
    }

    // --------------------------------------------------------------- tests

    @Test
    fun aNoteIsWrittenIntoTheDownloadsFileWithoutANewFile() {
        val before = matchingDownloads()
        assertTrue("the test file should be in Downloads", before.any { it.second == name })
        val model = newModel()

        open(model)
        addNote(model, "Written straight into the Downloads file")
        saveInPlace(model)

        // The same document, not a copy: same URI, same row, no new file.
        assertEquals(before.size, matchingDownloads().size)
        assertEquals(before.map { it.first }, matchingDownloads().map { it.first })
        assertEquals(
            listOf("Written straight into the Downloads file"),
            notesInFile(uri.toString())
        )

        // The revision was appended: the original bytes are still the prefix.
        val after = bytesOf(uri)
        assertTrue("the file should have grown", after.size > original.size)
        assertTrue("the original bytes must stay in front", after.copyOf(original.size).contentEquals(original))

        // A fresh viewer sees the note as a real annotation in the file, not as
        // a pending edit it is still holding.
        val reopened = newModel()
        open(reopened)
        assertTrue(
            "the reopened viewer must not hold the note as a pending edit",
            reopened.edit.value.annotations.isEmpty()
        )
        assertEquals(
            listOf("Written straight into the Downloads file"),
            notesInFile(reopened.state.value.sourceLocation)
        )
    }

    @Test
    fun anExternalChangeIsDetectedAndCanBeOverwritten() {
        val model = newModel()
        open(model)

        // Another app replaces the file after PDFPaper opened it, before the
        // user's next edit; the pending note is then made on the stale copy.
        val other = PdfBuilder(PageSize.A4).apply { repeat(2) { addPage() } }.build()
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(other) }
        addNote(model, "My edit")

        assertTrue("the source must still be writable", model.state.value.canSaveInPlace)
        assertEquals(AutosaveMode.IN_PLACE, AppSettings.autosaveMode(context))
        model.save()
        waitUntil("the save to reach a result") {
            model.save.value.status != ViewerSaveStatus.SAVING &&
                model.save.value.status != ViewerSaveStatus.IDLE
        }
        assertEquals(
            "the save should be blocked by the conflict, was ${model.save.value}, " +
                "export=${model.export.value != null}",
            ViewerSaveStatus.ERROR,
            model.save.value.status
        )
        assertTrue("the save should be reported as a conflict", model.save.value.conflict)
        assertTrue(
            "the message should say the file changed: ${model.save.value.conflictMessage}",
            model.save.value.conflictMessage.orEmpty().contains("changed", ignoreCase = true)
        )
        // Nothing was written: the newer revision is untouched.
        assertTrue(other.contentEquals(bytesOf(uri)))

        // "Overwrite anyway" replaces the newer file with the user's edit.
        model.overwriteAfterConflict()
        waitUntil("the overwrite to finish") {
            model.save.value.status == ViewerSaveStatus.SAVED
        }
        assertEquals(listOf("My edit"), notesInFile(uri.toString()))
        PdfDocument.open(ByteArraySource(bytesOf(uri))).getOrThrow().use { document ->
            assertEquals(1, document.pages().size)
        }
    }

    @Test
    fun theLastThreeVersionsAreKeptAndAPreviousOneCanBeRestored() {
        val model = newModel()
        open(model)

        repeat(4) { index ->
            addNote(model, "Note ${index + 1}")
            saveInPlace(model)
        }
        assertEquals(
            listOf("Note 1", "Note 2", "Note 3", "Note 4"),
            notesInFile(uri.toString())
        )

        model.loadVersions()
        waitUntil("the version list") {
            model.save.value.versions.size == VersionStore.MAX_VERSIONS
        }
        val versions = model.save.value.versions
        // Only the three most recent pre-save revisions are kept.
        assertTrue("only three versions are kept", versions.size == 3)

        // The versions are newest first, so the last one is the file as it was
        // before the second save: the one-note revision.
        model.restoreVersion(versions.last().id)
        waitUntil("the restore to finish") {
            model.save.value.status == ViewerSaveStatus.SAVED ||
                model.save.value.status == ViewerSaveStatus.ERROR
        }
        assertEquals(
            "restore failed: ${model.save.value.message}",
            ViewerSaveStatus.SAVED,
            model.save.value.status
        )
        assertEquals("Previous version restored", model.message.value)
        assertEquals(listOf("Note 1"), notesInFile(uri.toString()))
        assertEquals(1, matchingDownloads().size)
    }

    @Test
    fun aWriteThatDiesHalfWayEndsInAVerifiedRewrite() {
        val edited = PdfDocument.open(ByteArraySource(original)).getOrThrow().use { document ->
            PdfPageOps.editIncremental(
                document,
                original,
                listOf(
                    HighlightSpec(
                        pageIndex = 0,
                        rects = listOf(PdfRect(40.0, 700.0, 300.0, 740.0)),
                        contents = "half way"
                    )
                )
            ).getOrThrow()
        }
        val target = HalfWriteTarget(ContentSaveTarget(context, uri.toString()), original)
        val coordinator = SaveCoordinator(
            File(context.cacheDir, "t55-temp-${System.nanoTime()}").apply { mkdirs() }
        )

        val report = coordinator.save(
            target = target,
            originalBytes = original,
            editedBytes = edited,
            expectation = SaveExpectation(pageCount = 1, minimumAnnotations = 1),
            mode = SaveWriteMode.INCREMENTAL
        ).getOrThrow()

        assertTrue("a half-written append must fall back", report.fallbackToRewrite)
        assertTrue("the original must be put back first", target.restoredOriginal)
        assertTrue("the file should hold exactly the verified edit", edited.contentEquals(bytesOf(uri)))
    }

    /**
     * Writes half of the appended revision and then fails, the way a stream
     * killed mid-write does. The restore and the rewrite still work, so the
     * coordinator must recover without leaving the partial tail behind.
     */
    private class HalfWriteTarget(
        private val delegate: SaveTarget,
        private val original: ByteArray
    ) : SaveTarget {

        var restoredOriginal = false
            private set

        override fun read(): Result<ByteArray> = delegate.read()

        override fun fingerprint(): Result<DocumentFingerprint> = delegate.fingerprint()

        override fun append(bytes: ByteArray): Result<Unit> = runCatching {
            delegate.append(bytes.copyOf(bytes.size / 2)).getOrThrow()
            throw IOException("the stream died after half the revision")
        }

        override fun replace(bytes: ByteArray): Result<Unit> {
            if (bytes.contentEquals(original)) restoredOriginal = true
            return delegate.replace(bytes)
        }
    }
}
