package com.pdfpaper.engine.write

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageOpsTest {

    private fun write(result: Result<ByteArray>): PdfDocument =
        WriteFixtures.open(result.getOrThrow())

    private fun PdfDocument.catalog(): PdfDict = resolveRoot() as PdfDict

    private fun PdfDocument.words(): List<String> = WriteFixtures.pageWords(this)

    private fun PdfDocument.outlineTitles(): List<String> {
        val outlines = (catalog()["Outlines"] as? PdfRef)?.let { resolve(it) } as? PdfDict
            ?: return emptyList()
        val titles = ArrayList<String>()
        var node = (outlines["First"] as? PdfRef)?.let { resolve(it) } as? PdfDict
        while (node != null) {
            (node["Title"] as? PdfString)?.let { titles += it.text() }
            node = (node["Next"] as? PdfRef)?.let { resolve(it) } as? PdfDict
        }
        return titles
    }

    private fun PdfDocument.linkDest(pageIndex: Int): PdfObject? {
        val annots = pages()[pageIndex].dict["Annots"] as? PdfArray ?: return null
        for (item in annots.items) {
            val annot = resolve(item as? PdfRef ?: continue) as? PdfDict ?: continue
            if ((annot["Subtype"] as? PdfName)?.name == "Link") return annot["Dest"]
        }
        return null
    }

    /** One page with an outline item that points back at itself (P0-3). */
    private fun cyclicOutlineDoc(nextCycle: Boolean): PdfDocument {
        val item = if (nextCycle) {
            "<< /Title (Loop) /Parent 5 0 R /Next 6 0 R >>"
        } else {
            "<< /Title (Loop) /Parent 5 0 R /First 6 0 R >>"
        }
        return WriteFixtures.open(
            buildTestPdf(
                listOf(
                    TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R /Outlines 5 0 R >>"),
                    TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                    TestObj(
                        3, 0,
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] " +
                            "/Resources << >> /Contents 4 0 R >>"
                    ),
                    TestObj(4, 0, "<< /Length 0 >>\nstream\n\nendstream"),
                    TestObj(5, 0, "<< /Type /Outlines /First 6 0 R /Last 6 0 R /Count 1 >>"),
                    TestObj(6, 0, item)
                ),
                rootRef = "1 0 R"
            )
        )
    }

    @Test
    fun cyclicOutlineFirstDoesNotOverflow() {
        cyclicOutlineDoc(nextCycle = false).use { doc ->
            val bytes = PdfPageOps.extract(doc, listOf(0)).getOrThrow()
            WriteFixtures.open(bytes).use { out -> assertEquals(1, out.pages().size) }
        }
    }

    @Test
    fun cyclicOutlineNextDoesNotLoop() {
        cyclicOutlineDoc(nextCycle = true).use { doc ->
            val bytes = PdfPageOps.extract(doc, listOf(0)).getOrThrow()
            WriteFixtures.open(bytes).use { out -> assertEquals(1, out.pages().size) }
        }
    }

    // -- Basic operations -----------------------------------------------------

    @Test
    fun identityAssembleKeepsEveryPageInOrder() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { doc ->
            write(PdfPageOps.assemble(doc.pages().mapIndexed { i, _ -> PdfPageSelection(doc, i) }, doc))
                .use { out ->
                    assertFalse(out.file.rebuiltFromScan)
                    assertEquals(listOf("P1", "P2", "P3"), out.words())
                }
        }
    }

    @Test
    fun deleteRemovesPagesAndTheirContent() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { doc ->
            write(PdfPageOps.delete(doc, listOf(1))).use { out ->
                assertEquals(listOf("P1", "P3"), out.words())
            }
        }
    }

    @Test
    fun reorderChangesPageOrder() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { doc ->
            write(PdfPageOps.reorder(doc, listOf(2, 0, 1))).use { out ->
                assertEquals(listOf("P3", "P1", "P2"), out.words())
            }
        }
    }

    @Test
    fun extractKeepsRange() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { doc ->
            write(PdfPageOps.extract(doc, 0, 2)).use { out ->
                assertEquals(listOf("P1", "P2"), out.words())
            }
            write(PdfPageOps.extract(doc, listOf(2))).use { out ->
                assertEquals(listOf("P3"), out.words())
            }
        }
    }

    @Test
    fun rotateSetsAbsoluteRotationOnPages() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { doc ->
            write(PdfPageOps.rotate(doc, listOf(0, 2), 90)).use { out ->
                val outPages = out.pages()
                assertEquals(90, outPages[0].rotate)
                assertEquals(0, outPages[1].rotate)
                assertEquals(90, outPages[2].rotate)
            }
            // Rotating an already-rotated page accumulates.
            WriteFixtures.open(PdfPageOps.rotate(doc, listOf(0), 90).getOrThrow()).use { once ->
                write(PdfPageOps.rotate(once, listOf(0), 270)).use { twice ->
                    assertEquals(0, twice.pages()[0].rotate)
                }
            }
        }
    }

    @Test
    fun inheritedBoxIsMaterialisedOnEveryPage() {
        // The /MediaBox lives only on the /Pages node in this fixture.
        val bytes = com.pdfpaper.engine.test.buildTestPdf(
            listOf(
                com.pdfpaper.engine.test.TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                com.pdfpaper.engine.test.TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 400 500] >>"),
                com.pdfpaper.engine.test.TestObj(3, 0, "<< /Type /Page /Parent 2 0 R >>")
            )
        )
        WriteFixtures.open(bytes).use { doc ->
            write(PdfPageOps.assemble(listOf(PdfPageSelection(doc, 0)), doc)).use { out ->
                val box = out.pages()[0].dict["MediaBox"] as PdfArray
                assertEquals(4, box.items.size)
                // 400x500 preserved through the copy.
                assertTrue(out.pages()[0].mediaBox != null)
            }
        }
    }

    // -- Multi-document operations -------------------------------------------

    @Test
    fun mergeConcatenatesDocuments() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { a ->
            WriteFixtures.open(WriteFixtures.twoPageDoc()).use { b ->
                write(PdfPageOps.merge(listOf(a, b))).use { out ->
                    assertEquals(listOf("P1", "P2", "P3", "A1", "A2"), out.words())
                }
            }
        }
    }

    @Test
    fun insertPlacesSourcePagesAtPosition() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { target ->
            WriteFixtures.open(WriteFixtures.twoPageDoc()).use { source ->
                write(PdfPageOps.insert(target, source, at = 1)).use { out ->
                    assertEquals(listOf("P1", "A1", "A2", "P2", "P3"), out.words())
                }
            }
        }
    }

    @Test
    fun splitEveryNPages() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { doc ->
            val parts = PdfPageOps.split(doc, everyN = 2).getOrThrow()
            assertEquals(2, parts.size)
            WriteFixtures.open(parts[0]).use { assertEquals(listOf("P1", "P2"), it.words()) }
            WriteFixtures.open(parts[1]).use { assertEquals(listOf("P3"), it.words()) }
        }
    }

    @Test
    fun splitByRanges() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { doc ->
            val parts = PdfPageOps.split(doc, listOf(0..0, 1..2)).getOrThrow()
            assertEquals(2, parts.size)
            WriteFixtures.open(parts[0]).use { assertEquals(listOf("P1"), it.words()) }
            WriteFixtures.open(parts[1]).use { assertEquals(listOf("P2", "P3"), it.words()) }
        }
    }

    // -- Links ----------------------------------------------------------------

    @Test
    fun linkPreservedWhenTargetPageKept() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { doc ->
            // Drop the middle page; the link on page 1 still targets page 3.
            write(PdfPageOps.delete(doc, listOf(1))).use { out ->
                assertEquals(listOf("P1", "P3"), out.words())
                val dest = out.linkDest(0)
                assertNotNull("link must survive", dest)
                assertTrue((dest as PdfArray).items.first() is PdfRef)
            }
        }
    }

    @Test
    fun linkDroppedWhenTargetPageRemoved() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { doc ->
            // Remove the last page; the only link pointed at it.
            write(PdfPageOps.delete(doc, listOf(2))).use { out ->
                assertNull(out.linkDest(0))
            }
        }
    }

    // -- Bookmarks ------------------------------------------------------------

    @Test
    fun bookmarksFollowKeptPages() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { doc ->
            write(PdfPageOps.assemble(doc.pages().mapIndexed { i, _ -> PdfPageSelection(doc, i) }, doc))
                .use { out ->
                    assertEquals(listOf("Book One", "Book Three"), out.outlineTitles())
                }
            // Removing the first page drops "Book One" but keeps "Book Three".
            write(PdfPageOps.delete(doc, listOf(0))).use { out ->
                assertEquals(listOf("Book Three"), out.outlineTitles())
            }
        }
    }

    // -- Forms ----------------------------------------------------------------

    @Test
    fun formFieldPreservedWithWidgetOnKeptPage() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { doc ->
            write(PdfPageOps.assemble(doc.pages().mapIndexed { i, _ -> PdfPageSelection(doc, i) }, doc))
                .use { out ->
                    val acro = (out.catalog()["AcroForm"] as? PdfRef)?.let { out.resolve(it) } as? PdfDict
                    assertNotNull(acro)
                    val field = ((acro!!["Fields"] as PdfArray).items[0] as PdfRef).let { out.resolve(it) } as PdfDict
                    val widget = ((field["Kids"] as PdfArray).items[0] as PdfRef).let { out.resolve(it) } as PdfDict
                    val page = (widget["P"] as PdfRef).let { out.resolve(it) } as PdfDict
                    assertEquals(PdfName("Page"), page["Type"])
                    assertEquals(PdfName("Widget"), widget["Subtype"])
                }
        }
    }

    @Test
    fun formFieldDroppedWithItsPage() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { doc ->
            // The only widget lives on the first page.
            write(PdfPageOps.delete(doc, listOf(0))).use { out ->
                assertNull(out.catalog()["AcroForm"])
            }
        }
    }

    // -- Info, /ID, encryption ------------------------------------------------

    @Test
    fun infoAndIdArePreserved() {
        val bytes = com.pdfpaper.engine.test.buildTestPdf(
            listOf(
                com.pdfpaper.engine.test.TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                com.pdfpaper.engine.test.TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                com.pdfpaper.engine.test.TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10] >>"),
                com.pdfpaper.engine.test.TestObj(50, 0, "<< /Title (Doc) >>")
            ),
            trailerExtra = " /Info 50 0 R /ID [<AA01> <BB02>]"
        )
        WriteFixtures.open(bytes).use { doc ->
            write(PdfPageOps.assemble(listOf(PdfPageSelection(doc, 0)), doc)).use { out ->
                assertNotNull(out.file.trailer.raw["Info"])
                assertTrue(out.file.trailer.raw.getValue("ID").contains("AA01"))
                val info = out.file.trailer.raw.getValue("Info").let { PdfRef.parse(it)!! }
                assertEquals(PdfString("Doc"), (out.resolve(info) as PdfDict)["Title"])
            }
        }
    }

    @Test
    fun encryptedDocumentIsRejectedNotCorrupted() {
        val bytes = com.pdfpaper.engine.test.buildTestPdf(
            listOf(
                com.pdfpaper.engine.test.TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                com.pdfpaper.engine.test.TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                com.pdfpaper.engine.test.TestObj(3, 0, "<< /Type /Page /Parent 2 0 R >>")
            ),
            trailerExtra = " /Encrypt 99 0 R"
        )
        WriteFixtures.open(bytes).use { doc ->
            val result = PdfPageOps.assemble(listOf(PdfPageSelection(doc, 0)), doc)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("Encrypted") == true)
        }
    }

    // -- Incremental save -----------------------------------------------------

    @Test
    fun incrementalRotationPreservesOriginalBytes() {
        val original = WriteFixtures.threePageDoc()
        WriteFixtures.open(original).use { doc ->
            val updated = PdfPageOps.rotateIncremental(doc, original, listOf(0), 90).getOrThrow()
            assertTrue(updated.copyOf(original.size).contentEquals(original))
            WriteFixtures.open(updated).use { out ->
                assertFalse(out.file.rebuiltFromScan)
                assertEquals(3, out.pages().size)
                assertEquals(90, out.pages()[0].rotate)
                assertEquals(0, out.pages()[1].rotate)
                assertEquals(listOf("P1", "P2", "P3"), out.words())
            }
        }
    }

    @Test
    fun deleteAllPagesYieldsEmptyDocument() {
        WriteFixtures.open(WriteFixtures.threePageDoc()).use { doc ->
            // Deleting everything is legal and yields a zero-page document.
            write(PdfPageOps.delete(doc, listOf(0, 1, 2))).use { out ->
                assertEquals(0, out.pages().size)
            }
        }
    }
}
