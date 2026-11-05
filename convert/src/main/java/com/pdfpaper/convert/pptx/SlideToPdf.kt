package com.pdfpaper.convert.pptx

import com.pdfpaper.convert.NoteBag
import com.pdfpaper.convert.pdf.EllipseOp
import com.pdfpaper.convert.pdf.ImageOp
import com.pdfpaper.convert.pdf.LayoutPage
import com.pdfpaper.convert.pdf.LineOp
import com.pdfpaper.convert.pdf.PdfColor
import com.pdfpaper.convert.pdf.PdfOp
import com.pdfpaper.convert.pdf.RuleOp
import com.pdfpaper.convert.pdf.StandardFont
import com.pdfpaper.convert.pdf.StandardFonts
import com.pdfpaper.convert.pdf.TextOp
import com.pdfpaper.convert.pdf.WinAnsi
import kotlin.math.max

internal class SlideCounts {
    var slides = 0
    var shapes = 0
    var pictures = 0
    var textBoxes = 0
    var listItems = 0
    var replacedCharacters = 0
}

internal class SlideResult(val pages: List<LayoutPage>, val notes: List<String>, val counts: SlideCounts)

/**
 * Lays a [SlideDeck] out into one PDF page per slide.
 *
 * A slide is painted back to front: the background, then every item in the
 * order the slide lists it. Shapes draw their fill plus an outline of four
 * rules (the writer has no stroked rectangle), ellipses draw fill and stroke,
 * connectors draw a segment between two corners of their box, and pictures are
 * placed as they are. Text flows inside its box: paragraphs wrap greedily at
 * the box's width, the tallest piece sets the line's height, bullets and
 * numbers are drawn in the hanging indent, and the vertical anchor moves the
 * finished block.
 *
 * The page size is the slide size, so there is no pagination: something that
 * overflows a box or the slide is drawn as it overflows, exactly as PowerPoint
 * draws it, rather than being cut off.
 */
internal class SlideToPdf(private val deck: SlideDeck) {

    private val notes = NoteBag()
    private val counts = SlideCounts()

    fun layout(): SlideResult {
        val pages = deck.slides.map { page(it) }
        counts.slides = pages.size
        return SlideResult(pages, notes.toList(), counts)
    }

    private fun page(slide: SlideContent): LayoutPage {
        val ops = ArrayList<PdfOp>()
        slide.background?.let { background ->
            background.color?.let { ops += RuleOp(0.0, 0.0, deck.width, deck.height, it) }
            background.image?.let {
                counts.pictures++
                ops += ImageOp(0.0, 0.0, deck.width, deck.height, it)
            }
        }
        for (item in slide.items) {
            when (item) {
                is SlideFigure -> shape(item, ops)
                is SlidePicture -> {
                    counts.pictures++
                    ops += ImageOp(item.x, item.y, item.width, item.height, item.image)
                }
            }
        }
        return LayoutPage(deck.width, deck.height, ops)
    }

    // ---------------------------------------------------------------- shapes

    private fun shape(shape: SlideFigure, ops: MutableList<PdfOp>) {
        counts.shapes++
        when (shape.kind) {
            SlideFigureKind.RECT, SlideFigureKind.ROUND_RECT -> {
                shape.fill?.let { ops += RuleOp(shape.x, shape.y, shape.width, shape.height, it) }
                outline(shape, ops)
            }
            SlideFigureKind.ELLIPSE -> {
                if (shape.fill != null || shape.line != null) {
                    ops += EllipseOp(shape.x, shape.y, shape.width, shape.height, shape.fill, shape.line, shape.lineWidth)
                }
            }
            SlideFigureKind.LINE -> {
                val color = shape.line ?: return
                val (x1, y1, x2, y2) = lineEnds(shape)
                ops += LineOp(x1, y1, x2, y2, color, shape.lineWidth)
            }
        }
        shape.text?.let { text(shape, it, ops) }
    }

    /** A rectangle's outline as four rules centred on its edges. */
    private fun outline(shape: SlideFigure, ops: MutableList<PdfOp>) {
        val color = shape.line ?: return
        val thickness = max(0.1, shape.lineWidth)
        val half = thickness / 2.0
        val left = shape.x - half
        val top = shape.y - half
        val width = shape.width + thickness
        val height = shape.height + thickness
        ops += RuleOp(left, top, width, thickness, color)
        ops += RuleOp(left, top + height - thickness, width, thickness, color)
        ops += RuleOp(left, top, thickness, height, color)
        ops += RuleOp(left + width - thickness, top, thickness, height, color)
    }

