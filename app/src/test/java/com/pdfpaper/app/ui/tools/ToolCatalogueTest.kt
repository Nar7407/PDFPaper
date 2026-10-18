package com.pdfpaper.app.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogueTest {

    @Test
    fun blankSearchReturnsTheWholeCatalogueInOrder() {
        assertEquals(
            listOf(
                "merge", "split", "rotate", "reorder", "delete", "extract",
                "annotate", "fill-form",
                "add-text", "edit-text", "add-image", "edit-image",
                "encrypt", "info"
            ),
            ToolCatalogue.search("  ").map { it.id }
        )
    }

    @Test
    fun searchMatchesTitlesCaseInsensitively() {
        assertEquals(listOf("rotate"), ToolCatalogue.search("ROTATE").map { it.id })
        assertEquals(listOf("info"), ToolCatalogue.search("info").map { it.id })
    }

    @Test
    fun searchMatchesKeywordsAsWellAsTitles() {
        assertEquals(listOf("merge"), ToolCatalogue.search("combine").map { it.id })
        assertEquals(listOf("annotate"), ToolCatalogue.search("highlight").map { it.id })
        assertEquals(listOf("encrypt"), ToolCatalogue.search("password").map { it.id })
        assertEquals(listOf("fill-form"), ToolCatalogue.search("checkbox").map { it.id })
    }

    @Test
    fun unknownQueryReturnsNoTools() {
        assertTrue(ToolCatalogue.search("definitely-not-a-tool").isEmpty())
    }

    @Test
    fun idsAreUniqueAndLookupWorks() {
        val ids = ToolCatalogue.tools.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("Merge", ToolCatalogue.byId("merge")?.title)
        assertEquals(null, ToolCatalogue.byId("missing"))
    }

    @Test
    fun editGroupContainsTheFourObjectTools() {
        assertEquals(
            listOf("add-text", "edit-text", "add-image", "edit-image"),
            ToolCatalogue.tools.filter { it.group == ToolGroup.EDIT }.map { it.id }
        )
        assertTrue(
            ToolCatalogue.tools
                .filter { it.group == ToolGroup.EDIT }
                .all { it.requiresEditingEngine }
        )
    }

    @Test
    fun groupedCatalogueKeepsSectionOrderAndOmitsEmptyGroups() {
        val grouped = ToolCatalogue.grouped()
        assertEquals(
            listOf(
                ToolGroup.ORGANIZE,
                ToolGroup.ANNOTATE,
                ToolGroup.EDIT,
                ToolGroup.SECURITY
            ),
            grouped.map { it.first }
        )
        assertTrue(grouped.all { it.second.isNotEmpty() })
        assertTrue(ToolCatalogue.grouped(ToolCatalogue.search("rotate")).none {
            it.first == ToolGroup.EDIT
        })
    }
}
