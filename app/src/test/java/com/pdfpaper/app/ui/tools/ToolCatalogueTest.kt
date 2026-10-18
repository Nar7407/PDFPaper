package com.pdfpaper.app.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogueTest {

    @Test
    fun blankSearchReturnsTheWholeCatalogueInOrder() {
        assertEquals(
            listOf(
                "merge", "split", "rotate", "reorder", "delete",
                "extract", "annotate", "fill-form", "encrypt", "info"
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
}
