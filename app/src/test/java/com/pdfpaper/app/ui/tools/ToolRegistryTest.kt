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
                "highlight", "underline", "draw", "note",
                "fill-form", "protect",
                "info", "save", "save-as", "share", "print"
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
        assertEquals(listOf("note"), ToolRegistry.search("sticky").map { it.id })
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
    fun everyViewerToolModeMapsBackToATool() {
        assertEquals("highlight", ToolRegistry.byViewerTool("highlight")?.id)
        assertEquals("draw", ToolRegistry.byViewerTool("ink")?.id)
        assertEquals("note", ToolRegistry.byViewerTool("note")?.id)
        assertEquals("add-text", ToolRegistry.byViewerTool("text")?.id)
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
}
