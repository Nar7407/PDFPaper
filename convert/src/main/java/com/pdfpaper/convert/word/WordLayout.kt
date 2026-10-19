package com.pdfpaper.convert.word

import com.pdfpaper.convert.pdf.StandardFont
import com.pdfpaper.convert.pdf.StandardFonts
import com.pdfpaper.convert.pdf.WinAnsi
import kotlin.math.max

/**
 * One drawable item of a laid-out page. Coordinates are in points and measured
 * from the page's **top-left**, y growing downwards; the PDF writer flips them.
 */
internal sealed interface PdfOp

internal class TextOp(
    val x: Double,
    /** Baseline, from the top of the page. */
    val baseline: Double,
    val text: String,
    val font: StandardFont,
    val size: Double,
    val color: WordColor,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Extra advance after every character (`w:rPr/w:spacing`), in points. */
    val tracking: Double = 0.0,
    /** Extra advance after every space, for justified lines, in points. */
    val wordSpacing: Double = 0.0
) : PdfOp

internal class RuleOp(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val color: WordColor
) : PdfOp

internal class ImageOp(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val image: WordImage
) : PdfOp

internal fun PdfOp.shifted(dx: Double, dy: Double): PdfOp = when (this) {
    is TextOp -> TextOp(x + dx, baseline + dy, text, font, size, color, underline, strike, tracking, wordSpacing)
    is RuleOp -> RuleOp(x + dx, y + dy, width, height, color)
    is ImageOp -> ImageOp(x + dx, y + dy, width, height, image)
}

internal class LayoutPage(val width: Double, val height: Double, val ops: List<PdfOp>)

internal class LayoutCounts {
    var pages = 0
    var paragraphs = 0
    var headings = 0
    var listItems = 0
    var tables = 0
    var images = 0
    var replacedCharacters = 0
}

internal class LayoutResult(val pages: List<LayoutPage>, val notes: List<String>, val counts: LayoutCounts)

/**
 * Lays a [WordDocument] out into PDF pages.
 *
 * The layout is a top-down flow: paragraphs wrap into lines at the text width,
 * lines are placed until the text area runs out, then a new page starts. Tables
 * place row by row and a row that does not fit starts a new page rather than
 * being cut in half. A cell is measured with the same paragraph code as the
 * page, so nested tables and multi-paragraph cells work. Everything the layout
 * cannot express (widow/orphan control, text wrapped around a floating picture,
 * a column section) is named in the notes instead of being guessed at.
 */
internal class WordLayout(private val document: WordDocument) {

    private val notes = NoteBag()
    private val counts = LayoutCounts()

    fun layout(): LayoutResult {
        val sinks = document.sections.map { SectionSink(it.section, it.blocks) }
        for (sink in sinks) sink.write()
        val pages = sinks.flatMap { it.finished() }
        counts.pages = pages.size
        return LayoutResult(pages, notes.toList(), counts)
    }

    // ------------------------------------------------------------------ sinks

    /** Where laid-out items go: a page (which can break) or a table cell. */
    private interface Sink {
        val left: Double
        val right: Double
        val top: Double
        val bottom: Double
        var cursor: Double

        /** True when the sink can start a new page when it runs out of room. */
        val paginates: Boolean

        fun emit(op: PdfOp)

        /** Tries to continue on a fresh page; false when the sink cannot. */
        fun pageBreak(): Boolean
    }

    private class PageBuilder {
        val behind = ArrayList<PdfOp>()
        val body = ArrayList<PdfOp>()
        val front = ArrayList<PdfOp>()

        fun finished(width: Double, height: Double): LayoutPage =
            LayoutPage(width, height, behind + body + front)
    }

