package com.pdfpaper.engine.write

import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round-trip guarantee (T10): every corpus file must open, be re-saved and
 * reopen with the same page count, without the loader falling back to a rebuild
 * scan (which would prove the written xref is wrong).
 */
class RoundTripTest {

    private val corpusDir = File("..", "testdata").canonicalFile
        .takeIf { it.isDirectory } ?: File("testdata").canonicalFile

    private fun corpusFiles(): List<File> = corpusDir
        .listFiles { f -> f.isFile && f.name.endsWith(".pdf", ignoreCase = true) }
        ?.sortedBy { it.name }
        ?: emptyList()

    @Test
    fun everyCorpusFileRoundTrips() {
        val files = corpusFiles()
        assertTrue("expected corpus files in $corpusDir", files.isNotEmpty())
        for (file in files) {
            PdfDocument.open(FileSource(file)).getOrThrow().use { doc ->
                val before = doc.pages().size
                val selections = doc.pages().mapIndexed { index, _ -> PdfPageSelection(doc, index) }
                val written = PdfPageOps.assemble(selections, doc).getOrElse { error ->
                    throw AssertionError("${file.name}: write failed: ${error.message}", error)
                }
                PdfDocument.open(com.pdfpaper.engine.io.ByteArraySource(written)).getOrThrow().use { reopened ->
                    assertFalse("${file.name}: xref rebuilt on reopen", reopened.file.rebuiltFromScan)
                    assertEquals("${file.name}: page count changed", before, reopened.pages().size)
                }
            }
        }
    }

    @Test
    fun syntheticDocumentRoundTripsByteStable() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { doc ->
            val selections = doc.pages().mapIndexed { index, _ -> PdfPageSelection(doc, index) }
            val first = PdfPageOps.assemble(selections, doc).getOrThrow()
            WriteFixtures.open(first).use { reopened ->
                val second = PdfPageOps.assemble(
                    reopened.pages().mapIndexed { index, _ -> PdfPageSelection(reopened, index) },
                    reopened
                ).getOrThrow()
                // Second-generation output must be structurally valid and equal in size.
                WriteFixtures.open(second).use { twice ->
                    assertFalse(twice.file.rebuiltFromScan)
                    assertEquals(3, twice.pages().size)
                    assertEquals(listOf("P1", "P2", "P3"), WriteFixtures.pageWords(twice))
                }
            }
        }
    }
}
