package com.pdfpaper.renderjvm

import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.renderjvm.tools.run as runCli
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The `compress` CLI: it compresses a PDF with the engine's compressor and
 * the JVM host codec, and writes a file a viewer can open again.
 */
class CompressCliTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun compressesAPdfAndWritesAReadableFile() {
        val input = File(temporary.root, "in.pdf")
        input.writeBytes(documentWithPicture())
        val output = File(temporary.root, "nested/out.pdf")

        val code = runCli(
            arrayOf(
                "compress",
                input.absolutePath,
                "--preset", "strong",
                "--out", output.absolutePath
            )
        )

        assertEquals("the CLI should succeed", 0, code)
        assertTrue("the output file was not written", output.isFile)
        assertTrue(
            "the compressed file is larger than the original",
            output.length() <= input.length()
        )
        PdfDocument.open(FileSource(output)).getOrThrow().use { document ->
            assertEquals("the page count changed", 2, document.pages().size)
        }
    }

    @Test
    fun compressesTheCorpusFile() {
        val input = repoFile("testdata/text-and-graphics.pdf")
        val output = File(temporary.root, "corpus.pdf")

        val code = runCli(
            arrayOf(
                "compress",
                input.absolutePath,
                "--preset", "balanced",
                "--out", output.absolutePath
            )
        )

        assertEquals("the CLI should succeed", 0, code)
        assertTrue("the output file was not written", output.isFile)
        PdfDocument.open(FileSource(output)).getOrThrow().use { document ->
            assertEquals(1, document.pages().size)
        }
    }

    @Test
    fun refusesToCompressASignedDocumentWithoutForce() {
        val input = File(temporary.root, "signed.pdf")
        input.writeBytes(signedDocument())
        val output = File(temporary.root, "signed-out.pdf")

        val code = runCli(
            arrayOf("compress", input.absolutePath, "--out", output.absolutePath)
        )

        assertEquals("a signed document needs --force", 2, code)
        assertFalse("nothing may be written without --force", output.exists())
    }

    @Test
    fun compressesASignedDocumentWithForce() {
        val input = File(temporary.root, "signed.pdf")
        input.writeBytes(signedDocument())
        val output = File(temporary.root, "signed-out.pdf")

        val code = runCli(
            arrayOf("compress", input.absolutePath, "--out", output.absolutePath, "--force")
        )

        assertEquals("the CLI should succeed", 0, code)
        assertTrue("the output file was not written", output.isFile)
    }

    @Test
    fun reportsUsageErrors() {
        assertEquals("no arguments is a usage error", 2, runCli(arrayOf()))
        assertEquals(
            "a missing --out is a usage error",
            2,
            runCli(arrayOf("compress", "missing.pdf"))
        )
        assertEquals(
            "an unknown option is a usage error",
            2,
            runCli(arrayOf("compress", "missing.pdf", "--out", "out.pdf", "--nope"))
        )
        assertEquals(
            "an unknown preset is a usage error",
            2,
            runCli(arrayOf("compress", "missing.pdf", "--out", "out.pdf", "--preset", "tiny"))
        )
    }

    @Test
    fun reportsAMissingFile() {
        val code = runCli(
            arrayOf("compress", File(temporary.root, "nothing.pdf").absolutePath, "--out", "out.pdf")
        )
        assertEquals("a missing input file is a usage error", 2, code)
    }

    /** A two-page document: one page of text, one page with a JPEG picture. */
    private fun documentWithPicture(): ByteArray {
        val picture = EditImages.decode(jpegBytes(320, 240)).getOrThrow()
        return PdfBuilder(PageSize.A4)
            .apply {
                addPage().drawText("Hello PDFPaper", 72.0, 72.0, 24.0)
                addPage().drawImage(picture, 72.0, 100.0, 240.0, 180.0)
            }
            .build()
    }

    /** A document with a signature field, so the CLI has to warn. */
    private fun signedDocument(): ByteArray {
        val builder = PdfDocumentBuilder("1.7")
        val pagesNumber = builder.allocate()
        val pagesRef = PdfRef(pagesNumber, 0)
        val pageNumber = builder.allocate()
        val pageRef = PdfRef(pageNumber, 0)
        val contentRef = builder.addStream(
            PdfDict(mapOf(PdfName("Length") to PdfInteger(0))),
            ByteArray(0)
        )
        val signatureValueRef = builder.add(
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Sig"),
                    PdfName("Filter") to PdfName("Adobe.PPKLite"),
                    PdfName("SubFilter") to PdfName("adbe.pkcs7.detached"),
                    PdfName("ByteRange") to PdfArray(listOf(PdfInteger(0), PdfInteger(0))),
                    PdfName("Contents") to PdfString(ByteArray(8))
                )
            )
        )
        builder.put(
            pagesNumber,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Pages"),
                    PdfName("Kids") to PdfArray(listOf(pageRef)),
                    PdfName("Count") to PdfInteger(1)
                )
            )
        )
        builder.put(
            pageNumber,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Page"),
                    PdfName("Parent") to pagesRef,
                    PdfName("MediaBox") to PdfArray(
                        listOf(PdfInteger(0), PdfInteger(0), PdfInteger(200), PdfInteger(200))
                    ),
                    PdfName("Contents") to contentRef
                )
            )
        )
        val field = builder.add(
            PdfDict(
                mapOf(
                    PdfName("FT") to PdfName("Sig"),
                    PdfName("T") to PdfString("signature1"),
                    PdfName("V") to signatureValueRef,
                    PdfName("Rect") to PdfArray(
                        listOf(PdfInteger(10), PdfInteger(10), PdfInteger(100), PdfInteger(30))
                    )
                )
            )
        )
        builder.root = builder.add(
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Catalog"),
                    PdfName("Pages") to pagesRef,
                    PdfName("AcroForm") to PdfDict(mapOf(PdfName("Fields") to PdfArray(listOf(field))))
                )
            )
        )
        return builder.build()
    }

    /** A small JPEG of a colour gradient. */
    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val red = x * 255 / width
                val green = y * 255 / height
                image.setRGB(x, y, 0xFF shl 24 or (red shl 16) or (green shl 8) or 128)
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    /** Finds a file of the repository, whatever directory the test runs in. */
    private fun repoFile(relative: String): File {
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, relative)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        throw AssertionError("cannot find $relative")
    }
}