    /** The sink of one section: a stack of pages with its own page geometry. */
    private inner class SectionSink(
        val section: WordSection,
        private val blocks: List<WordBlock>
    ) : Sink {
        private val pages = ArrayList<PageBuilder>()
        private var current = PageBuilder()
        private var cursorY = section.marginTop

        override val left: Double get() = section.marginLeft
        override val right: Double get() = section.pageWidth - section.marginRight
        override val top: Double get() = section.marginTop
        override val bottom: Double get() = section.pageHeight - section.marginBottom
        override var cursor: Double
            get() = cursorY
            set(value) {
                cursorY = value
            }

        override val paginates: Boolean get() = true

        val page: PageBuilder get() = current

        override fun emit(op: PdfOp) {
            current.body += op
        }

        override fun pageBreak(): Boolean {
            pages += current
            current = PageBuilder()
            cursorY = section.marginTop
            return true
        }

        fun write() {
            for (block in blocks) {
                when (block) {
                    is WordParagraphBlock -> paragraph(block.paragraph, this)
                    is WordTableBlock -> table(block.table, this)
                }
            }
        }

        fun finished(): List<LayoutPage> {
            val all = pages + current
            return all.map { it.finished(section.pageWidth, section.pageHeight) }
        }
    }

    /** A table cell: laid out in full, because a cell cannot split a page. */
    private inner class CellSink(
        override val left: Double,
        override val right: Double
    ) : Sink {
        val ops = ArrayList<PdfOp>()
        private var cursorY = 0.0

        override val top: Double get() = 0.0
        override val bottom: Double get() = Double.MAX_VALUE
        override var cursor: Double
            get() = cursorY
            set(value) {
                cursorY = value
            }

        override val paginates: Boolean get() = false

        override fun emit(op: PdfOp) {
            ops += op
        }

        override fun pageBreak(): Boolean = false
    }

    // ------------------------------------------------------------- paragraphs

    private sealed interface Atom

    private class AtomWord(val text: String, val format: WordRunFormat) : Atom
    private class AtomSpace(val format: WordRunFormat) : Atom
    private class AtomTab(val format: WordRunFormat) : Atom
    private class AtomBreak(val page: Boolean) : Atom
    private class AtomImage(val run: WordImageRun) : Atom

    private data class Piece(
        val text: String,
        val font: StandardFont,
        val size: Double,
        val color: WordColor,
        val underline: Boolean,
        val strike: Boolean,
        val tracking: Double,
        /** Baseline shift in points: positive is raised (superscript). */
        val shift: Double
    ) {
        val ascent: Double get() = StandardFonts.ascent1000(font) / 1000.0 * size + max(0.0, shift)
        val descent: Double get() = -StandardFonts.descent1000(font) / 1000.0 * size + max(0.0, -shift)
    }

    private class ImagePiece(val run: WordImageRun)

    private class Line(
        val pieces: List<Any>,
        val height: Double,
        val ascent: Double,
        val natural: Double,
        /** True to start a fresh page before placing this line. */
        val pageBreakBefore: Boolean,
        /** True for every line of a justified paragraph except its last. */
        val justify: Boolean
    )

    /** A page break at the end of one block starts a new page for the next. */
    private var pendingPageBreak = false

    private fun paragraph(paragraph: WordParagraph, sink: Sink) {
        counts.paragraphs++
        if (paragraph.headingLevel != null) counts.headings++
        if (paragraph.marker != null) counts.listItems++

        if (paragraph.pageBreakBefore || pendingPageBreak) {
            sink.pageBreak()
            pendingPageBreak = false
        }
        val base = baseFormat(paragraph)
        if (paragraph.spaceBefore > 0 && sink.cursor > sink.top) sink.cursor += paragraph.spaceBefore

        val lines = wrap(paragraph, atoms(paragraph, sink), base, sink)
        if (lines.isEmpty()) {
            val single = singleHeight(base)
            if (sink.paginates && sink.cursor > sink.top && sink.cursor + single > sink.bottom) sink.pageBreak()
            sink.cursor += single
        } else {
            for ((index, line) in lines.withIndex()) {
                if (line.pageBreakBefore) sink.pageBreak()
                if (sink.paginates && sink.cursor > sink.top && sink.cursor + line.height > sink.bottom) {
                    sink.pageBreak()
                }
                place(line, sink, first = index == 0, paragraph = paragraph, base = base)
                sink.cursor += line.height
            }
        }
        sink.cursor += paragraph.spaceAfter
    }

