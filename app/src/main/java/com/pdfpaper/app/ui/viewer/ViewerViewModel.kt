package com.pdfpaper.app.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.data.CustomStampStore
import com.pdfpaper.app.edit.EditableBounds
import com.pdfpaper.app.edit.EditableObject
import com.pdfpaper.app.edit.EditableStyle
import com.pdfpaper.app.edit.EditingRuntime
import com.pdfpaper.app.edit.PdfEngine
import com.pdfpaper.app.ocr.MlKitOcrProvider
import com.pdfpaper.app.render.AndroidRasterDecoder
import com.pdfpaper.app.render.PageBitmapRenderer
import com.pdfpaper.app.signatures.CertificateSummary
import com.pdfpaper.app.signatures.DigitalCertificateStore
import com.pdfpaper.app.signatures.PlatformTrustStore
import com.pdfpaper.app.signatures.SignatureDraft
import com.pdfpaper.app.signatures.previewAspect
import com.pdfpaper.app.signatures.toArtwork
import com.pdfpaper.engine.annot.AnnotationSpec
import com.pdfpaper.engine.annot.CommentStatus
import com.pdfpaper.engine.annot.InkSpec
import com.pdfpaper.engine.annot.NoteSpec
import com.pdfpaper.engine.annot.PdfComment
import com.pdfpaper.engine.annot.ReplySpec
import com.pdfpaper.engine.annot.StampSpec
import com.pdfpaper.engine.annot.StatusSpec
import com.pdfpaper.engine.annot.comments
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.form.FieldKind
import com.pdfpaper.engine.form.PdfField
import com.pdfpaper.engine.form.formFields
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.links.DocumentLink
import com.pdfpaper.engine.links.LinkIndex
import com.pdfpaper.engine.links.LinkResolver
import com.pdfpaper.engine.links.LinkTarget
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.ocr.PdfOcr
import com.pdfpaper.engine.ocr.SearchableResult
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.security.PdfPasswordException
import com.pdfpaper.engine.sign.SignatureCaption
import com.pdfpaper.engine.sign.SignaturePlacement
import com.pdfpaper.engine.sign.VisualSignatureOptions
import com.pdfpaper.engine.sign.VisualSignatures
import com.pdfpaper.engine.stamp.StampDesign
import com.pdfpaper.engine.text.PageLayout
import com.pdfpaper.engine.text.PageMatch
import com.pdfpaper.engine.text.TextRect
import com.pdfpaper.engine.text.layout
import com.pdfpaper.engine.util.LruCache
import com.pdfpaper.engine.write.PdfPageOps
import com.pdfpaper.signatures.PdfSignatures
import com.pdfpaper.signatures.PdfSigner
import com.pdfpaper.signatures.SignatureAppearance
import com.pdfpaper.signatures.SignatureReport
import com.pdfpaper.signatures.SigningOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ViewerUiState(
    val isLoading: Boolean = true,
    val displayName: String? = null,
    val pageCount: Int = 0,
    val error: String? = null,
    /** True when the document is encrypted and awaits a password (T13). */
    val requiresPassword: Boolean = false,
    /** Set after a rejected password attempt. */
    val passwordError: String? = null
)

/** Text search state for the viewer (T11). */
data class ViewerSearchState(
    val query: String = "",
    val matches: List<PageMatch> = emptyList(),
    val activeIndex: Int = -1,
    val running: Boolean = false
) {
    val activeMatch: PageMatch? get() = matches.getOrNull(activeIndex)
}

/** One page's current text selection (T11). */
data class ViewerSelection(
    val pageIndex: Int,
    val start: Int,
    val end: Int,
    val rects: List<TextRect>,
    val text: String
)

/** Annotation tool selected in the viewer's edit mode (T12). */
enum class AnnotationTool { HIGHLIGHT, INK, NOTE }

/** Object-editing tool selected in the viewer's Edit toolbar. */
enum class ObjectEditTool { NONE, SELECT, TEXT, IMAGE }

/** Objects exposed by the app-side [PdfEngine] edit contract. */
data class ObjectEditState(
    val objects: List<EditableObject> = emptyList(),
    val selectedId: String? = null,
    val tool: ObjectEditTool = ObjectEditTool.NONE,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val fake: Boolean = false,
    val engineName: String = "",
    val saving: Boolean = false
) {
    val selected: EditableObject? get() = objects.firstOrNull { it.id == selectedId }
}

/** A freehand stroke still being drawn, in page user space. */
data class InkDraft(val pageIndex: Int, val points: List<Double>)

/** Output bytes waiting for the user to choose a destination (SAF CreateDocument). */
class ViewerExport(val bytes: ByteArray, val suggestedName: String)

/**
 * The product of one signing operation (UI-3), kept until the result dialog is
 * closed. [report] is the verification of the new signature alone; it is null
 * for a visual signature and when verification could not run.
 */
data class ViewerSigningOutcome(
    val bytes: ByteArray,
    val suggestedName: String,
    val visualOnly: Boolean,
    val report: SignatureReport? = null,
    val verificationError: String? = null,
    val certificate: CertificateSummary? = null,
    val reason: String? = null,
    val location: String? = null,
    val visible: Boolean = false,
    val pageIndex: Int = 0
)

/** Progress and outcome of the current signing operation. */
data class ViewerSigningState(
    val busy: Boolean = false,
    val error: String? = null,
    val outcome: ViewerSigningOutcome? = null
)

/** Viewer edit mode: pending annotations, form fields and their new values (T12). */
data class ViewerEditState(
    val active: Boolean = false,
    val tool: AnnotationTool = AnnotationTool.HIGHLIGHT,
    val annotations: List<AnnotationSpec> = emptyList(),
    val inkDraft: InkDraft? = null,
    val fields: List<PdfField> = emptyList(),
    val fieldValues: Map<String, String> = emptyMap(),
    val loadingFields: Boolean = false,
    val saving: Boolean = false
) {
    val hasPendingChanges: Boolean get() = annotations.isNotEmpty() || fieldValues.isNotEmpty()
}

/** One cached page bitmap plus the render plan that produced it. */
private class CachedPage(val plan: PageRenderPlan, val bitmap: Bitmap)

