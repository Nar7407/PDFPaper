package com.pdfpaper.engine.tools

import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import org.junit.Assert.assertFalse
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
}
