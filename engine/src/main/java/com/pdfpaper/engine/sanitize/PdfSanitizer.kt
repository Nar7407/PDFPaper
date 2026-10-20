package com.pdfpaper.engine.sanitize

import com.pdfpaper.engine.form.formFields
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.write.PdfDocumentAssembler
import com.pdfpaper.engine.write.PdfPageSelection
import com.pdfpaper.engine.write.PdfWriter

/**
 * T29 privacy sanitizer. It walks the source document, drops every selected
 * category from the copied graph, rewrites content that contains hidden text
 * or hidden optional-content layers, and writes a fresh PDF. The writer is a
 * full rewrite followed by unreachable-object garbage collection, so removed
 * strings do not survive in previous revisions or orphaned objects.
 */
object PdfSanitizer {

    fun sanitize(
        document: PdfDocument,
        options: SanitizeOptions = SanitizeOptions(),
        version: String = PdfWriter.DEFAULT_VERSION
    ): Result<SanitizeResult> = runCatching {
        val pages = document.pages()
        val annotations = document.pageAnnotations()
        val fields = document.formFields()
        val widgetsByPage = document.widgetsByPage()
        val layerPlan = inspectOptionalContent(document)
        val layerFilter = if (options.removeHiddenLayers && layerPlan.hasHidden) {
            OptionalContentFilter(layerPlan)
        } else {
            null
        }
        val context = SanitizeContentContext()

        val droppedAnnots = HashSet<Int>()
        var annotationsRemoved = 0
        var linksRemoved = 0
        var actionAnnotations = 0
        var embeddedAnnotations = 0

        for (annotation in annotations) {
            val widget = annotation.isWidget
            val link = annotation.isLink
            val actions = hasActions(annotation.dict)
            val embedded = hasEmbeddedFile(annotation.dict)

            val drop = when {
                widget && options.removesFormData -> true
                widget -> (options.removeJavaScriptAndActions && actions) ||
                    (options.removeEmbeddedFiles && embedded)
                options.removeCommentsAndAnnotations -> true
                options.removeLinks && link -> true
                options.removeJavaScriptAndActions && actions -> true
                options.removeEmbeddedFiles && embedded -> true
                else -> false
            }
            if (drop) annotation.ref?.let { droppedAnnots += it.objNum }

            if (!widget && options.removeCommentsAndAnnotations) annotationsRemoved++
            if (link && options.removeLinks) linksRemoved++
            if (actions && options.removeJavaScriptAndActions) actionAnnotations++
            if (embedded && options.removeEmbeddedFiles) embeddedAnnotations++
        }

        val formDataFields = if (options.removesFormData) fields.size else 0
        val dropAllAnnots = options.removeCommentsAndAnnotations && options.removesFormData
        val pageAppliers = pages.associate { page ->
            page.index to { source: com.pdfpaper.engine.page.PdfPage,
                            entries: LinkedHashMap<com.pdfpaper.engine.model.PdfName, com.pdfpaper.engine.model.PdfObject>,
                            copier: com.pdfpaper.engine.write.PdfObjectCopier,
                            builder: com.pdfpaper.engine.write.PdfDocumentBuilder ->
                SanitizePageApplier(
                    builder = builder,
                    copier = copier,
                    page = source,
                    options = options,
                    widgets = widgetsByPage[source.index].orEmpty(),
                    layers = layerFilter,
                    context = context,
                    dropAllAnnots = dropAllAnnots
                ).apply(entries)
            }
        }

        val outlineFilter: ((PdfDict) -> Boolean)? =
            if (options.removeJavaScriptAndActions) {
                { item -> hasActions(item) }
            } else {
                null
            }

        val assembler = PdfDocumentAssembler(
            selections = pages.map { PdfPageSelection(document, it.index) },
            primary = document,
            version = version,
            droppedAnnots = droppedAnnots,
            outlineFilter = outlineFilter,
            redactionPages = pageAppliers,
            garbageCollect = options.removeUnusedObjects,
            preserveInfo = !options.removeMetadata,
            preserveMetadata = !options.removeMetadata,
            preserveOpenAction = !options.removeJavaScriptAndActions,
            preserveCatalogActions = !options.removeJavaScriptAndActions,
            preserveOutlines = !options.removeBookmarks,
            preserveNamedDestinations = !options.removeNamedDestinations,
            preserveAcroForm = options.formData == FormDataHandling.KEEP,
            preserveOptionalContent = !options.removeHiddenLayers || !layerPlan.hasHidden,
            preserveEmbeddedFiles = !options.removeEmbeddedFiles,
            preserveJavaScriptNames = !options.removeJavaScriptAndActions,
            stripActions = options.removeJavaScriptAndActions
        )
        val bytes = assembler.assemble()

        val removed = LinkedHashMap<SanitizeItem, Int>()
        fun put(item: SanitizeItem, count: Int) {
            if (count > 0) removed[item] = count
        }
        if (options.removeMetadata) put(SanitizeItem.METADATA, countMetadata(document))
        if (options.removeJavaScriptAndActions) {
            put(SanitizeItem.JAVASCRIPT_ACTIONS, countActions(document) + actionAnnotations)
        }
        if (options.removeEmbeddedFiles) {
            put(SanitizeItem.EMBEDDED_FILES, countEmbeddedFiles(document) + embeddedAnnotations)
        }
        if (options.removeCommentsAndAnnotations) put(SanitizeItem.ANNOTATIONS, annotationsRemoved)
        if (options.removesFormData) put(SanitizeItem.FORM_DATA, formDataFields)
        if (options.removeHiddenLayers) put(SanitizeItem.HIDDEN_LAYERS, layerPlan.hiddenRefs.size)
        if (options.removeBookmarks) put(SanitizeItem.BOOKMARKS, countBookmarks(document))
        if (options.removeLinks) put(SanitizeItem.LINKS, linksRemoved)
        if (options.removeThumbnails) put(SanitizeItem.THUMBNAILS, countThumbnails(document))
        if (options.removeNamedDestinations) {
            put(SanitizeItem.NAMED_DESTINATIONS, countNamedDestinations(document))
        }
        if (options.removePreviousRevisions && document.file.trailer.prev != null) {
            put(SanitizeItem.PREVIOUS_REVISIONS, 1)
        }
        if (options.removeUnusedObjects) put(SanitizeItem.UNUSED_OBJECTS, assembler.unusedObjectsRemoved)
        if (options.removeHiddenText) put(SanitizeItem.HIDDEN_TEXT, context.hiddenTextRuns)

        val warnings = ArrayList<String>(context.warnings)
        if (!options.removePreviousRevisions && document.file.trailer.prev != null) {
            warnings += "previous revisions cannot be preserved; sanitize always writes a full rewrite"
        }
        SanitizeResult(
            bytes = bytes,
            report = SanitizeReport(options = options, removed = removed, warnings = warnings)
        )
    }
}