    private fun baseFormat(paragraph: WordParagraph): WordRunFormat = paragraph.baseFormat.copy(
        size = paragraph.baseFormat.size ?: document.defaultSize,
        family = paragraph.baseFormat.family ?: document.defaultFamily
    )

    private fun singleHeight(format: WordRunFormat): Double {
        val size = format.size ?: document.defaultSize
        val font = StandardFonts.resolve(format.family, format.bold == true, format.italic == true)
        return StandardFonts.singleLineHeight(font, size)
    }

    /**
     * Turns a paragraph's runs into wrap-able atoms. A floating picture never
     * becomes an atom: it is placed on the page instead of joining the line
     * (inside a table cell, where there is no page of its own, it stays inline).
     */
    private fun atoms(paragraph: WordParagraph, sink: Sink): List<Atom> {
        val atoms = ArrayList<Atom>()
        for (run in paragraph.runs) {
            when (run) {
                is WordTextRun -> {
                    val text = WinAnsi.stripSoftHyphens(run.text)
                        .let { if (run.format.allCaps == true) it.uppercase() else it }
                    var word = StringBuilder()
                    fun flush() {
                        if (word.isNotEmpty()) {
                            atoms += AtomWord(word.toString(), run.format)
                            word = StringBuilder()
                        }
                    }
                    for (ch in text) {
                        when (ch) {
                            '\t' -> {
                                flush()
                                atoms += AtomTab(run.format)
                            }
                            '\n', '\r' -> {
                                flush()
                                atoms += AtomBreak(page = false)
                            }
                            ' ', '\u00A0' -> {
                                flush()
                                atoms += AtomSpace(run.format)
                            }
                            else -> word.append(ch)
                        }
                    }
                    flush()
                }
                is WordTabRun -> atoms += AtomTab(run.format)
                is WordBreakRun -> atoms += AtomBreak(run.page)
                is WordImageRun -> when {
                    run.anchor == null -> atoms += AtomImage(run)
                    sink is SectionSink -> floating(run, sink)
                    else -> atoms += AtomImage(run)
                }
            }
        }
        return atoms
    }

    private fun pieceOf(text: String, format: WordRunFormat): Piece {
        val size = format.size ?: document.defaultSize
        val font = StandardFonts.resolve(format.family, format.bold == true, format.italic == true)
        val family = format.family
        if (family != null && !StandardFont.isStandard(family)) {
            notes.add(
                if (StandardFonts.recognised(family)) {
                    "font '$family' is replaced by ${font.baseName}; line breaks may differ"
                } else {
                    "font '$family' is not a standard PDF font and is replaced by ${font.baseName}"
                }
            )
        }
        for (unmappable in WinAnsi.unmappable(text)) {
            counts.replacedCharacters++
            notes.add("characters outside the WinAnsi encoding ('$unmappable') are drawn as '?'")
        }
        val shift = when {
            format.superscript == true -> size * 0.33
            format.subscript == true -> -size * 0.14
            else -> 0.0
        }
        return Piece(
            text = text,
            font = font,
            size = size,
            color = format.color ?: WordColor.BLACK,
            underline = format.underline == true,
            strike = format.strike == true,
            tracking = format.tracking ?: 0.0,
            shift = shift
        )
    }

    private fun width(piece: Piece, text: String = piece.text): Double =
        StandardFonts.width(piece.font, text, piece.size) + piece.tracking * text.length

    /** Longest prefix of [text] that still fits in [available] points. */
    private fun prefixThatFits(piece: Piece, text: String, available: Double): Int {
        var take = 0
        while (take < text.length && width(piece, text.substring(0, take + 1)) <= available) take++
        return take.coerceAtLeast(1)
    }

