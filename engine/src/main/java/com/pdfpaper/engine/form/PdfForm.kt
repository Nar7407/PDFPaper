package com.pdfpaper.engine.form

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages

private const val MAX_FIELD_DEPTH = 64

/** Interactive form field kind (§12.7.4.2 /FT and button /Ff flags). */
enum class FieldKind { TEXT, CHECKBOX, RADIO, PUSH_BUTTON, CHOICE, SIGNATURE, UNKNOWN }

/** One /Opt entry (§12.7.4.4): export value and the text a user sees. */
class FormOption(val exportValue: String, val displayValue: String)

/** One widget annotation of a field (§12.7.4.3): where it is and how it draws. */
class PdfWidget(
    val rect: PdfRect?,
    val pageIndex: Int?,
    /** On-state name of a button widget (/AP /N key other than /Off). */
    val onState: String?,
    val appearance: PdfStream?,
    val dict: PdfDict,
    val ref: PdfRef?
)

/**
 * One terminal AcroForm field (§12.7.4.2, Table 220) with inherited attributes
 * resolved down the field tree. [name] is fully qualified (`parent.child`),
 * which is also the key used when filling.
 */
class PdfField(
    val name: String,
    val kind: FieldKind,
    /** /V as text; for buttons this is the on-state name, or "Off". */
    val value: String?,
    /** /V when it is an array (multi-select choice). */
    val values: List<String>,
    val options: List<FormOption>,
    val flags: Int,
    val widgets: List<PdfWidget>,
    val dict: PdfDict,
    val ref: PdfRef?
) {

    val readOnly: Boolean get() = flags and 1 != 0
    val required: Boolean get() = flags and 2 != 0

    /** /Ff bit 13 (1-based) marks a multi-line text field. */
    val multiline: Boolean get() = kind == FieldKind.TEXT && flags and (1 shl 12) != 0

    /** /Ff bit 18 (1-based) marks an editable combo box. */
    val combo: Boolean get() = kind == FieldKind.CHOICE && flags and (1 shl 17) != 0

    /** On-state names available on this field's widgets, in widget order. */
    val onStates: List<String> get() = widgets.mapNotNull { it.onState }.distinct()

    val isChecked: Boolean get() = value != null && value != "Off"

    /** Pages carrying this field's widgets, ascending and de-duplicated. */
    val pageIndices: List<Int> get() = widgets.mapNotNull { it.pageIndex }.distinct().sorted()

    override fun toString(): String = "PdfField($name, $kind, value=$value)"
}

/** Document access plus a page-number lookup, shared by the collectors. */
private class Ctx(val doc: PdfDocument, pages: List<PdfPage>) {
    val pageIndex: Map<Int, Int> = buildMap {
        pages.forEachIndexed { index, page -> if (page.objNum >= 1) put(page.objNum, index) }
    }

    fun resolve(obj: PdfObject?): PdfObject? = when (obj) {
        null -> null
        is PdfRef -> doc.resolve(obj)
        else -> obj
    }
}

/**
 * Every interactive form field in the document (§12.7.3). Returns an empty list
 * when there is no /AcroForm. Inheritable attributes (/FT, /Ff, /V, /Opt) are
 * resolved through the field tree; widgets are collected for both merged
 * field+widget annotations and separate /Kids widgets.
 */
fun PdfDocument.formFields(): List<PdfField> {
    val ctx = Ctx(this, pages())
    val catalog = resolveRoot() as? PdfDict ?: return emptyList()
    val acro = ctx.resolve(catalog["AcroForm"]) as? PdfDict ?: return emptyList()
    val fields = (ctx.resolve(acro["Fields"]) as? PdfArray)?.items ?: return emptyList()
    val out = ArrayList<PdfField>()
    for (field in fields) collectField(ctx, field, "", Inherited(), out)
    return out
}

private class Inherited(
    val ft: String? = null,
    val ff: Int? = null,
    val value: PdfObject? = null,
    val opt: PdfObject? = null
)

