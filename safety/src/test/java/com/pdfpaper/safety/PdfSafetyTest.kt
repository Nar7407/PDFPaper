package com.pdfpaper.safety

import com.pdfpaper.engine.io.ByteArraySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class PdfSafetyTest {

    private fun scan(bytes: ByteArray): SafetyReport =
        PdfSafety.scan(ByteArraySource(bytes)).getOrThrow()

    private fun report(builder: TestPdfBuilder): SafetyReport = scan(builder.build())

    @Test
    fun cleanPageHasNoFindings() {
        val result = report(basePdf())
        assertTrue(result.findings.isEmpty())
        assertEquals(RiskLevel.LOW, result.risk)
        assertTrue(result.summary().contains("No known risky features"))
    }

    @Test
    fun openActionAndAdditionalActionsAreReported() {
        val builder = basePdf()
        builder.set(
            1,
            catalogBody(
                "/OpenAction << /S /JavaScript /JS (app.alert) >> " +
                    "/AA << /WC << /S /JavaScript /JS (app.alert) >> >>"
            )
        )
        val result = report(builder)

        assertEquals(RiskLevel.HIGH, result.risk)
        assertTrue(result.findings.any { it.category == SafetyCategory.JAVASCRIPT })
        assertTrue(result.findings.any { it.title == "Action when the document opens" })
        assertTrue(result.findings.any { it.title == "Additional actions" })
    }

    @Test
    fun everyNamedActionIsReportedWithItsPage() {
        val builder = basePdf()
        val actionObjects = listOf(
            builder.add("<< /S /Launch /F (program.exe) >>"),
            builder.add("<< /S /SubmitForm /F (https://example.com/post) >>"),
            builder.add("<< /S /ImportData /F (data.fdf) >>"),
            builder.add("<< /S /GoToR /F (other.pdf) >>"),
            builder.add("<< /S /GoToE /T (other.pdf) >>"),
            builder.add("<< /S /URI /URI (https://example.com) >>")
        )
        val annotations = actionObjects.map { builder.add(actionAnnotation("$it 0 R")) }
        builder.set(3, pageBody(annotations))

        val result = report(builder)
        val titles = result.findings.map { it.title }
        assertTrue(titles.contains("Launch action"))
        assertTrue(titles.contains("Form submission action"))
        assertTrue(titles.contains("Import data action"))
        assertTrue(titles.contains("Remote go-to action"))
        assertTrue(titles.contains("Embedded go-to action"))
        assertTrue(titles.contains("Web link"))
        val launch = result.findings.first { it.title == "Launch action" }
        assertEquals(0, launch.location?.pageIndex)
        assertEquals(RiskLevel.HIGH, result.risk)
    }

    @Test
    fun embeddedExecutableScriptAndArchiveAreClassifiedByBytesAndName() {
        val builder = basePdf()
        val exeStream = builder.addStream(
            "<< /Type /EmbeddedFile /Length 2 >>",
            byteArrayOf(0x4D, 0x5A)
        )
        val exeSpec = builder.add(
            "<< /Type /Filespec /F (invoice.pdf.exe) /UF (invoice.pdf.exe) " +
                "/EF << /F $exeStream 0 R >> >>"
        )
        val scriptBytes = "#!/bin/sh\necho hi\n".toByteArray(StandardCharsets.ISO_8859_1)
        val scriptStream = builder.addStream(
            "<< /Type /EmbeddedFile /Length ${scriptBytes.size} >>",
            scriptBytes
        )
        val scriptSpec = builder.add(
            "<< /Type /Filespec /F (run.js) /UF (run.js) /EF << /F $scriptStream 0 R >> >>"
        )
        val zipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)
        val zipStream = builder.addStream(
            "<< /Type /EmbeddedFile /Length ${zipBytes.size} >>",
            zipBytes
        )
        val zipSpec = builder.add(
            "<< /Type /Filespec /F (bundle.zip) /UF (bundle.zip) /EF << /F $zipStream 0 R >> >>"
        )
        val names = builder.add(
            "<< /Names [ (invoice.pdf.exe) $exeSpec 0 R (run.js) $scriptSpec 0 R " +
                "(bundle.zip) $zipSpec 0 R ] >>"
        )
        builder.set(1, catalogBody("/Names << /EmbeddedFiles $names 0 R >>"))

        val result = report(builder)
        val attachments = result.findings.filter { it.category == SafetyCategory.EMBEDDED_FILE }
        assertEquals(3, attachments.size)
        assertTrue(attachments.any { it.severity == RiskLevel.HIGH })
        assertTrue(attachments.any { it.evidence == "Windows executable" })
        assertTrue(attachments.any { it.evidence == "Script" })
        assertTrue(attachments.any { it.evidence == "ZIP archive" })
        assertTrue(attachments.any { it.explanation.contains("two extensions") })
    }

    @Test
    fun officeMacroArchiveIsHighRisk() {
        val builder = basePdf()
        val bytes = "PK\u0003\u0004 ... vbaProject.bin ...".toByteArray(StandardCharsets.ISO_8859_1)
        val stream = builder.addStream(
            "<< /Type /EmbeddedFile /Length ${bytes.size} >>",
            bytes
        )
        val spec = builder.add(
            "<< /Type /Filespec /F (report.docx) /UF (report.docx) /EF << /F $stream 0 R >> >>"
        )
        val names = builder.add("<< /Names [ (report.docx) $spec 0 R ] >>")
        builder.set(1, catalogBody("/Names << /EmbeddedFiles $names 0 R >>"))

        val result = report(builder)
        val attachment = result.findings.first { it.category == SafetyCategory.EMBEDDED_FILE }
        assertEquals(RiskLevel.HIGH, attachment.severity)
        assertTrue(attachment.evidence?.contains("macros") == true)
    }

    @Test
    fun xfaAndRichMediaAreReported() {
        val builder = basePdf()
        val xfa = builder.addStream("<< /Length 5 >>", "<xfa>".toByteArray(StandardCharsets.ISO_8859_1))
        builder.set(1, catalogBody("/AcroForm << /XFA $xfa 0 R >>"))
        val movie = builder.add("<< /Type /Annot /Subtype /Movie /Rect [0 0 100 100] >>")
        builder.set(3, pageBody(listOf(movie)))

        val result = report(builder)
        assertTrue(result.findings.any { it.category == SafetyCategory.XFA })
        assertTrue(result.findings.any { it.category == SafetyCategory.RICH_MEDIA })
        assertEquals(RiskLevel.HIGH, result.risk)
    }

    @Test
    fun escapedNamesAndNestedFiltersAreReported() {
        val builder = basePdf()
        builder.add("<< /J#61vaScript (app.alert) >>")
        builder.addStream(
            "<< /Length 1 /Filter [/ASCII85Decode /FlateDecode /FlateDecode] >>",
            byteArrayOf(0)
        )

        val result = report(builder)
        assertTrue(result.findings.any { it.category == SafetyCategory.OBFUSCATION })
        assertTrue(result.findings.any { it.title == "Deeply nested stream filters" })
        assertTrue(result.findings.any { it.title == "Escaped names in object" })
        assertTrue(result.findings.any { it.category == SafetyCategory.JAVASCRIPT })
    }

    @Test
    fun dataAfterTheLastEofIsReported() {
        val bytes = basePdf().build() + "JUNK".toByteArray(StandardCharsets.ISO_8859_1)
        val result = scan(bytes)
        assertTrue(result.findings.any { it.title == "Data after the last end-of-file marker" })
    }

    @Test
    fun repeatedEofMarkersAreReported() {
        val suffix = "\n%%EOF\n".repeat(5).toByteArray(StandardCharsets.ISO_8859_1)
        val result = scan(basePdf().build() + suffix)
        assertTrue(result.findings.any { it.title == "Several incremental updates" })
    }

    @Test
    fun damagedXrefFallsBackAndIsReported() {
        val original = basePdf().build().toString(StandardCharsets.ISO_8859_1)
        val damaged = original.replace(Regex("""startxref\s+\d+"""), "startxref\n999999")
        val result = scan(damaged.toByteArray(StandardCharsets.ISO_8859_1))
        assertTrue(result.findings.any { it.title == "Cross-reference table was rebuilt" })
    }

    @Test
    fun suspiciousLinksAreReportedWithTheRealDomain() {
        val builder = basePdf("https://example.com")
        val annotation = builder.add(linkAnnotation("https://bit.ly/abc"))
        builder.set(3, pageBody(listOf(annotation)))

        val result = report(builder)
        val linkFinding = result.findings.first { it.category == SafetyCategory.LINK }
        assertEquals(RiskLevel.HIGH, linkFinding.severity)
        assertEquals("bit.ly", linkFinding.link?.host)
        assertNotNull(linkFinding.link)
        assertTrue(linkFinding.link!!.warnings.any { it.contains("shortener") })
        assertTrue(linkFinding.link!!.warnings.any { it.contains("visible text") })
    }

    @Test
    fun aMatchingVisibleDomainIsNotFlaggedAsMismatch() {
        val builder = basePdf("https://example.com")
        val annotation = builder.add(linkAnnotation("https://example.com/path"))
        builder.set(3, pageBody(listOf(annotation)))

        val result = report(builder)
        val linkFinding = result.findings.first { it.category == SafetyCategory.LINK }
        assertEquals(RiskLevel.LOW, linkFinding.severity)
        assertFalse(linkFinding.link!!.warnings.any { it.contains("visible text") })
    }

    @Test
    fun malformedInputReturnsAResultAndNeverThrows() {
        val result = PdfSafety.scan(ByteArraySource("%PDF-1.7\nbroken".toByteArray()))
        assertTrue(result.isFailure || result.getOrNull() != null)
    }
}
