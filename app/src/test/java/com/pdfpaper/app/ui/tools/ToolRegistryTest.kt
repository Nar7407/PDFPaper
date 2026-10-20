package com.pdfpaper.app.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    @Test
    fun blankSearchReturnsTheWholeRegistryInOrder() {
        assertEquals(
            listOf(
                "rotate", "reorder", "delete", "extract", "insert", "split", "merge",
                "add-text", "edit-text", "add-image", "edit-image",
                "highlight", "underline", "draw", "note", "comments", "stamp",
                "redact", "redact-pages", "redact-find", "redact-properties", "sanitize",
                "fill-form", "sign", "signatures", "protect",
                "new-pdf", "scan", "ocr", "compress",
                "watermark", "page-numbers", "bates",
                "export-images", "info", "save", "save-as", "share", "print"
            ),
            ToolRegistry.search("  ").map { it.id }
        )
    }

    @Test
    fun searchMatchesTitlesCaseInsensitively() {
        assertEquals(listOf("rotate"), ToolRegistry.search("ROTATE").map { it.id })
        assertEquals(listOf("info"), ToolRegistry.search("info").map { it.id })
    }

    @Test
    fun searchMatchesKeywordsAsWellAsTitles() {
        assertEquals(listOf("merge"), ToolRegistry.search("combine").map { it.id })
        assertEquals(listOf("note", "comments"), ToolRegistry.search("sticky").map { it.id })
        assertEquals(listOf("protect"), ToolRegistry.search("password").map { it.id })
        assertEquals(listOf("fill-form"), ToolRegistry.search("checkbox").map { it.id })
    }

    @Test
    fun searchMatchesDescriptionWordPrefixes() {
        // "need" only appears in Delete's description, not in any name or keyword.
        assertEquals(listOf("delete"), ToolRegistry.search("need").map { it.id })
    }

    @Test
    fun unknownQueryReturnsNoTools() {
        assertTrue(ToolRegistry.search("definitely-not-a-tool").isEmpty())
    }

    @Test
    fun idsAreUniqueAndLookupWorks() {
        val ids = ToolRegistry.tools.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("Merge", ToolRegistry.byId("merge")?.name)
        assertEquals(null, ToolRegistry.byId("missing"))
    }

    @Test
    fun viewerToolsMapBackToATool() {
        assertEquals("highlight", ToolRegistry.byViewerTool("highlight")?.id)
        assertEquals("draw", ToolRegistry.byViewerTool("ink")?.id)
        assertEquals("note", ToolRegistry.byViewerTool("note")?.id)
        assertEquals("add-text", ToolRegistry.byViewerTool("text")?.id)
    }

    @Test
    fun newToolsHaveExplicitDestinationsOrViewerPanels() {
        assertEquals(ToolDestination.NEW_PDF, ToolRegistry.byId("new-pdf")?.destination)
        assertEquals(ToolDestination.SCAN, ToolRegistry.byId("scan")?.destination)
        assertEquals(ToolDestination.COMPRESS, ToolRegistry.byId("compress")?.destination)
        assertEquals("comments", ToolRegistry.byId("comments")?.viewerAction)
        assertEquals("stamps", ToolRegistry.byId("stamp")?.viewerAction)
        assertEquals("sign", ToolRegistry.byId("sign")?.viewerAction)
        assertEquals("signatures", ToolRegistry.byId("signatures")?.viewerAction)
        assertEquals("ocr", ToolRegistry.byId("ocr")?.viewerAction)
        assertTrue(ToolRegistry.byId("ocr")!!.isAvailable(demoEngine = false))
    }

    @Test
    fun theCompressToolIsReadyAndSearchable() {
        val compress = ToolRegistry.byId("compress")!!
        assertEquals("Compress PDF", compress.name)
        assertEquals(ToolSection.DOCUMENT, compress.section)
        assertTrue(compress.isAvailable(demoEngine = false))
        assertEquals(ToolStatus.READY, compress.status(demoEngine = false))
        assertEquals(listOf("compress"), ToolRegistry.search("shrink").map { it.id })
        assertEquals(listOf("compress"), ToolRegistry.search("smaller").map { it.id })
    }

    @Test
    fun theMarkToolsAreReadyAndSearchable() {
        val watermark = ToolRegistry.byId("watermark")!!
        assertEquals("Watermark", watermark.name)
        assertEquals("Put a text or picture watermark on the pages.", watermark.description)
        assertEquals(ToolSection.DOCUMENT, watermark.section)
        assertEquals(ToolDestination.MARK, watermark.destination)
        assertTrue(watermark.isAvailable(demoEngine = false))
        assertEquals(ToolStatus.READY, watermark.status(demoEngine = false))

        val numbers = ToolRegistry.byId("page-numbers")!!
        assertEquals("Page numbers", numbers.name)
        assertEquals(ToolDestination.MARK, numbers.destination)
        assertTrue(numbers.isAvailable(demoEngine = false))

        val bates = ToolRegistry.byId("bates")!!
        assertEquals("Bates numbering", bates.name)
        assertEquals(ToolDestination.MARK, bates.destination)
        assertTrue(bates.isAvailable(demoEngine = false))

        assertEquals(listOf("watermark"), ToolRegistry.search("confidential").map { it.id })
        assertEquals(listOf("page-numbers"), ToolRegistry.search("pagination").map { it.id })
        assertEquals(listOf("bates"), ToolRegistry.search("legal").map { it.id })
    }

    @Test
    fun editGroupContainsTheFourObjectTools() {
        assertEquals(
            listOf("add-text", "edit-text", "add-image", "edit-image"),
            ToolRegistry.tools.filter { it.section == ToolSection.EDIT }.map { it.id }
        )
        assertTrue(
            ToolRegistry.tools
                .filter { it.section == ToolSection.EDIT }
                .all { it.needsEditingEngine }
        )
    }

    @Test
    fun availabilityFollowsTheEditingEngineAndSupportFlags() {
        val addText = ToolRegistry.byId("add-text")!!
        assertFalse("no demo engine", addText.isAvailable(demoEngine = false))
        assertTrue("debug demo engine", addText.isAvailable(demoEngine = true))

        val underline = ToolRegistry.byId("underline")!!
        assertFalse(underline.isAvailable(demoEngine = true))

        val rotate = ToolRegistry.byId("rotate")!!
        assertTrue(rotate.isAvailable(demoEngine = false))
    }

    @Test
    fun groupedRegistryKeepsSectionOrderAndOmitsEmptySections() {
        val grouped = ToolRegistry.grouped()
        assertEquals(
            listOf(
                ToolSection.PAGES,
                ToolSection.EDIT,
                ToolSection.ANNOTATE,
                ToolSection.REDACT,
                ToolSection.FORMS_SECURITY,
                ToolSection.DOCUMENT
            ),
            grouped.map { it.first }
        )
        assertTrue(grouped.all { it.second.isNotEmpty() })
        assertTrue(ToolRegistry.grouped(ToolRegistry.search("rotate")).none {
            it.first == ToolSection.EDIT
        })
    }

    @Test
    fun redactGroupHoldsTheFivePrivacyTools() {
        assertEquals(
            listOf("redact", "redact-pages", "redact-find", "redact-properties", "sanitize"),
            ToolRegistry.tools.filter { it.section == ToolSection.REDACT }.map { it.id }
        )
    }

    @Test
    fun everyToolReportsAReadyOrComingSoonStatus() {
        ToolRegistry.tools.forEach { tool ->
            val status = tool.status(demoEngine = false)
            assertTrue(
                tool.id,
                status == ToolStatus.READY || status == ToolStatus.COMING_SOON
            )
        }
        // A supported tool is Ready in a release build.
        assertEquals(ToolStatus.READY, ToolRegistry.byId("rotate")!!.status(false))
    }
}
