package com.pdfpaper.convert.docx

import com.pdfpaper.convert.DocxValidator
import com.pdfpaper.convert.docx.DocAlign
import com.pdfpaper.convert.docx.DocImage
import com.pdfpaper.convert.docx.DocImageAnchor
import com.pdfpaper.convert.docx.DocImageBlock
import com.pdfpaper.convert.docx.DocListRef
import com.pdfpaper.convert.docx.DocNumInstance
import com.pdfpaper.convert.docx.DocNumbering
import com.pdfpaper.convert.docx.DocParagraph
import com.pdfpaper.convert.docx.DocParagraphBlock
import com.pdfpaper.convert.docx.DocRun
import com.pdfpaper.convert.docx.DocSection
import com.pdfpaper.convert.docx.DocTableBlock
import com.pdfpaper.convert.docx.DocxDocument
import com.pdfpaper.convert.docx.DocxTemplates
import com.pdfpaper.convert.docx.DocxWriter
import com.pdfpaper.convert.ooxml.OpcPackage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocxWriterTest {

    private val section = DocSection(
        pageWidth = 12240, pageHeight = 15840,
        marginLeft = 1440, marginRight = 1440, marginTop = 1440, marginBottom = 1440
    )

    private fun image(name: String, anchor: DocImageAnchor? = null) =
        DocImage(name, 720, 360, anchor, flipH = false, flipV = anchor == null)

    private fun document(): DocxDocument = DocxDocument(
        blocks = listOf(
            DocParagraphBlock(
                DocParagraph(
                    runs = listOf(DocRun("Quarterly", family = "Arial", sizeHalfPoints = 40, bold = true)),
                    styleId = "Heading1",
                    alignment = DocAlign.CENTER,
                    spacingExplicit = true,
                    // A4: it ends a section of its own, so it is written inline.
                    sectionEnd = DocSection(11906, 16838, 1134, 1134, 1134, 1134)
                )
            ),
            DocParagraphBlock(
                DocParagraph(
                    runs = listOf(DocRun("A\tB\nC\u00ADD", family = "Arial", sizeHalfPoints = 20, underline = true)),
                    list = DocListRef(1, 1),
                    indentLeft = 1440,
                    indentFirstLine = -360,
                    lineHeight = 276,
                    spacingExplicit = true,
                    sectionEnd = section
                )
            ),
            DocTableBlock(
                rows = listOf(
                    listOf(DocParagraph(listOf(DocRun("Item"))), DocParagraph(listOf(DocRun("1")))),
                    listOf(DocParagraph(listOf(DocRun("Total"))), DocParagraph(listOf(DocRun("2"))))
                ),
                columnWidths = listOf(2400, 1200)
            ),
            DocImageBlock(
                listOf(
                    image("word/media/image1.png"),
                    image("word/media/image2.png", DocImageAnchor(720, 1440, behindText = true))
                )
            ),
            DocImageBlock(image("word/media/image1.png"))
        ),
        media = mapOf(
            "word/media/image1.png" to byteArrayOf(1, 2, 3),
            "word/media/image2.png" to byteArrayOf(4, 5, 6)
        ),
        numbering = DocNumbering(listOf(DocNumInstance(1, DocxTemplates.ABSTRACT_DECIMAL, mapOf(1 to 3)))),
        finalSection = section,
        title = "Test document",
        pageCount = 2
    )

    private fun part(name: String): String =
        String(OpcPackage.read(DocxWriter.write(document())).getValue(name), Charsets.UTF_8)

    @Test
    fun `writes a package the validator accepts`() {
        assertEquals(emptyList(), DocxValidator.validate(DocxWriter.write(document())))
    }

    @Test
    fun `package has the required parts`() {
        val parts = OpcPackage.read(DocxWriter.write(document()))
        for (name in listOf(
            "[Content_Types].xml", "_rels/.rels", "docProps/core.xml", "docProps/app.xml",
            "word/document.xml", "word/_rels/document.xml.rels", "word/styles.xml",
            "word/settings.xml", "word/numbering.xml",
            "word/media/image1.png", "word/media/image2.png"
        )) {
            assertTrue(parts.containsKey(name), "missing part: $name")
        }
    }

    @Test
    fun `paragraph properties keep the schema order`() {
        val xml = part("word/document.xml")
        val blocks = Regex("<w:pPr>(.*?)</w:pPr>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }.toList()

        val heading = blocks.first { it.contains("<w:pStyle") }.trimStart()
        assertEquals(0, heading.indexOf("<w:pStyle"), heading)
        assertTrue(heading.indexOf("<w:spacing") < heading.indexOf("<w:jc"), heading)

        val list = blocks.first { it.contains("<w:numPr>") }
        val order = listOf("<w:numPr>", "<w:spacing", "<w:ind").map { list.indexOf(it) }
        assertEquals(order.sorted(), order, list)
    }

    @Test
    fun `the last section becomes the body level sectPr`() {
        val xml = part("word/document.xml")
        // One section ends inline, the final one sits at the end of w:body.
        assertEquals(2, Regex("<w:sectPr>").findAll(xml).count(), xml)
        assertTrue(xml.trimEnd().endsWith("</w:sectPr>\n  </w:body>\n</w:document>"), xml)
        assertTrue(xml.contains("<w:pgSz w:w=\"11906\" w:h=\"16838\"/>"), xml)
        assertTrue(xml.contains("<w:pgSz w:w=\"12240\" w:h=\"15840\"/>"), xml)
    }

    @Test
    fun `tabs and breaks become elements and soft hyphens disappear`() {
        val xml = part("word/document.xml")
        assertTrue(xml.contains("<w:tab/>"), xml)
        assertTrue(xml.contains("<w:br/>"), xml)
        assertFalse(xml.contains("\u00AD"), xml)
        assertTrue(xml.contains("<w:u w:val=\"single\"/>"), xml)
        assertTrue(xml.contains("w:line=\"276\" w:lineRule=\"exact\""), xml)
    }

    @Test
    fun `tables get a grid that matches their cells`() {
        val xml = part("word/document.xml")
        assertTrue(xml.contains("<w:gridCol w:w=\"2400\"/>"), xml)
        assertTrue(xml.contains("<w:gridCol w:w=\"1200\"/>"), xml)
        assertTrue(xml.contains("<w:tcW w:w=\"2400\" w:type=\"dxa\"/>"), xml)
    }

    @Test
    fun `every picture gets its own id and relationship`() {
        val xml = part("word/document.xml")
        val rels = part("word/_rels/document.xml.rels")
        for (id in 1..3) {
            assertTrue(xml.contains("<wp:docPr id=\"$id\" name=\"Picture $id\"/>"), "docPr $id: $xml")
        }
        assertTrue(rels.contains("Id=\"rId4\"") && rels.contains("Target=\"media/image1.png\""), rels)
        assertTrue(rels.contains("Id=\"rId5\"") && rels.contains("Target=\"media/image2.png\""), rels)
        assertTrue(xml.contains("r:embed=\"rId4\""), xml)
        assertTrue(xml.contains("behindDoc=\"1\""), xml)
        assertTrue(xml.contains("flipV=\"1\""), xml)
    }

    @Test
    fun `numbering carries the start override`() {
        val numbering = part("word/numbering.xml")
        assertTrue(numbering.contains("<w:num w:numId=\"1\">"), numbering)
        assertTrue(numbering.contains("<w:lvlOverride w:ilvl=\"1\">"), numbering)
        assertTrue(numbering.contains("<w:startOverride w:val=\"3\"/>"), numbering)
    }

    @Test
    fun `output is deterministic`() {
        assertContentEquals(DocxWriter.write(document()), DocxWriter.write(document()))
    }
}
