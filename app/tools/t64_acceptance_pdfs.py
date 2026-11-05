"""T64 acceptance fixtures: readable documents for the background reader.

Writes build/t64-acceptance/t64-page.pdf, t64-chapter.pdf and t64-outline.pdf.

The page is one short paragraph, so a reading starts speaking at once. The
chapter is two pages with several paragraphs each, so a reading crosses a page
break while the screen is off. The outline carries the same text with a table of
contents, so the notification can name the chapter instead of the page.

Run from the repo root:

    python app/tools/t64_acceptance_pdfs.py
"""
from pathlib import Path

import pymupdf

OUT = Path(__file__).resolve().parents[2] / "build" / "t64-acceptance"
A4 = pymupdf.paper_rect("a4")
MARGIN = 64
TEXT_WIDTH = A4.width - 2 * MARGIN

CHAPTER_ONE = [
    "The lamp was still burning when the ferry reached the far bank. "
    "Nobody on the pier moved, and the water kept its own slow account of the "
    "night.",
    "A reader who listens rather than looks needs the words to arrive in order. "
    "One paragraph should follow the last without a pause long enough to "
    "suggest that the reading has stopped.",
    "Page turns are the hard part. The screen is off, the room is dark, and "
    "the voice must not stumble when it leaves the bottom of one page and "
    "arrives at the top of the next.",
]

CHAPTER_TWO = [
    "By morning the fog had lifted, and the town looked smaller than it had in "
    "the dark. The ferry pulled away with nobody aboard but the crew.",
    "The second page exists so a reading can be heard crossing the break. It "
    "should carry on from the last paragraph of the first page without "
    "repeating a sentence and without skipping one.",
]


def write_pages(doc, pages):
    """Lay [pages] of [paragraphs] on A4, one text block per paragraph."""
    for paragraphs in pages:
        page = doc.new_page(width=A4.width, height=A4.height)
        y = MARGIN
        for paragraph in paragraphs:
            box = pymupdf.Rect(MARGIN, y, MARGIN + TEXT_WIDTH, y + 108)
            page.insert_textbox(box, paragraph, fontsize=13, lineheight=1.6)
            y += 132


def page(path):
    """One short paragraph: the reading speaks almost immediately."""
    doc = pymupdf.open()
    write_pages(doc, [["T64 acceptance: the page is short so a reading starts "
                       "speaking at once. Tap Read aloud, then turn the screen "
                       "off and the same sentence keeps going."]])
    doc.save(path)
    doc.close()


def chapter(path):
    """Two pages of paragraphs: a reading crosses a page break."""
    doc = pymupdf.open()
    write_pages(doc, [CHAPTER_ONE, CHAPTER_TWO])
    doc.save(path)
    doc.close()


def outline(path):
    """The chapter with a table of contents, so the notification names it."""
    doc = pymupdf.open()
    write_pages(doc, [CHAPTER_ONE, CHAPTER_TWO])
    doc.set_toc([[1, "The far bank", 1], [1, "Morning", 2]])
    doc.save(path)
    doc.close()


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    page(OUT / "t64-page.pdf")
    chapter(OUT / "t64-chapter.pdf")
    outline(OUT / "t64-outline.pdf")
    for name in ("t64-page.pdf", "t64-chapter.pdf", "t64-outline.pdf"):
        print(f"wrote {OUT / name}")
