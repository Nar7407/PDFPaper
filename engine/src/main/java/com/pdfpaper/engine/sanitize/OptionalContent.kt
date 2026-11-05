package com.pdfpaper.engine.sanitize

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString

/**
 * Optional-content groups that the default configuration keeps hidden
 * (ISO 32000-1, §8.11.4.3). [hasHidden] is false when a document has layers
 * but none of them are off, in which case the sanitizer leaves the layer
 * structure alone.
 */
internal class OptionalContentPlan(
    val hiddenRefs: Set<Int>,
    val hiddenNames: Set<String>,
    val totalGroups: Int
) {
    val hasHidden: Boolean get() = hiddenRefs.isNotEmpty() || hiddenNames.isNotEmpty()
}

internal fun inspectOptionalContent(document: PdfDocument): OptionalContentPlan {
    val catalog = document.resolveRoot() as? PdfDict ?: return OptionalContentPlan(emptySet(), emptySet(), 0)
    val properties = document.resolveDict(catalog["OCProperties"]) ?: return OptionalContentPlan(emptySet(), emptySet(), 0)
    val groups = document.resolveArray(properties["OCGs"])?.items.orEmpty()
    val allRefs = LinkedHashSet<Int>()
    val names = HashMap<Int, String>()
    for (group in groups) {
        val ref = group as? PdfRef ?: continue
        allRefs += ref.objNum
        val name = (document.resolveDict(group)?.get("Name") as? PdfString)?.text()
        if (name != null) names[ref.objNum] = name
    }

    val config = document.resolveDict(properties["D"])
        ?: document.resolveArray(properties["Configs"])?.items?.firstOrNull()?.let { document.resolveDict(it) }
    if (config == null) return OptionalContentPlan(emptySet(), emptySet(), groups.size)
    val on = refsIn(document, config["ON"])
    val off = refsIn(document, config["OFF"])
    val baseOff = (config["BaseState"] as? PdfName)?.name == "OFF"

    val hiddenRefs = LinkedHashSet<Int>()
    if (baseOff) hiddenRefs += allRefs
    hiddenRefs += off
    hiddenRefs -= on
    val hiddenNames = hiddenRefs.mapNotNull { names[it] }.toSet()
    return OptionalContentPlan(hiddenRefs, hiddenNames, groups.size)
}

private fun refsIn(document: PdfDocument, obj: PdfObject?): Set<Int> {
    val array = document.resolveArray(obj) ?: return emptySet()
    return array.items.mapNotNull { (it as? PdfRef)?.objNum }.toSet()
}

/**
 * Resolves `/OC` marked-content properties and reports whether any referenced
 * group is hidden. A property name is looked up in the resource `/Properties`
 * dictionary of the stream being rewritten.
 */
internal class OptionalContentFilter(private val plan: OptionalContentPlan) {

    fun isHidden(command: com.pdfpaper.engine.content.ContentCommand, document: PdfDocument, resources: PdfDict?): Boolean {
        if (command.operator != "BDC") return false
        val tag = (command.operands.getOrNull(0) as? PdfName)?.name ?: return false
        if (tag != "OC") return false
        val property = command.operands.getOrNull(1) ?: return false
        val value = when (property) {
            is PdfName -> {
                val table = document.resolveDict(resources?.get("Properties")) ?: return false
                table[property.name]
            }
            else -> property
        }
        return referencesHidden(document, value, 0)
    }

    private fun referencesHidden(document: PdfDocument, value: PdfObject?, depth: Int): Boolean {
        if (value == null || depth > MAX_SANITIZE_DEPTH) return false
        if (value is PdfRef && value.objNum in plan.hiddenRefs) return true
        return when (val resolved = document.resolveObject(value)) {
            is PdfArray -> resolved.items.any { referencesHidden(document, it, depth + 1) }
            is PdfDict -> {
                val name = (resolved["Name"] as? PdfString)?.text()
                if (name != null && name in plan.hiddenNames) true
                else {
                    val groups = resolved["OCGs"]
                    if (groups != null) {
                        referencesHidden(document, groups, depth + 1)
                    } else {
                        referencesHidden(document, resolved["OC"], depth + 1)
                    }
                }
            }
            else -> false
        }
    }
}
