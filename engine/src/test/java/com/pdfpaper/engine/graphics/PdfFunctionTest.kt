package com.pdfpaper.engine.graphics

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.arr
import com.pdfpaper.engine.test.buildTestPdf
import com.pdfpaper.engine.test.dictOf
import com.pdfpaper.engine.test.i
import com.pdfpaper.engine.test.r
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T8 acceptance: PDF function objects (§7.10). Type 2 (exponential), Type 3
 * (stitching) and Type 0 (sampled, nearest-neighbour) are implemented;
 * Type 4 degrades to null.
 */
class PdfFunctionTest {

    private val doc: PdfDocument by lazy {
        PdfDocument.open(
            ByteArraySource(
                buildTestPdf(
                    listOf(
                        TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                        TestObj(2, 0, "<< /Type /Pages /Kids [] /Count 0 >>")
                    )
                )
            )
        ).getOrThrow()
    }

    private fun resolve(obj: com.pdfpaper.engine.model.PdfObject): PdfFunction? = PdfFunctions.resolve(obj, doc)

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue(abs(expected - actual) < tolerance, "expected $expected, got $actual")
    }

    // ------------------------------------------------------------- Type 2

    @Test
    fun exponentialInterpolation() {
        val function = resolve(
            dictOf(
                "FunctionType" to i(2), "Domain" to arr(r(0.0), r(1.0)),
                "C0" to arr(r(0.0)), "C1" to arr(r(1.0)), "N" to i(2)
            )
        )!!
        assertClose(0.0, function.evaluate(doubleArrayOf(0.0))[0])
        assertClose(0.25, function.evaluate(doubleArrayOf(0.5))[0])
        assertClose(1.0, function.evaluate(doubleArrayOf(1.0))[0])
    }

    @Test
    fun exponentialClampsInputToDomain() {
        val function = resolve(
            dictOf(
                "FunctionType" to i(2), "Domain" to arr(r(0.0), r(1.0)),
                "C0" to arr(r(0.0)), "C1" to arr(r(1.0)), "N" to i(1)
            )
        )!!
        assertClose(0.0, function.evaluate(doubleArrayOf(-5.0))[0])
        assertClose(1.0, function.evaluate(doubleArrayOf(9.0))[0])
    }

    @Test
    fun exponentialWithSeveralOutputs() {
        val function = resolve(
            dictOf(
                "FunctionType" to i(2), "Domain" to arr(r(0.0), r(1.0)),
                "C0" to arr(r(0.0), r(0.5)), "C1" to arr(r(1.0), r(1.0)), "N" to i(1)
            )
        )!!
        val out = function.evaluate(doubleArrayOf(0.5))
        assertClose(0.5, out[0])
        assertClose(0.75, out[1])
    }

    @Test
    fun exponentialRangeClampsOutput() {
        val function = resolve(
            dictOf(
                "FunctionType" to i(2), "Domain" to arr(r(0.0), r(1.0)),
                "Range" to arr(r(0.0), r(1.0)),
                "C0" to arr(r(0.0)), "C1" to arr(r(2.0)), "N" to i(1)
            )
        )!!
        assertClose(1.0, function.evaluate(doubleArrayOf(1.0))[0])
    }

    // ------------------------------------------------------------- Type 3

    @Test
    fun stitchingSelectsSubFunctions() {
        val linear = dictOf(
            "FunctionType" to i(2), "Domain" to arr(r(0.0), r(1.0)),
            "C0" to arr(r(0.0)), "C1" to arr(r(1.0)), "N" to i(1)
        )
        val square = dictOf(
            "FunctionType" to i(2), "Domain" to arr(r(0.0), r(1.0)),
            "C0" to arr(r(0.0)), "C1" to arr(r(1.0)), "N" to i(2)
        )
        val stitch = resolve(
            dictOf(
                "FunctionType" to i(3), "Domain" to arr(r(0.0), r(1.0)),
                "Functions" to arr(linear, square),
                "Bounds" to arr(r(0.5)),
                "Encode" to arr(arr(r(0.0), r(1.0)), arr(r(0.0), r(1.0)))
            )
        )!!
        // Encode maps the sub-domain position onto each sub-function's /Domain
        // (§7.10.4), so 0.25 lands halfway through the first (linear) function
        // and 0.75 halfway through the second (squared) one.
        assertClose(0.5, stitch.evaluate(doubleArrayOf(0.25))[0])
        assertClose(0.25, stitch.evaluate(doubleArrayOf(0.75))[0])
    }

    // ------------------------------------------------------------- Type 0

    @Test
    fun sampledFunctionNearestNeighbour() {
        val stream = PdfStream(
            dictOf(
                "FunctionType" to i(0), "Domain" to arr(r(0.0), r(1.0)),
                "Size" to arr(i(2)), "BitsPerSample" to i(8),
                "Range" to arr(r(0.0), r(1.0)),
                "Encode" to arr(r(0.0), r(1.0)),
                "Decode" to arr(r(0.0), r(1.0))
            ),
            byteArrayOf(0, 255.toByte())
        )
        val function = PdfFunctions.resolve(stream, doc)!!
        assertClose(0.0, function.evaluate(doubleArrayOf(0.1))[0])
        assertClose(1.0, function.evaluate(doubleArrayOf(0.9))[0])
    }

    // ----------------------------------------------------------- unsupported

    @Test
    fun postScriptCalculatorIsUnsupported() {
        assertNull(resolve(dictOf("FunctionType" to i(4), "Domain" to arr(r(0.0), r(1.0)))))
    }

    @Test
    fun missingFunctionTypeIsUnresolvable() {
        assertNull(resolve(dictOf("Domain" to arr(r(0.0), r(1.0)))))
    }

    @Test
    fun stitchWithWrongBoundsCountIsRejected() {
        val linear: PdfDict = dictOf(
            "FunctionType" to i(2), "Domain" to arr(r(0.0), r(1.0)),
            "C0" to arr(r(0.0)), "C1" to arr(r(1.0)), "N" to i(1)
        )
        assertNull(
            resolve(
                dictOf(
                    "FunctionType" to i(3), "Domain" to arr(r(0.0), r(1.0)),
                    "Functions" to arr(linear, linear, linear),
                    "Bounds" to arr(r(0.5))
                )
            )
        )
    }
}
