package com.pdfpaper.engine.stamp

import com.pdfpaper.engine.edit.ImageFormat
import com.pdfpaper.engine.graphics.PdfColor
import java.io.File
import java.util.Base64

/**
 * Save and load of the custom stamp list (task T23).
 *
 * The file is one JSON array of stamp objects. A text stamp carries `text`
 * and the three `[r, g, b]` colours; an image stamp carries the base64 of the
 * original JPEG or PNG bytes plus `format`, `width` and `height`, so the list
 * is self-contained. Unknown keys are ignored and every value is checked:
 * a hand-edited file is a failed [Result], never a crash.
 *
 * ```
 * [
 *   { "id": "ship-it", "label": "Ship it", "text": "SHIP IT",
 *     "textColor": [0, 0.4, 0], "borderColor": [0, 0.4, 0] },
 *   { "id": "logo", "label": "Our logo", "format": "png",
 *     "width": 240, "height": 120, "image": "iVBORw0KG..." }
 * ]
 * ```
 */
object StampJson {

    /** The JSON list for [stamps]; an empty list is `[]`. */
    fun encode(stamps: List<CustomStamp>): String {
        if (stamps.isEmpty()) return "[]"
        val out = StringBuilder("[\n")
        stamps.forEachIndexed { index, stamp ->
            if (index > 0) out.append(",\n")
            out.append("  {\n")
            val fields = fields(stamp)
            fields.forEachIndexed { i, (key, value) ->
                out.append("    \"").append(key).append("\": ").append(value)
                if (i < fields.size - 1) out.append(',')
                out.append('\n')
            }
            out.append("  }")
        }
        out.append("\n]")
        return out.toString()
    }

    /** Reads a [StampJson] list; malformed input fails with the reason. */
    fun decode(text: String): Result<List<CustomStamp>> = runCatching {
        val root = MiniJson.parse(text).getOrThrow()
        val array = root as? JsonValue.Arr
            ?: error("the custom stamp list must be a JSON array")
        val ids = HashSet<String>()
        array.items.mapIndexed { index, item ->
            val stamp = stamp(item, index)
            if (!ids.add(stamp.id.lowercase())) error("duplicate stamp id '${stamp.id}'")
            stamp
        }
    }

    /** [decode] of a UTF-8 file; a missing or unreadable file is a failure. */
    fun load(file: File): Result<List<CustomStamp>> = runCatching {
        decode(file.readText(Charsets.UTF_8)).getOrThrow()
    }

    /** Writes [stamps] to [file] as UTF-8. */
    fun save(file: File, stamps: List<CustomStamp>): Result<Unit> = runCatching {
        file.writeText(encode(stamps), Charsets.UTF_8)
    }

    // -- writing --------------------------------------------------------------

    private fun fields(stamp: CustomStamp): List<Pair<String, String>> {
        val fields = ArrayList<Pair<String, String>>()
        fields += "id" to jsonString(stamp.id)
        fields += "label" to jsonString(stamp.label)
        if (stamp.isImage) {
            fields += "format" to jsonString((stamp.imageFormat ?: ImageFormat.PNG).name.lowercase())
            fields += "width" to stamp.imageWidth.toString()
            fields += "height" to stamp.imageHeight.toString()
            fields += "image" to jsonString(Base64.getEncoder().encodeToString(stamp.image))
        } else {
            fields += "text" to jsonString(stamp.text.orEmpty())
        }
        fields += "textColor" to colorArray(stamp.textColor)
        fields += "borderColor" to colorArray(stamp.borderColor)
        stamp.fillColor?.let { fields += "fillColor" to colorArray(it) }
        return fields
    }

    private fun colorArray(color: PdfColor): String =
        "[${jsonNumber(color.red)}, ${jsonNumber(color.green)}, ${jsonNumber(color.blue)}]"

    /** A compact JSON number; six decimals are enough for a colour. */
    private fun jsonNumber(value: Double): String {
        val rounded = Math.round(value * 1_000_000.0) / 1_000_000.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else rounded.toString()
    }

    private fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c.code < 0x20 -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    // -- reading --------------------------------------------------------------

