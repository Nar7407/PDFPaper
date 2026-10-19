package com.pdfpaper.engine.annot

import com.pdfpaper.engine.create.asDouble
import com.pdfpaper.engine.create.assertEqualsList
import com.pdfpaper.engine.create.imageBytes
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.stamp.CustomStamp
import com.pdfpaper.engine.stamp.StampCatalogue
import com.pdfpaper.engine.stamp.StampJson
import com.pdfpaper.engine.stamp.StampLibrary
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.write.PdfSerializer
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T23 acceptance: /Stamp annotations (§12.5.6.7) carry generated appearance
 * streams — border and colours, dynamic by/at text, pictures, quarter turns
 * and opacity — that round-trip through a saved document and show up as
 * comments.
 */
class StampTest {

    private fun open(vararg objects: TestObj): PdfDocument =
        PdfDocument.open(ByteArraySource(buildTestPdf(objects.toList()))).getOrThrow()

    /** A one-page blank document. */
    private fun blankDocument(): PdfDocument = open(
        TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
        TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
        TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Annots [] >>")
    )

    private fun reopen(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun place(vararg specs: AnnotationSpec): PdfDocument =
        reopen(PdfComments.edit(blankDocument(), specs.toList()).getOrThrow())

    private fun stampsOf(document: PdfDocument): List<PdfAnnotation> =
        document.pages().flatMap { page -> page.annotations() }
            .filter { it.type == AnnotationType.STAMP }

    private fun contentOf(annot: PdfAnnotation): String =
        String(assertNotNull(annot.appearance).data, StandardCharsets.ISO_8859_1)

    private fun rgb(color: PdfColor): String =
        "${PdfSerializer.formatReal(color.red)} " +
            "${PdfSerializer.formatReal(color.green)} ${PdfSerializer.formatReal(color.blue)}"

    private fun assertRect(expected: PdfRect, actual: PdfRect?) {
        val box = assertNotNull(actual)
        assertEqualsList(
            listOf(expected.left, expected.bottom, expected.right, expected.top),
            listOf(box.left, box.bottom, box.right, box.top)
        )
    }

    private fun bboxOf(stream: PdfStream): List<Double> =
        assertNotNull(stream.dict["BBox"] as? PdfArray).items.map { it.asDouble() }

    private fun resourcesOf(annot: PdfAnnotation): PdfDict =
        assertNotNull(assertNotNull(annot.appearance).dict["Resources"] as? PdfDict)

    private fun imageStreamOf(document: PdfDocument, annot: PdfAnnotation): PdfStream {
        val xobjects = assertNotNull(resourcesOf(annot)["XObject"] as? PdfDict)
        val ref = assertNotNull(xobjects.entries[PdfName("Im0")] as? PdfRef)
        return assertNotNull(document.resolve(ref) as? PdfStream)
    }

    @Test
    fun paintsBannerStampWithBorderAndColours() {
        val design = assertNotNull(StampLibrary.find("paid"))
        val document = place(StampSpec(0, design, x = 100.0, y = 500.0, size = 144.0))
        val stamp = stampsOf(document).single()

        assertRect(PdfRect(100.0, 500.0, 244.0, 560.0), stamp.rect)
        assertEquals(PdfAnnotation.PRINT, stamp.flags and PdfAnnotation.PRINT)
        assertEquals("Paid", stamp.contents)
        assertEquals("Standard business", stamp.subject)
        assertNull(stamp.title)
        assertNull(stamp.dict["Name"])
        assertTrue(assertNotNull(stamp.creationDate).isNotBlank())

        val content = contentOf(stamp)
        assertTrue(content.startsWith("q "), content)
        assertTrue(content.endsWith(" Q"), content)
        assertTrue(content.contains("(PAID) Tj"), content)
        assertTrue(content.contains("${rgb(design.textColor)} rg"), content)
        assertTrue(content.contains("${rgb(design.borderColor)} RG"), content)
        assertTrue(content.contains("${rgb(assertNotNull(design.fillColor))} rg"), content)
        assertTrue(content.contains(" re f"), content)
        assertTrue(content.contains(" re S Q"), content)

        val appearance = assertNotNull(stamp.appearance)
        assertEqualsList(listOf(0.0, 0.0, 144.0, 60.0), bboxOf(appearance))
        val resources = resourcesOf(stamp)
        assertEquals(1, assertNotNull(resources["Font"] as? PdfDict).entries.size)
        assertNull(resources["XObject"])
        assertNull(resources["ExtGState"])
    }

    @Test
    fun dynamicStampAddsAuthorAndDate() {
        val design = assertNotNull(StampLibrary.find("approved"))
        val moment = LocalDateTime.parse("2026-10-19T12:30").toInstant(ZoneOffset.UTC).toEpochMilli()
        val document = place(
            StampSpec(0, design, x = 100.0, y = 600.0, size = 150.0, author = "Alice", timestamp = moment)
        )
        val stamp = stampsOf(document).single()

        val content = contentOf(stamp)
        assertTrue(content.contains("(APPROVED) Tj"), content)
        assertTrue(content.contains("(By Alice) Tj"), content)
        assertTrue(content.contains("(2026-10-19 12:30) Tj"), content)
        assertTrue(content.contains(" l S Q"), content)

        assertEquals("Alice", stamp.title)
        assertEquals("Dynamic", stamp.subject)
        assertEquals(PdfName("Approved"), stamp.dict["Name"])
        assertEquals(2, assertNotNull(resourcesOf(stamp)["Font"] as? PdfDict).entries.size)
        val contents = assertNotNull(stamp.contents)
        assertTrue(contents.contains("Approved"), contents)
        assertTrue(contents.contains("By Alice"), contents)
        assertTrue(contents.contains("2026-10-19 12:30"), contents)
    }

    @Test
    fun placesImageStampWithPicture() {
        val bytes = imageBytes("png", 3, 2, 0xFFFF0000.toInt())
        val data = EditImages.decode(bytes).getOrThrow()
        val catalogue = StampCatalogue(listOf(CustomStamp.image("check", "Check", bytes).getOrThrow()))
        val design = assertNotNull(catalogue.find("check"))
        val document = place(StampSpec(0, design, x = 50.0, y = 50.0, size = 120.0, image = data))
        val stamp = stampsOf(document).single()

        assertRect(PdfRect(50.0, 50.0, 170.0, 130.0), stamp.rect)
        assertTrue(contentOf(stamp).contains("120 0 0 80 0 0 cm /Im0 Do"), contentOf(stamp))

        val image = imageStreamOf(document, stamp)
        assertEquals(PdfName("Image"), image.dict["Subtype"])
        assertEquals(3L, assertNotNull(image.dict["Width"] as? PdfInteger).value)
        assertEquals(2L, assertNotNull(image.dict["Height"] as? PdfInteger).value)
        assertEquals(PdfName("DeviceRGB"), image.dict["ColorSpace"])
        assertEquals(PdfName("FlateDecode"), image.dict["Filter"])
        assertNull(resourcesOf(stamp)["Font"])
        assertEquals("Custom", stamp.subject)
    }

    @Test
    fun jpegStampKeepsOriginalBytes() {
        val bytes = imageBytes("jpeg", 4, 2)
        val data = EditImages.decode(bytes).getOrThrow()
        val catalogue = StampCatalogue(listOf(CustomStamp.image("photo", "Photo", bytes).getOrThrow()))
        val document = place(
            StampSpec(0, assertNotNull(catalogue.find("photo")), x = 10.0, y = 10.0, size = 80.0, image = data)
        )
        val image = imageStreamOf(document, stampsOf(document).single())

        assertEquals(PdfName("DCTDecode"), image.dict["Filter"])
        assertEquals(PdfName("DeviceRGB"), image.dict["ColorSpace"])
        assertTrue(image.data.contentEquals(bytes), "the JPEG bytes must be embedded verbatim")
    }

    @Test
    fun rotationTurnsThePlacedBox() {
        val design = assertNotNull(StampLibrary.find("paid"))

        // A quarter turn counter-clockwise: a 120x50 box becomes 50x120.
        val turned = stampsOf(place(StampSpec(0, design, x = 100.0, y = 100.0, size = 120.0, rotation = 90))).single()
        assertRect(PdfRect(100.0, 100.0, 150.0, 220.0), turned.rect)
        assertTrue(contentOf(turned).contains("0 1 -1 0 50 0 cm"), contentOf(turned))
        assertEqualsList(listOf(0.0, 0.0, 50.0, 120.0), bboxOf(assertNotNull(turned.appearance)))

        val upsideDown = stampsOf(place(StampSpec(0, design, x = 100.0, y = 100.0, size = 120.0, rotation = 180))).single()
        assertRect(PdfRect(100.0, 100.0, 220.0, 150.0), upsideDown.rect)
        assertTrue(contentOf(upsideDown).contains("-1 0 0 -1 120 50 cm"), contentOf(upsideDown))

        // A rotation between quarter turns snaps to the nearest one.
        val snapped = stampsOf(place(StampSpec(0, design, x = 0.0, y = 0.0, size = 120.0, rotation = 100))).single()
        assertTrue(contentOf(snapped).contains("0 1 -1 0 50 0 cm"), contentOf(snapped))
    }

    @Test
    fun opacityBakesIntoTheAppearanceAndDictionary() {
        val design = assertNotNull(StampLibrary.find("paid"))
        val document = place(StampSpec(0, design, x = 0.0, y = 0.0, size = 144.0, opacity = 0.5))
        val stamp = stampsOf(document).single()

        val content = contentOf(stamp)
        assertTrue(content.startsWith("q /GS0 gs "), content)
        val gs0 = assertNotNull(assertNotNull(resourcesOf(stamp)["ExtGState"] as? PdfDict)["GS0"] as? PdfDict)
        assertEquals(0.5, assertNotNull(gs0["ca"] as? PdfReal).value)
        assertEquals(0.5, assertNotNull(gs0["CA"] as? PdfReal).value)
        assertEquals(0.5, assertNotNull(stamp.dict["CA"] as? PdfReal).value)
    }

    @Test
    fun signHereStampPaintsAnArrow() {
        val design = assertNotNull(StampLibrary.find("sign-here"))
        val document = place(StampSpec(0, design, x = 20.0, y = 700.0, size = 160.0))
        val stamp = stampsOf(document).single()

        assertRect(PdfRect(20.0, 700.0, 180.0, 750.0), stamp.rect)
        val content = contentOf(stamp)
        assertTrue(content.contains("(SIGN HERE) Tj"), content)
        assertTrue(content.contains(" l S Q"), content)
        assertTrue(content.contains(" h f"), content)
    }

    @Test
    fun customStampsFromAJsonListArePlaced() {
        val text = CustomStamp.text("ship-it", "Ship it", "SHIP IT", borderColor = PdfColor(0.0, 0.4, 0.0))
            .getOrThrow()
        val image = CustomStamp.image("logo", "Our logo", imageBytes("png", 4, 2)).getOrThrow()
        val list = StampJson.decode(StampJson.encode(listOf(text, image))).getOrThrow()
        val catalogue = StampCatalogue(list)

        val document = place(
            StampSpec(0, assertNotNull(catalogue.find("ship-it")), x = 10.0, y = 10.0, size = 130.0),
            StampSpec(
                0, assertNotNull(catalogue.find("logo")), x = 200.0, y = 10.0, size = 80.0,
                image = EditImages.decode(assertNotNull(image.image)).getOrThrow()
            )
        )
        val stamps = stampsOf(document)
        assertEquals(2, stamps.size)
        assertTrue(contentOf(stamps[0]).contains("(SHIP IT) Tj"), contentOf(stamps[0]))
        assertTrue(contentOf(stamps[0]).contains("${rgb(PdfColor(0.0, 0.4, 0.0))} RG"), contentOf(stamps[0]))
        assertTrue(contentOf(stamps[1]).contains("/Im0 Do"), contentOf(stamps[1]))
        assertEquals("Ship it", stamps[0].contents)
        assertEquals("Custom", stamps[0].subject)
    }

    @Test
    fun stampsShowUpAsComments() {
        val design = assertNotNull(StampLibrary.find("confidential"))
        val document = place(StampSpec(0, design, x = 30.0, y = 700.0, size = 120.0))
        val comment = document.comments().single()

        assertEquals(AnnotationType.STAMP, comment.type)
        assertEquals("Confidential", comment.contents)
        assertNotNull(comment.rect)
    }

    @Test
    fun badPlacementsWriteNoAnnotation() {
        val paid = assertNotNull(StampLibrary.find("paid"))
        val imageDesign = assertNotNull(
            StampCatalogue(listOf(CustomStamp.image("logo", "Logo", imageBytes("png", 2, 2)).getOrThrow()))
                .find("logo")
        )
        val document = place(
            StampSpec(0, paid, x = 10.0, y = 10.0, size = 0.0),
            StampSpec(0, paid, x = 10.0, y = 10.0, size = -5.0),
            StampSpec(0, imageDesign, x = 10.0, y = 10.0, size = 100.0),
            StampSpec(7, paid, x = 10.0, y = 10.0, size = 100.0)
        )
        assertTrue(stampsOf(document).isEmpty())
    }
}
