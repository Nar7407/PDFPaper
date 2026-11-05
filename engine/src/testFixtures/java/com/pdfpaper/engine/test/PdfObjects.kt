package com.pdfpaper.engine.test

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfString

/**
 * Terse PDF object builders for tests that construct dictionaries directly
 * (instead of parsing them). Names are intentionally short: `n("DeviceGray")`,
 * `i(8)`, `r(0.5)`, `arr(...)`, `str(0xFF, 0x00)`, `dictOf("Width" to i(1))`.
 */
fun n(name: String): PdfName = PdfName(name)

fun i(value: Long): PdfInteger = PdfInteger(value)

fun r(value: Double): PdfReal = PdfReal(value)

fun arr(vararg items: PdfObject): PdfArray = PdfArray(items.toList())

fun str(vararg bytes: Int): PdfString = PdfString(ByteArray(bytes.size) { bytes[it].toByte() })

fun dictOf(vararg entries: Pair<String, PdfObject>): PdfDict =
    PdfDict(entries.associate { PdfName(it.first) to it.second })
