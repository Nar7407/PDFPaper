"""T61 acceptance fixtures: a paper, a manual with page references and a book.

Writes /tmp/t61-paper.pdf, /tmp/t61-manual.pdf and /tmp/t61-book.pdf with real
text, numbered citations, a figure, a table, equations, outlines/bookmarks and
link annotations (explicit and named destinations).
"""
import pymupdf

A4 = pymupdf.paper_rect("a4")


def page(doc):
    return doc.new_page(width=A4.width, height=A4.height)


def text(p, x, y, s, size=11, bold=False):
    p.insert_text((x, y), s, fontsize=size, fontname="hebo" if bold else "helv")


def paper(path):
    doc = pymupdf.open()
    dests = pymupdf.open()  # placeholder for API symmetry

    # page 1: introduction with a citation list, a figure reference, a section
    p1 = page(doc)
    text(p1, 72, 80, "Link Previews in Document Readers", 20, True)
    text(p1, 72, 104, "A. Researcher, B. Engineer", 11)
    text(p1, 72, 140, "1  Introduction", 14, True)
    lines = [
        "Reading a paper on a phone often means losing your place. Jumping to a",
        "reference and back should be cheap, and the destination should be shown",
        "before the reader commits to the jump. See [1], [2] and [3, 5-6] for",
        "earlier work, and (Smith, 2019) for the original study.",
        "",
        "Figure 1 shows the card as it opens over the page. Table 1 lists the",
        "actions it offers. The method is described in Section 2.1, and the",
        "appendix material is on Page 3.",
        "",
        "The missing entry [9] proves that an unresolved reference is reported",
        "rather than guessed. Equation 1 defines the reading order.",
    ]
    y = 168
    for line in lines:
        text(p1, 72, y, line)
        y += 17
    text(p1, 72, y + 14, "x = a + b  (1)", 12)

    # page 2: references
    p2 = page(doc)
    text(p2, 72, 80, "References", 14, True)
    refs = [
        "[1] Smith, J. (2019). A Study of PDF Links. Journal of Documents, 12(3), 45-58.",
        "[2] Jones, A. (2020). Destinations and Reading Order. Proc. DocEng, 101-112.",
        "[3] Brown, B. (2021). Link Previews on Small Screens. Mobile HCI, 33-41.",
        "[4] Green, D. (2018). Back Stacks for Readers. UIST, 7-15.",
        "[5] White, C. (2022). Return Trips in Long Documents. CHI, 210-222.",
        "[6] Black, E. (2023). Citations as Interfaces. J. Reading, 5(1), 1-19.",
    ]
    y = 120
    for ref in refs:
        text(p2, 72, y, ref, 10)
        y += 26

    # page 3: figure, table and the methods heading
    p3 = page(doc)
    text(p3, 72, 80, "2.1  Methods", 14, True)
    text(p3, 72, 108, "We resolve the target, render a small preview and keep the", 11)
    text(p3, 72, 125, "reader's position on a back stack.", 11)
    # a simple figure: rounded box with a caption
    p3.draw_rect(pymupdf.Rect(72, 160, 360, 300), color=(0.2, 0.3, 0.6), width=1.5)
    text(p3, 110, 240, "preview card", 13)
    text(p3, 72, 320, "Figure 1: The preview card over a page.", 10)
    text(p3, 72, 360, "Table 1: Actions in the card", 10)
    rows = ["Action        Effect", "Go to         Jump and push the position",
            "Pin to side   Keep the preview open", "Copy text     Copy the entry"]
    y = 380
    for row in rows:
        text(p3, 72, y, row, 10)
        y += 16

    # named destination for page 3 plus an outline entry per section
    # A real GoTo annotation from the citation to the references page, added
    # once every page exists.
    doc[0].insert_link({
        "kind": pymupdf.LINK_GOTO,
        "from": pymupdf.Rect(338, 250, 362, 263),
        "page": 1,
        "to": pymupdf.Point(72, 100),
    })
    doc.set_toc([
        [1, "1 Introduction", 1],
        [1, "References", 2],
        [1, "2.1 Methods", 3],
    ])
    doc.save(path)


def manual(path):
    doc = pymupdf.open()
    chapters = [
        ("Chapter 1  Getting started", "Read the safety notes on Page 4 before you begin."),
        ("Chapter 2  Printing", "The print layout is covered in Section 3.2 and on Page 6."),
        ("Chapter 3  Maintenance", "Cleaning the rollers is described in Section 3.2."),
        ("Safety", "Always unplug the unit before cleaning."),
        ("Chapter 4  Troubleshooting", "If the paper jams, see Page 2 for the print path."),
        ("Appendix  Specifications", "The print area is 8.5 by 11 inches."),
    ]
    for title, body in chapters:
        p = page(doc)
        text(p, 72, 90, title, 16, True)
        text(p, 72, 130, body, 11)
        text(p, 72, 160, "This section is part of the reader's manual.", 11)
    doc.set_toc([[1, t, i + 1] for i, (t, _) in enumerate(chapters)])
    doc.save(path)


def book(path):
    doc = pymupdf.open()
    for i in range(1, 9):
        p = page(doc)
        text(p, 72, 90, f"Chapter {i}", 18, True)
        text(p, 72, 130, f"The story continues on the next page (Page {i + 1}).", 11)
        text(p, 72, 160, f"This is the body of chapter {i}.", 11)
    doc.set_toc([[1, f"Chapter {i}", i] for i in range(1, 9)])
    # a named destination link from chapter 1 to the last chapter
    doc[0].insert_link({
        "kind": pymupdf.LINK_GOTO,
        "from": pymupdf.Rect(72, 145, 320, 160),
        "page": 7,
        "to": pymupdf.Point(72, 90),
    })
    doc.save(path)


if __name__ == "__main__":
    paper("/tmp/t61-paper.pdf")
    manual("/tmp/t61-manual.pdf")
    book("/tmp/t61-book.pdf")
    for name in ("paper", "manual", "book"):
        d = pymupdf.open(f"/tmp/t61-{name}.pdf")
        links = sum(len(pg.get_links()) for pg in d)
        print(name, d.page_count, "pages,", len(d.get_toc()), "bookmarks,", links, "links")
