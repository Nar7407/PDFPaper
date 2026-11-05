"""Generate the DOCX fixtures for the DOCX->PDF tests.

Dev-only tooling, like the other checkers in this directory: it uses
python-docx, which is an independent implementation of the format, so the
converter is tested against real WordprocessingML packages rather than only
against its own writer.

    python tools/make_docx_samples.py

writes five documents into src/test/resources/docx/:

  simple.docx      headings, bold/italic/underline, bullet and number lists,
                   a page break (two pages)
  formatting.docx  sizes, colours, alignment, super/subscript, strike, small
                   caps, character spacing, indents, custom tab stops, line
                   spacing
  table.docx       A4 page with 2 cm margins, a bordered table with a shaded
                   header cell and a merged cell, and a table wider than the
                   text area
  image.docx       one PNG at two sizes, a wide PNG, and a BMP the converter
                   does not support (the report must name it)
  sections.docx    heading levels, a manual line break, a page-break-before
                   paragraph, an empty paragraph, a long unbreakable word,
                   and a landscape second section

The pictures are written by hand (zlib + struct), so the script has no
dependency on an image library.
"""

from __future__ import annotations

import io
import os
import struct
import zlib

from docx import Document
from docx.enum.section import WD_ORIENT, WD_SECTION
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Inches, Pt, RGBColor


HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "test", "resources", "docx")


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


def bmp(width: int, height: int) -> bytes:
    """A 24-bit BMP: a format the converter deliberately does not decode."""
    row = (width * 3 + 3) & ~3
    pixels = bytearray()
    for y in range(height):
        for x in range(width):
            pixels += bytes((40, 80, 160))
        pixels += b"\x00" * (row - width * 3)
    header = b"BM" + struct.pack("<IHHI", 14 + 40 + len(pixels), 0, 0, 14 + 40)
    info = struct.pack("<IiiHHIIiiII", 40, width, height, 1, 24, 0, len(pixels), 2835, 2835, 0, 0)
    return header + info + bytes(pixels)


def add(paragraph, text: str, **font):
    run = paragraph.add_run(text)
    if font.get("bold"):
        run.bold = True
    if font.get("italic"):
        run.italic = True
    if font.get("underline"):
        run.underline = True
    if font.get("strike"):
        run.font.strike = True
    if font.get("size"):
        run.font.size = Pt(font["size"])
    if font.get("color"):
        run.font.color.rgb = RGBColor(*font["color"])
    if font.get("superscript"):
        run.font.superscript = True
    if font.get("subscript"):
        run.font.subscript = True
    if font.get("small_caps"):
        run.font.small_caps = True
    return run


def rpr_property(run, name: str, value: str) -> None:
    """Adds a raw `w:<name>` to a run's `w:rPr` (f.e. `w:spacing`)."""
    properties = run._element.get_or_add_rPr()
    element = OxmlElement(f"w:{name}")
    element.set(qn("w:val"), value)
    properties.append(element)


def simple() -> None:
    document = Document()
    document.add_heading("Quarterly Report", level=1)
    paragraph = document.add_paragraph()
    add(paragraph, "The quick brown fox jumps over the lazy dog. ")
    add(paragraph, "Bold", bold=True)
    add(paragraph, ", ")
    add(paragraph, "italic", italic=True)
    add(paragraph, " and ")
    add(paragraph, "underlined", underline=True)
    add(paragraph, " runs share one paragraph.")
    document.add_paragraph("Second paragraph, plain, with a little more text to fill the line width.")
    centred = document.add_paragraph()
    add(centred, "This line is centred.")
    centred.alignment = WD_ALIGN_PARAGRAPH.CENTER
    document.add_paragraph("Reminders:", style="List Bullet")
    document.add_paragraph("Milk and bread", style="List Bullet")
    document.add_paragraph("Pay the invoice", style="List Bullet")
    document.add_paragraph("Steps:", style="List Number")
    document.add_paragraph("Open the file", style="List Number")
    document.add_paragraph("Read the numbers", style="List Number")
    page_break = document.add_paragraph()
    page_break.add_run().add_break(WD_BREAK.PAGE)
    document.add_paragraph("Second page: the page break above ends the first page.")
    save(document, "simple.docx")


def formatting() -> None:
    document = Document()
    small = document.add_paragraph()
    add(small, "Eight point text.", size=8)
    add(small, "  Fourteen point text.", size=14)
    add(small, "  Twenty-four point text.", size=24)
    colours = document.add_paragraph()
    add(colours, "red ", color=(192, 0, 0))
    add(colours, "green ", color=(0, 128, 0))
    add(colours, "blue", color=(0, 0, 255))
    document.add_paragraph("A very long justified paragraph that is meant to wrap over at least two lines so that the "
                          "space between its words can be stretched by the layout, which is what justification does.",
                          style=None).alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    right = document.add_paragraph()
    add(right, "Right aligned, ends at the margin.")
    right.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    fancy = document.add_paragraph()
    add(fancy, "x")
    add(fancy, "2", superscript=True)
    add(fancy, " and H")
    add(fancy, "2", subscript=True)
    add(fancy, "O, struck text: ")
    add(fancy, "gone", strike=True)
    add(fancy, ", small caps: ")
    add(fancy, "smalltalk", small_caps=True)
    spaced = document.add_paragraph()
    run = add(spaced, "spread out letters")
    rpr_property(run, "spacing", "60")  # 3 pt per character
    indented = document.add_paragraph("Left indent half an inch, first line another quarter.")
    indented.paragraph_format.left_indent = Inches(0.5)
    indented.paragraph_format.first_line_indent = Inches(0.25)
    hanging = document.add_paragraph("Hanging indent: the first line starts left of the block.")
    hanging.paragraph_format.left_indent = Inches(0.75)
    hanging.paragraph_format.first_line_indent = Inches(-0.5)
    spaced_paragraph = document.add_paragraph("One and a half line spacing.")
    spaced_paragraph.paragraph_format.line_spacing = 1.5
    spaced_paragraph.paragraph_format.space_before = Pt(14)
    spaced_paragraph.paragraph_format.space_after = Pt(10)
    exact = document.add_paragraph("Exactly 26 points of line height.")
    exact.paragraph_format.line_spacing = Pt(26)
    tabs = document.add_paragraph()
    tabs.paragraph_format.tab_stops.add_tab_stop(Inches(1.5))
    add(tabs, "name:\tthe value\tand more")
    unknown = document.add_paragraph()
    add(unknown, "A family the converter cannot know: ")
    papyrus = add(unknown, "papyrus text")
    papyrus.font.name = "Papyrus"
    save(document, "formatting.docx")


