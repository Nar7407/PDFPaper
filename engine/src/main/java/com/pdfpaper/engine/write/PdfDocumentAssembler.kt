package com.pdfpaper.engine.write

import com.pdfpaper.engine.annot.AnnotationSpec
import com.pdfpaper.engine.annot.AnnotationWriter
import com.pdfpaper.engine.edit.PageEdit
import com.pdfpaper.engine.edit.PageEditApplier
import com.pdfpaper.engine.edit.TextEditReport
import com.pdfpaper.engine.form.FormFiller
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.model.parseDirectObject
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import java.nio.charset.StandardCharsets

private const val MAX_OUTLINE_DEPTH = 64

/**
 * One page requested for the output, in output order (ISO 32000-1, §7.7.3).
 * [rotationDelta] is extra clockwise rotation added on top of the page's own
 * /Rotate (§14.11.2).
 */
data class PdfPageSelection(
    val document: PdfDocument,
    val pageIndex: Int,
    val rotationDelta: Int = 0
)

/**
 * Builds a new document from a chosen, ordered set of pages taken from one or
 * more source documents (ISO 32000-1, §7.7 page tree, §7.5 file structure).
 *
 * Each imported page is self-contained: inherited attributes (`/MediaBox`,
 * `/CropBox`, `/Rotate`, `/Resources`) are materialised on the page so it no
 * longer depends on an ancestor. Object graphs are copied with [PdfObjectCopier]
 * so shared resources stay shared and annotation/widget objects stay a single
 * copy.
 *
 * What is preserved:
 * - page content, resources and fonts, annotations (all types),
 * - link annotations and their destinations (page references and named
 *   destinations) that still point at a page in the output; a link to a page
 *   that was dropped is itself dropped,
 * - the outline (bookmarks) tree and the named-destination name tree, filtered
 *   the same way,
 * - AcroForm fields whose widgets live on a kept page (values and appearance
 *   streams included),
 * - document /Info and /ID (from the primary document).
 *
 * What is not preserved (documented, by design for T10):
 * - encrypted documents are rejected (T13),
 * - the structure tree, /PageLabels, /StructTreeRoot, embedded files and
 *   other /Names sub-trees, /PieceInfo, article threads and signatures,
 * - cross-reference streams and object streams: the output always uses a
 *   classic cross-reference table and plain objects.
 */