    /**
     * Greedy line breaking. Each line measures itself, so the tallest run sets
     * its height and the widest piece decides the wrap; a word wider than the
     * column is broken between characters, the way Word breaks a long word.
     */
    private fun wrap(paragraph: WordParagraph, atoms: List<Atom>, base: WordRunFormat, sink: Sink): List<Line> {
        val lines = ArrayList<Line>()
        val left = sink.left + paragraph.indentLeft
        val right = sink.right - paragraph.indentRight
        // A list marker fills the hanging indent, so the first line of a list
        // item has no extra room (only a real first-line indent widens it).
        var lineLeft = left + if (paragraph.marker != null) 0.0 else paragraph.indentFirst

        var pieces = ArrayList<Any>()
        var ascent = 0.0
        var descent = 0.0
        var x = 0.0
        var pendingSpaces = ArrayList<Piece>()
        var breakBefore = pendingPageBreak
        pendingPageBreak = false

        fun lineBox(): Double {
            val widest = pieces.filterIsInstance<Piece>().maxByOrNull { it.size }
            val size = widest?.size ?: (base.size ?: document.defaultSize)
            val font = widest?.font
                ?: StandardFonts.resolve(base.family, base.bold == true, base.italic == true)
            return when (val spacing = paragraph.lineHeight) {
                null -> StandardFonts.singleLineHeight(font, size)
                is WordLineHeight.Multiple -> spacing.factor * StandardFonts.singleLineHeight(font, size)
                is WordLineHeight.Exact -> spacing.points
                is WordLineHeight.AtLeast -> max(spacing.points, StandardFonts.singleLineHeight(font, size))
            }
        }

        fun finish() {
            val natural = ascent + descent
            val height = max(lineBox(), natural.coerceAtLeast(singleHeight(base)))
            lines += Line(
                pieces = pieces,
                height = height,
                ascent = max(ascent, 0.1),
                natural = natural,
                pageBreakBefore = breakBefore,
                justify = paragraph.alignment == WordAlign.JUSTIFY
            )
            pieces = ArrayList()
            ascent = 0.0
            descent = 0.0
            x = 0.0
            pendingSpaces = ArrayList()
            breakBefore = false
            lineLeft = left
        }

        fun placeSpaces() {
            for (space in pendingSpaces) {
                pieces += space
                x += width(space)
            }
            pendingSpaces = ArrayList()
        }

        fun fits(extra: Double): Boolean =
            (x <= 0.0 && pieces.isEmpty()) || lineLeft + x + extra <= right + 0.01

        for (atom in atoms) {
            when (atom) {
                is AtomSpace -> pendingSpaces += pieceOf(" ", atom.format)
                is AtomTab -> {
                    if (pieces.isNotEmpty()) {
                        val stop = document.tabStop.coerceAtLeast(4.0)
                        val target = (Math.floor((lineLeft + x) / stop) + 1) * stop
                        x = target - lineLeft
                    }
                }
                is AtomBreak -> {
                    finish()
                    if (atom.page) breakBefore = true
                }
                is AtomImage -> {
                    val imageWidth = atom.run.width
                    if (!fits(imageWidth) && pieces.isNotEmpty()) finish()
                    placeSpaces()
                    pieces += ImagePiece(atom.run)
                    x += imageWidth
                    ascent = max(ascent, atom.run.height)
                    counts.images++
                }
                is AtomWord -> {
                    val piece = pieceOf(atom.text, atom.format)
                    val wide = width(piece)
                    if (!fits(wide) && pieces.isNotEmpty()) finish()
                    placeSpaces()
                    val available = (right - lineLeft).coerceAtLeast(1.0)
                    if (wide <= available) {
                        pieces += piece
                        x += wide
                        ascent = max(ascent, piece.ascent)
                        descent = max(descent, piece.descent)
                    } else {
                        var rest = atom.text
                        while (rest.isNotEmpty()) {
                            val room = available - x
                            val take = if (room <= 1.0) 1 else prefixThatFits(piece, rest, room)
                            val chunk = rest.substring(0, take)
                            pieces += piece.copy(text = chunk)
                            x += width(piece, chunk)
                            ascent = max(ascent, piece.ascent)
                            descent = max(descent, piece.descent)
                            rest = rest.substring(take)
                            if (rest.isNotEmpty()) finish()
                        }
                    }
                    pendingSpaces = ArrayList()
                }
            }
        }
        finish()
        // A paragraph that ends with a page break leaves the break for whatever
        // comes next, and the last line of a justified paragraph is not stretched.
        if (lines.size >= 2 && lines.last().pieces.isEmpty() && lines.last().pageBreakBefore) {
            lines.removeAt(lines.size - 1)
            pendingPageBreak = true
        }
        if (lines.isNotEmpty()) {
            val last = lines.last()
            lines[lines.size - 1] =
                Line(last.pieces, last.height, last.ascent, last.natural, last.pageBreakBefore, false)
        }
        return lines
    }

