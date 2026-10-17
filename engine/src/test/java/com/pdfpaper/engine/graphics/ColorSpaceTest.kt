package com.pdfpaper.engine.graphics

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.arr
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.test.dictOf
import com.pdfpaper.engine.test.i
import com.pdfpaper.engine.test.n
import com.pdfpaper.engine.test.r
import com.pdfpaper.engine.test.str
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T8 acceptance: colour space resolution and conversion (§8.6.3-8.6.6):
 * Device*, Cal*, Indexed (string and stream lookups), ICCBased via /Alternate
 * or component count, Separation/DeviceN tint transforms and named spaces in
 * /Resources /ColorSpace.
 */
class ColorSpaceTest {

    private val doc: PdfDocument by lazy { openPdf(emptyList()) }

    private fun openPdf(extra: List<TestObj>): PdfDocument = PdfDocument.open(
        ByteArraySource(
            buildTestPdf(
                listOf(
                    TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                    TestObj(2, 0, "<< /Type /Pages /Kids [] /Count 0 >>")
                ) + extra
            )
        )
    ).getOrThrow()

    private fun streamObj(num: Int, dict: String, data: ByteArray): TestObj {
        val body = ("<< /Length ${data.size} $dict>>\nstream\n").toByteArray(StandardCharsets.US_ASCII) +
            data + "\nendstream".toByteArray(StandardCharsets.US_ASCII)
        return TestObj(num, 0, body)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertTrue(abs(expected - actual) < tolerance, "expected $expected, got $actual")
    }

    // -------------------------------------------------------------- device

