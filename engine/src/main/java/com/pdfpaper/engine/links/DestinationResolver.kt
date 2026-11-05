package com.pdfpaper.engine.links

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.PdfPage

/**
 * Resolves a destination to a page index and a fit (ISO 32000-1 §12.3.2,
 * Table 151). Shared by the link resolver (T61) and the outline reader (T34),
 * because a bookmark and an internal link use exactly the same destination
 * grammar and must agree on which page they name.
 *
 * A destination is an explicit array, a name or string naming an entry in the
 * catalogue's `/Dests` dictionary or the `/Names` `/Dests` name tree
 * (§12.3.2.3), or a dictionary holding `/D` or `/Dest`. Malformed input
 * resolves to null rather than throwing.
 */
class DestinationResolver(
    private val document: PdfDocument,
    private val pages: List<PdfPage>
) {

    private val catalog: PdfDict? = document.resolveRoot() as? PdfDict
    private val pageByObj: Map<Int, Int> = pages.associate { it.objNum to it.index }
    private val namedDestinations: Map<String, PdfObject> by lazy { readNamedDestinations() }

    fun resolve(dest: PdfObject?): LinkDestination? =
        resolve(dest, HashSet(), 0)

    private fun resolve(dest: PdfObject?, visited: MutableSet<String>, depth: Int): LinkDestination? {
        if (dest == null || depth > 16) return null
        return when (dest) {
            is PdfRef -> {
                if (!visited.add("r${dest.objNum}")) return null
                resolve(resolveObject(dest), visited, depth + 1)
            }
            is PdfArray -> parseDestinationArray(dest)
            is PdfName -> resolveNamed(dest.name, visited, depth + 1)
            is PdfString -> resolveNamed(dest.text(), visited, depth + 1)
            is PdfDict -> {
                val inner = dest["D"] ?: dest["Dest"]
                if (inner == null) null else resolve(inner, visited, depth + 1)
            }
            else -> null
        }
    }

    private fun resolveNamed(name: String, visited: MutableSet<String>, depth: Int): LinkDestination? {
        if (!visited.add("n$name")) return null
        val raw = namedDestinations[name] ?: return null
        return resolve(raw, visited, depth + 1)
    }

    private fun parseDestinationArray(array: PdfArray): LinkDestination? {
        if (array.items.isEmpty()) return null
        val pageIndex = pageIndexFor(array.items[0]) ?: return null
        val fitName = (array.items.getOrNull(1) as? PdfName)?.name
        val fit = when (fitName) {
            "XYZ" -> LinkFit.XYZ
            "Fit" -> LinkFit.FIT
            "FitH" -> LinkFit.FIT_H
            "FitV" -> LinkFit.FIT_V
            "FitR" -> LinkFit.FIT_RECT
            "FitB" -> LinkFit.FIT_B
            "FitBH" -> LinkFit.FIT_BH
            "FitBV" -> LinkFit.FIT_BV
            else -> LinkFit.UNKNOWN
        }
        val p = { index: Int -> number(array.items.getOrNull(index)) }
        return when (fit) {
            LinkFit.XYZ -> LinkDestination(
                pageIndex, fit, left = p(2), top = p(3), zoom = p(4)
            )
            LinkFit.FIT_H, LinkFit.FIT_BH -> LinkDestination(pageIndex, fit, top = p(2))
            LinkFit.FIT_V, LinkFit.FIT_BV -> LinkDestination(pageIndex, fit, left = p(2))
            LinkFit.FIT_RECT -> {
                val left = p(2) ?: return null
                val bottom = p(3) ?: return null
                val right = p(4) ?: return null
                val top = p(5) ?: return null
                LinkDestination(
                    pageIndex, fit,
                    left = left, top = top,
                    rect = PdfRect(left, bottom, right, top)
                )
            }
            else -> LinkDestination(pageIndex, fit)
        }
    }

    private fun pageIndexFor(obj: PdfObject?): Int? = when (obj) {
        is PdfRef -> pageByObj[obj.objNum]
        is PdfInteger -> obj.value.toInt().takeIf { it in pages.indices }
        is PdfDict -> pages.indexOfFirst { it.dict === obj }.takeIf { it >= 0 }
        else -> null
    }

    private fun readNamedDestinations(): Map<String, PdfObject> {
        val out = LinkedHashMap<String, PdfObject>()
        val destDict = resolveObject(catalog?.get("Dests")) as? PdfDict
        if (destDict != null) {
            for ((key, value) in destDict.entries) {
                out[key.name] = value
            }
        }
        val names = resolveObject(catalog?.get("Names")) as? PdfDict
        val tree = names?.get("Dests")
        if (tree != null) flattenNameTree(tree, out, 0, HashSet())
        return out
    }

    private fun flattenNameTree(
        nodeObject: PdfObject,
        out: MutableMap<String, PdfObject>,
        depth: Int,
        visited: MutableSet<Int>
    ) {
        if (depth > 16) return
        val ref = nodeObject as? PdfRef
        if (ref != null && !visited.add(ref.objNum)) return
        val node = resolveObject(nodeObject) as? PdfDict ?: return
        val names = resolveObject(node["Names"]) as? PdfArray
        if (names != null) {
            var i = 0
            while (i + 1 < names.items.size) {
                val key = when (val k = names.items[i]) {
                    is PdfString -> k.text()
                    is PdfName -> k.name
                    else -> null
                }
                val value = names.items[i + 1]
                if (key != null && key.isNotBlank()) out.putIfAbsent(key, value)
                i += 2
            }
        }
        val kids = resolveObject(node["Kids"]) as? PdfArray
        if (kids != null) {
            for (kid in kids.items) flattenNameTree(kid, out, depth + 1, visited)
        }
    }

    private fun resolveObject(obj: PdfObject?): PdfObject? = when (obj) {
        null -> null
        is PdfRef -> document.resolve(obj)
        else -> obj
    }

    private fun number(obj: PdfObject?): Double? = when (obj) {
        is PdfInteger -> obj.value.toDouble()
        is PdfReal -> obj.value
        else -> null
    }
}
