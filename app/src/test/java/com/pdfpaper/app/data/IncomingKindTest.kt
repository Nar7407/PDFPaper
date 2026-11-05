package com.pdfpaper.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T47: incoming files are classified from magic bytes, never from a file name
 * or the MIME type a provider claims.
 */
class IncomingKindTest {

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun pdfHeaderAtStartIsPdf() {
        assertEquals(
            IncomingKind.PDF,
            IncomingKindDetector.detect(ascii("%PDF-1.7\nrest of the file"))
        )
    }

    @Test
    fun pdfHeaderAfterLeadingWhitespaceIsPdf() {
        val header = ByteArray(40) { ' '.code.toByte() } +
            ascii("%PDF-1.4\n")
        assertEquals(IncomingKind.PDF, IncomingKindDetector.detect(header))
    }

    @Test
    fun pdfHeaderBeyondTheSniffedWindowIsNotPdf() {
        val header = ByteArray(1024) { 'x'.code.toByte() }
        assertFalse(IncomingKindDetector.containsPdfHeader(header))
        assertEquals(IncomingKind.OTHER, IncomingKindDetector.detect(header))
    }

    @Test
    fun zipIsOfficeCandidate() {
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
        assertEquals(IncomingKind.OFFICE, IncomingKindDetector.detect(bytes))
    }

    @Test
    fun legacyOleIsReportedSeparately() {
        val bytes = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        )
        assertEquals(IncomingKind.LEGACY_OFFICE, IncomingKindDetector.detect(bytes))
    }

    @Test
    fun pngJpegGifWebpAndHeifAreImages() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val gif = ascii("GIF89a....")
        val webp = ascii("RIFF") + ByteArray(4) + ascii("WEBP")
        val heif = ByteArray(4) + ascii("ftypheic")
        listOf(png, jpeg, gif, webp, heif).forEach {
            assertEquals(IncomingKind.IMAGE, IncomingKindDetector.detect(it))
        }
    }

    @Test
    fun aClaimedButWrongTypeFallsBackToOther() {
        assertEquals(
            IncomingKind.OTHER,
            IncomingKindDetector.detect(ascii("just a text file, not a PDF"))
        )
        assertEquals(IncomingKind.OTHER, IncomingKindDetector.detect(ByteArray(0)))
    }

    @Test
    fun pdfLookingBytesNeedTheRealHeader() {
        val fake = ascii("%PDX-1.7")
        assertFalse(IncomingKindDetector.containsPdfHeader(fake))
        assertTrue(IncomingKindDetector.containsPdfHeader(ascii("x%PDF-1.7")))
    }
}
