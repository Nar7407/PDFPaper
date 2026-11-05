package com.pdfpaper.app.ui.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.links.DocumentLink
import com.pdfpaper.engine.links.LinkDestination
import com.pdfpaper.engine.links.LinkFit
import com.pdfpaper.engine.links.LinkKind
import com.pdfpaper.engine.links.LinkTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T61 on-device UI: the modal preview card. It opens over the page, shows the
 * rendered target, offers Go to / Pin to side / Copy text / Close, pages
 * sideways through a citation list and explains a reference it cannot find.
 */
@RunWith(AndroidJUnit4::class)
class LinkPreviewSheetDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private fun bitmap(): Bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        Canvas(this).apply {
            drawRect(0f, 40f, 320f, 72f, Paint().apply { color = Color.DKGRAY })
            drawRect(0f, 96f, 220f, 120f, Paint().apply { color = Color.GRAY })
        }
    }

    private fun target(page: Int, title: String, label: String = "Bibliography entry"): LinkTarget =
        LinkTarget(
            pageIndex = page,
            destination = LinkDestination(page, LinkFit.XYZ, top = 700.0),
            title = title,
            previewRect = PdfRect(60.0, 620.0, 552.0, 700.0),
            pageFractionTop = 0.12,
            label = label,
            copyText = "$title. Smith, J. (2019). A Study of PDF Links."
        )

    private fun link(
        text: String,
        targets: List<LinkTarget>,
        kind: LinkKind = LinkKind.CITATION,
        note: String? = null
    ): DocumentLink = DocumentLink(
        sourcePageIndex = 0,
        rect = PdfRect(72.0, 600.0, 120.0, 615.0),
        kind = kind,
        text = text,
        targets = targets,
        note = note
    )

    @Test
    fun theCardShowsTheTargetAndItsActions() {
        var closed = 0
        var copied: LinkTarget? = null
        var pinned: LinkTarget? = null
        var wentTo: LinkTarget? = null
        val entry = target(1, "[3] Smith, J. (2019). A Study of PDF Links.")
        compose.setContent {
            PDFPaperTheme {
                LinkPreviewSheet(
                    request = LinkCardRequest(link("[3]", listOf(entry))),
                    onDismiss = { closed++ },
                    onGoTo = { wentTo = it },
                    onPin = { pinned = it },
                    onCopy = { copied = it },
                    loadPreview = { _, _ -> bitmap() }
                )
            }
        }

        compose.onNodeWithTag(UiTags.LINK_PREVIEW_SHEET).assertIsDisplayed()
        compose.onNodeWithText("[3]", substring = false).assertIsDisplayed()
        compose.onNodeWithText(entry.title).assertIsDisplayed()
        compose.onNodeWithText("Bibliography entry · Page 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Preview of ${entry.title}").assertIsDisplayed()

        compose.onNodeWithText("Go to").performClick()
        compose.onNodeWithText("Pin to side").performClick()
        compose.onNodeWithText("Copy text").performClick()
        compose.onNodeWithText("Close").performClick()

        assertEquals(entry, wentTo)
        assertEquals(entry, pinned)
        assertEquals(entry, copied)
        assertEquals(1, closed)
    }

    @Test
    fun aCitationWithSeveralTargetsPagesSideways() {
        val first = target(1, "[3] Smith, J. (2019).")
        val second = target(1, "[5] Jones, A. (2020).")
        val third = target(2, "[6] Brown, B. (2021).")
        var wentTo: LinkTarget? = null
        compose.setContent {
            PDFPaperTheme {
                LinkPreviewSheet(
                    request = LinkCardRequest(link("[3, 5-7]", listOf(first, second, third))),
                    onDismiss = {},
                    onGoTo = { wentTo = it },
                    onPin = {},
                    onCopy = {},
                    loadPreview = { _, _ -> bitmap() }
                )
            }
        }

        compose.onNodeWithText("Swipe for the next target (1 of 3)").assertIsDisplayed()
        compose.onNodeWithText(second.title).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.LINK_TARGET_PAGER).performTouchInput { swipeLeft() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Swipe for the next target (2 of 3)")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(second.title).assertIsDisplayed()

        compose.onNodeWithTag(UiTags.LINK_TARGET_PAGER).performTouchInput { swipeLeft() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Swipe for the next target (3 of 3)")
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Go to").performClick()
        assertEquals(third, wentTo)
    }

    @Test
    fun aMissingTargetIsExplainedInsteadOfGuessed() {
        var closed = 0
        val text = "Reference [9] was not found in this document."
        compose.setContent {
            PDFPaperTheme {
                LinkPreviewSheet(
                    request = LinkCardRequest(
                        link("[9]", emptyList(), note = text)
                    ),
                    onDismiss = { closed++ },
                    onGoTo = {},
                    onPin = {},
                    onCopy = {},
                    loadPreview = { _, _ -> bitmap() }
                )
            }
        }

        compose.onNodeWithText("No matching target").assertIsDisplayed()
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Close preview").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun thePinnedPanelKeepsTheTargetOnScreen() {
        val entry = target(3, "Table 4: Results", label = "Table")
        var closed = 0
        var wentTo: LinkTarget? = null
        compose.setContent {
            PDFPaperTheme {
                PinnedLinkPanel(
                    target = entry,
                    onClose = { closed++ },
                    onGoTo = { wentTo = it },
                    loadPreview = { _, _ -> bitmap() }
                )
            }
        }

        compose.onNodeWithTag(UiTags.LINK_PINNED_PANEL).assertIsDisplayed()
        compose.onNodeWithText("Pinned preview").assertIsDisplayed()
        compose.onNodeWithText(entry.title).assertIsDisplayed()
        compose.onNodeWithText("Table · Page 4").assertIsDisplayed()
        compose.onNodeWithContentDescription("Preview of ${entry.title}").assertIsDisplayed()

        compose.onNodeWithText("Go to").performClick()
        assertEquals(entry, wentTo)

        compose.onNodeWithContentDescription("Unpin preview").performClick()
        assertEquals(1, closed)
    }
}
