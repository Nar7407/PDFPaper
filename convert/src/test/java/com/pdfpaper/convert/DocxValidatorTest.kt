package com.pdfpaper.convert

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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The validator is the safety net the corpus check leans on, so it has to fail
 * loudly on the mistakes Word would want to repair.
 */
class DocxValidatorTest {

    private val section = DocSection(12240, 15840, 1440, 1440, 1440, 1440)

    private fun valid(): ByteArray = DocxWriter.write(
        DocxDocument(
            blocks = listOf(
                DocParagraphBlock(
                    DocParagraph(
                        listOf(DocRun("Item")),
                        list = DocListRef(1, 0),
                        spacingExplicit = true
                    )
                ),
                DocParagraphBlock(
                    DocParagraph(listOf(DocRun("Hello")), spacingExplicit = true, sectionEnd = section)
                )
            ),
            media = mapOf("word/media/image1.png" to byteArrayOf(1, 2, 3)),
            numbering = DocNumbering(listOf(DocNumInstance(1, DocxTemplates.ABSTRACT_DECIMAL))),
            finalSection = section
        )
    )

    /** Replaces one part of the package and re-serializes it. */
    private fun mutate(bytes: ByteArray, part: String, transform: (String) -> String): ByteArray {
        val source = OpcPackage.read(bytes)
        val pkg = OpcPackage()
        for ((name, partBytes) in source) {
            if (name != part) {
                pkg.add(name, partBytes)
            } else {
                pkg.add(name, transform(String(partBytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
            }
        }
        return pkg.bytes()
    }

    private fun withoutPart(bytes: ByteArray, part: String): ByteArray {
        val source = OpcPackage.read(bytes)
        val pkg = OpcPackage()
        for ((name, partBytes) in source) if (name != part) pkg.add(name, partBytes)
        return pkg.bytes()
    }

    private fun withExtraPart(bytes: ByteArray, name: String, content: ByteArray): ByteArray {
        val pkg = OpcPackage()
        for ((partName, partBytes) in OpcPackage.read(bytes)) pkg.add(partName, partBytes)
        pkg.add(name, content)
        return pkg.bytes()
    }

    @Test
    fun `accepts a correctly written package`() {
        assertEquals(emptyList(), DocxValidator.validate(valid()))
    }

    @Test
    fun `rejects something that is not a package`() {
        val problems = DocxValidator.validate("not a zip".toByteArray())
        assertEquals(1, problems.size)
        assertTrue(problems[0].startsWith("not a readable ZIP package"), problems.toString())
    }

    @Test
    fun `rejects a package with an unlisted part`() {
        // No Default for "bin", so nothing declares what this part is.
        val bytes = withExtraPart(valid(), "word/media/extra.bin", byteArrayOf(7))
        val problems = DocxValidator.validate(bytes)
        assertTrue(problems.any { it.contains("not covered by [Content_Types].xml: word/media/extra.bin") }, problems.toString())
    }

    @Test
    fun `rejects a broken relationship target`() {
        val problems = DocxValidator.validate(withoutPart(valid(), "word/settings.xml"))
        assertTrue(problems.any { it.contains("references missing part 'settings.xml'") }, problems.toString())
    }

    @Test
    fun `rejects a document that does not end with the section properties`() {
        val bytes = mutate(valid(), "word/document.xml") { xml ->
            val start = xml.lastIndexOf("<w:sectPr>")
            val end = xml.indexOf("</w:sectPr>", start) + "</w:sectPr>".length
            xml.removeRange(start, end)
        }
        assertTrue(DocxValidator.validate(bytes).any { it.contains("does not end with w:sectPr") })
    }

    @Test
    fun `rejects an undefined numbering reference`() {
        val bytes = mutate(valid(), "word/document.xml") { xml ->
            xml.replaceFirst("<w:numId w:val=\"1\"/>", "<w:numId w:val=\"9\"/>")
        }
        assertTrue(DocxValidator.validate(bytes).any { it.contains("undefined numbering id '9'") })
    }

    @Test
    fun `rejects a table with more cells than grid columns`() {
        val table = DocxWriter.write(
            DocxDocument(
                blocks = listOf(
                    DocTableBlock(
                        rows = listOf(
                            listOf(DocParagraph(listOf(DocRun("a"))), DocParagraph(listOf(DocRun("b")))),
                            listOf(DocParagraph(listOf(DocRun("c"))), DocParagraph(listOf(DocRun("d"))))
                        ),
                        columnWidths = listOf(1000, 1000)
                    )
                ),
                media = emptyMap(),
                finalSection = section
            )
        )
        // Duplicate the last cell of the first row: three cells, two columns.
        val bytes = mutate(table, "word/document.xml") { xml ->
            val rowEnd = xml.indexOf("</w:tr>")
            val rowStart = xml.lastIndexOf("<w:tr>", rowEnd)
            val row = xml.substring(rowStart, rowEnd)
            val cell = row.substring(row.lastIndexOf("<w:tc>"))
            xml.substring(0, rowEnd) + cell + xml.substring(rowEnd)
        }
        assertTrue(
            DocxValidator.validate(bytes).any { it.contains("table row has 3 cell(s) but w:tblGrid declares 2 column(s)") }
        )
    }
}
