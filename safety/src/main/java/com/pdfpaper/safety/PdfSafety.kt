package com.pdfpaper.safety

import com.pdfpaper.engine.annot.PdfAnnotation
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.filter.decoded
import com.pdfpaper.engine.filter.filterNamesOf
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.RandomAccessSource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfBoolean
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
import com.pdfpaper.engine.text.PageLayout
import com.pdfpaper.engine.text.layout
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Static safety inspection for a PDF (T49).
 *
 * The scanner opens the document with our own engine, reads dictionaries and
 * embedded bytes, and never follows an action. It never runs JavaScript, never
 * launches a file, never submits a form, and never opens a link or touches the
 * network.
 */
object PdfSafety {

    const val DISCLAIMER: String = SafetyReport.DISCLAIMER

    /**
     * Opens [source], scans it and closes it again. The returned [Result] is a
     * failure for a file that cannot be opened or analysed; malformed individual
     * objects are reported as findings rather than aborting the scan.
     *
     * The caller must not close [source] after a failure: the engine closes it
     * when the open fails.
     */
    fun scan(source: RandomAccessSource): Result<SafetyReport> = runCatching {
        val document = PdfDocument.open(source).getOrThrow()
        try {
            SafetyScanner(document).scan()
        } finally {
            runCatching { document.close() }
        }
    }

    /** Inspects one web address without resolving it or making a request. */
    fun inspectLink(uri: String, visibleText: String? = null): LinkSafety =
        LinkInspector.inspect(uri, visibleText)
}

private class SafetyScanner(private val document: PdfDocument) {

    private val file = document.file
    private val pages: List<PdfPage> = runCatching { document.pages() }.getOrDefault(emptyList())
    private val findings = LinkedHashMap<String, SafetyFinding>()
    private val pageOfObject = HashMap<Int, Int>()
    private val handledActionObjects = HashSet<Int>()
    private val layouts = HashMap<Int, PageLayout?>()
    private var scannedObjects = 0
    private var stoppedByLimit = false

