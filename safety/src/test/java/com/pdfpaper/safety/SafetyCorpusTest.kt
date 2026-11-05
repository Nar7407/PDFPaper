package com.pdfpaper.safety

import com.pdfpaper.engine.io.FileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * T49 acceptance on the committed corpus: these structural fixtures are not
 * risky documents. The corpus deliberately includes two damaged cross-reference
 * files; a rebuilt xref is a low-risk structural finding, so every file must
 * stay at LOW (no medium or high finding). A high finding here would be a false
 * accusation; a medium one would mean ordinary copy damage looks alarming.
 */
class SafetyCorpusTest {

    @Test
    fun theCommittedCorpusProducesOnlyLowRiskFindings() {
        val root = File("../testdata")
        val files = root.listFiles { file -> file.isFile && file.extension.equals("pdf", true) }
            ?.sortedBy { it.name }
            .orEmpty()
        assumeTrue("the corpus is available next to the module", files.isNotEmpty())

        for (file in files) {
            val result = PdfSafety.scan(FileSource(file))
            assertTrue("${file.name} must open for the scan", result.isSuccess)
            val report = result.getOrThrow()
            assertEquals(
                "${file.name} must stay low risk: ${report.findings}",
                RiskLevel.LOW,
                report.risk
            )
            assertTrue(
                "${file.name} must not contain a medium or high finding: ${report.findings}",
                report.findings.none { it.severity != RiskLevel.LOW }
            )
        }
    }
}
