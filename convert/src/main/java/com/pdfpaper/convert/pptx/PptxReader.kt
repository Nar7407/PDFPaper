package com.pdfpaper.convert.pptx

import com.pdfpaper.convert.NoteBag
import com.pdfpaper.convert.PptxToPdfException
import com.pdfpaper.convert.ooxml.PackageCheck
import com.pdfpaper.convert.ooxml.childElements
import com.pdfpaper.convert.ooxml.descendants
import com.pdfpaper.convert.ooxml.first
import com.pdfpaper.convert.ooxml.kids
import com.pdfpaper.convert.ooxml.plainText
import com.pdfpaper.convert.pdf.PdfColor
import com.pdfpaper.convert.pdf.PdfImage
import com.pdfpaper.convert.raster.PngReader
import com.pdfpaper.convert.raster.PngResult
import com.pdfpaper.engine.image.PdfImages
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Reads a PresentationML package into a [SlideDeck].
 *
 * The reader follows the file's own structure: the presentation part gives the
 * slide size and the slide order, each slide points at its layout and the
 * layout at its master, and everything a shape does not say itself is inherited
 * along that chain when the shape is read, so the model only ever carries
 * concrete values. Everything the converter cannot express — charts, SmartArt,
 * tables, video, transitions, themes' gradient fills — is reported in the
 * deck's notes instead of disappearing quietly.
 *
 * Theme colours resolve through `a:clrScheme` and the master's `p:clrMap`, so
 * `schemeClr val="bg1"` becomes the real colour; theme fonts (`+mj-lt`,
 * `+mn-lt`) become their typeface names.
 */
internal object PptxReader {

    /** The fill elements a `p:spPr` or an `a:ln` can state. */
    private val FILL_KINDS = setOf("solidFill", "noFill", "gradFill", "pattFill", "blipFill", "grpFill")

    /** Placeholder types an index-only placeholder can stand for. */
    private val PLACEHOLDER_TYPES =
        listOf("body", "title", "ctrTitle", "subTitle", "dt", "ftr", "sldNum")

    /** Said once per deck when a shape or connector carries an effect. */
    private const val EFFECT_NOTE = "a shape's shadow, glow or reflection is not drawn"

    fun read(archive: ByteArray): SlideDeck {
        val (package_, problems) = PackageCheck.read(archive)
        if (package_ == null) {
            throw PptxToPdfException(problems.firstOrNull() ?: "not a readable .pptx package")
        }
        val reader = DeckReader(package_)
        reader.notes.addAll(problems)
        return reader.read()
    }

    // ------------------------------------------------------------- reader state

    private class DeckReader(val package_: PackageCheck.Package) {

        val notes = NoteBag()
        private lateinit var theme: ThemeScheme
        private lateinit var master: MasterInfo
        private var layoutDecoration = 0

        fun read(): SlideDeck {
            val rootRels = package_.documents["_rels/.rels"]
            val presentationPart = targetOf(rootRels, "", "officeDocument")
                ?: "ppt/presentation.xml"
            val presentation = package_.documents[presentationPart]?.documentElement
                ?: throw PptxToPdfException("the package has no presentation part ($presentationPart)")

            val presentationRels = rels(presentationPart)
            theme = ThemeScheme.EMPTY
            targetOf(presentationRels, baseDir(presentationPart), "theme")?.let { part ->
                package_.documents[part]?.documentElement?.let { theme = ThemeScheme.read(it, notes) }
            }

            val masterPart = targetOf(presentationRels, baseDir(presentationPart), "slideMaster")
            master = if (masterPart != null) {
                readMaster(package_.documents[masterPart], masterPart)
            } else {
                MasterInfo(emptyMap(), emptyMap(), 0)
            }

            val size = presentation.first("sldSz")
            val width = emu(size?.value("cx")) ?: 720.0
            val height = emu(size?.value("cy")) ?: 540.0
            if (size == null) {
                notes.add("the presentation carries no slide size; 10 x 7.5 in was assumed")
            }

            val slides = ArrayList<SlideContent>()
            var number = 0
            for (id in presentation.first("sldIdLst")?.kids("sldId").orEmpty()) {
                val relId = id.r("id")
                val part = relId?.let { relTarget(presentationRels, baseDir(presentationPart), it) } ?: continue
                number++
                val hidden = id.value("show") == "0"
                if (hidden) notes.add("a hidden slide is included in the PDF")
                slides += slide(part, number, hidden)
            }
            if (slides.isEmpty()) throw PptxToPdfException("the presentation has no slides")

            val decoration = master.decoration + layoutDecoration
            if (decoration > 0) {
                notes.add("$decoration shape(s) drawn on the slide master or its layouts are not converted")
            }
            return SlideDeck(width, height, slides, notes.toList())
        }

