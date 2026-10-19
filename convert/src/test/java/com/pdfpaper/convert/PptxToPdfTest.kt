package com.pdfpaper.convert

import com.pdfpaper.convert.pdf.StandardFont
import com.pdfpaper.convert.pdf.StandardFonts
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.text.PdfTextRun
import com.pdfpaper.engine.text.extractText
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PPTX→PDF end to end. The inputs are real packages written by python-pptx (an
 * independent implementation of the format) and the outputs are re-opened with
 * the engine's own PDF reader, which checks the file the way a viewer would.
 * The minimal packages built here in Kotlin cover the cases no generator can
 * produce easily (a JPEG that must travel verbatim, a deck without slides).
 */
class PptxToPdfTest {

    private class RenderedPage(
        val width: Double,
        val height: Double,
        /** All runs of the page, joined without separators. */
        val text: String,
        val runs: List<PdfTextRun>
    ) {
        /** Every run of the page that contains [needle]. */
        fun runsWith(needle: String): List<PdfTextRun> = runs.filter { it.text.contains(needle) }

        /** The one run that contains [needle]; [needle] must fit in one run. */
        fun run(needle: String): PdfTextRun =
            runs.firstOrNull { it.text.contains(needle) }
                ?: error("no run contains \"$needle\" in: $text")

        /** The runs sharing the baseline of the run that contains [needle]. */
        fun lineOf(needle: String): List<PdfTextRun> {
            val y = run(needle).originY
            return runs.filter { abs(it.originY - y) < 0.2 }
        }

        /** The left and right edge of everything drawn on [needle]'s line. */
        fun spanOf(needle: String): Pair<Double, Double> {
            val line = lineOf(needle)
            return line.minOf { it.originX } to line.maxOf { it.endX }
        }

        fun has(needle: String): Boolean = text.contains(needle)
    }

    private fun sample(name: String): ByteArray =
        javaClass.getResourceAsStream("/pptx/$name")?.readBytes()
            ?: error("missing test resource /pptx/$name")

    private fun convert(name: String): PptxToPdfResult = PptxToPdf.convert(sample(name), sourceName = name)

    /** Opens the produced PDF with the engine and returns what it reads back. */
    private fun render(result: PptxToPdfResult): List<RenderedPage> =
        PdfDocument.open(ByteArraySource(result.bytes)).getOrThrow().use { document ->
            document.pages().map { page ->
                val extracted = page.extractText()
                assertTrue(
                    extracted.warnings.isEmpty(),
                    "the engine reported problems reading the produced PDF: ${extracted.warnings}"
                )
                val box = PageGeometry.boxOf(page)
                RenderedPage(
                    width = box.width,
                    height = box.height,
                    text = extracted.runs.joinToString("") { it.text },
                    runs = extracted.runs
                )
            }
        }

