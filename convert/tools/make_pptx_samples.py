"""Generate the PPTX fixtures for the PPTX->PDF tests.

Dev-only tooling, like the other checkers in this directory: it uses
python-pptx, an independent implementation of the format, so the converter is
tested against real PresentationML packages rather than only against its own
writer.

    python tools/make_pptx_samples.py

writes five decks into src/test/resources/pptx/:

  simple.pptx      16:9, title and body placeholders with bullets on two slides
                   (slide order), and a plain text box on a third
  formatting.pptx  sizes, bold/italic/underline/strike, colours, alignment,
                   line spacing, bullets and automatic numbers, a manual line
                   break, a known and an unknown font family
  shapes.pptx      a filled rectangle with text, a rounded rectangle, an
                   ellipse with an outline, a straight connector, an outline-
                   only rectangle and a triangle (which must be reported)
  pictures.pptx    one PNG at two sizes (the same part twice), a second PNG,
                   and a text box
  mixed.pptx       a solid background, an image background, a chart, a table,
                   a movie, a slide transition, an animation and a hidden
                   slide: everything the converter must skip and report

The pictures are written by hand (zlib + struct), so the script has no
dependency on an image library.
"""

from __future__ import annotations

import io
import os
import struct
import zlib

from lxml import etree

from pptx import Presentation
from pptx.chart.data import CategoryChartData
from pptx.dml.color import RGBColor
from pptx.enum.chart import XL_CHART_TYPE
from pptx.enum.shapes import MSO_CONNECTOR, MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Inches, Pt


HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "test", "resources", "pptx")

BLANK = 6
TITLE_AND_CONTENT = 1


