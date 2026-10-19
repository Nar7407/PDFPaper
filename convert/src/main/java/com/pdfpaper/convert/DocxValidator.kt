package com.pdfpaper.convert

import com.pdfpaper.convert.docx.DocxTemplates
import com.pdfpaper.convert.ooxml.PackageCheck
import com.pdfpaper.convert.ooxml.childElements
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Independent structural check of a written DOCX package. It re-reads the ZIP
 * and asserts what decides whether Word says "repair":
 *
 *  - the generic OPC rules ([PackageCheck]),
 *  - `word/document.xml` exists and its body ends with the section properties,
 *  - every picture relationship used by the document resolves,
 *  - every numbering reference points at a definition in `word/numbering.xml`,
 *  - every table row has as many cells as its grid declares.
 *
 * Used by the unit tests and by the corpus acceptance tool; it is deliberately
 * not part of the writer so it cannot be fooled by the writer's own helpers.
 */
object DocxValidator {

    fun validate(archive: ByteArray): List<String> {
        val (packageCheck, problems) = PackageCheck.read(archive)
        if (packageCheck == null) return problems

        val document = packageCheck.documents["word/document.xml"]
        if (document == null) {
            problems += "missing word/document.xml"
            return problems
        }

        val rels = PackageCheck.relationshipIds(packageCheck.documents["word/_rels/document.xml.rels"])
        val embeds = document.getElementsByTagNameNS(DocxTemplates.NS_A, "blip")
        for (i in 0 until embeds.length) {
            val id = (embeds.item(i) as Element).getAttributeNS(DocxTemplates.NS_R, "embed")
            if (id !in rels) problems += "document references unknown image relationship '$id'"
        }

        val bodies = document.getElementsByTagNameNS(DocxTemplates.NS_W, "body")
        for (i in 0 until bodies.length) {
            val body = bodies.item(i) as Element
            val children = body.childElements()
            val last = children.lastOrNull()
            if (last == null || last.localName != "sectPr") {
                problems += "w:body does not end with w:sectPr"
            }
        }

        checkNumbering(packageCheck.documents["word/numbering.xml"], document, problems)
        checkTables(document, problems)
        return problems
    }

    /** Every `w:numId` the body uses must exist, and point at an abstract num. */
    private fun checkNumbering(numbering: Document?, document: Document, problems: MutableList<String>) {
        val used = LinkedHashSet<String>()
        val numIds = document.getElementsByTagNameNS(DocxTemplates.NS_W, "numId")
        for (i in 0 until numIds.length) {
            val value = (numIds.item(i) as Element).getAttributeNS(DocxTemplates.NS_W, "val")
            if (value.isNotEmpty()) used += value
        }
        if (used.isEmpty()) return
        if (numbering == null) {
            problems += "document uses numbering but word/numbering.xml is missing"
            return
        }

        val defined = HashSet<String>()
        val abstracts = HashSet<String>()
        val nums = numbering.getElementsByTagNameNS(DocxTemplates.NS_W, "num")
        for (i in 0 until nums.length) {
            val num = nums.item(i) as Element
            defined += num.getAttributeNS(DocxTemplates.NS_W, "numId")
            for (reference in num.childElements()) {
                if (reference.localName == "abstractNumId") {
                    abstracts += reference.getAttributeNS(DocxTemplates.NS_W, "val")
                }
            }
        }
        val declaredAbstracts = HashSet<String>()
        val abstractElements = numbering.getElementsByTagNameNS(DocxTemplates.NS_W, "abstractNum")
        for (i in 0 until abstractElements.length) {
            declaredAbstracts += (abstractElements.item(i) as Element)
                .getAttributeNS(DocxTemplates.NS_W, "abstractNumId")
        }
        for (id in used) {
            if (id !in defined) problems += "document uses undefined numbering id '$id'"
        }
        for (id in abstracts) {
            if (id !in declaredAbstracts) problems += "numbering definition references undefined abstractNumId '$id'"
        }
    }

    /** `w:tblGrid` must declare a column for every cell of every row. */
    private fun checkTables(document: Document, problems: MutableList<String>) {
        val tables = document.getElementsByTagNameNS(DocxTemplates.NS_W, "tbl")
        for (i in 0 until tables.length) {
            val table = tables.item(i) as Element
            var columns = -1
            for (child in table.childElements()) {
                if (child.localName == "tblGrid") columns = child.childElements().count { it.localName == "gridCol" }
            }
            val rows = table.childElements().filter { it.localName == "tr" }
            for (row in rows) {
                val cells = row.childElements().count { it.localName == "tc" }
                if (columns >= 0 && cells != columns) {
                    problems += "table row has $cells cell(s) but w:tblGrid declares $columns column(s)"
                }
            }
        }
    }
}
