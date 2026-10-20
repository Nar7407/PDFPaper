package com.pdfpaper.engine.redact

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.stamp.JsonValue
import com.pdfpaper.engine.stamp.MiniJson

/**
 * Reads and writes the `marks.json` format used by the redaction CLI (T28).
 *
 * Example:
 * ```json
 * {
 *   "marks": [
 *     {
 *       "page": 1,
 *       "rects": [[72, 700, 250, 725]],
 *       "type": "area",
 *       "overlayText": "REDACTED",
 *       "color": [0, 0, 0],
 *       "expectedText": "Secret"
 *     }
 *   ]
 * }
 * ```
 *
 * `page` is one-based. `pageIndex` is also accepted and is zero-based. A mark
 * may use `rect` for one rectangle or `rects` for several selection fragments;
 * `quads` (one or more 8-number arrays) is accepted as well. `type` is one of
 * `area`, `text`, `image` or `page`.
 */
object RedactionMarksJson {

    fun parse(text: String): List<RedactionMark> {
        val root = MiniJson.parse(text).getOrElse {
            throw IllegalArgumentException("invalid marks JSON: ${it.message}")
        }
        val array = when (root) {
            is JsonValue.Arr -> root
            is JsonValue.Obj -> root.entries["marks"] as? JsonValue.Arr
                ?: throw IllegalArgumentException("marks JSON needs a 'marks' array")
            else -> throw IllegalArgumentException("marks JSON must be an array or object")
        }
        val out = ArrayList<RedactionMark>(array.items.size)
        for ((index, item) in array.items.withIndex()) {
            val obj = item as? JsonValue.Obj
                ?: throw IllegalArgumentException("mark ${index + 1} must be an object")
            out += parseMark(obj, index + 1)
        }
        return out
    }

    fun serialize(marks: List<RedactionMark>, pretty: Boolean = true): String {
        val out = StringBuilder()
        out.append('{').append(if (pretty) "\n  " else "")
        out.append("\"marks\": [")
        for ((index, mark) in marks.withIndex()) {
            if (index > 0) out.append(',')
            if (pretty) out.append("\n    ")
            out.append('{')
            out.append("\"page\": ").append(mark.pageIndex + 1)
            out.append(", \"type\": \"").append(mark.type.name.lowercase()).append('"')
            out.append(", \"rects\": [")
            for ((rectIndex, rect) in mark.rects.withIndex()) {
                if (rectIndex > 0) out.append(", ")
                out.append('[')
                    .append(number(rect.left)).append(", ")
                    .append(number(rect.bottom)).append(", ")
                    .append(number(rect.right)).append(", ")
                    .append(number(rect.top)).append(']')
            }
            out.append(']')
            mark.overlayText?.let { out.append(", \"overlayText\": \"").append(escape(it)).append('"') }
            out.append(", \"color\": [")
                .append(number(mark.color.red)).append(", ")
                .append(number(mark.color.green)).append(", ")
                .append(number(mark.color.blue)).append(']')
            mark.expectedText?.let { out.append(", \"expectedText\": \"").append(escape(it)).append('"') }
            out.append('}')
        }
        if (pretty) out.append("\n  ")
        out.append(']').append(if (pretty) "\n" else "")
        out.append('}')
        return out.toString()
    }