        // ----------------------------------------------------------- inheritance

        private fun readMaster(document: Document?, partName: String?): MasterInfo {
            val root = document?.documentElement
                ?: return MasterInfo(emptyMap(), emptyMap(), 0)
            val clrMap = HashMap<String, String>()
            root.first("clrMap")?.let { map ->
                for (name in listOf("bg1", "tx1", "bg2", "tx2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")) {
                    map.value(name)?.let { clrMap[name] = it }
                }
            }
            // Everything below reads colours through the master's colour map.
            theme = theme.withClrMap(clrMap)
            val styles = HashMap<String, Map<Int, PartialParagraph>>()
            val txStyles = root.first("txStyles")
            for (name in listOf("titleStyle", "bodyStyle", "otherStyle")) {
                val levels = HashMap<Int, PartialParagraph>()
                val style = txStyles?.first(name)
                for (level in 1..9) {
                    style?.first("lvl${level}pPr")?.let { levels[level] = readParagraphProperties(it, theme, notes) }
                }
                if (levels.isNotEmpty()) {
                    styles[name.removeSuffix("Style").lowercase()] = levels
                }
            }
            val parts = partsOf(root, emptyMap())
            return MasterInfo(
                styles = styles,
                placeholders = parts.placeholders,
                decoration = parts.decoration
            ).also { info ->
                info.background = backgroundOf(
                    root.first("cSld")?.first("bg"),
                    rels(partName ?: ""),
                    baseDir(partName ?: "")
                )
            }
        }

        private fun layout(part: String?): LayoutInfo {
            val document = part?.let { package_.documents[it] } ?: return LayoutInfo(emptyMap(), null, 0)
            val root = document.documentElement ?: return LayoutInfo(emptyMap(), null, 0)
            val parts = partsOf(root, master.placeholders)
            val background = backgroundOf(root.first("cSld")?.first("bg"), rels(part), baseDir(part))
            return LayoutInfo(parts.placeholders, background, parts.decoration)
        }

        private class Parts(
            val placeholders: Map<String, PlaceholderInfo>,
            val decoration: Int
        )

        /**
         * Placeholders and decoration counts of a master or layout part. A
         * layout's placeholders read their missing pieces from [fallback] (the
         * master's), so the layout only overrides what it states itself.
         */
        private fun partsOf(root: Element, fallback: Map<String, PlaceholderInfo>): Parts {
            val placeholders = HashMap<String, PlaceholderInfo>()
            var decoration = 0
            for (sp in root.descendants("spTree").firstOrNull()?.childElements().orEmpty()) {
                when (sp.localName) {
                    "sp", "pic" -> {
                        val ph = sp.descendants("ph").firstOrNull()
                        if (ph == null) {
                            decoration++
                            continue
                        }
                        val key = placeholderKey(ph)
                        placeholders[key] = placeholderInfo(sp, fallback[key] ?: typeFallback(fallback, key))
                    }
                    "graphicFrame", "cxnSp", "grpSp" -> decoration++
                }
            }
            return Parts(placeholders, decoration)
        }

        private fun placeholderKey(ph: Element): String {
            ph.value("idx")?.let { return "idx$it" }
            return "type${ph.value("type") ?: "body"}"
        }

        /** The master placeholder an index-only layout placeholder points at. */
        private fun typeFallback(
            placeholders: Map<String, PlaceholderInfo>,
            key: String
        ): PlaceholderInfo? {
            if (!key.startsWith("idx")) return null
            return PLACEHOLDER_TYPES.firstNotNullOfOrNull { placeholders["type$it"] }
        }

        private fun placeholderInfo(element: Element, fallback: PlaceholderInfo?): PlaceholderInfo {
            val spPr = element.first("spPr")
            val txBody = element.first("txBody")
            val levels = HashMap<Int, PartialParagraph>()
            val lstStyle = txBody?.first("lstStyle")
            for (level in 1..9) {
                lstStyle?.first("lvl${level}pPr")?.let { levels[level] = readParagraphProperties(it, theme, notes) }
            }
            return PlaceholderInfo(
                box = readBox(spPr?.first("xfrm")) ?: fallback?.box,
                body = readBodyProperties(txBody?.first("bodyPr"), fallback?.body),
                levels = levels,
                paint = spPr ?: fallback?.paint
            )
        }

