package com.pdfpaper.app.incoming

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import java.io.File

/**
 * T47: two PDFs shared from another app are merged into one plan. The prompt
 * asks first, and only the confirmed merge puts both documents into the
 * organizer.
 */
@RunWith(AndroidJUnit4::class)
class IncomingMergeDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application

    private fun pdf(name: String, pages: Int): File =
        File(context.cacheDir, "incoming-merge").apply { mkdirs() }
            .let { dir -> File(dir, "${System.nanoTime()}-$name").apply { writeBytes(pdfBytes(pages)) } }

    private fun pdfBytes(pages: Int): ByteArray =
        PdfBuilder(PageSize.A4).apply { repeat(pages) { addPage() } }.build()

    private fun uri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    @Test
    fun twoSharedPdfsAreMergedIntoOnePlan() {
        val first = pdf("first.pdf", 1)
        val second = pdf("second.pdf", 2)
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(uri(first), uri(second))
            )
        }

        compose.runOnUiThread { compose.activity.onNewIntent(intent) }

        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(UiTags.INCOMING_PROMPT).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.INCOMING_MERGE).performClick()

        // The organizer shows one plan with the pages of both documents.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("Add PDF").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("3 pages").assertExists()
        // Let the new entry settle before the test activity is destroyed.
        compose.waitForIdle()
    }}
