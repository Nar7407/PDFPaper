package com.pdfpaper.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherLabelsTest {

    @Test
    fun namesAreHiddenByDefault() {
        assertFalse(LauncherLabels.DEFAULT_SHOW_NAMES)
        assertEquals(
            listOf("Recent PDF 1", "Recent PDF 2", "Recent PDF 3"),
            (0..2).map { index ->
                LauncherLabels.recentLabel(index, "Secret acquisition plan.pdf", showNames = false)
            }
        )
    }

    @Test
    fun hiddenWidgetAndShortcutLabelsNeverContainTheFileName() {
        val hidden = LauncherLabels.recentLabel(1, "Board minutes.pdf", showNames = false)

        assertEquals("Recent PDF 2", hidden)
        assertFalse(hidden.contains("Board"))
        assertFalse(hidden.contains("minutes"))
    }

    @Test
    fun namesAppearOnlyAfterTheUserOptsIn() {
        assertEquals(
            "Quarterly report.pdf",
            LauncherLabels.recentLabel(0, "Quarterly report.pdf", showNames = true)
        )
        assertEquals(
            "Open Quarterly report.pdf",
            LauncherLabels.recentLongLabel(0, "Quarterly report.pdf", showNames = true)
        )
    }

    @Test
    fun whitespaceIsCollapsedAndBlankNamesFallBackToTheHiddenLabel() {
        assertEquals(
            "Board minutes.pdf",
            LauncherLabels.recentLabel(0, "  Board\n\tminutes.pdf  ", showNames = true)
        )
        assertEquals(
            "Recent PDF 1",
            LauncherLabels.recentLabel(0, "   ", showNames = true)
        )
    }

    @Test
    fun longNamesAreBoundedForLaunchers() {
        val label = LauncherLabels.recentLabel(0, "x".repeat(200), showNames = true)

        assertEquals(LauncherLabels.MAX_LABEL_LENGTH, label.length)
    }

    @Test
    fun pinnedLabelsFollowTheSamePrivacyChoice() {
        assertEquals(
            LauncherLabels.HIDDEN_PINNED_LABEL,
            LauncherLabels.pinnedLabel("Tax return 2026.pdf", showNames = false)
        )
        assertEquals(
            "Tax return 2026.pdf",
            LauncherLabels.pinnedLabel("Tax return 2026.pdf", showNames = true)
        )
        assertEquals(
            "Open a PDF document",
            LauncherLabels.pinnedLongLabel("Tax return 2026.pdf", showNames = false)
        )
        assertEquals(
            "Open Tax return 2026.pdf",
            LauncherLabels.pinnedLongLabel("Tax return 2026.pdf", showNames = true)
        )
    }

    @Test
    fun pinnedIdsAreStablePerDocumentAndDifferentBetweenDocuments() {
        val first = LauncherLabels.pinnedId("content://provider/doc/1")
        val same = LauncherLabels.pinnedId("content://provider/doc/1")
        val other = LauncherLabels.pinnedId("content://provider/doc/2")

        assertEquals(first, same)
        assertNotEquals(first, other)
        assertTrue(first.startsWith("pinned-"))
        assertTrue(first.length <= 64)
    }
}