        /** The placeholder a shape refers to, with layout and master merged. */
        private fun placeholderOf(ph: Element, layout: LayoutInfo): PlaceholderInfo? {
            val key = placeholderKey(ph)
            val keys = ArrayList<String>()
            keys += key
            if (key.startsWith("idx")) keys += PLACEHOLDER_TYPES.map { "type$it" }
            val fromLayout = keys.firstNotNullOfOrNull { layout.placeholders[it] }
            val fromMaster = keys.firstNotNullOfOrNull { master.placeholders[it] }
            if (fromLayout == null && fromMaster == null) return null
            val levels = HashMap<Int, PartialParagraph>()
            for (level in 1..9) {
                val l = fromLayout?.levels?.get(level)
                val m = fromMaster?.levels?.get(level)
                if (l != null || m != null) {
                    levels[level] = (l ?: PartialParagraph()).over(m ?: PartialParagraph())
                }
            }
            return PlaceholderInfo(
                box = fromLayout?.box ?: fromMaster?.box,
                body = fromLayout?.body ?: fromMaster?.body,
                levels = levels,
                paint = fromLayout?.paint ?: fromMaster?.paint
            )
        }

        /** The master text style a placeholder follows. */
        private fun masterStyle(ph: Element?, hasPlaceholder: Boolean): Map<Int, PartialParagraph> {
            // A shape that is not a placeholder takes the master's "other" style,
            // with the theme's object defaults filled in underneath.
            if (!hasPlaceholder) return under(master.styles["other"] ?: emptyMap(), theme.objectDefaults)
            return when (ph?.value("type")) {
                "title", "ctrTitle" -> master.styles["title"] ?: master.styles["body"] ?: emptyMap()
                else -> master.styles["body"] ?: emptyMap()
            }
        }

        /** [upper] with [lower] filled in underneath, property by property. */
        private fun under(
            upper: Map<Int, PartialParagraph>,
            lower: Map<Int, PartialParagraph>
        ): Map<Int, PartialParagraph> {
            if (lower.isEmpty()) return upper
            val merged = HashMap(upper)
            for ((level, paragraph) in lower) {
                merged[level] = (merged[level] ?: PartialParagraph()).over(paragraph)
            }
            return merged
        }

        // ------------------------------------------------------------- one slide

        private fun slide(part: String, number: Int, hidden: Boolean): SlideContent {
            val document = package_.documents[part]
            val root = document?.documentElement
                ?: throw PptxToPdfException("slide $number is missing from the package")
            val slideRels = rels(part)
            val layoutPart = targetOf(slideRels, baseDir(part), "slideLayout")
            val layout = layout(part = layoutPart)
            val background = backgroundOf(root.first("cSld")?.first("bg"), slideRels, baseDir(part))
                ?: layout.background
                ?: master.background

            if (root.first("transition") != null) notes.add("slide transitions are not converted")
            if (root.first("timing") != null) notes.add("slide animations are not converted")

            val items = ArrayList<SlideItem>()
            val spTree = root.first("cSld")?.first("spTree")
            walk(spTree?.childElements().orEmpty(), Transform.IDENTITY, layout, slideRels, part, items)
            layoutDecoration += layout.decoration
            return SlideContent(number, hidden, background, items)
        }

        private fun walk(
            elements: List<Element>,
            transform: Transform,
            layout: LayoutInfo,
            rels: Document?,
            part: String,
            into: MutableList<SlideItem>
        ) {
            for (element in elements) {
                when (element.localName) {
                    "sp" -> shape(element, transform, layout, into)
                    "cxnSp" -> connector(element, transform, layout, into)
                    "pic" -> picture(element, transform, layout, rels, part)?.let { into += it }
                    "grpSp" -> {
                        val nested = transform.child(element.first("grpSpPr")?.first("xfrm"))
                        val rotation = element.first("grpSpPr")?.first("xfrm")?.value("rot")
                            ?.trim()?.toDoubleOrNull() ?: 0.0
                        if (rotation != 0.0) notes.add("a rotated group is drawn upright")
                        walk(element.childElements(), nested, layout, rels, part, into)
                    }
                    "graphicFrame" -> graphicFrame(element)
                    "contentPart" -> notes.add("an embedded content part is not converted")
                    else -> {}
                }
            }
        }

