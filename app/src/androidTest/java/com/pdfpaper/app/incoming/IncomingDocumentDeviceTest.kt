package com.pdfpaper.app.incoming

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.data.DocumentAccess
import com.pdfpaper.app.data.ImagePdf
import com.pdfpaper.app.data.IncomingAction
import com.pdfpaper.app.data.IncomingIntents
import com.pdfpaper.app.data.NonSeekableException
import com.pdfpaper.app.data.openParcelFileDescriptorSource
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * T47 on a device: incoming intents are parsed without trusting the provider,
 * a seekable content PDF opens through its descriptor (no cache copy), a pipe
 * is detected as non-seekable, and shared images become a PDF through T21.
 */
@RunWith(AndroidJUnit4::class)
class IncomingDocumentDeviceTest {

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application

    private fun pdfBytes(pages: Int = 1): ByteArray =
        PdfBuilder(PageSize.A4).apply {
            repeat(pages) { addPage() }
        }.build()

    private fun cacheFile(name: String, bytes: ByteArray): File =
        File(context.cacheDir, "incoming-tests").apply { mkdirs() }
            .let { dir -> File(dir, "${System.nanoTime()}-$name").apply { writeBytes(bytes) } }

    private fun contentUri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    @Test
    fun viewSendAndMultipleAreParsedButMainIsNot() {
        val first = Uri.parse("content://example/a.pdf")
        val second = Uri.parse("content://example/b.pdf")

        val view = IncomingIntents.parse(Intent(Intent.ACTION_VIEW, first))
        assertEquals(IncomingAction.VIEW, view?.action)
        assertEquals(listOf(first), view?.uris)

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, first)
        }
        val sendRequest = IncomingIntents.parse(send)
        assertEquals(IncomingAction.SEND, sendRequest?.action)
        assertEquals(listOf(first), sendRequest?.uris)

        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
        }
        val multipleRequest = IncomingIntents.parse(multiple)
        assertEquals(IncomingAction.SEND_MULTIPLE, multipleRequest?.action)
        assertEquals(listOf(first, second), multipleRequest?.uris)

        assertNull(IncomingIntents.parse(Intent(Intent.ACTION_MAIN)))
        assertNull(IncomingIntents.parse(null))
    }

    @Test
    fun aShareWithNoLocalFileIsReportedNotOpened() {
        val request = IncomingIntents.parse(
            Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        )
        assertNotNull(request)
        assertTrue(request!!.uris.isEmpty())
        assertNotNull(request.error)
        assertTrue(request.error!!.contains("No file"))
    }

    @Test
    fun aSeekableContentPdfOpensThroughItsDescriptor() {
        val bytes = pdfBytes(2)
        val file = cacheFile("direct.pdf", bytes)
        val uri = contentUri(file)

        val opened = DocumentAccess.open(context, uri.toString()).getOrThrow()
        opened.document.use { document ->
            assertEquals(2, document.pages().size)
        }
        assertTrue(opened.location.isContent)
        assertEquals(uri.toString(), opened.location.value)

        val read = DocumentAccess.readBytes(context, uri.toString()).getOrThrow()
        assertEquals(bytes.size, read.size)
        assertEquals("%PDF-", String(read.copyOf(5), Charsets.US_ASCII))
    }

    @Test
    fun aPipeDescriptorIsRejectedBeforeTheEngineReadsIt() {
        val pipe = ParcelFileDescriptor.createPipe()
        var rejected = false
        try {
            openParcelFileDescriptorSource(pipe[0])
        } catch (_: NonSeekableException) {
            rejected = true
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
        assertTrue("a pipe must not be handed to the engine", rejected)
    }

    @Test
    fun corruptAndUnknownFilesFailWithAMessage() {
        val corrupt = cacheFile("broken.pdf", "%PDF-1.7\nnot really a PDF".toByteArray())
        val corruptResult = DocumentAccess.open(context, corrupt.absolutePath)
        assertTrue(corruptResult.isFailure)
        assertNotNull(corruptResult.exceptionOrNull()?.message)

        val text = cacheFile("notes.txt", "hello".toByteArray())
        val textResult = DocumentAccess.open(context, text.absolutePath)
        assertTrue(textResult.isFailure)
        assertTrue(
            textResult.exceptionOrNull()?.message.orEmpty().contains("%PDF-")
        )
    }

    @Test
    fun sharedImagesBecomeOnePagePerImage() {
        fun bitmap(format: Bitmap.CompressFormat): ByteArray {
            val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(30, 120, 200))
            val output = ByteArrayOutputStream()
            assertTrue(bitmap.compress(format, 90, output))
            bitmap.recycle()
            return output.toByteArray()
        }

        val bytes = ImagePdf.createFromEncoded(
            listOf(bitmap(Bitmap.CompressFormat.PNG), bitmap(Bitmap.CompressFormat.JPEG))
        ).getOrThrow()

        PdfDocument.open(ByteArraySource(bytes)).getOrThrow().use { document ->
            assertEquals(2, document.pages().size)
        }
        assertEquals("%PDF-", String(bytes.copyOf(5), Charsets.US_ASCII))
    }

    @Test
    fun undecodableImageDataFailsWithoutThrowing() {
        val result = ImagePdf.createFromEncoded(listOf("not an image".toByteArray()))
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()?.message)
    }
}
