package com.pdfpaper.convert.ooxml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmlBuilderTest {

    @Test
    fun `escapes text and attribute values`() {
        assertEquals("a &amp; b &lt;c&gt;", XmlBuilder.escapeText("a & b <c>"))
        assertEquals("say &quot;hi&quot;", XmlBuilder.escapeAttribute("say \"hi\""))
    }

    @Test
    fun `drops characters XML forbids`() {
        // NUL and a lone high surrogate are both illegal in XML 1.0.
        val cleaned = XmlBuilder.stripInvalid("ab\u0000c\uD83D")
        assertEquals("ab\uFFFDc\uFFFD", cleaned)
        // A valid surrogate pair survives unchanged.
        val emoji = "x\uD83D\uDE00y"
        assertEquals(emoji, XmlBuilder.stripInvalid(emoji))
    }

    @Test
    fun `builds nested elements with leaf text on one line`() {
        val xml = XmlBuilder().declaration()
            .open("a:root", "xmlns:a" to "urn:a")
            .empty("a:child", "x" to "1")
            .open("a:p")
            .leafText("a:t", "  spaced  ", "xml:space" to "preserve")
            .close("a:p")
            .close("a:root")
            .build()
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("<a:child x=\"1\"/>"))
        assertTrue(xml.contains("<a:t xml:space=\"preserve\">  spaced  </a:t>"))
        assertTrue(xml.contains("</a:root>"))
    }
}