def png(width: int, height: int, tint=(200, 40, 40)) -> bytes:
    """A 24-bit PNG with a diagonal gradient, written without a library."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0
        for x in range(width):
            shade = (x + y) * 255 // max(1, width + height - 2)
            raw += bytes(
                (
                    (tint[0] + shade) // 2,
                    (tint[1] + shade) // 2,
                    (tint[2] + shade) // 2,
                )
            )

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def textbox(slide, left, top, width, height):
    box = slide.shapes.add_textbox(Inches(left), Inches(top), Inches(width), Inches(height))
    frame = box.text_frame
    frame.word_wrap = True
    return frame


def add_run(paragraph, text, *, size=None, bold=False, italic=False, underline=False,
            strike=False, color=None, name=None):
    r = paragraph.add_run()
    r.text = text
    if size:
        r.font.size = Pt(size)
    if bold:
        r.font.bold = True
    if italic:
        r.font.italic = True
    if underline:
        r.font.underline = True
    if color:
        r.font.color.rgb = RGBColor(*color)
    if name:
        r.font.name = name
    if strike:
        properties = r._r.get_or_add_rPr()
        element = etree.SubElement(properties, qn("a:strike"))
        element.set("val", "sngStrike")
    return r


def ppr(paragraph):
    return paragraph._p.get_or_add_pPr()


def bullet(paragraph, char: str = "\u2022") -> None:
    element = etree.SubElement(ppr(paragraph), qn("a:buChar"))
    element.set("char", char)


def auto_number(paragraph, kind: str = "arabicPeriod") -> None:
    element = etree.SubElement(ppr(paragraph), qn("a:buAutoNum"))
    element.set("type", kind)


def indent(paragraph, margin_in: float, indent_in: float) -> None:
    properties = ppr(paragraph)
    properties.set("marL", str(int(margin_in * 914400)))
    properties.set("indent", str(int(indent_in * 914400)))


def save(prs: Presentation, name: str) -> None:
    os.makedirs(OUT, exist_ok=True)
    path = os.path.abspath(os.path.join(OUT, name))
    prs.save(path)
    print(f"wrote {path} ({os.path.getsize(path)} bytes)")


def simple() -> None:
    prs = Presentation()
    prs.slide_width = Inches(13.333)
    prs.slide_height = Inches(7.5)

    first = prs.slides.add_slide(prs.slide_layouts[TITLE_AND_CONTENT])
    first.shapes.title.text = "Quarterly Review"
    body = first.placeholders[1]
    body.text_frame.text = "Revenue is up"
    body.text_frame.add_paragraph().text = "Costs are flat"
    body.text_frame.add_paragraph().text = "Third bullet"

    second = prs.slides.add_slide(prs.slide_layouts[TITLE_AND_CONTENT])
    second.shapes.title.text = "Second slide"
    second.placeholders[1].text_frame.text = "This slide comes second"

    third = prs.slides.add_slide(prs.slide_layouts[BLANK])
    frame = textbox(third, 1.0, 1.0, 6.0, 2.0)
    frame.text = "A plain text box on the third slide"
    save(prs, "simple.pptx")


def formatting() -> None:
    prs = Presentation()
    prs.slide_width = Inches(13.333)
    prs.slide_height = Inches(7.5)
    slide = prs.slides.add_slide(prs.slide_layouts[BLANK])

    sizes = textbox(slide, 0.5, 0.3, 9.0, 1.4)
    line = sizes.paragraphs[0]
    add_run(line, "Eight. ", size=8)
    add_run(line, "Fourteen. ", size=14)
    add_run(line, "Twenty-four.", size=24)

    styles = textbox(slide, 0.5, 1.8, 9.0, 1.2)
    line = styles.paragraphs[0]
    add_run(line, "bold ", bold=True)
    add_run(line, "italic ", italic=True)
    add_run(line, "underlined ", underline=True)
    add_run(line, "struck", strike=True)

    colours = textbox(slide, 0.5, 3.0, 9.0, 0.8)
    line = colours.paragraphs[0]
    add_run(line, "red ", color=(192, 0, 0))
    add_run(line, "green ", color=(0, 128, 0))
    add_run(line, "blue", color=(0, 0, 255))

    centred = textbox(slide, 0.5, 3.9, 9.0, 0.8)
    centred.paragraphs[0].alignment = PP_ALIGN.CENTER
    add_run(centred.paragraphs[0], "Centred text")

    right = textbox(slide, 0.5, 4.7, 9.0, 0.8)
    right.paragraphs[0].alignment = PP_ALIGN.RIGHT
    add_run(right.paragraphs[0], "Right aligned")

    justified = textbox(slide, 0.5, 5.5, 5.0, 1.4)
    justified.paragraphs[0].alignment = PP_ALIGN.JUSTIFY
    add_run(
        justified.paragraphs[0],
        "A long justified paragraph that has to wrap over more than one line "
        "so the spaces can be stretched.",
    )

    lists = textbox(slide, 5.8, 5.5, 4.0, 1.8)
    first = lists.paragraphs[0]
    bullet(first)
    indent(first, 0.3, -0.3)
    add_run(first, "First bullet")
    second = lists.add_paragraph()
    bullet(second)
    indent(second, 0.3, -0.3)
    add_run(second, "Second bullet")
    numbered = lists.add_paragraph()
    auto_number(numbered)
    indent(numbered, 0.3, -0.3)
    add_run(numbered, "Numbered")
    numbered2 = lists.add_paragraph()
    auto_number(numbered2)
    indent(numbered2, 0.3, -0.3)
    add_run(numbered2, "Numbered again")

    spacing = textbox(slide, 5.8, 4.6, 4.0, 0.9)
    spaced = spacing.paragraphs[0]
    spaced.line_spacing = 1.5
    spaced.space_before = Pt(12)
    spaced.space_after = Pt(6)
    add_run(spaced, "Line spacing and space before")

    fonts = textbox(slide, 0.5, 6.6, 9.0, 0.8)
    line = fonts.paragraphs[0]
    add_run(line, "Arial and ", name="Arial")
    add_run(line, "Papyrus text", name="Papyrus")

    breaks = textbox(slide, 9.6, 0.3, 3.2, 1.2)
    add_run(breaks.paragraphs[0], "first line\vsecond line")

    anchored = textbox(slide, 9.6, 1.8, 3.2, 1.8)
    anchored.vertical_anchor = MSO_ANCHOR.MIDDLE
    add_run(anchored.paragraphs[0], "Vertically centred")
    save(prs, "formatting.pptx")


def shapes() -> None:
    prs = Presentation()
    slide = prs.slides.add_slide(prs.slide_layouts[BLANK])

    rectangle = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.5), Inches(0.5), Inches(3.5), Inches(1.5))
    rectangle.fill.solid()
    rectangle.fill.fore_color.rgb = RGBColor(0xC0, 0x30, 0x30)
    rectangle.line.fill.background()
    rectangle.text_frame.text = "In the box"

    rounded = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(4.5), Inches(0.5), Inches(3.0), Inches(1.5))
    rounded.fill.solid()
    rounded.fill.fore_color.rgb = RGBColor(0x30, 0x60, 0xC0)
    rounded.line.color.rgb = RGBColor(0x10, 0x20, 0x40)
    rounded.line.width = Pt(2)

    oval = slide.shapes.add_shape(MSO_SHAPE.OVAL, Inches(7.0), Inches(0.5), Inches(2.5), Inches(2.5))
    oval.fill.solid()
    oval.fill.fore_color.rgb = RGBColor(0x30, 0xA0, 0x40)
    oval.line.color.rgb = RGBColor(0x10, 0x40, 0x10)
    oval.line.width = Pt(3)

    outline = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.5), Inches(2.5), Inches(2.0), Inches(1.0))
    outline.fill.background()
    outline.line.color.rgb = RGBColor(0x80, 0x80, 0x00)
    outline.line.width = Pt(2)

    connector = slide.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(0.5), Inches(4.0), Inches(4.0), Inches(5.5))
    connector.line.color.rgb = RGBColor(0x00, 0x00, 0x80)
    connector.line.width = Pt(3)

    triangle = slide.shapes.add_shape(MSO_SHAPE.ISOSCELES_TRIANGLE, Inches(5.0), Inches(3.0), Inches(2.0), Inches(1.5))
    triangle.fill.solid()
    triangle.fill.fore_color.rgb = RGBColor(0xAA, 0xAA, 0xAA)

    label = textbox(slide, 7.0, 4.5, 2.8, 1.0)
    label.text = "Shapes"
    save(prs, "shapes.pptx")


def pictures() -> None:
    prs = Presentation()
    slide = prs.slides.add_slide(prs.slide_layouts[BLANK])

    first = png(220, 120)
    slide.shapes.add_picture(io.BytesIO(first), Inches(0.5), Inches(0.5), width=Inches(3.0))
    slide.shapes.add_picture(io.BytesIO(first), Inches(0.5), Inches(2.1), width=Inches(1.5))
    slide.shapes.add_picture(io.BytesIO(png(360, 200, tint=(40, 90, 190))), Inches(4.5), Inches(0.5), width=Inches(4.0))

    label = textbox(slide, 0.5, 3.6, 6.0, 0.8)
    label.text = "Pictures"
    save(prs, "pictures.pptx")


def mixed() -> None:
    prs = Presentation()
    prs.slide_width = Inches(10)
    prs.slide_height = Inches(7.5)

    first = prs.slides.add_slide(prs.slide_layouts[BLANK])
    first.background.fill.solid()
    first.background.fill.fore_color.rgb = RGBColor(0xFF, 0xF2, 0xCC)
    frame = textbox(first, 0.5, 0.5, 5.0, 1.0)
    frame.text = "Coloured background"

    second = prs.slides.add_slide(prs.slide_layouts[BLANK])
    picture = png(320, 200, tint=(30, 80, 60))
    image_part, rId = second.part.get_or_add_image_part(io.BytesIO(picture))
    cSld = second._element.find(qn("p:cSld"))
    background = etree.Element(qn("p:bg"))
    properties = etree.SubElement(background, qn("p:bgPr"))
    blip_fill = etree.SubElement(properties, qn("a:blipFill"))
    blip = etree.SubElement(blip_fill, qn("a:blip"))
    blip.set(qn("r:embed"), rId)
    stretch = etree.SubElement(blip_fill, qn("a:stretch"))
    etree.SubElement(stretch, qn("a:fillRect"))
    etree.SubElement(properties, qn("a:effectLst"))
    cSld.insert(0, background)

    frame = textbox(second, 0.5, 6.0, 5.0, 1.0)
    frame.text = "Image background"

    chart_data = CategoryChartData()
    chart_data.categories = ["One", "Two", "Three"]
    chart_data.add_series("Series 1", (1.0, 2.0, 3.0))
    second.shapes.add_chart(
        XL_CHART_TYPE.COLUMN_CLUSTERED, Inches(5.5), Inches(0.5), Inches(4.0), Inches(3.0), chart_data
    )
    table_shape = second.shapes.add_table(2, 2, Inches(5.5), Inches(4.0), Inches(4.0), Inches(1.0))
    table_shape.table.cell(0, 0).text = "cell"

    movie = os.path.join(HERE, "sample-movie.mp4")
    with open(movie, "wb") as handle:
        handle.write(b"\x00\x00\x00\x18ftypmp42" + b"PDFPaper test movie placeholder".ljust(64, b"\x00"))
    try:
        second.shapes.add_movie(movie, Inches(0.5), Inches(4.5), Inches(2.0), Inches(1.5))
    finally:
        os.remove(movie)

    transition = etree.SubElement(second._element, qn("p:transition"))
    transition.set("spd", "fast")
    etree.SubElement(transition, qn("p:fade"))
    timing = etree.SubElement(second._element, qn("p:timing"))
    etree.SubElement(timing, qn("p:tnLst"))

    third = prs.slides.add_slide(prs.slide_layouts[BLANK])
    frame = textbox(third, 0.5, 0.5, 5.0, 1.0)
    frame.text = "Hidden slide"
    prs.part._element.find(qn("p:sldIdLst"))[-1].set("show", "0")
    save(prs, "mixed.pptx")


def main() -> None:
    simple()
    formatting()
    shapes()
    pictures()
    mixed()


if __name__ == "__main__":
    main()
