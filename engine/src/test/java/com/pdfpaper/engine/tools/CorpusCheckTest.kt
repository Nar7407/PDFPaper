package com.pdfpaper.engine.tools

import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CorpusCheckTest {

    private fun validPdf(): File {
        val bytes = buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                TestObj(
                    3, 0,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] " +
                        "/Resources << >> /Contents 4 0 R >>"
                ),
                TestObj(4, 0, "<< /Length 0 >>\nstream\n\nendstream")
            )
        )
        val file = File.createTempFile("pdfpaper-corpus-check", ".pdf")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun perPageTextFailureIsReported() {
        val result = checkOne(validPdf(), extractText = { throw IllegalStateException("boom") })
        assertFalse(result.ok)
        assertTrue(result.reason.contains("boom"))
    }

    @Test
    fun perPageDisplayFailureIsReported() {
        val result = checkOne(
            validPdf(),
            buildDisplayList = { throw IllegalStateException("draw-boom") }
        )
        assertFalse(result.ok)
        assertTrue(result.reason.contains("draw-boom"))
    }

    private fun testdataDir(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null && !File(dir, "testdata").isDirectory) {
            dir = dir.parentFile
        }
        return File(dir, "testdata")
    }

    @Test
    fun corpusExercisesTextAndGraphics() {
        val report = File.createTempFile("pdfpaper-corpus-report", ".txt")
        report.deleteOnExit()
        runCorpusCheck(testdataDir(), report)
        val text = report.readText()
        val totals = Regex("""Total: (\d+) files, (\d+) opened OK, (\d+) failed""").find(text)
        assertNotNull(totals)
        assertEquals("0", totals!!.groupValues[3])
        val textRuns = Regex("""Text runs: (\d+)""").find(text)?.groupValues?.get(1)?.toInt() ?: 0
        val commands = Regex("""Display list: (\d+) commands""").find(text)?.groupValues?.get(1)?.toInt() ?: 0
        assertTrue("text runs=$textRuns", textRuns > 0)
        assertTrue("commands=$commands", commands > 0)
    }
}
