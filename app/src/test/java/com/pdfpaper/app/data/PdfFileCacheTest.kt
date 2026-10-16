package com.pdfpaper.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class PdfFileCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val pdfBytes = "%PDF-1.7\n1 0 obj\n<< >>\nendobj\n".toByteArray(Charsets.US_ASCII)

    @Test
    fun copiesStreamBytesAndReportsSize() {
        val cacheDir = tempFolder.newFolder("cache")

        val cached = copyPdfToCache(
            ByteArrayInputStream(pdfBytes),
            "sample.pdf",
            cacheDir
        )

        assertEquals("sample.pdf", cached.displayName)
        assertEquals(pdfBytes.size.toLong(), cached.sizeBytes)
        assertTrue(cached.file.isFile)
        assertArrayEquals(pdfBytes, cached.file.readBytes())
    }

    @Test
    fun blankDisplayNameFallsBackToDocumentPdf() {
        val cacheDir = tempFolder.newFolder("cache")

        val cached = copyPdfToCache(ByteArrayInputStream(pdfBytes), "   ", cacheDir)

        assertEquals("document.pdf", cached.displayName)
        assertTrue(cached.file.isFile)
    }

    @Test
    fun pathSeparatorsAndTraversalAreStripped() {
        val cacheDir = tempFolder.newFolder("cache")

        val cached = copyPdfToCache(
            ByteArrayInputStream(pdfBytes),
            "../secret\\evil.pdf",
            cacheDir
        )

        assertFalse(cached.displayName.contains('/'))
        assertFalse(cached.displayName.contains('\\'))
        assertFalse(cached.displayName.contains(".."))
        // The cached file must stay inside the pdfs cache dir.
        assertEquals(
            tempFolder.root.toPath().resolve("cache").resolve("pdfs").toFile().canonicalFile,
            requireNotNull(cached.file.parentFile).canonicalFile
        )
    }

    @Test
    fun duplicateDisplayNamesProduceUniqueFiles() {
        val cacheDir = tempFolder.newFolder("cache")
        val otherBytes = "other".toByteArray(Charsets.US_ASCII)

        val first = copyPdfToCache(ByteArrayInputStream(pdfBytes), "a.pdf", cacheDir)
        val second = copyPdfToCache(ByteArrayInputStream(otherBytes), "a.pdf", cacheDir)

        assertNotEquals(first.file.absolutePath, second.file.absolutePath)
        assertArrayEquals(pdfBytes, first.file.readBytes())
        assertArrayEquals(otherBytes, second.file.readBytes())
    }
}
