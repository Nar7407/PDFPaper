package com.pdfpaper.engine.stamp

import com.pdfpaper.engine.graphics.PdfColor

/**
 * The stamp library groups (task T23). The order is the order a picker shows;
 * [CUSTOM] holds the stamps the user defined and saved in a JSON list.
 */
enum class StampGroup(val label: String) {
    DYNAMIC("Dynamic"),
    SIGN_HERE("Sign here"),
    STANDARD_BUSINESS("Standard business"),
    CUSTOM("Custom")
}

/** How [StampPainter] lays a stamp's appearance out. */
enum class StampArt {
    /** The word in a single ruled banner; dynamic stamps add their by/at line. */
    BANNER,
    /** The word in a double-ruled serif frame, for business stamps. */
    CERTIFICATE,
    /** The word above an arrow, for signature places. */
    ARROW,
    /** A picture filling the stamp box (custom image stamps). */
    IMAGE
}

/**
 * One stamp design: the data a picker lists and the writer paints. A design is
 * plain data — id, label, group, colours and layout — so the library is not
 * tied to any UI, the CLI and a future picker read the same list.
 *
 * [aspect] is the width / height ratio of the generated appearance; [text] is
 * the word the banner, certificate and arrow styles paint (defaults to the
 * upper-cased [label]); [standardName] is written as the annotation's /Name
 * when the design matches a standard stamp name (§12.5.6.7, Table 166).
 */
class StampDesign(
    val id: String,
    val label: String,
    val group: StampGroup,
    val art: StampArt = StampArt.BANNER,
    val textColor: PdfColor = PdfColor.BLACK,
    val borderColor: PdfColor = PdfColor.BLACK,
    val fillColor: PdfColor? = null,
    /** Dynamic designs get a "By <author> / at <date>" line when placed. */
    val dynamic: Boolean = false,
    val aspect: Double = 2.6,
    val text: String? = null,
    val standardName: String? = null
) {

    /** The word the banner, certificate and arrow appearances paint. */
    val word: String get() = text ?: label.uppercase()

    override fun toString(): String = "StampDesign($id, $label)"
}

/**
 * The stamps available to place: the built-ins ([StampLibrary]) plus the custom
 * stamps loaded from a JSON list. Lookup is case-insensitive; a custom stamp
 * with the same id as a built-in replaces it.
 */
class StampCatalogue(custom: List<CustomStamp> = emptyList()) {

    private val customsById: Map<String, CustomStamp> = custom.associateBy { it.id.lowercase() }

    val stamps: List<StampDesign> = StampLibrary.builtIn + custom.map { it.toDesign() }

    private val designsById: Map<String, StampDesign> = stamps.associateBy { it.id.lowercase() }

    fun find(id: String): StampDesign? = designsById[id.trim().lowercase()]

    /** The custom stamp behind [id], when there is one. */
    fun custom(id: String): CustomStamp? = customsById[id.trim().lowercase()]

    /** The stamps grouped in the library's display order. */
    fun groups(): List<Pair<StampGroup, List<StampDesign>>> =
        StampGroup.entries.map { group -> group to stamps.filter { it.group == group } }
}