    /** A connector runs from one corner of its box to the opposite one. */
    private fun lineEnds(shape: SlideFigure): List<Double> {
        val left = shape.x
        val right = shape.x + shape.width
        val top = shape.y
        val bottom = shape.y + shape.height
        return listOf(
            if (shape.flipH) right else left,
            if (shape.flipV) bottom else top,
            if (shape.flipH) left else right,
            if (shape.flipV) top else bottom
        )
    }

    // ------------------------------------------------------------------ text

    private sealed interface Atom

    private class AtomWord(val text: String, val format: SlideRunFormat) : Atom
    private class AtomSpace(val format: SlideRunFormat) : Atom
    private object AtomBreak : Atom

    private class Piece(
        val text: String,
        val font: StandardFont,
        val size: Double,
        val color: PdfColor,
        val underline: Boolean,
        val strike: Boolean
    ) {
        val ascent: Double get() = StandardFonts.ascent1000(font) / 1000.0 * size
        val descent: Double get() = -StandardFonts.descent1000(font) / 1000.0 * size
    }

    private class TextLine(
        val pieces: List<Piece>,
        val height: Double,
        val ascent: Double,
        val natural: Double,
        val align: SlideAlign,
        var justify: Boolean,
        val textLeft: Double,
        val textRight: Double,
        var marker: Piece? = null,
        var markerX: Double = 0.0,
        /** Space the paragraph asks for in front of this line. */
        var spaceBefore: Double = 0.0
    )

    private fun text(shape: SlideFigure, body: SlideText, ops: MutableList<PdfOp>) {
        counts.textBoxes++
        val left = shape.x + body.leftInset
        val right = shape.x + shape.width - body.rightInset
        val lines = ArrayList<TextLine>()
        var after = 0.0
        for (paragraph in body.paragraphs) {
            if (paragraph.marker != null) counts.listItems++
            val wrapped = wrap(paragraph, left, right, body.wrap)
            wrapped.firstOrNull()?.spaceBefore = paragraph.spaceBefore
            lines += wrapped
            after += paragraph.spaceAfter
        }
        val top = shape.y + body.topInset
        val bottom = shape.y + shape.height - body.bottomInset
        val total = lines.sumOf { it.spaceBefore + it.height } + after
        var cursor = when (body.anchor.trim().lowercase()) {
            "ctr" -> top + (bottom - top - total) / 2.0
            "b" -> bottom - total
            else -> top
        }
        for (line in lines) {
            cursor += line.spaceBefore
            place(line, cursor, ops)
            cursor += line.height
        }
    }

    /** The pixels of one line: alignment, justification, underline, baseline. */
    private fun place(line: TextLine, cursor: Double, ops: MutableList<PdfOp>) {
        var width = 0.0
        var gaps = 0
        for (piece in line.pieces) {
            width += width(piece)
            gaps += piece.text.count { it == ' ' }
        }
        val available = (line.textRight - line.textLeft).coerceAtLeast(0.0)
        val wordSpacing =
            if (line.justify && gaps > 0 && width < available) (available - width) / gaps else 0.0
        var x = when (line.align) {
            SlideAlign.LEFT, SlideAlign.JUSTIFY -> line.textLeft
            SlideAlign.CENTER -> line.textLeft + max(0.0, (available - width) / 2.0)
            SlideAlign.RIGHT -> line.textLeft + max(0.0, available - width)
        }
        val baseline = cursor + line.ascent + max(0.0, (line.height - line.natural) / 2.0)
        line.marker?.let { marker ->
            ops += TextOp(line.markerX, baseline, marker.text, marker.font, marker.size, marker.color)
        }
        for (piece in line.pieces) {
            ops += TextOp(
                x = x,
                baseline = baseline,
                text = piece.text,
                font = piece.font,
                size = piece.size,
                color = piece.color,
                underline = piece.underline,
                strike = piece.strike,
                wordSpacing = wordSpacing
            )
            x += width(piece) + if (piece.text.endsWith(" ")) wordSpacing else 0.0
        }
    }