        private fun graphicFrame(element: Element) {
            val graphicData = element.descendants("graphicData").firstOrNull()
            val uri = graphicData?.value("uri") ?: return
            when {
                uri.endsWith("/chart") -> notes.add("a chart is not converted")
                uri.endsWith("/table") -> notes.add("a table on a slide is not converted")
                uri.endsWith("/diagram") -> notes.add("SmartArt is not converted")
                uri.contains("/ole") -> notes.add("an embedded object is not converted")
                else -> notes.add("a '${uri.substringAfterLast('/')}' object is not converted")
            }
        }

        // ---------------------------------------------------------------- shapes

        private fun shape(
            element: Element,
            transform: Transform,
            layout: LayoutInfo,
            into: MutableList<SlideItem>
        ) {
            val spPr = element.first("spPr")
            val ph = element.descendants("ph").firstOrNull()
            val info = ph?.let { placeholderOf(it, layout) }
            val box = readBox(spPr?.first("xfrm")) ?: info?.box
            if (box == null) {
                notes.add("a shape without a position was skipped")
                return
            }
            if (box.rotation != 0.0) notes.add("a rotated shape is drawn upright")

            val paint = paint(spPr, element.first("style"), info?.paint)
            val kind = kindOf(spPr, info?.paint)
            if (kind == null) return
            if (hasEffect(element)) notes.add(EFFECT_NOTE)

            val text = element.first("txBody")?.let {
                readText(
                    txBody = it,
                    placeholder = info,
                    style = masterStyle(ph, ph != null),
                    defaults = shapeDefaults(element.first("style"))
                )
            }
            into += SlideFigure(
                x = transform.x(box.x),
                y = transform.y(box.y),
                width = box.width * transform.scaleX,
                height = box.height * transform.scaleY,
                kind = kind,
                fill = paint.fill,
                line = paint.line,
                lineWidth = paint.lineWidth,
                text = text
            )
        }

        private fun connector(
            element: Element,
            transform: Transform,
            layout: LayoutInfo,
            into: MutableList<SlideItem>
        ) {
            val spPr = element.first("spPr")
            val ph = element.descendants("ph").firstOrNull()
            val info = ph?.let { placeholderOf(it, layout) }
            val box = readBox(spPr?.first("xfrm")) ?: info?.box
            if (box == null) {
                notes.add("a connector without a position was skipped")
                return
            }
            val preset = spPr?.first("prstGeom")?.value("prst")
            if (preset != null && preset != "line" && preset != "straightConnector1") {
                notes.add("a '$preset' connector is not converted")
                return
            }
            val paint = paint(spPr, element.first("style"), info?.paint)
            if (hasEffect(element)) notes.add(EFFECT_NOTE)
            into += SlideFigure(
                x = transform.x(box.x),
                y = transform.y(box.y),
                width = box.width * transform.scaleX,
                height = box.height * transform.scaleY,
                kind = SlideFigureKind.LINE,
                fill = null,
                line = paint.line ?: PdfColor.BLACK,
                lineWidth = paint.lineWidth,
                flipH = box.flipH,
                flipV = box.flipV
            )
        }

        private fun picture(
            element: Element,
            transform: Transform,
            layout: LayoutInfo,
            rels: Document?,
            part: String
        ): SlideItem? {
            val nvPr = element.first("nvPicPr")?.first("nvPr")
            if (nvPr?.first("videoFile") != null || nvPr?.first("audioFile") != null) {
                notes.add("a video or audio clip is not converted")
                return null
            }
            val spPr = element.first("spPr")
            val ph = nvPr?.first("ph")
            val info = ph?.let { placeholderOf(it, layout) }
            val box = readBox(spPr?.first("xfrm")) ?: info?.box
            if (box == null) {
                notes.add("a picture without a position was skipped")
                return null
            }
            if (box.rotation != 0.0) notes.add("a rotated picture is drawn upright")
            val blipFill = element.first("blipFill")
            val image = imageOf(blipFill, rels, baseDir(part)) ?: return null
            val crop = blipFill?.first("srcRect")
            if (crop != null && listOf("l", "t", "r", "b").any { (crop.value(it)?.toDoubleOrNull() ?: 0.0) != 0.0 }) {
                notes.add("a cropped picture is drawn uncropped")
            }
            if (blipFill?.first("tile") != null) {
                notes.add("a tiled picture is stretched over its frame")
            }
            return SlidePicture(
                x = transform.x(box.x),
                y = transform.y(box.y),
                width = box.width * transform.scaleX,
                height = box.height * transform.scaleY,
                image = image
            )
        }

