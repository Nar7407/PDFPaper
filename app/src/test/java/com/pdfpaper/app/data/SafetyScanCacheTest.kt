package com.pdfpaper.app.data

import com.pdfpaper.safety.RiskLevel
import com.pdfpaper.safety.SafetyReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafetyScanCacheTest {

    @Test
    fun reportIsReadBackByTheSamePath() {
        SafetyScanCache.clear()
        val report = SafetyReport(risk = RiskLevel.MEDIUM, findings = emptyList())
        SafetyScanCache.put("/tmp/a.pdf", report)
        assertEquals(report, SafetyScanCache.get("/tmp/a.pdf"))
        assertNull(SafetyScanCache.get("/tmp/b.pdf"))
    }

    @Test
    fun blankPathsAreIgnored() {
        SafetyScanCache.clear()
        SafetyScanCache.put("", SafetyReport(risk = RiskLevel.LOW, findings = emptyList()))
        assertNull(SafetyScanCache.get(""))
    }
}
