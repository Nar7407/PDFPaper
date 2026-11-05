"""Builds the three sample office files the app tests use (dev tool, not shipped).

The files land in both test trees:

* app/src/test/resources/office/     - read by the JVM unit tests
* app/src/androidTest/assets/office/ - read on device by the instrumented tests
  (and handy for a manual "open this on a phone" pass)

Run with the dev Python that has python-docx and python-pptx installed:

    python app/tools/make_office_samples.py

The files are:

* notes.docx        - a simple Word document (heading, body text, bullets, a
                      small table) that the DOCX-to-PDF converter handles.
* deck.pptx         - a simple PowerPoint deck (title slide plus a bullet
                      slide) that the PPTX-to-PDF converter handles.
* legacy-sample.doc - a Word 97-2003 stub: it carries the OLE compound-file
                      signature and nothing else, which is exactly what the
                      app must refuse with "save it as .docx".
"""

from pathlib import Path

from docx import Document
from pptx import Presentation
from pptx.util import Inches, Pt

REPO = Path(__file__).resolve().parents[2]
UNIT_TREE = REPO / "app" / "src" / "test" / "resources" / "office"
ANDROID_TREE = REPO / "app" / "src" / "androidTest" / "assets" / "office"

OLE_SIGNATURE = bytes([0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1])


def make_docx(path: Path) -> None:
    document = Document()
    document.add_heading("Meeting notes", level=1)
    paragraph = document.add_paragraph()
    paragraph.add_run("Attendees: ").bold = True
    paragraph.add_run("Ada, Grace, Alan. The ")
    run = paragraph.add_run("release")
    run.italic = True
    paragraph.add_run(" is on track.")

    document.add_heading("Decisions", level=2)
    for item in (
        "Ship the PDF viewer with the office conversion.",
        "Keep the conversion on device; nothing is uploaded.",
        "Revisit fonts after the first field report.",
    ):
        document.add_paragraph(item, style="List Bullet")

    table = document.add_table(rows=2, cols=2)
    table.style = "Table Grid"
    table.cell(0, 0).text = "Owner"
    table.cell(0, 1).text = "Task"
    table.cell(1, 0).text = "Ada"
    table.cell(1, 1).text = "Write release notes"

    document.save(path)


def make_pptx(path: Path) -> None:
    deck = Presentation()
    title_layout = deck.slide_layouts[0]
    bullet_layout = deck.slide_layouts[1]

    cover = deck.slides.add_slide(title_layout)
    cover.shapes.title.text = "PDFPaper office support"
    cover.placeholders[1].text = "Word and PowerPoint open straight in the viewer"

    agenda = deck.slides.add_slide(bullet_layout)
    agenda.shapes.title.text = "What works"
    body = agenda.placeholders[1].text_frame
    body.text = "Open .docx and .pptx from the picker"
    for line in (
        "Convert to PDF on the device",
        "Read the conversion report in the viewer",
        "Save the PDF or send it to another app",
    ):
        item = body.add_paragraph()
        item.text = line

    for slide in deck.slides:
        for shape in slide.shapes:
            if shape.has_text_frame:
                for paragraph in shape.text_frame.paragraphs:
                    for run in paragraph.runs:
                        run.font.size = Pt(24 if slide is cover else 18)

    deck.save(path)


def make_doc_stub(path: Path) -> None:
    path.write_bytes(OLE_SIGNATURE + b"\x00" * 512)


def main() -> None:
    for tree in (UNIT_TREE, ANDROID_TREE):
        tree.mkdir(parents=True, exist_ok=True)
        make_docx(tree / "notes.docx")
        make_pptx(tree / "deck.pptx")
        make_doc_stub(tree / "legacy-sample.doc")
        print(f"wrote 3 samples to {tree}")


if __name__ == "__main__":
    main()
