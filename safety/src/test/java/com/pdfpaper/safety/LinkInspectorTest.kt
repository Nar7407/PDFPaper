package com.pdfpaper.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkInspectorTest {

    @Test
    fun ordinaryLinkHasNoWarnings() {
        val link = PdfSafety.inspectLink("https://example.com/docs", "example.com")
        assertEquals(RiskLevel.LOW, link.risk)
        assertEquals("example.com", link.host)
        assertFalse(link.hasWarnings)
    }

    @Test
    fun numericIpAddressIsFlagged() {
        val link = PdfSafety.inspectLink("http://192.168.1.20/login")
        assertEquals(RiskLevel.MEDIUM, link.risk)
        assertTrue(link.warnings.any { it.contains("IP address") })
    }

    @Test
    fun shortenerIsFlagged() {
        val link = PdfSafety.inspectLink("https://t.co/abc")
        assertEquals(RiskLevel.MEDIUM, link.risk)
        assertTrue(link.warnings.any { it.contains("shortener") })
    }

    @Test
    fun punycodeAndMixedScriptHostsAreHighRisk() {
        val punycode = PdfSafety.inspectLink("https://xn--pypal-4ve.com")
        assertEquals(RiskLevel.HIGH, punycode.risk)
        assertTrue(punycode.warnings.any { it.contains("punycode") })

        val mixed = PdfSafety.inspectLink("https://p\u0430ypal.com")
        assertEquals(RiskLevel.HIGH, mixed.risk)
        assertTrue(mixed.warnings.any { it.contains("script") })
    }

    @Test
    fun visibleTextMismatchShowsBothNames() {
        val link = PdfSafety.inspectLink(
            "https://evil.example/login",
            "Open https://paypal.com to continue"
        )
        assertEquals(RiskLevel.HIGH, link.risk)
        assertTrue(link.warnings.any { it.contains("paypal.com") })
        assertTrue(link.warnings.any { it.contains("evil.example") })
    }

    @Test
    fun userInfoAndBidiTricksAreHighRisk() {
        val userInfo = PdfSafety.inspectLink("https://paypal.com@evil.example/")
        assertEquals("evil.example", userInfo.host)
        assertEquals(RiskLevel.HIGH, userInfo.risk)
        assertTrue(userInfo.warnings.any { it.contains("@") })

        val bidi = PdfSafety.inspectLink("https://example.com/\u202Egnp.exe")
        assertEquals(RiskLevel.HIGH, bidi.risk)
        assertTrue(bidi.warnings.any { it.contains("direction") })
    }

    @Test
    fun javascriptAndDataLinksAreReported() {
        val js = PdfSafety.inspectLink("javascript:app.alert(1)")
        assertEquals(RiskLevel.HIGH, js.risk)
        assertTrue(js.warnings.any { it.contains("JavaScript") })

        val data = PdfSafety.inspectLink("data:text/html,<script>alert(1)</script>")
        assertEquals(RiskLevel.HIGH, data.risk)
        assertTrue(data.warnings.any { it.contains("data:") })
    }

    @Test
    fun mailtoGetsItsDomain() {
        val link = PdfSafety.inspectLink("mailto:help@example.com")
        assertEquals("example.com", link.host)
        assertEquals(RiskLevel.LOW, link.risk)
    }
}