    /** Places one line: alignment, justification, underline, baseline. */
    private fun place(line: Line, sink: Sink, first: Boolean, paragraph: WordParagraph, base: WordRunFormat) {
        val indented = first && paragraph.marker == null
        val lineLeft = sink.left + paragraph.indentLeft + if (indented) paragraph.indentFirst else 0.0
        val available = (sink.right - paragraph.indentRight) - lineLeft
        var width = 0.0
        var gaps = 0
        for (piece in line.pieces) {
            when (piece) {
                is Piece -> {
                    width += width(piece)
                    gaps += piece.text.count { it == ' ' }
                }
                is ImagePiece -> width += piece.run.width
            }
        }
        val wordSpacing =
            if (line.justify && gaps > 0 && width < available) (available - width) / gaps else 0.0
        var x = when (paragraph.alignment) {
            WordAlign.LEFT, WordAlign.JUSTIFY -> lineLeft
            WordAlign.CENTER -> lineLeft + max(0.0, (available - width) / 2.0)
            WordAlign.RIGHT -> lineLeft + max(0.0, available - width)
        }
        val natural = max(line.natural, 0.1)
        val baseline = sink.cursor + line.ascent + max(0.0, (line.height - natural) / 2.0)

        if (first) paragraph.marker?.let { marker -> x = marker(marker, sink, baseline, base) }

        for (piece in line.pieces) {
            when (piece) {
                is Piece -> {
                    sink.emit(
                        TextOp(
                            x = x,
                            baseline = baseline - piece.shift,
                            text = piece.text,
                            font = piece.font,
                            size = piece.size,
                            color = piece.color,
                            underline = piece.underline,
                            strike = piece.strike,
                            tracking = piece.tracking,
                            wordSpacing = wordSpacing
                        )
                    )
                    x += width(piece) + if (piece.text.endsWith(" ")) wordSpacing else 0.0
                }
                is ImagePiece -> {
                    val image = piece.run
                    sink.emit(ImageOp(x, baseline - image.height, image.width, image.height, image.image))
                    x += image.width
                }
            }
        }
    }

    /**
     * Draws a list marker in the hanging indent and returns where the text
     * starts, which is the paragraph's own left indent.
     */
    private fun marker(marker: WordMarker, sink: Sink, baseline: Double, base: WordRunFormat): Double {
        val size = base.size ?: document.defaultSize
        val font = StandardFonts.resolve(marker.family ?: base.family, base.bold == true, base.italic == true)
        val piece = Piece(
            text = marker.text,
            font = font,
            size = size,
            color = base.color ?: WordColor.BLACK,
            underline = false,
            strike = false,
            tracking = 0.0,
            shift = 0.0
        )
        val start = sink.left + marker.indentLeft - marker.hanging
        val x = when (marker.align) {
            WordAlign.RIGHT -> start + marker.hanging - width(piece)
            WordAlign.CENTER -> start + (marker.hanging - width(piece)) / 2.0
            else -> start
        }
        sink.emit(
            TextOp(
                x = x,
                baseline = baseline,
                text = piece.text,
                font = piece.font,
                size = piece.size,
                color = piece.color
            )
        )
        return sink.left + marker.indentLeft
    }

    // ------------------------------------------------------------ floaters

