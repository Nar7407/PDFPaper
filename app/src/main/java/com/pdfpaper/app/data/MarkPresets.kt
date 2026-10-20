package com.pdfpaper.app.data

/**
 * The options the three mark screens remember between runs (T44).
 *
 * Only plain values are stored: the text the user typed, the sizes, the
 * switches and the name of the selected choice. The picture of a watermark is
 * not kept — it is picked again from the gallery next time.
 */

/** The last used options of the watermark screen. */
data class WatermarkPreset(
    val text: String = "CONFIDENTIAL",
    val useImage: Boolean = false,
    val fontSize: Double = 48.0,
    val opacity: Double = 0.3,
    val rotation: Double = 45.0,
    /** The name of the colour choice, e.g. "GREY". */
    val color: String = "GREY",
    /** The name of the position choice, e.g. "CENTER". */
    val position: String = "CENTER",
    val behindContent: Boolean = true,
    /** The name of the page-range choice, e.g. "ALL". */
    val rangeMode: String = "ALL",
    val rangeText: String = ""
)

/** The last used options of the page-number screen. */
data class PageNumbersPreset(
    val header: String = "",
    val footer: String = "{page}",
    /** The name of the number format, e.g. "DECIMAL". */
    val format: String = "DECIMAL",
    /** The name of the alignment, e.g. "CENTER". */
    val align: String = "CENTER",
    val fontSize: Double = 10.0,
    val marginTop: Double = 36.0,
    val marginBottom: Double = 36.0,
    val marginLeft: Double = 54.0,
    val marginRight: Double = 54.0,
    val startNumber: Int = 1,
    val skipFirstPage: Boolean = false,
    val rangeMode: String = "ALL",
    val rangeText: String = ""
)

/** The last used options of the Bates-number screen. */
data class BatesPreset(
    val prefix: String = "",
    val suffix: String = "",
    val digits: Int = 6,
    val startNumber: Int = 1,
    /** The name of the placement choice, e.g. "BOTTOM_RIGHT". */
    val placement: String = "BOTTOM_RIGHT",
    val fontSize: Double = 9.0,
    val rangeMode: String = "ALL",
    val rangeText: String = ""
)
