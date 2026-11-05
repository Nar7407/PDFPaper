package com.pdfpaper.engine.annot

import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.write.PdfPageOps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T54: pressure-varying ink appearances and their editable private payload. */
class RichInkTest {

    private fun document(): PdfDocument {
        val objects = listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << >> >>"
            )
        )
        return PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow()
    }

    private fun rewrite(spec: RichInkSpec): PdfDocument {
        val source = document()
        val bytes = PdfPageOps.edit(source, listOf(spec)).getOrThrow()
        source.close()
        return PdfDocument.open(ByteArraySource(bytes)).getOrThrow()
    }

    @Test
    fun writesPressureAppearanceAndReadsItBack() {
        val stroke = listOf(
            InkPoint(20.0, 20.0, 0.2),
            InkPoint(60.0, 80.0, 1.0),
            InkPoint(100.0, 30.0, 0.5)
        )
        val doc = rewrite(
            RichInkSpec(
                pageIndex = 0,
                strokes = listOf(stroke),
                color = PdfColor(0.9, 0.1, 0.1),
                width = 8.0,
                title = "Pen"
            )
        )
        val annot = doc.pages().first().annotations().single()
        assertEquals(AnnotationType.INK, annot.type)
        assertEquals(8.0, annot.borderWidth)
        assertEquals(PdfColor(0.9, 0.1, 0.1), annot.color)
        assertEquals("Pen", annot.title)
        assertTrue(annot.inkLists.single().size == 6)
        val stored = PdfPaperInk.read(annot)
        assertEquals(1, stored.size)
        assertEquals(InkTool.PEN, stored[0].tool)
        assertEquals(3, stored[0].points.size)
        assertEquals(0.2, stored[0].points[0].pressure, 0.0001)
        assertEquals(1.0, stored[0].points[1].pressure, 0.0001)
        assertEquals(8.0, stored[0].width, 0.0001)
        val appearance = assertNotNull(annot.appearance).decoded().getOrThrow()
        val content = String(appearance.bytes, Charsets.ISO_8859_1)
        assertTrue(content.contains(" c f"), "round joins/caps are filled curves")
        assertTrue(content.contains(" f"), "the variable-width body is filled")
        assertTrue(content.contains(" Q"), "the graphics state is restored as its own operator")
        assertFalse(content.contains("fQ"), "operators must stay separated")
        val rect = assertNotNull(annot.rect)
        assertTrue(rect.width > 0.0 && rect.height > 0.0)
        doc.close()
    }

    @Test
    fun highlighterKeepsConstantWidthOpacityAndMultiplyBlend() {
        val doc = rewrite(
            RichInkSpec(
                pageIndex = 0,
                strokes = listOf(listOf(InkPoint(40.0, 40.0, 0.1), InkPoint(180.0, 40.0, 1.0))),
                color = PdfColor(1.0, 0.9, 0.2),
                width = 18.0,
                opacity = 0.35,
                tool = InkTool.HIGHLIGHTER
            )
        )
        val annot = doc.pages().first().annotations().single()
        assertEquals("Highlighter", annot.subject)
        assertEquals("Highlighter mark", annot.contents)
        val stored = PdfPaperInk.read(annot)
        assertEquals(InkTool.HIGHLIGHTER, stored.single().tool)
        assertEquals(0.35, stored.single().opacity, 0.0001)
        val appearance = assertNotNull(annot.appearance)
        val resources = appearance.dict["Resources"] as com.pdfpaper.engine.model.PdfDict
        val gs = (resources["ExtGState"] as com.pdfpaper.engine.model.PdfDict)["GS0"]
            as com.pdfpaper.engine.model.PdfDict
        assertEquals("Multiply", (gs["BM"] as com.pdfpaper.engine.model.PdfName).name)
        assertEquals(0.35, (gs["ca"] as com.pdfpaper.engine.model.PdfReal).value, 0.0001)
        doc.close()
    }

    @Test
    fun ordinaryInkListIsEditableWithDefaultPressure() {
        val objects = listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << >> " +
                    "/Annots [5 0 R] >>"
            ),
            TestObj(
                5, 0,
                "<< /Type /Annot /Subtype /Ink /Rect [5 5 50 50] " +
                    "/InkList [[5 5 20 20 50 10]] /C [0 0 1] /BS << /W 3 >> >>"
            )
        )
        val doc = PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow()
        val stored = PdfPaperInk.read(doc.pages().first().annotations().single())
        assertEquals(1, stored.size)
        assertEquals(3, stored.single().points.size)
        assertEquals(1.0, stored.single().points[1].pressure, 0.0001)
        assertEquals(PdfColor(0.0, 0.0, 1.0), stored.single().color)
        assertEquals(3.0, stored.single().width, 0.0001)
        doc.close()
    }

    @Test
    fun ignoresMalformedPrivatePayload() {
        val objects = listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << >> " +
                    "/Annots [5 0 R] >>"
            ),
            TestObj(
                5, 0,
                "<< /Type /Annot /Subtype /Ink /Rect [5 5 50 50] /InkList [] " +
                    "/PdfPaperInk << /Version 1 /Strokes [<< /P [1 2] >>] >> >>"
            )
        )
        val doc = PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow()
        assertTrue(PdfPaperInk.read(doc.pages().first().annotations().single()).isEmpty())
        doc.close()
    }

    @Test
    fun singlePointBecomesDotAndBadSpecWritesNothing() {
        val doc = rewrite(
            RichInkSpec(0, listOf(listOf(InkPoint(50.0, 50.0, 1.0))), width = 4.0)
        )
        val annot = doc.pages().first().annotations().single()
        val content = String(assertNotNull(annot.appearance).decoded().getOrThrow().bytes, Charsets.ISO_8859_1)
        assertTrue(content.contains(" c f"))
        doc.close()

        val bad = rewrite(RichInkSpec(0, emptyList()))
        assertTrue(bad.pages().first().annotations().isEmpty())
        bad.close()
    }
}