        /** The picture behind a `a:blipFill`. */
        private fun imageOf(blipFill: Element?, rels: Document?, baseDir: String): PdfImage? {
            val embed = blipFill?.first("blip")?.r("embed") ?: return null
            val target = relTarget(rels, baseDir, embed)
            if (target == null) {
                notes.add("a picture has no relationship and was skipped")
                return null
            }
            return images.load(target)
        }

        // ---------------------------------------------------------------- paints

        private class Paint(val fill: PdfColor?, val line: PdfColor?, val lineWidth: Double)

        private fun paint(spPr: Element?, style: Element?, inherited: Element?): Paint {
            // Properties inherit one at a time: a slide shape may state its own
            // fill and take the outline from the layout's placeholder.
            val fillElement = (spPr?.childElements().orEmpty() + inherited?.childElements().orEmpty())
                .firstOrNull { it.localName in FILL_KINDS }
            val fill = when (fillElement?.localName) {
                "solidFill" -> parentColour(fillElement, theme)
                "noFill" -> null
                "gradFill" -> {
                    notes.add("a gradient fill is drawn as its first colour")
                    firstStop(fillElement)
                }
                "pattFill" -> {
                    notes.add("a pattern fill is drawn as its foreground colour")
                    parentColour(fillElement.first("fgClr"), theme)
                }
                "blipFill" -> {
                    notes.add("a shape with a picture fill is drawn without a fill")
                    null
                }
                else -> styleFill(style)
            }
            var width = 0.75
            var line: PdfColor? = null
            val ln = spPr?.first("ln") ?: inherited?.first("ln")
            if (ln != null) {
                width = emu(ln.value("w")) ?: 0.75
                val lnFill = ln.childElements().firstOrNull {
                    it.localName in setOf("solidFill", "noFill", "gradFill", "pattFill")
                }
                line = when (lnFill?.localName) {
                    "noFill" -> null
                    "solidFill" -> parentColour(lnFill, theme)
                    "gradFill" -> {
                        notes.add("a gradient outline is drawn as its first colour")
                        firstStop(lnFill)
                    }
                    "pattFill" -> parentColour(lnFill.first("fgClr"), theme)
                    else -> lineStyle(style)
                }
                if (line != null) {
                    val dash = ln.first("prstDash")?.value("val")
                    if (dash != null && dash != "solid") notes.add("a dashed outline is drawn solid")
                    if (ln.first("custDash") != null) notes.add("a custom outline dash is drawn solid")
                }
            } else {
                val ref = style?.first("lnRef")
                val index = ref?.value("idx")?.trim()?.toIntOrNull() ?: 0
                if (ref != null && index > 0) {
                    line = parentColour(ref, theme)
                    width = 0.5
                }
            }
            return Paint(fill, line, width)
        }

        /** A shape that states no fill takes the theme's `fillRef`. */
        private fun styleFill(style: Element?): PdfColor? {
            val ref = style?.first("fillRef") ?: return null
            val index = ref.value("idx")?.trim()?.toIntOrNull() ?: 0
            if (index <= 0) return null
            return parentColour(ref, theme)
        }

        private fun lineStyle(style: Element?): PdfColor? =
            parentColour(style?.first("lnRef"), theme) ?: PdfColor.BLACK

        private fun firstStop(gradient: Element): PdfColor? =
            parentColour(gradient.first("gsLst")?.first("gs"), theme)

        /** True when a shape asks for a theme effect or states one of its own. */
        private fun hasEffect(element: Element): Boolean {
            val ref = element.first("style")?.first("effectRef")
            val index = ref?.value("idx")?.trim()?.toIntOrNull() ?: 0
            if (index in theme.effectStyles) return true
            return element.first("spPr")?.first("effectLst")?.firstChildElement() != null
        }

        /** The run defaults a shape's `p:style` asks for. */
        private fun shapeDefaults(style: Element?): PartialFormat {
            val ref = style?.first("fontRef")
            val family = when (ref?.value("idx")) {
                "major" -> theme.majorFont ?: theme.minorFont
                "minor" -> theme.minorFont ?: theme.majorFont
                "none" -> null
                else -> theme.minorFont
            }
            return PartialFormat(family = family, color = ref?.let { parentColour(it, theme) })
        }

