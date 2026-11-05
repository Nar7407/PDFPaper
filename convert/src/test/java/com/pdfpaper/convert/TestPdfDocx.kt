package com.pdfpaper.convert

import java.nio.charset.StandardCharsets

/**
 * Builds a PDF with one content stream per page. Objects: 1 catalog, 2 pages,
 * then three per page (page, content, Helvetica font), so the xref stays valid
 * whatever the fixture does.
 */
fun pagesPdf(pageContents: List<String>, width: Int = 612, height: Int = 792): ByteArray {
    val objects = ArrayList<TestObj>()
    val kids = ArrayList<String>()
    val pageNumbers = ArrayList<Int>()
    val fontNumbers = ArrayList<Int>()
    var next = 3
    for (index in pageContents.indices) {
        pageNumbers += next
        fontNumbers += next + 2
        next += 3
    }
    for (number in pageNumbers) kids += "$number 0 R"
    objects += TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>")
    objects += TestObj(
        2, 0,
        "<< /Type /Pages /Kids [${kids.joinToString(" ")}] /Count ${pageContents.size} >>"
    )
    for (index in pageContents.indices) {
        val content = pageContents[index].toByteArray(StandardCharsets.US_ASCII)
        val page = pageNumbers[index]
        objects += TestObj(
            page, 0,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] " +
                "/Resources << /Font << /F1 ${fontNumbers[index]} 0 R >> >> " +
                "/Contents ${page + 1} 0 R >>"
        )
        objects += streamObj(page + 1, "<< /Length ${content.size} >>", content)
        objects += TestObj(fontNumbers[index], 0, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    }
    return buildTestPdf(objects)
}

/** A page content stream; y is PDF user space (up), like the text operators. */
class PageStream {
    private val sb = StringBuilder()

    fun text(x: Double, y: Double, size: Double, text: String, font: String = "F1"): PageStream {
        sb.append("BT /$font $size Tf $x $y Td ($text) Tj ET\n")
        return this
    }

    fun raw(command: String): PageStream {
        sb.append(command).append('\n')
        return this
    }

    override fun toString(): String = sb.toString()
}

/**
 * One Letter page with the shapes a "well converted" flow document has: a
 * title, two body paragraphs (the first of them broken over two lines), a
 * small heading, a bullet list, a numbered list and a three-column table.
 */
fun flowDocumentPdf(): ByteArray {
    val page = PageStream()
        // Title: 24 pt against a 10 pt body -> Heading 1.
        .text(72.0, 716.0, 24.0, "Quarterly Report")
        // One paragraph in two lines (tight leading).
        .text(72.0, 680.0, 10.0, "This document summarises the results of the quarter")
        .text(72.0, 668.0, 10.0, "and the outlook for the next one.")
        // A second paragraph, separated by a bigger gap.
        .text(72.0, 640.0, 10.0, "Revenue grew in every region, led by the north.")
        // A 14 pt heading (against a 10 pt body -> Heading 3).
        .text(72.0, 610.0, 14.0, "What changed")
        // Bullets.
        .text(72.0, 586.0, 10.0, "* Lower operating costs")
        .text(72.0, 574.0, 10.0, "* Faster delivery")
        // A numbered list.
        .text(72.0, 546.0, 10.0, "1. Review the forecast")
        .text(72.0, 534.0, 10.0, "2. Publish the results")
        // A table: three columns, two rows, aligned.
        .text(72.0, 496.0, 10.0, "Item")
        .text(300.0, 496.0, 10.0, "Qty")
        .text(432.0, 496.0, 10.0, "Price")
        .text(72.0, 482.0, 10.0, "Widgets")
        .text(300.0, 482.0, 10.0, "12")
        .text(432.0, 482.0, 10.0, "24.00")
        .text(72.0, 468.0, 10.0, "Gadgets")
        .text(300.0, 468.0, 10.0, "3")
        .text(432.0, 468.0, 10.0, "9.50")
        // A footer that is a page number, so it goes away in flow mode.
        .text(290.0, 40.0, 9.0, "Page 1")
    return pagesPdf(listOf(page.toString()))
}

/** One Letter page with two columns of text plus a title. */
fun twoColumnPdf(): ByteArray {
    val page = PageStream()
        .text(72.0, 716.0, 18.0, "Two Column Article")
    var y = 680.0
    for (line in 0 until 6) {
        page.text(72.0, y, 10.0, "Left column line $line with some words")
        y -= 14.0
    }
    y = 680.0
    for (line in 0 until 6) {
        page.text(320.0, y, 10.0, "Right column line $line with words")
        y -= 14.0
    }
    return pagesPdf(listOf(page.toString()))
}

/** Three pages, each with a running foot "Page N" and a short body line. */
fun runningHeadPdf(): ByteArray {
    val pages = ArrayList<String>()
    for (number in 1..3) {
        pages += PageStream()
            .text(72.0, 700.0, 10.0, "Body of page $number")
            .text(290.0, 40.0, 9.0, "Page $number")
            .toString()
    }
    return pagesPdf(pages)
}

/** Two pages, for section/page-break checks. */
fun twoPageDocxPdf(): ByteArray = pagesPdf(
    listOf(
        PageStream().text(72.0, 700.0, 10.0, "First page body").toString(),
        PageStream().text(72.0, 700.0, 10.0, "Second page body").toString()
    )
)