    private fun near(expected: Double, actual: Double, tolerance: Double = 1.5) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "expected $expected, got $actual (tolerance $tolerance)"
        )
    }

    /** The decompressed content streams of the produced PDF, one string per page. */
    private fun contentStreams(result: PptxToPdfResult): String =
        PdfDocument.open(ByteArraySource(result.bytes)).getOrThrow().use { document ->
            document.pages().joinToString("\n") { page ->
                page.contents().getOrThrow()?.bytes?.toString(Charsets.US_ASCII) ?: ""
            }
        }

    /** The file itself as text, for the dictionaries around the content streams. */
    private fun dictionaryText(result: PptxToPdfResult): String =
        String(result.bytes, Charsets.ISO_8859_1)

    // ------------------------------------------------------------------- tests

    @Test
    fun `a simple deck becomes one page per slide with title and bullets`() {
        val result = convert("simple.pptx")
        val pages = render(result)
        val report = result.report

        assertEquals(3, pages.size, "the deck has three slides")
        assertEquals(3, report.slides)
        assertEquals(5, report.shapes, "three master bullets, a title and a text box count on three slides")
        assertEquals(4, report.listItems, "three bullets on the first slide and one on the second")

        // 13.333 in x 7.5 in, the size every python-pptx presentation starts with.
        near(960.0, pages[0].width, 1.0)
        near(540.0, pages[0].height, 0.1)

        val first = pages[0]
        assertTrue(first.has("Quarterly Review"), "the title is missing: ${first.text}")
        near(44.0, first.run("Quarterly").fontSize, 0.1)
        assertTrue(first.has("Revenue is up"), "the body text is missing: ${first.text}")
        near(32.0, first.run("Revenue").fontSize, 0.1)

        // The title box is vertically centred on the master, so its baseline is
        // above the top-anchored body text.
        assertTrue(
            first.run("Quarterly").originY > first.run("Revenue").originY,
            "the title should sit above the body"
        )

        // The title is centred in its placeholder (36..684 pt).
        val (left, right) = first.spanOf("Quarterly")
        near(360.0, (left + right) / 2.0, 1.5)

        // Body style level 1 has a hanging bullet: the marker at the text area's
        // left edge, the text one 27 pt indent further in.
        val bullet = first.run("\u2022")
        near(43.2, bullet.originX, 0.5)
        near(27.0, first.run("Revenue").originX - bullet.originX, 0.5)

        assertTrue(pages[1].has("Second slide"), "the second slide is missing")
        assertTrue(pages[2].has("A plain text box on the third slide"), "the text box is missing")

        // Calibri is the theme's minor font and not a standard font.
        assertTrue(
            report.notes.any { it.contains("Calibri") && it.contains("Helvetica") },
            "the font substitution should be reported: ${report.notes}"
        )
        assertTrue(report.hasLosses)
    }

    @Test
    fun `run formatting, alignment, bullets and line breaks reach the PDF`() {
        val result = convert("formatting.pptx")
        val page = render(result).single()

        // Three sizes in one paragraph share a line: the tallest sets its height.
        near(8.0, page.run("Eight").fontSize, 0.1)
        near(14.0, page.run("Fourteen").fontSize, 0.1)
        near(24.0, page.run("Twenty-four").fontSize, 0.1)
        assertTrue(
            page.lineOf("Eight").any { it.text.contains("Twenty-four") },
            "the three sizes should sit on one line"
        )

        // Bold and italic pick the matching standard font.
        val fonts = dictionaryText(result)
        assertTrue(fonts.contains("/Helvetica-Bold"), "bold text should use a bold font")
        assertTrue(fonts.contains("/Helvetica-Oblique"), "italic text should use an italic font")

        // Boxes run from 0.5 to 9.5 in with the default 7.2 pt insets.
        val (centredLeft, centredRight) = page.spanOf("Centred")
        near(360.0, (centredLeft + centredRight) / 2.0, 1.5)
        near(676.8 - StandardFonts.width(StandardFont.HELVETICA, "Right aligned", 18.0), page.run("Right").originX, 1.5)

        // The justified paragraph fills its 0.5..5.5 in box; its last line does
        // not. The bullet list on the right shares the first line's baseline.
        near(388.8, page.lineOf("justified").filter { it.originX < 400.0 }.maxOf { it.endX }, 1.5)
        assertTrue(
            page.lineOf("stretched.").filter { it.originX < 400.0 }.maxOf { it.endX } < 380.0,
            "the last line of a justified paragraph must not be stretched"
        )

        // Bullets and auto numbers are drawn in the hanging indent, before the text.
        repeat(2) { index ->
            val marker = page.runs.filter { it.text == "\u2022" }[index]
            near(424.8, marker.originX, 0.5)
        }
        near(446.4, page.runs.filter { it.text == "Second" }[0].originX, 0.5)
        assertEquals(listOf("1.", "2."), page.runs.filter { it.text.endsWith(".") && it.text.length == 2 }.map { it.text })
        near(424.8, page.run("1.").originX, 0.5)

        // A vertical tab in the run text is a manual line break (`_x000B_`).
        val firstLine = page.run("first").originY
        val secondLine = page.run("second").originY
        assertTrue(secondLine < firstLine, "the second half of the manual break is not below the first")
        near(20.25, firstLine - secondLine, 0.5)

        // 1.5 line spacing and 12 pt of space before separate the last paragraph.
        val spaced = page.run("Line").originY - page.run("Numbered").originY
        assertTrue(spaced > 40.0, "line spacing and space before should widen the gap, got $spaced")

        val notes = result.report.notes
        assertTrue(notes.any { it.contains("Papyrus") }, "an unknown font should be named: $notes")
        assertTrue(notes.any { it.contains("Arial") }, "a known but non-standard font should be reported: $notes")
        assertTrue(notes.any { it.contains("Helvetica-Bold") }, "the bold substitution should be reported: $notes")
    }

    @Test
    fun `shapes draw their fills, outlines, ellipse and connector`() {
        val result = convert("shapes.pptx")
        val page = render(result).single()

        assertEquals(6, result.report.shapes, "rect, rounded rect, oval, outline, connector and triangle")
        assertTrue(page.has("In the box"), "the shape text is missing: ${page.text}")
        assertTrue(page.has("Shapes"), "the label is missing")
        // The box is left-aligned in the file: left edge + 7.2 pt inset.
        near(43.2, page.run("In").originX, 0.5)

        val notes = result.report.notes
        assertTrue(notes.any { it.contains("rounded rectangle") }, "the round corner should be reported: $notes")
        assertTrue(notes.any { it.contains("triangle") }, "an unknown preset should be reported: $notes")

        val content = contentStreams(result)
        // The red rectangle C03030, its outline removed by a:noFill.
        assertTrue(content.contains("0.753 0.188 0.188 rg"), "the red fill is missing")
        assertTrue(!content.contains("noFill"), "the content stream is broken")
        // The ellipse is four Béziers with a fill and a stroke (a:ln w=3pt).
        assertEquals(4, content.split(" c\n").size - 1, "one ellipse should be four curves")
        assertTrue(content.contains("RG\nB\n"), "the ellipse should be both filled and stroked")
        assertTrue(content.contains("3 w 0.063 0.251 0.063 RG"), "the ellipse's outline is missing")
        // The connector is a stroked segment with round caps.
        assertTrue(content.contains("1 J 1 j"), "the line should set round caps and joins")
        assertTrue(content.contains(" l S\n"), "the connector should be a stroked segment")
    }

    @Test
    fun `pictures are embedded, scaled and read back`() {
        val result = convert("pictures.pptx")
        val page = render(result).single()

        assertEquals(3, result.report.pictures)
        assertTrue(page.has("Pictures"), "the label is missing")

        val ascii = dictionaryText(result)
        assertEquals(2, Regex("/Subtype /Image").findAll(ascii).count(), "the first PNG is used twice")
        assertTrue(ascii.contains("/FlateDecode"), "the PNG pixels should be deflated")
        assertTrue(!ascii.contains("/DCTDecode"), "a PNG is not a JPEG")

        // The same image twice is one XObject drawn twice, the third is its own.
        val content = contentStreams(result)
        assertEquals(3, Regex("/Im\\d+ Do").findAll(content).count(), "three picture placements")
    }

    @Test
    fun `backgrounds, hidden slides and skipped objects are reported`() {
        val result = convert("mixed.pptx")
        val pages = render(result)

        assertEquals(3, pages.size, "the hidden slide is included")
        near(720.0, pages[0].width, 1.0)
        near(540.0, pages[0].height, 0.1)
        assertEquals(1, result.report.pictures, "the second slide's picture background")

        assertTrue(pages[0].has("Coloured background"))
        assertTrue(pages[1].has("Image background"))
        assertTrue(pages[2].has("Hidden slide"), "the hidden slide's text is still drawn")

        // The solid background is a full-page rule in FFF2CC.
        val content = contentStreams(result)
        assertTrue(content.contains("1 0.949 0.8 rg"), "the slide's own background colour is missing")
        assertTrue(content.contains("/Im1 Do"), "the picture background is missing")

        val notes = result.report.notes
        for (expected in listOf("transition", "animation", "chart", "table", "video", "hidden")) {
            assertTrue(notes.any { it.contains(expected) }, "$expected should be reported: $notes")
        }
    }

    @Test
    fun `a JPEG picture is embedded verbatim as DCTDecode`() {
        val jpeg = jpegBytes()
        val archive = pptx(
            "[Content_Types].xml" to contentTypes(
                defaults = mapOf(
                    "rels" to "application/vnd.openxmlformats-package.relationships+xml",
                    "xml" to "application/xml",
                    "jpg" to "image/jpeg"
                ),
                overrides = mapOf(
                    "ppt/presentation.xml" to
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
                    "ppt/slides/slide1.xml" to
                        "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
                )
            ),
            "_rels/.rels" to relationships(
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>"""
            ),
            "ppt/presentation.xml" to presentation(
                """<p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst>"""
            ),
            "ppt/_rels/presentation.xml.rels" to relationships(
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>"""
            ),
            "ppt/slides/slide1.xml" to
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <p:cSld><p:spTree>
                    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                    <p:grpSpPr/>
                    <p:pic>
                      <p:nvPicPr><p:cNvPr id="2" name="Picture 1"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
                      <p:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
                      <p:spPr>
                        <a:xfrm><a:off x="914400" y="914400"/><a:ext cx="2743200" cy="1828800"/></a:xfrm>
                        <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                      </p:spPr>
                    </p:pic>
                    <p:sp>
                      <p:nvSpPr><p:cNvPr id="3" name="TextBox 1"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
                      <p:spPr>
                        <a:xfrm><a:off x="914400" y="3657600"/><a:ext cx="2743200" cy="914400"/></a:xfrm>
                        <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                      </p:spPr>
                      <p:txBody>
                        <a:bodyPr wrap="square"/><a:lstStyle/>
                        <a:p><a:r><a:rPr lang="en-US"/><a:t>JPEG</a:t></a:r></a:p>
                      </p:txBody>
                    </p:sp>
                  </p:spTree></p:cSld>
                </p:sld>""",
            "ppt/slides/_rels/slide1.xml.rels" to relationships(
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image1.jpg"/>"""
            ),
            "ppt/media/image1.jpg" to jpeg
        )

        val result = PptxToPdf.convert(archive, sourceName = "jpeg.pptx")
        val pages = render(result)

        assertEquals(1, pages.size)
        assertEquals(1, result.report.pictures)
        assertTrue(pages[0].has("JPEG"))

        val ascii = dictionaryText(result)
        assertTrue(ascii.contains("/DCTDecode"), "the JPEG should stay a JPEG")
        assertTrue(ascii.contains("/Width 32") && ascii.contains("/Height 24"), "the JPEG's size is missing")
        assertTrue(
            indexOf(result.bytes, jpeg) >= 0,
            "the JPEG bytes should be embedded verbatim"
        )
    }

    @Test
    fun `a slide without a master still converts`() {
        val archive = pptx(
            "[Content_Types].xml" to contentTypes(
                defaults = mapOf(
                    "rels" to "application/vnd.openxmlformats-package.relationships+xml",
                    "xml" to "application/xml"
                ),
                overrides = mapOf(
                    "ppt/presentation.xml" to
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
                    "ppt/slides/slide1.xml" to
                        "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
                )
            ),
            "_rels/.rels" to relationships(
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>"""
            ),
            "ppt/presentation.xml" to presentation(
                """<p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst>"""
            ),
            "ppt/_rels/presentation.xml.rels" to relationships(
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>"""
            ),
            "ppt/slides/slide1.xml" to
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <p:cSld><p:spTree>
                    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                    <p:grpSpPr/>
                    <p:sp>
                      <p:nvSpPr><p:cNvPr id="2" name="TextBox 1"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
                      <p:spPr>
                        <a:xfrm><a:off x="914400" y="914400"/><a:ext cx="5486400" cy="914400"/></a:xfrm>
                        <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                      </p:spPr>
                      <p:txBody>
                        <a:bodyPr wrap="square"/><a:lstStyle/>
                        <a:p><a:r><a:t>Bare slide</a:t></a:r></a:p>
                      </p:txBody>
                    </p:sp>
                  </p:spTree></p:cSld>
                </p:sld>"""
        )

        val result = PptxToPdf.convert(archive, sourceName = "bare.pptx")
        val page = render(result).single()
        assertTrue(page.has("Bare slide"))
        assertTrue(result.report.notes.none { it.contains("font") }, "no font should be substituted as 'Calibri'")
    }

    @Test
    fun `something that is not a package is refused with a clear message`() {
        val error = assertFailsWith<PptxToPdfException> {
            PptxToPdf.convert("this is not a package".toByteArray())
        }
        assertTrue(error.message!!.isNotBlank())
    }

    @Test
    fun `a presentation without slides is refused`() {
        val archive = pptx(
            "[Content_Types].xml" to contentTypes(
                defaults = mapOf(
                    "rels" to "application/vnd.openxmlformats-package.relationships+xml",
                    "xml" to "application/xml"
                ),
                overrides = mapOf(
                    "ppt/presentation.xml" to
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
                )
            ),
            "_rels/.rels" to relationships(
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>"""
            ),
            "ppt/presentation.xml" to presentation("""<p:sldIdLst/>""")
        )
        val error = assertFailsWith<PptxToPdfException> { PptxToPdf.convert(archive) }
        assertTrue(error.message!!.contains("no slides"), "unexpected message: ${error.message}")
    }

    // ----------------------------------------------------------------- helpers

    /** A small JPEG written by ImageIO, for the verbatim-DCT test. */
    private fun jpegBytes(): ByteArray {
        val image = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 24) {
            for (x in 0 until 32) image.setRGB(x, y, (x * 8 shl 16) or (y * 8 shl 8) or 0x40)
        }
        val out = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, "jpg", out), "no JPEG writer is available")
        return out.toByteArray()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    private fun presentation(slideIds: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          $slideIds
          <p:sldSz cx="9144000" cy="6858000"/>
        </p:presentation>"""

    private fun contentTypes(defaults: Map<String, String>, overrides: Map<String, String>): String {
        val defaultsXml = defaults.entries.joinToString("") { (extension, type) ->
            """<Default Extension="$extension" ContentType="$type"/>"""
        }
        val overridesXml = overrides.entries.joinToString("") { (part, type) ->
            """<Override PartName="/$part" ContentType="$type"/>"""
        }
        return """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">$defaultsXml$overridesXml</Types>"""
    }

    private fun relationships(vararg entries: String): String =
        """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${entries.joinToString("")}</Relationships>"""

    private fun pptx(vararg parts: Pair<String, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(
                    when (content) {
                        is ByteArray -> content
                        else -> content.toString().toByteArray(Charsets.UTF_8)
                    }
                )
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