        private fun kindOf(spPr: Element?, inherited: Element?): SlideFigureKind? {
            val geometry = (spPr ?: inherited)?.first("prstGeom")
            val custom = (spPr ?: inherited)?.first("custGeom")
            if (geometry == null && custom != null) {
                notes.add("a custom shape is not converted")
                return null
            }
            return when (geometry?.value("prst")) {
                null, "rect" -> SlideFigureKind.RECT
                "roundRect" -> {
                    notes.add("a rounded rectangle is drawn with square corners")
                    SlideFigureKind.ROUND_RECT
                }
                "ellipse", "circle" -> SlideFigureKind.ELLIPSE
                "line", "straightConnector1" -> SlideFigureKind.LINE
                else -> {
                    notes.add("a '${geometry.value("prst")}' shape is not converted")
                    null
                }
            }
        }

        // ------------------------------------------------------------------ text

        private fun readText(
            txBody: Element,
            placeholder: PlaceholderInfo?,
            style: Map<Int, PartialParagraph>,
            defaults: PartialFormat
        ): SlideText? {
            val bodyPr = txBody.first("bodyPr")
            val autoFit = bodyPr?.first("normAutofit")
            if (autoFit != null && (autoFit.value("fontScale") != null || autoFit.value("lnSpcReduction") != null)) {
                notes.add("text shrunk to fit its box is drawn at its full size")
            }
            val levels = HashMap<Int, PartialParagraph>()
            val lstStyle = txBody.first("lstStyle")
            for (level in 1..9) {
                lstStyle?.first("lvl${level}pPr")?.let { levels[level] = readParagraphProperties(it, theme, notes) }
            }

            val paragraphs = ArrayList<SlideParagraph>()
            val counters = HashMap<Int, Int>()
            var sawField = false
            for (p in txBody.kids("p")) {
                val pPr = p.first("pPr")
                val level = pPr?.value("lvl")?.trim()?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                val chain = readParagraphProperties(pPr, theme, notes)
                    .over(levels[level + 1] ?: PartialParagraph())
                    .over(placeholder?.levels?.get(level + 1) ?: PartialParagraph())
                    .over(style[level + 1] ?: PartialParagraph())

                val runs = ArrayList<SlideRun>()
                for (child in p.childElements()) {
                    when (child.localName) {
                        "r" -> {
                            val format = resolveFormat(child.first("rPr"), chain.format, defaults)
                            val text = child.first("t")?.textContent ?: (child.textContent ?: "")
                            runs += SlideRun(plainText(text), format)
                        }
                        "br" -> {
                            val format = resolveFormat(child.first("rPr"), chain.format, defaults)
                            runs += SlideRun("\n", format)
                        }
                        "fld" -> {
                            sawField = true
                            val format = resolveFormat(child.first("rPr"), chain.format, defaults)
                            val text = child.first("t")?.textContent ?: ""
                            runs += SlideRun(plainText(text), format)
                        }
                        "endParaRPr" -> {}
                    }
                }

                val base = resolveFormat(null, chain.format, defaults)
                val size = runs.filter { it.text.isNotBlank() }.maxOfOrNull { it.format.size } ?: base.size
                val marker = marker(chain.bullet, level, counters)
                paragraphs += SlideParagraph(
                    level = level,
                    align = chain.align ?: SlideAlign.LEFT,
                    marginLeft = chain.marginLeft ?: 0.0,
                    indent = chain.indent ?: 0.0,
                    spaceBefore = chain.spaceBefore ?: (chain.spaceBeforePercent ?: 0.0) * size,
                    spaceAfter = chain.spaceAfter ?: (chain.spaceAfterPercent ?: 0.0) * size,
                    lineSpacingMultiple = chain.lineMultiple,
                    lineSpacingPoints = chain.linePoints,
                    marker = marker,
                    base = base,
                    size = size,
                    runs = runs
                )
            }
            if (sawField) notes.add("a field is drawn with the text the file last saved for it")
            if (paragraphs.all { paragraph -> paragraph.runs.all { it.text.isBlank() } && paragraph.marker == null }) {
                return null
            }
            val body = readBodyProperties(bodyPr, placeholder?.body)
            return SlideText(
                leftInset = body.left,
                topInset = body.top,
                rightInset = body.right,
                bottomInset = body.bottom,
                anchor = body.anchor,
                wrap = body.wrap,
                paragraphs = paragraphs
            )
        }