    private fun parseMark(obj: JsonValue.Obj, ordinal: Int): RedactionMark {
        val entries = obj.entries
        val pageIndex = when {
            entries["pageIndex"] != null -> number(entries["pageIndex"], "pageIndex", ordinal).toInt()
            entries["page"] != null -> number(entries["page"], "page", ordinal).toInt() - 1
            else -> throw IllegalArgumentException("mark $ordinal needs 'page' or 'pageIndex'")
        }
        if (pageIndex < 0) throw IllegalArgumentException("mark $ordinal has a page below 1")
        val type = RedactionMarkType.parse(string(entries["type"], "type", ordinal))
        val rects = ArrayList<PdfRect>()
        entries["rect"]?.let { rects += parseRect(it, "rect", ordinal) }
        entries["rects"]?.let { value ->
            val array = value as? JsonValue.Arr
                ?: throw IllegalArgumentException("mark $ordinal 'rects' must be an array")
            for (rect in array.items) rects += parseRect(rect, "rects", ordinal)
        }
        entries["quad"]?.let { rects += quadBounds(it, "quad", ordinal) }
        entries["quads"]?.let { value ->
            val array = value as? JsonValue.Arr
                ?: throw IllegalArgumentException("mark $ordinal 'quads' must be an array")
            for (quad in array.items) rects += quadBounds(quad, "quads", ordinal)
        }
        if (type != RedactionMarkType.PAGE && rects.isEmpty()) {
            throw IllegalArgumentException("mark $ordinal needs 'rect' or 'rects'")
        }
        val overlay = string(entries["overlayText"] ?: entries["overlay"], "overlayText", ordinal)
        val expected = string(entries["expectedText"] ?: entries["expected"], "expectedText", ordinal)
        val color = entries["color"]?.let { parseColor(it, ordinal) } ?: PdfColor(0.0, 0.0, 0.0)
        return RedactionMark(pageIndex, rects, type, overlay, color, expected)
    }

    private fun parseRect(value: JsonValue, field: String, ordinal: Int): PdfRect {
        val array = value as? JsonValue.Arr
            ?: throw IllegalArgumentException("mark $ordinal '$field' must be an array")
        if (array.items.size != 4) {
            throw IllegalArgumentException("mark $ordinal '$field' needs four numbers")
        }
        val v = DoubleArray(4) { number(array.items[it], field, ordinal) }
        return PdfRect(v[0], v[1], v[2], v[3])
    }

    private fun quadBounds(value: JsonValue, field: String, ordinal: Int): PdfRect {
        val array = value as? JsonValue.Arr
            ?: throw IllegalArgumentException("mark $ordinal '$field' must be an array of eight numbers")
        if (array.items.size != 8) {
            throw IllegalArgumentException("mark $ordinal '$field' needs eight numbers")
        }
        val points = DoubleArray(8) { number(array.items[it], field, ordinal) }
        return PdfRect(
            (0 until 8 step 2).minOf { points[it] },
            (1 until 8 step 2).minOf { points[it] },
            (0 until 8 step 2).maxOf { points[it] },
            (1 until 8 step 2).maxOf { points[it] }
        )
    }

    private fun parseColor(value: JsonValue, ordinal: Int): PdfColor {
        if (value is JsonValue.Str) {
            val hex = value.value.removePrefix("#")
            if (hex.length != 6) throw IllegalArgumentException("mark $ordinal color must be #RRGGBB")
            val rgb = hex.toIntOrNull(16)
                ?: throw IllegalArgumentException("mark $ordinal color is not hexadecimal")
            return PdfColor(
                ((rgb shr 16) and 0xFF) / 255.0,
                ((rgb shr 8) and 0xFF) / 255.0,
                (rgb and 0xFF) / 255.0
            )
        }
        val array = value as? JsonValue.Arr
            ?: throw IllegalArgumentException("mark $ordinal color must be [r,g,b]")
        if (array.items.size != 3) throw IllegalArgumentException("mark $ordinal color needs three components")
        val components = DoubleArray(3) { number(array.items[it], "color", ordinal) }
        val scale = if (components.any { it > 1.0 }) 255.0 else 1.0
        return PdfColor(
            (components[0] / scale).coerceIn(0.0, 1.0),
            (components[1] / scale).coerceIn(0.0, 1.0),
            (components[2] / scale).coerceIn(0.0, 1.0)
        )
    }

    private fun string(value: JsonValue?, field: String, ordinal: Int): String? = when (value) {
        null, JsonValue.Null -> null
        is JsonValue.Str -> value.value
        else -> throw IllegalArgumentException("mark $ordinal '$field' must be a string")
    }

    private fun number(value: JsonValue?, field: String, ordinal: Int): Double {
        val number = value as? JsonValue.Num
            ?: throw IllegalArgumentException("mark $ordinal '$field' must be a number")
        if (!number.value.isFinite()) throw IllegalArgumentException("mark $ordinal '$field' is not finite")
        return number.value
    }

    private fun number(value: Double): String {
        if (!value.isFinite()) return "0"
        val rounded = kotlin.math.round(value * 10000.0) / 10000.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        return out.toString()
    }
}

