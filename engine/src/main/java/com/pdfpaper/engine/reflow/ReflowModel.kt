package com.pdfpaper.engine.reflow

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry

/**
 * What a reflow extractor keeps (T33). Everything here is a view decision, so
 * nothing is ever written back into the document.
 */
data class ReflowOptions(
    /** Drops a pure page number ("12", "Page 12 of 20", "iv") in a page band. */
    val hidePageNumbers: Boolean = true,
    /** Drops small text in the bottom band, which is where footnotes live. */
    val hideFootnotes: Boolean = true,
    /** Detects rows of aligned cells and hands the region to the renderer. */
    val detectTables: Boolean = true,
    /** Top band of the page (fraction of its height) that may hold a running head. */
    val headerBand: Double = 0.08,
    /** Bottom band of the page that may hold a page number or a footnote. */
    val footerBand: Double = 0.16,
    /** Images smaller than this fraction of the page area are decoration. */
    val minImageArea: Double = 0.02,
    /** An image covering this much of the page is a background or a scan. */
    val backgroundCoverage: Double = 0.6
) {
    companion object {
        /** Defaults, for callers that do not care. */
        val DEFAULT: ReflowOptions = ReflowOptions()
    }
}

/** What one reflow block is (T33). */
enum class ReflowBlockKind {
    PARAGRAPH,
    HEADING,
    LIST_ITEM,

    /** A picture; the block carries the page region to render. */
    IMAGE,

    /** A table; the block carries the page region to render. */
    TABLE
}

/**
 * One block of a reflowed page. Text blocks carry their text; [IMAGE] and
 * [TABLE] blocks carry no text but the page region the app renders instead.
 *
 * [textStart]/[textEnd] are offsets into [ReflowPage.text] (the reflow's own
 * string), [pageTextStart]/[pageTextEnd] offsets into the page's normal text
 * layout. The second pair is what keeps page view and reflow on the same
 * paragraph when the reader switches modes.
 */
data class ReflowBlock(
    val kind: ReflowBlockKind,
    val text: String,
    val textStart: Int,
    val textEnd: Int,
    val pageTextStart: Int,
    val pageTextEnd: Int,
    /** Page-space box of the block (unrotated user space, y up). */
    val bounds: PdfRect,
    /** 1 for the largest heading, 2 and 3 below it; 0 when not a heading. */
    val level: Int = 0,
    /** List marker ("•", "1.") when the block is a list item, else "". */
    val marker: String = ""
) {
    val isText: Boolean
        get() = kind == ReflowBlockKind.PARAGRAPH ||
            kind == ReflowBlockKind.HEADING ||
            kind == ReflowBlockKind.LIST_ITEM

    /** [text] without the list marker, ready to be drawn next to it. */
    val body: String
        get() {
            if (kind != ReflowBlockKind.LIST_ITEM || marker.isEmpty()) return text
            if (!text.startsWith(marker)) return text
            return text.substring(marker.length).trimStart()
        }
}

/**
 * One page after reflow extraction (T33). [blocks] are in reading order;
 * [text] is the concatenation of the text blocks, so a text offset survives a
 * settings change that re-extracts the page.
 *
 * [scanned] means the page has no text layer and is covered by a picture: the
 * app shows "This page is scanned" and offers OCR (T40) instead of pretending
 * the page is blank.
 */
class ReflowPage(
    val index: Int,
    val blocks: List<ReflowBlock>,
    val text: String,
    val scanned: Boolean,
    /** The page's crop box and rotation, used to render image and table regions. */
    val box: PageBox,
    /** The page's text in normal reading order, for search and offset mapping. */
    val pageText: String,
    val warnings: List<String> = emptyList()
) {
    val hasText: Boolean get() = blocks.any { it.isText }

    /** True when the page has neither text nor pictures nor vector art. */
    val isBlank: Boolean get() = blocks.isEmpty() && !scanned

    /** True when the page has content but no text layer to reflow. */
    val needsOcr: Boolean get() = scanned

    /**
     * 0..1 position of [block] on the displayed page (y down, /Rotate applied).
     * The app turns this into a scroll position when it leaves reflow.
     */
    fun fractionOf(block: ReflowBlock): Double {
        val height = box.rotatedHeight
        if (height <= 0.0) return 0.0
        val matrix = PageGeometry.matrixFor(box, 1.0, 1.0)
        val point = matrix.apply(block.bounds.centerX, block.bounds.centerY)
        if (!point[1].isFinite()) return 0.0
        return (point[1] / height).coerceIn(0.0, 1.0)
    }

    /**
     * The text block that holds [offset] of [pageText], or the first text block
     * when the offset falls between two of them (a picture, a hidden page
     * number). Null only when the page has no text at all.
     */
    fun blockForPageOffset(offset: Int): ReflowBlock? {
        val textBlocks = blocks.filter { it.isText }
        if (textBlocks.isEmpty()) return null
        for (block in textBlocks) {
            if (offset in block.pageTextStart..block.pageTextEnd) return block
        }
        return textBlocks.first()
    }

    /** The text block nearest a 0..1 vertical position on the displayed page. */
    fun blockForFraction(fraction: Double): ReflowBlock? {
        val textBlocks = blocks.filter { it.isText }
        if (textBlocks.isEmpty()) return null
        var best = textBlocks.first()
        var bestDistance = Double.MAX_VALUE
        for (block in textBlocks) {
            val distance = kotlin.math.abs(fractionOf(block) - fraction)
            if (distance < bestDistance) {
                bestDistance = distance
                best = block
            }
        }
        return best
    }

    companion object {
        /** A page the extractor could not read at all. */
        fun empty(index: Int, box: PageBox): ReflowPage =
            ReflowPage(index, emptyList(), "", false, box, "")
    }
}