/**
 * Viewer state for one document (T9). Rendering happens on a single-threaded
 * background dispatcher: one page is rasterised at a time, which bounds peak
 * memory and serialises access to the document's random-access source.
 * Bitmaps of off-screen pages stay in a byte-bounded LRU cache; pending
 * renders for pages that scrolled away are cancelled. Zoom is owned by the UI
 * (it changes the requested pixel width), so a zoom change simply re-requests
 * the visible pages.
 *
 * T11 adds case-insensitive search over the extracted text and word/reading
 * order selection. Text work shares the render dispatcher so the document is
 * only ever touched by one thread; layouts are cached and bounded like bitmaps.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // limitedParallelism
class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private val _pageBoxes = MutableStateFlow<List<PageBox>>(emptyList())
    val pageBoxes: StateFlow<List<PageBox>> = _pageBoxes.asStateFlow()

    /** Bitmaps currently on screen; off-screen ones live only in the cache. */
    private val _bitmaps = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    val bitmaps: StateFlow<Map<Int, Bitmap>> = _bitmaps.asStateFlow()

    /** Render plan per on-screen page: user space → bitmap pixels (T11 overlays). */
    private val _plans = MutableStateFlow<Map<Int, PageRenderPlan>>(emptyMap())
    val plans: StateFlow<Map<Int, PageRenderPlan>> = _plans.asStateFlow()

    private val _search = MutableStateFlow(ViewerSearchState())
    val search: StateFlow<ViewerSearchState> = _search.asStateFlow()

    private val _selection = MutableStateFlow<ViewerSelection?>(null)
    val selection: StateFlow<ViewerSelection?> = _selection.asStateFlow()

    /** Annotation/form edit mode (T12). */
    private val _edit = MutableStateFlow(ViewerEditState())
    val edit: StateFlow<ViewerEditState> = _edit.asStateFlow()

    /**
     * Object editing goes through the app-side [PdfEngine] contract. Debug
     * builds get the in-memory demonstration engine; release builds get an
     * honest "coming soon" implementation until :engine exposes content edits.
     */
    private val objectEngine: PdfEngine = EditingRuntime.create(getApplication())
    private val _objectEdit = MutableStateFlow(
        ObjectEditState(
            fake = objectEngine.isFake,
            engineName = objectEngine.name
        )
    )
    val objectEdit: StateFlow<ObjectEditState> = _objectEdit.asStateFlow()

    /** Bytes produced by [save], waiting for a SAF destination. */
    private val _export = MutableStateFlow<ViewerExport?>(null)
    val export: StateFlow<ViewerExport?> = _export.asStateFlow()

    /** Non-fatal problem worth showing (render failure, warning summary). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // ------------------------------------------------------- comments (UI-2)

    /** Comments read from the open PDF; drafts live in [edit]. */
    private val _comments = MutableStateFlow<List<PdfComment>>(emptyList())
    val comments: StateFlow<List<PdfComment>> = _comments.asStateFlow()

    private val _commentsLoading = MutableStateFlow(false)
    val commentsLoading: StateFlow<Boolean> = _commentsLoading.asStateFlow()

    private val _commentsError = MutableStateFlow<String?>(null)
    val commentsError: StateFlow<String?> = _commentsError.asStateFlow()

    /** Replies/status changes made in this session, keyed by comment object number. */
    private val _commentReplies = MutableStateFlow<Map<Int, List<CommentReplyUi>>>(emptyMap())
    val commentReplies: StateFlow<Map<Int, List<CommentReplyUi>>> = _commentReplies.asStateFlow()

    private val _commentStatuses = MutableStateFlow<Map<Int, CommentStatus>>(emptyMap())
    val commentStatuses: StateFlow<Map<Int, CommentStatus>> = _commentStatuses.asStateFlow()

    // ----------------------------------------------------- signatures (UI-2)

    private val _signatures = MutableStateFlow(ViewerSignaturesState())
    val signatures: StateFlow<ViewerSignaturesState> = _signatures.asStateFlow()
    private var signaturesLoaded = false

    // --------------------------------------------------------- signing (UI-3)

    private val _signing = MutableStateFlow(ViewerSigningState())
    val signing: StateFlow<ViewerSigningState> = _signing.asStateFlow()

    // ------------------------------------------------------------- OCR (UI-2)

    private val _ocr = MutableStateFlow(ViewerOcrState())
    val ocr: StateFlow<ViewerOcrState> = _ocr.asStateFlow()
    private var ocrProvider: MlKitOcrProvider? = null
    private var ocrResult: SearchableResult? = null
    private var ocrJob: Job? = null

    /** Display name preserved across a save/reload of the edited cache copy. */
    private var displayNameOverride: String? = null

    private var document: PdfDocument? = null
    private var pages: List<PdfPage> = emptyList()
    private var fieldsLoaded = false

    /** Path of the document being viewed, kept so a password retry can reopen it. */
    private var currentPath: String = ""

    private val cache = LruCache<Int, CachedPage>(bitmapBudgetBytes()) { it.bitmap.allocationByteCount.toLong() }

    /** Per-page text layouts; small and bounded, re-extracted if evicted. */
    private val layouts = LruCache<Int, PageLayout>(64)

    // ------------------------------------------------------- links (T36/T61)

    private var linkIndex: LinkIndex? = null
    private var linkJob: Job? = null

    /**
     * Cropped previews for the link card. The budget is much smaller than the
     * page cache because previews are small and only a handful are visible.
     */
    private val linkPreviews = LruCache<String, Bitmap>(16L * 1024 * 1024) {
        it.allocationByteCount.toLong()
    }

    /**
     * One dispatcher for all document access: rendering and text extraction
     * share the [PdfDocument], so they must never run at the same time.
     */
    private val documentDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val renderScope = CoroutineScope(SupervisorJob() + documentDispatcher)
    private val textScope = CoroutineScope(SupervisorJob() + documentDispatcher)
    private val jobs = HashMap<Int, Job>()
    private val requestedWidths = HashMap<Int, Int>()
    private var searchJob: Job? = null

    /** First word of the current selection, to extend from (T11). */
    private var selectionAnchor: Pair<Int, IntRange>? = null

    /** Embedded fonts are materialised here so android.graphics can load them. */
    private val fontDir: File by lazy { File(getApplication<Application>().cacheDir, "embedded-fonts") }

    fun load(path: String) {
        displayNameOverride = null
        currentPath = path
        openWith("")
    }

    /** Retries opening the current document with [password] (T13). */
    fun unlock(password: String) = openWith(password)

    /** Gives up on the password prompt. */
    fun cancelUnlock() = _state.update {
        it.copy(requiresPassword = false, passwordError = null, error = "Password required")
    }

    private fun openWith(password: String) {
        val path = currentPath
        if (path.isBlank()) {
            _state.value = ViewerUiState(isLoading = false, error = "No file selected")
            return
        }
        clearTextState()
        closeCurrentDocument()
        _state.value = ViewerUiState(isLoading = true)
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    val doc = PdfDocument.open(FileSource(File(path)), password).getOrThrow()
                    doc to doc.pages()
                }
            }
            opened.fold(
                onSuccess = { (doc, list) ->
                    document = doc
                    pages = list
                    _pageBoxes.value = list.map { PageGeometry.boxOf(it) }
                    _state.value = ViewerUiState(
                        isLoading = false,
                        displayName = displayNameOverride ?: File(path).name,
                        pageCount = list.size
                    )
                    loadComments()
                    buildLinkIndex()
                },
                onFailure = { error ->
                    if (error is PdfPasswordException) {
                        _state.value = ViewerUiState(
                            isLoading = false,
                            displayName = File(path).name,
                            requiresPassword = true,
                            passwordError = if (password.isEmpty()) null else "Incorrect password"
                        )
                    } else {
                        _state.value = ViewerUiState(
                            isLoading = false,
                            displayName = File(path).name,
                            error = error.message ?: "Cannot open this document"
                        )
                    }
                }
            )
        }
    }

    /**
     * Called when a page becomes visible. Publishes a cached bitmap when it
     * already has the right resolution, otherwise schedules a render.
     */
    fun onPageVisible(index: Int, targetPixelWidth: Int) {
        if (index !in pages.indices) return
        val width = targetPixelWidth.coerceAtLeast(1)

        cache.get(index)?.let { cached ->
            if (resolutionMatches(cached.plan.pixelWidth, width)) {
                publish(index, cached.plan, cached.bitmap)
                return
            }
        }
        if (jobs[index]?.isActive == true && requestedWidths[index] == width) return

        jobs.remove(index)?.cancel()
        requestedWidths[index] = width
        val box = _pageBoxes.value.getOrNull(index) ?: return
        val scale = PageGeometry.scaleForPixelWidth(box, width).coerceIn(0.05, 12.0)
        jobs[index] = renderScope.launch {
            val rendered = try {
                PageBitmapRenderer.render(pages[index], scale, fontDir = fontDir)
            } catch (t: Throwable) {
                _message.value = "Page ${index + 1} could not be rendered: ${t.message}"
                null
            }
            if (rendered != null && isActive) {
                if (rendered.warnings.isNotEmpty()) {
                    _message.value = "Page ${index + 1}: ${rendered.warnings.first()}"
                }
                cache.put(index, CachedPage(rendered.plan, rendered.bitmap))
                publish(index, rendered.plan, rendered.bitmap)
            }
            jobs.remove(index)
            requestedWidths.remove(index)
        }
    }

    /** Called when a page scrolls out of view: drop the on-screen reference. */
    fun onPageHidden(index: Int) {
        jobs.remove(index)?.cancel()
        requestedWidths.remove(index)
        _bitmaps.update { it - index }
        _plans.update { it - index }
    }

    // ------------------------------------------------------- links (T36/T61)

    /** Builds the link/reference index once per document, off the main thread. */
    private fun buildLinkIndex() {
        val doc = document ?: return
        linkJob?.cancel()
        linkIndex = null
        linkPreviews.clear()
        linkJob = textScope.launch {
            val built = runCatching { LinkResolver(doc).resolve() }.getOrNull()
            if (isActive && document === doc) linkIndex = built
        }
    }

    /**
     * The link or reference under a fractional page point. The text index is
     * built in the background; before it is ready this simply returns null.
     */
    fun linkAtFraction(pageIndex: Int, fractionX: Float, fractionY: Float): DocumentLink? {
        val index = linkIndex ?: return null
        val point = toUserSpace(pageIndex, fractionX, fractionY) ?: return null
        return index.hitTest(pageIndex, point[0], point[1])
    }

    /**
     * Renders and crops a target preview. The whole page is rendered at the
     * requested width on the shared document dispatcher; the crop is cached
     * in a small byte-bounded LRU so repeated previews cost nothing.
     */
    suspend fun linkPreview(target: LinkTarget, requestedWidthPx: Int): Bitmap? {
        if (target.pageIndex !in pages.indices) return null
        val key = buildString {
            append(target.pageIndex).append(':')
            append(target.previewRect.left).append(':')
            append(target.previewRect.bottom).append(':')
            append(target.previewRect.right).append(':')
            append(target.previewRect.top).append(':')
            append(requestedWidthPx)
        }
        linkPreviews.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        return withContext(documentDispatcher) {
            linkPreviews.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }
            val page = pages.getOrNull(target.pageIndex) ?: return@withContext null
            val box = _pageBoxes.value.getOrNull(target.pageIndex) ?: return@withContext null
            val width = requestedWidthPx.coerceIn(240, 1400)
            val scale = PageGeometry.scaleForPixelWidth(box, width).coerceIn(0.05, 3.0)
            val rendered = runCatching {
                PageBitmapRenderer.render(page, scale, maxPixels = 4_000_000, fontDir = fontDir)
            }.getOrNull() ?: return@withContext null
            val full = rendered.bitmap
            try {
                val plan = rendered.plan
                val corners = doubleArrayOf(
                    plan.matrix.apply(target.previewRect.left, target.previewRect.bottom)[0],
                    plan.matrix.apply(target.previewRect.right, target.previewRect.top)[0],
                    plan.matrix.apply(target.previewRect.left, target.previewRect.bottom)[1],
                    plan.matrix.apply(target.previewRect.right, target.previewRect.top)[1]
                )
                val targetLeft = min(corners[0], corners[1])
                val targetRight = max(corners[0], corners[1])
                val targetTop = min(corners[2], corners[3])
                val targetBottom = max(corners[2], corners[3])
                val targetWidth = (targetRight - targetLeft).coerceAtLeast(1.0)
                val targetHeight = (targetBottom - targetTop).coerceAtLeast(1.0)

                // A bibliography entry is a single line; give the card enough
                // context to recognise the page instead of a thin strip.
                val cropWidth = max(targetWidth * 1.2, plan.pixelWidth * 0.62)
                val cropHeight = max(max(targetHeight * 1.25, cropWidth / 1.7), 120.0)
                val centerX = (targetLeft + targetRight) / 2.0
                val centerY = (targetTop + targetBottom) / 2.0
                var x = (centerX - cropWidth / 2.0).roundToInt()
                var y = (centerY - cropHeight / 2.0).roundToInt()
                var w = cropWidth.roundToInt().coerceAtMost(full.width)
                var h = cropHeight.roundToInt().coerceAtMost(full.height)
                if (x < 0) x = 0
                if (y < 0) y = 0
                if (x + w > full.width) x = (full.width - w).coerceAtLeast(0)
                if (y + h > full.height) y = (full.height - h).coerceAtLeast(0)
                w = w.coerceIn(1, full.width)
                h = h.coerceIn(1, full.height)
                val crop = Bitmap.createBitmap(full, x, y, w, h)
                if (crop !== full) full.recycle()
                linkPreviews.put(key, crop)
                crop
            } catch (_: Throwable) {
                if (!full.isRecycled) full.recycle()
                null
            }
        }
    }

    // ------------------------------------------------------------------ search

    /**
     * Searches the whole document for [query] (case-insensitive). A blank query
     * clears the results. Results for an older query are dropped when a newer
     * search finishes first.
     */
    fun setSearchQuery(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _search.value = ViewerSearchState(query = query)
            return
        }
        clearSelection()
        _search.value = ViewerSearchState(query = query, running = true)
        searchJob = textScope.launch {
            val found = ArrayList<PageMatch>()
            for (index in pages.indices) {
                if (!isActive || found.size >= MAX_MATCHES) break
                val layout = layoutFor(index) ?: continue
                for (match in layout.search(query, MAX_MATCHES - found.size)) {
                    found += PageMatch(index, match)
                }
            }
            if (!isActive) return@launch
            _search.update { current ->
                if (current.query != query) {
                    current
                } else {
                    current.copy(
                        matches = found,
                        activeIndex = if (found.isEmpty()) -1 else 0,
                        running = false
                    )
                }
            }
        }
    }

    fun nextMatch() = stepMatch(1)

    fun previousMatch() = stepMatch(-1)

    private fun stepMatch(delta: Int) {
        _search.update { current ->
            val count = current.matches.size
            if (count == 0) {
                current
            } else {
                val next = ((current.activeIndex + delta) % count + count) % count
                current.copy(activeIndex = next)
            }
        }
    }

    // --------------------------------------------------------------- selection

    /**
     * Selects the word nearest the page point given as a fraction of the drawn
     * page ([fractionX] across, [fractionY] down). With [extend] the selection
     * grows from the anchor word to this one, following reading order.
     */
    fun selectAt(pageIndex: Int, fractionX: Float, fractionY: Float, extend: Boolean) {
        val plan = _plans.value[pageIndex] ?: return
        val inverse = plan.matrix.inverse() ?: return
        val point = inverse.apply(
            fractionX.toDouble() * plan.pixelWidth,
            fractionY.toDouble() * plan.pixelHeight
        )
        textScope.launch {
            val layout = layoutFor(pageIndex) ?: return@launch
            val word = layout.wordAt(point[0], point[1])
            if (word.isEmpty()) return@launch
            val anchor = if (extend) {
                selectionAnchor?.takeIf { it.first == pageIndex }?.second ?: word
            } else {
                word
            }
            selectionAnchor = pageIndex to anchor
            val start = min(anchor.first, word.first)
            val end = max(anchor.last, word.last) + 1
            _selection.value = ViewerSelection(
                pageIndex, start, end, layout.rectsFor(start, end), layout.text.substring(start, end)
            )
        }
    }

    /** Selects every character on [pageIndex]. */
    fun selectAll(pageIndex: Int) {
        textScope.launch {
            val layout = layoutFor(pageIndex) ?: return@launch
            if (layout.text.isEmpty()) return@launch
            selectionAnchor = null
            _selection.value = ViewerSelection(
                pageIndex, 0, layout.text.length, layout.rectsFor(0, layout.text.length), layout.text
            )
        }
    }

    fun clearSelection() {
        selectionAnchor = null
        _selection.value = null
    }

    // -------------------------------------------------- annotations (T12)

    /** Turns edit mode on/off; entering it lazily loads the form fields. */
    fun setEditMode(active: Boolean) {
        _edit.update { it.copy(active = active, inkDraft = if (active) it.inkDraft else null) }
        _objectEdit.update {
            it.copy(
                tool = if (active) ObjectEditTool.SELECT else ObjectEditTool.NONE,
                selectedId = if (active) it.selectedId else null
            )
        }
        if (active) ensureFieldsLoaded()
    }

    /** Selects an annotation tool; this also leaves object-edit mode. */
    fun setTool(tool: AnnotationTool) {
        _edit.update { it.copy(tool = tool) }
        _objectEdit.update { it.copy(tool = ObjectEditTool.NONE) }
    }

    /** Chooses an object tool without changing the markup tool. */
    fun setObjectTool(tool: ObjectEditTool) {
        _objectEdit.update {
            it.copy(
                tool = tool,
                selectedId = if (tool == ObjectEditTool.TEXT || tool == ObjectEditTool.IMAGE) {
                    null
                } else {
                    it.selectedId
                }
            )
        }
    }

    /** Maps a tool id from All tools to the viewer's initial object mode. */
    fun startObjectEditing(toolId: String?) {
        startViewerTool(
            when (toolId) {
                "text" -> "text"
                "image" -> "image"
                "highlight" -> "highlight"
                "ink" -> "ink"
                "note" -> "note"
                else -> "select"
            }
        )
    }

    /**
     * Opens the viewer mode for a tool chosen from the tools menu: object tools
     * (add/edit text/image) switch the object tool, annotate tools switch the
     * markup tool. Either way the viewer enters edit mode.
     */
    fun startViewerTool(mode: String) {
        _edit.update { it.copy(active = true) }
        when (mode) {
            "highlight" -> {
                _edit.update { it.copy(tool = AnnotationTool.HIGHLIGHT) }
                _objectEdit.update { it.copy(tool = ObjectEditTool.NONE, selectedId = null) }
            }
            "ink" -> {
                _edit.update { it.copy(tool = AnnotationTool.INK) }
                _objectEdit.update { it.copy(tool = ObjectEditTool.NONE, selectedId = null) }
            }
            "note" -> {
                _edit.update { it.copy(tool = AnnotationTool.NOTE) }
                _objectEdit.update { it.copy(tool = ObjectEditTool.NONE, selectedId = null) }
            }
            "text" -> _objectEdit.update { it.copy(tool = ObjectEditTool.TEXT, selectedId = null) }
            "image" -> _objectEdit.update { it.copy(tool = ObjectEditTool.IMAGE, selectedId = null) }
            else -> _objectEdit.update { it.copy(tool = ObjectEditTool.SELECT) }
        }
        ensureFieldsLoaded()
    }

    /** Undo for the top bar: object edits first, then the last annotation. */
    fun undoAny() {
        if (_objectEdit.value.canUndo) undoObjectEdit() else undoAnnotation()
    }

    /** Redo for the top bar (only object edits keep a redo stack). */
    fun redoAny() {
        if (_objectEdit.value.canRedo) redoObjectEdit()
    }

    /** Drops annotations added after [count]; used when a tool is cancelled. */
    fun trimAnnotationsTo(count: Int) = _edit.update {
        it.copy(annotations = it.annotations.take(count.coerceAtLeast(0)), inkDraft = null)
    }

    fun selectObject(id: String) = _objectEdit.update { it.copy(selectedId = id) }

    fun clearObjectSelection() = _objectEdit.update { it.copy(selectedId = null) }

    /**
     * Adds a text object through [PdfEngine]. [bounds] uses page fractions so
     * it survives zoom and re-rendering.
     */
    fun addTextObject(
        pageIndex: Int,
        bounds: EditableBounds,
        content: String,
        style: EditableStyle
    ): EditableObject? {
        if (!objectEngine.capabilities.addText) {
            _message.value = "Add text is coming soon: the PDF engine has no content-editing API yet"
            return null
        }
        val created = objectEngine.addText(pageIndex, bounds, content, style) ?: return null
        publishObjects(selectedId = created.id)
        _objectEdit.update { it.copy(tool = ObjectEditTool.SELECT) }
        return created
    }

    fun addImageObject(
        pageIndex: Int,
        bounds: EditableBounds,
        contentUri: String
    ): EditableObject? {
        if (!objectEngine.capabilities.addImage) {
            _message.value = "Add image is coming soon: the PDF engine has no content-editing API yet"
            return null
        }
        val created = objectEngine.addImage(
            pageIndex = pageIndex,
            bounds = bounds,
            contentUri = contentUri,
            style = EditableStyle()
        ) ?: return null
        publishObjects(selectedId = created.id)
        _objectEdit.update { it.copy(tool = ObjectEditTool.SELECT) }
        return created
    }

    fun updateTextObject(id: String, content: String, style: EditableStyle) {
        objectEngine.updateText(id, content, style) ?: return
        publishObjects(selectedId = id)
    }

    fun replaceImageObject(id: String, contentUri: String) {
        objectEngine.updateImage(id, contentUri) ?: return
        publishObjects(selectedId = id)
    }

    fun updateObjectBounds(id: String, bounds: EditableBounds) {
        objectEngine.updateBounds(id, bounds) ?: return
        publishObjects(selectedId = id)
    }

    fun deleteObject(id: String) {
        if (!objectEngine.delete(id)) return
        publishObjects(selectedId = null)
    }

    fun undoObjectEdit() {
        if (!objectEngine.undo()) return
        publishObjects(dirty = true)
    }

    fun redoObjectEdit() {
        if (!objectEngine.redo()) return
        publishObjects(dirty = true)
    }

    /** Saves object edits when the engine can; FakeEngine only marks the demo. */
    fun saveObjectEdits() {
        val current = _objectEdit.value
        if (!current.dirty) {
            _message.value = "No object edits to save yet"
            return
        }
        if (!objectEngine.capabilities.save || !objectEngine.save()) {
            _message.value = "Saving objects is coming soon: the PDF engine cannot write them yet"
            return
        }
        _objectEdit.update { it.copy(dirty = false) }
        _message.value = if (objectEngine.isFake) {
            "Demo save: text and image edits are simulated and were not written to the PDF"
        } else {
            "Saved"
        }
    }

    /** Save button for the object toolbar: handles markup and object edits. */
    fun saveAll() {
        val objectDirty = _objectEdit.value.dirty
        val markupDirty = _edit.value.hasPendingChanges
        if (!objectDirty && !markupDirty) {
            _message.value = "Nothing to save yet"
            return
        }
        if (objectDirty) saveObjectEdits()
        if (markupDirty) save()
    }

    private fun publishObjects(
        selectedId: String? = _objectEdit.value.selectedId,
        dirty: Boolean = true
    ) {
        val objects = objectEngine.allObjects()
        _objectEdit.update {
            it.copy(
                objects = objects,
                selectedId = selectedId?.takeIf { id -> objects.any { obj -> obj.id == id } },
                canUndo = objectEngine.canUndo,
                canRedo = objectEngine.canRedo,
                dirty = it.dirty || dirty,
                fake = objectEngine.isFake,
                engineName = objectEngine.name
            )
        }
    }

    /** Turns the current selection into a highlight annotation, then clears it. */
    fun highlightSelection() {
        val current = _selection.value ?: return
        val spec = selectionToHighlight(current) ?: return
        _edit.update { it.copy(annotations = it.annotations + spec) }
        clearSelection()
    }

    fun startInk(pageIndex: Int, fractionX: Float, fractionY: Float) {
        val point = toUserSpace(pageIndex, fractionX, fractionY) ?: return
        _edit.update { it.copy(inkDraft = InkDraft(pageIndex, listOf(point[0], point[1]))) }
    }

    fun extendInk(fractionX: Float, fractionY: Float) {
        val draft = _edit.value.inkDraft ?: return
        val point = toUserSpace(draft.pageIndex, fractionX, fractionY) ?: return
        if (draft.points.size >= 2) {
            val lastX = draft.points[draft.points.size - 2]
            val lastY = draft.points[draft.points.size - 1]
            val dx = point[0] - lastX
            val dy = point[1] - lastY
            if (dx * dx + dy * dy < 1.0) return // ignore sub-point jitter
        }
        _edit.update { it.copy(inkDraft = draft.copy(points = draft.points + listOf(point[0], point[1]))) }
    }

    fun finishInk() {
        val draft = _edit.value.inkDraft ?: return
        if (draft.points.size >= 4) {
            _edit.update {
                it.copy(
                    annotations = it.annotations + InkSpec(draft.pageIndex, listOf(draft.points)),
                    inkDraft = null
                )
            }
        } else {
            _edit.update { it.copy(inkDraft = null) }
        }
    }

    /** Adds a sticky note of [text] at the tapped point (page user space). */
    fun addNote(pageIndex: Int, fractionX: Float, fractionY: Float, text: String, title: String? = null) {
        if (text.isBlank()) return
        val point = toUserSpace(pageIndex, fractionX, fractionY) ?: return
        val rect = noteRectAt(point[0], point[1], NOTE_SIZE)
        _edit.update {
            it.copy(annotations = it.annotations + NoteSpec(pageIndex, rect, text.trim(), title = title))
        }
    }

    // ----------------------------------------------------------- stamps (UI-2)

    /**
     * Places [design] where the placement overlay shows it. The on-screen
     * width is converted through the page's displayed width, and the stamp is
     * turned by the page's /Rotate so it stays axis-aligned on screen.
     */
    fun addStamp(
        design: StampDesign,
        pageIndex: Int,
        centerX: Float,
        centerY: Float,
        widthFraction: Float
    ) {
        val plan = _plans.value[pageIndex] ?: run {
            _message.value = "Wait for the page to finish drawing, then place the stamp."
            return
        }
        val box = _pageBoxes.value.getOrNull(pageIndex) ?: return
        val center = pageFractionToUserSpace(plan, centerX, centerY) ?: return
        val size = (widthFraction * box.rotatedWidth).coerceIn(8.0, box.rotatedWidth)
        val aspect = design.aspect.takeIf { it.isFinite() && it > 0.2 } ?: 2.6
        val swapped = box.rotate % 180 != 0
        val boxWidth = if (swapped) size / aspect else size
        val boxHeight = if (swapped) size else size / aspect
        val x = center[0] - boxWidth / 2.0
        val y = center[1] - boxHeight / 2.0
        val author = AppSettings.authorName(getApplication())
        viewModelScope.launch {
            val image = withContext(Dispatchers.IO) { customStampImage(design) }
            _edit.update {
                it.copy(
                    active = true,
                    annotations = it.annotations + StampSpec(
                        pageIndex = pageIndex,
                        design = design,
                        x = x,
                        y = y,
                        size = size,
                        rotation = box.rotate,
                        author = author,
                        image = image
                    )
                )
            }
            _message.value = "Stamp placed. Save to add it to the PDF."
        }
    }

    private fun customStampImage(design: StampDesign) = runCatching {
        if (design.art != com.pdfpaper.engine.stamp.StampArt.IMAGE) return@runCatching null
        val bytes = CustomStampStore(getApplication()).load()
            .firstOrNull { it.id == design.id }
            ?.image
            ?: return@runCatching null
        EditImages.decode(bytes, AndroidRasterDecoder).getOrNull()
    }.getOrNull()

    // --------------------------------------------------------- comments (UI-2)

    /** Re-reads the comment threads of the open document. */
    fun refreshComments() = loadComments()

    private fun loadComments() {
        val doc = document ?: return
        _commentsLoading.value = true
        _commentsError.value = null
        textScope.launch {
            val result = runCatching { doc.comments() }
            if (!isActive) return@launch
            result.fold(
                onSuccess = {
                    _comments.value = it
                    _commentsLoading.value = false
                },
                onFailure = {
                    _commentsLoading.value = false
                    _commentsError.value = it.message ?: "Could not read the comments."
                }
            )
        }
    }

    /** Replies to a saved comment; the reply is written on the next save. */
    fun addCommentReply(targetObjNum: Int, pageIndex: Int, text: String) {
        if (targetObjNum <= 0 || text.isBlank()) return
        val author = AppSettings.authorName(getApplication())
        _edit.update {
            it.copy(
                active = true,
                annotations = it.annotations + ReplySpec(
                    pageIndex = pageIndex,
                    targetObjNum = targetObjNum,
                    contents = text.trim(),
                    title = author
                )
            )
        }
        _commentReplies.update { current ->
            current + (targetObjNum to (current[targetObjNum].orEmpty() + CommentReplyUi(
                author = author,
                text = text.trim(),
                date = formatCommentNow()
            )))
        }
        _message.value = "Reply added. Save to write it into the PDF."
    }

    /** Resolves or reopens a saved comment on the next save. */
    fun setCommentResolved(targetObjNum: Int, pageIndex: Int, resolved: Boolean) {
        if (targetObjNum <= 0) return
        val status = if (resolved) CommentStatus.COMPLETED else CommentStatus.NONE
        _edit.update {
            it.copy(
                active = true,
                annotations = it.annotations + StatusSpec(
                    pageIndex = pageIndex,
                    targetObjNum = targetObjNum,
                    status = status,
                    title = AppSettings.authorName(getApplication())
                )
            )
        }
        _commentStatuses.update { it + (targetObjNum to status) }
        _message.value = if (resolved) {
            "Comment resolved. Save to write the change into the PDF."
        } else {
            "Comment reopened. Save to write the change into the PDF."
        }
    }

    // ------------------------------------------------------- signatures (UI-2)

    /** Verifies the current file with the platform trust store. */
    fun loadSignatures(force: Boolean = false) {
        if (!force && signaturesLoaded) return
        signaturesLoaded = true
        val path = currentPath
        if (path.isBlank()) {
            _signatures.value = ViewerSignaturesState(error = "No file selected.")
            return
        }
        _signatures.value = ViewerSignaturesState(loading = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PdfSignatures.verify(File(path), PlatformTrustStore).getOrThrow()
                }
            }
            result.fold(
                onSuccess = { verification ->
                    _signatures.value = ViewerSignaturesState(verification = verification)
                },
                onFailure = { error ->
                    _signatures.value = ViewerSignaturesState(
                        error = error.message ?: "This file's signatures could not be checked."
                    )
                }
            )
        }
    }

    fun reloadSignatures() = loadSignatures(force = true)

    // --------------------------------------------------------- signing (UI-3)

    fun clearSigningResult() {
        if (!_signing.value.busy) _signing.value = ViewerSigningState()
    }

    /** Hands the signed bytes to the usual SAF save flow. */
    fun exportSigned() {
        val outcome = _signing.value.outcome ?: return
        _export.value = ViewerExport(outcome.bytes, outcome.suggestedName)
    }

    /**
     * Places [draft] on [pageIndex] as a T26 visual signature. The placement
     * fraction is the centre of the on-screen signature box; the engine
     * converts the artwork into a self-contained appearance annotation.
     */
    fun placeVisualSignature(
        draft: SignatureDraft,
        pageIndex: Int,
        centerX: Float,
        centerY: Float,
        widthFraction: Float,
        captionName: String?,
        captionDate: String?
    ) {
        if (_signing.value.busy) return
        val path = currentPath
        if (path.isBlank()) {
            _signing.value = ViewerSigningState(error = "No file selected.")
            return
        }
        val placement = signaturePlacement(pageIndex, centerX, centerY, widthFraction, draft.previewAspect())
        if (placement == null) {
            _signing.value = ViewerSigningState(error = "That page is not ready yet. Scroll to it and try again.")
            return
        }
        val author = AppSettings.authorName(getApplication())
        _signing.value = ViewerSigningState(busy = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = File(path).readBytes()
                    PdfDocument.open(ByteArraySource(bytes)).getOrThrow().use { document ->
                        val artwork = draft.toArtwork().getOrThrow()
                        VisualSignatures.place(
                            document = document,
                            artwork = artwork,
                            placement = placement,
                            options = VisualSignatureOptions(
                                caption = SignatureCaption(captionName, captionDate),
                                author = author
                            ),
                            decoder = AndroidRasterDecoder
                        ).getOrThrow().bytes
                    }
                }
            }
            result.fold(
                onSuccess = { bytes ->
                    _signing.value = ViewerSigningState(
                        outcome = ViewerSigningOutcome(
                            bytes = bytes,
                            suggestedName = signedName(),
                            visualOnly = true,
                            visible = true,
                            pageIndex = pageIndex
                        )
                    )
                },
                onFailure = { error ->
                    _signing.value = ViewerSigningState(
                        error = error.message ?: "The visual signature could not be added."
                    )
                }
            )
        }
    }

    /**
     * Signs the current file with an imported certificate (T27). The signature
     * is verified immediately with the platform trust store so the result
     * dialog can report its real state; an untrusted certificate still signs
     * and simply reports [SignatureReport] as intact but unverified.
     */
    fun signDigitally(
        certificate: CertificateSummary,
        reason: String,
        location: String,
        visible: Boolean,
        pageIndex: Int,
        centerX: Float,
        centerY: Float,
        widthFraction: Float
    ) {
        if (_signing.value.busy) return
        val path = currentPath
        if (path.isBlank()) {
            _signing.value = ViewerSigningState(error = "No file selected.")
            return
        }
        val placed = signaturePlacement(pageIndex, centerX, centerY, widthFraction, DIGITAL_SIGNATURE_ASPECT)
        if (visible && placed == null) {
            _signing.value = ViewerSigningState(error = "That page is not ready yet. Scroll to it and try again.")
            return
        }
        val rect = if (visible && placed != null) {
            val aspect = DIGITAL_SIGNATURE_ASPECT.toDouble()
            val width = placed.width
            val height = width / aspect
            PdfRect(placed.x, placed.y, placed.x + width, placed.y + height)
        } else {
            null
        }
        val cleanReason = reason.trim().ifBlank { null }
        val cleanLocation = location.trim().ifBlank { null }
        val now = Instant.now()
        val displayName = certificate.commonName ?: certificate.label
        val options = SigningOptions(
            pageIndex = pageIndex,
            rect = rect,
            visible = visible,
            name = displayName,
            date = now,
            reason = cleanReason,
            location = cleanLocation,
            appearance = SignatureAppearance(
                name = displayName,
                date = now,
                dateText = formatSigningDate(now),
                reason = cleanReason,
                location = cleanLocation
            ),
            signingTime = now
        )
        _signing.value = ViewerSigningState(busy = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val key = DigitalCertificateStore(getApplication())
                        .signingKey(certificate.id)
                        .getOrThrow()
                    val signed = PdfSigner.sign(File(path), key, options).getOrThrow()
                    val verification = runCatching {
                        PdfSignatures.verify(signed.bytes, PlatformTrustStore).getOrThrow()
                    }
                    val report = verification.getOrNull()
                        ?.signatures
                        ?.lastOrNull { it.fieldName == signed.fieldName }
                        ?: verification.getOrNull()?.signatures?.lastOrNull()
                    Triple(
                        signed.bytes,
                        report,
                        verification.exceptionOrNull()?.message
                    )
                }
            }
            result.fold(
                onSuccess = { (bytes, report, verificationError) ->
                    _signing.value = ViewerSigningState(
                        outcome = ViewerSigningOutcome(
                            bytes = bytes,
                            suggestedName = signedName(),
                            visualOnly = false,
                            report = report,
                            verificationError = verificationError,
                            certificate = certificate,
                            reason = cleanReason,
                            location = cleanLocation,
                            visible = visible,
                            pageIndex = pageIndex
                        )
                    )
                },
                onFailure = { error ->
                    _signing.value = ViewerSigningState(
                        error = error.message ?: "The document could not be signed."
                    )
                }
            )
        }
    }

    /** Builds a T26 placement whose width follows the artwork aspect ratio. */
    private fun signaturePlacement(
        pageIndex: Int,
        centerX: Float,
        centerY: Float,
        widthFraction: Float,
        aspect: Float
    ): SignaturePlacement? {
        val box = _pageBoxes.value.getOrNull(pageIndex) ?: return null
        val plan = _plans.value[pageIndex] ?: PageGeometry.plan(box, 1.0)
        val center = pageFractionToUserSpace(plan, centerX, centerY) ?: return null
        val ratio = aspect.toDouble().coerceIn(1.2, 8.0)
        val width = (widthFraction.toDouble() * box.rotatedWidth).coerceIn(24.0, box.rotatedWidth)
        val height = width / ratio
        val x = (center[0] - width / 2)
            .coerceIn(box.left, (box.right - width).coerceAtLeast(box.left))
        val y = (center[1] - height / 2)
            .coerceIn(box.bottom, (box.top - height).coerceAtLeast(box.bottom))
        return SignaturePlacement(
            pageIndex = pageIndex,
            x = x,
            y = y,
            width = width,
            rotation = box.rotate
        )
    }

    private fun formatSigningDate(instant: Instant): String =
        DateTimeFormatter
            .ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)

    // -------------------------------------------------------------- OCR (UI-2)

    /**
     * Runs on-device OCR over all pages or [currentPage]. The result is kept
     * in [ViewerOcrState.hasResult] until the user asks to save it.
     */
    fun startOcr(language: String, allPages: Boolean, currentPage: Int) {
        val doc = document ?: return
        if (_ocr.value.running) return
        val provider = MlKitOcrProvider()
        ocrProvider = provider
        ocrResult = null
        val requested = if (allPages) pages.size else 1
        _ocr.value = ViewerOcrState(
            running = true,
            requested = requested,
            language = language
        )
        provider.onProcessed = { processed ->
            _ocr.update { it.copy(processed = processed) }
        }
        val selected = if (allPages) null else listOf(currentPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
        ocrJob = textScope.launch {
            val result = runCatching {
                PdfOcr.makeSearchable(doc, provider, language, selected).getOrThrow()
            }
            val cancelled = provider.cancelled
            provider.close()
            if (!isActive) return@launch
            result.fold(
                onSuccess = { searchable ->
                    if (cancelled) {
                        _ocr.update {
                            it.copy(
                                running = false,
                                finished = true,
                                summary = "OCR cancelled. The original PDF was left unchanged."
                            )
                        }
                    } else if (searchable.pagesAdded == 0) {
                        _ocr.update {
                            it.copy(
                                running = false,
                                finished = true,
                                summary = noTextSummary(searchable)
                            )
                        }
                    } else {
                        ocrResult = searchable
                        _ocr.update {
                            it.copy(
                                running = false,
                                finished = true,
                                hasResult = true,
                                summary = "Added ${searchable.wordsAdded} words to " +
                                    "${searchable.pagesAdded} page" +
                                    (if (searchable.pagesAdded == 1) "" else "s") + "."
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _ocr.update {
                        it.copy(
                            running = false,
                            finished = true,
                            summary = "OCR failed: ${error.message ?: "unknown error"}"
                        )
                    }
                }
            )
        }
    }

    /** Stops after the page ML Kit is currently reading; no result is offered. */
    fun cancelOcr() {
        ocrProvider?.cancel()
        ocrResult = null
        _ocr.update {
            it.copy(
                hasResult = false,
                summary = if (it.running) "Cancelling after this page…" else it.summary
            )
        }
    }

    /** Hands the searchable bytes to the usual SAF save flow. */
    fun exportSearchable() {
        val result = ocrResult ?: return
        val base = (_state.value.displayName ?: "document.pdf").substringBeforeLast('.')
        _export.value = ViewerExport(result.bytes, "$base-searchable.pdf")
    }

    private fun noTextSummary(result: SearchableResult): String {
        val skipped = result.pages.count { it.status == com.pdfpaper.engine.ocr.OcrPageStatus.SKIPPED_HAS_TEXT }
        val noImage = result.pages.count { it.status == com.pdfpaper.engine.ocr.OcrPageStatus.NO_IMAGE }
        return when {
            skipped > 0 -> "No new text was added: $skipped page" +
                (if (skipped == 1) " already has" else "s already have") +
                " searchable text. Choose \"This page\" to run OCR anyway."
            noImage > 0 -> "No new text was added: no page images were found to read."
            else -> "No searchable words were recognized. Try a sharper scan or a different language."
        }
    }

    fun undoAnnotation() = _edit.update {
        it.copy(annotations = it.annotations.dropLast(1), inkDraft = null)
    }

    fun clearAnnotations() = _edit.update { it.copy(annotations = emptyList(), inkDraft = null) }

    // --------------------------------------------------------- forms (T12)

    fun setFieldValue(name: String, value: String) = _edit.update {
        it.copy(fieldValues = it.fieldValues + (name to value))
    }

    private fun ensureFieldsLoaded() {
        if (fieldsLoaded) return
        fieldsLoaded = true
        _edit.update { it.copy(loadingFields = true) }
        textScope.launch {
            val fields = runCatching { document?.formFields() ?: emptyList() }.getOrDefault(emptyList())
            if (!isActive) return@launch
            _edit.update { it.copy(fields = fields, loadingFields = false) }
        }
    }

    // ---------------------------------------------------------- saving (T12)

    /**
     * Builds a new PDF with the pending annotations and field values and parks
     * it in [export] for the UI to write through SAF. Document access stays on
     * the shared document dispatcher.
     */
    fun save() {
        val doc = document ?: return
        val current = _edit.value
        if (!current.hasPendingChanges) {
            _message.value = "Nothing to save yet"
            return
        }
        _edit.update { it.copy(saving = true) }
        textScope.launch {
            val result = runCatching {
                PdfPageOps.edit(doc, current.annotations, current.fieldValues).getOrThrow()
            }
            if (!isActive) return@launch
            result.fold(
                onSuccess = { bytes ->
                    _edit.update { it.copy(saving = false) }
                    _export.value = ViewerExport(bytes, suggestedName())
                },
                onFailure = { error ->
                    _edit.update { it.copy(saving = false) }
                    _message.value = error.message ?: "Save failed"
                }
            )
        }
    }

    /** Writes the pending export to the user-chosen [uri]. */
    fun completeExport(uri: Uri) {
        val pending = _export.value ?: return
        _export.value = null
        _edit.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { it.write(pending.bytes) }
                        ?: throw IOException("Could not open the destination for writing")
                }
            }
            result.fold(
                onSuccess = {
                    _message.value = "Saved"
                    reloadSaved(pending.bytes)
                },
                onFailure = { error ->
                    _edit.update { it.copy(saving = false) }
                    _message.value = error.message ?: "Save failed"
                }
            )
        }
    }

    fun cancelExport() {
        _export.value = null
    }

    /**
     * After a successful save, writes the new bytes to a private cache file and
     * reopens it. This is what makes saved comments, replies and stamps appear
     * as real, addressable annotations in the same session.
     */
    private fun reloadSaved(bytes: ByteArray) {
        val name = _state.value.displayName
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val directory = File(getApplication<Application>().cacheDir, "edited").apply {
                        mkdirs()
                    }
                    File(directory, "edited-${System.currentTimeMillis()}.pdf").also {
                        it.writeBytes(bytes)
                    }
                }.getOrNull()
            }
            if (file == null) {
                _edit.update {
                    it.copy(saving = false, annotations = emptyList(), fieldValues = emptyMap())
                }
                _message.value = "Saved. Reopen the PDF to see the saved comments."
                return@launch
            }
            displayNameOverride = name
            currentPath = file.absolutePath
            openWith("")
        }
    }

    /**
     * Saves the PDF that is open right now (the banner's "Save as PDF"): reads
     * the cached file back and parks it in [export] for the same SAF flow the
     * annotation export uses.
     */
    fun saveConvertedPdf(suggestedName: String) {
        val path = currentPath
        if (path.isBlank()) return
        viewModelScope.launch {
            val bytes = runCatching {
                withContext(Dispatchers.IO) { File(path).readBytes() }
            }.getOrNull()
            if (bytes == null) {
                _message.value = "The converted PDF is no longer in the cache; open the document again"
            } else {
                _export.value = ViewerExport(bytes, suggestedName)
            }
        }
    }

    /** Maps a fractional page point to PDF user space through the render plan. */
    private fun toUserSpace(pageIndex: Int, fractionX: Float, fractionY: Float): DoubleArray? {
        val plan = _plans.value[pageIndex]
            ?: _pageBoxes.value.getOrNull(pageIndex)?.let { PageGeometry.plan(it, 1.0) }
            ?: return null
        return pageFractionToUserSpace(plan, fractionX, fractionY)
    }

    private fun suggestedName(): String {
        val base = (_state.value.displayName ?: "document.pdf").substringBeforeLast('.')
        return "$base-annotated.pdf"
    }

    private fun signedName(): String {
        val base = (_state.value.displayName ?: "document.pdf").substringBeforeLast('.')
        return "$base-signed.pdf"
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** Shows a short snackbar message (used by the tools menu). */
    fun showMessage(text: String) {
        _message.value = text
    }

    override fun onCleared() {
        renderScope.cancel()
        textScope.cancel()
        ocrProvider?.cancel()
        ocrProvider?.close()
        ocrProvider = null
        closeCurrentDocument()
        _selection.value = null
        _edit.value = ViewerEditState()
        objectEngine.clear()
        _objectEdit.value = ObjectEditState(
            fake = objectEngine.isFake,
            engineName = objectEngine.name
        )
        _export.value = null
        super.onCleared()
    }

    // ---------------------------------------------------------------- internals

    /** Stops page jobs and closes the document; safe to call more than once. */
    private fun closeCurrentDocument() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        requestedWidths.clear()
        linkJob?.cancel()
        linkJob = null
        linkIndex = null
        linkPreviews.clear()
        cache.clear()
        layouts.clear()
        _bitmaps.value = emptyMap()
        _plans.value = emptyMap()
        runCatching { document?.close() }
        document = null
        pages = emptyList()
        _pageBoxes.value = emptyList()
    }

    /** Text layout of one page; must be called on the document dispatcher. */
    private fun layoutFor(index: Int): PageLayout? {
        layouts.get(index)?.let { return it }
        val page = pages.getOrNull(index) ?: return null
        val layout = page.layout()
        layouts.put(index, layout)
        return layout
    }

    private fun clearTextState() {
        searchJob?.cancel()
        _search.value = ViewerSearchState()
        _selection.value = null
        selectionAnchor = null
        layouts.clear()
        fieldsLoaded = false
        _edit.value = ViewerEditState()
        objectEngine.clear()
        _objectEdit.value = ObjectEditState(
            fake = objectEngine.isFake,
            engineName = objectEngine.name
        )
        _export.value = null
        _comments.value = emptyList()
        _commentsLoading.value = false
        _commentsError.value = null
        _commentReplies.value = emptyMap()
        _commentStatuses.value = emptyMap()
        signaturesLoaded = false
        _signatures.value = ViewerSignaturesState()
        if (!_signing.value.busy) _signing.value = ViewerSigningState()
        ocrJob?.cancel()
        ocrJob = null
        ocrProvider?.cancel()
        ocrProvider = null
        ocrResult = null
        _ocr.value = ViewerOcrState()
    }

    private fun publish(index: Int, plan: PageRenderPlan, bitmap: Bitmap) {
        _plans.update { it + (index to plan) }
        _bitmaps.update { it + (index to bitmap) }
    }

    /** A cached bitmap is reusable when its width is within ~10% of the target. */
    private fun resolutionMatches(cachedWidth: Int, targetWidth: Int): Boolean =
        abs(cachedWidth - targetWidth) <= max(16, targetWidth / 10)

    private companion object {
        /** Upper bound on search hits kept for one query. */
        const val MAX_MATCHES: Int = 1000

        /** Default size, in points, of a newly placed sticky note (T12). */
        const val NOTE_SIZE: Double = 20.0

        /** Width/height of the visible digital signature panel (UI-3). */
        const val DIGITAL_SIGNATURE_ASPECT: Float = 2.8f

        /**
         * Bitmap budget: a sixth of the heap, clamped to 16..64 MB. Together
         * with one-page-at-a-time rendering this keeps a 500-page document
         * bounded regardless of page count.
         */
        fun bitmapBudgetBytes(): Long {
            val maxHeap = Runtime.getRuntime().maxMemory()
            return (maxHeap / 6).coerceIn(16L * 1024 * 1024, 64L * 1024 * 1024)
        }
    }
}