    /**
     * Greedy line breaking inside a text box. Each line measures itself, so the
     * tallest run sets its height and the widest piece decides the wrap; a word
     * wider than the box is broken between characters. `wrap = false` boxes
     * never break their lines.
     */
    private fun wrap(
        paragraph: SlideParagraph,
        left: Double,
        right: Double,
        wrap: Boolean
    ): List<TextLine> {
        val textLeft = left + paragraph.marginLeft
        val textRight = right

        val atoms = ArrayList<Atom>()
        for (run in paragraph.runs) {
            var word = StringBuilder()
            fun flush() {
                if (word.isNotEmpty()) {
                    atoms += AtomWord(word.toString(), run.format)
                    word = StringBuilder()
                }
            }
            for (ch in run.text) {
                when (ch) {
                    '\n', '\r' -> {
                        flush()
                        atoms += AtomBreak
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

        val lines = ArrayList<TextLine>()
        var pieces = ArrayList<Piece>()
        var ascent = 0.0
        var descent = 0.0
        var x = 0.0
        var lineStart = textLeft + if (paragraph.marker == null) max(0.0, paragraph.indent) else 0.0
        var pendingSpaces = ArrayList<Piece>()
        var trailingBreak = false

        fun singleHeight(): Double {
            val tallest = pieces.maxByOrNull { it.size }
            val font = tallest?.font
                ?: StandardFonts.resolve(paragraph.base.family, paragraph.base.bold, paragraph.base.italic)
            val size = tallest?.size ?: paragraph.size
            return StandardFonts.singleLineHeight(font, size)
        }

        fun lineHeight(): Double {
            val single = singleHeight()
            return when {
                paragraph.lineSpacingPoints != null -> paragraph.lineSpacingPoints
                paragraph.lineSpacingMultiple != null -> paragraph.lineSpacingMultiple * single
                else -> single
            }
        }

        fun finish() {
            val natural = max(ascent + descent, 0.1)
            lines += TextLine(
                pieces = pieces,
                height = max(lineHeight(), natural),
                ascent = max(ascent, 0.1),
                natural = natural,
                align = paragraph.align,
                justify = paragraph.align == SlideAlign.JUSTIFY,
                textLeft = lineStart,
                textRight = textRight
            )
            pieces = ArrayList()
            ascent = 0.0
            descent = 0.0
            x = 0.0
            pendingSpaces = ArrayList()
            lineStart = textLeft
        }

        fun placeSpaces() {
            for (space in pendingSpaces) {
                pieces += space
                x += width(space)
            }
            pendingSpaces = ArrayList()
        }

        fun fits(extra: Double): Boolean =
            !wrap || (x <= 0.0 && pieces.isEmpty()) || (lineStart + x + extra <= textRight + 0.01)

        for (atom in atoms) {
            when (atom) {
                is AtomSpace -> {
                    pendingSpaces += pieceOf(" ", atom.format)
                    trailingBreak = false
                }
                is AtomBreak -> {
                    finish()
                    trailingBreak = true
                }
                is AtomWord -> {
                    val piece = pieceOf(atom.text, atom.format)
                    val wide = width(piece)
                    if (!fits(wide) && pieces.isNotEmpty()) finish()
                    placeSpaces()
                    val available = (textRight - lineStart).coerceAtLeast(1.0)
                    if (!wrap || wide <= available) {
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
                            pieces += Piece(chunk, piece.font, piece.size, piece.color, piece.underline, piece.strike)
                            x += width(piece, chunk)
                            ascent = max(ascent, piece.ascent)
                            descent = max(descent, piece.descent)
                            rest = rest.substring(take)
                            if (rest.isNotEmpty()) finish()
                        }
                    }
                    pendingSpaces = ArrayList()
                    trailingBreak = false
                }
            }
        }
        finish()
        // A paragraph that ends with a break draws its own blank line already.
        if (trailingBreak && lines.size >= 2 && lines.last().pieces.isEmpty()) {
            lines.removeAt(lines.size - 1)
        }
        if (lines.isNotEmpty()) lines.last().justify = false

        paragraph.marker?.let { marker ->
            val base = paragraph.base
            val piece = Piece(
                text = marker,
                font = StandardFonts.resolve(base.family, base.bold, base.italic),
                size = paragraph.size,
                color = base.color,
                underline = false,
                strike = false
            )
            val first = lines.first()
            first.marker = piece
            first.markerX = textLeft + paragraph.indent
        }
        return lines
    }

    private fun pieceOf(text: String, format: SlideRunFormat): Piece {
        val font = StandardFonts.resolve(format.family, format.bold, format.italic)
        if (!StandardFont.isStandard(format.family)) {
            notes.add(
                if (StandardFonts.recognised(format.family)) {
                    "font '${format.family}' is replaced by ${font.baseName}; line breaks may differ"
                } else {
                    "font '${format.family}' is not a standard PDF font and is replaced by ${font.baseName}"
                }
            )
        }
        for (unmappable in WinAnsi.unmappable(text)) {
            counts.replacedCharacters++
            notes.add("characters outside the WinAnsi encoding ('$unmappable') are drawn as '?'")
        }
        return Piece(text, font, format.size, format.color, format.underline, format.strike)
    }

    private fun width(piece: Piece, text: String = piece.text): Double =
        StandardFonts.width(piece.font, text, piece.size)

    /** Longest prefix of [text] that still fits in [available] points. */
    private fun prefixThatFits(piece: Piece, text: String, available: Double): Int {
        var take = 0
        while (take < text.length && width(piece, text.substring(0, take + 1)) <= available) take++
        return take.coerceAtLeast(1)
    }
}
