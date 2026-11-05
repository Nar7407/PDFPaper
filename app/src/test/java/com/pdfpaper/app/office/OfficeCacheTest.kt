package com.pdfpaper.app.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The cache is how the viewer learns that the PDF it is showing was converted
 * from an office file and what the converter could not keep.
 */
class OfficeCacheTest {

    private val cacheDir: File = Files.createTempDirectory("office-cache").toFile()

    @Test
    fun theStoredPdfIsNamedAfterTheSourceAndKeepsTheNotes() {
        val stored = OfficeCache.store(
            cacheDir,
            "Meeting notes.docx",
            OfficeKind.WORD,
            "%PDF-1.7\n".toByteArray(),
            listOf("font 'Calibri' is replaced by Helvetica")
        )

        assertEquals("Meeting notes.pdf", stored.pdf.name)
        assertEquals("%PDF-1.7\n", stored.pdf.readText())
        assertEquals("Meeting notes.pdf", stored.pdfName)
        assertEquals(stored, OfficeCache.read(stored.pdf.absolutePath))
    }

    @Test
    fun aPlainPdfHasNoConversionNotes() {
        val plain = File(cacheDir, "plain.pdf").apply { writeText("%PDF-1.7\n") }

        assertNull(OfficeCache.read(plain.absolutePath))
        assertNull(OfficeCache.read(""))
    }

    @Test
    fun twoDocumentsWithTheSameNameDoNotOverwriteEachOther() {
        val first = OfficeCache.store(
            cacheDir, "notes.docx", OfficeKind.WORD, byteArrayOf(1), listOf("first")
        )
        val second = OfficeCache.store(
            cacheDir, "notes.docx", OfficeKind.WORD, byteArrayOf(2), listOf("second")
        )

        assertNotEquals(first.pdf, second.pdf)
        assertEquals(listOf("first"), OfficeCache.read(first.pdf.absolutePath)!!.warnings)
        assertEquals(listOf("second"), OfficeCache.read(second.pdf.absolutePath)!!.warnings)
    }

    @Test
    fun aSlideDeckComesBackAsAPowerPointSource() {
        val stored = OfficeCache.store(
            cacheDir, "deck.pptx", OfficeKind.POWERPOINT, byteArrayOf(1), listOf("a chart is not converted")
        )

        val read = OfficeCache.read(stored.pdf.absolutePath)!!

        assertEquals(OfficeKind.POWERPOINT, read.kind)
        assertEquals("deck.pptx", read.sourceName)
        assertEquals(listOf("a chart is not converted"), read.warnings)
    }

    @Test
    fun warningsStayOnePerLineEvenWhenTheyContainNewlines() {
        val stored = OfficeCache.store(
            cacheDir, "odd.docx", OfficeKind.WORD, byteArrayOf(1), listOf("first\nsecond")
        )

        assertEquals(listOf("first second"), OfficeCache.read(stored.pdf.absolutePath)!!.warnings)
    }

    @Test
    fun thePdfNameDropsTheExtensionAndSanitisesThePath() {
        assertEquals("Notes.pdf", OfficeCache.pdfNameFor("Notes.docx"))
        assertEquals("report.pdf", OfficeCache.pdfNameFor("report"))
        assertEquals("document.pdf", OfficeCache.pdfNameFor("   "))
        assertEquals("_etc_passwd.pdf", OfficeCache.pdfNameFor("../etc/passwd"))
    }
}
