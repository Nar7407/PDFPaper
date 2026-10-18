package com.pdfpaper.convert.pptx

/**
 * Static PresentationML parts. Keeping them as constants (rather than building
 * them element by element) makes it obvious that nothing here depends on the
 * input PDF, and the structure validator parses every one of them back.
 *
 * Namespaces and part content types follow ECMA-376 / ISO 29500-1 §13 and the
 * OPC conventions of ISO 29500-2.
 */
internal object PptxTemplates {

    const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
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
    const val REL_SLIDE_MASTER = "$REL_BASE/slideMaster"
    const val REL_SLIDE_LAYOUT = "$REL_BASE/slideLayout"
    const val REL_SLIDE = "$REL_BASE/slide"
    const val REL_THEME = "$REL_BASE/theme"
    const val REL_PRES_PROPS = "$REL_BASE/presProps"
    const val REL_IMAGE = "$REL_BASE/image"
    const val REL_EXTENDED_PROPS = "$REL_BASE/extended-properties"
    const val REL_CORE_PROPS = "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"

    const val CT_PRESENTATION =
        "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
    const val CT_SLIDE_MASTER =
        "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"
    const val CT_SLIDE_LAYOUT =
        "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"
    const val CT_SLIDE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
    const val CT_THEME = "application/vnd.openxmlformats-officedocument.theme+xml"
    const val CT_PRES_PROPS =
        "application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"
    const val CT_CORE_PROPS = "application/vnd.openxmlformats-package.core-properties+xml"
    const val CT_EXTENDED_PROPS =
        "application/vnd.openxmlformats-officedocument.extended-properties+xml"
    const val CT_RELS = "application/vnd.openxmlformats-package.relationships+xml"

    const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    val PRES_PROPS = XML_DECL +
        "<p:presentationPr xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\"/>\n"

    /** A complete Office-like theme; a slide master must relate to one. */
    val THEME = XML_DECL +
        """<a:theme xmlns:a="$NS_A" name="Office Theme">
  <a:themeElements>
    <a:clrScheme name="Office">
      <a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>
      <a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
      <a:dk2><a:srgbClr val="44546A"/></a:dk2>
      <a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>
      <a:accent1><a:srgbClr val="4472C4"/></a:accent1>
      <a:accent2><a:srgbClr val="ED7D31"/></a:accent2>
      <a:accent3><a:srgbClr val="A5A5A5"/></a:accent3>
      <a:accent4><a:srgbClr val="FFC000"/></a:accent4>
      <a:accent5><a:srgbClr val="5B9BD5"/></a:accent5>
      <a:accent6><a:srgbClr val="70AD47"/></a:accent6>
      <a:hlink><a:srgbClr val="0563C1"/></a:hlink>
      <a:folHlink><a:srgbClr val="954F72"/></a:folHlink>
    </a:clrScheme>
    <a:fontScheme name="Office">
      <a:majorFont>
        <a:latin typeface="Calibri Light"/>
        <a:ea typeface=""/>
        <a:cs typeface=""/>
      </a:majorFont>
      <a:minorFont>
        <a:latin typeface="Calibri"/>
        <a:ea typeface=""/>
        <a:cs typeface=""/>
      </a:minorFont>
    </a:fontScheme>
    <a:fmtScheme name="Office">
      <a:fillStyleLst>
        <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
        <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
        <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
      </a:fillStyleLst>
      <a:lnStyleLst>
        <a:ln w="6350" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>
        <a:ln w="12700" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>
        <a:ln w="19050" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>
      </a:lnStyleLst>
      <a:effectStyleLst>
        <a:effectStyle><a:effectLst/></a:effectStyle>
        <a:effectStyle><a:effectLst/></a:effectStyle>
        <a:effectStyle><a:effectLst/></a:effectStyle>
      </a:effectStyleLst>
      <a:bgFillStyleLst>
        <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
        <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
        <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
      </a:bgFillStyleLst>
    </a:fmtScheme>
  </a:themeElements>
  <a:objectDefaults/>
  <a:extraClrSchemeLst/>
</a:theme>
"""

    private val GROUP_SP_TREE =
        """  <p:nvGrpSpPr>
    <p:cNvPr id="1" name=""/>
    <p:cNvGrpSpPr/>
    <p:nvPr/>
  </p:nvGrpSpPr>
  <p:grpSpPr>
    <a:xfrm>
      <a:off x="0" y="0"/>
      <a:ext cx="0" cy="0"/>
      <a:chOff x="0" y="0"/>
      <a:chExt cx="0" cy="0"/>
    </a:xfrm>
  </p:grpSpPr>
"""

    val SLIDE_MASTER = XML_DECL +
        """<p:sldMaster xmlns:a="$NS_A" xmlns:r="$NS_R" xmlns:p="$NS_P">
  <p:cSld>
    <p:bg>
      <p:bgPr>
        <a:solidFill><a:schemeClr val="bg1"/></a:solidFill>
        <a:effectLst/>
      </p:bgPr>
    </p:bg>
    <p:spTree>
$GROUP_SP_TREE  </p:spTree>
  </p:cSld>
  <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
  <p:sldLayoutIdLst>
    <p:sldLayoutId id="2147483649" r:id="rId1"/>
  </p:sldLayoutIdLst>
  <p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>
</p:sldMaster>
"""

    val SLIDE_MASTER_RELS = XML_DECL +
        """<Relationships xmlns="$NS_REL">
  <Relationship Id="rId1" Type="$REL_SLIDE_LAYOUT" Target="../slideLayouts/slideLayout1.xml"/>
  <Relationship Id="rId2" Type="$REL_THEME" Target="../theme/theme1.xml"/>
</Relationships>
"""

    val SLIDE_LAYOUT = XML_DECL +
        """<p:sldLayout xmlns:a="$NS_A" xmlns:r="$NS_R" xmlns:p="$NS_P" type="blank" preserve="1">
  <p:cSld name="Blank">
    <p:spTree>
$GROUP_SP_TREE  </p:spTree>
  </p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sldLayout>
"""

    val SLIDE_LAYOUT_RELS = XML_DECL +
        """<Relationships xmlns="$NS_REL">
  <Relationship Id="rId1" Type="$REL_SLIDE_MASTER" Target="../slideMasters/slideMaster1.xml"/>
</Relationships>
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

    fun appProps(slideCount: Int): String = XML_DECL +
        """<Properties xmlns="$NS_EP" xmlns:vt="$NS_VT">
  <Application>PDFPaper</Application>
  <Slides>$slideCount</Slides>
</Properties>
"""

    val ROOT_RELS = XML_DECL +
        """<Relationships xmlns="$NS_REL">
  <Relationship Id="rId1" Type="$REL_OFFICE_DOCUMENT" Target="ppt/presentation.xml"/>
  <Relationship Id="rId2" Type="$REL_CORE_PROPS" Target="docProps/core.xml"/>
  <Relationship Id="rId3" Type="$REL_EXTENDED_PROPS" Target="docProps/app.xml"/>
</Relationships>
"""
}
