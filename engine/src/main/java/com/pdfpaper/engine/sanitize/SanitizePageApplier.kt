package com.pdfpaper.engine.sanitize

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.edit.ContentSerializer
import com.pdfpaper.engine.edit.Flate
import com.pdfpaper.engine.form.PdfWidget
import com.pdfpaper.engine.geom.PdfMatrix
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfObjectCopier

/**
 * Rebuilds one page's /Contents and /Resources for T29, and optionally paints
 * flattened form-widget appearances on top. Called from the assembler after the
 * page boxes and surviving annotations have been copied.
 */
internal class SanitizePageApplier(
    private val builder: PdfDocumentBuilder,
    private val copier: PdfObjectCopier,
    private val page: PdfPage,
    private val options: SanitizeOptions,
    private val widgets: List<PdfWidget>,
    private val layers: OptionalContentFilter?,
    private val context: SanitizeContentContext,
    private val dropAllAnnots: Boolean
) {

    fun apply(entries: LinkedHashMap<PdfName, PdfObject>) {
        val pageBox = PdfRect.of(page.cropBox) ?: PdfRect(0.0, 0.0, 612.0, 792.0)

        val contents = page.contents()
        val bytes = contents.getOrNull()?.bytes
        if (bytes == null && page.dict["Contents"] != null) {
            val reason = contents.exceptionOrNull()?.message ?: "content stream could not be decoded"
            context.warn("page ${page.index + 1}: $reason; content was copied unchanged")
            page.resources?.let { entries[PdfName("Resources")] = copier.copyValue(it) ?: PdfDict(emptyMap()) }
            page.dict["Contents"]?.let { raw ->
                copier.copyValue(raw)?.let { entries[PdfName("Contents")] = it }
            }
            cleanPageKeys(entries)
            if (dropAllAnnots) entries.remove(PdfName("Annots"))
            return
        }

        val parsed = bytes?.let { parseContent(it) }
        val rewriter = SanitizeContentRewriter(
            builder = builder,
            copier = copier,
            document = page.document,
            pageBox = pageBox,
            removeHiddenText = options.removeHiddenText,
            layers = layers,
            context = context
        )
        val rewritten = rewriter.rewrite(
            parsed?.commands.orEmpty(),
            page.resources,
            PdfMatrix.IDENTITY
        )
        val resources = LinkedHashMap(rewritten.resources.entries)
        val commands = ArrayList(rewritten.commands)
        if (options.formData == FormDataHandling.FLATTEN) {
            flattenWidgets(commands, resources, pageBox)
        }

        val data = ContentSerializer.serialize(commands)
        entries[PdfName("Contents")] = builder.addStream(
            PdfDict(mapOf(PdfName("Filter") to PdfName("FlateDecode"))),
            Flate.encode(data)
        )
        entries[PdfName("Resources")] = PdfDict(resources)
        cleanPageKeys(entries)
        if (dropAllAnnots) entries.remove(PdfName("Annots"))
    }

    private fun cleanPageKeys(entries: LinkedHashMap<PdfName, PdfObject>) {
        if (options.removeThumbnails) entries.remove(PdfName("Thumb"))
        if (options.removeMetadata) entries.remove(PdfName("Metadata"))
        if (options.removeEmbeddedFiles) entries.remove(PdfName("AF"))
        if (options.removeJavaScriptAndActions) {
            entries.remove(PdfName("AA"))
            entries.remove(PdfName("OpenAction"))
        }
    }

    // -- Form flattening (§12.7.4.3 / §12.5.5) --------------------------------

    private fun flattenWidgets(
        commands: MutableList<ContentCommand>,
        resources: LinkedHashMap<PdfName, PdfObject>,
        pageBox: PdfRect
    ) {
        for (widget in widgets) {
            if (!widgetIsVisible(widget)) continue
            val rect = widget.rect ?: continue
            if (rect.isEmpty || rect.width <= 0.0 || rect.height <= 0.0) continue
            val appearance = widget.appearance ?: continue
            if (!rect.intersectsBox(pageBox)) {
                // Widgets outside the visible page cannot be seen; skip the
                // paint but still remove the widget annotation.
                continue
            }
            val form = copyAppearance(appearance) ?: continue
            val bbox = bboxOf(form.dict, rect)
            if (bbox.isEmpty) continue
            val name = addXObjectResource(resources, form)
            val sx = rect.width / bbox.width
            val sy = rect.height / bbox.height
            if (!sx.isFinite() || !sy.isFinite()) continue
            val matrix = PdfMatrix.translation(rect.left, rect.bottom) *
                PdfMatrix.scaling(sx, sy) *
                PdfMatrix.translation(-bbox.left, -bbox.bottom)
            commands += ContentCommand(emptyList(), "q")
            commands += ContentCommand(
                listOf(
                    PdfReal(matrix.a), PdfReal(matrix.b), PdfReal(matrix.c),
                    PdfReal(matrix.d), PdfReal(matrix.e), PdfReal(matrix.f)
                ),
                "cm"
            )
            commands += ContentCommand(listOf(PdfName(name)), "Do")
            commands += ContentCommand(emptyList(), "Q")
        }
    }

    private fun copyAppearance(appearance: PdfStream): PdfStream? {
        val copied = copier.copyValue(appearance) as? PdfStream ?: return null
        val entries = LinkedHashMap(copied.dict.entries)
        entries[PdfName("Type")] = PdfName("XObject")
        entries[PdfName("Subtype")] = PdfName("Form")
        return PdfStream(PdfDict(entries), copied.data)
    }

    private fun bboxOf(dict: PdfDict, rect: PdfRect): PdfRect {
        val array = when (val raw = dict["BBox"]) {
            is PdfArray -> raw
            is PdfRef -> page.document.resolve(raw) as? PdfArray
            else -> null
        }
        return PdfRect.of(array) ?: PdfRect(0.0, 0.0, rect.width, rect.height)
    }

    private fun addXObjectResource(resources: LinkedHashMap<PdfName, PdfObject>, form: PdfStream): String {
        val xobjects = LinkedHashMap<PdfName, PdfObject>()
        when (val current = resources[PdfName("XObject")]) {
            is PdfDict -> xobjects.putAll(current.entries)
            is PdfRef -> (builder.get(current.objNum) as? PdfDict)?.entries?.let { xobjects.putAll(it) }
            else -> Unit
        }
        var index = 1
        var name: String
        while (true) {
            name = "PpSanitizeForm$index"
            if (!xobjects.containsKey(PdfName(name))) break
            index++
        }
        xobjects[PdfName(name)] = builder.add(form)
        resources[PdfName("XObject")] = PdfDict(xobjects)
        return name
    }

    private fun PdfRect.intersectsBox(other: PdfRect): Boolean {
        val eps = 1e-7
        return right > other.left + eps && left < other.right - eps &&
            top > other.bottom + eps && bottom < other.top - eps
    }
}