    private fun stamp(item: JsonValue, index: Int): CustomStamp {
        val obj = item as? JsonValue.Obj ?: error("stamp ${index + 1} is not a JSON object")
        val id = requiredString(obj, "id", index)
        val label = requiredString(obj, "label", index)
        val text = optionalString(obj, "text", index)
        val image = optionalString(obj, "image", index)
        val textColor = color(obj, "textColor", index) ?: PdfColor.BLACK
        val borderColor = color(obj, "borderColor", index) ?: PdfColor.BLACK
        val fillColor = color(obj, "fillColor", index)
        return when {
            text != null && image == null ->
                CustomStamp.text(id, label, text, textColor, borderColor, fillColor).getOrThrow()
            image != null && text == null ->
                imageStamp(obj, index, id, label, image, textColor, borderColor, fillColor)
            text != null -> error("stamp '$id' has both text and an image")
            else -> error("stamp '$id' has neither text nor an image")
        }
    }

    private fun imageStamp(
        obj: JsonValue.Obj,
        index: Int,
        id: String,
        label: String,
        base64: String,
        textColor: PdfColor,
        borderColor: PdfColor,
        fillColor: PdfColor?
    ): CustomStamp {
        val cleanId = CustomStamp.validId(id)
        val cleanLabel = CustomStamp.validLabel(label)
        val formatName = requiredString(obj, "format", index)
        val format = ImageFormat.entries.firstOrNull { it.name.equals(formatName, ignoreCase = true) }
            ?: error("stamp '$cleanId' has unknown image format '$formatName' (use png or jpeg)")
        val width = positiveInt(obj, "width", index)
        val height = positiveInt(obj, "height", index)
        if (width.toLong() * height > 100_000_000L) error("stamp '$cleanId' image is too large")
        val bytes = runCatching { Base64.getMimeDecoder().decode(base64) }
            .getOrElse { error("stamp '$cleanId' image is not valid base64") }
        if (!matchesFormat(format, bytes)) {
            error("stamp '$cleanId' image data does not match format '$formatName'")
        }
        return CustomStamp(
            cleanId, cleanLabel, image = bytes, imageFormat = format,
            imageWidth = width, imageHeight = height,
            textColor = textColor, borderColor = borderColor, fillColor = fillColor
        )
    }

    private fun matchesFormat(format: ImageFormat, bytes: ByteArray): Boolean = when (format) {
        ImageFormat.JPEG -> bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        ImageFormat.PNG -> bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    }

    private fun requiredString(obj: JsonValue.Obj, key: String, index: Int): String {
        val value = obj.entries[key] ?: error("stamp ${index + 1} is missing '$key'")
        return (value as? JsonValue.Str)?.value ?: error("stamp ${index + 1}: '$key' must be a string")
    }

    private fun optionalString(obj: JsonValue.Obj, key: String, index: Int): String? {
        val value = obj.entries[key] ?: return null
        return (value as? JsonValue.Str)?.value ?: error("stamp ${index + 1}: '$key' must be a string")
    }

    private fun positiveInt(obj: JsonValue.Obj, key: String, index: Int): Int {
        val value = obj.entries[key] ?: error("stamp ${index + 1} is missing '$key'")
        val number = (value as? JsonValue.Num)?.value ?: error("stamp ${index + 1}: '$key' must be a number")
        if (number != Math.floor(number) || number <= 0.0 || number > 100_000.0) {
            error("stamp ${index + 1}: '$key' must be a positive whole number")
        }
        return number.toInt()
    }

    private fun color(obj: JsonValue.Obj, key: String, index: Int): PdfColor? {
        val value = obj.entries[key] ?: return null
        val array = value as? JsonValue.Arr ?: error("stamp ${index + 1}: '$key' must be an [r, g, b] array")
        if (array.items.size != 3 || array.items.any { it !is JsonValue.Num }) {
            error("stamp ${index + 1}: '$key' must be three numbers")
        }
        val rgb = array.items.map { (it as JsonValue.Num).value.coerceIn(0.0, 1.0) }
        return PdfColor(rgb[0], rgb[1], rgb[2])
    }
}
