package com.pdfpaper.engine.sign

import com.pdfpaper.engine.annot.AnnotationType
import com.pdfpaper.engine.annot.PdfAnnotation
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.create.assertEqualsList
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfBoolean
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T26 acceptance: visual signatures from drawn strokes, typed names and
 * pictures, with captions, annotation editing, flattening and the explicit
 * "not a cryptographic signature" contract.
 */
class VisualSignatureTest {

    private fun open(vararg objects: TestObj): PdfDocument =
        PdfDocument.open(ByteArraySource(buildTestPdf(objects.toList()))).getOrThrow()

    private fun blankDocument(): PdfDocument = open(
        TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
        TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
        TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Annots [] >>")
    )

    private fun reopen(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    private fun drawnArtwork(): SignatureArtwork.Drawn = SignatureArtwork.Drawn(
        strokes = listOf(
            SignatureStroke(
                listOf(
                    SignaturePoint(0.0, 0.0, 0.4),
                    SignaturePoint(50.0, 40.0, 1.4),
                    SignaturePoint(100.0, 0.0, 0.8)
                )
            ),
            SignatureStroke(listOf(SignaturePoint(20.0, 20.0), SignaturePoint(80.0, 30.0)))
        ),
        width = 2.5
    )

    private fun annotations(document: PdfDocument): List<PdfAnnotation> =
        document.pages().flatMap { it.annotations() }

    private fun onlySignature(document: PdfDocument): PdfAnnotation =
        annotations(document).single()

    private fun contentOf(annotation: PdfAnnotation): String =
        String(assertNotNull(annotation.appearance).data, StandardCharsets.ISO_8859_1)

    private fun bboxOf(stream: PdfStream): List<Double> =
        assertNotNull(stream.dict["BBox"] as? PdfArray).items.map {
            when (it) {
                is com.pdfpaper.engine.model.PdfReal -> it.value
                is com.pdfpaper.engine.model.PdfInteger -> it.value.toDouble()
                else -> error("non-numeric /BBox")
            }
        }

    private fun assertRect(expected: PdfRect, actual: PdfRect?) {
        val box = assertNotNull(actual)
        assertEqualsList(
            listOf(expected.left, expected.bottom, expected.right, expected.top),
            listOf(box.left, box.bottom, box.right, box.top)
        )
    }

    private fun visualMarker(annotation: PdfAnnotation): PdfDict =
        assertNotNull(annotation.dict["PdfPaperVisualSignature"] as? PdfDict)

    private fun imageXObject(document: PdfDocument, annotation: PdfAnnotation): PdfStream {
        val resources = assertNotNull(assertNotNull(annotation.appearance).dict["Resources"] as? PdfDict)
        val xobjects = assertNotNull(resources["XObject"] as? PdfDict)
        val ref = assertNotNull(xobjects.entries[PdfName("Im0")] as? PdfRef)
        return assertNotNull(document.resolve(ref) as? PdfStream)
    }

    private fun alphaPng(): ByteArray {
        val image = BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(20, 20, 20)
        graphics.drawLine(2, 13, 29, 3)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", out))
        return out.toByteArray()
    }

    private fun whiteJpeg(): ByteArray {
        val image = BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, 32, 16)
        graphics.color = Color.BLACK
        graphics.drawLine(2, 13, 29, 3)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        check(ImageIO.write(image, "jpeg", out))
        return out.toByteArray()
    }

    @Test
    fun drawnSignatureUsesInkAndPressureAndCaption() {
        val placement = SignaturePlacement(0, x = 100.0, y = 300.0, width = 160.0)
        val options = VisualSignatureOptions(
            caption = SignatureCaption("Alice", "2026-10-20"),
            author = "Alice",
            id = "sig-drawn"
        )
        val result = VisualSignatures.place(
            blankDocument(), drawnArtwork(), placement, options
        ).getOrThrow()

        assertTrue(result.visualOnly)
        assertFalse(result.cryptographicSignature)
        assertEquals(VisualSignatureSource.DRAWN, result.source)
        assertEquals(VisualSignatureChange.PLACED, result.change)
        assertEquals("sig-drawn", result.signatureId)
        assertEquals(1, result.annotationIds.size)
        assertRect(PdfRect(100.0, 300.0, 260.0, assertNotNull(result.rect).top), result.rect)
        assertEquals(160.0, assertNotNull(result.rect).width, 0.001)

        val document = reopen(result.bytes)
        val signature = onlySignature(document)
        assertEquals(AnnotationType.INK, signature.type)
        assertEquals("Alice", signature.title)
        assertEquals("Visual signature (not a cryptographic signature): Alice / 2026-10-20", signature.contents)
        assertEquals("sig-drawn", (signature.dict["NM"] as? com.pdfpaper.engine.model.PdfString)?.text())
        val marker = visualMarker(signature)
        assertEquals(true, (marker["VisualOnly"] as? PdfBoolean)?.value)
        assertEquals("Drawn", (marker["Source"] as? PdfName)?.name)
        assertNotNull(signature.inkLists)
        assertEquals(2, signature.inkLists.size)
        assertEquals(3, signature.inkLists[0].size / 2)
        assertTrue(
            signature.inkLists.all { points ->
                points.chunked(2).all { point -> point[0] >= 100.0 && point[0] <= 260.0 }
            }
        )

        val content = contentOf(signature)
        assertTrue(content.contains(" w "), content)
        assertTrue(content.contains(" l S"), content)
        assertTrue(content.contains("(Alice) Tj"), content)
        assertTrue(content.contains("(2026-10-20) Tj"), content)
        // Pressure changes the per-segment stroke width.
        assertTrue(content.contains("2.25 w") || content.contains("0.375 w"), content)
        assertEquals(
            1,
            assertNotNull(assertNotNull(signature.appearance).dict["Resources"] as? PdfDict)
                .let { it["Font"] as? PdfDict }?.entries?.size
        )
        assertEquals(VisualSignatureSource.DRAWN, VisualSignatures.find(document, "sig-drawn")?.source)
    }

