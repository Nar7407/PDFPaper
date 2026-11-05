package com.pdfpaper.convert.docx

/**
 * Static WordprocessingML parts. Keeping them as constants (rather than
 * building them element by element) makes it obvious that nothing here depends
 * on the input PDF, and the structure validator parses every one of them back.
 *
 * Namespaces and part content types follow ECMA-376 / ISO 29500-1 §17 and the
 * OPC conventions of ISO 29500-2.
 */
internal object DocxTemplates {

    const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val NS_WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val NS_PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    const val NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"
    const val NS_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val NS_CP = "http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
    const val NS_DC = "http://purl.org/dc/elements/1.1/"
    const val NS_DCTERMS = "http://purl.org/dc/terms/"
    const val NS_DCMITYPE = "http://purl.org/dc/dcmitype/"
    const val NS_XSI = "http://www.w3.org/2001/XMLSchema-instance"
    const val NS_EP = "http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
    const val NS_VT = "http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"

    private const val REL_BASE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    const val REL_OFFICE_DOCUMENT = "$REL_BASE/officeDocument"
    const val REL_STYLES = "$REL_BASE/styles"
    const val REL_SETTINGS = "$REL_BASE/settings"
    const val REL_NUMBERING = "$REL_BASE/numbering"
    const val REL_IMAGE = "$REL_BASE/image"
    const val REL_EXTENDED_PROPS = "$REL_BASE/extended-properties"
    const val REL_CORE_PROPS = "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"

    const val CT_DOCUMENT =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    const val CT_STYLES = "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"
    const val CT_SETTINGS =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"
    const val CT_NUMBERING =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"
    const val CT_CORE_PROPS = "application/vnd.openxmlformats-package.core-properties+xml"
    const val CT_EXTENDED_PROPS =
        "application/vnd.openxmlformats-officedocument.extended-properties+xml"
    const val CT_RELS = "application/vnd.openxmlformats-package.relationships+xml"

    const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    val ROOT_RELS = XML_DECL +
        """<Relationships xmlns="$NS_REL">
  <Relationship Id="rId1" Type="$REL_OFFICE_DOCUMENT" Target="word/document.xml"/>
  <Relationship Id="rId2" Type="$REL_CORE_PROPS" Target="docProps/core.xml"/>
  <Relationship Id="rId3" Type="$REL_EXTENDED_PROPS" Target="docProps/app.xml"/>
</Relationships>
"""

    val SETTINGS = XML_DECL +
        """<w:settings xmlns:w="$NS_W">
  <w:zoom w:percent="100"/>
  <w:defaultTabStop w:val="708"/>
  <w:characterSpacingControl w:val="doNotCompress"/>
  <w:compat>
    <w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/>
  </w:compat>
</w:settings>
"""

    /**
     * Body, heading and list styles. The heading sizes come from the theme
     * (20/16/14 pt) rather than from the source PDF: a flowing document gets
     * the target application's own heading look, and the outline level is what
     * keeps navigation and the table of contents working.
     */
    val STYLES = XML_DECL +
        """<w:styles xmlns:w="$NS_W">
  <w:docDefaults>
    <w:rPrDefault>
      <w:rPr>
        <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/>
        <w:sz w:val="22"/>
        <w:szCs w:val="22"/>
        <w:lang w:val="en-US" w:eastAsia="en-US" w:bidi="ar-SA"/>
      </w:rPr>
    </w:rPrDefault>
    <w:pPrDefault>
      <w:pPr>
        <w:spacing w:after="160" w:line="259" w:lineRule="auto"/>
      </w:pPr>
    </w:pPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
    <w:name w:val="Normal"/>
    <w:qFormat/>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading1">
    <w:name w:val="heading 1"/>
    <w:basedOn w:val="Normal"/>
    <w:next w:val="Normal"/>
    <w:uiPriority w:val="9"/>
    <w:qFormat/>
    <w:pPr>
      <w:keepNext/>
      <w:keepLines/>
      <w:spacing w:before="240" w:after="0"/>
      <w:outlineLvl w:val="0"/>
    </w:pPr>
    <w:rPr>
      <w:rFonts w:ascii="Calibri Light" w:hAnsi="Calibri Light" w:cs="Calibri Light"/>
      <w:b/>
      <w:color w:val="2F5496"/>
      <w:sz w:val="40"/>
      <w:szCs w:val="40"/>
    </w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading2">
    <w:name w:val="heading 2"/>
    <w:basedOn w:val="Normal"/>
    <w:next w:val="Normal"/>
    <w:uiPriority w:val="9"/>
    <w:qFormat/>
    <w:pPr>
      <w:keepNext/>
      <w:keepLines/>
      <w:spacing w:before="200" w:after="0"/>
      <w:outlineLvl w:val="1"/>
    </w:pPr>
    <w:rPr>
      <w:rFonts w:ascii="Calibri Light" w:hAnsi="Calibri Light" w:cs="Calibri Light"/>
      <w:b/>
      <w:color w:val="2F5496"/>
      <w:sz w:val="32"/>
      <w:szCs w:val="32"/>
    </w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading3">
    <w:name w:val="heading 3"/>
    <w:basedOn w:val="Normal"/>
    <w:next w:val="Normal"/>
    <w:uiPriority w:val="9"/>
    <w:qFormat/>
    <w:pPr>
      <w:keepNext/>
      <w:keepLines/>
      <w:spacing w:before="160" w:after="0"/>
      <w:outlineLvl w:val="2"/>
    </w:pPr>
    <w:rPr>
      <w:rFonts w:ascii="Calibri Light" w:hAnsi="Calibri Light" w:cs="Calibri Light"/>
      <w:b/>
      <w:color w:val="1F3763"/>
      <w:sz w:val="28"/>
      <w:szCs w:val="28"/>
    </w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="ListParagraph">
    <w:name w:val="List Paragraph"/>
    <w:basedOn w:val="Normal"/>
    <w:uiPriority w:val="34"/>
    <w:qFormat/>
    <w:pPr>
      <w:ind w:left="720"/>
      <w:contextualSpacing/>
    </w:pPr>
  </w:style>
</w:styles>
"""

