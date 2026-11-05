package com.pdfpaper.app.ui.viewer.session

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.ReadingMode
import com.pdfpaper.app.data.ReadingOrientation
import com.pdfpaper.app.data.ReadingState
import com.pdfpaper.app.data.ReadingStateStore
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.viewer.ViewerScreen
import com.pdfpaper.app.ui.viewer.fit.FitContentMode
import com.pdfpaper.engine.create.PageSize
import com.pdfpaper.engine.create.PdfBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * T38b on-device acceptance for the reading session. The pure rules live in
 * `ReadingStateTest`; this test proves they reach the screen: the first frame
 * the reader sees is the saved page (not page 1 scrolled away from), a shorter
 * file clamps and says so, "Forget position" really forgets, "Always start at
 * page 1" keeps the mode, and a narrower page keeps the same part of the page.
 */
@RunWith(AndroidJUnit4::class)
class SessionStateDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    private lateinit var origin: String

    @Before
    fun defaults() {
        origin = "content://pdfpaper.test/session-${System.nanoTime()}"
        AppSettings.setAlwaysStartAtPageOne(context, false)
    }

    @After
    fun restore() {
        AppSettings.setAlwaysStartAtPageOne(context, false)
        ReadingStateStore(context).forget(origin)
    }

    private fun book(pages: Int = 10): File {
        val builder = PdfBuilder(PageSize.A4)
        for (index in 0 until pages) {
            val page = builder.addPage()
            page.drawText("Page ${index + 1}", 72.0, 90.0, 24.0)
            for (row in 0 until 20) {
                page.drawText("Line $row of page ${index + 1}", 72.0, 140.0 + row * 18.0, 11.0)
            }
        }
        val file = File(context.cacheDir, "session-${System.nanoTime()}.pdf")
        file.writeBytes(builder.build())
        return file
    }

    private fun save(
        pageIndex: Int,
        pageCount: Int,
        fraction: Float = 0.35f,
        zoom: Float = 1f,
        mode: ReadingMode = ReadingMode.PAGE,
        orientation: ReadingOrientation = ReadingOrientation.AUTO
    ) {
        ReadingStateStore(context).save(
            origin,
            "session book.pdf",
            ReadingState(
                pageIndex = pageIndex,
                pageFraction = fraction,
                pageCount = pageCount,
                zoom = zoom,
                mode = mode,
                orientation = orientation,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Renders the viewer and returns the (page, offset) of the first laid-out
     * frame. [width] changes the page width the way a rotation does, which is
     * why it is a parameter.
     */
    private fun show(
        file: File,
        width: Int = 411,
        onFirstFrame: (Int, Int) -> Unit = { _, _ -> }
    ) {
        val page = AtomicInteger(-1)
        val offset = AtomicInteger(-1)
        val startedAt = AtomicLong(System.currentTimeMillis())
        compose.setContent {
            Box(Modifier.width(width.dp)) {
                ViewerScreen(
                    path = file.absolutePath,
                    displayName = file.name,
                    origin = origin,
                    onFirstFrame = { index, px ->
                        page.set(index)
                        offset.set(px)
                        onFirstFrame(index, px)
                    }
                )
            }
        }
        compose.waitUntil(30_000) { page.get() >= 0 }
        lastFirstFrameMs = System.currentTimeMillis() - startedAt.get()
        firstPage = page.get()
        firstOffset = offset.get()
    }

    private var firstPage = -1
    private var firstOffset = 0
    private var lastFirstFrameMs = 0L

    @Test
    fun theFirstFrameIsAlreadyTheSavedPage() {
        val file = book()
        save(pageIndex = 6, pageCount = 10)

        show(file)

        assertEquals(6, firstPage)
        // Part-way into the page, not its top: the offset was estimated from
        // the page's own shape before the list was laid out.
        assertTrue("expected a non-zero offset, got $firstOffset", firstOffset > 0)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("10 pages", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The first frame must not be a page-1 frame that scrolled away: the
        // page is on screen and no clamp note was shown.
        assertTrue(
            "the clamp note must not appear for a valid page",
            compose.onAllNodesWithText("This document changed", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun aShorterFileClampsToItsLastPageAndSaysSo() {
        val file = book(pages = 4)
        save(pageIndex = 50, pageCount = 60)

        show(file)

        assertEquals(3, firstPage)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("This document changed", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            compose.onAllNodesWithText("now has 4 pages", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun forgetPositionClearsTheSessionAndStaysCleared() {
        val file = book()
        save(pageIndex = 5, pageCount = 10)
        show(file)
        assertEquals(5, firstPage)

        compose.onNodeWithTag(UiTags.VIEWER_TOOLS_BUTTON).performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag(UiTags.VIEWER_TOOLS_SHEET)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.VIEWER_FORGET_POSITION).performScrollTo().performClick()
        compose.waitForIdle()

        assertNull("forget must drop the record", ReadingStateStore(context).load(origin))
        // The debounced save and the save on leaving must not put it back.
        Thread.sleep(1_200)
        compose.waitForIdle()
        assertNull(
            "the forgotten position must not come back",
            ReadingStateStore(context).load(origin)
        )
    }

    @Test
    fun alwaysStartAtPageOneKeepsTheReadingMode() {
        val file = book()
        save(pageIndex = 7, pageCount = 10, mode = ReadingMode.FIT_CONTENT)
        AppSettings.setAlwaysStartAtPageOne(context, true)

        show(file)

        assertEquals(0, firstPage)
        // The mode is still the reader's: a fitted session stays fitted.
        compose.waitUntil(10_000) {
            AppSettings.fitContentMode(context, origin) != FitContentMode.OFF
        }
        assertEquals(FitContentMode.DOCUMENT, AppSettings.fitContentMode(context, origin))
    }

    @Test
    fun aNarrowerPageKeepsTheSamePartOfThePage() {
        // A rotation changes the page's pixel width. The restored position is a
        // fraction of the page, so the same text must stay at the top - the
        // offset has to shrink with the width, not stay absolute. `key(width)`
        // restarts the viewer the way a recreated activity does: same saved
        // session, new page width, new first frame.
        val file = book()
        save(pageIndex = 4, pageCount = 10, fraction = 0.5f)
        val frames = mutableListOf<Pair<Int, Pair<Int, Int>>>()
        var width by mutableStateOf(411)
        compose.setContent {
            Box(Modifier.width(width.dp)) {
                key(width) {
                    ViewerScreen(
                        path = file.absolutePath,
                        displayName = file.name,
                        origin = origin,
                        onFirstFrame = { index, px ->
                            synchronized(frames) { frames.add(width to (index to px)) }
                        }
                    )
                }
            }
        }
        compose.waitUntil(30_000) { synchronized(frames) { frames.any { it.first == 411 } } }
        val wide = synchronized(frames) { frames.first { it.first == 411 }.second }

        width = 205
        compose.waitUntil(30_000) { synchronized(frames) { frames.any { it.first == 205 } } }
        val narrow = synchronized(frames) { frames.first { it.first == 205 }.second }

        assertEquals(4, wide.first)
        assertEquals(wide.first, narrow.first)
        assertTrue("expected positive offsets, got $wide and $narrow", wide.second > 0 && narrow.second > 0)
        // Half the width is half the page height, so half the offset. Padding
        // and rounding cost a few pixels.
        val expected = wide.second / 2
        assertTrue(
            "offset ${narrow.second} is not about half of ${wide.second}",
            kotlin.math.abs(narrow.second - expected) <= 24
        )
    }

    @Test
    fun theFirstFrameArrivesQuickly() {
        // There is no T31 budget constant in this repository, so this records
        // the measurement instead of pretending to a number: the threshold is
        // deliberately generous, and the value is logged for the notes.
        val file = book()
        save(pageIndex = 6, pageCount = 10)

        show(file)

        assertNotNull(ReadingStateStore(context).load(origin))
        assertTrue(
            "the first frame took ${lastFirstFrameMs}ms",
            lastFirstFrameMs < 5_000
        )
        println("T38b first frame with a restored session: ${lastFirstFrameMs}ms")
    }
}