    @Test
    fun typedSignatureUsesStandardFontAndCaption() {
        val result = VisualSignatures.place(
            blankDocument(),
            SignatureArtwork.Typed("Dana S.", StandardFont.HELVETICA_OBLIQUE),
            SignaturePlacement(0, x = 80.0, y = 500.0, width = 150.0),
            VisualSignatureOptions(
                caption = SignatureCaption(name = "Dana S.", date = "Signed 2026-10-20"),
                id = "sig-typed"
            )
        ).getOrThrow()

        assertEquals(VisualSignatureSource.TYPED, result.source)
        val document = reopen(result.bytes)
        val signature = onlySignature(document)
        assertEquals(AnnotationType.STAMP, signature.type)
        assertEquals(150.0, assertNotNull(signature.rect).width, 0.001)
        assertTrue(signature.contents!!.contains("Dana S."))
        val content = contentOf(signature)
        assertTrue(content.contains("(Dana S.) Tj"), content)
        assertTrue(content.contains("(Signed 2026-10-20) Tj"), content)
        assertTrue(content.contains(" Tf "), content)
        val fonts = assertNotNull(
            assertNotNull(assertNotNull(signature.appearance).dict["Resources"] as? PdfDict)["Font"] as? PdfDict
        )
        assertEquals(2, fonts.entries.size)
        assertTrue(fonts.entries.values.all { it is PdfRef })
        val marker = visualMarker(signature)
        assertEquals("Typed", (marker["Source"] as? PdfName)?.name)
    }

    @Test
    fun pngSignatureKeepsItsAlphaChannel() {
        val image = EditImages.decode(alphaPng()).getOrThrow()
        val result = VisualSignatures.place(
            blankDocument(),
            SignatureArtwork.Image(image),
            SignaturePlacement(0, x = 20.0, y = 40.0, width = 100.0),
            VisualSignatureOptions(id = "sig-png")
        ).getOrThrow()

        val document = reopen(result.bytes)
        val signature = onlySignature(document)
        assertEquals(AnnotationType.STAMP, signature.type)
        val xobject = imageXObject(document, signature)
        assertEquals(PdfName("FlateDecode"), xobject.dict["Filter"])
        assertNotNull(xobject.dict["SMask"] as? PdfRef)
        assertEquals(32L, (xobject.dict["Width"] as? com.pdfpaper.engine.model.PdfInteger)?.value)
        assertEquals(16L, (xobject.dict["Height"] as? com.pdfpaper.engine.model.PdfInteger)?.value)
        assertEquals(VisualSignatureSource.IMAGE, result.source)
    }

    @Test
    fun jpegWhiteBackgroundCanBeMadeTransparent() {
        val image = EditImages.decode(whiteJpeg()).getOrThrow()
        val result = VisualSignatures.place(
            blankDocument(),
            SignatureArtwork.Image(image, transparentBackground = true),
            SignaturePlacement(0, x = 20.0, y = 40.0, width = 90.0),
            VisualSignatureOptions(id = "sig-jpeg"),
            decoder = EditImages.defaultDecoder()
        ).getOrThrow()

        val document = reopen(result.bytes)
        val signature = onlySignature(document)
        val xobject = imageXObject(document, signature)
        assertEquals(PdfName("FlateDecode"), xobject.dict["Filter"])
        assertNotNull(xobject.dict["SMask"] as? PdfRef)
    }