internal class PdfDocumentAssembler(
    private val selections: List<PdfPageSelection>,
    private val primary: PdfDocument,
    private val version: String,
    /** New annotations to add, grouped by source page index (T12). */
    private val additions: List<AnnotationSpec> = emptyList(),
    /** AcroForm field values to write, by fully-qualified name (T12). */
    private val formValues: Map<String, String> = emptyMap(),
    /** Page edits to apply while copying the primary document's pages (T17). */
    private val edits: List<PageEdit> = emptyList(),
    /**
     * Source object numbers of primary-document annotations to remove (T22).
     * Any reference to them from a kept annotation copies as "dropped", so a
     * reply never survives its comment.
     */
    private val droppedAnnots: Set<Int> = emptySet()
) {

    private val builder = PdfDocumentBuilder(version)
    private val copiers = HashMap<PdfDocument, PdfObjectCopier>()
    private val pagesByDoc = HashMap<PdfDocument, List<PdfPage>>()
    private val firstRefByDoc = HashMap<PdfDocument, HashMap<Int, PdfRef>>()
    private val targetPageRefs = LinkedHashSet<PdfRef>()
    private val rawNames = LinkedHashMap<String, PdfObject>()
    private val keptNames = HashSet<String>()
    private val additionsByPage: Map<Int, List<AnnotationSpec>> = additions.groupBy { it.pageIndex }
    private val editsByPage: Map<Int, List<PageEdit>> = edits.groupBy { it.pageIndex }
    private val annotationWriter: AnnotationWriter by lazy { AnnotationWriter(builder) }

    /** Reports from text edits applied while copying (task T16). */
    private val textReports = ArrayList<TextEditReport>()

    /** Text-edit reports gathered by the last [assemble] call. */
    fun textReports(): List<TextEditReport> = textReports

    fun assemble(): ByteArray {
        checkNotEncrypted(primary)

        // 1. Resolve source page lists, allocate target page refs, and tell each
        //    copier which source page becomes which target page (or is dropped).
        val pageJobs = ArrayList<Pair<PdfPageSelection, PdfRef>>()
        for (selection in selections) {
            val doc = selection.document
            val list = pagesByDoc.getOrPut(doc) { doc.pages() }
            require(selection.pageIndex in list.indices) {
                "Page ${selection.pageIndex} is out of range (0..${list.size - 1})"
            }
            val ref = PdfRef(builder.allocate(), 0)
            pageJobs += selection to ref
            targetPageRefs += ref
            val refsForDoc = firstRefByDoc.getOrPut(doc) { HashMap() }
            if (!refsForDoc.containsKey(selection.pageIndex)) {
                refsForDoc[selection.pageIndex] = ref
                val page = list[selection.pageIndex]
                if (page.objNum >= 1) copierFor(doc).mapKnown(page.objNum, ref)
            }
        }
        // Pages that were not selected must never be copied through a dangling
        // destination reference; map them to "dropped" explicitly.
        for ((doc, list) in pagesByDoc) {
            val refsForDoc = firstRefByDoc[doc]
            val copier = copierFor(doc)
            for ((index, page) in list.withIndex()) {
                if (page.objNum >= 1 && refsForDoc?.containsKey(index) != true) {
                    copier.mapKnown(page.objNum, null)
                }
            }
        }
        // Dropped annotations (T22) must not come back through a /Popup, /IRT
        // or /Parent reference from a kept annotation.
        if (droppedAnnots.isNotEmpty()) {
            val copier = copierFor(primary)
            for (num in droppedAnnots) copier.mapKnown(num, null)
        }

        // 2. Named destinations must be known before pages are copied, because
        //    links refer to them by name.
        resolveNames()

        // 3. Page tree node: allocated first so pages can point at it.
        val pagesRef = PdfRef(builder.allocate(), 0)

        // 4. Copy every page.
        for ((selection, ref) in pageJobs) copyPage(selection, ref, pagesRef)

        // 5. The page tree itself.
        val kids = PdfArray(pageJobs.map { it.second })
        builder.put(
            pagesRef.objNum,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Pages"),
                    PdfName("Kids") to kids,
                    PdfName("Count") to PdfInteger(kids.items.size.toLong())
                )
            )
        )

        // 6. Catalog.
        builder.root = buildCatalog(pagesRef)

        // 7. AcroForm field values (T12) are written into the already-copied
        //    field objects, then /Info and /ID are copied across.
        if (formValues.isNotEmpty()) FormFiller(builder).fill(formValues)
        copyInfo()
        copyId()
        return builder.build()
    }

    private fun copierFor(doc: PdfDocument): PdfObjectCopier =
        copiers.getOrPut(doc) { PdfObjectCopier(doc, builder) }

    private fun copyPage(selection: PdfPageSelection, ref: PdfRef, pagesRef: PdfRef) {
        val doc = selection.document
        val page = pagesByDoc.getValue(doc)[selection.pageIndex]
        val copier = copierFor(doc)
        val src = page.dict

        val pageEdits = if (doc === primary) editsByPage[selection.pageIndex].orEmpty() else emptyList()
        val edited = pageEdits.isNotEmpty()
        val skipKeys = if (edited) {
            setOf("Parent", "Annots", "Resources", "Contents")
        } else {
            setOf("Parent", "Annots")
        }
        val base = copier.copyDict(src, skipKeys = skipKeys) ?: PdfDict(emptyMap())
        val entries = LinkedHashMap(base.entries)
        entries[PdfName("Type")] = PdfName("Page")
        entries[PdfName("Parent")] = pagesRef
        materialise(entries, copier, "MediaBox", src["MediaBox"] ?: page.mediaBox)
        materialise(entries, copier, "CropBox", src["CropBox"] ?: page.cropBox)
        materialise(entries, copier, "Rotate", src["Rotate"] ?: inheritedRotate(page))
        if (!edited) {
            materialise(entries, copier, "Resources", src["Resources"] ?: page.resources)
        }

        val existingAnnots = src["Annots"]?.let { copyAnnots(copier, it, doc === primary) }
        val extras = if (doc === primary) additionsByPage[selection.pageIndex] else null
        val pageBox = PdfRect.of(page.cropBox)
        val added = extras?.flatMap {
            annotationWriter.build(it, ref, pageBox) { num -> copier.copiedRef(num) }
        }.orEmpty()
        val annots = when {
            added.isEmpty() -> existingAnnots
            existingAnnots == null -> PdfArray(added)
            else -> PdfArray(existingAnnots.items + added)
        }
        if (annots != null) entries[PdfName("Annots")] = annots else entries.remove(PdfName("Annots"))

        if (selection.rotationDelta % 360 != 0) {
            entries[PdfName("Rotate")] =
                PdfInteger(normalizeRotation(page.rotate + selection.rotationDelta).toLong())
        }
        if (edited) {
            PageEditApplier(builder, copier, page, pageEdits, textReports).apply(entries)
        }
        builder.put(ref.objNum, PdfDict(entries))
    }

    private fun materialise(
        entries: LinkedHashMap<PdfName, PdfObject>,
        copier: PdfObjectCopier,
        key: String,
        value: PdfObject?
    ) {
        if (entries.containsKey(PdfName(key))) return
        val copied = value?.let { copier.copyValue(it) } ?: return
        entries[PdfName(key)] = copied
    }

    private fun inheritedRotate(page: PdfPage): PdfObject? =
        if (page.rotate != 0) PdfInteger(page.rotate.toLong()) else null

    // -- Annotations and destinations ----------------------------------------

    private fun copyAnnots(copier: PdfObjectCopier, annotsObj: PdfObject, drops: Boolean): PdfArray? {
        val array = copier.resolve(annotsObj) as? PdfArray
            ?: return copier.copyValue(annotsObj) as? PdfArray
        val out = ArrayList<PdfObject>()
        for (item in array.items) {
            val sourceNum = (item as? PdfRef)?.objNum
            if (drops && sourceNum != null && sourceNum in droppedAnnots) continue
            // A widget's /Parent would otherwise drag the whole AcroForm tree in
            // from here; the form is imported explicitly later and reuses these
            // same objects through the copier's memo. Every other annotation
            // keeps /Parent: a popup's /Parent is its markup annotation
            // (§12.5.6.14).
            val skip = if (annotSubtype(copier, item) == "Widget") setOf("Parent") else emptySet()
            val copied: PdfObject = when (item) {
                is PdfRef -> copier.copyIndirect(item.objNum, skip)
                else -> copier.copyValue(item, skip)
            } ?: continue
            if (keepAnnot(copied)) out += copied
        }
        return if (out.isEmpty()) null else PdfArray(out)
    }

    private fun annotSubtype(copier: PdfObjectCopier, item: PdfObject): String? =
        ((copier.resolve(item) as? PdfDict)?.get("Subtype") as? PdfName)?.name

    /** A link with no reachable destination is dropped; other annots always stay. */
    private fun keepAnnot(copied: PdfObject): Boolean {
        val dict = annotDict(copied) ?: return true
        if ((dict["Subtype"] as? PdfName)?.name != "Link") return true
        return destinationValid(dict["Dest"]) || actionValid(dict["A"])
    }

    private fun annotDict(obj: PdfObject): PdfDict? = when (obj) {
        is PdfRef -> builder.get(obj.objNum) as? PdfDict
        is PdfDict -> obj
        else -> null
    }

    private fun destinationValid(obj: PdfObject?, depth: Int = 0): Boolean {
        if (depth > 8) return false
        return when (obj) {
            null -> false
            is PdfRef ->
                if (obj in targetPageRefs) true else destinationValid(builder.get(obj.objNum), depth + 1)
            is PdfArray -> {
                val first = obj.items.firstOrNull()
                first is PdfRef && first in targetPageRefs
            }
            is PdfString -> obj.text() in keptNames
            is PdfName -> obj.name in keptNames
            is PdfDict -> destinationValid(obj["D"], depth + 1)
            else -> false
        }
    }

    private fun actionValid(obj: PdfObject?, depth: Int = 0): Boolean {
        if (depth > 8) return false
        return when (obj) {
            null -> false
            is PdfRef -> actionValid(builder.get(obj.objNum), depth + 1)
            is PdfDict -> when ((obj["S"] as? PdfName)?.name) {
                null, "GoTo" -> destinationValid(obj["D"], depth + 1)
                else -> true // URI, Launch, GoToR, Named, ... stay as they are
            }
            else -> false
        }
    }

    private fun openActionValid(obj: PdfObject?): Boolean {
        if (obj == null) return false
        if (obj is PdfDict) {
            return if (obj["S"] != null) actionValid(obj) else destinationValid(obj)
        }
        return destinationValid(obj) || actionValid(obj)
    }

    // -- Named destinations ---------------------------------------------------

    private fun resolveNames() {
        val catalog = primary.resolveRoot() as? PdfDict ?: return
        val copier = copierFor(primary)
        (catalog["Dests"] as? PdfDict)?.let { dests ->
            for ((key, value) in dests.entries) rawNames[key.name] = value
        }
        catalog["Names"]?.let { namesObj ->
            val namesDict = copier.resolve(namesObj) as? PdfDict
            namesDict?.get("Dests")?.let { flattenNameTree(copier, it) }
        }
        for ((name, dest) in rawNames) {
            if (sourceDestKeepsPage(copier, dest)) keptNames += name
        }
    }

    private fun flattenNameTree(copier: PdfObjectCopier, node: PdfObject) {
        val dict = copier.resolve(node) as? PdfDict ?: return
        (dict["Kids"] as? PdfArray)?.let { kids ->
            for (kid in kids.items) flattenNameTree(copier, kid)
            return
        }
        val names = dict["Names"] as? PdfArray ?: return
        var i = 0
        while (i + 1 < names.items.size) {
            val key = names.items[i]
            if (key is PdfString) rawNames[key.text()] = names.items[i + 1]
            i += 2
        }
    }

    /** True when the source destination points at a page that made it into the output. */
    private fun sourceDestKeepsPage(copier: PdfObjectCopier, dest: PdfObject?, depth: Int = 0): Boolean {
        if (depth > 8) return false
        return when (val resolved = copier.resolve(dest ?: return false)) {
            is PdfArray -> {
                val first = resolved.items.firstOrNull()
                first is PdfRef && copier.mappedRef(first.objNum) != null
            }
            is PdfName -> resolved.name in keptNames
            is PdfDict -> sourceDestKeepsPage(copier, resolved["D"], depth + 1)
            else -> false
        }
    }

    private fun importNames(): PdfRef? {
        if (keptNames.isEmpty()) return null
        val copier = copierFor(primary)
        val namesArray = ArrayList<PdfObject>()
        for ((name, dest) in rawNames.entries.sortedBy { it.key }) {
            if (name !in keptNames) continue
            val copied = copier.copyValue(dest) ?: continue
            namesArray += PdfString(name)
            namesArray += copied
        }
        if (namesArray.isEmpty()) return null
        val leaf = builder.add(PdfDict(mapOf(PdfName("Names") to PdfArray(namesArray))))
        return builder.add(PdfDict(mapOf(PdfName("Dests") to leaf)))
    }

    // -- Outlines (bookmarks) -------------------------------------------------

    private fun importOutlines(catalog: PdfDict): PdfRef? {
        val copier = copierFor(primary)
        val root = copier.resolve(catalog["Outlines"] ?: return null) as? PdfDict ?: return null
        val first = root["First"] ?: return null
        val rootRef = PdfRef(builder.allocate(), 0)
        val level = importOutlineLevel(copier, first, rootRef)
        if (level.first == null) return null
        builder.put(
            rootRef.objNum,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Outlines"),
                    PdfName("First") to level.first,
                    PdfName("Last") to level.last!!,
                    PdfName("Count") to PdfInteger(level.count.toLong())
                )
            )
        )
        return rootRef
    }

    private data class Level(val first: PdfRef?, val last: PdfRef?, val count: Int)

    private fun importOutlineLevel(
        copier: PdfObjectCopier,
        firstObj: PdfObject,
        parentRef: PdfRef,
        seen: MutableSet<Int> = HashSet(),
        depth: Int = 0
    ): Level {
        if (depth > MAX_OUTLINE_DEPTH) return Level(null, null, 0)
        val sources = ArrayList<PdfDict>()
        var nextObj: PdfObject? = firstObj
        while (nextObj != null) {
            val objNum = (nextObj as? PdfRef)?.objNum
            if (objNum != null && !seen.add(objNum)) break
            val node = copier.resolve(nextObj) as? PdfDict ?: break
            sources += node
            nextObj = node["Next"]
        }
        // An item is kept when it has a reachable destination or keeps children.
        val keptSources = sources.filter { keepOutlineItem(copier, it) }
        val refs = keptSources.map { PdfRef(builder.allocate(), 0) }
        var descendants = 0
        for ((index, src) in keptSources.withIndex()) {
            val ref = refs[index]
            val entries = LinkedHashMap<PdfName, PdfObject>()
            copier.copyValue(src["Title"] ?: PdfString(""))?.let { entries[PdfName("Title")] = it }
            entries[PdfName("Parent")] = parentRef
            if (index > 0) entries[PdfName("Prev")] = refs[index - 1]
            if (index < refs.lastIndex) entries[PdfName("Next")] = refs[index + 1]
            for (key in listOf("C", "F")) {
                src[key]?.let { copier.copyValue(it)?.let { v -> entries[PdfName(key)] = v } }
            }
            src["Dest"]?.let { d ->
                val copied = copier.copyValue(d)
                if (copied != null && destinationValid(copied)) entries[PdfName("Dest")] = copied
            }
            src["A"]?.let { a ->
                val copied = copier.copyValue(a)
                if (copied != null && actionValid(copied)) entries[PdfName("A")] = copied
            }
            src["First"]?.let { childFirst ->
                val child = importOutlineLevel(copier, childFirst, ref, seen, depth + 1)
                if (child.first != null) {
                    entries[PdfName("First")] = child.first
                    entries[PdfName("Last")] = child.last!!
                    entries[PdfName("Count")] = PdfInteger(child.count.toLong())
                    descendants += child.count
                }
            }
            builder.put(ref.objNum, PdfDict(entries))
        }
        descendants += refs.size
        return Level(refs.firstOrNull(), refs.lastOrNull(), descendants)
    }

    private fun keepOutlineItem(copier: PdfObjectCopier, src: PdfDict): Boolean {
        val destOk = src["Dest"]?.let { sourceDestKeepsPage(copier, it) } == true
        val actionOk = src["A"]?.let { sourceActionKeepsPage(copier, it) } == true
        return destOk || actionOk || src["First"] != null
    }

    private fun sourceActionKeepsPage(copier: PdfObjectCopier, actionObj: PdfObject): Boolean {
        val action = copier.resolve(actionObj) as? PdfDict ?: return false
        return when ((action["S"] as? PdfName)?.name) {
            null, "GoTo" -> sourceDestKeepsPage(copier, action["D"])
            else -> true
        }
    }

    // -- AcroForm -------------------------------------------------------------

    private fun importAcroForm(catalog: PdfDict): PdfRef? {
        val copier = copierFor(primary)
        val acro = copier.resolve(catalog["AcroForm"] ?: return null) as? PdfDict ?: return null
        val acroRef = PdfRef(builder.allocate(), 0)
        val fields = LinkedHashSet<PdfRef>()
        (acro["Fields"] as? PdfArray)?.let { array ->
            for (field in array.items) importField(copier, field, null)?.let { fields += it }
        }
        if (fields.isEmpty()) return null
        val entries = LinkedHashMap<PdfName, PdfObject>()
        for (key in listOf("DA", "DR", "NeedAppearances", "Q", "SigFlags")) {
            acro[key]?.let { copier.copyValue(it)?.let { v -> entries[PdfName(key)] = v } }
        }
        entries[PdfName("Fields")] = PdfArray(fields.toList())
        builder.put(acroRef.objNum, PdfDict(entries))
        return acroRef
    }

    private fun importField(copier: PdfObjectCopier, fieldObj: PdfObject, parentRef: PdfRef?): PdfRef? {
        val src = copier.resolve(fieldObj) as? PdfDict ?: return null
        val srcObjNum = (fieldObj as? PdfRef)?.objNum

        // A widget may already have been copied from a page's /Annots; reuse it.
        if (srcObjNum != null) {
            copier.copiedRef(srcObjNum)?.let { existing -> attachParent(existing, parentRef); return existing }
        }
        val selfRef = if (srcObjNum != null) copier.reserveCopy(srcObjNum) else PdfRef(builder.allocate(), 0)

        val keptKids = ArrayList<PdfRef>()
        (src["Kids"] as? PdfArray)?.let { array ->
            for (kid in array.items) importField(copier, kid, selfRef)?.let { keptKids += it }
        }

        val pageRef = src["P"] as? PdfRef
        val pageDropped = pageRef != null && copier.mappedRef(pageRef.objNum) == null
        val hadKids = src["Kids"] is PdfArray
        if (keptKids.isEmpty() && (pageDropped || hadKids)) return null

        val base = copier.copyDict(src, skipKeys = setOf("Parent", "Kids")) ?: PdfDict(emptyMap())
        val entries = LinkedHashMap(base.entries)
        if (parentRef != null) entries[PdfName("Parent")] = parentRef else entries.remove(PdfName("Parent"))
        if (keptKids.isNotEmpty()) entries[PdfName("Kids")] = PdfArray(keptKids) else entries.remove(PdfName("Kids"))
        builder.put(selfRef.objNum, PdfDict(entries))
        return selfRef
    }

    /** Ensures a reused widget dictionary carries its field's /Parent. */
    private fun attachParent(ref: PdfRef, parentRef: PdfRef?) {
        if (parentRef == null) return
        val dict = builder.get(ref.objNum) as? PdfDict ?: return
        if (dict["Parent"] != null) return
        val entries = LinkedHashMap(dict.entries)
        entries[PdfName("Parent")] = parentRef
        builder.put(ref.objNum, PdfDict(entries))
    }

    // -- Catalog, info, id ----------------------------------------------------

    private fun buildCatalog(pagesRef: PdfRef): PdfRef {
        val catalog = LinkedHashMap<PdfName, PdfObject>()
        catalog[PdfName("Type")] = PdfName("Catalog")
        catalog[PdfName("Pages")] = pagesRef

        val primaryRoot = primary.resolveRoot() as? PdfDict
        if (primaryRoot != null) {
            val copier = copierFor(primary)
            for (key in listOf("ViewerPreferences", "PageLayout", "PageMode", "Lang", "Metadata", "Version")) {
                primaryRoot[key]?.let { copier.copyValue(it)?.let { v -> catalog[PdfName(key)] = v } }
            }
            primaryRoot["OpenAction"]?.let { oa ->
                val copied = copier.copyValue(oa)
                if (copied != null && openActionValid(copied)) catalog[PdfName("OpenAction")] = copied
            }
            importOutlines(primaryRoot)?.let { catalog[PdfName("Outlines")] = it }
            importNames()?.let { catalog[PdfName("Names")] = it }
            importAcroForm(primaryRoot)?.let { catalog[PdfName("AcroForm")] = it }
        }
        return builder.add(PdfDict(catalog))
    }

    private fun copyInfo() {
        val raw = primary.file.trailer.raw["Info"] ?: return
        val ref = PdfRef.parse(raw) ?: return
        builder.info = copierFor(primary).copyIndirect(ref.objNum)
    }

    private fun copyId() {
        val raw = primary.file.trailer.raw["ID"] ?: return
        val parsed = parseDirectObject(raw.toByteArray(StandardCharsets.ISO_8859_1)).getOrNull()
        if (parsed is PdfArray) builder.id = parsed
    }

    private fun checkNotEncrypted(doc: PdfDocument) {
        // Reading encrypted files (with a password) is supported (T13), but
        // writing would silently drop the protection, so it is refused until
        // re-encryption is implemented.
        if (doc.file.trailer.raw.containsKey("Encrypt")) {
            throw IllegalStateException(
                "Encrypted document: saving would remove its protection, which is not supported yet"
            )
        }
    }

    private fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
