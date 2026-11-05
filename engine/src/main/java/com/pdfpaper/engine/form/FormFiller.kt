package com.pdfpaper.engine.form

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfBoolean
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfSerializer
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

private const val MAX_FIELD_DEPTH = 64

/**
 * Writes AcroForm field values and fresh appearance streams into a document
 * that a [PdfDocumentBuilder] already holds (ISO 32000-1, §12.7.4.3 widgets,
 * §12.7.4.4 field value, §12.5.5 appearance streams).
 *
 * The target is walked through the builder itself, so it trusts nothing about
 * the source document: fields are located by /AcroForm /Fields, patched in
 * place, and their widgets get a self-contained /AP /N so every viewer shows
 * the value without needing to regenerate it. A Helvetica font is added once
 * and shared by all text/choice appearances.
 */
internal class FormFiller(private val builder: PdfDocumentBuilder) {

    private val fontRef: PdfRef by lazy {
        builder.add(
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Font"),
                    PdfName("Subtype") to PdfName("Type1"),
                    PdfName("BaseFont") to PdfName("Helvetica"),
                    PdfName("Encoding") to PdfName("WinAnsiEncoding")
                )
            )
        )
    }

    fun fill(values: Map<String, String>) {
        val rootRef = builder.root ?: return
        val catalog = builder.get(rootRef.objNum) as? PdfDict ?: return
        val acroRef = catalog["AcroForm"] as? PdfRef ?: return
        val acro = builder.get(acroRef.objNum) as? PdfDict ?: return
        val fields = (resolve(acro["Fields"]) as? PdfArray)?.items ?: return

        // We always write appearance streams, so viewers must not regenerate.
        val acroEntries = LinkedHashMap(acro.entries)
        acroEntries[PdfName("NeedAppearances")] = PdfBoolean(false)
        if (acro["DA"] == null) acroEntries[PdfName("DA")] = PdfString("/Helv 0 Tf 0 g")
        builder.put(acroRef.objNum, PdfDict(acroEntries))

        val defaultDa = (acro["DA"] as? PdfString)?.text()
        for (field in fields) fillField(field, "", Inherited(da = defaultDa), values)
    }

    // -- field tree -----------------------------------------------------------

    private class Inherited(val ft: String? = null, val ff: Int? = null, val da: String? = null)

    private fun fillField(
        node: PdfObject,
        prefix: String,
        inherited: Inherited,
        values: Map<String, String>,
        seen: MutableSet<Int> = HashSet(),
        depth: Int = 0
    ) {
        if (depth > MAX_FIELD_DEPTH) return
        val ref = node as? PdfRef
        if (ref != null && !seen.add(ref.objNum)) return
        val dict = resolve(node) as? PdfDict ?: return
        val ownName = (dict["T"] as? PdfString)?.text().orEmpty()
        val name = qualify(prefix, ownName)
        val ft = (dict["FT"] as? PdfName)?.name ?: inherited.ft
        val ff = intOf(dict["Ff"]) ?: inherited.ff
        val da = (resolve(dict["DA"]) as? PdfString)?.text() ?: inherited.da

        val kids = (dict["Kids"] as? PdfArray)?.items
        val fieldKids = kids?.filter { kid ->
            val kd = resolve(kid) as? PdfDict
            kd != null && (kd["Subtype"] as? PdfName)?.name != "Widget"
        }.orEmpty()
        if (fieldKids.isNotEmpty()) {
            val next = Inherited(ft, ff, da)
            for (kid in kids!!) if (kid in fieldKids) fillField(kid, name, next, values, seen, depth + 1)
            return
        }

        val value = values[name] ?: return
        val kind = kindOf(ft, ff)
        if (kind == FieldKind.PUSH_BUTTON || kind == FieldKind.SIGNATURE || kind == FieldKind.UNKNOWN) return

        val selfRef = node as? PdfRef
        val widgets = ArrayList<Pair<PdfRef?, PdfDict>>()
        if (dict["Rect"] != null) widgets += selfRef to dict
        kids?.forEach { kid ->
            val kd = resolve(kid) as? PdfDict ?: return@forEach
            if ((kd["Subtype"] as? PdfName)?.name == "Widget") widgets += (kid as? PdfRef) to kd
        }

        when (kind) {
            FieldKind.TEXT -> setText(selfRef, dict, widgets, value, da, ff ?: 0)
            FieldKind.CHOICE -> setChoice(selfRef, dict, widgets, value, da)
            FieldKind.CHECKBOX -> setButton(selfRef, dict, widgets, value, kind)
            FieldKind.RADIO -> setButton(selfRef, dict, widgets, value, kind)
            else -> {}
        }
    }

    // -- value writers --------------------------------------------------------

    private fun setText(
        fieldRef: PdfRef?,
        field: PdfDict,
        widgets: List<Pair<PdfRef?, PdfDict>>,
        value: String,
        da: String?,
        ff: Int
    ) {
        putField(fieldRef, field, mapOf("V" to textString(value)))
        val multiline = ff and (1 shl 12) != 0
        for ((ref, widget) in widgets) {
            val rect = PdfRect.of(widget["Rect"] as? PdfArray) ?: continue
            if (rect.isEmpty) continue
            val content = textContent(rect, value, da, multiline)
            val stream = form(listOf(0.0, 0.0, rect.width, rect.height), fontResources(), content)
            patch(ref, widget, mapOf("AP" to singleAppearance(stream)))
        }
    }

    private fun setChoice(
        fieldRef: PdfRef?,
        field: PdfDict,
        widgets: List<Pair<PdfRef?, PdfDict>>,
        value: String,
        da: String?
    ) {
        putField(fieldRef, field, mapOf("V" to textString(value)))
        val display = displayValue(field, value)
        for ((ref, widget) in widgets) {
            val rect = PdfRect.of(widget["Rect"] as? PdfArray) ?: continue
            if (rect.isEmpty) continue
            val content = textContent(rect, display, da, multiline = false)
            val stream = form(listOf(0.0, 0.0, rect.width, rect.height), fontResources(), content)
            patch(ref, widget, mapOf("AP" to singleAppearance(stream)))
        }
    }

    private fun setButton(
        fieldRef: PdfRef?,
        field: PdfDict,
        widgets: List<Pair<PdfRef?, PdfDict>>,
        value: String,
        kind: FieldKind
    ) {
        val options = optionsOf(field)
        val checked = value != "Off" && value.isNotEmpty()
        // The field value is the on-state name of the widget that is selected.
        var selectedName: String? = null
        widgets.forEachIndexed { index, (_, widget) ->
            val onName = widgetOnName(widget, index, options, kind)
            if (checked && (onName == value || (kind == FieldKind.CHECKBOX && selectedName == null))) {
                if (kind == FieldKind.RADIO && onName != value) return@forEachIndexed
                selectedName = onName
            }
        }
        val fieldValue = if (checked) (selectedName ?: value) else "Off"
        putField(fieldRef, field, mapOf("V" to PdfName(fieldValue)))

        widgets.forEachIndexed { index, (ref, widget) ->
            val rect = PdfRect.of(widget["Rect"] as? PdfArray) ?: return@forEachIndexed
            if (rect.isEmpty) return@forEachIndexed
            val onName = widgetOnName(widget, index, options, kind)
            val isSelected = checked && onName == fieldValue
            val onContent = if (kind == FieldKind.CHECKBOX) checkMark(rect) else radioDot(rect)
            val offContent = if (kind == FieldKind.CHECKBOX) checkBox(rect) else radioRing(rect)
            val onRef = form(listOf(0.0, 0.0, rect.width, rect.height), null, onContent)
            val offRef = form(listOf(0.0, 0.0, rect.width, rect.height), null, offContent)
            val ap = PdfDict(
                mapOf(
                    PdfName("N") to PdfDict(
                        mapOf(PdfName(onName) to onRef, PdfName("Off") to offRef)
                    )
                )
            )
            patch(ref, widget, mapOf("AP" to ap, "AS" to PdfName(if (isSelected) onName else "Off")))
        }
    }

    // -- appearance content ---------------------------------------------------

    private fun textContent(rect: PdfRect, value: String, da: String?, multiline: Boolean): String {
        val w = rect.width
        val h = rect.height
        val size = daFontSize(da, rect, value, multiline)
        val color = daColor(da)
        val sb = StringBuilder()
        sb.append("q ")
        // A hairline field border, as most writers draw for a text widget.
        sb.append("0.5 w 0.6 0.6 0.6 RG 0.25 0.25 ")
        sb.append(num(w - 0.5)).append(' ').append(num(h - 0.5)).append(" re S ")
        sb.append("BT /Helv ").append(num(size)).append(" Tf ")
        sb.append(num(color.red)).append(' ').append(num(color.green)).append(' ')
            .append(num(color.blue)).append(" rg ")
        if (multiline) {
            val leading = size * 1.15
            val lines = value.split('\n')
            var baseline = h - 2.0 - size * 0.8
            for (line in lines) {
                sb.append("1 0 0 1 2 ").append(num(baseline)).append(" Tm ")
                sb.append(escape(line)).append(" Tj ")
                baseline -= leading
            }
        } else {
            val baseline = (h - size) / 2.0 + size * 0.22
            sb.append("1 0 0 1 2 ").append(num(baseline)).append(" Tm ")
            sb.append(escape(value)).append(" Tj ")
        }
        sb.append("ET Q")
        return sb.toString()
    }

    private fun checkMark(rect: PdfRect): String {
        val w = rect.width
        val h = rect.height
        val stroke = (minOf(w, h) * 0.14).coerceAtLeast(1.0)
        return buildString {
            append("q ")
            append(rectBorder(w, h))
            append(' ').append(num(stroke)).append(" w 1 J 1 j 0 0 0 RG ")
            append(num(w * 0.20)).append(' ').append(num(h * 0.50)).append(" m ")
            append(num(w * 0.42)).append(' ').append(num(h * 0.28)).append(" l ")
            append(num(w * 0.80)).append(' ').append(num(h * 0.76)).append(" l S Q")
        }
    }

    private fun checkBox(rect: PdfRect): String {
        return buildString {
            append("q ")
            append(rectBorder(rect.width, rect.height))
            append(" Q")
        }
    }

    private fun radioDot(rect: PdfRect): String {
        val cx = rect.width / 2.0
        val cy = rect.height / 2.0
        val r = minOf(rect.width, rect.height) * 0.28
        return buildString {
            append("q ")
            append(rectBorder(rect.width, rect.height))
            append(" 0 0 0 rg ")
            append(circlePath(cx, cy, r))
            append(" f Q")
        }
    }

    private fun radioRing(rect: PdfRect): String {
        val cx = rect.width / 2.0
        val cy = rect.height / 2.0
        val r = minOf(rect.width, rect.height) * 0.36
        return buildString {
            append("q ")
            append(rectBorder(rect.width, rect.height))
            append(" 0.4 w 0.5 0.5 0.5 RG ")
            append(circlePath(cx, cy, r))
            append(" S Q")
        }
    }

    /** Border shared by button appearances: a thin black rectangle. */
    private fun rectBorder(w: Double, h: Double): String =
        "0.8 w 0 0 0 RG 0.4 0.4 ${num(w - 0.8)} ${num(h - 0.8)} re S"

    /** Filled/outlined circle as four cubic Béziers (no `arc` operator in PDF). */
    private fun circlePath(cx: Double, cy: Double, r: Double): String {
        val k = 0.5522847498307936 * r
        val sb = StringBuilder()
        sb.append(num(cx)).append(' ').append(num(cy + r)).append(" m ")
        sb.append(num(cx + k)).append(' ').append(num(cy + r)).append(' ')
        sb.append(num(cx + r)).append(' ').append(num(cy + k)).append(' ')
        sb.append(num(cx + r)).append(' ').append(num(cy)).append(" c ")
        sb.append(num(cx + r)).append(' ').append(num(cy - k)).append(' ')
        sb.append(num(cx + k)).append(' ').append(num(cy - r)).append(' ')
        sb.append(num(cx)).append(' ').append(num(cy - r)).append(" c ")
        sb.append(num(cx - k)).append(' ').append(num(cy - r)).append(' ')
        sb.append(num(cx - r)).append(' ').append(num(cy - k)).append(' ')
        sb.append(num(cx - r)).append(' ').append(num(cy)).append(" c ")
        sb.append(num(cx - r)).append(' ').append(num(cy + k)).append(' ')
        sb.append(num(cx - k)).append(' ').append(num(cy + r)).append(' ')
        sb.append(num(cx)).append(' ').append(num(cy + r)).append(" c")
        return sb.toString()
    }

    // -- builder plumbing -----------------------------------------------------

    private fun resolve(obj: PdfObject?): PdfObject? = when (obj) {
        null -> null
        is PdfRef -> builder.get(obj.objNum)
        else -> obj
    }

    /** Applies [updates] to the stored body at [ref], preserving earlier patches. */
    private fun patch(ref: PdfRef?, dict: PdfDict, updates: Map<String, PdfObject>) {
        if (ref == null) return
        // A merged field+widget object is patched more than once (value, then
        // appearance); always start from the latest stored body so nothing is lost.
        val current = builder.get(ref.objNum) as? PdfDict ?: dict
        val entries = LinkedHashMap(current.entries)
        for ((key, value) in updates) entries[PdfName(key)] = value
        builder.put(ref.objNum, PdfDict(entries))
    }

    private fun putField(ref: PdfRef?, field: PdfDict, updates: Map<String, PdfObject>) =
        patch(ref, field, updates)

    private fun singleAppearance(stream: PdfRef): PdfDict =
        PdfDict(mapOf(PdfName("N") to stream))

    private fun fontResources(): PdfDict = PdfDict(
        mapOf(
            PdfName("Font") to PdfDict(mapOf(PdfName("Helv") to fontRef))
        )
    )

    private fun form(bbox: List<Double>, resources: PdfDict?, content: String): PdfRef {
        val entries = LinkedHashMap<PdfName, PdfObject>()
        entries[PdfName("Type")] = PdfName("XObject")
        entries[PdfName("Subtype")] = PdfName("Form")
        entries[PdfName("FormType")] = PdfInteger(1)
        entries[PdfName("BBox")] = PdfArray(bbox.map { com.pdfpaper.engine.model.PdfReal(it) })
        if (resources != null) entries[PdfName("Resources")] = resources
        entries[PdfName("Length")] = PdfInteger(0)
        return builder.addStream(PdfDict(entries), content.toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun optionsOf(field: PdfDict): List<FormOption> {
        val items = (resolve(field["Opt"]) as? PdfArray)?.items ?: return emptyList()
        return items.mapNotNull { entry ->
            when (val v = resolve(entry)) {
                is PdfString -> FormOption(v.text(), v.text())
                is PdfArray -> {
                    val export = (resolve(v.items.getOrNull(0)) as? PdfString)?.text() ?: return@mapNotNull null
                    val display = (resolve(v.items.getOrNull(1)) as? PdfString)?.text() ?: export
                    FormOption(export, display)
                }
                else -> null
            }
        }
    }

    private fun displayValue(field: PdfDict, value: String): String =
        optionsOf(field).firstOrNull { it.exportValue == value }?.displayValue ?: value

    private fun widgetOnName(widget: PdfDict, index: Int, options: List<FormOption>, kind: FieldKind): String {
        val existing = widgetOnStateName(widget)
        return onNameFor(existing, index, options, kind)
    }

    private fun widgetOnStateName(widget: PdfDict): String? {
        val ap = resolve(widget["AP"]) as? PdfDict ?: return null
        val normal = resolve(ap["N"]) as? PdfDict ?: return null
        return normal.entries.keys.firstOrNull { it.name != "Off" }?.name
    }

    private fun kindOf(ft: String?, ff: Int?): FieldKind = when (ft) {
        "Tx" -> FieldKind.TEXT
        "Btn" -> when {
            ff != null && ff and 0x10000 != 0 -> FieldKind.PUSH_BUTTON
            ff != null && ff and 0x8000 != 0 -> FieldKind.RADIO
            else -> FieldKind.CHECKBOX
        }
        "Ch" -> FieldKind.CHOICE
        "Sig" -> FieldKind.SIGNATURE
        else -> FieldKind.UNKNOWN
    }

    private fun intOf(value: PdfObject?): Int? = (resolve(value) as? PdfInteger)?.value?.toInt()

    // -- DA parsing (§12.7.4.3 default appearance) ----------------------------

    private fun daFontSize(da: String?, rect: PdfRect, value: String, multiline: Boolean): Double {
        val declared = daSize(da)
        if (declared != null && declared > 0.0) return declared
        val longest = value.split('\n').maxOfOrNull { it.length } ?: 1
        val byHeight = (rect.height - 4.0).coerceIn(4.0, 72.0)
        if (multiline) return byHeight
        val byWidth = if (longest > 0) (rect.width - 6.0) / (longest * 0.5) else byHeight
        return minOf(byHeight, byWidth).coerceIn(4.0, 72.0)
    }

    private fun daSize(da: String?): Double? {
        if (da == null) return null
        var size: Double? = null
        var last: Double? = null
        for (token in da.trim().split(Regex("\\s+"))) {
            val d = token.toDoubleOrNull()
            if (d != null) {
                last = d
            } else {
                if (token == "Tf") size = last
                last = null
            }
        }
        return size
    }

    private fun daColor(da: String?): PdfColor {
        if (da == null) return PdfColor.BLACK
        var color = PdfColor.BLACK
        val numbers = ArrayList<Double>()
        for (token in da.trim().split(Regex("\\s+"))) {
            val d = token.toDoubleOrNull()
            if (d != null) {
                numbers += d
                continue
            }
            when (token) {
                "g" -> if (numbers.size >= 1) {
                    val g = numbers[numbers.size - 1]
                    color = PdfColor(g, g, g)
                }
                "rg" -> if (numbers.size >= 3) {
                    color = PdfColor(
                        numbers[numbers.size - 3],
                        numbers[numbers.size - 2],
                        numbers[numbers.size - 1]
                    )
                }
                "k" -> if (numbers.size >= 4) {
                    val k = numbers[numbers.size - 4]
                    val m = numbers[numbers.size - 3]
                    val y = numbers[numbers.size - 2]
                    val b = numbers[numbers.size - 1]
                    color = PdfColor((1 - k) * (1 - b), (1 - m) * (1 - b), (1 - y) * (1 - b))
                }
            }
            numbers.clear()
        }
        return color
    }

    // -- small helpers --------------------------------------------------------

    private fun escape(text: String): String =
        String(PdfSerializer.toBytes(textString(text)), StandardCharsets.ISO_8859_1)

    private fun textString(text: String): PdfString {
        val latin = text.toByteArray(StandardCharsets.ISO_8859_1)
        if (String(latin, StandardCharsets.ISO_8859_1) == text) return PdfString(latin)
        val out = ByteArrayOutputStream()
        out.write(0xFE)
        out.write(0xFF)
        out.write(text.toByteArray(StandardCharsets.UTF_16BE))
        return PdfString(out.toByteArray())
    }

    private fun num(value: Double): String = PdfSerializer.formatReal(value)

    private fun qualify(prefix: String, own: String): String = when {
        own.isEmpty() -> prefix
        prefix.isEmpty() -> own
        else -> "$prefix.$own"
    }
}
