package com.pdfpaper.convert

import com.pdfpaper.convert.ooxml.OpcPackage
import com.pdfpaper.convert.pptx.Emu
import com.pdfpaper.convert.pptx.PptxDeck
import com.pdfpaper.convert.pptx.PptxWriter
import com.pdfpaper.convert.pptx.Slide
import com.pdfpaper.convert.pptx.TextBoxShape
import kotlin.test.Test
import kotlin.test.assertTrue

class PptxValidatorTest {

    private fun goodDeck(): ByteArray = PptxWriter.write(
        PptxDeck(
            slideWidthEmu = Emu.ofPoints(612.0),
            slideHeightEmu = Emu.ofPoints(792.0),
            slides = listOf(
                Slide(
                    listOf(
                        TextBoxShape(
                            2, 0, 0, 100, 100, "hi", 1200,
                            false, false, 0x000000, "Arial"
                        )
                    )
                )
            ),
            media = emptyMap()
        )
    )

    @Test
    fun `accepts a well-formed package`() {
        val problems = PptxValidator.validate(goodDeck())
        assertTrue(problems.isEmpty(), problems.joinToString())
    }

    @Test
    fun `rejects a package with a dangling relationship`() {
        val pkg = OpcPackage()
        pkg.addXml(OpcPackage.CONTENT_TYPES, contentTypes())
        pkg.addXml(
            "_rels/.rels",
            """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
               <Relationship Id="rId1" Type="x" Target="ppt/presentation.xml"/></Relationships>"""
        )
        val problems = PptxValidator.validate(pkg.bytes())
        assertTrue(problems.any { it.contains("missing part") }, problems.joinToString())
    }

    @Test
    fun `rejects a part without a content type`() {
        val pkg = OpcPackage()
        pkg.addXml(OpcPackage.CONTENT_TYPES, contentTypes())
        pkg.addXml("ppt/presentation.xml", "<?xml version=\"1.0\"?><p:presentation xmlns:p=\"p\"/>")
        pkg.add("odd.bin", ByteArray(4))
        val problems = PptxValidator.validate(pkg.bytes())
        assertTrue(problems.any { it.contains("not covered by [Content_Types].xml") }, problems.joinToString())
    }

    @Test
    fun `rejects malformed xml`() {
        val pkg = OpcPackage()
        pkg.addXml(OpcPackage.CONTENT_TYPES, contentTypes())
        pkg.addXml("ppt/presentation.xml", "<?xml version=\"1.0\"?><p:presentation xmlns:p=\"p\">")
        val problems = PptxValidator.validate(pkg.bytes())
        assertTrue(problems.any { it.contains("not well-formed") }, problems.joinToString())
    }

    private fun contentTypes(): String =
        """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
           <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
           <Default Extension="xml" ContentType="application/xml"/>
           <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
           </Types>"""
}