def table() -> None:
    document = Document()
    section = document.sections[0]
    section.page_width = Cm(21.0)
    section.page_height = Cm(29.7)
    section.left_margin = Cm(2.0)
    section.right_margin = Cm(2.0)
    section.top_margin = Cm(1.5)
    section.bottom_margin = Cm(1.5)
    document.add_heading("Table of figures", level=2)

    grid = document.add_table(rows=4, cols=3)
    grid.style = "Table Grid"
    header = ("Name", "Count", "Notes")
    for column, title in enumerate(header):
        cell = grid.cell(0, column)
        cell.text = ""
        add(cell.paragraphs[0], title, bold=True)
        shade(cell, "DDDDDD")
    values = (
        ("Bolts", "12", "stainless"),
        ("Washers", "144", "pack of twelve"),
        ("Nuts", "9", "two spare bags"),
    )
    for row, (name, count, notes) in enumerate(values, start=1):
        grid.cell(row, 0).text = name
        grid.cell(row, 1).text = count
        cell = grid.cell(row, 2)
        cell.text = ""
        add(cell.paragraphs[0], notes)
        cell.add_paragraph("second line in the cell")

    merged = document.add_table(rows=2, cols=3)
    merged.style = "Table Grid"
    merged.cell(0, 0).text = "Merged"
    merged.cell(0, 1).text = "across"
    merged.cell(0, 2).text = "three"
    merged.cell(0, 0).merge(merged.cell(0, 2))
    merged.cell(1, 0).text = "left"
    merged.cell(1, 1).text = "middle"
    merged.cell(1, 2).text = "right"

    wide = document.add_table(rows=3, cols=6)
    wide.style = "Table Grid"
    for row in range(3):
        for column in range(6):
            wide.cell(row, column).text = f"c{row}{column} with a longer cell text"
    for column in wide.columns:
        column.width = Inches(1.6)
    document.add_paragraph("After the tables: a closing paragraph.")
    save(document, "table.docx")


def shade(cell, fill: str) -> None:
    properties = cell._tc.get_or_add_tcPr()
    shading = OxmlElement("w:shd")
    shading.set(qn("w:val"), "clear")
    shading.set(qn("w:color"), "auto")
    shading.set(qn("w:fill"), fill)
    properties.append(shading)


def image() -> None:
    document = Document()
    document.add_heading("Pictures", level=3)
    document.add_paragraph("An inline picture at 1.5 inches, then the same picture smaller:")
    picture = png(220, 120)
    document.add_picture(io.BytesIO(picture), width=Inches(1.5))
    document.add_picture(io.BytesIO(picture), width=Inches(0.75))
    document.add_paragraph("A wide picture fills most of the text width:")
    document.add_picture(io.BytesIO(png(360, 200, tint=(40, 90, 190))), width=Inches(6.0))
    document.add_paragraph("A picture the converter cannot decode is named in the report:")
    document.add_picture(io.BytesIO(bmp(40, 30)), width=Inches(1.2))
    document.add_paragraph("End of the picture document.")
    save(document, "image.docx")


def sections() -> None:
    document = Document()
    document.add_heading("Section one", level=1)
    document.add_heading("A sub-heading", level=2)
    document.add_heading("A sub-sub-heading", level=3)
    document.add_paragraph("A paragraph with a manual line break here:").add_run().add_break()
    document.add_paragraph("")
    document.add_paragraph("An unbreakable token follows: "
                          "https://example.invalid/a/very/long/path/without/any/spaces/that/has/to/wrap/"
                          "and/keeps/going/for/quite/a/while/before/it/stops/in/the/middle/of/a/long/word")
    document.add_paragraph("Ends the portrait section.")

    landscape = document.add_section(WD_SECTION.NEW_PAGE)
    landscape.page_width = Cm(29.7)
    landscape.page_height = Cm(21.0)
    landscape.orientation = WD_ORIENT.LANDSCAPE
    document.add_heading("Section two (landscape)", level=1)
    forced = document.add_paragraph("This paragraph asks for a page break before it.")
    forced.paragraph_format.page_break_before = True
    document.add_paragraph("Landscape body text, wide but short.")
    save(document, "sections.docx")


def save(document: Document, name: str) -> None:
    os.makedirs(OUT, exist_ok=True)
    path = os.path.abspath(os.path.join(OUT, name))
    document.save(path)
    print(f"wrote {path} ({os.path.getsize(path)} bytes)")


def main() -> None:
    simple()
    formatting()
    table()
    image()
    sections()


if __name__ == "__main__":
    main()
