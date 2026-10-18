package com.pdfpaper.engine.edit

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T17 acceptance: saving through the editor entry point must not change the
 * existing corpus. With no edits the new path is the same full save as before,
 * so every file keeps its page count and a valid cross-reference table.
 */
class PdfEditOpsTest {

    private val corpusDir = File("..", "testdata").canonicalFile
        .takeIf { it.isDirectory } ?: File("testdata").canonicalFile

    @Test
    fun savingEveryCorpusFileWithoutEditsPreservesIt() {
        val files = corpusDir
            .listFiles { f -> f.isFile && f.name.endsWith(".pdf", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue(files.isNotEmpty(), "expected corpus files in $corpusDir")

        for (file in files) {
            PdfDocument.open(FileSource(file)).getOrThrow().use { document ->
                val before = document.pages().size
                val written = PdfEditOps.save(document, emptyList()).getOrElse { error ->
                    throw AssertionError("${file.name}: save failed: ${error.message}", error)
                }
                PdfDocument.open(ByteArraySource(written)).getOrThrow().use { reopened ->
                    assertFalse(reopened.file.rebuiltFromScan, "${file.name}: xref rebuilt on reopen")
                    assertEquals(before, reopened.pages().size, "${file.name}: page count changed")
                }
            }
        }
    }
}