    @Test
    fun deviceConversions() {
        assertEquals(PdfColor(0.25, 0.25, 0.25), DeviceGrayCS.toRgb(doubleArrayOf(0.25)))
        assertEquals(PdfColor(0.1, 0.2, 0.3), DeviceRgbCS.toRgb(doubleArrayOf(0.1, 0.2, 0.3)))
        // §8.6.4.3 conversion without an ICC profile.
        assertEquals(PdfColor(0.0, 1.0, 1.0), DeviceCmykCS.toRgb(doubleArrayOf(1.0, 0.0, 0.0, 0.0)))
        assertEquals(PdfColor(0.0, 0.0, 0.0), DeviceCmykCS.toRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0)))
    }

    // ---------------------------------------------------------------- Cal

    @Test
    fun calRgbAppliesGammaAndMatrix() {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val space = CalRgbCS(doubleArrayOf(2.0, 2.0, 2.0), identity)
        val color = space.toRgb(doubleArrayOf(0.5, 0.0, 0.0))
        assertClose(0.25, color.red)
        assertClose(0.0, color.green)
    }

    @Test
    fun calGrayAppliesGamma() {
        val color = CalGrayCS(2.0).toRgb(doubleArrayOf(0.5))
        assertClose(0.25, color.red)
    }

    // ------------------------------------------------------------ Indexed

    @Test
    fun indexedStringLookup() {
        val space = IndexedCS(DeviceRgbCS, 1, byteArrayOf(0xFF.toByte(), 0, 0, 0, 0xFF.toByte(), 0))
        // §8.6.6.3: the component is the palette index itself.
        assertEquals(PdfColor(1.0, 0.0, 0.0), space.toRgb(doubleArrayOf(0.0)))
        assertEquals(PdfColor(0.0, 1.0, 0.0), space.toRgb(doubleArrayOf(1.0)))
        assertEquals(1, space.components)
    }

    @Test
    fun indexedResolvesThroughArray() {
        val resolved = ColorSpaces.resolve(
            arr(n("Indexed"), n("DeviceRGB"), i(1), str(0xFF, 0x00, 0x00, 0x00, 0xFF, 0x00)),
            null,
            doc
        )
        val space = resolved.space!!
        assertTrue(space is IndexedCS)
        assertEquals(PdfColor(0.0, 1.0, 0.0), space.toRgb(doubleArrayOf(1.0)))
    }

    @Test
    fun indexedStreamLookupIsDecoded() {
        val document = openPdf(listOf(streamObj(5, "", byteArrayOf(0, 17))))
        val resolved = ColorSpaces.resolve(
            arr(n("Indexed"), n("DeviceGray"), i(1), PdfRef(5, 0)),
            null,
            document
        )
        val space = resolved.space!!
        assertClose(0.0, space.toRgb(doubleArrayOf(0.0)).red)
        assertClose(17.0 / 255.0, space.toRgb(doubleArrayOf(1.0)).red)
    }

    // ----------------------------------------------------------- ICCBased

    @Test
    fun iccBasedUsesAlternate() {
        val document = openPdf(listOf(streamObj(5, "/N 3 /Alternate /DeviceRGB ", ByteArray(0))))
        val space = ColorSpaces.resolve(arr(n("ICCBased"), PdfRef(5, 0)), null, document).space
        assertSame(DeviceRgbCS, space)
    }

    @Test
    fun iccBasedFallsBackToComponentCount() {
        val document = openPdf(listOf(streamObj(5, "/N 4 ", ByteArray(0))))
        val space = ColorSpaces.resolve(arr(n("ICCBased"), PdfRef(5, 0)), null, document).space
        assertSame(DeviceCmykCS, space)
    }

    // ----------------------------------------------------- Separation/DeviceN

    @Test
    fun separationUsesTintTransform() {
        val tint = dictOf(
            "FunctionType" to i(2), "Domain" to arr(r(0.0), r(1.0)),
            "C0" to arr(r(0.0), r(0.0), r(0.0), r(0.0)),
            "C1" to arr(r(0.0), r(0.0), r(0.0), r(1.0)),
            "N" to i(1)
        )
        val space = ColorSpaces.resolve(
            arr(n("Separation"), n("Spot"), n("DeviceCMYK"), tint), null, doc
        ).space!!
        assertEquals(1, space.components)
        val half = space.toRgb(doubleArrayOf(0.5))
        assertClose(0.5, half.red)
        assertClose(0.5, half.green)
        assertClose(0.5, half.blue)
    }

    @Test
    fun deviceNKeepsItsComponentCount() {
        val tint = dictOf(
            "FunctionType" to i(2), "Domain" to arr(r(0.0), r(1.0)),
            "C0" to arr(r(0.0), r(0.0), r(0.0), r(0.0)),
            "C1" to arr(r(0.0), r(0.0), r(0.0), r(1.0)),
            "N" to i(1)
        )
        val space = ColorSpaces.resolve(
            arr(n("DeviceN"), arr(n("Cyan"), n("Magenta")), n("DeviceCMYK"), tint), null, doc
        ).space!!
        assertEquals(2, space.components)
    }

    // ------------------------------------------------------------- named

    @Test
    fun resourceNamedSpaceResolves() {
        val resources = dictOf("ColorSpace" to dictOf("CS1" to n("DeviceGray")))
        assertSame(DeviceGrayCS, ColorSpaces.resolve(PdfName("CS1"), resources, doc).space)
    }

    @Test
    fun unknownSpaceWarnsAndYieldsNull() {
        val resolved = ColorSpaces.resolve(n("NoSuchSpace"), null, doc)
        assertNull(resolved.space)
        assertTrue(resolved.warnings.any { it.contains("NoSuchSpace") }, "warnings: ${resolved.warnings}")
    }

    @Test
    fun patternIsRecognisedButNotPainted() {
        assertSame(PatternCS, ColorSpaces.resolve(n("Pattern"), null, doc).space)
    }

    @Test
    fun missingColorSpaceWarns() {
        val resolved = ColorSpaces.resolve(null, null, doc)
        assertNull(resolved.space)
        assertTrue(resolved.warnings.isNotEmpty())
    }

    @Test
    fun separationWithoutTintTransformFallsBackToAlternate() {
        val resolved = ColorSpaces.resolve(arr(n("Separation"), n("Spot"), n("DeviceRGB"), i(0)), null, doc)
        val space = resolved.space!!
        assertEquals(PdfColor(0.0, 0.0, 0.0), space.toRgb(doubleArrayOf(0.5)))
        assertTrue(resolved.warnings.any { it.contains("tint transform") }, "warnings: ${resolved.warnings}")
    }
}
