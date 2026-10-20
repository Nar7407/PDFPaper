package com.pdfpaper.engine.links

import com.pdfpaper.engine.geom.PdfRect

/**
 * What a link or in-text reference points at (T36/T61). Link annotations and
 * text references share one model so the app can show the same preview card
 * for both.
 */
enum class LinkKind {
    /** A /Link annotation with a GoTo destination. */
    LINK,

    /** A /Link annotation with a /URI action. */
    URI,

    /** A /Link annotation that points at another file (GoToR or Launch). */
    FILE,

    /** A numbered citation such as "[3]" or "[3, 5-7]". */
    CITATION,

    /** "Figure 2.1" / "Fig. 3". */
    FIGURE,

    /** "Table 4". */
    TABLE,

    /** "Section 3.2" / "§3.2". */
    SECTION,

    /** "Page 48". */
    PAGE,

    /** "Equation 3" / "Eq. (3)". */
    EQUATION
}

/** Destination fit types of ISO 32000-1 Table 151. */
enum class LinkFit { XYZ, FIT, FIT_H, FIT_V, FIT_RECT, FIT_B, FIT_BH, FIT_BV, UNKNOWN }

/**
 * A resolved internal destination. [top] is the top edge in PDF user space;
 * the other values are present only when the destination supplied them.
 */
class LinkDestination(
    val pageIndex: Int,
    val fit: LinkFit = LinkFit.UNKNOWN,
    val left: Double? = null,
    val top: Double? = null,
    val zoom: Double? = null,
    val rect: PdfRect? = null
)

/**
 * One resolved target, with everything the preview card needs. [previewRect]
 * is the bibliography entry, caption, heading and first paragraph, or page
 * region in PDF user space; the app renders only that region.
 *
 * [pageFractionTop] is the preview's top after the page's /Rotate is applied,
 * as 0..1 from the top of the displayed page. The viewer uses it to scroll to
 * the same place on screen without guessing from the fit type.
 */
class LinkTarget(
    val pageIndex: Int,
    val destination: LinkDestination,
    val title: String,
    val previewRect: PdfRect,
    val pageFractionTop: Double,
    /** What kind of text the target is, e.g. "Bibliography entry". */
    val label: String,
    /** Plain text of the preview region for the Copy text action. */
    val copyText: String,
    val note: String? = null
)

/**
 * A tappable link or reference. [rect] is in the source page's user space.
 * External links have no [targets] but carry [uri] or [file]. A reference whose
 * target could not be found carries a clear [note] instead.
 */
class DocumentLink(
    val sourcePageIndex: Int,
    val rect: PdfRect,
    val kind: LinkKind,
    /** The visible link text or reference, e.g. "[3, 5-7]". */
    val text: String,
    val targets: List<LinkTarget> = emptyList(),
    val uri: String? = null,
    val file: String? = null,
    val note: String? = null
) {
    val isExternal: Boolean get() = uri != null || file != null
    val isResolved: Boolean get() = targets.isNotEmpty()
}

/**
 * All links and references found in a document, indexed for constant-time hit
 * testing. The app builds this once per document in the background.
 */
class LinkIndex internal constructor(
    private val byPage: Map<Int, List<DocumentLink>>,
    /** Every resolved target in reading order (for tests and accessibility). */
    val targets: List<LinkTarget>
) {

    fun linksOnPage(pageIndex: Int): List<DocumentLink> = byPage[pageIndex].orEmpty()

    /**
     * The link under a page-space point. Annotation links win over in-text
     * references; within one group the smallest rectangle wins, so a citation
     * inside a link does not steal the tap.
     */
    fun hitTest(pageIndex: Int, x: Double, y: Double): DocumentLink? {
        var best: DocumentLink? = null
        var bestArea = Double.MAX_VALUE
        var bestAnnotation = false
        for (link in byPage[pageIndex].orEmpty()) {
            if (!link.rect.contains(x, y)) continue
            val annotation = link.kind == LinkKind.LINK ||
                link.kind == LinkKind.URI ||
                link.kind == LinkKind.FILE
            val area = link.rect.width * link.rect.height
            if (annotation && !bestAnnotation || annotation == bestAnnotation && area < bestArea) {
                best = link
                bestArea = area
                bestAnnotation = annotation
            }
        }
        return best
    }
}

/** Human labels used by the app's preview card and accessibility text. */
fun LinkKind.label(): String = when (this) {
    LinkKind.LINK -> "Link"
    LinkKind.URI -> "Web link"
    LinkKind.FILE -> "Linked file"
    LinkKind.CITATION -> "Citation"
    LinkKind.FIGURE -> "Figure"
    LinkKind.TABLE -> "Table"
    LinkKind.SECTION -> "Section"
    LinkKind.PAGE -> "Page reference"
    LinkKind.EQUATION -> "Equation"
}
