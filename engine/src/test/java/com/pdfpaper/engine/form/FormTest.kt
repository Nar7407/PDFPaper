package com.pdfpaper.engine.form

import com.pdfpaper.engine.annot.HighlightSpec
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.write.PdfPageOps
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T12 acceptance: AcroForm fields (§12.7) are read into a typed model, filled,
 * and saved with fresh appearance streams (§12.5.5) that other viewers show.
 */
class FormTest {

    private fun formObj(num: Int, content: String): TestObj {
        val bytes = content.toByteArray(StandardCharsets.ISO_8859_1)
        return TestObj(
            num, 0,
            "<< /Type /XObject /Subtype /Form /BBox [0 0 20 20] /Resources << >> " +
                "/Length ${bytes.size} >>\nstream\n$content\nendstream"
        )
    }

    /** Text, checkbox, two-widget radio and choice fields, all on page 0. */
    private fun formDocument(): PdfDocument {
        val objects = listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R /AcroForm 5 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> " +
                    "/Annots [6 0 R 7 0 R 9 0 R 10 0 R 15 0 R] >>"
            ),
            TestObj(
                5, 0,
                "<< /Fields [6 0 R 7 0 R 8 0 R 15 0 R] /DA (/Helv 0 Tf 0 g) " +
                    "/DR << /Font << /Helv 20 0 R >> >> >>"
            ),
            TestObj(
                6, 0,
                "<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /Rect [50 700 250 720] " +
                    "/F 4 /DA (/Helv 12 Tf 0 g) /V (before) /P 3 0 R >>"
            ),
            TestObj(
                7, 0,
                "<< /Type /Annot /Subtype /Widget /FT /Btn /T (agree) /Rect [50 650 70 670] " +
                    "/F 4 /V /Off /AS /Off /AP << /N << /Yes 11 0 R /Off 12 0 R >> >> /P 3 0 R >>"
            ),
            TestObj(8, 0, "<< /FT /Btn /Ff 32768 /T (color) /Kids [9 0 R 10 0 R] /Opt [(r) (b)] >>"),
            TestObj(
                9, 0,
                "<< /Type /Annot /Subtype /Widget /Parent 8 0 R /Rect [50 600 70 620] /F 4 " +
                    "/AS /Off /AP << /N << /r 13 0 R /Off 12 0 R >> >> /P 3 0 R >>"
            ),
            TestObj(
                10, 0,
                "<< /Type /Annot /Subtype /Widget /Parent 8 0 R /Rect [80 600 100 620] /F 4 " +
                    "/AS /Off /AP << /N << /b 14 0 R /Off 12 0 R >> >> /P 3 0 R >>"
            ),
            formObj(11, "0 0 20 20 re f"),
            formObj(12, "q Q"),
            formObj(13, "0 0 20 20 re f"),
            formObj(14, "0 0 20 20 re f"),
            TestObj(
                15, 0,
                "<< /Type /Annot /Subtype /Widget /FT /Ch /T (city) /Rect [50 550 250 570] /F 4 " +
                    "/Opt [[(ny) (New York)] [(ca) (Canada)]] /V (ny) /P 3 0 R >>"
            ),
            TestObj(
                20, 0,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
            )
        )
        return PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow()
    }

    private fun cyclicFormDocument(): PdfDocument {
        val objects = listOf(
            TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R /AcroForm 5 0 R >>"),
            TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>"),
            TestObj(5, 0, "<< /Fields [6 0 R] /DA (/Helv 0 Tf 0 g) >>"),
            TestObj(6, 0, "<< /T (a) /Kids [7 0 R] >>"),
            TestObj(7, 0, "<< /T (b) /Kids [6 0 R] >>")
        )
        return PdfDocument.open(ByteArraySource(buildTestPdf(objects))).getOrThrow()
    }

    private fun reopen(bytes: ByteArray): PdfDocument =
        PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

    @Test
    fun readsTextCheckboxRadioAndChoiceFields() {
        val doc = formDocument()
        val fields = doc.formFields().associateBy { it.name }
        assertEquals(4, fields.size)

        val name = fields.getValue("name")
        assertEquals(FieldKind.TEXT, name.kind)
        assertEquals("before", name.value)
        assertEquals(1, name.widgets.size)
        assertEquals(PdfRect(50.0, 700.0, 250.0, 720.0), name.widgets[0].rect)
        assertEquals(0, name.widgets[0].pageIndex)
        assertNull(name.widgets[0].onState)

        val agree = fields.getValue("agree")
        assertEquals(FieldKind.CHECKBOX, agree.kind)
        assertEquals(listOf("Yes"), agree.onStates)
        assertTrue(!agree.isChecked)

        val color = fields.getValue("color")
        assertEquals(FieldKind.RADIO, color.kind)
        assertEquals(listOf("r", "b"), color.onStates)
        assertEquals(2, color.widgets.size)

        val city = fields.getValue("city")
        assertEquals(FieldKind.CHOICE, city.kind)
        assertEquals(listOf("ny", "ca"), city.options.map { it.exportValue })
        assertEquals(listOf("New York", "Canada"), city.options.map { it.displayValue })
        assertEquals("ny", city.value)
        doc.close()
    }

    @Test
    fun noAcroFormYieldsNoFields() {
        val doc = PdfDocument.open(
            ByteArraySource(
                buildTestPdf(
                    listOf(
                        TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                        TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                        TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>")
                    )
                )
            )
        ).getOrThrow()
        assertTrue(doc.formFields().isEmpty())
        doc.close()
    }

    @Test
    fun fillsEveryFieldKindAndWritesAppearances() {
        val doc = formDocument()
        val bytes = PdfPageOps.fillForm(
            doc,
            mapOf(
                "name" to "Hello World",
                "agree" to "Yes",
                "color" to "b",
                "city" to "ca"
            )
        ).getOrThrow()
        doc.close()

        val reopened = reopen(bytes)
        val fields = reopened.formFields().associateBy { it.name }
        assertEquals("Hello World", fields.getValue("name").value)
        assertTrue(fields.getValue("agree").isChecked)
        assertEquals("b", fields.getValue("color").value)
        assertEquals("ca", fields.getValue("city").value)

        // Text appearance carries the value and a Helvetica resource.
        val nameStream = fields.getValue("name").widgets[0].appearance
        assertNotNull(nameStream)
        val nameContent = String(nameStream.data, StandardCharsets.ISO_8859_1)
        assertTrue(nameContent.contains("(Hello World)"), "appearance was: $nameContent")
        assertNotNull(nameStream.dict["Resources"])

        // Checkbox widget has both states and the selected /AS.
        val agreeWidget = fields.getValue("agree").widgets[0]
        assertEquals("Yes", agreeWidget.onState)
        assertEquals("Yes", agreeWidget.dict["AS"]?.let { (it as com.pdfpaper.engine.model.PdfName).name })

        // Radio: only the /b widget is on, /r is off.
        val colorWidgets = fields.getValue("color").widgets
        assertEquals(2, colorWidgets.size)
        val onStates = colorWidgets.map { w -> (w.dict["AS"] as com.pdfpaper.engine.model.PdfName).name }
        assertEquals(listOf("Off", "b"), onStates)

        // Choice shows the display value.
        val cityStream = fields.getValue("city").widgets[0].appearance
        assertNotNull(cityStream)
        assertTrue(String(cityStream.data, StandardCharsets.ISO_8859_1).contains("(Canada)"))
        reopened.close()
    }

    @Test
    fun annotationsAndFormValuesSaveTogether() {
        val doc = formDocument()
        val bytes = PdfPageOps.edit(
            doc,
            annotations = listOf(
                HighlightSpec(
                    0,
                    listOf(PdfRect(50.0, 700.0, 250.0, 720.0)),
                    contents = "name area"
                )
            ),
            formValues = mapOf("name" to "Ada")
        ).getOrThrow()
        doc.close()

        val reopened = reopen(bytes)
        val page = reopened.pages().first()
        val annots = page.annotations()
        // The five widget annotations plus the new highlight.
        assertEquals(6, annots.size)
        assertNotNull(annots.firstOrNull { it.contents == "name area" })
        assertEquals("Ada", reopened.formFields().associateBy { it.name }.getValue("name").value)
        reopened.close()
    }

    @Test
    fun cyclicFieldTreeDoesNotOverflow() {
        val doc = cyclicFormDocument()
        assertTrue(doc.formFields().isEmpty())
        doc.close()
    }

    @Test
    fun fillingCyclicFieldTreeDoesNotOverflow() {
        val doc = cyclicFormDocument()
        PdfPageOps.fillForm(doc, mapOf("a" to "x")).getOrThrow()
        doc.close()
    }
}