    fun scan(): SafetyReport {
        indexPages()
        structureChecks()
        pageLinkChecks()
        objectChecks()
        val list = findings.values.sortedWith(
            compareByDescending<SafetyFinding> { it.severity.rank }
                .thenBy { it.category.ordinal }
                .thenBy { it.title }
        )
        val risk = when {
            list.any { it.severity == RiskLevel.HIGH } -> RiskLevel.HIGH
            list.any { it.severity == RiskLevel.MEDIUM } -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        return SafetyReport(
            risk = risk,
            findings = list,
            scannedObjects = scannedObjects,
            pageCount = pages.size,
            encrypted = document.isEncrypted
        )
    }

    // ------------------------------------------------------------------ index

    private fun indexPages() {
        for (page in pages) {
            pageOfObject[page.objNum] = page.index
            val annotations = runCatching { page.annotations() }.getOrDefault(emptyList())
            for (annotation in annotations) {
                annotation.ref?.let { pageOfObject[it.objNum] = page.index }
                (annotation.dict["A"] as? PdfRef)?.let { handledActionObjects += it.objNum }
                (annotation.dict["AA"] as? PdfRef)?.let { handledActionObjects += it.objNum }
            }
        }
    }

    // --------------------------------------------------------------- structure

    private fun structureChecks() {
        if (file.rebuiltFromScan) {
            add(
                finding(
                    category = SafetyCategory.STRUCTURE,
                    severity = RiskLevel.LOW,
                    title = "Cross-reference table was rebuilt",
                    explanation = "The file's cross-reference table was missing or damaged, so the " +
                        "engine found the objects by scanning the file. This is often just damage " +
                        "from a bad copy, but it also makes the file harder to check in the usual way.",
                    evidence = "rebuiltFromScan=true"
                )
            )
        }
        scanRawStructure()
        if (document.isEncrypted) {
            add(
                finding(
                    category = SafetyCategory.ENCRYPTION,
                    severity = RiskLevel.MEDIUM,
                    title = "Encrypted document",
                    explanation = "Parts of this document are encrypted. The static check can read " +
                        "the structure only after decryption and cannot inspect every action or " +
                        "attachment in the clear."
                )
            )
        }
    }

    private fun scanRawStructure() {
        val size = file.sourceSize
        if (size <= 0L) return
        val window = minOf(size, RAW_WINDOW_BYTES.toLong())
        val start = size - window
        var offset = start
        var carry = ByteArray(0)
        var eofCount = 0
        var lastEof = -1L
        while (offset < size) {
            val length = minOf(CHUNK_BYTES.toLong(), size - offset).toInt()
            val bytes = runCatching { file.readRaw(offset, length) }.getOrDefault(ByteArray(0))
            if (bytes.isEmpty()) break
            val combined = if (carry.isEmpty()) bytes else carry + bytes
            val base = offset - carry.size
            var index = 0
            while (index <= combined.size - EOF_MARKER.size) {
                if (matches(combined, index, EOF_MARKER)) {
                    eofCount++
                    lastEof = base + index
                    index += EOF_MARKER.size
                } else {
                    index++
                }
            }
            carry = if (combined.size > EOF_MARKER.size - 1) {
                combined.copyOfRange(combined.size - (EOF_MARKER.size - 1), combined.size)
            } else {
                combined
            }
            offset += bytes.size
        }

        if (eofCount >= 10) {
            add(
                finding(
                    category = SafetyCategory.STRUCTURE,
                    severity = RiskLevel.HIGH,
                    title = "Many incremental updates",
                    explanation = "The file contains many incremental updates. Each update can add " +
                        "or hide content, actions and attachments without looking like a rewrite. " +
                        "This is common for signed forms, but it also makes a file harder to review.",
                    evidence = "$eofCount %%EOF markers in the scanned tail"
                )
            )
        } else if (eofCount >= 4) {
            add(
                finding(
                    category = SafetyCategory.STRUCTURE,
                    severity = RiskLevel.MEDIUM,
                    title = "Several incremental updates",
                    explanation = "The file has been updated several times in place. Content from " +
                        "an earlier version can still be present even if the current page view " +
                        "does not show it.",
                    evidence = "$eofCount %%EOF markers in the scanned tail"
                )
            )
        }

        if (lastEof >= 0L) {
            val afterStart = lastEof + EOF_MARKER.size
            val afterLength = minOf(4096L, size - afterStart).toInt()
            if (afterLength > 0) {
                val after = runCatching { file.readRaw(afterStart, afterLength) }
                    .getOrDefault(ByteArray(0))
                if (after.any { !isPdfWhitespace(it) }) {
                    add(
                        finding(
                            category = SafetyCategory.STRUCTURE,
                            severity = RiskLevel.MEDIUM,
                            title = "Data after the last end-of-file marker",
                            explanation = "There are non-whitespace bytes after the last %%EOF " +
                                "marker. Some tools append data there; other readers ignore it, so " +
                                "two apps can see different content."
                        )
                    )
                }
            }
        } else if (size > 1024L && !file.rebuiltFromScan) {
            add(
                finding(
                    category = SafetyCategory.STRUCTURE,
                    severity = RiskLevel.LOW,
                    title = "No end-of-file marker in the scanned tail",
                    explanation = "The last %%EOF marker was not found in the final part of the " +
                        "file. Some writers omit it; a truncated file can look the same."
                )
            )
        }
    }

    // ------------------------------------------------------------- page links

    private fun pageLinkChecks() {
        for (page in pages) {
            val annotations = runCatching { page.annotations() }.getOrDefault(emptyList())
            if (annotations.isEmpty()) continue
            for (annotation in annotations) {
                val location = SafetyLocation(
                    pageIndex = page.index,
                    objectNumber = annotation.ref?.objNum,
                    detail = annotation.subtype.ifBlank { "annotation" }
                )
                val visible = visibleTextFor(page, annotation.rect)
                val action = resolveValue(annotation.dict["A"])
                if (action is PdfDict) {
                    inspectAction(action, location, visible)
                }
                val additional = resolveValue(annotation.dict["AA"])
                if (additional is PdfDict) {
                    inspectAdditionalActions(additional, location, visible)
                }
                if (annotation.hidden && hasActionKey(annotation.dict)) {
                    add(
                        finding(
                            category = SafetyCategory.OBFUSCATION,
                            severity = RiskLevel.MEDIUM,
                            title = "Hidden annotation carries an action",
                            explanation = "An annotation is marked hidden or invisible but still " +
                                "contains an action. A reader normally does not show the control; " +
                                "a script or another event may still reach it.",
                            location = location
                        )
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- objects

    private fun objectChecks() {
        val numbers = file.entries.keys.sorted()
        for (number in numbers) {
            // Leave one slot for the "stopped early" finding below; add()
            // refuses to grow the list past MAX_FINDINGS.
            if (findings.size >= MAX_FINDINGS - 1) {
                stoppedByLimit = true
                break
            }
            val entry = file.entry(number) ?: continue
            if (entry.kind == com.pdfpaper.engine.xref.EntryKind.FREE) continue
            val objectValue = runCatching { document.deref(number) }.getOrNull() ?: continue
            scannedObjects++
            inspectObject(number, objectValue)
        }
        if (stoppedByLimit) {
            add(
                finding(
                    category = SafetyCategory.UNUSUAL,
                    severity = RiskLevel.MEDIUM,
                    title = "Safety check stopped early",
                    explanation = "The document has more findings or objects than this check " +
                        "examines in one pass. The findings shown are real, but the file was not " +
                        "fully inspected.",
                    evidence = "stopped after $scannedObjects objects"
                )
            )
        }
    }

    private fun inspectObject(number: Int, value: PdfObject) {
        val page = pageOfObject[number]
        val location = SafetyLocation(pageIndex = page, objectNumber = number)
        when (value) {
            is PdfStream -> {
                inspectDictionary(number, value.dict, location)
                inspectStream(value, location)
            }
            is PdfDict -> inspectDictionary(number, value, location)
            is PdfArray -> {
                for (item in value.items) {
                    val resolved = resolveValue(item)
                    if (resolved is PdfDict) inspectDictionary(number, resolved, location)
                }
            }
            else -> Unit
        }
        inspectRawObfuscation(number, value, location)
    }

    private fun inspectDictionary(
        number: Int,
        dict: PdfDict,
        location: SafetyLocation,
        directDepth: Int = 0
    ) {
        val type = nameOf(dict["Type"])
        val subtype = nameOf(dict["Subtype"])
        val skipActions = number in handledActionObjects

        if (dict["OpenAction"] != null) {
            add(
                finding(
                    category = SafetyCategory.ACTION,
                    severity = RiskLevel.MEDIUM,
                    title = "Action when the document opens",
                    explanation = "The document contains an /OpenAction. A reader may do something " +
                        "automatically as soon as the file opens, before the user asks for it.",
                    location = location
                )
            )
            val value = resolveValue(dict["OpenAction"])
            if (value is PdfDict) inspectAction(value, location, null)
        }

        if (!skipActions) {
            val js = resolveValue(dict["JS"] ?: dict["JavaScript"])
            if (js != null) {
                add(
                    finding(
                        category = SafetyCategory.JAVASCRIPT,
                        severity = RiskLevel.HIGH,
                        title = "Embedded JavaScript",
                        explanation = "The file contains JavaScript. PDFPaper never runs it, but " +
                            "another viewer might. JavaScript can change pages, call actions or " +
                            "try to contact other software.",
                        location = location,
                        evidence = preview(textOf(js))
                    )
                )
                inspectScriptText(textOf(js), location)
            }
            dict["AA"]?.let { raw ->
                val additional = resolveValue(raw)
                if (additional is PdfDict) inspectAdditionalActions(additional, location, null)
            }
            val action = nameOf(dict["S"])
            if (action != null) {
                inspectAction(dict, location, null)
            } else if (dict["URI"] != null) {
                inspectUri(textOf(dict["URI"]), location, null)
            }
        }

        if (dict["XFA"] != null) {
            add(
                finding(
                    category = SafetyCategory.XFA,
                    severity = RiskLevel.HIGH,
                    title = "XFA form",
                    explanation = "The document contains an XFA form. XFA is a full form/scripting " +
                        "format; this check does not execute it, and some viewers do not support it " +
                        "at all.",
                    location = location
                )
            )
        }

        if ((subtype != null && subtype in RICH_MEDIA_SUBTYPES) ||
            dict.entries.keys.any { it.name in RICH_MEDIA_KEYS }
        ) {
            add(
                finding(
                    category = SafetyCategory.RICH_MEDIA,
                    severity = RiskLevel.HIGH,
                    title = "Rich media or multimedia object",
                    explanation = "The document contains rich media (video, sound, a 3D object or " +
                        "a legacy Screen/Movie action). These objects can invoke external players " +
                        "or code and are unnecessary for reading the page.",
                    location = location,
                    evidence = subtype?.ifBlank { null }
                )
            )
        }

        if (type == "Filespec" || dict["EF"] != null) {
            inspectAttachment(dict, location)
        }

        if (
            subtype != null &&
            subtype in ANNOTATION_SUBTYPES &&
            isHiddenAnnotation(dict) &&
            hasActionKey(dict)
        ) {
            add(
                finding(
                    category = SafetyCategory.OBFUSCATION,
                    severity = RiskLevel.MEDIUM,
                    title = "Hidden annotation carries an action",
                    explanation = "An annotation is marked hidden or invisible but still contains " +
                        "an action. It is not shown, but it is still in the file.",
                    location = location,
                    evidence = subtype
                )
            )
        }

        if (directDepth < 2) {
            for (key in DIRECT_NESTED_KEYS) {
                when (val nested = dict[key]) {
                    is PdfDict -> inspectDictionary(number, nested, location, directDepth + 1)
                    is PdfArray -> nested.items.forEach { item ->
                        if (item is PdfDict) {
                            inspectDictionary(number, item, location, directDepth + 1)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun inspectStream(stream: PdfStream, location: SafetyLocation) {
        val filters = runCatching { filterNamesOf(stream.dict) }.getOrDefault(emptyList())
        if (filters.size >= 3) {
            add(
                finding(
                    category = SafetyCategory.OBFUSCATION,
                    severity = RiskLevel.MEDIUM,
                    title = "Deeply nested stream filters",
                    explanation = "A stream is wrapped in ${filters.size} filters " +
                        "(${filters.joinToString(" -> ")}). Multiple layers are legal but are also " +
                        "used to hide content from simple inspection.",
                    location = location,
                    evidence = filters.joinToString(" -> ")
                )
            )
        }
        if (filters.size >= 2 && filters.zipWithNext().any { (a, b) -> a == b }) {
            add(
                finding(
                    category = SafetyCategory.OBFUSCATION,
                    severity = RiskLevel.LOW,
                    title = "Repeated stream filter",
                    explanation = "A stream applies the same filter more than once. That is legal, " +
                        "but unusual and can hide what the stream really does.",
                    location = location,
                    evidence = filters.joinToString(" -> ")
                )
            )
        }
        if (stream.data.size > LARGE_STREAM_BYTES) {
            add(
                finding(
                    category = SafetyCategory.UNUSUAL,
                    severity = RiskLevel.LOW,
                    title = "Large stream object",
                    explanation = "A stream is ${stream.data.size} bytes before decoding. Large " +
                        "or highly compressed streams can slow a viewer down.",
                    location = location,
                    evidence = "${stream.data.size} bytes"
                )
            )
        }
        val declaredLength = (stream.dict["Length"] as? PdfInteger)?.value
        if (declaredLength != null && declaredLength > HUGE_DECLARED_BYTES) {
            add(
                finding(
                    category = SafetyCategory.UNUSUAL,
                    severity = RiskLevel.MEDIUM,
                    title = "Stream claims a very large size",
                    explanation = "A stream declares /Length $declaredLength, much larger than a " +
                        "normal page object. This can be a decompression bomb.",
                    location = location,
                    evidence = "/Length $declaredLength"
                )
            )
        }
        val decodedLength = (stream.dict["DL"] as? PdfInteger)?.value
        if (decodedLength != null && decodedLength > HUGE_DECLARED_BYTES) {
            add(
                finding(
                    category = SafetyCategory.UNUSUAL,
                    severity = RiskLevel.MEDIUM,
                    title = "Stream claims a very large decoded size",
                    explanation = "A stream declares /DL $decodedLength. A small file can expand " +
                        "to a very large amount of data.",
                    location = location,
                    evidence = "/DL $decodedLength"
                )
            )
        }
        val objectStreamCount = (stream.dict["N"] as? PdfInteger)?.value
        if (nameOf(stream.dict["Type"]) == "ObjStm" && objectStreamCount != null &&
            objectStreamCount > LARGE_OBJECT_STREAM_COUNT
        ) {
            add(
                finding(
                    category = SafetyCategory.UNUSUAL,
                    severity = RiskLevel.LOW,
                    title = "Very large object stream",
                    explanation = "An object stream contains $objectStreamCount objects. This is " +
                        "unusual and makes the file harder to inspect manually.",
                    location = location,
                    evidence = "/N $objectStreamCount"
                )
            )
        }
    }

    private fun inspectRawObfuscation(number: Int, value: PdfObject, location: SafetyLocation) {
        if (value is PdfStream) return
        val entry = file.entry(number) ?: return
        if (entry.kind != com.pdfpaper.engine.xref.EntryKind.UNCOMPRESSED) return
        val raw = runCatching {
            file.readRaw(entry.offset, minOf(RAW_PROBE_BYTES.toLong(), file.sourceSize - entry.offset).toInt())
        }.getOrDefault(ByteArray(0))
        val rawText = raw.toString(StandardCharsets.ISO_8859_1)
        val header = rawText.substringBefore("stream")
        val nameEscapes = NAME_ESCAPE.findAll(header).count()
        if (nameEscapes > 0) {
            val hasAction = value is PdfDict && hasActionKey(value)
            add(
                finding(
                    category = SafetyCategory.OBFUSCATION,
                    severity = if (hasAction || nameEscapes >= 4) RiskLevel.MEDIUM else RiskLevel.LOW,
                    title = "Escaped names in object",
                    explanation = "Object $number writes dictionary names with #xx escapes. This is " +
                        "legal, but it is also used to hide action or key names from text searches.",
                    location = location,
                    evidence = "$nameEscapes escaped name${if (nameEscapes == 1) "" else "s"}"
                )
            )
        }
        if (HEX_ACTION.containsMatchIn(header)) {
            add(
                finding(
                    category = SafetyCategory.OBFUSCATION,
                    severity = RiskLevel.MEDIUM,
                    title = "Action value encoded as a hex string",
                    explanation = "An action, JavaScript or URI value is stored as a hexadecimal " +
                        "string. The text is not visible by simply reading the PDF source.",
                    location = location
                )
            )
        }
    }

    // ------------------------------------------------------------- attachments

    private fun inspectAttachment(dict: PdfDict, location: SafetyLocation) {
        val name = textOf(dict["UF"])
            ?: textOf(dict["F"])
            ?: textOf(dict["Unix"])
            ?: textOf(dict["DOS"])
            ?: "Unnamed attachment"
        val embeddedFiles = resolveValue(dict["EF"])
        val stream = if (embeddedFiles is PdfDict) {
            resolveValue(embeddedFiles["UF"] ?: embeddedFiles["F"])
        } else {
            null
        }
        val bytes = (stream as? PdfStream)?.let { embeddedBytes(it) }
        val verdict = AttachmentInspector.inspect(name, bytes)
        add(
            finding(
                category = SafetyCategory.EMBEDDED_FILE,
                severity = verdict.severity,
                title = "Embedded file: ${preview(name, 80)}",
                explanation = verdict.explanation +
                    if (verdict.warnings.isEmpty()) "" else " " + verdict.warnings.joinToString(" "),
                location = location,
                evidence = verdict.label
            )
        )
    }

    private fun embeddedBytes(stream: PdfStream): ByteArray? {
        val declared = (stream.dict["DL"] as? PdfInteger)?.value
        if (declared != null && (declared < 0 || declared > MAX_EMBEDDED_BYTES)) return null
        if (stream.data.size > MAX_COMPRESSED_EMBEDDED_BYTES) return null
        val filters = runCatching { filterNamesOf(stream.dict) }.getOrDefault(emptyList())
        if (filters.isEmpty()) return stream.data
        val result = runCatching {
            if (filters.size == 1 && filters[0] in setOf("FlateDecode", "Fl")) {
                inflateBounded(stream.data, MAX_EMBEDDED_BYTES)
            } else {
                stream.decoded { ref -> document.resolve(ref) }.getOrThrow().bytes
            }
        }.getOrNull() ?: return null
        return if (result.size <= MAX_EMBEDDED_BYTES) result else null
    }

    private fun inflateBounded(data: ByteArray, limit: Int): ByteArray? {
        if (data.isEmpty()) return ByteArray(0)
        val inflater = Inflater()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        return try {
            inflater.setInput(data)
            while (!inflater.finished()) {
                if (inflater.needsInput()) break
                val count = try {
                    inflater.inflate(buffer)
                } catch (_: DataFormatException) {
                    break
                }
                if (count == 0) {
                    if (inflater.finished() || inflater.needsInput()) break
                    break
                }
                if (out.size() + count > limit) return null
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    // ----------------------------------------------------------------- actions

    private fun inspectAction(dict: PdfDict, location: SafetyLocation, visibleText: String?) {
        val actionType = nameOf(dict["S"])
        when (actionType) {
            "JavaScript" -> {
                add(
                    finding(
                        category = SafetyCategory.JAVASCRIPT,
                        severity = RiskLevel.HIGH,
                        title = "JavaScript action",
                        explanation = "The document attaches JavaScript to an action. It could run " +
                            "when the page opens, a link is clicked or a field changes. PDFPaper " +
                            "never executes it.",
                        location = location,
                        evidence = preview(textOf(dict["JS"] ?: dict["JavaScript"]))
                    )
                )
                inspectScriptText(textOf(dict["JS"] ?: dict["JavaScript"]), location)
            }
            "Launch" -> add(
                finding(
                    category = SafetyCategory.ACTION,
                    severity = RiskLevel.HIGH,
                    title = "Launch action",
                    explanation = "A Launch action asks the reader to start another program or open " +
                        "another file. PDFPaper never performs it.",
                    location = location,
                    evidence = preview(textOf(dict["F"]), 120)
                )
            )
            "SubmitForm" -> add(
                finding(
                    category = SafetyCategory.ACTION,
                    severity = RiskLevel.HIGH,
                    title = "Form submission action",
                    explanation = "A SubmitForm action can send form values to an address or a " +
                        "remote service. The check does not contact it.",
                    location = location,
                    evidence = preview(textOf(dict["F"]), 120)
                )
            )
            "ImportData" -> add(
                finding(
                    category = SafetyCategory.ACTION,
                    severity = RiskLevel.HIGH,
                    title = "Import data action",
                    explanation = "An ImportData action reads another file into the document. It is " +
                        "not followed by PDFPaper.",
                    location = location,
                    evidence = preview(textOf(dict["F"]), 120)
                )
            )
            "GoToR" -> add(
                finding(
                    category = SafetyCategory.ACTION,
                    severity = RiskLevel.MEDIUM,
                    title = "Remote go-to action",
                    explanation = "A GoToR action refers to a destination in another file. It can " +
                        "make a reader open a different document.",
                    location = location,
                    evidence = preview(textOf(dict["F"]), 120)
                )
            )
            "GoToE" -> add(
                finding(
                    category = SafetyCategory.ACTION,
                    severity = RiskLevel.MEDIUM,
                    title = "Embedded go-to action",
                    explanation = "A GoToE action refers to a destination inside an embedded file. " +
                        "It can make a reader open attached content.",
                    location = location,
                    evidence = preview(textOf(dict["T"]), 120)
                )
            )
            "URI" -> inspectUri(textOf(dict["URI"]), location, visibleText)
            "Sound", "Movie", "Rendition", "RichMediaExecute", "GoTo3DView" -> add(
                finding(
                    category = SafetyCategory.RICH_MEDIA,
                    severity = RiskLevel.HIGH,
                    title = "Multimedia action",
                    explanation = "The document contains a $actionType action. It can invoke media " +
                        "or an external player and is not needed to read the page.",
                    location = location
                )
            )
            null -> {
                if (dict["URI"] != null) inspectUri(textOf(dict["URI"]), location, visibleText)
            }
            else -> {
                // Other actions (GoTo, Named, Hide, ...) do not run code by
                // themselves. They are reported only if they contain a JS key.
                if (dict["JS"] != null || dict["JavaScript"] != null) {
                    add(
                        finding(
                            category = SafetyCategory.JAVASCRIPT,
                            severity = RiskLevel.HIGH,
                            title = "JavaScript in an action",
                            explanation = "The action also carries JavaScript.",
                            location = location,
                            evidence = preview(textOf(dict["JS"] ?: dict["JavaScript"]))
                        )
                    )
                }
            }
        }
    }

    private fun inspectUri(uri: String?, location: SafetyLocation, visibleText: String?) {
        if (uri.isNullOrBlank()) return
        val link = LinkInspector.inspect(uri, visibleText)
        add(
            finding(
                category = SafetyCategory.LINK,
                severity = link.risk,
                title = if (link.hasWarnings) "Suspicious web link" else "Web link",
                explanation = if (link.hasWarnings) {
                    link.warnings.joinToString(" ")
                } else {
                    "The document contains a web link. PDFPaper did not open it. Check the real " +
                        "domain before continuing."
                },
                location = location,
                evidence = link.uri,
                link = link
            )
        )
    }

    private fun inspectAdditionalActions(
        dict: PdfDict,
        location: SafetyLocation,
        visibleText: String?
    ) {
        val events = dict.entries.keys.map { it.name }.filter { it.isNotBlank() }.sorted()
        add(
            finding(
                category = SafetyCategory.ACTION,
                severity = RiskLevel.MEDIUM,
                title = "Additional actions",
                explanation = "The file attaches actions to events (" +
                    (events.joinToString(", ").ifBlank { "an event" }) + "). A viewer may run " +
                    "them when a page opens, closes or a field changes.",
                location = location,
                evidence = events.joinToString(", ")
            )
        )
        for (entry in dict.entries.values) {
            val value = resolveValue(entry)
            when (value) {
                is PdfDict -> inspectAction(value, location, visibleText)
                is PdfArray -> value.items.forEach { item ->
                    (resolveValue(item) as? PdfDict)?.let { inspectAction(it, location, visibleText) }
                }
                else -> Unit
            }
        }
    }

    private fun inspectScriptText(script: String?, location: SafetyLocation) {
        if (script.isNullOrBlank()) return
        val signals = SCRIPT_SIGNALS.filter { script.contains(it, ignoreCase = true) }
        val escapes = script.count { it == '\\' }
        if (signals.isNotEmpty() || escapes >= 20) {
            add(
                finding(
                    category = SafetyCategory.OBFUSCATION,
                    severity = RiskLevel.MEDIUM,
                    title = "Obfuscated JavaScript",
                    explanation = "The JavaScript contains constructs often used to hide what it " +
                        "does (${signals.joinToString(", ").ifBlank { "many escape sequences" }}).",
                    location = location,
                    evidence = signals.joinToString(", ").ifBlank { "$escapes backslashes" }
                )
            )
        }
    }

    // --------------------------------------------------------------- helpers

    private fun resolveValue(value: PdfObject?): PdfObject? = when (value) {
        null -> null
        is PdfRef -> document.resolve(value)
        else -> value
    }

    private fun nameOf(value: PdfObject?): String? = (resolveValue(value) as? PdfName)?.name

    private fun textOf(value: PdfObject?): String? = when (val resolved = resolveValue(value)) {
        is PdfString -> resolved.text()
        is PdfName -> resolved.name
        is PdfInteger -> resolved.value.toString()
        is PdfBoolean -> resolved.value.toString()
        else -> null
    }

    private fun preview(value: String?, max: Int = 180): String? {
        val text = value?.replace('\n', ' ')?.replace('\r', ' ')?.trim() ?: return null
        if (text.isBlank()) return null
        return if (text.length <= max) text else text.take(max - 1) + "…"
    }

    private fun visibleTextFor(page: PdfPage, rect: PdfRect?): String? {
        if (rect == null || rect.isEmpty) return null
        val layout = layouts.getOrPut(page.index) {
            runCatching { page.layout() }.getOrNull()
        } ?: return null
        val box = com.pdfpaper.engine.text.TextRect(rect.left, rect.bottom, rect.right, rect.top)
        val text = layout.lines
            .filter { intersects(it.bounds, box) }
            .joinToString(" ") { it.text }
            .trim()
        return preview(text)
    }

    private fun intersects(a: com.pdfpaper.engine.text.TextRect, b: com.pdfpaper.engine.text.TextRect): Boolean =
        a.x0 < b.x1 && a.x1 > b.x0 && a.y0 < b.y1 && a.y1 > b.y0

    private fun hasActionKey(dict: PdfDict): Boolean =
        ACTION_KEYS.any { dict[it] != null }

    private fun isHiddenAnnotation(dict: PdfDict): Boolean {
        val flags = (resolveValue(dict["F"]) as? PdfInteger)?.value?.toInt() ?: return false
        return flags and (1 or 2 or 32) != 0
    }

    private fun add(finding: SafetyFinding) {
        if (findings.size >= MAX_FINDINGS) {
            stoppedByLimit = true
            return
        }
        val key = buildString {
            append(finding.category)
            append('|')
            append(finding.title)
            append('|')
            append(finding.location?.pageIndex ?: -1)
            append('|')
            append(finding.location?.objectNumber ?: -1)
            append('|')
            append(finding.evidence?.take(80).orEmpty())
        }
        if (!findings.containsKey(key)) findings[key] = finding
    }

    private fun finding(
        category: SafetyCategory,
        severity: RiskLevel,
        title: String,
        explanation: String,
        location: SafetyLocation? = null,
        evidence: String? = null,
        link: LinkSafety? = null
    ): SafetyFinding = SafetyFinding(
        id = "$category|$title|${location?.pageIndex ?: -1}|${location?.objectNumber ?: -1}|${evidence.orEmpty()}",
        category = category,
        severity = severity,
        title = title,
        explanation = explanation,
        location = location,
        evidence = evidence,
        link = link
    )

    private fun matches(bytes: ByteArray, offset: Int, marker: ByteArray): Boolean {
        if (offset < 0 || offset + marker.size > bytes.size) return false
        for (i in marker.indices) {
            if (bytes[offset + i] != marker[i]) return false
        }
        return true
    }

    private fun isPdfWhitespace(byte: Byte): Boolean = when (byte.toInt() and 0xFF) {
        0, 9, 10, 12, 13, 32 -> true
        else -> false
    }

    private companion object {
        const val MAX_FINDINGS = 500
        const val RAW_WINDOW_BYTES = 16 * 1024 * 1024
        const val CHUNK_BYTES = 256 * 1024
        const val RAW_PROBE_BYTES = 16 * 1024
        const val MAX_COMPRESSED_EMBEDDED_BYTES = 4 * 1024 * 1024
        const val MAX_EMBEDDED_BYTES = 16 * 1024 * 1024
        const val LARGE_STREAM_BYTES = 8 * 1024 * 1024
        const val HUGE_DECLARED_BYTES = 64L * 1024 * 1024
        const val LARGE_OBJECT_STREAM_COUNT = 5000L

        val EOF_MARKER = "%%EOF".toByteArray(StandardCharsets.ISO_8859_1)
        val NAME_ESCAPE = Regex("""/[^\s/<>\[\](){}%]*#[0-9A-Fa-f]{2}[^\s/<>\[\](){}%]*""")
        val HEX_ACTION = Regex(
            """/(?:JS|JavaScript|URI|OpenAction|AA|Launch|SubmitForm|ImportData|GoToR|GoToE)\s*<[0-9A-Fa-f\s]+>""",
            RegexOption.IGNORE_CASE
        )

        val ACTION_KEYS = setOf(
            "OpenAction", "AA", "A", "JS", "JavaScript", "Launch", "SubmitForm",
            "ImportData", "GoToR", "GoToE", "URI"
        )
        val DIRECT_NESTED_KEYS = setOf(
            "AcroForm", "Names", "AF", "AA", "A", "OpenAction", "EF",
            "OCProperties", "RichMediaContent", "RichMediaPresentation", "XFA"
        )

        val RICH_MEDIA_KEYS = setOf(
            "RichMedia", "RichMediaContent", "RichMediaPresentation", "Movie", "Sound",
            "Rendition", "3D", "3DAnimation", "3DView"
        )
        val RICH_MEDIA_SUBTYPES = setOf(
            "RichMedia", "Movie", "Sound", "Screen", "3D", "3DAnimation"
        )
        val ANNOTATION_SUBTYPES = setOf(
            "Text", "Link", "FreeText", "Line", "Square", "Circle", "Polygon", "PolyLine",
            "Highlight", "Underline", "Squiggly", "StrikeOut", "Stamp", "Caret", "Ink",
            "FileAttachment", "Sound", "Movie", "Widget", "Screen", "PrinterMark",
            "TrapNet", "Watermark", "Redact", "RichMedia"
        )
        val SCRIPT_SIGNALS = listOf(
            "eval(", "unescape(", "fromCharCode", "atob(", "document.write",
            "app.launchURL", "submitForm", "this.exportDataObject", "app.alert"
        )
    }
}

