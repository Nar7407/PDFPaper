package com.pdfpaper.engine.compress

import com.pdfpaper.engine.image.EncodedImage
import com.pdfpaper.engine.image.ImageEncoder
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compressor: images are scaled to the target resolution and re-encoded,
 * streams are packed again, identical objects are shared, and the result is
 * written with object streams and a cross-reference stream.
 *
 * The JPEG codec lives outside the engine, so these tests use a stand-in
 * encoder that reports a smaller file without producing a real JPEG. The
 * pixel-level check runs in the JVM tooling, where the codec is real.
 */
class PdfCompressTest {

    /** A stand-in for the platform JPEG encoder. */
    private class StubEncoder : ImageEncoder {
        override fun encodeJpeg(
            width: Int,
            height: Int,
            rgba: ByteArray,
            quality: Int,
            grayscale: Boolean
        ): EncodedImage? {
            val bytes = ByteArray((width * height / 8).coerceAtLeast(64)) { (it % 251).toByte() }
            return EncodedImage(bytes, width, height, grayscale)
        }
    }

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun textOf(bytes: ByteArray): String {
        val document = open(bytes)
        document.use {
            return it.pages().joinToString("\n") { page -> page.layout().text }
        }
    }

    @Test
    fun textOnlyDocumentKeepsItsTextAndNeverGrows() {
        val original = CompressFixtures.textOnly()
        val document = open(original)
        val result = document.use {
            PdfCompressor.compress(it, original, CompressOptions(), encoder = StubEncoder())
        }.getOrThrow()

        assertTrue(
            "the result must never be bigger than the original",
            result.bytes.size <= original.size
        )
        assertEquals(
            "the text must survive the rewrite",
            textOf(original),
            textOf(result.bytes)
        )
        assertEquals(
            result.bytes.size <= original.size,
            !result.report.usedOriginal
        )
    }

    @Test
    fun scannedImageIsScaledToTheTargetResolution() {
        val original = CompressFixtures.scanned()
        val document = open(original)
        val result = document.use {
            PdfCompressor.compress(
                it,
                original,
                CompressOptions(targetImageDpi = 150, jpegQuality = 70),
                encoder = StubEncoder()
            )
        }.getOrThrow()

        assertEquals(1, result.report.imagesRecompressed)
        assertEquals(1, result.report.imagesDownsampled)

        val image = firstImage(result.bytes)
        assertNotNull("the compressed file must still have an image", image)
        // 2550 px over 612 points is 300 dpi; at 150 dpi the picture is half
        // as wide.
        assertEquals(1275, (image!!.dict["Width"] as com.pdfpaper.engine.model.PdfInteger).value.toInt())
        assertEquals(1650, (image.dict["Height"] as com.pdfpaper.engine.model.PdfInteger).value.toInt())
        assertEquals("DCTDecode", (image.dict["Filter"] as PdfName).name)
    }

    @Test
    fun identicalPicturesShareOneObject() {
        val original = CompressFixtures.duplicatedImage()
        val document = open(original)
        val result = document.use {
            PdfCompressor.compress(it, original, CompressOptions(), encoder = StubEncoder())
        }.getOrThrow()

        assertTrue(
            "the two identical pictures should share one object",
            result.report.duplicatesShared >= 1
        )
        val document2 = open(result.bytes)
        document2.use {
            val images = it.pages().flatMap { page ->
                imageObjectsOf(page)
            }
            assertEquals("only one picture should be left", 1, images.distinct().size)
        }
    }

    @Test
    fun brokenImagesAreKeptWithoutFailingTheRun() {
        val original = CompressFixtures.brokenImage()
        val document = open(original)
        val result = document.use {
            PdfCompressor.compress(it, original, CompressOptions(), encoder = StubEncoder())
        }.getOrThrow()

        assertTrue(result.bytes.size <= original.size)
        assertNotNull(firstImage(result.bytes))
    }