    @Test
    fun signatureCanBeMovedResizedAndRotated() {
        val placed = VisualSignatures.place(
            blankDocument(),
            drawnArtwork(),
            SignaturePlacement(0, x = 100.0, y = 100.0, width = 120.0),
            VisualSignatureOptions(id = "sig-edit")
        ).getOrThrow()
        val original = assertNotNull(placed.rect)
        val firstRef = VisualSignatureRef(placed.pageIndex, placed.annotationIds.single())

        val moved = VisualSignatures.move(reopen(placed.bytes), firstRef, 30.0, -20.0).getOrThrow()
        assertEquals(VisualSignatureChange.MOVED, moved.change)
        val movedInfo = assertNotNull(VisualSignatures.find(reopen(moved.bytes), "sig-edit"))
        assertRect(
            PdfRect(original.left + 30.0, original.bottom - 20.0, original.right + 30.0, original.top - 20.0),
            movedInfo.rect
        )

        val resized = VisualSignatures.resize(
            reopen(moved.bytes), VisualSignatureRef(movedInfo), 200.0, 300.0, 90.0, 70.0
        ).getOrThrow()
        assertEquals(VisualSignatureChange.RESIZED, resized.change)
        val resizedInfo = assertNotNull(VisualSignatures.find(reopen(resized.bytes), "sig-edit"))
        assertRect(PdfRect(200.0, 300.0, 290.0, 370.0), resizedInfo.rect)
        val resizedBBox = bboxOf(
            assertNotNull(onlySignature(reopen(resized.bytes)).appearance)
        )

        val rotated = VisualSignatures.rotate(
            reopen(resized.bytes), VisualSignatureRef(resizedInfo), 90
        ).getOrThrow()
        assertEquals(VisualSignatureChange.ROTATED, rotated.change)
        val rotatedDocument = reopen(rotated.bytes)
        val rotatedInfo = assertNotNull(VisualSignatures.find(rotatedDocument, "sig-edit"))
        assertRect(PdfRect(210.0, 290.0, 280.0, 380.0), rotatedInfo.rect)
        val signature = onlySignature(rotatedDocument)
        val appearance = assertNotNull(signature.appearance)
        assertEqualsList(listOf(0.0, 0.0, resizedBBox[3], resizedBBox[2]), bboxOf(appearance))
        assertTrue(contentOf(signature).contains("0 1 -1 0"), contentOf(signature))
        assertNotNull(signature.inkLists)

        val deleted = VisualSignatures.delete(
            reopen(rotated.bytes), VisualSignatureRef(rotatedInfo)
        ).getOrThrow()
        assertTrue(deleted.deleted)
        assertEquals(VisualSignatureChange.DELETED, deleted.change)
        assertTrue(VisualSignatures.list(reopen(deleted.bytes)).isEmpty())
        assertTrue(annotations(reopen(deleted.bytes)).isEmpty())
    }

    @Test
    fun flattenPaintsTheAppearanceIntoPageContent() {
        val placed = VisualSignatures.place(
            blankDocument(),
            SignatureArtwork.Typed("Flatten Me", StandardFont.TIMES_ITALIC),
            SignaturePlacement(0, x = 50.0, y = 600.0, width = 140.0),
            VisualSignatureOptions(id = "sig-flat")
        ).getOrThrow()
        val info = assertNotNull(VisualSignatures.find(reopen(placed.bytes), "sig-flat"))

        val flat = VisualSignatures.flatten(
            reopen(placed.bytes), VisualSignatureRef(info)
        ).getOrThrow()
        assertTrue(flat.flattened)
        assertEquals(VisualSignatureChange.FLATTENED, flat.change)
        assertEquals(emptyList(), flat.annotationIds)
        assertNull(flat.rect)

        val document = reopen(flat.bytes)
        assertTrue(annotations(document).isEmpty())
        assertTrue(VisualSignatures.list(document).isEmpty())
        val page = document.pages().single()
        val content = String(
            assertNotNull(page.contents().getOrThrow()).bytes,
            StandardCharsets.ISO_8859_1
        )
        assertTrue(content.contains("/PpS1 Do"), content)
        val resources = assertNotNull(page.resources)
        val xobjects = assertNotNull(resources["XObject"] as? PdfDict)
        assertNotNull(xobjects.entries[PdfName("PpS1")] as? PdfRef)
    }

    @Test
    fun flattenDuringPlacementLeavesNoAnnotation() {
        val result = VisualSignatures.place(
            blankDocument(),
            SignatureArtwork.Typed("Already Flat", StandardFont.HELVETICA),
            SignaturePlacement(0, x = 10.0, y = 10.0, width = 120.0),
            VisualSignatureOptions(flatten = true, id = "sig-flat-now")
        ).getOrThrow()
        assertTrue(result.flattened)
        assertTrue(result.visualOnly)
        assertTrue(result.annotationIds.isEmpty())
        assertTrue(annotations(reopen(result.bytes)).isEmpty())
    }

    @Test
    fun badInputReturnsFailureAndWritesNothing() {
        assertTrue(
            VisualSignatures.place(
                blankDocument(),
                SignatureArtwork.Drawn(emptyList()),
                SignaturePlacement(0, 0.0, 0.0, 100.0)
            ).isFailure
        )
        assertTrue(
            VisualSignatures.place(
                blankDocument(),
                SignatureArtwork.Typed("   "),
                SignaturePlacement(0, 0.0, 0.0, 100.0)
            ).isFailure
        )
        assertTrue(
            VisualSignatures.place(
                blankDocument(),
                drawnArtwork(),
                SignaturePlacement(0, 0.0, 0.0, 0.0)
            ).isFailure
        )
        assertTrue(
            VisualSignatures.place(
                blankDocument(),
                drawnArtwork(),
                SignaturePlacement(0, 0.0, 0.0, 100.0, rotation = 45)
            ).isFailure
        )
    }
}
