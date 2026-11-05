package com.pdfpaper.signatures

import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.edit.ImageFormat
import com.pdfpaper.engine.edit.Standard14
import com.pdfpaper.engine.edit.StandardFont
import com.pdfpaper.engine.form.FieldKind
import com.pdfpaper.engine.form.PdfField
import com.pdfpaper.engine.form.PdfWidget
import com.pdfpaper.engine.form.formFields
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.image.PdfImages
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfNull
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfReal
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.write.PdfSerializer
import com.pdfpaper.signatures.cms.CmsSignatureAlgorithm
import com.pdfpaper.signatures.cms.CmsWriter
import com.pdfpaper.signatures.der.DerReader
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import java.util.zip.Deflater

/**
 * Builds one incremental PDF revision for a signature (ISO 32000-1 §12.8 and
 * §7.5.6). This class deliberately keeps all cryptography behind [SigningKey]
 * and [TimestampProvider].
 */
internal class SigningEngine(
    private val document: PdfDocument,
    private val original: ByteArray,
    private val key: SigningKey,
    private val options: SigningOptions
) {

    private val file = document.file
    private val pages: List<PdfPage> = document.pages()
    private val catalog: PdfDict = document.resolveRoot() as? PdfDict
        ?: error("document has no catalog")
    private val rootRef: PdfRef = file.trailer.root?.let { PdfRef(it.objNum, it.gen) }
        ?: error("document trailer has no /Root")
    private val allFields: List<PdfField> = runCatching { document.formFields() }.getOrDefault(emptyList())
    private val signatureFields: List<PdfField> = allFields.filter { it.kind == FieldKind.SIGNATURE }
    private val updates = TreeMap<Int, Pending>()
    private var nextObject = maxOf(
        1,
        file.trailer.size,
        (file.entries.keys.maxOrNull() ?: 0) + 1
    )
    private val catalogEntries = LinkedHashMap(catalog.entries)
    private var catalogChanged = false

    fun sign(): SigningResult {
        require(!document.isEncrypted) { "encrypted PDFs cannot be signed by this engine" }
        require(original.isNotEmpty()) { "input PDF is empty" }
        require(file.startXrefOffset != null) {
            "cannot sign a file that was recovered without a usable cross-reference table"
        }
        require(pages.isNotEmpty()) { "document has no pages" }

        val chain = key.certificateChain
        require(chain.isNotEmpty()) { "signing key has no certificate chain" }
        val signer = chain.first()
        val algorithm = signatureAlgorithm(signer)
        // A caller-supplied display date also becomes the signature /M time,
        // so the visible panel and the signed dictionary cannot disagree.
        val signingTime = options.date ?: options.signingTime
        val appearance = effectiveAppearance(signer)

        val selected = selectField()
        val fieldName = selected?.name ?: options.fieldName?.takeIf { it.isNotBlank() }
            ?: freshFieldName()
        if (selected == null) {
            val collision = allFields.firstOrNull { it.name == fieldName }
            require(collision == null || collision.kind == FieldKind.SIGNATURE) {
                "field '$fieldName' already exists with another field type"
            }
        }
        val widget = selected?.widgets?.firstOrNull { it.ref != null }
            ?: selected?.widgets?.firstOrNull()
        val pageIndex = widget?.pageIndex ?: options.pageIndex
        val page = pages.getOrNull(pageIndex)
        val merged = widget == null || widget.ref == null || widget.ref == selected?.ref
        val existingRect = widget?.rect

        val shouldBeVisible = options.visible
            ?: (options.rect?.let { !it.isEmpty }
                ?: existingRect?.let { !it.isEmpty }
                ?: false)
        val rect = when {
            !shouldBeVisible -> null
            options.rect != null -> {
                require(!options.rect.isEmpty) { "visible signature rectangle is empty" }
                options.rect
            }
            existingRect != null && !existingRect.isEmpty -> existingRect
            else -> error("a visible signature needs a non-empty rectangle")
        }

        val image = if (shouldBeVisible && appearance.showImage) {
            options.image ?: appearance.imageCopy
        } else {
            null
        }
        val placedImage = image?.let { addImageObject(it) }
        val appearanceRef = if (rect != null) {
            addAppearanceObject(rect, appearance, signingTime, placedImage)
        } else {
            null
        }

        val signatureNum = allocate()
        val signatureRef = PdfRef(signatureNum, 0)
        val fieldRef = selected?.ref ?: PdfRef(allocate(), 0)
        val fieldPageRef = page?.let { pageRef(it) }

        if (selected == null) {
            val entries = LinkedHashMap<PdfName, PdfObject>()
            entries[PdfName("FT")] = PdfName("Sig")
            entries[PdfName("T")] = PdfString(fieldName)
            entries[PdfName("V")] = signatureRef
            entries[PdfName("Type")] = PdfName("Annot")
            entries[PdfName("Subtype")] = PdfName("Widget")
            entries[PdfName("Rect")] = rectArray(rect)
            if (fieldPageRef != null) entries[PdfName("P")] = fieldPageRef
            entries[PdfName("F")] = PdfInteger(132)
            if (appearanceRef != null) entries[PdfName("AP")] = normalAppearance(appearanceRef)
            addObject(fieldRef, PdfDict(entries))

            val newPage = page ?: error("new signature field needs a page")
            if (fieldPageRef == null) error("new signature field needs a page")
            addFieldToPage(newPage, fieldRef)
        } else {
            val existing = selected
            val fieldEntries = LinkedHashMap(existing.dict.entries)
            fieldEntries[PdfName("V")] = signatureRef
            if (merged) {
                applyWidgetEntries(
                    fieldEntries,
                    rect,
                    fieldPageRef,
                    appearanceRef,
                    existing.dict
                )
                addObject(existing.ref!!, PdfDict(fieldEntries))
            } else {
                addObject(existing.ref!!, PdfDict(fieldEntries))
                val widgetRef = widget?.ref
                if (widgetRef != null) {
                    val widgetEntries = LinkedHashMap(widget.dict.entries)
                    applyWidgetEntries(
                        widgetEntries,
                        rect,
                        fieldPageRef,
                        appearanceRef,
                        widget.dict
                    )
                    addObject(widgetRef, PdfDict(widgetEntries))
                }
            }
        }

        addToAcroForm(fieldRef)
        if (catalogChanged) addObject(rootRef, PdfDict(catalogEntries))

        val metadata = signatureMetadata(appearance, signer, signingTime)
        var capacity = INITIAL_CONTENTS_BYTES
        var lastError: Throwable? = null
        while (capacity <= MAX_CONTENTS_BYTES) {
            try {
                return assemble(
                    signatureNum = signatureNum,
                    capacity = capacity,
                    fieldName = fieldName,
                    pageIndex = pageIndex,
                    visible = rect != null,
                    fieldRef = fieldRef,
                    metadata = metadata,
                    algorithm = algorithm,
                    signer = signer,
                    chain = chain,
                    signingTime = signingTime
                )
            } catch (error: CmsTooLarge) {
                lastError = error
                capacity *= 2
            }
        }
        throw IllegalArgumentException(
            "CMS signature does not fit in the reserved /Contents placeholder",
            lastError
        )
    }

    // --------------------------------------------------------------- fields

    private fun selectField(): PdfField? {
        val requested = options.fieldName?.takeIf { it.isNotBlank() }
        if (requested != null) {
            val field = allFields.firstOrNull { it.name == requested }
                ?: return null
            require(field.kind == FieldKind.SIGNATURE) {
                "field '$requested' is not a signature field"
            }
            require(isEmpty(field)) { "signature field '$requested' is already signed" }
            require(field.ref != null) { "signature field '$requested' has no indirect reference" }
            return field
        }
        if (!options.reuseEmptyField) return null
        return signatureFields.firstOrNull { isEmpty(it) && it.ref != null }
    }

    private fun isEmpty(field: PdfField): Boolean {
        val value = field.dict["V"] ?: return true
        return when (val resolved = resolve(value)) {
            null, is PdfNull -> true
            is PdfString -> resolved.text().isBlank()
            else -> false
        }
    }

    private fun freshFieldName(): String {
        val used = allFields.mapTo(HashSet()) { it.name }
        var index = 1
        while (used.contains("Signature$index")) index++
        return "Signature$index"
    }

    private fun addFieldToPage(page: PdfPage, fieldRef: PdfRef) {
        require(page.objNum >= 1) { "page has no indirect object number" }
        val entries = LinkedHashMap(page.dict.entries)
        val annots = (resolve(page.dict["Annots"]) as? PdfArray)?.items.orEmpty().toMutableList()
        if (annots.none { it == fieldRef }) annots += fieldRef
        entries[PdfName("Annots")] = PdfArray(annots)
        addObject(pageRef(page), PdfDict(entries))
    }

    private fun addToAcroForm(fieldRef: PdfRef) {
        val acroObject = catalog["AcroForm"]
        val acro = resolve(acroObject) as? PdfDict
        val entries = LinkedHashMap(acro?.entries.orEmpty())
        val fields = (resolve(acro?.get("Fields")) as? PdfArray)?.items.orEmpty().toMutableList()
        if (fields.none { it == fieldRef }) fields += fieldRef
        entries[PdfName("Fields")] = PdfArray(fields)
        val sigFlags = (acro?.get("SigFlags") as? PdfInteger)?.value ?: 0L
        entries[PdfName("SigFlags")] = PdfInteger(sigFlags or 3L)

        when (acroObject) {
            is PdfRef -> addObject(acroObject, PdfDict(entries))
            is PdfDict -> {
                catalogEntries[PdfName("AcroForm")] = PdfDict(entries)
                catalogChanged = true
            }
            else -> {
                val newAcro = addObject(PdfDict(entries))
                catalogEntries[PdfName("AcroForm")] = newAcro
                catalogChanged = true
            }
        }
    }

    private fun applyWidgetEntries(
        entries: MutableMap<PdfName, PdfObject>,
        rect: PdfRect?,
        page: PdfRef?,
        appearance: PdfRef?,
        old: PdfDict
    ) {
        if (old["Type"] == null) entries[PdfName("Type")] = PdfName("Annot")
        if (old["Subtype"] == null) entries[PdfName("Subtype")] = PdfName("Widget")
        entries[PdfName("Rect")] = rectArray(rect)
        if (page != null) entries[PdfName("P")] = page
        if (old["F"] == null) entries[PdfName("F")] = PdfInteger(132)
        if (appearance != null) {
            entries[PdfName("AP")] = normalAppearance(appearance)
        } else {
            entries.remove(PdfName("AP"))
        }
    }

    // ---------------------------------------------------------- appearance

    private fun effectiveAppearance(signer: X509Certificate): SignatureAppearance {
        val base = options.appearance ?: SignatureAppearance()
        return SignatureAppearance(
            name = options.name ?: base.name ?: commonName(signer),
            date = options.date ?: base.date,
            dateText = base.dateText,
            reason = options.reason ?: base.reason,
            location = options.location ?: base.location,
            image = options.image ?: base.imageCopy,
            showName = base.showName,
            showDate = base.showDate,
            showReason = base.showReason,
            showLocation = base.showLocation,
            showImage = base.showImage
        )
    }

    private fun addAppearanceObject(
        rect: PdfRect,
        appearance: SignatureAppearance,
        signingTime: Instant,
        image: PlacedImage?
    ): PdfRef {
        val content = appearanceContent(rect, appearance, signingTime, image)
        val resources = LinkedHashMap<PdfName, PdfObject>()
        val fonts = LinkedHashMap<PdfName, PdfObject>()
        fonts[PdfName("F1")] = standardFont(StandardFont.HELVETICA)
        fonts[PdfName("F2")] = standardFont(StandardFont.HELVETICA_BOLD)
        resources[PdfName("Font")] = PdfDict(fonts)
        resources[PdfName("ProcSet")] = PdfArray(
            listOf(PdfName("PDF"), PdfName("Text"), PdfName("ImageB"), PdfName("ImageC"), PdfName("ImageI"))
        )
        if (image != null) {
            resources[PdfName("XObject")] = PdfDict(linkedMapOf(PdfName("Im1") to image.ref))
        }
        val entries = LinkedHashMap<PdfName, PdfObject>()
        entries[PdfName("Type")] = PdfName("XObject")
        entries[PdfName("Subtype")] = PdfName("Form")
        entries[PdfName("FormType")] = PdfInteger(1)
        entries[PdfName("BBox")] = PdfArray(
            listOf(PdfReal(0.0), PdfReal(0.0), PdfReal(rect.width), PdfReal(rect.height))
        )
        entries[PdfName("Resources")] = PdfDict(resources)
        return addObject(PdfStream(PdfDict(entries), content))
    }

    private fun appearanceContent(
        rect: PdfRect,
        appearance: SignatureAppearance,
        signingTime: Instant,
        image: PlacedImage?
    ): ByteArray {
        val width = rect.width
        val height = rect.height
        val margin = minOf(6.0, maxOf(2.0, minOf(width, height) * 0.08))
        val titleSize = (height * 0.24).coerceIn(5.0, 16.0)
        val detailSize = (height * 0.15).coerceIn(4.0, 9.0)
        val gap = margin
        val availableHeight = maxOf(1.0, height - 2 * margin)

        var imageDrawWidth = 0.0
        var imageDrawHeight = 0.0
        if (image != null) {
            val sourceAspect = image.width.toDouble() / maxOf(1, image.height).toDouble()
            imageDrawHeight = availableHeight
            imageDrawWidth = imageDrawHeight * sourceAspect
            val maxWidth = width * 0.38
            if (imageDrawWidth > maxWidth) {
                imageDrawWidth = maxWidth
                imageDrawHeight = imageDrawWidth / maxOf(0.0001, sourceAspect)
            }
        }
        val textX = margin + (if (image != null) imageDrawWidth + gap else 0.0)
        val textWidth = maxOf(1.0, width - textX - margin)

        val lines = ArrayList<String>()
        if (appearance.showName && !appearance.name.isNullOrBlank()) {
            lines += appearance.name
        }
        val dateText = appearance.dateText ?: formatAppearanceDate(appearance.date ?: signingTime)
        if (appearance.showDate && dateText.isNotBlank()) lines += "Date: $dateText"
        if (appearance.showReason && !appearance.reason.isNullOrBlank()) {
            lines += "Reason: ${appearance.reason}"
        }
        if (appearance.showLocation && !appearance.location.isNullOrBlank()) {
            lines += "Location: ${appearance.location}"
        }

        return ByteArrayOutputStream().also { out ->
            ascii(out, "q\n")
            ascii(out, "1 1 1 rg 0 0 ${num(width)} ${num(height)} re f\n")
            ascii(out, "0.55 G 0.6 w 0.3 0.3 ${num(maxOf(0.0, width - 0.6))} ${num(maxOf(0.0, height - 0.6))} re S\n")
            ascii(out, "0 g\n")
            if (image != null) {
                val x = margin
                val y = (height - imageDrawHeight) / 2
                ascii(
                    out,
                    "q ${num(imageDrawWidth)} 0 0 ${num(imageDrawHeight)} " +
                        "${num(x)} ${num(y)} cm /Im1 Do Q\n"
                )
            }
            var lineIndex = 1
            if (lines.isNotEmpty()) {
                val font = StandardFont.HELVETICA_BOLD
                val title = fit(lines[0], font, titleSize, textWidth)
                if (title.isNotEmpty()) {
                    drawText(out, textX, height - margin - titleSize, titleSize, font, title)
                }
                var y = height - margin - titleSize - detailSize * 1.4
                while (lineIndex < lines.size && y >= margin - detailSize) {
                    val line = fit(lines[lineIndex], StandardFont.HELVETICA, detailSize, textWidth)
                    if (line.isNotEmpty()) {
                        drawText(out, textX, y, detailSize, StandardFont.HELVETICA, line)
                    }
                    y -= detailSize * 1.25
                    lineIndex++
                }
            }
            ascii(out, "Q\n")
        }.toByteArray()
    }

    private fun addImageObject(bytes: ByteArray): PlacedImage {
        val data = EditImages.decode(bytes).getOrThrow()
        return when (data.format) {
            ImageFormat.JPEG -> {
                val components = PdfImages.jpegInfo(bytes)?.getOrNull(2) ?: 3
                val colorSpace = when (components) {
                    1 -> "DeviceGray"
                    4 -> "DeviceCMYK"
                    else -> "DeviceRGB"
                }
                val entries = LinkedHashMap<PdfName, PdfObject>()
                entries[PdfName("Type")] = PdfName("XObject")
                entries[PdfName("Subtype")] = PdfName("Image")
                entries[PdfName("Width")] = PdfInteger(data.width.toLong())
                entries[PdfName("Height")] = PdfInteger(data.height.toLong())
                entries[PdfName("ColorSpace")] = PdfName(colorSpace)
                entries[PdfName("BitsPerComponent")] = PdfInteger(8)
                entries[PdfName("Filter")] = PdfName("DCTDecode")
                val ref = addObject(PdfStream(PdfDict(entries), data.encoded ?: error("JPEG bytes missing")))
                PlacedImage(ref, data.width, data.height)
            }
            ImageFormat.PNG -> {
                val argb = data.argb ?: error("PNG pixels missing")
                val rgb = ByteArray(argb.size * 3)
                val alpha = ByteArray(argb.size)
                var hasAlpha = false
                for (i in argb.indices) {
                    val pixel = argb[i]
                    val a = pixel ushr 24 and 0xFF
                    rgb[i * 3] = (pixel ushr 16 and 0xFF).toByte()
                    rgb[i * 3 + 1] = (pixel ushr 8 and 0xFF).toByte()
                    rgb[i * 3 + 2] = (pixel and 0xFF).toByte()
                    alpha[i] = a.toByte()
                    if (a != 0xFF) hasAlpha = true
                }
                val entries = LinkedHashMap<PdfName, PdfObject>()
                entries[PdfName("Type")] = PdfName("XObject")
                entries[PdfName("Subtype")] = PdfName("Image")
                entries[PdfName("Width")] = PdfInteger(data.width.toLong())
                entries[PdfName("Height")] = PdfInteger(data.height.toLong())
                entries[PdfName("ColorSpace")] = PdfName("DeviceRGB")
                entries[PdfName("BitsPerComponent")] = PdfInteger(8)
                entries[PdfName("Filter")] = PdfName("FlateDecode")
                if (hasAlpha) {
                    val maskEntries = LinkedHashMap<PdfName, PdfObject>()
                    maskEntries[PdfName("Type")] = PdfName("XObject")
                    maskEntries[PdfName("Subtype")] = PdfName("Image")
                    maskEntries[PdfName("Width")] = PdfInteger(data.width.toLong())
                    maskEntries[PdfName("Height")] = PdfInteger(data.height.toLong())
                    maskEntries[PdfName("ColorSpace")] = PdfName("DeviceGray")
                    maskEntries[PdfName("BitsPerComponent")] = PdfInteger(8)
                    maskEntries[PdfName("Filter")] = PdfName("FlateDecode")
                    entries[PdfName("SMask")] = addObject(
                        PdfStream(PdfDict(maskEntries), deflate(alpha))
                    )
                }
                val ref = addObject(PdfStream(PdfDict(entries), deflate(rgb)))
                PlacedImage(ref, data.width, data.height)
            }
        }
    }

    // ------------------------------------------------------------- signature

    private fun signatureMetadata(
        appearance: SignatureAppearance,
        signer: X509Certificate,
        signingTime: Instant
    ): SignatureMetadata {
        return SignatureMetadata(
            name = appearance.name ?: commonName(signer),
            reason = appearance.reason,
            location = appearance.location,
            contactInfo = options.contactInfo,
            time = signingTime,
            subFilter = options.subFilter
        )
    }

    private fun assemble(
        signatureNum: Int,
        capacity: Int,
        fieldName: String,
        pageIndex: Int?,
        visible: Boolean,
        fieldRef: PdfRef,
        metadata: SignatureMetadata,
        algorithm: CmsSignatureAlgorithm,
        signer: X509Certificate,
        chain: List<X509Certificate>,
        signingTime: Instant
    ): SigningResult {
        val raw = buildSignatureBody(metadata, capacity)
        updates[signatureNum] = Pending.Raw(raw)
        val layout = layout()
        val signatureObjectOffset = layout.offsets[signatureNum]
            ?: throw IllegalStateException("signature object was not laid out")
        val headerLength = objectHeaderLength(signatureNum, 0)
        val contentsStart = signatureObjectOffset + headerLength + raw.contentsStart
        val contentsEnd = signatureObjectOffset + headerLength + raw.contentsEnd
        val hexStart = signatureObjectOffset + headerLength + raw.hexStart
        val byteRange = listOf(
            0L,
            contentsStart.toLong(),
            contentsEnd.toLong(),
            layout.bytes.size.toLong() - contentsEnd.toLong()
        )
        patchByteRange(layout.bytes, signatureObjectOffset + headerLength, raw.byteRangeSlots, byteRange)

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(layout.bytes, 0, contentsStart)
        digest.update(layout.bytes, contentsEnd, layout.bytes.size - contentsEnd)
        val dataDigest = digest.digest()
        val cms = try {
            CmsWriter.create(
                dataDigest = dataDigest,
                signerCertificate = signer,
                chain = chain,
                signingTime = signingTime,
                algorithm = algorithm,
                timestampProvider = options.timestampProvider,
                signDigest = key::sign
            )
        } finally {
            dataDigest.fill(0)
        }
        if (cms.bytes.size > capacity) {
            cms.bytes.fill(0)
            throw CmsTooLarge(cms.bytes.size)
        }

        writeHex(layout.bytes, hexStart, capacity, cms.bytes)
        val result = SigningResult(
            bytes = layout.bytes,
            fieldName = fieldName,
            pageIndex = pageIndex,
            visible = visible,
            signatureObjectId = signatureNum,
            fieldObjectId = fieldRef.objNum,
            subFilter = metadata.subFilter.pdfName,
            byteRange = byteRange,
            contentsBytes = capacity,
            cmsBytes = cms.bytes.size,
            timestamped = cms.timestamped,
            signedAt = signingTime
        )
        cms.bytes.fill(0)
        return result
    }

    private fun buildSignatureBody(metadata: SignatureMetadata, capacity: Int): RawBody {
        val out = ByteArrayOutputStream(capacity * 2 + 1024)
        ascii(out, "<<")
        entryName(out, "Type", "Sig")
        entryName(out, "Filter", "Adobe.PPKLite")
        entryName(out, "SubFilter", metadata.subFilter.pdfName)
        ascii(out, " /ByteRange [")
        val slots = ArrayList<Int>(4)
        for (i in 0 until 4) {
            if (i > 0) ascii(out, " ")
            slots += out.size()
            ascii(out, "0".repeat(BYTE_RANGE_WIDTH))
        }
        ascii(out, "] /Contents ")
        val contentsStart = out.size()
        out.write('<'.code)
        val hexStart = out.size()
        ascii(out, "0".repeat(capacity * 2))
        out.write('>'.code)
        val contentsEnd = out.size()
        if (!metadata.timeText.isNullOrBlank()) entryString(out, "M", metadata.timeText)
        if (!metadata.name.isNullOrBlank()) entryString(out, "Name", metadata.name)
        if (!metadata.reason.isNullOrBlank()) entryString(out, "Reason", metadata.reason)
        if (!metadata.location.isNullOrBlank()) entryString(out, "Location", metadata.location)
        if (!metadata.contactInfo.isNullOrBlank()) entryString(out, "ContactInfo", metadata.contactInfo)
        ascii(out, " >>")
        return RawBody(out.toByteArray(), contentsStart, contentsEnd, hexStart, slots)
    }

    // --------------------------------------------------------------- layout

    private class Layout(val bytes: ByteArray, val offsets: Map<Int, Int>)

    private fun layout(): Layout {
        val out = ByteArrayOutputStream(original.size + 4096)
        out.write(original)
        // An incremental update must start on a new line. Most files end with
        // one, but accepting a final %%EOF without a newline avoids swallowing
        // the first appended object header.
        if (original.isNotEmpty() &&
            original.last() != '\n'.code.toByte() &&
            original.last() != '\r'.code.toByte()
        ) {
            out.write('\n'.code)
        }
        val offsets = HashMap<Int, Int>(updates.size)
        for ((num, pending) in updates) {
            val gen = pending.gen
            offsets[num] = out.size()
            ascii(out, "$num $gen obj\n")
            when (pending) {
                is Pending.Object -> PdfSerializer.writeObject(pending.obj, out)
                is Pending.Raw -> out.write(pending.body.bytes)
            }
            ascii(out, "\nendobj\n")
        }

        val xrefAt = out.size()
        ascii(out, "xref\n")
        val sorted = updates.keys.toList()
        var i = 0
        while (i < sorted.size) {
            val first = sorted[i]
            var last = first
            var j = i + 1
            while (j < sorted.size && sorted[j] == last + 1) {
                last = sorted[j]
                j++
            }
            val count = last - first + 1
            ascii(out, "$first $count\n")
            for (n in first..last) {
                val offset = offsets[n] ?: 0
                val gen = updates[n]?.gen ?: 0
                ascii(out, "%010d %05d n \n".format(offset, gen))
            }
            i = j
        }

        val size = maxOf(
            file.trailer.size,
            (file.entries.keys.maxOrNull() ?: 0) + 1,
            nextObject
        )
        val info = trailerRef("Info")
        val id = trailerId()
        ascii(out, "trailer\n<< /Size $size /Root ${rootRef.objNum} ${rootRef.gen} R")
        if (info != null) ascii(out, " /Info ${info.objNum} ${info.gen} R")
        if (id != null) {
            ascii(out, " /ID ")
            PdfSerializer.writeObject(id, out)
        }
        ascii(out, " /Prev ${file.startXrefOffset} >>\n")
        ascii(out, "startxref\n$xrefAt\n%%EOF\n")
        return Layout(out.toByteArray(), offsets)
    }

    private fun trailerRef(name: String): PdfRef? {
        val raw = file.trailer.raw[name] ?: return null
        return com.pdfpaper.engine.model.parseDirectObject(raw.toByteArray(StandardCharsets.ISO_8859_1))
            .getOrNull() as? PdfRef
    }

    private fun trailerId(): PdfArray? {
        val raw = file.trailer.raw["ID"] ?: return null
        return com.pdfpaper.engine.model.parseDirectObject(raw.toByteArray(StandardCharsets.ISO_8859_1))
            .getOrNull() as? PdfArray
    }

    private fun objectHeaderLength(num: Int, gen: Int): Int = "$num $gen obj\n".length

    private fun patchByteRange(bytes: ByteArray, headerOffset: Int, slots: List<Int>, ranges: List<Long>) {
        if (ranges.size != slots.size) error("wrong byte-range slot count")
        for (i in ranges.indices) {
            val text = ranges[i].toString().padStart(BYTE_RANGE_WIDTH, '0')
            require(text.length == BYTE_RANGE_WIDTH) { "byte range value is too wide" }
            val at = headerOffset + slots[i]
            for (j in text.indices) bytes[at + j] = text[j].code.toByte()
        }
    }

    private fun writeHex(bytes: ByteArray, start: Int, capacity: Int, value: ByteArray) {
        require(value.size <= capacity) { "CMS value does not fit" }
        for (i in 0 until capacity * 2) bytes[start + i] = '0'.code.toByte()
        val digits = "0123456789ABCDEF"
        for (i in value.indices) {
            val b = value[i].toInt() and 0xFF
            bytes[start + i * 2] = digits[b ushr 4].code.toByte()
            bytes[start + i * 2 + 1] = digits[b and 0xF].code.toByte()
        }
    }

    // --------------------------------------------------------------- helpers

    private fun addObject(ref: PdfRef, obj: PdfObject) {
        updates[ref.objNum] = Pending.Object(ref.gen, obj)
    }

    private fun addObject(obj: PdfObject): PdfRef {
        val num = allocate()
        val ref = PdfRef(num, 0)
        updates[num] = Pending.Object(0, obj)
        return ref
    }

    private fun allocate(): Int {
        val value = nextObject
        nextObject++
        return value
    }

    private fun resolve(obj: PdfObject?): PdfObject? = when (obj) {
        null -> null
        is PdfRef -> document.resolve(obj)
        else -> obj
    }

    private fun pageRef(page: PdfPage): PdfRef =
        PdfRef(page.objNum, file.entry(page.objNum)?.gen ?: 0)

    private fun rectArray(rect: PdfRect?): PdfArray {
        if (rect == null || rect.isEmpty) {
            return PdfArray(listOf(PdfInteger(0), PdfInteger(0), PdfInteger(0), PdfInteger(0)))
        }
        return PdfArray(
            listOf(PdfReal(rect.left), PdfReal(rect.bottom), PdfReal(rect.right), PdfReal(rect.top))
        )
    }

    private fun normalAppearance(ref: PdfRef): PdfDict =
        PdfDict(linkedMapOf(PdfName("N") to ref))

    private fun standardFont(font: StandardFont): PdfDict = PdfDict(
        linkedMapOf(
            PdfName("Type") to PdfName("Font"),
            PdfName("Subtype") to PdfName("Type1"),
            PdfName("BaseFont") to PdfName(font.baseFont),
            PdfName("Encoding") to PdfName("WinAnsiEncoding")
        )
    )

    private fun entryName(out: ByteArrayOutputStream, key: String, value: String) {
        ascii(out, " /$key ")
        out.write(PdfSerializer.toBytes(PdfName(value)))
    }

    private fun entryString(out: ByteArrayOutputStream, key: String, value: String) {
        ascii(out, " /$key ")
        out.write(PdfSerializer.toBytes(PdfString(pdfTextBytes(value))))
    }

    private fun drawText(
        out: ByteArrayOutputStream,
        x: Double,
        y: Double,
        size: Double,
        font: StandardFont,
        text: String
    ) {
        val name = if (font == StandardFont.HELVETICA_BOLD) "F2" else "F1"
        ascii(out, "BT /$name ${num(size)} Tf 1 0 0 1 ${num(x)} ${num(y)} Tm ")
        out.write(PdfSerializer.toBytes(PdfString(pdfTextBytes(text))))
        ascii(out, " Tj ET\n")
    }

    private fun fit(text: String, font: StandardFont, size: Double, maxWidth: Double): String {
        if (text.isEmpty() || maxWidth <= 0.0) return ""
        if (Standard14.width(font, text, size) <= maxWidth) return text
        val ellipsis = "..."
        var end = text.length
        while (end > 0) {
            val candidate = text.substring(0, end) + ellipsis
            if (Standard14.width(font, candidate, size) <= maxWidth) return candidate
            end--
        }
        return ellipsis
    }

    private fun pdfTextBytes(text: String): ByteArray = text.toByteArray(WIN_ANSI)

    private fun formatAppearanceDate(value: Instant): String =
        APPEARANCE_DATE.format(value.atZone(ZoneOffset.UTC))

    private fun commonName(cert: X509Certificate): String? {
        val subject = cert.subjectX500Principal.name
        return Regex("""(?:^|,)\s*CN=([^,]+)""").find(subject)?.groupValues?.get(1)
    }

    private fun signatureAlgorithm(cert: X509Certificate): CmsSignatureAlgorithm =
        when (cert.publicKey.algorithm.uppercase()) {
            "RSA" -> CmsSignatureAlgorithm.RSA_SHA256
            "EC", "ECDSA" -> CmsSignatureAlgorithm.ECDSA_SHA256
            else -> error("unsupported signer key algorithm: ${cert.publicKey.algorithm}")
        }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count <= 0) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun num(value: Double): String = PdfSerializer.formatReal(value)

    private fun ascii(out: ByteArrayOutputStream, text: String) {
        out.write(text.toByteArray(StandardCharsets.US_ASCII))
    }

    private sealed class Pending(val gen: Int) {
        class Object(gen: Int, val obj: PdfObject) : Pending(gen)
        class Raw(val body: RawBody) : Pending(0)
    }

    private class RawBody(
        val bytes: ByteArray,
        val contentsStart: Int,
        val contentsEnd: Int,
        val hexStart: Int,
        val byteRangeSlots: List<Int>
    )

    private class PlacedImage(val ref: PdfRef, val width: Int, val height: Int)

    private class SignatureMetadata(
        val name: String?,
        val reason: String?,
        val location: String?,
        val contactInfo: String?,
        val time: Instant,
        val subFilter: SigningSubFilter
    ) {
        val timeText: String = "D:" + PDF_DATE.format(time.atZone(ZoneOffset.UTC)) + "Z"
    }

    private class CmsTooLarge(size: Int) : Exception("CMS signature is $size bytes")

    companion object {
        private const val INITIAL_CONTENTS_BYTES = 32 * 1024
        private const val MAX_CONTENTS_BYTES = 1024 * 1024
        private const val BYTE_RANGE_WIDTH = 20
        private val WIN_ANSI: Charset = Charset.forName("windows-1252")
        private val APPEARANCE_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
        private val PDF_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)
    }
}