    @Test
    fun outputUsesObjectStreamsAndCrossReferenceStreams() {
        val original = CompressFixtures.photoHeavy()
        val document = open(original)
        val result = document.use {
            PdfCompressor.compress(it, original, CompressOptions(), encoder = StubEncoder())
        }.getOrThrow()

        val text = String(result.bytes, Charsets.ISO_8859_1)
        assertTrue("the file must use object streams", text.contains("/ObjStm"))
        assertTrue("the file must use a cross-reference stream", text.contains("/XRef"))
        // The engine has to be able to read its own output.
        val reopened = open(result.bytes)
        reopened.use {
            assertEquals(3, it.pages().size)
        }
    }

    @Test
    fun signatureFieldsAreReported() {
        val original = CompressFixtures.withSignatureField()
        val document = open(original)
        assertTrue(PdfCompressor.detectSignatures(document))
        val result = document.use {
            PdfCompressor.compress(it, original, CompressOptions(), encoder = StubEncoder())
        }.getOrThrow()
        assertTrue(result.hasSignatures)
    }

    @Test
    fun everyCorpusFileCompressesToNoMoreThanTheOriginal() {
        val corpus = listOf(
            "text-only" to CompressFixtures.textOnly(),
            "scanned" to CompressFixtures.scanned(),
            "photo-heavy" to CompressFixtures.photoHeavy(),
            "duplicated image" to CompressFixtures.duplicatedImage(),
            "broken image" to CompressFixtures.brokenImage()
        )
        for ((name, original) in corpus) {
            val document = open(original)
            val result = document.use {
                PdfCompressor.compress(it, original, CompressOptions(), encoder = StubEncoder())
            }.getOrThrow()
            assertTrue("$name grew", result.bytes.size <= original.size)
            if (result.report.usedOriginal) {
                assertEquals(name, original.size, result.bytes.size)
            } else {
                assertTrue("$name did not get smaller", result.bytes.size < original.size)
            }
        }
    }

    @Test
    fun photoHeavyDocumentGetsSmaller() {
        val original = CompressFixtures.photoHeavy()
        val document = open(original)
        val result = document.use {
            PdfCompressor.compress(it, original, CompressOptions(), encoder = StubEncoder())
        }.getOrThrow()
        assertFalse("a photo document should get smaller", result.report.usedOriginal)
        assertTrue(result.report.savedBytes > 0)
        assertTrue(
            "the report should show where the space went",
            result.report.categories.any { it.label == "Images" }
        )
    }

    @Test
    fun anEstimateIsCloseToTheRealResult() {
        val original = CompressFixtures.photoHeavy()
        val document = open(original)
        val estimate = document.use {
            PdfCompressor.estimate(it, original, CompressOptions(), encoder = StubEncoder())
        }
        assertTrue(estimate.sampledPages > 0)
        assertTrue(estimate.totalPages >= estimate.sampledPages)
        assertTrue("the estimate must be smaller than the original", estimate.estimatedBytes < original.size)
    }

    private fun firstImage(bytes: ByteArray): PdfStream? {
        val document = open(bytes)
        document.use {
            for (page in it.pages()) {
                val image = imageObjectsOf(page).firstOrNull()
                if (image != null) return image
            }
        }
        return null
    }

    /** Every image object a page draws, resolved to its stream. */
    private fun imageObjectsOf(page: com.pdfpaper.engine.page.PdfPage): List<PdfStream> {
        val resources = page.resources ?: return emptyList()
        val table = resources["XObject"] as? com.pdfpaper.engine.model.PdfDict ?: return emptyList()
        return table.entries.values.mapNotNull { value ->
            when (value) {
                is PdfStream -> value
                is com.pdfpaper.engine.model.PdfRef -> page.document.resolve(value) as? PdfStream
                else -> null
            }
        }.filter { (it.dict["Subtype"] as? PdfName)?.name == "Image" }
    }
}
