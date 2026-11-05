package com.pdfpaper.engine.edit

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T17 image acceptance: list page images with id and bounds, move/resize/
 * replace/delete them, and add JPEG/PNG images as XObjects that survive a
 * save/reopen and still render.
 */
class ImageEditTest {

    private fun streamObj(num: Int, content: String): TestObj {
        val bytes = content.toByteArray(Charsets.ISO_8859_1)
        return TestObj(num, 0, "<< /Length ${bytes.size} >>\nstream\n$content\nendstream")
    }

    /** One 612x792 page drawing a 2x1 gray image at [200,300]–[300,350]. */
    private fun docWithImage(): ByteArray {
        val imageBody = ByteArrayOutputStream().apply {
            write(
                ("<< /Type /XObject /Subtype /Image /Width 2 /Height 1 " +
                    "/ColorSpace /DeviceGray /BitsPerComponent 8 /Length 2 >>\nstream\n").toByteArray(Charsets.ISO_8859_1)
            )
            write(byteArrayOf(0x00, 0xFF.toByte()))
            write("\nendstream".toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()
        return buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                TestObj(
                    3, 0,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                        "/Resources << /XObject << /Im0 6 0 R >> >> /Contents 5 0 R >>"
                ),
                streamObj(5, "q 100 0 0 50 200 300 cm /Im0 Do Q"),
                TestObj(6, 0, imageBody)
            )
        )
    }

    private fun open(bytes: ByteArray): PdfDocument = PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun pngBytes(width: Int, height: Int, argb: IntArray): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, argb, 0, width)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            image.setRGB(x, y, 0x336699)
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpeg", out)
        return out.toByteArray()
    }

    private fun saveAndReopen(document: PdfDocument, edits: List<PageEdit>): PdfDocument {
        val out = PdfEditOps.save(document, edits).getOrThrow()
        document.close()
        val reopened = open(out)
        assertFalse(reopened.file.rebuiltFromScan)
        return reopened
    }

    @Test
    fun listsExistingImageWithBounds() {
        open(docWithImage()).use { document ->
            val images = PdfEditOps.listImages(document, 0)
            assertEquals(1, images.size)
            assertEquals("Im0", images[0].id)
            assertEquals(PdfRect(200.0, 300.0, 300.0, 350.0), images[0].bounds)
            assertEquals(2, images[0].width)
            assertEquals(1, images[0].height)
        }
    }

    @Test
    fun movesImage() {
        val document = open(docWithImage())
        saveAndReopen(document, listOf(MoveImageEdit(0, "Im0", 10.0, 20.0))).use { reopened ->
            assertEquals(PdfRect(210.0, 320.0, 310.0, 370.0), PdfEditOps.listImages(reopened, 0).single().bounds)
        }
    }

    @Test
    fun resizesImage() {
        val document = open(docWithImage())
        saveAndReopen(document, listOf(ResizeImageEdit(0, "Im0", 0.0, 0.0, 20.0, 10.0))).use { reopened ->
            assertEquals(PdfRect(0.0, 0.0, 20.0, 10.0), PdfEditOps.listImages(reopened, 0).single().bounds)
        }
    }

    @Test
    fun deletesImage() {
        val document = open(docWithImage())
        saveAndReopen(document, listOf(DeleteImageEdit(0, "Im0"))).use { reopened ->
            assertTrue(PdfEditOps.listImages(reopened, 0).isEmpty())
            // The orphaned XObject resource is dropped too, so viewers do not
            // list an image that is no longer drawn.
            val xobjects = reopened.pages().single().resources!!.get("XObject") as? PdfDict
            assertTrue(xobjects == null || xobjects.entries.keys.none { it.name == "Im0" })
        }
    }

    @Test
    fun replacesImageKeepingPlacement() {
        val document = open(docWithImage())
        val replacement = EditImages.decode(pngBytes(3, 1, intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))).getOrThrow()
        saveAndReopen(document, listOf(ReplaceImageEdit(0, "Im0", replacement))).use { reopened ->
            val image = PdfEditOps.listImages(reopened, 0).single()
            assertEquals("Im0", image.id)
            assertEquals(PdfRect(200.0, 300.0, 300.0, 350.0), image.bounds)
            assertEquals(3, image.width)
            assertEquals(1, image.height)
        }
    }

    @Test
    fun addsPngImageWithSoftMask() {
        val document = open(docWithImage())
        val png = EditImages.decode(
            pngBytes(2, 2, intArrayOf(0x80FF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt()))
        ).getOrThrow()
        val out = PdfEditOps.save(document, listOf(AddImageEdit(0, png, 50.0, 60.0, 30.0, 40.0))).getOrThrow()
        document.close()

        open(out).use { reopened ->
            val images = PdfEditOps.listImages(reopened, 0)
            assertEquals(2, images.size)
            val added = images.first { it.id.startsWith("PpI") }
            assertEquals(PdfRect(50.0, 60.0, 80.0, 100.0), added.bounds)
            assertEquals(2, added.width)
            assertEquals(2, added.height)

            val page = reopened.pages().single()
            val xobjects = page.resources!!.get("XObject") as PdfDict
            val name = xobjects.entries.keys.first { it.name.startsWith("PpI") }
            val stream = reopened.resolve(xobjects.entries.getValue(name) as PdfRef) as PdfStream
            assertEquals("Image", (stream.dict["Subtype"] as PdfName).name)
            assertNotNull(stream.dict["SMask"], "transparent PNG should carry an /SMask")

            assertTrue(page.buildDisplayList().commands.isNotEmpty())
        }
    }

    @Test
    fun addsJpegImage() {
        val document = open(docWithImage())
        val jpeg = EditImages.decode(jpegBytes(4, 3)).getOrThrow()
        val out = PdfEditOps.save(document, listOf(AddImageEdit(0, jpeg, 10.0, 10.0, 40.0, 30.0))).getOrThrow()
        document.close()

        open(out).use { reopened ->
            val added = PdfEditOps.listImages(reopened, 0).first { it.id.startsWith("PpI") }
            assertEquals(PdfRect(10.0, 10.0, 50.0, 40.0), added.bounds)
            assertEquals(4, added.width)
            assertEquals(3, added.height)

            val page = reopened.pages().single()
            val xobjects = page.resources!!.get("XObject") as PdfDict
            val name = xobjects.entries.keys.first { it.name.startsWith("PpI") }
            val stream = reopened.resolve(xobjects.entries.getValue(name) as PdfRef) as PdfStream
            assertEquals("DCTDecode", (stream.dict["Filter"] as PdfName).name)
        }
    }
}
