package com.pdfpaper.app.incoming

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.data.DocumentAccess
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.page.pages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T47/T55: a source the provider lets us write to, and a writable local file,
 * may be saved in place; every other source is read-only and can only be saved
 * as a copy. The write itself must replace the original bytes and never throw.
 */
@RunWith(AndroidJUnit4::class)
class IncomingWritableSourceDeviceTest {

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application

    private fun pdfBytes(pages: Int): ByteArray =
        PdfBuilder(PageSize.A4).apply { repeat(pages) { addPage() } }.build()

    private fun cacheFile(name: String, bytes: ByteArray): File =
        File(context.cacheDir, "incoming-writable").apply { mkdirs() }
            .let { dir -> File(dir, "${System.nanoTime()}-$name").apply { writeBytes(bytes) } }

    private fun contentUri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    @Test
    fun aWritableContentSourceOffersSaveInPlace() {
        val file = cacheFile("writable.pdf", pdfBytes(1))
        val uri = contentUri(file)

        val opened = DocumentAccess.open(context, uri.toString()).getOrThrow()
        opened.document.use { }
        assertTrue("the provider allows writing here", opened.location.canWriteInPlace)

        val replacement = pdfBytes(3)
        assertTrue(
            DocumentAccess.writeInPlace(context, uri.toString(), replacement).isSuccess
        )
        assertEquals(replacement.size.toLong(), file.length())
        assertEquals(
            replacement.toList(),
            file.readBytes().toList()
        )

        // The saved file is still a readable PDF through the same URI.
        val reopened = DocumentAccess.open(context, uri.toString()).getOrThrow()
        reopened.document.use { document ->
            assertEquals(3, document.pages().size)
        }
    }

    @Test
    fun aLocalFileCanBeSavedInPlaceThroughTheFileTarget() {
        val file = cacheFile("local.pdf", pdfBytes(1))

        val opened = DocumentAccess.open(context, file.absolutePath).getOrThrow()
        opened.document.use { }
        // T55: Downloads and Documents are ordinary writable files, so a local
        // file is a save-in-place source too (written through FileSaveTarget).
        assertTrue("a writable local file can be saved in place", opened.location.canWriteInPlace)

        // The content-URI primitive still refuses a plain path: the viewer uses
        // FileSaveTarget for local files, not a content resolver write.
        val result = DocumentAccess.writeInPlace(context, file.absolutePath, pdfBytes(2))
        assertTrue("writing a plain path through the content writer must fail", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("content://"))
    }
}