    /**
     * A floating picture is not part of the text flow: it is placed on the page
     * the paragraph is on, behind or in front of the text, from its offsets.
     * Word would wrap text around it; that is reported, not imitated.
     */
    private fun floating(run: WordImageRun, sink: Sink) {
        val sectionSink = sink as? SectionSink
        if (sectionSink == null || run.anchor == null) return
        val anchor = run.anchor
        val section = sectionSink.section
        val fromX = if (anchor.fromPageX) 0.0 else section.marginLeft
        val toX = if (anchor.fromPageX) section.pageWidth else section.pageWidth - section.marginRight
        val x = anchor.offsetX ?: when (anchor.alignX) {
            WordAlign.CENTER -> fromX + ((toX - fromX) - run.width) / 2.0
            WordAlign.RIGHT -> toX - run.width
            else -> fromX
        }
        val fromY = if (anchor.fromPageY) 0.0 else section.marginTop
        val toY = if (anchor.fromPageY) section.pageHeight else section.pageHeight - section.marginBottom
        val y = anchor.offsetY ?: when (anchor.alignY?.trim()?.lowercase()) {
            "center" -> fromY + ((toY - fromY) - run.height) / 2.0
            "bottom" -> toY - run.height
            else -> fromY
        }
        val op = ImageOp(x, y, run.width, run.height, run.image)
        if (anchor.behindText) sectionSink.page.behind += op else sectionSink.page.front += op
        counts.images++
    }

    // ---------------------------------------------------------------- tables

    private fun table(table: WordTable, sink: Sink) {
        counts.tables++
        if (table.rows.isEmpty()) return
        val available = (sink.right - sink.left - table.indentLeft).coerceAtLeast(1.0)
        val widths = columnWidths(table, available)
        val left = sink.left + table.indentLeft
        for (row in table.rows) {
            // Cells, in their grid columns.
            val placements = ArrayList<Triple<WordTableCell, Double, Double>>()
            var column = 0
            var offset = 0.0
            for (cell in row.cells) {
                val span = cell.spanAcross.coerceAtLeast(1)
                val width = (column until (column + span)).sumOf { widths.getOrElse(it) { 0.0 } }
                    .takeIf { it > 0.0 } ?: widths.lastOrNull() ?: available
                placements += Triple(cell, offset, width)
                offset += width
                column += span
            }
            val laidOut = placements.map { (cell, x, width) ->
                val inner = CellSink(table.cellMargin, width - table.cellMargin)
                for (block in cell.blocks) {
                    when (block) {
                        is WordParagraphBlock -> paragraph(block.paragraph, inner)
                        is WordTableBlock -> table(block.table, inner)
                    }
                }
                Triple(cell, Pair(x, width), inner)
            }
            val contentHeight = laidOut.maxOf { it.third.cursor } + table.cellMargin
            val rowHeight = max(contentHeight, row.height ?: 0.0)
            if (sink.paginates && sink.cursor > sink.top && sink.cursor + rowHeight > sink.bottom) {
                sink.pageBreak()
            }
            val rowTop = sink.cursor
            for ((cell, position, inner) in laidOut) {
                cell.shading?.let { sink.emit(RuleOp(left + position.first, rowTop, position.second, rowHeight, it)) }
                for (op in inner.ops) sink.emit(op.shifted(left + position.first, rowTop))
            }
            if (table.borders) grid(left, rowTop, widths, rowHeight, available, sink)
            sink.cursor += rowHeight
        }
    }

    private fun columnWidths(table: WordTable, available: Double): List<Double> {
        val grid = table.columnWidths.filter { it > 0.0 }
        if (grid.isEmpty()) {
            val columns = table.rows.maxOf { row -> row.cells.sumOf { it.spanAcross.coerceAtLeast(1) } }
            return List(columns) { available / columns }
        }
        if (grid.sum() > available + 0.01) {
            notes.add("a table wider than the text area was scaled down to fit it")
            val scale = available / grid.sum()
            return grid.map { it * scale }
        }
        return grid
    }

    private fun grid(left: Double, top: Double, widths: List<Double>, height: Double, available: Double, sink: Sink) {
        val grey = WordColor(0x80, 0x80, 0x80)
        val thickness = 0.5
        val total = widths.sum().coerceAtMost(available)
        sink.emit(RuleOp(left, top, total, thickness, grey))
        var x = 0.0
        for (width in widths) {
            sink.emit(RuleOp(left + x, top, thickness, height, grey))
            x += width
        }
        sink.emit(RuleOp(left + x, top, thickness, height, grey))
        sink.emit(RuleOp(left, top + height, total, thickness, grey))
    }
}
