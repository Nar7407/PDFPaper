package com.pdfpaper.app.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The detection rules are the app's first line of defence: providers hand out
 * renamed files, and ".docx" is no promise at all. Every case here is decided
 * from the bytes.
 */
class OfficeDetectTest {

    private fun sample(name: String): File =
        File(checkNotNull(javaClass.getResource("/office/$name")).toURI())

    private fun tempFile(suffix: String, bytes: ByteArray): File {
        val file = File.createTempFile("detect", suffix)
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    private fun zipWith(vararg entries: String): File {
        val file = File.createTempFile("detect", ".zip")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            for (entry in entries) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write("<x/>".toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun aWordPackageIsDetectedFromItsParts() {
        assertEquals(
            DocumentKind.Office(OfficeKind.WORD),
            OfficeDetect.detect(sample("notes.docx"))
        )
    }

    @Test
    fun aPowerPointPackageIsDetectedFromItsParts() {
        assertEquals(
            DocumentKind.Office(OfficeKind.POWERPOINT),
            OfficeDetect.detect(sample("deck.pptx"))
        )
    }

    @Test
    fun aPdfIsRecognisedSoTheViewerCanOpenItDirectly() {
        val pdf = tempFile(".pdf", "%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n".toByteArray())

        assertEquals(DocumentKind.Pdf, OfficeDetect.detect(pdf))
    }

    @Test
    fun aLegacyDocIsRefusedRatherThanCalledCorrupt() {
        assertEquals(DocumentKind.LegacyOffice, OfficeDetect.detect(sample("legacy-sample.doc")))
    }

    @Test
    fun aRenamedLegacyFileIsStillRefusedByItsBytes() {
        val renamed = tempFile(".docx", sample("legacy-sample.doc").readBytes())

        assertEquals(DocumentKind.LegacyOffice, OfficeDetect.detect(renamed))
    }

    @Test
    fun aTruncatedZipIsUnreadable() {
        val truncated = tempFile(".docx", sample("notes.docx").readBytes().copyOf(64))

        assertEquals(DocumentKind.Unreadable, OfficeDetect.detect(truncated))
    }

    @Test
    fun aZipThatIsNotAnOfficePackageIsOther() {
        val zip = zipWith("readme.txt", "photos/cat.jpg")

        val kind = OfficeDetect.detect(zip)

        assertTrue(kind is DocumentKind.Other)
        assertTrue((kind as DocumentKind.Other).description.contains("ZIP"))
    }

    @Test
    fun anExcelWorkbookIsOtherNotWordOrPowerPoint() {
        val zip = zipWith("[Content_Types].xml", "xl/workbook.xml")

        val kind = OfficeDetect.detect(zip)

        assertTrue(kind is DocumentKind.Other)
        assertTrue((kind as DocumentKind.Other).description.contains("Excel"))
    }

    @Test
    fun anEmptyFileIsOtherWithAnExplanation() {
        val empty = tempFile(".bin", ByteArray(0))

        val kind = OfficeDetect.detect(empty)

        assertTrue(kind is DocumentKind.Other)
    }

    @Test
    fun aMissingFileIsUnreadableInsteadOfCrashing() {
        val missing = File(File(System.getProperty("java.io.tmpdir")), "no-such-office-file.docx")

        assertEquals(DocumentKind.Unreadable, OfficeDetect.detect(missing))
    }
}