private fun collectField(
    ctx: Ctx,
    node: PdfObject,
    prefix: String,
    inherited: Inherited,
    out: MutableList<PdfField>,
    seen: MutableSet<Int> = HashSet(),
    depth: Int = 0
) {
    if (depth > MAX_FIELD_DEPTH) return
    val ref = node as? PdfRef
    if (ref != null && !seen.add(ref.objNum)) return
    val dict = ctx.resolve(node) as? PdfDict ?: return
    val ownName = (dict["T"] as? PdfString)?.text().orEmpty()
    val name = qualify(prefix, ownName)
    val ft = (dict["FT"] as? PdfName)?.name ?: inherited.ft
    val ff = intOf(ctx, dict["Ff"]) ?: inherited.ff
    val value = dict["V"] ?: inherited.value
    val opt = dict["Opt"] ?: inherited.opt

    val kids = (dict["Kids"] as? PdfArray)?.items
    val fieldKids = kids?.filter { kid ->
        val kd = ctx.resolve(kid) as? PdfDict
        kd != null && (kd["Subtype"] as? PdfName)?.name != "Widget"
    }.orEmpty()
    if (fieldKids.isNotEmpty()) {
        val next = Inherited(ft, ff, value, opt)
        for (kid in kids!!) if (kid in fieldKids) collectField(ctx, kid, name, next, out, seen, depth + 1)
        return
    }

    val kind = kindOf(ft, ff)
    val options = optionsOf(ctx, opt)
    val widgets = ArrayList<PdfWidget>()
    if (dict["Rect"] != null) widgets += widgetOf(ctx, node, dict)
    kids?.forEach { kid ->
        val kd = ctx.resolve(kid) as? PdfDict ?: return@forEach
        if ((kd["Subtype"] as? PdfName)?.name == "Widget") widgets += widgetOf(ctx, kid, kd)
    }

    // A button without an /AP on-state still needs a stable name to fill by.
    val needsFallback = widgets.none { it.onState != null } &&
        (kind == FieldKind.CHECKBOX || kind == FieldKind.RADIO)
    val resolvedWidgets = if (!needsFallback) {
        widgets
    } else {
        widgets.mapIndexed { index, w ->
            PdfWidget(w.rect, w.pageIndex, onNameFor(null, index, options, kind), w.appearance, w.dict, w.ref)
        }
    }

    out += PdfField(
        name = name,
        kind = kind,
        value = textOf(ctx, value),
        values = stringsOf(ctx, value),
        options = options,
        flags = ff ?: 0,
        widgets = resolvedWidgets,
        dict = dict,
        ref = ref
    )
}

private fun widgetOf(ctx: Ctx, node: PdfObject, dict: PdfDict): PdfWidget {
    val ap = ctx.resolve(dict["AP"]) as? PdfDict
    val normal = ctx.resolve(ap?.get("N"))
    val onState = when (normal) {
        is PdfDict -> normal.entries.keys.firstOrNull { it.name != "Off" }?.name
        else -> null
    }
    val appearance = when (normal) {
        is PdfStream -> normal
        is PdfDict -> {
            val asName = (dict["AS"] as? PdfName)?.name
            (asName?.let { normal[it] } ?: normal.entries.values.firstOrNull()) as? PdfStream
        }
        else -> null
    }
    val pageRef = dict["P"] as? PdfRef
    return PdfWidget(
        rect = PdfRect.of(dict["Rect"] as? PdfArray),
        pageIndex = pageRef?.let { ctx.pageIndex[it.objNum] },
        onState = onState,
        appearance = appearance,
        dict = dict,
        ref = node as? PdfRef
    )
}

/** On-state name for a button widget: existing key, else a stable fallback. */
internal fun onNameFor(existing: String?, index: Int, options: List<FormOption>, kind: FieldKind): String {
    if (existing != null) return existing
    if (kind == FieldKind.RADIO) {
        return options.getOrNull(index)?.exportValue ?: (index + 1).toString()
    }
    return "Yes"
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

private fun optionsOf(ctx: Ctx, opt: PdfObject?): List<FormOption> {
    val items = (ctx.resolve(opt) as? PdfArray)?.items ?: return emptyList()
    return items.mapNotNull { entry ->
        when (val v = ctx.resolve(entry)) {
            is PdfString -> FormOption(v.text(), v.text())
            is PdfArray -> {
                val export = textOf(ctx, ctx.resolve(v.items.getOrNull(0))) ?: return@mapNotNull null
                val display = textOf(ctx, ctx.resolve(v.items.getOrNull(1))) ?: export
                FormOption(export, display)
            }
            else -> null
        }
    }
}

private fun textOf(ctx: Ctx, value: PdfObject?): String? = when (val v = ctx.resolve(value)) {
    is PdfString -> v.text()
    is PdfName -> v.name
    else -> null
}

private fun stringsOf(ctx: Ctx, value: PdfObject?): List<String> = when (val v = ctx.resolve(value)) {
    is PdfArray -> v.items.mapNotNull { textOf(ctx, it) }
    else -> emptyList()
}

private fun intOf(ctx: Ctx, value: PdfObject?): Int? = (ctx.resolve(value) as? PdfInteger)?.value?.toInt()

private fun qualify(prefix: String, own: String): String = when {
    own.isEmpty() -> prefix
    prefix.isEmpty() -> own
    else -> "$prefix.$own"
}
