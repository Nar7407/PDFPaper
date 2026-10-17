package com.pdfpaper.engine.page

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun openPdf(objects: List<TestObj>): PdfDocument =
    PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow()

private fun pageDict(parentRef: String, extra: String = "") =
    "<< /Type /Page /Parent $parentRef$extra >>"

private fun pagesDict(parentRef: String, kids: String, count: String) =
    if (parentRef.isEmpty()) {
        // Root pages node (§7.7.3.2): no /Parent.
        "<< /Type /Pages /Kids [$kids]$count >>"
    } else {
        "<< /Type /Pages /Parent $parentRef /Kids [$kids]$count >>"
    }

private fun PdfArray.numbers(): List<Double> = items.map {
    when (it) {
        is com.pdfpaper.engine.model.PdfInteger -> it.value.toDouble()
        is com.pdfpaper.engine.model.PdfReal -> it.value
        else -> Double.NaN
    }
}

private fun fontNames(resources: PdfDict?): Set<String> {
    val font = resources?.get("Font") as? PdfDict ?: return emptySet()
    return font.entries.keys.map { it.name }.toSet()
}

class PageTreeTest {

    // -- Nested trees and index access --------------------------------------

    @Test
    fun nestedTreeTraversedInDocumentOrder() {
        val doc = openPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, pagesDict("", "3 0 R 4 0 R", " /Count 3")),
                TestObj(3, 0, pagesDict("2 0 R", "5 0 R 6 0 R", " /Count 2")),
                TestObj(4, 0, pagesDict("2 0 R", "7 0 R", " /Count 1")),
                TestObj(5, 0, pageDict("3 0 R")),
                TestObj(6, 0, pageDict("3 0 R")),
                TestObj(7, 0, pageDict("4 0 R"))
            )
        )
        doc.use {
            val pages = it.pages()
            assertEquals(3, pages.size)
            assertEquals(listOf(5, 6, 7), pages.map { p -> p.objNum })
            assertEquals(0, pages[0].index)
            assertEquals(3, it.pageCount())
            assertEquals(7, it.page(2)!!.objNum)
            assertNull(it.page(3))
            assertNull(it.page(-1))
        }
    }

    @Test
    fun directDictKidsAreTolerated() {
        val doc = openPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(
                    2, 0,
                    "<< /Type /Pages /Kids [<< /Type /Page /Parent 2 0 R >>] /Count 1 >>"
                )
            )
        )
        doc.use { assertEquals(1, it.pageCount()) }
    }

    // -- Inheritance ---------------------------------------------------------

    @Test
    fun attributesResolveThroughNearestAncestor() {
        val doc = openPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(
                    2, 0,
                    "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 " +
                        "/MediaBox [0 0 612 792] /Rotate 90 " +
                        "/Resources << /Font << /F1 << /Type /Font /BaseFont /Helvetica >> >> >> >>"
                ),
                TestObj(
                    3, 0,
                    "<< /Type /Pages /Parent 2 0 R /Kids [5 0 R] /Count 1 " +
                        "/Rotate 180 /Resources << /Font << /F2 << /Type /Font /BaseFont /Times-Roman >> >> >> >>"
                ),
                TestObj(
                    4, 0, pageDict("2 0 R", "/Contents 6 0 R")
                ),
                TestObj(
                    5, 0, pageDict("3 0 R", "/MediaBox [0 0 100 100]")
                ),
                TestObj(6, 0, "<< /Length 6 >>\nstream\nBT\nET\nendstream")
            )
        )
        doc.use {
            val overriding = it.page(0)!! // object 5, under the intermediate node
            val inheriting = it.page(1)!! // object 4, directly under the root node

            // Page-level MediaBox wins over the inherited one.
            assertEquals(listOf(0.0, 0.0, 100.0, 100.0), overriding.mediaBox!!.numbers())
            // Rotate from the intermediate beats the root's 90.
            assertEquals(180, overriding.rotate)
            // Resources come from the nearest ancestor (the intermediate node).
            assertEquals(setOf("F2"), fontNames(overriding.resources))
            // CropBox defaults to MediaBox even when only inherited.
            assertEquals(listOf(0.0, 0.0, 100.0, 100.0), overriding.cropBox!!.numbers())

            // The other page inherits everything from the root node.
            assertEquals(listOf(0.0, 0.0, 612.0, 792.0), inheriting.mediaBox!!.numbers())
            assertEquals(90, inheriting.rotate)
            assertEquals(setOf("F1"), fontNames(inheriting.resources))
            // CropBox defaults through the inherited MediaBox.
            assertEquals(listOf(0.0, 0.0, 612.0, 792.0), inheriting.cropBox!!.numbers())
        }
    }

    @Test
    fun rotateDefaultsAndNormalizes() {
        val doc = openPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /Rotate 450 >>"),
                TestObj(3, 0, pageDict("2 0 R", "/Rotate 360")),
                TestObj(4, 0, pageDict("2 0 R"))
            )
        )
        doc.use {
            assertEquals(0, it.page(0)!!.rotate) // 360 normalizes to 0
            assertEquals(90, it.page(1)!!.rotate) // 450 inherited -> 450 % 360
        }
    }

    // -- Missing /Count and broken trees --------------------------------------

    @Test
    fun missingCountCountsByTraversal() {
        val doc = openPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                // No /Count anywhere in the tree.
                TestObj(2, 0, pagesDict("", "3 0 R 4 0 R", "")),
                TestObj(3, 0, pagesDict("2 0 R", "5 0 R 6 0 R", "")),
                TestObj(4, 0, pagesDict("2 0 R", "7 0 R", "")),
                TestObj(5, 0, pageDict("3 0 R")),
                TestObj(6, 0, pageDict("3 0 R")),
                TestObj(7, 0, pageDict("4 0 R"))
            )
        )
        doc.use { assertEquals(3, it.pageCount()) }
    }

    @Test
    fun cyclesNeverHangTheTraversal() {
        val nodeBody = "<< /Type /Pages /Kids [2 0 R 3 0 R] >>"
        val doc = openPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, nodeBody), // refers back to itself
                TestObj(3, 0, pageDict("2 0 R"))
            )
        )
        doc.use {
            // The self-reference is skipped; the reachable page is counted once.
            assertEquals(1, it.pageCount())
        }
    }

    @Test
    fun missingPagesPointerYieldsNoPages() {
        val doc = openPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog >>"),
                TestObj(2, 0, pagesDict("", "", " /Count 0"))
            )
        )
        doc.use {
            assertTrue(it.pages().isEmpty())
            assertTrue(pageTree(it) is PageTreeResult.NoRoot)
        }
    }

    @Test
    fun danglingPagesRefYieldsBroken() {
        val doc = openPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 99 0 R >>")
            )
        )
        doc.use {
            assertTrue(it.pages().isEmpty())
            assertTrue(pageTree(it) is PageTreeResult.Broken)
        }
    }

    @Test
    fun noCatalogYieldsNoPages() {
        val doc = openPdf(listOf(TestObj(1, 0, "<< /Type /Page >>")))
        doc.use {
            assertTrue(it.pages().isEmpty())
            assertTrue(pageTree(it) is PageTreeResult.NoRoot)
        }
    }

    @Test
    fun rootRecoveredFromCatalogScanAfterRebuild() {
        // Classic xref + trailer, with "startxref" and beyond chopped off so
        // the file takes the rebuild path (§7.5.5 recovery) and still finds
        // the catalog object by scanning for "/Type /Catalog".
        val full = buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, pagesDict("", "3 0 R", " /Count 1")),
                TestObj(3, 0, pageDict("2 0 R", "/MediaBox [0 0 100 100]"))
            )
        )
        val text = full.toString(Charsets.ISO_8859_1)
        val cutAt = text.indexOf("startxref")
        require(cutAt > 0)
        val rebuilt = text.substring(0, cutAt).toByteArray(Charsets.ISO_8859_1) + "%%EOF\n".toByteArray(Charsets.ISO_8859_1)
        PdfDocument.open(ByteArraySource(rebuilt)).getOrThrow().use {
            assertEquals(1, it.pageCount())
            assertEquals(listOf(0.0, 0.0, 100.0, 100.0), it.page(0)!!.mediaBox!!.numbers())
        }
    }

    // -- Contents -------------------------------------------------------------

    @Test
    fun contentsArrayIsResolvedDecodedAndJoined() {
        val doc = openPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, pagesDict("", "3 0 R 4 0 R", " /Count 2")),
                TestObj(
                    3, 0,
                    pageDict("2 0 R", "/Contents [5 0 R 6 0 R] /MediaBox [0 0 100 100]")
                ),
                TestObj(4, 0, pageDict("2 0 R")),
                TestObj(5, 0, "<< /Length 10 /Filter /ASCIIHexDecode >>\nstream\n476F6F64>\nendstream"),
                TestObj(6, 0, "<< /Length 8 /Filter /ASCIIHexDecode >>\nstream\n627965>\nendstream")
            )
        )
        doc.use {
            val contents = it.page(0)!!.contents().getOrThrow()!!
            // Streams joined with "\n" so tokens never merge across split points.
            assertArrayEquals("Good\nbye".toByteArray(Charsets.ISO_8859_1), contents.bytes)
            assertTrue(contents.warnings.isEmpty())
        }
    }

    @Test
    fun contentsStreamDirectRef() {
        val doc = openPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, pagesDict("", "3 0 R", " /Count 1")),
                TestObj(3, 0, pageDict("2 0 R", "/Contents 4 0 R")),
                TestObj(4, 0, "<< /Length 6 >>\nstream\nBT\nET\nendstream")
            )
        )
        doc.use {
            val contents = it.page(0)!!.contents().getOrThrow()!!
            assertArrayEquals("BT\nET\n".toByteArray(Charsets.ISO_8859_1), contents.bytes)
            assertTrue(contents.warnings.isEmpty())
        }
    }

    @Test
    fun pageWithoutContentsYieldsNull() {
        val doc = openPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, pagesDict("", "3 0 R", " /Count 1")),
                TestObj(3, 0, pageDict("2 0 R"))
            )
        )
        doc.use {
            assertEquals(null, it.page(0)!!.contents().getOrThrow())
        }
    }
}
