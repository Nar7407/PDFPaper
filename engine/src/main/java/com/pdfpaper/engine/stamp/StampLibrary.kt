package com.pdfpaper.engine.stamp

import com.pdfpaper.engine.graphics.PdfColor

/**
 * The built-in stamp library (task T23): grouped designs with an id, a label
 * and the colours the generated appearance uses. The styling is this
 * project's own — ruled banners, certificate frames and a signature arrow —
 * not a copy of any other product's artwork.
 */
object StampLibrary {

    private val GREEN = PdfColor(0.03, 0.45, 0.18)
    private val BLUE = PdfColor(0.10, 0.32, 0.62)
    private val TEAL = PdfColor(0.04, 0.42, 0.42)
    private val ORANGE = PdfColor(0.72, 0.38, 0.03)
    private val INK = PdfColor(0.18, 0.18, 0.20)
    private val RED = PdfColor(0.72, 0.08, 0.10)
    private val NAVY = PdfColor(0.10, 0.20, 0.48)
    private val SLATE = PdfColor(0.32, 0.35, 0.42)

    /** Every built-in design, in library order. */
    val builtIn: List<StampDesign> = listOf(
        // Dynamic: the placement adds "By <author>" and the date time.
        StampDesign(
            "approved", "Approved", StampGroup.DYNAMIC,
            textColor = GREEN, borderColor = GREEN, fillColor = PdfColor(0.94, 0.98, 0.94),
            dynamic = true, aspect = 3.0, standardName = "Approved"
        ),
        StampDesign(
            "reviewed", "Reviewed", StampGroup.DYNAMIC,
            textColor = BLUE, borderColor = BLUE, fillColor = PdfColor(0.94, 0.96, 0.99),
            dynamic = true, aspect = 3.0
        ),
        StampDesign(
            "received", "Received", StampGroup.DYNAMIC,
            textColor = TEAL, borderColor = TEAL, fillColor = PdfColor(0.93, 0.98, 0.98),
            dynamic = true, aspect = 3.0
        ),
        StampDesign(
            "revised", "Revised", StampGroup.DYNAMIC,
            textColor = ORANGE, borderColor = ORANGE, fillColor = PdfColor(0.99, 0.96, 0.91),
            dynamic = true, aspect = 3.0
        ),
        StampDesign(
            "final", "Final", StampGroup.DYNAMIC,
            textColor = INK, borderColor = INK, fillColor = PdfColor(0.95, 0.95, 0.96),
            dynamic = true, aspect = 3.0, standardName = "Final"
        ),

        // Signature places.
        StampDesign(
            "sign-here", "Sign here", StampGroup.SIGN_HERE, art = StampArt.ARROW,
            textColor = INK, borderColor = INK, aspect = 3.2
        ),
        StampDesign(
            "initial-here", "Initial here", StampGroup.SIGN_HERE,
            textColor = SLATE, borderColor = SLATE, fillColor = PdfColor(0.95, 0.96, 0.98),
            aspect = 3.2
        ),
        StampDesign(
            "witness", "Witness", StampGroup.SIGN_HERE,
            textColor = PdfColor(0.32, 0.25, 0.16), borderColor = PdfColor(0.32, 0.25, 0.16),
            aspect = 2.8
        ),

        // Standard business: the certificate frame.
        StampDesign(
            "paid", "Paid", StampGroup.STANDARD_BUSINESS, art = StampArt.CERTIFICATE,
            textColor = GREEN, borderColor = GREEN, fillColor = PdfColor(0.96, 0.99, 0.96),
            aspect = 2.4
        ),
        StampDesign(
            "confidential", "Confidential", StampGroup.STANDARD_BUSINESS, art = StampArt.CERTIFICATE,
            textColor = RED, borderColor = RED, aspect = 2.4, standardName = "Confidential"
        ),
        StampDesign(
            "draft", "Draft", StampGroup.STANDARD_BUSINESS, art = StampArt.CERTIFICATE,
            textColor = SLATE, borderColor = SLATE, fillColor = PdfColor(0.96, 0.97, 0.98),
            aspect = 2.4, standardName = "Draft"
        ),
        StampDesign(
            "void", "Void", StampGroup.STANDARD_BUSINESS, art = StampArt.CERTIFICATE,
            textColor = RED, borderColor = RED, aspect = 2.4
        ),
        StampDesign(
            "copy", "Copy", StampGroup.STANDARD_BUSINESS, art = StampArt.CERTIFICATE,
            textColor = NAVY, borderColor = NAVY, aspect = 2.4
        ),
        StampDesign(
            "expired", "Expired", StampGroup.STANDARD_BUSINESS, art = StampArt.CERTIFICATE,
            textColor = PdfColor(0.50, 0.14, 0.08), borderColor = PdfColor(0.50, 0.14, 0.08),
            aspect = 2.4, standardName = "Expired"
        )
    )

    /** A built-in by id, case-insensitively; null when it does not exist. */
    fun find(id: String): StampDesign? =
        builtIn.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
}
