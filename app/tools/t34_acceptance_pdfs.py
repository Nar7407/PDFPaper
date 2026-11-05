"""T34 acceptance fixtures.

Writes the two documents read aloud is judged on:

* t34-paper.pdf - A4, two columns, title and abstract, three outline entries,
  a figure block, running page numbers.
* t34-book.pdf - A5 book pages with large bold chapter headings and no outline,
  so chapters come from the T33 heading fallback.

Run from the repo root:

    python app/tools/t34_acceptance_pdfs.py

The files land in app/src/androidTest/assets/t34/ and build/t34-acceptance/.
"""
from pathlib import Path

import pymupdf

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app" / "src" / "androidTest" / "assets" / "t34"
BUILD = ROOT / "build" / "t34-acceptance"

A4 = pymupdf.paper_rect("a4")
MARGIN = 56
GUTTER = 24
COLUMN = (A4.width - 2 * MARGIN - GUTTER) / 2

ABSTRACT = (
    "Reading a two-column paper aloud is an ordering problem before it is a "
    "speech problem. The eye follows the left column to its foot and then "
    "returns to the top of the right column, while the page itself stores the "
    "text in whatever order the producer chose."
)

LEFT = [
    ("Introduction", [
        "The reading service must speak the left column first, from its "
        "heading to its last line, and only then move to the right column.",
        "A full-width title is not split across the columns. It is spoken once, "
        "before either column, and it may become a chapter of its own.",
        "Page numbers and running heads are furniture. They are dropped by the "
        "reading-order layout and must never be spoken in the middle of a "
        "paragraph.",
    ]),
    ("Method", [
        "Each text block is tokenised into words. Every word keeps the "
        "rectangles the highlight layer paints while it is being spoken.",
    ]),
]

RIGHT = [
    ("Results", [
        "The spoken word advances through the sentence, and the amber box "
        "follows it. A word split by a hyphen at a line break is spoken once "
        "and highlighted on both lines.",
        "A figure has no words to speak. The reader is told that the figure was "
        "skipped instead of hearing a description invented for it.",
    ]),
    ("Discussion", [
        "Chapters come from the document outline when it has one. The three "
        "headings in this paper are also entries in the outline, so the sheet "
        "can list them by name.",
    ]),
]


def columns(page, blocks, x, y, width, height):
    for heading, paragraphs in blocks:
        box = pymupdf.Rect(x, y, x + width, y + height)
        page.insert_textbox(box, heading, fontsize=13, fontname="hebo")
        y += 22
        for paragraph in paragraphs:
            box = pymupdf.Rect(x, y, x + width, y + 160)
            page.insert_textbox(box, paragraph, fontsize=9.5, lineheight=1.55)
            y += 78


def paper(path):
    doc = pymupdf.open()
    page = doc.new_page(width=A4.width, height=A4.height)
    page.insert_textbox(
        pymupdf.Rect(MARGIN, MARGIN, A4.width - MARGIN, MARGIN + 34),
        "Listening to a Two-Column Page",
        fontsize=20,
        fontname="hebo",
        align=1,
    )
    page.insert_textbox(
        pymupdf.Rect(MARGIN + 40, MARGIN + 40, A4.width - MARGIN - 40, MARGIN + 110),
        ABSTRACT,
        fontsize=9.5,
        lineheight=1.5,
    )
    top = MARGIN + 130
    columns(page, LEFT, MARGIN, top, COLUMN, 560)
    columns(page, RIGHT, MARGIN + COLUMN + GUTTER, top, COLUMN, 560)
    # The figure: a framed block with a caption, no words to read.
    figure = pymupdf.Rect(
        MARGIN + COLUMN + GUTTER,
        A4.height - 210,
        A4.width - MARGIN,
        A4.height - 120,
    )
    page.draw_rect(figure, color=(0.2, 0.2, 0.2), fill=(0.9, 0.9, 0.9))
    page.insert_textbox(
        pymupdf.Rect(figure.x0, figure.y1 + 6, figure.x1, figure.y1 + 30),
        "Figure 1. A figure with no spoken text.",
        fontsize=8,
    )
    page.insert_textbox(
        pymupdf.Rect(MARGIN, A4.height - 42, A4.width - MARGIN, A4.height - 20),
        "1",
        fontsize=9,
        align=1,
    )
    doc.set_toc(
        [
            [1, "Introduction", 1],
            [1, "Method", 1],
            [1, "Results", 1],
            [1, "Discussion", 1],
        ]
    )
    doc.save(path)
    doc.close()


BOOK = [
    ("Chapter One: The Lamp", [
        "The lamp was still burning when the ferry reached the far bank. Nobody "
        "on the pier moved, and the water kept its own slow account of the night.",
        "A reader who listens rather than looks needs the words to arrive in "
        "order. One paragraph should follow the last without a pause long "
        "enough to suggest that the reading has stopped.",
    ]),
    ("Chapter Two: Morning", [
        "By morning the fog had lifted, and the town looked smaller than it had "
        "in the dark. The ferry pulled away with nobody aboard but the crew.",
        "The second chapter exists so the chapter list has somewhere to go. A "
        "sleep timer set to the end of a chapter must stop here, not in the "
        "middle of the next page.",
    ]),
    ("Chapter Three: The Crossing", [
        "The crossing back was quiet. The same water, the same pier, and a "
        "different reader waiting on the bank.",
        "Headings become chapters when a document has no outline. A large bold "
        "line is the signal; running text set in the same size is not.",
    ]),
]


def book(path):
    doc = pymupdf.open()
    for number, (heading, paragraphs) in enumerate(BOOK, start=1):
        page = doc.new_page(width=420, height=595)
        page.insert_textbox(
            pymupdf.Rect(48, 56, 420 - 48, 110),
            heading,
            fontsize=18,
            fontname="hebo",
        )
        y = 128
        for paragraph in paragraphs:
            box = pymupdf.Rect(48, y, 420 - 48, y + 120)
            page.insert_textbox(box, paragraph, fontsize=11, lineheight=1.6)
            y += 110
        page.insert_textbox(
            pymupdf.Rect(48, 595 - 40, 420 - 48, 595 - 20),
            str(number),
            fontsize=9,
            align=1,
        )
    doc.save(path)
    doc.close()


if __name__ == "__main__":
    for folder in (ASSETS, BUILD):
        folder.mkdir(parents=True, exist_ok=True)
        paper(folder / "t34-paper.pdf")
        book(folder / "t34-book.pdf")
        print(f"wrote {folder / 't34-paper.pdf'}")
        print(f"wrote {folder / 't34-book.pdf'}")
