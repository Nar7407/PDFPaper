package com.pdfpaper.engine.edit

import com.pdfpaper.engine.content.ContentCommand
import com.pdfpaper.engine.content.parseContent
import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.write.PdfDocumentBuilder
import com.pdfpaper.engine.write.PdfObjectCopier
import java.io.ByteArrayOutputStream

/**
 * Applies one page's [PageEdit]s while the assembler copies that page
 * (task T17). Adding text or images appends a new content stream and leaves the
 * original content object untouched; moving, resizing or deleting an existing
 * image rewrites the page's own content stream. New fonts and image XObjects
 * are added to a page-local /Resources dictionary.
 */
internal class PageEditApplier(
    private val builder: PdfDocumentBuilder,
    private val copier: PdfObjectCopier,
    private val page: PdfPage,
    private val edits: List<PageEdit>,
    /** Receives one report per applied text edit (T16). */
    private val textReports: MutableList<TextEditReport> = ArrayList(),
    /** Shared invisible-text font, created only when an OCR edit needs it (T25). */
    private val ocrFont: Lazy<OcrTextFont>
) {

    private val fontResources = LinkedHashMap<PdfName, PdfObject>()
    private val xobjectResources = LinkedHashMap<PdfName, PdfObject>()
    private val fontNames = HashMap<EditFont, PdfName>()
    private val addedImageNames = HashSet<String>()
    private val existingImageNames = HashSet<String>()
    private var nextResource = 1
    private var ocrName: PdfName? = null

    private val originalCommands: List<ContentCommand> by lazy {
        parseContent(page.contents().getOrNull()?.bytes ?: ByteArray(0)).commands
    }

    private val hasTextEdits: Boolean
        get() = edits.any { it is ReplaceTextEdit || it is DeleteTextEdit || it is MoveTextEdit }

    fun apply(entries: LinkedHashMap<PdfName, PdfObject>) {
        val source = page.resources
        val base = source?.let { copier.copyDict(it, skipKeys = setOf("Font", "XObject")) } ?: PdfDict(emptyMap())
        val outEntries = LinkedHashMap(base.entries)
        copyFontGroup(source)
        copyXObjectGroup(source)

        val rewritesContent = hasTextEdits || edits.any {
            it is MoveImageEdit || it is ResizeImageEdit || it is DeleteImageEdit
        }
        if (rewritesContent) {
            entries[PdfName("Contents")] = builder.addStream(PdfDict(emptyMap()), rewriteContent())
        } else {
            restoreContents(entries, buildAdditions())
        }

        if (fontResources.isNotEmpty()) outEntries[PdfName("Font")] = PdfDict(fontResources)
        if (xobjectResources.isNotEmpty()) outEntries[PdfName("XObject")] = PdfDict(xobjectResources)
        entries[PdfName("Resources")] = PdfDict(outEntries)
    }

    // -- Resources ------------------------------------------------------------

    private fun copyFontGroup(source: PdfDict?) {
        val fonts = resolveDict(source?.get("Font")) ?: return
        for ((name, value) in fonts.entries) {
            copier.copyValue(value)?.let { fontResources[name] = it }
        }
    }

    private fun copyXObjectGroup(source: PdfDict?) {
        val xobjects = resolveDict(source?.get("XObject")) ?: return
        for ((name, value) in xobjects.entries) {
            copier.copyValue(value)?.let { xobjectResources[name] = it }
            if (value is PdfStream && value.isImage()) existingImageNames += name.name
            if (value is PdfRef && (page.document.resolve(value) as? PdfStream)?.isImage() == true) {
                existingImageNames += name.name
            }
        }
    }

    private fun PdfStream.isImage(): Boolean = (dict["Subtype"] as? PdfName)?.name == "Image"

    private fun isImageName(name: String): Boolean = name in addedImageNames || name in existingImageNames

    private fun freshName(prefix: String): PdfName {
        while (true) {
            val candidate = PdfName(prefix + nextResource++)
            if (candidate !in fontResources && candidate !in xobjectResources) return candidate
        }
    }

    private fun fontNameFor(font: EditFont): PdfName = fontNames.getOrPut(font) {
        val name = freshName("PpF")
        fontResources[name] = when (font) {
            is EditFont.Standard -> standardFontDict(font.font)
            EditFont.Fallback -> EmbeddedFont.fontDict(builder)
        }
        name
    }

    private fun standardFontDict(font: StandardFont): PdfDict {
        val entries = LinkedHashMap<PdfName, PdfObject>()
        entries[PdfName("Type")] = PdfName("Font")
        entries[PdfName("Subtype")] = PdfName("Type1")
        entries[PdfName("BaseFont")] = PdfName(font.baseFont)
        if (font.winAnsi) entries[PdfName("Encoding")] = PdfName("WinAnsiEncoding")
        return PdfDict(entries)
    }

    private fun imageNameFor(image: ImageData): PdfName {
        val name = freshName("PpI")
        xobjectResources[name] = ImageXObjects.create(builder, image)
        addedImageNames += name.name
        return name
    }

    private fun registerImageUnder(name: String, image: ImageData) {
        xobjectResources[PdfName(name)] = ImageXObjects.create(builder, image)
        addedImageNames += name
    }

    // -- Content --------------------------------------------------------------

    private fun buildAdditions(): ByteArray {
        val out = ByteArrayOutputStream()
        for (edit in edits) {
            when (edit) {
                is AddTextEdit -> ContentSerializer.write(textCommands(edit), out)
                is AddOcrTextEdit -> ContentSerializer.write(ocrTextCommands(edit), out)
                is AddImageEdit -> ContentSerializer.write(imageCommands(edit), out)
                is ReplaceImageEdit -> replaceImage(edit)
                else -> {}
            }
        }
        return out.toByteArray()
    }

    private fun rewriteContent(): ByteArray {
        // Text edits are applied first; they never touch image `Do` commands, so
        // the image edits below still find their placements by name.
        val editedCommands = applyTextEdits(originalCommands)
        val model = ContentModel(editedCommands, ::isImageName)
        val deletedNames = HashSet<String>()
        for (edit in edits) {
            when (edit) {
                is AddTextEdit -> model.commands += textCommands(edit)
                is AddOcrTextEdit -> model.commands += ocrTextCommands(edit)
                is AddImageEdit -> model.commands += imageCommands(edit)
                is MoveImageEdit -> model.move(edit.imageId, edit.dx, edit.dy)
                is ResizeImageEdit -> model.resize(edit.imageId, edit.x, edit.y, edit.width, edit.height)
                is DeleteImageEdit -> {
                    model.placements().firstOrNull { it.id == edit.imageId }?.let { deletedNames += it.name }
                    model.delete(edit.imageId)
                }
                is ReplaceImageEdit -> replaceImage(edit)
                else -> {}
            }
        }
        dropUnusedImages(deletedNames, model.commands)
        return ContentSerializer.serialize(model.commands)
    }

    /** Runs the T16 scanner/editor, appending one report per text edit. */
    private fun applyTextEdits(commands: List<ContentCommand>): List<ContentCommand> {
        val textEdits = edits.filter {
            it is ReplaceTextEdit || it is DeleteTextEdit || it is MoveTextEdit
        }
        if (textEdits.isEmpty()) return commands
        val warnings = ArrayList<String>()
        val runs = TextRunScanner.scan(commands, page.resources, page.document, warnings)
        val editor = TextRunEditor(
            commands = commands,
            runs = runs,
            pageIndex = textEdits.first().pageIndex,
            fallbackName = { fontNameFor(EditFont.Fallback) },
            reports = textReports
        )
        return editor.apply(textEdits)
    }

    /**
     * Removes XObjects that a delete edit orphaned, but only when nothing draws
     * them any more — including Form XObjects, which may inherit the page's
     * resources (§8.10.1).
     */
    private fun dropUnusedImages(deletedNames: Set<String>, commands: List<ContentCommand>) {
        if (deletedNames.isEmpty()) return
        val referenced = referencedXObjectNames(commands)
        for (name in deletedNames) {
            if (name !in referenced) {
                xobjectResources.remove(PdfName(name))
                addedImageNames.remove(name)
            }
        }
    }

    private fun referencedXObjectNames(commands: List<ContentCommand>): Set<String> {
        val names = HashSet<String>()
        collectDoNames(commands, names)
        resolveDict(page.resources?.get("XObject"))?.let { walkForms(it, names, HashSet(), 0) }
        return names
    }

    private fun collectDoNames(commands: List<ContentCommand>, out: MutableSet<String>) {
        for (command in commands) {
            if (command.operator == "Do") ImagePlacements.nameOf(command.operands)?.let { out += it }
        }
    }

    private fun walkForms(xobjects: PdfDict, out: MutableSet<String>, visited: MutableSet<Int>, depth: Int) {
        if (depth > MAX_FORM_DEPTH) return
        for ((_, value) in xobjects.entries) {
            val stream = when (value) {
                is PdfStream -> value
                is PdfRef -> page.document.resolve(value) as? PdfStream
                else -> null
            } ?: continue
            if ((stream.dict["Subtype"] as? PdfName)?.name != "Form") continue
            val ref = value as? PdfRef ?: continue
            if (!visited.add(ref.objNum)) continue
            val content = stream.decoded { page.document.resolve(it) }.getOrNull()?.bytes ?: continue
            collectDoNames(parseContent(content).commands, out)
            resolveDict(stream.dict["Resources"])?.get("XObject")
                ?.let { resolveDict(it) }
                ?.let { walkForms(it, out, visited, depth + 1) }
        }
    }

    private fun textCommands(edit: AddTextEdit): List<ContentCommand> {
        val font = fontNameFor(edit.font)
        return listOf(
            ContentCommand(emptyList(), "q"),
            ContentCommand(
                listOf(PdfReal(edit.color.red), PdfReal(edit.color.green), PdfReal(edit.color.blue)),
                "rg"
            ),
            ContentCommand(emptyList(), "BT"),
            ContentCommand(listOf(font, PdfReal(edit.size)), "Tf"),
            ContentCommand(listOf(PdfReal(edit.x), PdfReal(edit.y)), "Td"),
            ContentCommand(listOf(PdfString(WinAnsi.encode(edit.text))), "Tj"),
            ContentCommand(emptyList(), "ET"),
            ContentCommand(emptyList(), "Q")
        )
    }

    /**
     * One invisible word (T25): Tr 3 (§9.3.6), the shared Identity-H font, the
     * OCR word's text matrix and horizontal scale, then one Tj.
     */
    private fun ocrTextCommands(edit: AddOcrTextEdit): List<ContentCommand> {
        val bytes = ocrFont.value.encode(edit.text)
        if (bytes.isEmpty()) return emptyList()
        val name = ocrFontName()
        val m = edit.matrix
        return listOf(
            ContentCommand(emptyList(), "q"),
            ContentCommand(listOf(PdfInteger(3)), "Tr"),
            ContentCommand(emptyList(), "BT"),
            ContentCommand(listOf(name, PdfReal(edit.size)), "Tf"),
            ContentCommand(listOf(PdfReal(edit.horizontalScale)), "Tz"),
            ContentCommand(
                listOf(
                    PdfReal(m.a), PdfReal(m.b), PdfReal(m.c),
                    PdfReal(m.d), PdfReal(m.e), PdfReal(m.f)
                ),
                "Tm"
            ),
            ContentCommand(listOf(PdfString(bytes)), "Tj"),
            ContentCommand(emptyList(), "ET"),
            ContentCommand(emptyList(), "Q")
        )
    }

    private fun ocrFontName(): PdfName {
        ocrName?.let { return it }
        val name = freshName("PpO")
        fontResources[name] = ocrFont.value.fontRef
        ocrName = name
        return name
    }

    private fun imageCommands(edit: AddImageEdit): List<ContentCommand> {
        val name = imageNameFor(edit.image)
        return listOf(
            ContentCommand(emptyList(), "q"),
            ContentCommand(
                listOf(
                    PdfReal(edit.width), PdfInteger(0), PdfInteger(0), PdfReal(edit.height),
                    PdfReal(edit.x), PdfReal(edit.y)
                ),
                "cm"
            ),
            ContentCommand(listOf(name), "Do"),
            ContentCommand(emptyList(), "Q")
        )
    }

    private fun replaceImage(edit: ReplaceImageEdit) {
        val name = ImagePlacements.scan(originalCommands, ::isImageName)
            .firstOrNull { it.id == edit.imageId }?.name
            ?: edit.imageId.takeIf { it in addedImageNames }
            ?: return
        registerImageUnder(name, edit.image)
    }

    /**
     * Re-attaches the page's original /Contents (copied untouched) and appends
     * [additions] as a new stream when there is anything to add.
     */
    private fun restoreContents(entries: LinkedHashMap<PdfName, PdfObject>, additions: ByteArray) {
        val copied = page.dict["Contents"]?.let { copier.copyValue(it) }
        if (additions.isEmpty()) {
            if (copied != null) entries[PdfName("Contents")] = copied
            return
        }
        val streamRef = builder.addStream(PdfDict(emptyMap()), additions)
        entries[PdfName("Contents")] = when (copied) {
            null -> PdfArray(listOf(streamRef))
            is PdfArray -> PdfArray(copied.items + streamRef)
            else -> PdfArray(listOf(copied, streamRef))
        }
    }

    private fun resolveDict(obj: PdfObject?): PdfDict? = when (obj) {
        is PdfDict -> obj
        is PdfRef -> page.document.resolve(obj) as? PdfDict
        else -> null
    }

    private companion object {
        const val MAX_FORM_DEPTH = 8
    }
}
