"""T59 acceptance fixtures: a paper, a newsletter, a book and a slide deck.

Writes /tmp/t59-paper.pdf, /tmp/t59-newsletter.pdf, /tmp/t59-book.pdf and
/tmp/t59-deck.pdf.

These four are what the column detector and the column-flow reading mode are
judged on:

* the paper is a two-column article with a full-width title and abstract, a
  full-width figure between the columns and running heads plus page numbers;
* the newsletter is a three-column page with a spanning headline and a
  spanning advert box;
* the book is a plain single-column page with a header and a page number;
* the deck is a landscape slide with a title and one block of text.

Nothing in them is unusual for a PDF reader, and the flow mode never writes
into a file: it only decides which rectangle of the page to show.
"""
import pymupdf

A4 = pymupdf.paper_rect("a4")
SLIDE = pymupdf.paper_rect("a4-l")


def text(page, x, y, s, size=10, bold=False, colour=(0, 0, 0)):
    page.insert_text(
        (x, y),
        s,
        fontsize=size,
        fontname="hebo" if bold else "helv",
        color=colour,
    )


def column(page, x, width, top, bottom, lines, size=9.5):
    """Fills one column with [lines] of body text, wrapped at [width]."""
    y = top
    for line in lines:
        words = line.split()
        row = ""
        for word in words:
            candidate = word if not row else row + " " + word
            if pymupdf.get_text_length(candidate, fontname="helv", fontsize=size) > width:
                text(page, x, y, row, size)
                y += size * 1.45
                row = word
            else:
                row = candidate
        if row:
            text(page, x, y, row, size)
            y += size * 1.45
        if y > bottom:
            break
    return y


BODY = [
    "The measurement of small displacements in a rotating frame has been studied "
    "for more than a century, and the literature is correspondingly large.",
    "We report a method that needs no reference mark on the moving part, which "
    "removes the largest source of error in the earlier designs.",
    "The instrument was calibrated against a laser interferometer and the residual "
    "drift over one hour stayed below two micrometres.",
    "A second run, with the frame deliberately unbalanced, reproduced the same "
    "figures within the stated tolerance, which we take as evidence that the "
    "method does not depend on a lucky alignment.",
    "Finally, the cost of the parts is low enough that a teaching laboratory can "
    "build several of them from a single order.",
]


def paper(path):
    """Two columns, a spanning title and abstract, a spanning figure."""
    doc = pymupdf.open()
    for number in range(1, 3):
        page = doc.new_page(width=A4.width, height=A4.height)
        # Running head and page number: furniture the reader never reads.
        text(page, 56, 42, "Journal of Small Instruments 12 (2026) 1-8", 8,
             colour=(0.35, 0.35, 0.35))
        text(page, A4.width - 70, 42, str(number), 8, colour=(0.35, 0.35, 0.35))

        # Full-width title and abstract (they cross both gutters).
        text(page, 56, 92, "A reference-free method for small displacements", 17, True)
        text(page, 56, 110, "A. Reader and B. Writer", 10)
        text(page, 56, 132, "Abstract", 9.5, True)
        column(page, 56, A4.width - 112, 146, 190,
               ["We describe an instrument that measures the small displacements of a "
                "rotating frame without any mark on the moving part, and we compare "
                "it with a laser interferometer over one hour of continuous use."])

        # A full-width figure that must not be cut by the column boxes.
        page.draw_rect(pymupdf.Rect(56, 210, A4.width - 56, 320), color=(0.2, 0.2, 0.2))
        page.draw_line(pymupdf.Point(70, 300), pymupdf.Point(A4.width - 70, 240),
                       color=(0.1, 0.1, 0.6), width=2)
        text(page, 56, 336, "Figure 1. The frame and the sensor.", 8)

        gutter = 18
        width = (A4.width - 112 - gutter) / 2
        left = 56
        right = 56 + width + gutter
        bottom = A4.height - 70
        # The left column ends early so the right column is taller: a reader who
        # goes by height alone would start on the wrong side.
        column(page, left, width, 360, bottom - 120, BODY)
        column(page, right, width, 360, bottom, BODY + BODY)

        text(page, 56, A4.height - 50, "Preprint, not for distribution", 7.5,
             colour=(0.4, 0.4, 0.4))
    doc.save(path)
    doc.close()


def newsletter(path):
    """Three columns, a spanning headline and a spanning advert box."""
    doc = pymupdf.open()
    page = doc.new_page(width=A4.width, height=A4.height)
    text(page, 40, 60, "THE WEEKLY", 30, True)
    text(page, 40, 76, "Newsletter of the riverside district  -  October 2026", 9,
         colour=(0.35, 0.35, 0.35))

    margin = 40
    gutter = 14
    width = (A4.width - margin * 2 - gutter * 2) / 3
    xs = [margin, margin + width + gutter, margin + (width + gutter) * 2]

    # A headline that spans all three columns.
    text(page, margin, 112, "The bridge reopens after two years of work", 16, True)
    column(page, margin, A4.width - margin * 2, 126, 160,
           ["After two years the old bridge carries traffic again, and the "
            "engineers who built it say it will outlast the road on both sides."])

    top = 180
    bottom = 520
    column(page, xs[0], width, top, bottom, BODY)
    column(page, xs[1], width, top, bottom, BODY)
    column(page, xs[2], width, top, bottom, BODY)

    # A spanning advert: it crosses all three gutters, so no column may cut it.
    page.draw_rect(pymupdf.Rect(margin, 560, A4.width - margin, 660),
                   color=(0.7, 0.1, 0.1), fill=(1.0, 0.94, 0.94))
    text(page, margin + 20, 600, "THE RIVERSIDE CAFE", 18, True, (0.7, 0.1, 0.1))
    text(page, margin + 20, 620, "Open every day from seven until late.", 10,
         colour=(0.5, 0.1, 0.1))

    column(page, xs[0], width, 680, A4.height - 50, BODY)
    column(page, xs[1], width, 680, A4.height - 50, BODY)
    column(page, xs[2], width, 680, A4.height - 50, BODY)
    doc.save(path)
    doc.close()


def book(path):
    """One column, a running head and a page number: the easy case."""
    doc = pymupdf.open()
    for number in range(1, 4):
        page = doc.new_page(width=A4.width, height=A4.height)
        text(page, 70, 60, "The Old Mill", 9, colour=(0.35, 0.35, 0.35))
        column(page, 70, A4.width - 140, 110, A4.height - 90, BODY + BODY, size=11)
        text(page, A4.width / 2, A4.height - 60, str(number), 10)
    doc.save(path)
    doc.close()


def deck(path):
    """A landscape slide: a title and one block of text, one column."""
    doc = pymupdf.open()
    page = doc.new_page(width=SLIDE.width, height=SLIDE.height)
    text(page, 60, 110, "Reading a two-column paper on a phone", 30, True)
    text(page, 60, 150, "Column flow", 16, colour=(0.2, 0.2, 0.5))
    column(page, 60, SLIDE.width - 120, 190, 310, BODY[:4], size=13)
    page.draw_rect(pymupdf.Rect(60, 330, SLIDE.width - 60, 360), color=None,
                   fill=(0.9, 0.9, 0.95))
    text(page, 70, 350, "One segment at a time, edge to edge.", 12)
    doc.save(path)
    doc.close()


paper("/tmp/t59-paper.pdf")
newsletter("/tmp/t59-newsletter.pdf")
book("/tmp/t59-book.pdf")
deck("/tmp/t59-deck.pdf")
print("wrote t59-paper.pdf, t59-newsletter.pdf, t59-book.pdf, t59-deck.pdf")
