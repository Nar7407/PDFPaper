package com.pdfpaper.convert.pptx

import com.pdfpaper.convert.PptxValidator
import com.pdfpaper.convert.ooxml.OpcPackage
import com.pdfpaper.convert.raster.PngWriter
import com.pdfpaper.convert.raster.Raster
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PptxWriterTest {

    private fun deck(): PptxDeck {
        val png = PngWriter.encodeRgb(Raster(2, 2).apply { fillWhite() })
        return PptxDeck(
            slideWidthEmu = Emu.ofPoints(612.0),
            slideHeightEmu = Emu.ofPoints(792.0),
            slides = listOf(
                Slide(
                    listOf(
                        TextBoxShape(
                            id = 2, xEmu = 1000, yEmu = 2000, wEmu = 3000, hEmu = 4000,
                            text = "Hello & <world>", sizeHundredths = 2400,
                            bold = true, italic = false, rgb = 0x112233, family = "Arial"
                        ),
                        PictureShape(3, 0, 0, 100, 100, false, false, "ppt/media/image1.png")
                    )
                ),
                Slide(
                    listOf(
                        TextBoxShape(
                            id = 2, xEmu = 0, yEmu = 0, wEmu = 100, hEmu = 100,
                            text = "invisible", sizeHundredths = 1800,
                            bold = false, italic = true, rgb = 0x000000, family = "Times New Roman",
                            visible = false
                        )
                    )
                )
            ),
            media = mapOf("ppt/media/image1.png" to png),
            title = "Test deck"
        )
    }

    @Test
    fun `writes a package the validator accepts`() {
        val bytes = PptxWriter.write(deck())
        assertEquals(emptyList(), PptxValidator.validate(bytes))
    }

    @Test
    fun `package has the required parts`() {
        val parts = OpcPackage.read(PptxWriter.write(deck()))
        for (name in listOf(
            "[Content_Types].xml", "_rels/.rels", "docProps/core.xml", "docProps/app.xml",
            "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels", "ppt/presProps.xml",
            "ppt/theme/theme1.xml", "ppt/slideMasters/slideMaster1.xml",
            "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            "ppt/slideLayouts/slideLayout1.xml", "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
            "ppt/slides/slide1.xml", "ppt/slides/slide2.xml",
            "ppt/slides/_rels/slide1.xml.rels", "ppt/media/image1.png"
        )) {
            assertTrue(parts.containsKey(name), "missing part: $name")
        }
    }

    @Test
    fun `presentation lists the slides and keeps the page size`() {
        val parts = OpcPackage.read(PptxWriter.write(deck()))
        val presentation = String(parts.getValue("ppt/presentation.xml"), Charsets.UTF_8)
        assertTrue(presentation.contains("<p:sldId id=\"256\" r:id=\"rId2\"/>"))
        assertTrue(presentation.contains("<p:sldId id=\"257\" r:id=\"rId3\"/>"))
        assertTrue(presentation.contains("<p:sldSz cx=\"7772400\" cy=\"10058400\"/>"))
    }

    @Test
    fun `text is escaped and invisible runs get zero alpha`() {
        val parts = OpcPackage.read(PptxWriter.write(deck()))
        val slide1 = String(parts.getValue("ppt/slides/slide1.xml"), Charsets.UTF_8)
        assertTrue(slide1.contains("Hello &amp; &lt;world&gt;"))
        assertTrue(slide1.contains("b=\"1\""))
        val slide2 = String(parts.getValue("ppt/slides/slide2.xml"), Charsets.UTF_8)
        assertTrue(slide2.contains("<a:alpha val=\"0\"/>"))
    }

    @Test
    fun `output is deterministic`() {
        assertContentEquals(PptxWriter.write(deck()), PptxWriter.write(deck()))
    }
}