    fun coreProps(title: String): String = XML_DECL +
        """<cp:coreProperties xmlns:cp="$NS_CP" xmlns:dc="$NS_DC" xmlns:dcterms="$NS_DCTERMS" xmlns:dcmitype="$NS_DCMITYPE" xmlns:xsi="$NS_XSI">
  <dc:title>${com.pdfpaper.convert.ooxml.XmlBuilder.escapeText(title)}</dc:title>
  <dc:creator>PDFPaper</dc:creator>
  <cp:lastModifiedBy>PDFPaper</cp:lastModifiedBy>
  <dcterms:created xsi:type="dcterms:W3CDTF">2020-01-01T00:00:00Z</dcterms:created>
  <dcterms:modified xsi:type="dcterms:W3CDTF">2020-01-01T00:00:00Z</dcterms:modified>
</cp:coreProperties>
"""

    fun appProps(pageCount: Int, paragraphs: Int, words: Int, characters: Int): String = XML_DECL +
        """<Properties xmlns="$NS_EP" xmlns:vt="$NS_VT">
  <Application>PDFPaper</Application>
  <Pages>$pageCount</Pages>
  <Words>$words</Words>
  <Characters>$characters</Characters>
  <Paragraphs>$paragraphs</Paragraphs>
</Properties>
"""

    // ------------------------------------------------------------- numbering

    /** Abstract numbering definitions; index 0 is the bullet list. */
    const val ABSTRACT_BULLET = 0
    const val ABSTRACT_DECIMAL = 1
    const val ABSTRACT_LOWER_LETTER = 2
    const val ABSTRACT_UPPER_LETTER = 3
    const val ABSTRACT_LOWER_ROMAN = 4
    const val ABSTRACT_UPPER_ROMAN = 5
    const val ABSTRACT_COUNT = 6

    private fun abstractNum(id: Int, name: String, format: String, level0: String): String {
        fun level(ilvl: Int, text: String): String {
            val marker = when {
                format == "bullet" && ilvl == 1 -> """<w:rFonts w:ascii="Courier New" w:hAnsi="Courier New" w:hint="default"/>"""
                format == "bullet" && ilvl == 2 -> """<w:rFonts w:ascii="Wingdings" w:hAnsi="Wingdings" w:hint="default"/>"""
                format == "bullet" -> """<w:rFonts w:ascii="Symbol" w:hAnsi="Symbol" w:hint="default"/>"""
                else -> ""
            }
            return """    <w:lvl w:ilvl="$ilvl">
      <w:start w:val="1"/>
      <w:numFmt w:val="$format"/>
      <w:lvlText w:val="$text"/>
      <w:lvlJc w:val="left"/>
      <w:pPr>
        <w:ind w:left="${720 * (ilvl + 1)}" w:hanging="360"/>
      </w:pPr>
      <w:rPr>
        $marker
      </w:rPr>
    </w:lvl>
"""
        }
        return """  <w:abstractNum w:abstractNumId="$id">
    <w:multiLevelType w:val="hybridMultilevel"/>
    <w:name w:val="$name"/>
${level(0, level0)}${level(1, if (format == "bullet") "\uF0A7" else "%2)")}${level(2, if (format == "bullet") "\uF0B7" else "%3.")}
  </w:abstractNum>
"""
    }

    fun numbering(numbering: DocNumbering): String {
        val sb = StringBuilder(XML_DECL)
        sb.append("""<w:numbering xmlns:w="$NS_W">
""")
        sb.append(abstractNum(ABSTRACT_BULLET, "PDFPaperBullet", "bullet", "\uF0B7"))
        sb.append(abstractNum(ABSTRACT_DECIMAL, "PDFPaperDecimal", "decimal", "%1."))
        sb.append(abstractNum(ABSTRACT_LOWER_LETTER, "PDFPaperLowerLetter", "lowerLetter", "%1."))
        sb.append(abstractNum(ABSTRACT_UPPER_LETTER, "PDFPaperUpperLetter", "upperLetter", "%1."))
        sb.append(abstractNum(ABSTRACT_LOWER_ROMAN, "PDFPaperLowerRoman", "lowerRoman", "%1."))
        sb.append(abstractNum(ABSTRACT_UPPER_ROMAN, "PDFPaperUpperRoman", "upperRoman", "%1."))
        for (instance in numbering.instances) {
            sb.append("""  <w:num w:numId="${instance.numId}">
    <w:abstractNumId w:val="${instance.abstractNumId}"/>
""")
            for ((level, start) in instance.startOverrides.entries.sortedBy { it.key }) {
                sb.append("""    <w:lvlOverride w:ilvl="$level">
      <w:startOverride w:val="$start"/>
    </w:lvlOverride>
""")
            }
            sb.append("  </w:num>\n")
        }
        sb.append("</w:numbering>\n")
        return sb.toString()
    }
}
