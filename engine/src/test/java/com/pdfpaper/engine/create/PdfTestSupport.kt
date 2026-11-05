package com.pdfpaper.engine.create

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.page
import com.pdfpaper.engine.text.extractText
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun openPdf(bytes: ByteArray): PdfDocument =
    PdfDocument.open(ByteArraySource(bytes)).getOrThrow()

internal fun pageOf(document: PdfDocument, index: Int): PdfPage =
    document.page(index) ?: error("no page $index")

/** MediaBox as [llx, lly, urx, ury]. */
internal fun mediaBox(page: PdfPage): List<Double> {
    val box = page.mediaBox ?: error("page has no MediaBox")
    return box.items.map { value ->
        when (value) {
            is PdfInteger -> value.value.toDouble()
            is PdfReal -> value.value
            else -> error("unexpected MediaBox value: $value")
        }
    }
}

internal fun textOf(page: PdfPage): String =
    page.extractText().runs.joinToString(separator = "") { it.text }

internal fun textRuns(page: PdfPage): List<String> =
    page.extractText().runs.map { it.text }

internal fun contentOf(page: PdfPage): String {
    val contents = page.contents().getOrThrow() ?: error("page has no contents")
    return contents.bytes.toString(Charsets.US_ASCII)
}

internal fun assertClose(expected: Double, actual: Double, tolerance: Double = 0.01) {
    assertTrue(
        kotlin.math.abs(expected - actual) <= tolerance,
        "expected $expected +/- $tolerance but was $actual"
    )
}

/** A solid raster encoded as [format] ("png" or "jpg") by the JDK codec. */
internal fun imageBytes(
    format: String,
    width: Int,
    height: Int,
    color: Int = 0xFF336699.toInt()
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = Color(color)
    graphics.fillRect(0, 0, width, height)
    graphics.dispose()
    val out = ByteArrayOutputStream()
    check(ImageIO.write(image, format, out)) { "no ImageIO writer for $format" }
    return out.toByteArray()
}

/** Reads the image stream of the first XObject on [page]. */
internal fun firstImage(page: PdfPage): com.pdfpaper.engine.model.PdfStream {
    val resources = page.dict["Resources"] as? com.pdfpaper.engine.model.PdfDict
        ?: error("page has no resources")
    val xobjects = resources["XObject"] as? com.pdfpaper.engine.model.PdfDict
        ?: error("resources have no XObject dictionary")
    return xobjects.entries.values.filterIsInstance<com.pdfpaper.engine.model.PdfRef>()
        .map { page.document.resolve(it) }
        .filterIsInstance<com.pdfpaper.engine.model.PdfStream>()
        .firstOrNull()
        ?: error("no image stream found")
}

internal fun PdfObject.asDouble(): Double = when (this) {
    is PdfInteger -> value.toDouble()
    is PdfReal -> value
    else -> error("not a number: $this")
}

internal fun assertEqualsList(expected: List<Double>, actual: List<Double>, tolerance: Double = 0.01) {
    assertEquals(expected.size, actual.size)
    expected.zip(actual).forEach { (e, a) -> assertClose(e, a, tolerance) }
}
