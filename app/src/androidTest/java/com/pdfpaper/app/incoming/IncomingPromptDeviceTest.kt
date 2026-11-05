package com.pdfpaper.app.incoming

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfpaper.app.MainActivity
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * T47: a running activity receives a share through onNewIntent. Several PDFs
 * offer Merge; images offer Make a PDF. Nothing is merged or converted until
 * the user confirms.
 */
@RunWith(AndroidJUnit4::class)
class IncomingPromptDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application

    private fun file(name: String, bytes: ByteArray): File =
        File(context.cacheDir, "incoming-prompt").apply { mkdirs() }
            .let { dir -> File(dir, "${System.nanoTime()}-$name").apply { writeBytes(bytes) } }

    private fun uri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    private fun pdf(): File = file(
        "shared.pdf",
        PdfBuilder(PageSize.A4).apply { addPage() }.build()
    )

    @Test
    fun twoSharedPdfsOfferMerge() {
        val first = pdf()
        val second = file(
            "second.pdf",
            PdfBuilder(PageSize.LETTER).apply { addPage() }.build()
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri(first), uri(second)))
        }

        compose.runOnUiThread { compose.activity.onNewIntent(intent) }

        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(UiTags.INCOMING_PROMPT).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Merge these 2 PDFs?").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INCOMING_MERGE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INCOMING_OPEN_FIRST).assertIsDisplayed()
        // Close the dialog before the rule tears the activity down.
        compose.onNodeWithTag(UiTags.INCOMING_OPEN_FIRST).performClick()
        compose.waitForIdle()
    }

    @Test
    fun sharedImagesOfferMakingAPdf() {
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(200, 80, 40))
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        val image = file("photo.png", output.toByteArray())

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri(image))
        }

        compose.runOnUiThread { compose.activity.onNewIntent(intent) }

        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(UiTags.INCOMING_PROMPT).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Make a PDF from these images?").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INCOMING_MAKE_PDF).assertIsDisplayed()
        // Close the dialog before the rule tears the activity down.
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
    }
}