        private fun resolveFormat(
            element: Element?,
            chain: PartialFormat?,
            defaults: PartialFormat
        ): SlideRunFormat {
            val merged = (readRunFormat(element, theme, notes) ?: PartialFormat())
                .over(chain ?: PartialFormat())
                .over(defaults)
            return SlideRunFormat(
                family = merged.family ?: theme.minorFont,
                size = merged.size ?: 18.0,
                bold = merged.bold == true,
                italic = merged.italic == true,
                underline = merged.underline == true,
                strike = merged.strike == true,
                color = merged.color ?: PdfColor.BLACK
            )
        }

        /** The marker for a paragraph, keeping a counter per outline level. */
        private fun marker(bullet: Bullet?, level: Int, counters: HashMap<Int, Int>): String? = when (bullet) {
            null -> null
            Bullet.None -> null
            is Bullet.Char -> bullet.text
            is Bullet.Auto -> {
                val value = counters.getOrPut(level) { bullet.start }
                counters[level] = value + 1
                counters.keys.filter { it > level }.forEach { counters.remove(it) }
                autoNumber(bullet.type, value)
            }
        }

        // ---------------------------------------------------------------- images

        private val images = object {
            private val loaded = HashMap<String, PdfImage?>()

            /** Loads and decodes picture parts once each. */
            fun load(partName: String): PdfImage? {
                if (loaded.containsKey(partName)) return loaded[partName]
                val bytes = package_.parts[partName]
                val image = if (bytes == null) {
                    notes.add("a picture part is missing from the package: $partName")
                    null
                } else {
                    decode(partName, bytes)
                }
                loaded[partName] = image
                return image
            }

            private fun decode(partName: String, bytes: ByteArray): PdfImage? {
                if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
                    val info = PdfImages.jpegInfo(bytes)
                    if (info == null) {
                        notes.add("a JPEG picture could not be read and was skipped")
                        return null
                    }
                    return PdfImage(jpeg = bytes, name = partName)
                }
                if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) {
                    return when (val result = PngReader.read(bytes)) {
                        is PngResult.Ok -> PdfImage(raster = result.image, name = partName)
                        is PngResult.Failed -> {
                            notes.add("a PNG picture was skipped (${result.reason})")
                            null
                        }
                    }
                }
                val extension = partName.substringAfterLast('.', "").uppercase()
                notes.add("a ${if (extension.isEmpty()) "picture" else extension} picture is not converted")
                return null
            }
        }

        // --------------------------------------------------------------- helpers

        private fun backgroundOf(bg: Element?, rels: Document?, baseDir: String): SlideBackground? {
            if (bg == null) return null
            bg.first("bgPr")?.let { properties ->
                val solid = parentColour(properties.first("solidFill"), theme)
                val image = properties.first("blipFill")?.let { imageOf(it, rels, baseDir) }
                if (solid != null || image != null) return SlideBackground(solid, image)
                properties.first("gradFill")?.let { gradient ->
                    notes.add("a gradient background is drawn as its first colour")
                    return SlideBackground(firstStop(gradient), null)
                }
                return null
            }
            bg.first("bgRef")?.let { ref ->
                val index = ref.value("idx")?.trim()?.toIntOrNull() ?: 0
                if (index > 1001) notes.add("a themed background is drawn as its first colour")
                return SlideBackground(parentColour(ref, theme), null)
            }
            return null
        }

        private fun readBox(xfrm: Element?): Box? {
            val off = xfrm?.first("off") ?: return null
            val ext = xfrm.first("ext") ?: return null
            val rotation = (xfrm.value("rot")?.trim()?.toDoubleOrNull() ?: 0.0) / 60000.0
            return Box(
                x = emu(off.value("x")) ?: 0.0,
                y = emu(off.value("y")) ?: 0.0,
                width = emu(ext.value("cx")) ?: 0.0,
                height = emu(ext.value("cy")) ?: 0.0,
                rotation = rotation,
                flipH = xfrm.boolValue("flipH") == true,
                flipV = xfrm.boolValue("flipV") == true
            )
        }

        /**
         * Insets, anchor and wrap of a text body. Attributes inherit one by one
         * from [fallback] — the layout's or master's placeholder — so a slide
         * that only drops an empty `a:bodyPr` still gets the placeholder's
         * centring. Missing pieces take the DrawingML defaults of §21.1.2.4.
         */
        private fun readBodyProperties(bodyPr: Element?, fallback: Body? = null): Body {
            val defaults = fallback ?: Body(7.2, 3.6, 7.2, 3.6, "t", true)
            if (bodyPr == null) return defaults
            return Body(
                left = emu(bodyPr.value("lIns")) ?: defaults.left,
                top = emu(bodyPr.value("tIns")) ?: defaults.top,
                right = emu(bodyPr.value("rIns")) ?: defaults.right,
                bottom = emu(bodyPr.value("bIns")) ?: defaults.bottom,
                anchor = bodyPr.value("anchor") ?: defaults.anchor,
                wrap = when (bodyPr.value("wrap")) {
                    "none" -> false
                    null -> defaults.wrap
                    else -> true
                }
            )
        }

        private fun rels(part: String): Document? {
            if (part.isEmpty()) return null
            val slash = part.lastIndexOf('/')
            val name = if (slash < 0) {
                "_rels/$part.rels"
            } else {
                "${part.substring(0, slash)}/_rels/${part.substring(slash + 1)}.rels"
            }
            return package_.documents[name]
        }

        private fun baseDir(part: String): String = part.substringBeforeLast('/', "")

        private fun targetOf(rels: Document?, baseDir: String, typeSuffix: String): String? {
            val root = rels?.documentElement ?: return null
            for (relationship in root.childElements()) {
                if (relationship.localName != "Relationship") continue
                if (!relationship.getAttribute("Type").endsWith("/$typeSuffix")) continue
                if (relationship.getAttribute("TargetMode") == "External") return null
                return PackageCheck.resolveTarget(baseDir, relationship.getAttribute("Target"))
            }
            return null
        }

        private fun relTarget(rels: Document?, baseDir: String, id: String): String? {
            val root = rels?.documentElement ?: return null
            for (relationship in root.childElements()) {
                if (relationship.localName != "Relationship") continue
                if (relationship.getAttribute("Id") != id) continue
                if (relationship.getAttribute("TargetMode") == "External") {
                    notes.add("an externally linked part is not converted")
                    return null
                }
                return PackageCheck.resolveTarget(baseDir, relationship.getAttribute("Target"))
            }
            return null
        }
    }

    // ------------------------------------------------------------- small types

    private class Box(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
        val rotation: Double,
        val flipH: Boolean,
        val flipV: Boolean
    )

    private class Body(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val anchor: String,
        val wrap: Boolean
    )

    /** What a slide master or layout says about one placeholder. */
    private class PlaceholderInfo(
        val box: Box?,
        val body: Body?,
        val levels: Map<Int, PartialParagraph>,
        /** The `p:spPr` element, for geometry and paint inheritance. */
        val paint: Element?
    )

    private class LayoutInfo(
        val placeholders: Map<String, PlaceholderInfo>,
        val background: SlideBackground?,
        val decoration: Int
    )

    private class MasterInfo(
        val styles: Map<String, Map<Int, PartialParagraph>>,
        val placeholders: Map<String, PlaceholderInfo>,
        val decoration: Int
    ) {
        var background: SlideBackground? = null
    }

    /** A group's child-to-page transform. */
    private class Transform(
        val dx: Double,
        val dy: Double,
        val scaleX: Double,
        val scaleY: Double
    ) {
        fun x(value: Double): Double = dx + value * scaleX
        fun y(value: Double): Double = dy + value * scaleY

        /** The transform inside a group whose `a:xfrm` is [xfrm]. */
        fun child(xfrm: Element?): Transform {
            if (xfrm == null) return this
            val off = xfrm.first("off") ?: return this
            val ext = xfrm.first("ext") ?: return this
            val chOff = xfrm.first("chOff") ?: return this
            val chExt = xfrm.first("chExt") ?: return this
            val offX = emu(off.value("x")) ?: 0.0
            val offY = emu(off.value("y")) ?: 0.0
            val extX = emu(ext.value("cx")) ?: 0.0
            val extY = emu(ext.value("cy")) ?: 0.0
            val chOffX = emu(chOff.value("x")) ?: 0.0
            val chOffY = emu(chOff.value("y")) ?: 0.0
            val chExtX = emu(chExt.value("cx")) ?: 0.0
            val chExtY = emu(chExt.value("cy")) ?: 0.0
            val scaleX = if (chExtX > 0.0) extX / chExtX else 1.0
            val scaleY = if (chExtY > 0.0) extY / chExtY else 1.0
            val nextX = this.scaleX * scaleX
            val nextY = this.scaleY * scaleY
            return Transform(
                dx = x(offX) - chOffX * nextX,
                dy = y(offY) - chOffY * nextY,
                scaleX = nextX,
                scaleY = nextY
            )
        }

        companion object {
            val IDENTITY = Transform(0.0, 0.0, 1.0, 1.0)
        }
    }
}
