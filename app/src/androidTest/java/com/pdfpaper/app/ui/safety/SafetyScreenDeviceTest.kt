package com.pdfpaper.app.ui.safety

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** T49: the details screen really scans a document on a device, off the UI thread. */
@RunWith(AndroidJUnit4::class)
class SafetyScreenDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aCleanGeneratedDocumentReportsNoKnownRiskyFeatures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = PdfBuilder(PageSize.A4).apply { addPage() }.build()
        val file = File(context.cacheDir, "safety-clean-${System.nanoTime()}.pdf")
        file.writeBytes(bytes)

        compose.setContent {
            PDFPaperTheme {
                SafetyScreen(
                    path = file.absolutePath,
                    displayName = "clean.pdf",
                    onBack = {}
                )
            }
        }

        compose.waitUntil(10_000) {
            compose.onAllNodes(
                androidx.compose.ui.test.hasTestTag(UiTags.SAFETY_FINDINGS)
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.SAFETY_FINDINGS).assertIsDisplayed()
        compose.onNodeWithText("No known risky features found").assertIsDisplayed()
        compose.onNodeWithText(
            "This does not prove the document is safe. It only means the check did not " +
                "see the features it knows about."
        ).assertIsDisplayed()
    }
}
