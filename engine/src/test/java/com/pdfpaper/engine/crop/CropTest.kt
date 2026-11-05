package com.pdfpaper.engine.crop

import com.pdfpaper.engine.export.PageRaster
import com.pdfpaper.engine.export.PageRasterizer
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.mark.PageSelection
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.redact.PdfRedaction
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.text.extractText
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T46: CropBox writing, page selection, auto-detection and the destructive
 * "remove hidden content" option.
 */
class CropTest {

    // ---------------------------------------------------------------- fixtures

    private fun open(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun stream(num: Int, text: String): TestObj {
        val body = text.toByteArray(StandardCharsets.ISO_8859_1)
        val out = java.io.ByteArrayOutputStream()
        out.write("<< /Length ${body.size} >>\nstream\n".toByteArray(StandardCharsets.US_ASCII))
        out.write(body)
        out.write("\nendstream".toByteArray(StandardCharsets.US_ASCII))
        return TestObj(num, 0, out.toByteArray())
    }

    private fun page(parent: Int, contents: Int, media: String): String =
        "<< /Type /Page /Parent $parent 0 R /MediaBox [$media] " +
            "/Resources << /Font << /F1 20 0 R >> >> /Contents $contents 0 R >>"

    /**
     * Three 600 × 800 pages. Page 1 also carries an existing CropBox
     * [50 50 550 750] so margins-from-CropBox can be checked.
     */
    private fun threePageDoc(): ByteArray = buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] " +
                    "/CropBox [50 50 550 750] /Resources << /Font << /F1 20 0 R >> >> " +
                    "/Contents 6 0 R >>"
            ),
            TestObj(4, 0, page(2, 7, "0 0 600 800")),
            TestObj(5, 0, page(2, 8, "0 0 600 800")),
            stream(6, "BT /F1 12 Tf 20 20 Td (P1) Tj ET"),
            stream(7, "BT /F1 12 Tf 20 20 Td (P2) Tj ET"),
            stream(8, "BT /F1 12 Tf 20 20 Td (P3) Tj ET"),
            TestObj(20, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        )
    )

    private fun sizesDoc(): ByteArray = buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>"),
            TestObj(3, 0, page(2, 5, "0 0 300 300")),
            TestObj(4, 0, page(2, 6, "0 0 120 120")),
            stream(5, ""),
            stream(6, ""),
            TestObj(20, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        )
    )

    private fun hiddenContentDoc(): ByteArray = buildTestPdf(
        listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(3, 0, page(2, 5, "0 0 600 800")),
            stream(
                5,
                "BT /F1 12 Tf 20 20 Td (OUTSIDE) Tj ET\n" +
                    "BT /F1 12 Tf 200 300 Td (INSIDE) Tj ET"
            ),
            TestObj(20, 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        )
    )

    private fun cropBox(document: PdfDocument, pageIndex: Int): PdfRect =
        PdfRect.of(document.pages()[pageIndex].cropBox)
            ?: error("page $pageIndex has no crop box")

    private fun mediaBox(document: PdfDocument, pageIndex: Int): PdfRect =
        PdfRect.of(document.pages()[pageIndex].mediaBox)
            ?: error("page $pageIndex has no media box")

    private fun assertRect(expected: PdfRect, actual: PdfRect?, tolerance: Double = 0.001) {
        assertNotNull(actual)
        assertEquals(expected.left, actual.left, tolerance, "left")
        assertEquals(expected.bottom, actual.bottom, tolerance, "bottom")
        assertEquals(expected.right, actual.right, tolerance, "right")
        assertEquals(expected.top, actual.top, tolerance, "top")
    }

    // ------------------------------------------------------------ CropBox write

    @Test
    fun marginsWriteCropBoxAndKeepMediaBox() {
        open(threePageDoc()).use { document ->
            val result = PdfCrop.apply(
                document,
                CropRequest(CropSpec.Margins(10.0, 20.0, 30.0, 40.0), PageSelection.All)
            ).getOrThrow()

            assertEquals(3, result.cropBoxes.size)
            assertFalse(result.hiddenContentRemoved)
            open(result.bytes).use { reopened ->
                val pages = reopened.pages()
                assertEquals(3, pages.size)
                // Page 1's current CropBox is [50 50 550 750]; margins trim from it.
                assertRect(PdfRect(60.0, 70.0, 520.0, 710.0), cropBox(reopened, 0))
                assertRect(PdfRect(10.0, 20.0, 570.0, 760.0), cropBox(reopened, 1))
                assertRect(PdfRect(10.0, 20.0, 570.0, 760.0), cropBox(reopened, 2))
                // The media box is untouched on every page.
                assertRect(PdfRect(0.0, 0.0, 600.0, 800.0), mediaBox(reopened, 0))
                assertRect(PdfRect(0.0, 0.0, 600.0, 800.0), mediaBox(reopened, 1))
                assertRect(PdfRect(0.0, 0.0, 600.0, 800.0), mediaBox(reopened, 2))
            }
        }
    }

    @Test
    fun pageSelectionAllOddEvenAndCustom() {
        open(threePageDoc()).use { document ->
            val cases = listOf(
                PageSelection.All to listOf(0, 1, 2),
                PageSelection.Odd to listOf(0, 2),
                PageSelection.Even to listOf(1),
                PageSelection.Custom(setOf(1)) to listOf(1)
            )
            for ((selection, expectedPages) in cases) {
                val result = PdfCrop.apply(
                    document,
                    CropRequest(CropSpec.Margins(5.0, 5.0, 5.0, 5.0), selection)
                ).getOrThrow()
                assertEquals(expectedPages.toSet(), result.cropBoxes.keys, "selection=$selection")
                open(result.bytes).use { reopened ->
                    // Unselected pages keep their previous CropBox.
                    if (selection is PageSelection.Custom) {
                        assertRect(PdfRect(50.0, 50.0, 550.0, 750.0), cropBox(reopened, 0))
                    }
                    for (index in expectedPages) {
                        val base = cropBox(reopened, index)
                        assertTrue(base.width < 600.0 && base.height < 800.0, "page $index")
                    }
                }
            }
        }
    }

    @Test
    fun perPageBoxesAreWrittenExactly() {
        open(threePageDoc()).use { document ->
            val box0 = PdfRect(80.0, 90.0, 480.0, 690.0)
            val box2 = PdfRect(10.0, 10.0, 590.0, 790.0)
            val result = PdfCrop.apply(
                document,
                CropRequest(
                    CropSpec.PerPage(mapOf(0 to box0, 1 to PdfRect(0.0, 0.0, 1.0, 1.0), 2 to box2)),
                    PageSelection.All
                )
            ).getOrThrow()
            // A 1 × 1 pt box is legal, so all three pages are in the map.
            assertEquals(setOf(0, 1, 2), result.cropBoxes.keys)
            open(result.bytes).use { reopened ->
                assertRect(box0, cropBox(reopened, 0))
                assertRect(box2, cropBox(reopened, 2))
            }
        }
    }

    @Test
    fun invalidInputsReturnFailureAndNeverThrow() {
        open(threePageDoc()).use { document ->
            assertTrue(
                PdfCrop.apply(document, CropRequest(CropSpec.Margins(700.0, 0.0, 0.0, 0.0)))
                    .isFailure
            )
            assertTrue(
                PdfCrop.apply(document, CropRequest(CropSpec.Margins(-1.0, 0.0, 0.0, 0.0)))
                    .isFailure
            )
            assertTrue(
                PdfCrop.apply(
                    document,
                    CropRequest(
                        CropSpec.PerPage(mapOf(0 to PdfRect(-10.0, 0.0, 100.0, 100.0))),
                        PageSelection.Custom(setOf(0))
                    )
                ).isFailure
            )
            assertTrue(
                PdfCrop.apply(
                    document,
                    CropRequest(CropSpec.Margins(1.0, 1.0, 1.0, 1.0), PageSelection.Custom(emptySet()))
                ).isFailure
            )
        }
    }

    // -------------------------------------------------------------- remove hidden

    @Test
    fun aNormalCropOnlyHidesContentOutsideTheBox() {
        open(hiddenContentDoc()).use { document ->
            val result = PdfCrop.apply(
                document,
                CropRequest(CropSpec.Margins(100.0, 100.0, 100.0, 100.0))
            ).getOrThrow()
            open(result.bytes).use { reopened ->
                assertRect(PdfRect(100.0, 100.0, 500.0, 700.0), cropBox(reopened, 0))
                val text = reopened.pages()[0].extractText().runs.joinToString("") { it.text }
                assertTrue(text.contains("OUTSIDE"), "a normal crop must not rewrite content: $text")
                assertTrue(text.contains("INSIDE"), "text=$text")
            }
        }
    }

    @Test
    fun removeHiddenContentLeavesNoTextOutsideTheBox() {
        open(hiddenContentDoc()).use { document ->
            val result = PdfCrop.apply(
                document,
                CropRequest(CropSpec.Margins(100.0, 100.0, 100.0, 100.0)),
                removeHiddenContent = true
            ).getOrThrow()
            assertTrue(result.complete, "unredacted=${result.unredacted}")
            assertTrue(result.hiddenContentRemoved, "unredacted=${result.unredacted}")
            open(result.bytes).use { reopened ->
                assertRect(PdfRect(100.0, 100.0, 500.0, 700.0), cropBox(reopened, 0))
                val text = reopened.pages()[0].extractText().runs.joinToString("") { it.text }
                assertFalse(text.contains("OUTSIDE"), "text outside the crop survived: $text")
                assertTrue(text.contains("INSIDE"), "text inside the crop was lost: $text")
                val verification = PdfRedaction.verify(reopened, listOf("OUTSIDE"))
                assertTrue(
                    verification.passed,
                    "leaks=${verification.leaks} unredacted=${verification.unredacted}"
                )
            }
        }
    }

    // ------------------------------------------------------------------ auto

    /**
     * A rasterizer that paints a black rectangle where the fixture says content
     * is; every other pixel stays white. It also proves the API only needs the
     * host hook, not a platform renderer.
     */
    private class RectRasterizer(
        private val content: Map<Int, PdfRect?>
    ) : PageRasterizer {
        override fun rasterize(page: com.pdfpaper.engine.page.PdfPage, plan: PageRenderPlan): PageRaster {
            val width = plan.pixelWidth
            val height = plan.pixelHeight
            val rgba = ByteArray(width * height * 4)
            for (i in rgba.indices step 4) {
                rgba[i] = 255.toByte()
                rgba[i + 1] = 255.toByte()
                rgba[i + 2] = 255.toByte()
                rgba[i + 3] = 255.toByte()
            }
            val rect = content[page.index]
            if (rect != null) {
                val a = plan.matrix.apply(rect.left, rect.top)
                val b = plan.matrix.apply(rect.right, rect.bottom)
                val left = min(a[0], b[0]).toInt().coerceIn(0, width - 1)
                val right = max(a[0], b[0]).toInt().coerceIn(left + 1, width)
                val top = min(a[1], b[1]).toInt().coerceIn(0, height - 1)
                val bottom = max(a[1], b[1]).toInt().coerceIn(top + 1, height)
                for (y in top until bottom) {
                    for (x in left until right) {
                        val i = (y * width + x) * 4
                        rgba[i] = 0
                        rgba[i + 1] = 0
                        rgba[i + 2] = 0
                    }
                }
            }
            return PageRaster(width, height, rgba)
        }
    }

    @Test
    fun autoDetectsTheContentBox() {
        open(sizesDoc()).use { document ->
            val rasterizer = RectRasterizer(
                mapOf(0 to PdfRect(50.0, 60.0, 150.0, 160.0), 1 to null)
            )
            val result = PdfCrop.auto(
                document,
                rasterizer,
                tolerance = 10,
                dpi = 96,
                pages = PageSelection.All
            ).getOrThrow()
            assertEquals(setOf(0), result.boxes.keys)
            assertEquals(listOf(1), result.emptyPages)
            assertNull(result.uniformSize)
            val box = result.boxes.getValue(0)
            assertRect(PdfRect(50.0, 60.0, 150.0, 160.0), box, tolerance = 2.0)
            assertTrue(result.warnings.any { it.contains("blank") }, "warnings=${result.warnings}")
        }
    }

    @Test
    fun autoSameSizeGrowsBoxesAndReportsTooSmallPages() {
        open(sizesDoc()).use { document ->
            val rasterizer = RectRasterizer(
                mapOf(
                    0 to PdfRect(50.0, 50.0, 200.0, 200.0),
                    1 to PdfRect(10.0, 10.0, 110.0, 110.0)
                )
            )
            val result = PdfCrop.auto(
                document,
                rasterizer,
                sameSize = true,
                dpi = 96
            ).getOrThrow()
            val size = assertNotNull(result.uniformSize)
            assertEquals(150.0, size.width, 2.0)
            assertEquals(150.0, size.height, 2.0)
            assertEquals(setOf(0, 1), result.boxes.keys)
            // Page 1 can take the full 150 pt; page 2 is only 120 pt wide/tall.
            assertEquals(150.0, result.boxes.getValue(0).width, 2.0)
            assertEquals(150.0, result.boxes.getValue(0).height, 2.0)
            assertEquals(120.0, result.boxes.getValue(1).width, 0.001)
            assertEquals(120.0, result.boxes.getValue(1).height, 0.001)
            assertEquals(listOf(1), result.unevenPages)
            assertTrue(result.warnings.any { it.contains("smaller") }, "warnings=${result.warnings}")
        }
    }

    @Test
    fun autoRejectsAnImpossibleToleranceOrDpi() {
        open(sizesDoc()).use { document ->
            val rasterizer = RectRasterizer(emptyMap())
            assertTrue(PdfCrop.auto(document, rasterizer, tolerance = 999).isFailure)
            assertTrue(PdfCrop.auto(document, rasterizer, dpi = 2).isFailure)
            assertTrue(
                PdfCrop.auto(
                    document,
                    rasterizer,
                    pages = PageSelection.Custom(emptySet())
                ).isFailure
            )
        }
    }

    @Test
    fun marginPresetsAreUniqueNonNegativeAndLookupable() {
        val ids = MarginPreset.PRESETS.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "preset ids must be unique")
        for (preset in MarginPreset.PRESETS) {
            assertTrue(preset.id.isNotBlank() && preset.label.isNotBlank(), "preset=$preset")
            assertEquals(4, preset.margins.size, "preset=${preset.id}")
            assertTrue(preset.margins.all { it.isFinite() && it >= 0.0 }, "preset=${preset.id}")
            assertEquals(preset, MarginPreset.fromId(preset.id), "preset=${preset.id}")
        }
    }

    @Test
    fun autoOnAnAlreadyCroppedPageNeverRevealsTheOldMedia() {
        open(threePageDoc()).use { document ->
            // Page 1's visible CropBox is [50 50 550 750]; content fills it.
            val visible = PdfRect(50.0, 50.0, 550.0, 750.0)
            val rasterizer = RectRasterizer(mapOf(0 to visible))
            val result = PdfCrop.auto(
                document,
                rasterizer,
                pages = PageSelection.Custom(setOf(0)),
                dpi = 96
            ).getOrThrow()
            val box = result.boxes.getValue(0)
            assertTrue(box.left >= 49.0 && box.bottom >= 49.0, "box=$box")
            assertTrue(box.right <= 551.0 && box.top <= 751.0, "box=$box")
            assertTrue(abs(box.width - 500.0) < 2.0, "box=$box")
        }
    }
}
