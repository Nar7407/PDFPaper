package com.pdfpaper.app.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.speech.ReadAloudHub
import com.pdfpaper.app.speech.ReadAloudService
import com.pdfpaper.app.ui.viewer.speech.SpeechPosition
import com.pdfpaper.app.data.AutosaveMode
import com.pdfpaper.app.data.CustomStampStore
import com.pdfpaper.app.data.DocumentAccess
import com.pdfpaper.app.data.DocumentFingerprints
import com.pdfpaper.app.data.DocumentLocation
import com.pdfpaper.app.data.DocumentLookStore
import com.pdfpaper.app.data.EinkDisplay
import com.pdfpaper.app.data.EinkResolution
import com.pdfpaper.app.data.LookResolution
import com.pdfpaper.app.data.OpenedDocument
import com.pdfpaper.app.data.ReadingMode
import com.pdfpaper.app.data.ReadingOrientation
import com.pdfpaper.app.data.ReadingRestore
import com.pdfpaper.app.data.RestoredReading
import com.pdfpaper.app.data.AnnotationLayerStore
import com.pdfpaper.app.data.ReadingState
import com.pdfpaper.app.data.ReadingStateStore
import com.pdfpaper.app.data.RecentStore
import com.pdfpaper.app.data.save.ContentSaveTarget
import com.pdfpaper.app.data.save.DocumentFingerprint
import com.pdfpaper.app.data.save.FileChangedException
import com.pdfpaper.app.data.save.FileSaveTarget
import com.pdfpaper.app.data.save.SaveCoordinator
import com.pdfpaper.app.data.save.SaveExpectation
import com.pdfpaper.app.data.save.SaveReport
import com.pdfpaper.app.data.save.SaveWriteMode
import com.pdfpaper.app.data.save.SavedVersion
import com.pdfpaper.app.data.save.VersionStore
import com.pdfpaper.app.annot.AnnotationEditor
import com.pdfpaper.app.annot.OpenedDocumentIndex
import com.pdfpaper.app.edit.EditableBounds
import com.pdfpaper.app.edit.EditableObject
import com.pdfpaper.app.edit.EditableStyle
import com.pdfpaper.app.edit.EditingRuntime
import com.pdfpaper.app.edit.PdfEngine
import com.pdfpaper.app.ocr.MlKitOcrProvider
import com.pdfpaper.app.render.AndroidImageDecoder
import com.pdfpaper.app.render.AndroidRasterDecoder
import com.pdfpaper.app.render.PageBitmapRenderer
import com.pdfpaper.app.render.RenderedPage
import com.pdfpaper.engine.annot.AnnotationFilter
import com.pdfpaper.engine.annot.AnnotationFilterReport
import com.pdfpaper.engine.annot.annotationFilterReport
import com.pdfpaper.app.signatures.CertificateSummary
import com.pdfpaper.app.signatures.DigitalCertificateStore
import com.pdfpaper.app.signatures.PlatformTrustStore
import com.pdfpaper.app.signatures.SignatureDraft
import com.pdfpaper.app.signatures.previewAspect
import com.pdfpaper.app.render.AndroidPageRasterizer
import com.pdfpaper.app.signatures.toArtwork
import com.pdfpaper.app.ui.crop.CropMargins
import com.pdfpaper.app.ui.viewer.fit.FitContentMode
import com.pdfpaper.app.ui.viewer.fit.FitContentState
import com.pdfpaper.app.ui.viewer.fit.fitCropBoxes
import com.pdfpaper.app.ui.viewer.fit.fitViewBoxes
import com.pdfpaper.app.ui.viewer.reflow.REFLOW_PAGE_CACHE
import com.pdfpaper.app.ui.viewer.reflow.ReflowPosition
import com.pdfpaper.app.ui.viewer.reflow.ReflowRegion
import com.pdfpaper.app.ui.viewer.reflow.ReflowSettings
import com.pdfpaper.app.ui.viewer.reflow.ReflowUiState
import com.pdfpaper.app.ui.viewer.reflow.isTextSmall
import com.pdfpaper.app.ui.viewer.reflow.reflowRegionBox
import com.pdfpaper.app.ui.viewer.reflow.reflowRegionKey
import com.pdfpaper.app.ui.viewer.reflow.reflowRegionScale
import com.pdfpaper.app.ui.viewer.flow.ColumnFlowSettings
import com.pdfpaper.app.ui.viewer.flow.ColumnFlowUiState
import com.pdfpaper.app.ui.viewer.flow.FLOW_BITMAP_CACHE
import com.pdfpaper.app.ui.viewer.flow.FLOW_PAGE_CACHE
import com.pdfpaper.app.ui.viewer.flow.FlowPosition
import com.pdfpaper.app.ui.viewer.flow.FlowSegmentBitmap
import com.pdfpaper.app.ui.viewer.flow.applyFlowCorrection
import com.pdfpaper.app.ui.viewer.flow.flowSegmentBox
import com.pdfpaper.app.ui.viewer.flow.flowSegmentKey
import com.pdfpaper.app.ui.viewer.flow.flowSegmentScale
import com.pdfpaper.app.ui.viewer.flow.flowStep as nextFlowSegment
import com.pdfpaper.engine.flow.ColumnFlow
import com.pdfpaper.engine.flow.ColumnFlowOptions
import com.pdfpaper.engine.flow.FlowPage
import com.pdfpaper.engine.flow.SegmentRef
import com.pdfpaper.app.ui.redact.RedactionFindMode
import com.pdfpaper.app.ui.redact.RedactionFindUi
import com.pdfpaper.app.ui.redact.RedactionMarkUi
import com.pdfpaper.app.ui.redact.RedactionOutcome
import com.pdfpaper.app.ui.redact.RedactionProperties
import com.pdfpaper.app.ui.redact.RedactionTool
import com.pdfpaper.app.ui.redact.RedactionUiState
import com.pdfpaper.app.ui.redact.SanitizeOutcome
import com.pdfpaper.app.ui.redact.SanitizeUiState
import com.pdfpaper.app.ui.redact.isRemovalEnabled
import com.pdfpaper.app.ui.redact.toUi
import com.pdfpaper.app.ui.redact.withRemoval
import com.pdfpaper.app.ui.search.RegexActions
import com.pdfpaper.app.ui.search.RegexProgress
import com.pdfpaper.app.ui.search.RegexRedactionHandoff
import com.pdfpaper.app.ui.search.RegexScope
import com.pdfpaper.app.ui.search.RegexTesterResult
import com.pdfpaper.app.ui.search.RegexUiState
import com.pdfpaper.app.ui.search.TESTER_MAX_MATCHES
import com.pdfpaper.app.ui.search.TESTER_TIME_MS
import com.pdfpaper.app.ui.search.describeRegexTester
import com.pdfpaper.app.ui.search.documentOptions
import com.pdfpaper.app.ui.search.regexConfirmText
import com.pdfpaper.app.ui.search.rowsForResult
import com.pdfpaper.app.ui.search.stepRegexIndex
import com.pdfpaper.app.ui.viewer.ink.InkGeometry
import com.pdfpaper.app.ui.viewer.ink.InkPalette
import com.pdfpaper.app.ui.viewer.ink.InkSample
import com.pdfpaper.app.ui.viewer.ink.InkStroke
import com.pdfpaper.app.ui.viewer.ink.InkToolMode
import com.pdfpaper.app.ui.viewer.ink.InkTransform
import com.pdfpaper.app.ui.viewer.ink.InkUiState
import com.pdfpaper.engine.annot.AnnotationSpec
import com.pdfpaper.engine.annot.CommentStatus
import com.pdfpaper.engine.annot.InkSpec
import com.pdfpaper.engine.annot.PdfPaperInk
import com.pdfpaper.engine.annot.annotations
import com.pdfpaper.engine.annot.NoteSpec
import com.pdfpaper.engine.annot.PdfComment
import com.pdfpaper.engine.annot.ReplySpec
import com.pdfpaper.engine.annot.StampSpec
import com.pdfpaper.engine.annot.StatusSpec
import com.pdfpaper.engine.annot.comments
import com.pdfpaper.engine.edit.EditImages
import com.pdfpaper.engine.fit.ContentFit
import com.pdfpaper.engine.fit.ContentFitOptions
import com.pdfpaper.engine.form.FieldKind
import com.pdfpaper.engine.form.PdfField
import com.pdfpaper.engine.form.formFields
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.links.DocumentLink
import com.pdfpaper.engine.links.LinkIndex
import com.pdfpaper.engine.links.LinkResolver
import com.pdfpaper.engine.links.LinkTarget
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.ocr.PdfOcr
import com.pdfpaper.engine.ocr.SearchableResult
import com.pdfpaper.engine.page.PdfPage
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.redact.PdfRedaction
import com.pdfpaper.engine.redact.RedactionMarkType
import com.pdfpaper.engine.redact.RedactionPreset
import com.pdfpaper.engine.reflow.ReflowExtractor
import com.pdfpaper.engine.eink.EinkSettings
import com.pdfpaper.engine.look.LookSuggestion
import com.pdfpaper.engine.render.ColorTransform
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageFilter
import com.pdfpaper.engine.render.PageGeometry
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.render.PageTheme
import com.pdfpaper.engine.render.PageThemeMode
import com.pdfpaper.engine.sanitize.FormDataHandling
import com.pdfpaper.engine.sanitize.PdfSanitizer
import com.pdfpaper.engine.sanitize.SanitizeItem
import com.pdfpaper.engine.sanitize.SanitizeOptions
import com.pdfpaper.engine.security.PdfPasswordException
import com.pdfpaper.engine.sign.SignatureCaption
import com.pdfpaper.engine.sign.SignaturePlacement
import com.pdfpaper.engine.sign.VisualSignatureOptions
import com.pdfpaper.engine.sign.VisualSignatures
import com.pdfpaper.engine.stamp.StampDesign
import com.pdfpaper.engine.text.PageLayout
import com.pdfpaper.engine.text.PageMatch
import com.pdfpaper.engine.text.RegexFlag
import com.pdfpaper.engine.text.RegexResult
import com.pdfpaper.engine.text.RegexSearch
import com.pdfpaper.engine.text.TextRect
import com.pdfpaper.engine.text.layout
import com.pdfpaper.engine.text.searchRegex
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    val passwordError: String? = null,
    /** A local path or a `content://` URI the viewer currently reads (T47). */
    val sourceLocation: String = "",
    /** True when the provider accepts writes, so Save in place can be offered. */
    val canSaveInPlace: Boolean = false,
    // ---- Task T38b: the saved reading session, resolved before the first
    // frame. The screen creates its page list at these values, so nothing is
    // drawn at page 1 and then scrolled away.
    val startPage: Int = 0,
    val startFraction: Float = 0f,
    val startZoom: Float = 1f,
    val startMode: ReadingMode = ReadingMode.PAGE,
    /** Where the reflow overlay opens when the saved mode was reflow. */
    val startReflow: ReflowPosition? = null,
    /** T59: where column flow opens when the saved mode was column flow. */
    val startFlow: FlowPosition? = null,
    val orientation: ReadingOrientation = ReadingOrientation.AUTO,
    /** The tool the reader had selected last, restored as a label only. */
    val startToolId: String? = null,
    /** T34: the paragraph the last reading aloud reached. */
    val startSpeechPage: Int = 0,
    val startSpeechParagraph: Int = 0,
    val hasSavedSpeech: Boolean = false,
    /** Bumped per document, so the screen rebuilds its list state once. */
    val restoreToken: Int = 0
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

/** What produced an export, so a successful save can update the right state. */
enum class ViewerExportKind {
    EDIT,
    SIGNED,
    SEARCHABLE,
    REDACTION,
    SANITIZE,
    HIGHLIGHTS
}

/** Output bytes waiting for the user to choose a destination (SAF CreateDocument). */
class ViewerExport(
    val bytes: ByteArray,
    val suggestedName: String,
    val kind: ViewerExportKind = ViewerExportKind.EDIT,
    /** The exact source bytes this export was derived from (T55). */
    val originalBytes: ByteArray? = null,
    val writeMode: SaveWriteMode = SaveWriteMode.FULL_REWRITE,
    val expectation: SaveExpectation? = null,
    /** How many highlights a HIGHLIGHTS copy carries (T65). */
    val highlightCount: Int = 0
)

/** Save lifecycle shown in the viewer's status line (T55). */
enum class ViewerSaveStatus { IDLE, UNSAVED, SAVING, SAVED, ERROR }

/** One pre-save version offered by "Restore previous version". */
data class ViewerVersionEntry(
    val id: String,
    val savedAt: Long,
    val sizeBytes: Long
)

/** Save/autosave state, version history and conflict prompts (T55). */
data class ViewerSaveState(
    val status: ViewerSaveStatus = ViewerSaveStatus.IDLE,
    val message: String? = null,
    val versions: List<ViewerVersionEntry> = emptyList(),
    val conflict: Boolean = false,
    val conflictMessage: String? = null,
    val signatureWarning: String? = null,
    val canSaveInPlace: Boolean = false,
    val autosaveMode: AutosaveMode = AutosaveMode.IN_PLACE
)

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
    val saving: Boolean = false,
    val inkDirty: Boolean = false
) {
    val hasPendingChanges: Boolean get() =
        annotations.isNotEmpty() || fieldValues.isNotEmpty() || inkDirty
}

/** One cached page bitmap plus the render plan that produced it. */
private class CachedPage(val plan: PageRenderPlan, val bitmap: Bitmap)

/**
 * T62: a filter the reader may want for this page. It is only ever a banner:
 * the reader applies it or dismisses it, and a dismissal is remembered per
 * document.
 */
data class LookHint(val filter: PageFilter, val message: String)

/** What opening a document produced, including its content fingerprint (T62). */
private class OpenedViewerDocument(
    val opened: OpenedDocument,
    val pages: List<PdfPage>,
    val snapshot: ByteArray?,
    val fingerprint: String?
)

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

    // ---- Annotation layers (T68b) ------------------------------------------

    private val _annotationLayers = MutableStateFlow(AnnotationLayersState.SHOW_ALL)
    val annotationLayersState: StateFlow<AnnotationLayersState> =
        _annotationLayers.asStateFlow()

    private val _annotationFilterReport = MutableStateFlow<AnnotationFilterReport?>(null)
    val annotationFilterReport: StateFlow<AnnotationFilterReport?> =
        _annotationFilterReport.asStateFlow()

    private val _isLayersSheetOpen = MutableStateFlow(false)
    val isLayersSheetOpen: StateFlow<Boolean> = _isLayersSheetOpen.asStateFlow()

    private val annotationLayerStore by lazy { AnnotationLayerStore(getApplication()) }
    private var annotationReportJob: Job? = null

    fun openLayersSheet() {
        _isLayersSheetOpen.value = true
    }

    fun closeLayersSheet() {
        _isLayersSheetOpen.value = false
    }

    fun toggleLayerGroup(group: AnnotationLayerGroup) = updateLayers { it.toggleGroup(group) }

    fun toggleLayerColor(rgb: Int) = updateLayers { it.toggleColor(rgb) }

    fun soloLayerColor(group: AnnotationLayerGroup, rgb: Int) =
        updateLayers { it.soloColor(group, rgb) }

    fun showAllLayers() = updateLayers { it.showAll() }

    fun hideAllLayers() = updateLayers { it.hideAll() }

    /**
     * Applies and remembers one layer choice. Nothing is written to the PDF:
     * the filter only decides what a render paints, and a hidden annotation
     * still hides by its own /F flags (§12.5.3).
     */
    private fun updateLayers(change: (AnnotationLayersState) -> AnnotationLayersState) {
        val next = change(_annotationLayers.value)
        if (next == _annotationLayers.value) return
        _annotationLayers.value = next
        annotationLayerStore.save(lookFingerprint, next)
        // Every bitmap above a page render was painted with the old filter.
        presentCache.clear()
        linkPreviews.clear()
        reflowRegionCache.clear()
        _flowBitmaps.value = emptyMap()
        flowBitmapOrder.clear()
        invalidateRenders()
    }

    /**
     * Counts are document-wide, so they are computed once with the all-visible
     * filter; a filter that is active only changes which of those annotations
     * are painted, not what the document contains.
     */
    private fun loadAnnotationReport(document: PdfDocument) {
        annotationReportJob?.cancel()
        _annotationFilterReport.value = null
        annotationReportJob = textScope.launch {
            val report = runCatching {
                document.annotationFilterReport(AnnotationFilter.SHOW_ALL)
            }.getOrNull() ?: return@launch
            if (this@ViewerViewModel.document === document) {
                _annotationFilterReport.value = report
            }
        }
    }

    private val _pageBoxes = MutableStateFlow<List<PageBox>>(emptyList())
    val pageBoxes: StateFlow<List<PageBox>> = _pageBoxes.asStateFlow()

    /**
     * T57: the boxes the viewer actually draws. They equal [pageBoxes] until
     * "Fit content" is on; then each entry is the content box of its page.
     */
    private val _viewBoxes = MutableStateFlow<List<PageBox>>(emptyList())
    val viewBoxes: StateFlow<List<PageBox>> = _viewBoxes.asStateFlow()

    /** T57: the fit mode, the detected boxes and the manual margins. */
    private val _fit = MutableStateFlow(FitContentState())
    val fit: StateFlow<FitContentState> = _fit.asStateFlow()
    private var fitJob: Job? = null

    /** Bumped on every fit change so a slow analysis cannot win a race. */
    private var fitGeneration = 0

    // ---- Page theme (T32 content-aware dark mode, T32b true black) --------

    private val _pageTheme = MutableStateFlow(PageTheme.OFF)
    val pageTheme: StateFlow<PageTheme> = _pageTheme.asStateFlow()

    /** Bumped whenever the theme changes; page items key their render on it. */
    private val _themeGeneration = MutableStateFlow(0)
    val themeGeneration: StateFlow<Int> = _themeGeneration.asStateFlow()

    /** Whether the system is in dark mode ("follow system dark theme"). */
    private var systemDarkTheme: Boolean = true

    // ---- Page look (T62: filters and sliders, remembered per document) ----

    private val _look = MutableStateFlow(ColorTransform.NONE)
    val look: StateFlow<ColorTransform> = _look.asStateFlow()

    /** True when this document has a saved look of its own (not a default). */
    private val _lookFromDocument = MutableStateFlow(false)
    val lookFromDocument: StateFlow<Boolean> = _lookFromDocument.asStateFlow()

    private val _lookHint = MutableStateFlow<LookHint?>(null)
    val lookHint: StateFlow<LookHint?> = _lookHint.asStateFlow()
    private var lookHintJob: Job? = null

    private val lookStore by lazy { DocumentLookStore(getApplication()) }

    /**
     * The document's content fingerprint (T62): the same value the annotation
     * index uses, so a moved or re-shared file keeps its look while a different
     * file with the same name does not inherit it.
     */
    private var lookFingerprint: String? = null

    // ---- E-Ink display (T63) ---------------------------------------------

    private val _eink = MutableStateFlow(EinkDisplay())
    val eink: StateFlow<EinkDisplay> = _eink.asStateFlow()

    /** The per-document override, or null when the document inherits (T32b). */
    private val _documentThemeMode = MutableStateFlow<PageThemeMode?>(null)
    val documentThemeMode: StateFlow<PageThemeMode?> = _documentThemeMode.asStateFlow()

    /**
     * Recomputes the theme for the open document and drops every cached
     * bitmap when it changed. [systemDark] comes from the screen, so a system
     * theme switch while the app is open is honoured too.
     */
    fun refreshPageTheme(systemDark: Boolean = systemDarkTheme) {
        systemDarkTheme = systemDark
        val app = getApplication<Application>()
        val override = AppSettings.documentThemeMode(app, documentKey)
        // T62: the look is resolved here too, before the first frame: a saved
        // sepia book opens sepia, not white for a moment.
        val resolved = LookResolution.resolve(
            baseTheme = AppSettings.resolvedPageTheme(app, documentKey, systemDark),
            documentLook = lookStore.load(lookFingerprint),
            legacyMode = override,
            defaultLook = AppSettings.defaultColorTransform(app),
            followSystem = AppSettings.pageThemeFollowsSystem(app),
            systemDark = systemDark
        )
        _documentThemeMode.value = resolved.documentMode
        _lookFromDocument.value = resolved.fromDocument
        // T63: the E-Ink profile is resolved here as well, so an enabled
        // profile is already on the first frame. A document override (even an
        // explicit Off) wins over the device profile; the device switch wins
        // over both.
        val einkDevice = AppSettings.einkSettings(app)
        val einkResolved = EinkResolution.resolve(
            device = einkDevice,
            enabled = AppSettings.einkEnabled(app),
            documentProfile = lookStore.loadEink(lookFingerprint)
        )
        val changed = resolved.theme != _pageTheme.value ||
            resolved.transform != _look.value ||
            einkResolved != _eink.value
        _look.value = resolved.transform
        _pageTheme.value = resolved.theme
        _eink.value = einkResolved
        if (!changed) return
        _themeGeneration.value += 1
        presentCache.clear()
        linkPreviews.clear()
        invalidateRenders()
    }

    /**
     * Applies an E-Ink profile (T63). [forDocument] stores it against this
     * document's fingerprint; otherwise it changes the device profile for
     * every document that does not override it. Nothing is written to the PDF.
     */
    fun applyEink(settings: EinkSettings, forDocument: Boolean = false) {
        val app = getApplication<Application>()
        if (forDocument) {
            lookStore.saveEink(lookFingerprint, settings.profile)
        } else {
            AppSettings.setEinkSettings(app, settings.copy(profile = settings.profile))
        }
        refreshPageTheme()
    }

    /** The one-tap switch: on uses the device profile, off turns E-Ink off. */
    fun setEinkEnabled(enabled: Boolean) {
        AppSettings.setEinkEnabled(getApplication(), enabled)
        refreshPageTheme()
    }

    /** "Use this document's own profile" or return to the device profile. */
    fun setEinkFromDocument(settings: EinkSettings, fromDocument: Boolean) {
        val app = getApplication<Application>()
        if (fromDocument) {
            lookStore.saveEink(lookFingerprint, settings.profile)
        } else {
            lookStore.forgetEink(lookFingerprint)
        }
        if (!fromDocument) AppSettings.setEinkSettings(app, settings)
        refreshPageTheme()
    }

    /**
     * The T32b per-document override, kept as an API for the old chips: a mode
     * is a filter, and "follow settings" is "no look of this document's own".
     */
    fun setDocumentThemeMode(mode: PageThemeMode?) {
        if (mode == null) {
            resetLook()
        } else {
            AppSettings.setDocumentThemeMode(getApplication(), documentKey, mode)
            applyLook(_look.value.copy(filter = PageFilter.of(mode)))
        }
    }

    /**
     * Applies a look to the open document and remembers it (T62). Every change
     * is live: the visible page is re-rendered, and the file is never touched.
     */
    fun applyLook(transform: ColorTransform) {
        val look = transform.normalized()
        lookStore.save(lookFingerprint, look)
        _lookHint.value = null
        refreshPageTheme()
    }

    /** "Reset to default": forget this document's look and inherit again. */
    fun resetLook() {
        lookStore.forget(lookFingerprint)
        AppSettings.setDocumentThemeMode(getApplication(), documentKey, null)
        _lookHint.value = null
        refreshPageTheme()
    }

    /** "Use as default for new documents": the look becomes the global one. */
    fun useLookAsDefault() {
        AppSettings.setDefaultColorTransform(getApplication(), _look.value)
        showMessage("New documents will open with ${_look.value.filter.label}")
    }

    /** Takes the suggested filter for this page, and remembers the choice. */
    fun applyLookHint() {
        val hint = _lookHint.value ?: return
        _lookHint.value = null
        applyLook(_look.value.copy(filter = hint.filter))
    }

    /** "Not now": remembered per document so the page stays quiet. */
    fun dismissLookHint() {
        lookStore.setHintDismissed(lookFingerprint, true)
        _lookHint.value = null
    }

    /**
     * Offers a look for the first page (T62): sepia for a scan on aged paper,
     * inverted monochrome for a line drawing. It runs on a small render of the
     * page as authored, once per document, and only when no look is saved and
     * the reader has not dismissed it. It never applies anything itself.
     */
    private fun suggestLook() {
        val fingerprint = lookFingerprint ?: return
        if (lookStore.load(fingerprint) != null) return
        if (_look.value.active) return
        if (lookStore.hintDismissed(fingerprint)) return
        val page = pages.getOrNull(0) ?: return
        val box = viewBox(0) ?: return
        lookHintJob?.cancel()
        lookHintJob = renderScope.launch {
            val scale = PageGeometry.scaleForPixelWidth(box, LOOK_HINT_PIXEL_WIDTH)
                .coerceIn(0.05, 2.0)
            val rendered = runCatching {
                PageBitmapRenderer.render(
                    page = page,
                    box = box,
                    scale = scale,
                    maxPixels = 1_000_000,
                    fontDir = fontDir
                )
            }.getOrNull() ?: return@launch
            val bitmap = rendered.bitmap
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            val filter = LookSuggestion.suggest(pixels, width, height) ?: return@launch
            if (!isActive || _lookHint.value != null || _look.value.active) return@launch
            _lookHint.value = LookHint(filter, lookHintMessage(filter))
        }
    }

    private fun lookHintMessage(filter: PageFilter): String = when (filter) {
        PageFilter.SEPIA ->
            "This page looks like a scan on aged paper. Sepia may be easier to read."
        PageFilter.INVERTED_MONO ->
            "This page is a line drawing. Inverted monochrome may be easier to read."
        else -> "Look: ${filter.label}"
    }

    /** Bitmaps currently on screen; off-screen ones live only in the cache. */
    private val _bitmaps = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    val bitmaps: StateFlow<Map<Int, Bitmap>> = _bitmaps.asStateFlow()

    /** Render plan per on-screen page: user space → bitmap pixels (T11 overlays). */
    private val _plans = MutableStateFlow<Map<Int, PageRenderPlan>>(emptyMap())
    val plans: StateFlow<Map<Int, PageRenderPlan>> = _plans.asStateFlow()

    private val _search = MutableStateFlow(ViewerSearchState())
    val search: StateFlow<ViewerSearchState> = _search.asStateFlow()

    /** T65: the document-scope regex run and its rows (one scope per sheet). */
    private val _regex = MutableStateFlow(RegexUiState(scope = RegexScope.DOCUMENT))
    val regex: StateFlow<RegexUiState> = _regex.asStateFlow()

    private val _selection = MutableStateFlow<ViewerSelection?>(null)
    val selection: StateFlow<ViewerSelection?> = _selection.asStateFlow()

    /** Annotation/form edit mode (T12). */
    private val _edit = MutableStateFlow(ViewerEditState())
    val edit: StateFlow<ViewerEditState> = _edit.asStateFlow()

    /** Stylus ink tools and their editable strokes (T54). */
    private val _ink = MutableStateFlow(InkUiState())
    val ink: StateFlow<InkUiState> = _ink.asStateFlow()

    private val inkUndo = ArrayDeque<List<InkStroke>>()
    private val inkRedo = ArrayDeque<List<InkStroke>>()
    private var inkLoaded = false
    private var inkNextId = 1L
    private var inkActionBase: List<InkStroke>? = null
    private var inkMoveBase: List<InkStroke>? = null
    private var inkMoveAnchor: InkSample? = null
    private var inkGesturePage: Int? = null
    private var inkEraserOverride = false

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

    // ------------------------------------------- saving in place (T55)

    private val _save = MutableStateFlow(ViewerSaveState())
    val save: StateFlow<ViewerSaveState> = _save.asStateFlow()
    private var openedFingerprint: DocumentFingerprint? = null

    /**
     * The bytes the open document was read from, for a writable source (T55).
     * An edit is built on these, so a file that changed elsewhere can be
     * overwritten with exactly what the user saw. Bounded: a very large file
     * is re-read at save time instead.
     */
    private var openedBytes: ByteArray? = null
    private val maxSnapshotBytes = 32L * 1024L * 1024L
    private var autosaveJob: Job? = null
    private var saveInFlight = false
    private var pendingConflictSave: ViewerExport? = null
    private var pendingSignatureSave: ViewerExport? = null
    private var documentOriginKey: String = ""
    private var copyDestination: String? = null
    private val autosaveDelayMs = 1_800L

    /**
     * The stable identity of the open document (T38b): the URI it was opened
     * from when the caller knows it, otherwise the path the viewer reads. Cache
     * copies are named after the file and change between opens, so anything
     * remembered per document is keyed on this instead.
     */
    val documentKey: String get() = documentOriginKey.ifBlank { currentPath }

    // ------------------------------------------------- reading session (T38b)

    private val readingStore by lazy { ReadingStateStore(getApplication()) }
    private var explicitStartPage: Int? = null
    private var restoreGeneration = 0

    /**
     * True after "Forget position" for the current document: the session stops
     * writing so the forgotten spot does not come back when the reader leaves.
     */
    private var readingSuppressed = false

    private val _readingOrientation = MutableStateFlow(ReadingOrientation.AUTO)
    val readingOrientation: StateFlow<ReadingOrientation> = _readingOrientation.asStateFlow()

    private val saveCoordinator by lazy {
        SaveCoordinator(File(getApplication<Application>().cacheDir, "save-temp"))
    }

    // --------------------------------------------------------- signing (UI-3)

    private val _signing = MutableStateFlow(ViewerSigningState())
    val signing: StateFlow<ViewerSigningState> = _signing.asStateFlow()

    // ------------------------------------------------------------- OCR (UI-2)

    private val _ocr = MutableStateFlow(ViewerOcrState())
    val ocr: StateFlow<ViewerOcrState> = _ocr.asStateFlow()
    private var ocrProvider: MlKitOcrProvider? = null
    private var ocrResult: SearchableResult? = null
    private var ocrJob: Job? = null

    // --------------------------------------------------------- redaction (UI-4)

    private val _redaction = MutableStateFlow(RedactionUiState())
    val redaction: StateFlow<RedactionUiState> = _redaction.asStateFlow()
    private var nextRedactionMarkId = 1L

    private val _sanitize = MutableStateFlow(SanitizeUiState())
    val sanitize: StateFlow<SanitizeUiState> = _sanitize.asStateFlow()

    /** Display name preserved across a save/reload of the edited cache copy. */
    private var displayNameOverride: String? = null

    private var document: PdfDocument? = null
    private var pages: List<PdfPage> = emptyList()
    private var fieldsLoaded = false

    /** Path or content URI of the document, kept so a password retry can reopen it. */
    private var currentPath: String = ""
    private var currentDisplayName: String = ""
    /** Cache copy for content documents, made only when a tool needs a File. */
    private var localFilePath: String? = null

    private val cache = LruCache<Int, CachedPage>(bitmapBudgetBytes()) { it.bitmap.allocationByteCount.toLong() }

    /**
     * T53: pages rendered for the presentation. It is separate from the
     * viewer's cache so scrolling the hidden page list cannot evict the slide
     * being shown, and it is bounded so a long deck cannot exhaust memory.
     */
    private val presentCache = LruCache<Int, CachedPage>(48L * 1024 * 1024) {
        it.bitmap.allocationByteCount.toLong()
    }

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

    /**
     * T65: a separate job from [searchJob], so a literal search and a regex run
     * cannot cancel each other. [regexGeneration] invalidates the result of a
     * run the reader has already replaced or cleared, and [regexCancelRequested]
     * lets a cancelled run return what it found instead of dropping it.
     */
    private var regexJob: Job? = null
    private var regexGeneration: Int = 0
    @Volatile private var regexCancelRequested: Boolean = false

    /** The page the reader is looking at, for the one-page tester (T65). */
    @Volatile private var visiblePage: Int = 0

    /** First word of the current selection, to extend from (T11). */
    private var selectionAnchor: Pair<Int, IntRange>? = null

    /** Embedded fonts are materialised here so android.graphics can load them. */
    private val fontDir: File by lazy { File(getApplication<Application>().cacheDir, "embedded-fonts") }

    init {
        viewModelScope.launch {
            _edit.collect { edit ->
                // A conflict waits for the user's choice: retrying would hit the
                // same newer file again and hide the error behind "Unsaved".
                if (edit.hasPendingChanges && !edit.saving && !_save.value.conflict) {
                    _save.update {
                        it.copy(
                            status = ViewerSaveStatus.UNSAVED,
                            canSaveInPlace = _state.value.canSaveInPlace,
                            autosaveMode = AppSettings.autosaveMode(getApplication())
                        )
                    }
                    scheduleAutosave()
                }
            }
        }
    }

    /**
     * Opens [path] (a local file or a `content://` URI). [origin] is the source
     * the reader picked; it becomes the document's identity (T38b) so the
     * reading session survives the cache copy the viewer reads. [startPageIndex]
     * asks for one page explicitly (a search hit or a hub row) and wins over the
     * saved position.
     */
    fun load(
        path: String,
        displayName: String? = null,
        origin: String? = null,
        startPageIndex: Int = 0
    ) {
        displayNameOverride = displayName
        currentPath = path
        localFilePath = if (DocumentAccess.isContentLocation(path)) null else path
        currentDisplayName = displayName
            ?: if (DocumentAccess.isContentLocation(path)) {
                "PDF document"
            } else {
                File(path).name
            }
        signaturesLoaded = false
        _signatures.value = ViewerSignaturesState()
        openedFingerprint = null
        openedBytes = null
        lookFingerprint = null
        lookHintJob?.cancel()
        _lookHint.value = null
        pendingConflictSave = null
        pendingSignatureSave = null
        documentOriginKey = origin?.takeIf { it.isNotBlank() } ?: path
        explicitStartPage = startPageIndex.takeIf { it > 0 }
        // A new open is a new session: a "Forget position" from the last one
        // must not silence this one.
        readingSuppressed = false
        copyDestination = null
        _save.value = ViewerSaveState(
            canSaveInPlace = false,
            autosaveMode = AppSettings.autosaveMode(getApplication())
        )
        openWith("")
    }

    /** The password the document was opened with, kept for the reading (T64). */
    private var currentPassword: String = ""

    /**
     * T64: hands this document to the reading service.
     *
     * The service - not the viewer - owns the reading, so this only starts it
     * (or asks an already running one to read this document) and the viewer
     * observes the hub. A start needs a foreground service, because the reading
     * carries on with the screen off.
     */
    fun startReadAloud(fromPage: Int, fromParagraph: Int = 0) {
        val location = state.value.sourceLocation
        if (location.isBlank()) {
            showMessage("This document has no readable location")
            return
        }
        val name = state.value.displayName ?: "Document"
        val position = SpeechPosition(
            pageIndex = fromPage.coerceIn(0, (state.value.pageCount - 1).coerceAtLeast(0)),
            paragraphIndex = fromParagraph.coerceAtLeast(0)
        )
        val running = ReadAloudHub.commands
        if (running != null) {
            running.start(location, name, currentPassword, position)
        } else {
            getApplication<Application>().startForegroundService(
                ReadAloudService.startIntent(
                    getApplication(),
                    location,
                    name,
                    currentPassword,
                    position
                )
            )
        }
    }

    /** Retries opening the current document with [password] (T13). */
    fun unlock(password: String) = openWith(password)

    /** Gives up on the password prompt. */
    fun cancelUnlock() = _state.update {
        it.copy(requiresPassword = false, passwordError = null, error = "Password required")
    }

    private fun openWith(password: String) {
        // T64: the service opens the document itself, so it needs the password
        // the reader typed. An empty one is a document that has no password.
        currentPassword = password
        val path = currentPath
        if (path.isBlank()) {
            _state.value = ViewerUiState(isLoading = false, error = "No file selected")
            return
        }
        clearTextState()
        closeCurrentDocument()
        _state.value = ViewerUiState(isLoading = true, displayName = currentDisplayName)
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) {
                DocumentAccess.open(getApplication(), path, password)
                    .map { opened ->
                        val pages = opened.document.pages()
                        // T55: a writable source is edited from the bytes it was
                        // opened from, so "Overwrite anyway" after an external
                        // change writes the user's version, not a mix of both.
                        val snapshot = if (
                            opened.location.canWriteInPlace &&
                            opened.location.sizeBytes in 1..maxSnapshotBytes
                        ) {
                            DocumentAccess.readBytes(getApplication(), opened.location.value)
                                .getOrNull()
                        } else {
                            null
                        }
                        // T62: the look is keyed by the file's content, which
                        // needs one bounded read of its head and tail.
                        val fingerprint = DocumentFingerprints.of(getApplication(), opened.location.value)
                        OpenedViewerDocument(opened, pages, snapshot, fingerprint)
                    }
            }
            opened.fold(
                onSuccess = { openedDocument ->
                    val opened = openedDocument.opened
                    val list = openedDocument.pages
                    val snapshot = openedDocument.snapshot
                    lookFingerprint = openedDocument.fingerprint
                    val location = opened.location
                    document = opened.document
                    pages = list
                    // T68b: the layer choice is restored before "loaded", so
                    // the first frame already paints with it.
                    _annotationLayers.value = annotationLayerStore.load(lookFingerprint)
                    _isLayersSheetOpen.value = false
                    loadAnnotationReport(opened.document)
                    currentPath = location.value
                    currentDisplayName = displayNameOverride ?: location.displayName
                    localFilePath = if (location.isContent) null else location.value
                    _pageBoxes.value = list.map { PageGeometry.boxOf(it) }
                    // Until a fit is restored or chosen, every page is shown as
                    // it is (T57).
                    _viewBoxes.value = _pageBoxes.value
                    openedFingerprint = DocumentFingerprint(location.sizeBytes, location.lastModified)
                    openedBytes = snapshot
                    // T62: resolve the look now, so the first frame is already
                    // painted with the document's own filter (T38b's rule).
                    refreshPageTheme()
                    // T38b: the saved session is resolved here, before the
                    // state says "loaded", so the first frame the screen builds
                    // is already the reader's page at the reader's zoom and
                    // mode - there is no frame at page 1 to scroll away from.
                    val restore = ReadingRestore.resolve(
                        state = readingStore.load(documentKey),
                        pageCount = list.size,
                        alwaysStartAtPageOne = AppSettings.alwaysStartAtPageOne(getApplication()),
                        explicitPage = explicitStartPage
                    )
                    restoreGeneration += 1
                    _readingOrientation.value = restore.orientation
                    _state.value = ViewerUiState(
                        isLoading = false,
                        displayName = currentDisplayName,
                        pageCount = list.size,
                        sourceLocation = location.value,
                        canSaveInPlace = location.canWriteInPlace,
                        startPage = restore.pageIndex,
                        startFraction = restore.pageFraction,
                        startZoom = restore.zoom,
                        startMode = restore.mode,
                        startReflow = if (restore.mode == ReadingMode.REFLOW) {
                            ReflowPosition(restore.pageIndex, restore.pageFraction)
                        } else {
                            null
                        },
                        startFlow = if (restore.mode == ReadingMode.COLUMN_FLOW) {
                            FlowPosition(restore.pageIndex, restore.flowOrder)
                        } else {
                            null
                        },
                        orientation = restore.orientation,
                        startToolId = restore.lastToolId,
                        startSpeechPage = restore.speechPage,
                        startSpeechParagraph = restore.speechParagraph,
                        hasSavedSpeech = restore.speechPage > 0 || restore.speechParagraph > 0,
                        restoreToken = restoreGeneration
                    )
                    _save.update {
                        it.copy(
                            canSaveInPlace = location.canWriteInPlace,
                            autosaveMode = AppSettings.autosaveMode(getApplication())
                        )
                    }
                    loadComments()
                    buildLinkIndex()
                    loadSignatures(force = true)
                    loadVersions()
                    indexForHub()
                    restoreFit(restore)
                    restore.note?.let { showMessage(it) }
                    // T62: offer Sepia or Inverted monochrome when the page
                    // looks like a scan or a drawing; never applied by itself.
                    suggestLook()
                },
                onFailure = { error ->
                    if (error is PdfPasswordException) {
                        _state.value = ViewerUiState(
                            isLoading = false,
                            displayName = currentDisplayName,
                            requiresPassword = true,
                            passwordError = if (password.isEmpty()) null else "Incorrect password",
                            sourceLocation = path
                        )
                    } else {
                        _state.value = ViewerUiState(
                            isLoading = false,
                            displayName = currentDisplayName,
                            error = error.message ?: "Cannot open this document",
                            sourceLocation = path
                        )
                    }
                }
            )
        }
    }

    // ---------------------------------------------------- fit content (T57)

    /**
     * Applies the framing of the restored session (T38b): "fit content" is the
     * T57 document fit, every other mode shows the page as it is. A saved
     * manual fit is kept, and a document with no saved session keeps the
     * framing it was last given, so this cannot throw a reader's own box away.
     */
    private fun restoreFit(restore: RestoredReading) {
        fitGeneration++
        fitJob?.cancel()
        val app = getApplication<Application>()
        val saved = AppSettings.fitContentMode(app, documentKey)
        val target = if (!restore.restoredFromSession) {
            saved
        } else {
            when (restore.mode) {
                ReadingMode.FIT_CONTENT ->
                    if (saved == FitContentMode.MANUAL) FitContentMode.MANUAL else FitContentMode.DOCUMENT
                ReadingMode.PAGE, ReadingMode.REFLOW, ReadingMode.COLUMN_FLOW ->
                    FitContentMode.OFF
            }
        }
        if (target != saved) AppSettings.setFitContentMode(app, documentKey, target)
        _fit.value = FitContentState(
            mode = target,
            margins = AppSettings.fitMargins(app, documentKey),
            ignoreHeaderFooter = AppSettings.fitIgnoreHeaderFooter(app)
        )
        when (target) {
            FitContentMode.OFF -> _viewBoxes.value = _pageBoxes.value
            FitContentMode.MANUAL -> applyViewBoxes()
            FitContentMode.DOCUMENT, FitContentMode.PAGE -> computeFit(target)
        }
    }

    // ------------------------------------------------- reading session (T38b)

    /**
     * Persists where the reader is, how they are looking at the document and
     * which tool they last used. The screen calls this debounced while reading
     * and again when it stops, so a killed process loses at most the last
     * second of scrolling.
     */
    fun saveReadingState(
        pageIndex: Int,
        fraction: Float,
        zoom: Float,
        mode: ReadingMode,
        toolId: String?,
        flowOrder: Int = 0
    ) {
        // "Forget position" means the *next* open starts at page 1, so this
        // session stops writing. Saving again on the way out would undo it.
        if (readingSuppressed) return
        val current = _state.value
        if (current.isLoading || current.pageCount <= 0) return
        val key = documentKey
        if (key.isBlank()) return
        val page = pageIndex.coerceIn(0, current.pageCount - 1)
        // The speech position is written by the reading, not this scroll save;
        // carrying it over keeps a running read-aloud offer from disappearing
        // every time the reader turns a page.
        val previous = readingStore.load(key)
        readingStore.save(
            key,
            currentDisplayName,
            ReadingState(
                pageIndex = page,
                pageFraction = fraction.coerceIn(0f, 1f),
                pageCount = current.pageCount,
                zoom = zoom.coerceIn(ReadingState.MIN_ZOOM, ReadingState.MAX_ZOOM),
                mode = mode,
                orientation = _readingOrientation.value,
                lastToolId = toolId,
                flowOrder = flowOrder.coerceAtLeast(0),
                speechPage = previous?.speechPage ?: 0,
                speechParagraph = previous?.speechParagraph ?: 0,
                updatedAt = System.currentTimeMillis()
            )
        )
        // The Recent row says "Page 48 of 210"; a file the reader picked is in
        // that list under the same URI, and setProgress ignores anything else.
        runCatching { RecentStore(getApplication()).setProgress(key, page, current.pageCount) }
    }

    /**
     * T34: remembers where read aloud was, so a killed process loses at most
     * the paragraph being spoken. The reading itself is not restarted on open;
     * the viewer offers "Continue reading aloud" instead.
     */
    fun saveSpeechPosition(pageIndex: Int, paragraphIndex: Int) {
        if (readingSuppressed) return
        val current = _state.value
        if (current.isLoading || current.pageCount <= 0) return
        val key = documentKey
        if (key.isBlank()) return
        val page = pageIndex.coerceIn(0, current.pageCount - 1)
        val paragraph = paragraphIndex.coerceAtLeast(0)
        val saved = readingStore.load(key)
        val next = saved?.copy(
            speechPage = page,
            speechParagraph = paragraph,
            pageCount = current.pageCount,
            updatedAt = System.currentTimeMillis()
        ) ?: ReadingState(
            pageIndex = current.startPage.coerceIn(0, current.pageCount - 1),
            pageCount = current.pageCount,
            zoom = current.startZoom,
            mode = current.startMode,
            orientation = _readingOrientation.value,
            speechPage = page,
            speechParagraph = paragraph,
            updatedAt = System.currentTimeMillis()
        )
        readingStore.save(key, currentDisplayName, next)
        _state.update {
            it.copy(
                startSpeechPage = page,
                startSpeechParagraph = paragraph,
                hasSavedSpeech = page > 0 || paragraph > 0
            )
        }
    }

    /** The orientation the reader chose for this document. */
    fun setReadingOrientation(orientation: ReadingOrientation) {
        if (_readingOrientation.value == orientation) return
        _readingOrientation.value = orientation
        _state.update { it.copy(orientation = orientation) }
    }

    /**
     * "Forget position" for this document: the next open starts at page 1 with
     * the default zoom. The reader is not moved now, so the button cannot throw
     * them out of the page they are reading.
     */
    fun forgetReadingState() {
        readingStore.forget(documentKey)
        readingSuppressed = true
        showMessage("Position forgotten. This document opens at page 1 next time.")
    }

    /** The reading spot reflow currently reports, for the session save. */
    fun reflowPositionNow(): ReflowPosition = reflowPosition

    /** Switches the viewer between the whole page, a fitted page and manual. */
    fun setFitMode(mode: FitContentMode) {
        if (_fit.value.mode == mode && mode != FitContentMode.DOCUMENT && mode != FitContentMode.PAGE) {
            return
        }
        AppSettings.setFitContentMode(getApplication(), documentKey, mode)
        _fit.update { it.copy(mode = mode, error = null) }
        when (mode) {
            FitContentMode.OFF -> {
                fitGeneration++
                fitJob?.cancel()
                _fit.value = _fit.value.copy(
                    loading = false,
                    boxes = emptyMap(),
                    oddSize = null,
                    evenSize = null,
                    detectedPages = 0,
                    fallbackPages = emptyList(),
                    scannedPages = emptyList(),
                    warnings = emptyList()
                )
                applyViewBoxes()
            }
            FitContentMode.MANUAL -> {
                fitGeneration++
                fitJob?.cancel()
                _fit.value = _fit.value.copy(loading = false)
                applyViewBoxes()
            }
            FitContentMode.DOCUMENT, FitContentMode.PAGE -> computeFit(mode)
        }
    }

    /** Manual margins, shared by every page (the sheet's corner handles). */
    fun setFitMargins(margins: CropMargins) {
        if (margins == _fit.value.margins) return
        _fit.update { it.copy(margins = margins, mode = FitContentMode.MANUAL) }
        AppSettings.setFitContentMode(getApplication(), documentKey, FitContentMode.MANUAL)
        AppSettings.setFitMargins(getApplication(), documentKey, margins)
        applyViewBoxes()
    }

    /** Running heads and page numbers in or out of the detected box. */
    fun setFitIgnoreHeaderFooter(ignore: Boolean) {
        if (_fit.value.ignoreHeaderFooter == ignore) return
        AppSettings.setFitIgnoreHeaderFooter(getApplication(), ignore)
        _fit.update { it.copy(ignoreHeaderFooter = ignore) }
        val mode = _fit.value.mode
        if (mode == FitContentMode.DOCUMENT || mode == FitContentMode.PAGE) computeFit(mode)
    }

    /** The boxes "Make this permanent" gives the T46 crop screen. */
    fun fitCropBoxes(): Map<Int, PdfRect> = fitCropBoxes(_pageBoxes.value, _fit.value)

    /**
     * Measures the document on the shared document dispatcher: the display
     * list build and a scanned page's low-resolution render both touch the
     * open [PdfDocument], so they must not run beside a page render.
     */
    private fun computeFit(mode: FitContentMode) {
        val doc = document ?: return
        if (_pageBoxes.value.isEmpty()) return
        val generation = ++fitGeneration
        fitJob?.cancel()
        _fit.value = _fit.value.copy(
            mode = mode,
            loading = true,
            error = null,
            boxes = emptyMap(),
            oddSize = null,
            evenSize = null,
            detectedPages = 0,
            fallbackPages = emptyList(),
            scannedPages = emptyList(),
            warnings = emptyList()
        )
        val ignoreHeads = _fit.value.ignoreHeaderFooter
        fitJob = viewModelScope.launch {
            val measured = withContext(documentDispatcher) {
                ContentFit.compute(
                    document = doc,
                    options = ContentFitOptions(
                        ignoreHeaderFooter = ignoreHeads,
                        sharedSize = mode == FitContentMode.DOCUMENT
                    ),
                    decoder = AndroidImageDecoder,
                    rasterizer = AndroidPageRasterizer
                )
            }
            if (generation != fitGeneration) return@launch
            measured.fold(
                onSuccess = { result ->
                    // Publish the boxes and switch the drawn page in the same
                    // main-thread step, then clear "loading": a watcher that
                    // sees loading=false must already see the fitted page.
                    _fit.value = _fit.value.copy(
                        boxes = result.boxes,
                        oddSize = result.oddSize,
                        evenSize = result.evenSize,
                        detectedPages = result.boxes.size,
                        fallbackPages = result.fallbackPages,
                        scannedPages = result.scannedPages,
                        warnings = result.warnings
                    )
                    applyViewBoxes()
                    _fit.value = _fit.value.copy(loading = false)
                },
                onFailure = { error ->
                    _fit.value = _fit.value.copy(
                        mode = FitContentMode.OFF,
                        loading = false,
                        error = error.message ?: "The content could not be measured"
                    )
                    AppSettings.setFitContentMode(
                        getApplication(),
                        documentKey,
                        FitContentMode.OFF
                    )
                    applyViewBoxes()
                }
            )
        }
    }

    /** Publishes the fitted boxes and drops renders made with the old ones. */
    private fun applyViewBoxes() {
        val next = fitViewBoxes(_pageBoxes.value, _fit.value)
        if (sameBoxes(next, _viewBoxes.value)) return
        _viewBoxes.value = next
        invalidateRenders()
    }

    /** The cache holds bitmaps of one box only, so a change clears it. */
    private fun invalidateRenders() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        requestedWidths.clear()
        cache.clear()
        _bitmaps.value = emptyMap()
        _plans.value = emptyMap()
    }

    /** The drawn box of [index]: the fitted one when a fit is active. */
    private fun viewBox(index: Int): PageBox? =
        _viewBoxes.value.getOrNull(index) ?: _pageBoxes.value.getOrNull(index)

    private fun sameBoxes(a: List<PageBox>, b: List<PageBox>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (x.left != y.left || x.bottom != y.bottom || x.right != y.right ||
                x.top != y.top || x.rotate != y.rotate
            ) {
                return false
            }
        }
        return true
    }

    /**
     * One whole page for the manual sheet, so its handles start at the real
     * page edges instead of the current fitted box.
     */
    suspend fun renderFitPreview(pageIndex: Int, targetPixelWidth: Int): RenderedPage? =
        withContext(documentDispatcher) {
            val page = pages.getOrNull(pageIndex) ?: return@withContext null
            val box = _pageBoxes.value.getOrNull(pageIndex) ?: return@withContext null
            val scale = PageGeometry.scaleForPixelWidth(box, targetPixelWidth.coerceIn(200, 1200))
                .coerceIn(0.05, 3.0)
            runCatching {
                PageBitmapRenderer.render(
                    page = page,
                    box = box,
                    scale = scale,
                    maxPixels = 4_000_000,
                    fontDir = fontDir,
                    theme = _pageTheme.value,
                    transform = _look.value,
                    eink = _eink.value.settings
                )
            }.getOrNull()
        }

    // ------------------------------------------------------- reflow (T33)

    private val _reflow = MutableStateFlow(ReflowUiState())
    val reflow: StateFlow<ReflowUiState> = _reflow.asStateFlow()

    /** Picture and table regions rendered for the reflow overlay. */
    private val _reflowRegions = MutableStateFlow<Map<String, ReflowRegion>>(emptyMap())
    val reflowRegions: StateFlow<Map<String, ReflowRegion>> = _reflowRegions.asStateFlow()

    /** Oldest-first page extraction order, for the memory cap. */
    private val reflowOrder = ArrayDeque<Int>()
    private val reflowLoading = HashSet<Int>()
    private val reflowRegionsLoading = HashSet<String>()
    private var reflowPosition: ReflowPosition = ReflowPosition.START

    private val reflowRegionCache = LruCache<String, ReflowRegion>(16L * 1024 * 1024) {
        it.bitmap.allocationByteCount.toLong()
    }

    /** Opens reflow at [position]; the overlay then asks for its pages. */
    fun openReflow(position: ReflowPosition) {
        reflowPosition = ReflowPosition(position.page.coerceAtLeast(0), position.fraction.coerceIn(0f, 1f))
        reflowOrder.clear()
        reflowLoading.clear()
        _reflow.value = ReflowUiState(
            active = true,
            settings = AppSettings.reflowSettings(getApplication()),
            entry = reflowPosition,
            hint = false,
            hintDismissed = _reflow.value.hintDismissed
        )
        requestReflowPage(reflowPosition.page)
    }

    /** Leaves reflow and reports the reading spot the page view should show. */
    fun closeReflow(): ReflowPosition {
        _reflow.update { it.copy(active = false, hint = false, loading = emptySet()) }
        reflowLoading.clear()
        return reflowPosition
    }

    /** The overlay reports the page under the viewport and how far into it. */
    fun updateReflowPosition(page: Int, fraction: Float) {
        reflowPosition = ReflowPosition(page.coerceAtLeast(0), fraction.coerceIn(0f, 1f))
    }

    /**
     * Applies a settings change. Font, spacing, alignment and margins only
     * re-lay the same blocks out; the two "hide" switches change what a page
     * contains, so the extracted pages are dropped and read again.
     */
    fun setReflowSettings(settings: ReflowSettings) {
        val normalised = settings.normalised()
        val previous = _reflow.value.settings
        AppSettings.setReflowSettings(getApplication(), normalised)
        val reextract = previous.hidePageNumbers != normalised.hidePageNumbers ||
            previous.hideFootnotes != normalised.hideFootnotes
        if (!reextract) {
            _reflow.update { it.copy(settings = normalised) }
            return
        }
        reflowOrder.clear()
        reflowLoading.clear()
        _reflow.update {
            it.copy(settings = normalised, pages = emptyMap(), loading = emptySet())
        }
        requestReflowPage(reflowPosition.page)
    }

    /**
     * Extracts one page if it is not in memory yet. Extraction runs on the
     * shared document dispatcher, so it never races a page render.
     */
    fun requestReflowPage(index: Int) {
        val state = _reflow.value
        if (!state.active) return
        if (index < 0 || index >= pages.size) return
        if (state.pages.containsKey(index) || index in reflowLoading) return
        reflowLoading += index
        _reflow.update { it.copy(loading = it.loading + index) }
        textScope.launch {
            val page = pages.getOrNull(index)
            val options = _reflow.value.settings.options()
            val extracted = if (page == null) {
                null
            } else {
                ReflowExtractor.extract(page, options, AndroidImageDecoder).getOrNull()
            }
            reflowLoading -= index
            if (!isActive) return@launch
            if (extracted == null) {
                _reflow.update { it.copy(loading = it.loading - index) }
                return@launch
            }
            _reflow.update { current ->
                if (!current.active) {
                    current.copy(loading = current.loading - index)
                } else {
                    current.copy(
                        pages = current.pages + (index to extracted),
                        loading = current.loading - index
                    )
                }
            }
            rememberReflowPage(index)
        }
    }

    /**
     * Keeps the pages near the reader and forgets the far ones. Text only, but
     * a 500-page book would still hold every page's paragraphs at once.
     */
    private fun rememberReflowPage(centre: Int) {
        reflowOrder.remove(centre)
        reflowOrder.addLast(centre)
        val current = _reflow.value
        if (current.pages.size <= REFLOW_PAGE_CACHE) return
        val keep = HashSet<Int>()
        for (offset in -2..2) keep += centre + offset
        val removed = ArrayList<Int>()
        val iterator = reflowOrder.iterator()
        var size = current.pages.size
        while (iterator.hasNext() && size > REFLOW_PAGE_CACHE) {
            val candidate = iterator.next()
            if (candidate in keep) continue
            iterator.remove()
            if (!current.pages.containsKey(candidate)) continue
            removed += candidate
            size--
        }
        if (removed.isEmpty()) return
        _reflow.update { it.copy(pages = it.pages - removed.toSet()) }
    }

    /**
     * Renders the page region behind a picture or a table, at [pixelWidth]
     * pixels wide. The region is drawn by the normal page renderer, so it looks
     * exactly like the page does, theme included.
     */
    fun requestReflowRegion(pageIndex: Int, blockIndex: Int, pixelWidth: Int) {
        val state = _reflow.value
        if (!state.active || pixelWidth <= 0) return
        val page = state.pages[pageIndex] ?: return
        val block = page.blocks.getOrNull(blockIndex) ?: return
        if (block.isText) return
        val key = reflowRegionKey(pageIndex, blockIndex, pixelWidth)
        if (_reflowRegions.value.containsKey(key) || key in reflowRegionsLoading) return
        reflowRegionsLoading += key
        textScope.launch {
            val source = pages.getOrNull(pageIndex)
            val rotate = _pageBoxes.value.getOrNull(pageIndex)?.rotate ?: 0
            val rendered = if (source == null) {
                null
            } else {
                val box = reflowRegionBox(block.bounds, rotate)
                val scale = reflowRegionScale(block.bounds, rotate, pixelWidth)
                runCatching {
                    PageBitmapRenderer.render(
                        page = source,
                        box = box,
                        scale = scale,
                        maxPixels = 4_000_000,
                        decoder = AndroidImageDecoder,
                        fontDir = fontDir,
                        theme = _pageTheme.value,
                        transform = _look.value,
                        eink = _eink.value.settings
                    )
                }.getOrNull()
            }
            reflowRegionsLoading -= key
            if (!isActive || rendered == null) return@launch
            val region = ReflowRegion(rendered.bitmap, rendered.plan.pixelWidth)
            reflowRegionCache.put(key, region)
            _reflowRegions.update { it + (key to region) }
        }
    }

    /**
     * OCRs one scanned page and reflows it again from the searchable bytes. The
     * result is kept as [ocrResult], so the usual "save searchable copy" flow
     * still works and the original file is untouched.
     */
    fun runReflowOcr(pageIndex: Int, language: String) {
        val doc = document ?: return
        if (_reflow.value.ocrBusy) return
        if (pageIndex < 0 || pageIndex >= pages.size) return
        val provider = MlKitOcrProvider()
        ocrProvider = provider
        _reflow.update { it.copy(ocrBusy = true, ocrPage = pageIndex, ocrMessage = null) }
        _ocr.value = ViewerOcrState(running = true, requested = 1, language = language)
        ocrJob = textScope.launch {
            val result = runCatching {
                PdfOcr.makeSearchable(doc, provider, language, listOf(pageIndex)).getOrThrow()
            }
            provider.close()
            if (!isActive) return@launch
            val searchable = result.getOrNull()
            if (searchable == null) {
                val error = result.exceptionOrNull()
                finishReflowOcr(
                    pageIndex,
                    "OCR failed: ${error?.message ?: "unknown error"}"
                )
                return@launch
            }
            if (searchable.pagesAdded == 0) {
                finishReflowOcr(pageIndex, ocrNoTextSummary(searchable))
                return@launch
            }
            ocrResult = searchable
            val reflowed = withContext(documentDispatcher) {
                val reopened = PdfDocument.open(ByteArraySource(searchable.bytes)).getOrNull()
                val extracted = reopened?.let { document ->
                    document.pages().getOrNull(pageIndex)?.let { page ->
                        ReflowExtractor.extract(
                            page,
                            _reflow.value.settings.options(),
                            AndroidImageDecoder
                        ).getOrNull()
                    }
                }
                runCatching { reopened?.close() }
                extracted
            }
            if (reflowed == null) {
                finishReflowOcr(
                    pageIndex,
                    "OCR finished, but the page could not be read again."
                )
                return@launch
            }
            _reflow.update {
                it.copy(
                    ocrBusy = false,
                    ocrPage = null,
                    ocrMessage = "This page now reflows as text.",
                    pages = it.pages + (pageIndex to reflowed)
                )
            }
            _ocr.value = ViewerOcrState(
                running = false,
                requested = 1,
                language = language,
                finished = true,
                hasResult = true,
                summary = "Added ${searchable.wordsAdded} words to this page."
            )
        }
    }

    private fun finishReflowOcr(pageIndex: Int, message: String) {
        _reflow.update {
            it.copy(ocrBusy = false, ocrPage = null, ocrMessage = message)
        }
        _ocr.value = ViewerOcrState(
            running = false,
            requested = 1,
            finished = true,
            summary = message
        )
    }

    /**
     * Shows "Text is small. Try Reflow" when the visible page's body text is
     * under 12 sp at the current zoom (T33b). It is only ever a suggestion and
     * only in page view; the app never switches mode by itself.
     */
    fun checkReflowHint(pageIndex: Int, scale: Double) {
        val state = _reflow.value
        if (state.active || state.hint || state.hintDismissed) return
        if (AppSettings.reflowHintDismissed(getApplication(), documentKey)) return
        textScope.launch {
            val layout = layoutFor(pageIndex) ?: return@launch
            val density = getApplication<Application>().resources.displayMetrics.density
            if (!isTextSmall(layout, scale, density)) return@launch
            if (_reflow.value.active || _reflow.value.hint || _reflow.value.hintDismissed) return@launch
            _reflow.update { it.copy(hint = true) }
        }
    }

    /** "Not now": remembered per document, so the page stays quiet. */
    fun dismissReflowHint() {
        AppSettings.setReflowHintDismissed(getApplication(), documentKey, true)
        _reflow.update { it.copy(hint = false, hintDismissed = true) }
    }

    /** Dismisses the OCR result line above the reflow text. */
    fun clearReflowMessage() {
        _reflow.update { it.copy(ocrMessage = null) }
    }

    /** Hides the suggestion without remembering it (the reader took it). */
    fun clearReflowHint() {
        _reflow.update { it.copy(hint = false) }
    }

    private fun resetReflowState() {
        reflowOrder.clear()
        reflowLoading.clear()
        reflowRegionsLoading.clear()
        reflowRegionCache.clear()
        reflowPosition = ReflowPosition.START
        _reflow.value = ReflowUiState(hintDismissed = false)
        _reflowRegions.value = emptyMap()
    }

    /** Must run on an IO dispatcher; returns a local path for the open document. */
    private fun ensureLocalPath(): String {        val path = currentPath
        if (!DocumentAccess.isContentLocation(path)) return path
        localFilePath?.let { cached ->
            if (File(cached).isFile) return cached
        }
        val materialized = DocumentAccess.materialize(
            getApplication(),
            path,
            currentDisplayName
        ).getOrThrow().absolutePath
        localFilePath = materialized
        return materialized
    }

    /**
     * Materialises the current content URI once for a tool that only accepts a
     * File (organizer, info, marks, compression, crop). Local documents are
     * returned immediately. Runs on the main thread only for the callback.
     */
    fun withLocalDocument(
        onError: (String) -> Unit = { _message.value = it },
        onReady: (String) -> Unit
    ) {
        val path = currentPath
        if (path.isBlank()) {
            onError("No file selected")
            return
        }
        if (!DocumentAccess.isContentLocation(path)) {
            onReady(path)
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (localFilePath?.let { File(it).isFile } == true) {
                        localFilePath!!
                    } else {
                        DocumentAccess.materialize(
                            getApplication(),
                            path,
                            currentDisplayName
                        ).getOrThrow().absolutePath.also { localFilePath = it }
                    }
                }
            }
            result.fold(
                onSuccess = { onReady(it) },
                onFailure = { onError(it.message ?: "The document could not be prepared") }
            )
        }
    }

    /**
     * Called when a page becomes visible. Publishes a cached bitmap when it
     * already has the right resolution, otherwise schedules a render.
     */
    fun onPageVisible(index: Int, targetPixelWidth: Int) {
        if (index !in pages.indices) return
        visiblePage = index
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
        // T57: the fitted box is what the screen shows, so only that region is
        // rasterised and every overlay's plan is built from it.
        val box = viewBox(index) ?: return
        val scale = PageGeometry.scaleForPixelWidth(box, width).coerceIn(0.05, 12.0)
        jobs[index] = renderScope.launch {
            val rendered = try {
                PageBitmapRenderer.render(
                    page = pages[index],
                    box = box,
                    scale = scale,
                    fontDir = fontDir,
                    theme = _pageTheme.value,
                    transform = _look.value,
                    eink = _eink.value.settings
                )
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

    /**
     * Renders one page for the presentation (T53). The request runs on the
     * same single document dispatcher as the viewer, so the document is never
     * touched concurrently, and the result is cached in a separate bounded
     * cache. Returns null when the page cannot be rendered; the presenter
     * shows an error instead of crashing.
     */
    suspend fun renderPresenterPage(
        index: Int,
        targetPixelWidth: Int,
        maxPixels: Int = 16_000_000
    ): RenderedPage? {
        if (index !in pages.indices) return null
        val width = targetPixelWidth.coerceIn(320, 4096)
        return withContext(documentDispatcher) {
            if (index !in pages.indices) return@withContext null
            presentCache.get(index)?.let { cached ->
                if (resolutionMatches(cached.plan.pixelWidth, width)) {
                    return@withContext RenderedPage(cached.plan, cached.bitmap, emptyList())
                }
            }
            val box = _pageBoxes.value.getOrNull(index) ?: return@withContext null
            val scale = PageGeometry.scaleForPixelWidth(box, width).coerceIn(0.05, 8.0)
            val rendered = runCatching {
                PageBitmapRenderer.render(
                    page = pages[index],
                    scale = scale,
                    maxPixels = maxPixels,
                    fontDir = fontDir,
                    theme = _pageTheme.value,
                    transform = _look.value,
                    eink = _eink.value.settings
                )
            }.getOrNull() ?: return@withContext null
            presentCache.put(index, CachedPage(rendered.plan, rendered.bitmap))
            rendered
        }
    }

    /**
     * Adds the temporary presenter ink to the pending annotations and starts
     * the normal save flow (T53). The marks are real `/Ink` annotations only
     * when the user chooses "Save as annotations"; nothing is written before.
     */
    fun addPresenterInk(specs: List<InkSpec>) {
        if (specs.isEmpty()) return
        _edit.update { it.copy(annotations = it.annotations + specs) }
        _message.value = "Pen marks added. Choose where to save the copy."
        save()
    }

    // --------------------------------------------------- column flow (T59)

    private val _flow = MutableStateFlow(ColumnFlowUiState())
    val flow: StateFlow<ColumnFlowUiState> = _flow.asStateFlow()

    /** Rendered segments, keyed by page, reading order and pixel width. */
    private val _flowBitmaps = MutableStateFlow<Map<String, FlowSegmentBitmap>>(emptyMap())
    val flowBitmaps: StateFlow<Map<String, FlowSegmentBitmap>> = _flowBitmaps.asStateFlow()

    private val flowLoading = HashSet<Int>()
    private val flowBitmapLoading = HashSet<String>()
    private val flowPageOrder = ArrayDeque<Int>()
    private val flowBitmapOrder = ArrayDeque<String>()

    /** The reader's corrections for this document: page → boxes in page space. */
    private var flowCorrections: Map<Int, List<PdfRect>> = emptyMap()
    private var flowPosition: FlowPosition = FlowPosition.START

    /**
     * Opens column flow at [position]. Detection is per page and lazy: the
     * overlay asks for the page it shows, so opening a 500-page book costs one
     * page, not five hundred.
     */
    fun openColumnFlow(position: FlowPosition) {
        if (pages.isEmpty()) return
        val start = FlowPosition(
            position.page.coerceIn(0, pages.size - 1),
            position.order.coerceAtLeast(0)
        )
        flowPosition = start
        flowCorrections = AppSettings.flowCorrections(getApplication(), documentKey)
        flowPageOrder.clear()
        flowLoading.clear()
        _flow.value = ColumnFlowUiState(
            active = true,
            settings = AppSettings.flowSettings(getApplication()),
            position = start
        )
        requestFlowPage(start.page)
    }

    /** Leaves column flow and reports the reading spot the page view should show. */
    fun closeColumnFlow(): FlowPosition {
        _flow.update { it.copy(active = false, loading = emptySet(), message = null) }
        flowLoading.clear()
        return flowPosition
    }

    /** The position right now, for a caller that is about to save the session. */
    fun flowPositionNow(): FlowPosition = flowPosition

    /** Moves to a segment of a page; the overlay then asks for its bitmap. */
    fun setFlowPosition(page: Int, order: Int) {
        if (pages.isEmpty()) return
        val index = page.coerceIn(0, pages.size - 1)
        val target = FlowPosition(index, order.coerceAtLeast(0))
        val movedPage = _flow.value.position.page != index
        flowPosition = target
        _flow.update {
            it.copy(position = target, message = if (movedPage) null else it.message)
        }
        requestFlowPage(index)
        // The next page is prefetched, so a step at the end of a column is not
        // followed by a spinner.
        if (index + 1 < pages.size) requestFlowPage(index + 1)
    }

    /**
     * Steps [steps] segments forward (positive) or back through the document,
     * crossing page boundaries. Used by edge taps, a swipe past a segment's
     * bottom and the volume keys (T58).
     */
    fun flowStep(steps: Int) {
        if (steps == 0) return
        val state = _flow.value
        if (!state.active || pages.isEmpty()) return
        val current = state.position
        val withinPage = state.page?.let { nextFlowSegment(it, current.order, steps) }
        val target = when {
            withinPage != null -> FlowPosition(withinPage.pageIndex, withinPage.order)
            steps > 0 -> {
                if (current.page >= pages.size - 1) return
                FlowPosition(current.page + 1, 0)
            }
            else -> {
                if (current.page <= 0) return
                val previous = current.page - 1
                val order = _flow.value.pages[previous]?.flowSegments?.lastOrNull()?.order ?: 0
                FlowPosition(previous, order)
            }
        }
        setFlowPosition(target.page, target.order)
    }

    /** Shows the whole page, or goes back to the current segment. */
    fun toggleFlowFullPage() {
        _flow.update { it.copy(fullPage = !it.fullPage) }
    }

    fun dismissFlowMessage() {
        _flow.update { it.copy(message = null) }
    }

    /** Applies and remembers a presentation choice; nothing is re-detected. */
    fun setFlowSettings(settings: ColumnFlowSettings) {
        val normalised = settings.normalised()
        AppSettings.setFlowSettings(getApplication(), normalised)
        _flow.update { it.copy(settings = normalised) }
    }

    /**
     * Detects one page if it is not in memory yet, applies a saved correction
     * and publishes it. Detection runs on the shared document dispatcher, so it
     * never races a page render.
     */
    fun requestFlowPage(index: Int) {
        val state = _flow.value
        if (!state.active) return
        if (index < 0 || index >= pages.size) return
        if (state.pages.containsKey(index) || index in flowLoading) return
        flowLoading += index
        _flow.update { it.copy(loading = it.loading + index) }
        textScope.launch {
            val page = pages.getOrNull(index)
            val detected = if (page == null) {
                null
            } else {
                runCatching {
                    ColumnFlow.analyse(
                        page = page,
                        pageIndex = index,
                        options = ColumnFlowOptions(),
                        decoder = AndroidImageDecoder,
                        rasterizer = AndroidPageRasterizer
                    )
                }.getOrNull()
            }
            flowLoading -= index
            if (!isActive) return@launch
            if (detected == null) {
                _flow.update { it.copy(loading = it.loading - index) }
                return@launch
            }
            val corrected = applyFlowCorrection(detected, flowCorrections[index])
            _flow.update { current ->
                if (!current.active) {
                    current.copy(loading = current.loading - index)
                } else {
                    current.copy(
                        pages = current.pages + (index to corrected),
                        loading = current.loading - index,
                        message = if (current.position.page == index && corrected.fallback) {
                            "This page was left as one block because its columns were not clear."
                        } else {
                            current.message
                        }
                    )
                }
            }
            rememberFlowPage(index)
        }
    }

    /** Keeps the pages near the reader and forgets the far ones. */
    private fun rememberFlowPage(centre: Int) {
        flowPageOrder.remove(centre)
        flowPageOrder.addLast(centre)
        val current = _flow.value
        if (current.pages.size <= FLOW_PAGE_CACHE) return
        val keep = HashSet<Int>()
        for (offset in -2..2) keep += centre + offset
        val removed = ArrayList<Int>()
        val iterator = flowPageOrder.iterator()
        var size = current.pages.size
        while (iterator.hasNext() && size > FLOW_PAGE_CACHE) {
            val candidate = iterator.next()
            if (candidate in keep) continue
            iterator.remove()
            if (!current.pages.containsKey(candidate)) continue
            removed += candidate
            size--
        }
        if (removed.isEmpty()) return
        _flow.update { it.copy(pages = it.pages - removed.toSet()) }
    }

    /**
     * Renders one segment at [pixelWidth] pixels wide, or the whole page when
     * [fullPage] is set. The page renderer paints it, so a figure keeps its own
     * look and the page theme and the T62 look apply unchanged.
     */
    fun requestFlowSegment(pageIndex: Int, order: Int, pixelWidth: Int, fullPage: Boolean) {
        val state = _flow.value
        if (!state.active || pixelWidth <= 0) return
        if (pageIndex < 0 || pageIndex >= pages.size) return
        val key = flowSegmentKey(pageIndex, order, pixelWidth, fullPage)
        if (_flowBitmaps.value.containsKey(key) || key in flowBitmapLoading) return
        val page = state.pages[pageIndex]
        if (page == null) {
            requestFlowPage(pageIndex)
            return
        }
        val bounds = if (fullPage) page.page else page.segmentAt(order)?.bounds ?: return
        val rotate = page.rotate
        flowBitmapLoading += key
        textScope.launch {
            val source = pages.getOrNull(pageIndex)
            val rendered = if (source == null) {
                null
            } else {
                runCatching {
                    PageBitmapRenderer.render(
                        page = source,
                        box = flowSegmentBox(bounds, rotate),
                        scale = flowSegmentScale(bounds, rotate, pixelWidth),
                        maxPixels = if (fullPage) 12_000_000 else 4_000_000,
                        decoder = AndroidImageDecoder,
                        fontDir = fontDir,
                        theme = _pageTheme.value,
                        transform = _look.value,
                        eink = _eink.value.settings
                    )
                }.getOrNull()
            }
            flowBitmapLoading -= key
            if (!isActive || rendered == null) return@launch
            publishFlowBitmap(key, FlowSegmentBitmap(rendered.bitmap, rendered.plan.pixelWidth))
        }
    }

    /** Publishes a rendered segment and drops the oldest beyond the cap. */
    private fun publishFlowBitmap(key: String, bitmap: FlowSegmentBitmap) {
        flowBitmapOrder.remove(key)
        flowBitmapOrder.addLast(key)
        val removed = ArrayList<String>()
        while (flowBitmapOrder.size > FLOW_BITMAP_CACHE) {
            removed += flowBitmapOrder.removeFirst()
        }
        _flowBitmaps.update { current ->
            val next = LinkedHashMap(current)
            for (old in removed) next.remove(old)
            next[key] = bitmap
            next
        }
    }

    // ---- manual corrections ------------------------------------------------

    /**
     * Merges the current segment with the one after it on the same page, so a
     * column the detector split in two reads as one. The result is remembered
     * for this document and never written into the PDF.
     */
    fun flowMergeNext() {
        val state = _flow.value
        val page = state.page ?: return
        val current = page.segmentAt(state.position.order) ?: return
        val next = page.flowSegments.firstOrNull { it.order > current.order } ?: return
        val merged = ColumnFlow.mergeBoxes(page, listOf(current.ref, next.ref)) ?: return
        val boxes = page.flowSegments.mapNotNull { segment ->
            when (segment.order) {
                current.order -> merged
                next.order -> null
                else -> segment.bounds
            }
        }
        storeFlowCorrection(page, boxes, "Merged two segments")
    }

    /** Splits the current segment in half at its middle, top to bottom. */
    fun flowSplitHere() {
        val state = _flow.value
        val page = state.page ?: return
        val current = page.segmentAt(state.position.order) ?: return
        val middle = (current.bounds.bottom + current.bounds.top) / 2.0
        val halves = ColumnFlow.splitBox(current, middle)
        if (halves.size < 2) return
        val boxes = page.flowSegments.flatMap { segment ->
            if (segment.order == current.order) halves else listOf(segment.bounds)
        }
        storeFlowCorrection(page, boxes, "Split the segment")
    }

    /** Forgets this page's correction and shows the detected order again. */
    fun flowResetPage() {
        val state = _flow.value
        if (state.page == null) return
        val index = state.position.page
        if (flowCorrections.containsKey(index)) {
            flowCorrections = flowCorrections - index
            AppSettings.setFlowCorrections(getApplication(), documentKey, flowCorrections)
        }
        flowLoading -= index
        _flow.update {
            it.copy(
                pages = it.pages - index,
                position = FlowPosition(index, 0),
                loading = it.loading - index
            )
        }
        flowPosition = FlowPosition(index, 0)
        requestFlowPage(index)
    }

    /**
     * Replaces a page's reading segments with a manual correction, remembers it
     * and republishes the page. The page keeps its furniture (a running head is
     * still shown), because the correction is about reading order.
     */
    private fun storeFlowCorrection(page: FlowPage, boxes: List<PdfRect>, note: String) {
        if (boxes.isEmpty()) return
        val index = page.pageIndex
        flowCorrections = flowCorrections + (index to boxes)
        AppSettings.setFlowCorrections(getApplication(), documentKey, flowCorrections)
        val corrected = ColumnFlow.withSegments(page, boxes)
        _flow.update {
            it.copy(
                pages = it.pages + (index to corrected),
                position = FlowPosition(index, 0),
                message = note
            )
        }
        flowPosition = FlowPosition(index, 0)
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
                PageBitmapRenderer.render(
                    page = page,
                    scale = scale,
                    maxPixels = 4_000_000,
                    fontDir = fontDir,
                    theme = _pageTheme.value,
                    transform = _look.value,
                    eink = _eink.value.settings
                )
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
    fun setSearchQuery(query: String, preferredPage: Int? = null) {
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
                    val preferredIndex = if (preferredPage == null || found.isEmpty()) {
                        if (found.isEmpty()) -1 else 0
                    } else {
                        found.indexOfFirst { it.pageIndex >= preferredPage }
                            .takeIf { it >= 0 } ?: 0
                    }
                    current.copy(
                        matches = found,
                        activeIndex = preferredIndex,
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

    // ------------------------------------------------------------ regex (T65)

    /** Remembers the pattern the reader typed; running is a separate action. */
    fun setRegexPattern(pattern: String) {
        _regex.update { it.copy(pattern = pattern, error = null) }
    }

    /** The chips are a shared preference, so the library opens with the same ones. */
    fun setRegexFlags(flags: Set<RegexFlag>) {
        AppSettings.setRegexFlags(getApplication(), flags)
        _regex.update { it.copy(flags = flags, error = null) }
    }

    /**
     * Runs [pattern] over every page of the open document. A blank pattern
     * clears the results, an invalid one publishes its error and keeps the row
     * list empty, and a newer run always wins over an older one still finishing.
     */
    fun runDocumentRegex(pattern: String, flags: Set<RegexFlag>) {
        val doc = document
        regexJob?.cancel()
        regexJob = null
        val generation = ++regexGeneration
        regexCancelRequested = false
        val name = currentDisplayName
        if (pattern.isEmpty() || doc == null) {
            _regex.value = RegexUiState(scope = RegexScope.DOCUMENT, pattern = pattern, flags = flags)
            return
        }
        _regex.value = RegexUiState(
            scope = RegexScope.DOCUMENT,
            pattern = pattern,
            flags = flags,
            running = true,
            progress = RegexProgress(filesDone = 0, filesTotal = 0, currentName = "")
        )
        regexJob = textScope.launch {
            val result = doc.searchRegex(
                pattern = pattern,
                options = documentOptions(flags),
                onProgress = { done, total ->
                    if (generation == regexGeneration) {
                        _regex.update { current ->
                            if (current.pattern != pattern) {
                                current
                            } else {
                                current.copy(
                                    progress = RegexProgress(done, total, "")
                                )
                            }
                        }
                    }
                },
                isCancelled = { regexCancelRequested || !isActive }
            )
            if (!isActive || generation != regexGeneration) return@launch
            val rows = if (result is RegexResult.Matches) {
                rowsForResult(RegexScope.DOCUMENT, 0L, "", name, result)
            } else {
                emptyList()
            }
            _regex.update { current ->
                when (result) {
                    is RegexResult.InvalidPattern -> RegexUiState(
                        scope = RegexScope.DOCUMENT,
                        pattern = pattern,
                        flags = flags,
                        error = result.error
                    )
                    is RegexResult.Matches -> current.copy(
                        rows = rows,
                        running = false,
                        error = null,
                        truncated = result.truncated,
                        timedOut = result.timedOut,
                        cancelled = result.cancelled,
                        progress = null,
                        activeIndex = if (rows.isEmpty()) -1 else 0
                    )
                }
            }
        }
    }

    /**
     * Stops the run at the next page boundary. The matches found so far are
     * kept and the state says the run was cancelled, so a slow pattern never
     * leaves the reader with nothing.
     */
    fun cancelDocumentRegex() {
        if (regexJob?.isActive != true) return
        regexCancelRequested = true
        _regex.update { it.copy(running = false, cancelled = true) }
    }

    fun regexNext() = stepRegex(1)

    fun regexPrevious() = stepRegex(-1)

    private fun stepRegex(delta: Int) {
        _regex.update { current ->
            val index = stepRegexIndex(current.activeIndex, current.visibleRows.size, delta)
            current.copy(activeIndex = index)
        }
    }

    /** Selects one row (a tap on the list); an out-of-range index selects none. */
    fun setRegexActive(index: Int) {
        _regex.update { current ->
            current.copy(activeIndex = if (index in current.visibleRows.indices) index else -1)
        }
    }

    /** "Unique matches only" for the document-scope list (T65 panel switch). */
    fun setRegexUniqueOnly(unique: Boolean) {
        _regex.update { it.copy(uniqueOnly = unique, activeIndex = -1) }
    }

    /**
     * The one-page tester: runs [pattern] over the page the reader is looking at
     * with a 50-match cap and a 300 ms deadline, then reports "3 matches on page
     * 4 · 1.2 s" or the compile error. It never touches the document's rows.
     */
    fun regexTester(pattern: String, flags: Set<RegexFlag>) {
        val pageIndex = visiblePage
        val started = System.nanoTime()
        textScope.launch {
            val layout = layoutFor(pageIndex)
            if (layout == null) {
                _regex.update { it.copy(tester = RegexTesterResult("")) }
                return@launch
            }
            val result = RegexSearch.find(
                pattern = pattern,
                layout = layout,
                options = documentOptions(flags).copy(
                    maxMatches = TESTER_MAX_MATCHES,
                    timeLimitMs = TESTER_TIME_MS
                )
            )
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            val tester = when (result) {
                is RegexResult.InvalidPattern -> RegexTesterResult("", result.error)
                is RegexResult.Matches -> RegexTesterResult(
                    describeRegexTester(result.matches.size, pageIndex, elapsed)
                )
            }
            _regex.update { it.copy(tester = tester) }
        }
    }

    /** Puts the sheet back to its empty state (a new document, or "Clear"). */
    fun clearRegex() {
        regexGeneration++
        regexJob?.cancel()
        regexJob = null
        regexCancelRequested = false
        _regex.value = RegexUiState(scope = RegexScope.DOCUMENT)
    }

    /**
     * T65: builds a copy with one /Highlight per regex row that has boxes. The
     * bytes come from the source the viewer opened, so unsaved annotations are
     * not included and the open file is byte-identical afterwards.
     */
    fun highlightRegexMatches() {
        val specs = RegexActions.highlightsFor(
            _regex.value.rows,
            AppSettings.authorName(getApplication())
        )
        if (specs.isEmpty()) {
            _message.value = "No matches with boxes on this document"
            return
        }
        val doc = document ?: return
        val current = _edit.value
        val path = currentPath
        textScope.launch {
            val result = runCatching {
                val originalBytes = openedBytes
                    ?: DocumentAccess.readBytes(getApplication(), path).getOrThrow()
                val incremental = !doc.isEncrypted &&
                    doc.file.startXrefOffset != null &&
                    originalBytes.isNotEmpty()
                val bytes = if (incremental) {
                    PdfPageOps.editIncremental(
                        doc,
                        originalBytes,
                        specs,
                        current.fieldValues,
                        emptySet()
                    ).getOrThrow()
                } else {
                    PdfPageOps.edit(doc, specs, current.fieldValues, emptySet()).getOrThrow()
                }
                ViewerExport(
                    bytes = bytes,
                    suggestedName = highlightedCopyName(),
                    kind = ViewerExportKind.HIGHLIGHTS,
                    originalBytes = originalBytes,
                    highlightCount = specs.size
                )
            }
            if (!isActive) return@launch
            result.fold(
                onSuccess = { pending ->
                    _export.value = pending
                    _message.value = regexConfirmText(
                        matchCount = pending.highlightCount,
                        truncated = _regex.value.truncated,
                        timedOut = _regex.value.timedOut,
                        hasUnsavedEdits = current.hasPendingChanges
                    )
                },
                onFailure = { error ->
                    _message.value = error.message ?: "Could not build the highlighted copy"
                }
            )
        }
    }

    /**
     * T65: closes the regex sheet and hands its pattern to T28's own redaction
     * search. The hand-off must not run that search here: only one redaction
     * matcher exists, so the panel prefills and the reader presses Find.
     */
    fun handOffRegexToRedactions() {
        val pattern = _regex.value.pattern
        if (pattern.isEmpty()) return
        _redaction.update {
            it.copy(
                active = true,
                error = null,
                outcome = null,
                find = RedactionFindUi(mode = RedactionFindMode.REGEX, query = pattern),
                pageSelection = emptySet()
            )
        }
        _regex.update { it.copy(handoff = RegexRedactionHandoff(pattern)) }
    }

    /** The screen consumed the redaction hand-off; clear it so it fires once. */
    fun clearRegexHandoff() {
        _regex.update { if (it.handoff == null) it else it.copy(handoff = null) }
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
        if (tool == AnnotationTool.INK) ensureInkLoaded()
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
                ensureInkLoaded()
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

    /** Undo for the top bar: ink, then object edits, then the last annotation. */
    fun undoAny() {
        when {
            _edit.value.tool == AnnotationTool.INK && _ink.value.canUndo -> undoInk()
            _objectEdit.value.canUndo -> undoObjectEdit()
            _edit.value.tool == AnnotationTool.INK -> Unit
            else -> undoAnnotation()
        }
    }

    /** Redo for the top bar: ink first, then object edits. */
    fun redoAny() {
        when {
            _edit.value.tool == AnnotationTool.INK && _ink.value.canRedo -> redoInk()
            _objectEdit.value.canRedo -> redoObjectEdit()
        }
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

    // --------------------------------------------------------- stylus ink (T54)

    /** Loads ink already saved in the document once, so it stays editable. */
    fun ensureInkLoaded() {
        if (inkLoaded) return
        val doc = document ?: return
        inkLoaded = true
        _ink.update { it.copy(loading = true) }
        textScope.launch {
            val loaded = ArrayList<InkStroke>()
            val replaced = HashSet<Int>()
            // Loaded strokes use a separate negative id range. A stroke the
            // user starts before this background read finishes keeps its own
            // positive id, so the merge below can never collide with it.
            var nextId = -1L
            for (page in doc.pages()) {
                for (annotation in runCatching { page.annotations() }.getOrDefault(emptyList())) {
                    val stored = runCatching { PdfPaperInk.read(annotation) }.getOrDefault(emptyList())
                    if (stored.isEmpty()) continue
                    annotation.ref?.let { replaced += it.objNum }
                    for (data in stored) {
                        loaded += InkPalette.converted(data, page.index, nextId--)
                    }
                }
            }
            _ink.update { state ->
                state.copy(
                    strokes = state.strokes + loaded,
                    savedAnnotationIds = state.savedAnnotationIds + replaced,
                    loading = false
                )
            }
        }
    }

    fun setInkMode(mode: InkToolMode) {
        _ink.update {
            it.copy(
                mode = mode,
                active = null,
                lasso = emptyList(),
                selected = if (mode == InkToolMode.LASSO) it.selected else emptySet()
            )
        }
        if (mode != InkToolMode.LASSO) inkMoveAnchor = null
    }

    fun setInkColor(color: com.pdfpaper.engine.graphics.PdfColor) {
        _ink.update { it.copy(color = color) }
    }

    fun setInkWidth(width: Double) {
        _ink.update { it.copy(width = width.coerceIn(0.5, 40.0)) }
    }

    fun setStylusOnly(enabled: Boolean) {
        _ink.update { it.copy(stylusOnly = enabled) }
    }

    fun setInkFocusPage(pageIndex: Int?) {
        _ink.update { it.copy(focusPage = pageIndex) }
    }

    fun clearInkSelection() {
        _ink.update { it.copy(selected = emptySet()) }
    }

    fun deleteSelectedInk() {
        val selected = _ink.value.selected
        if (selected.isEmpty()) return
        beginInkAction()
        changedInk(_ink.value.strokes.filterNot { it.id in selected })
        _ink.update { it.copy(selected = emptySet()) }
        endInkAction()
    }

    fun clearInkStrokes() {
        if (_ink.value.strokes.isEmpty()) return
        beginInkAction()
        changedInk(emptyList())
        _ink.update { it.copy(selected = emptySet()) }
        endInkAction()
    }

    fun selectAllInkOnPage(pageIndex: Int) {
        _ink.update {
            it.copy(selected = it.strokesOn(pageIndex).map { stroke -> stroke.id }.toSet())
        }
    }

    private fun inkSampleAt(
        pageIndex: Int,
        fractionX: Float,
        fractionY: Float,
        pressure: Float,
        tilt: Float
    ): InkSample? {
        val plan = _plans.value[pageIndex]
            ?: viewBox(pageIndex)?.let { PageGeometry.plan(it, 1.0) }
            ?: return null
        return InkTransform.sampleAt(plan, fractionX, fractionY, pressure, tilt)
    }

    fun startInkStroke(
        pageIndex: Int,
        fractionX: Float,
        fractionY: Float,
        pressure: Float,
        tilt: Float,
        stylusButton: Boolean = false
    ) {
        val sample = inkSampleAt(pageIndex, fractionX, fractionY, pressure, tilt) ?: return
        val state = _ink.value
        inkGesturePage = pageIndex
        inkEraserOverride = stylusButton
        beginInkAction()
        val mode = if (stylusButton) InkToolMode.ERASER_POINT else state.mode
        when (mode) {
            InkToolMode.PEN, InkToolMode.HIGHLIGHTER -> {
                val tool = if (state.mode == InkToolMode.HIGHLIGHTER) {
                    com.pdfpaper.engine.annot.InkTool.HIGHLIGHTER
                } else {
                    com.pdfpaper.engine.annot.InkTool.PEN
                }
                val width = if (tool == com.pdfpaper.engine.annot.InkTool.HIGHLIGHTER) {
                    InkPalette.HIGHLIGHTER_WIDTH
                } else {
                    state.width
                }
                val opacity = if (tool == com.pdfpaper.engine.annot.InkTool.HIGHLIGHTER) {
                    InkPalette.HIGHLIGHTER_OPACITY
                } else {
                    1.0
                }
                _ink.update {
                    it.copy(
                        active = InkStroke(
                            id = inkNextId++,
                            pageIndex = pageIndex,
                            samples = listOf(sample),
                            color = state.color,
                            width = width,
                            opacity = opacity,
                            tool = tool
                        ),
                        lasso = emptyList()
                    )
                }
            }
            InkToolMode.ERASER_POINT -> {
                eraseInk(pageIndex, listOf(sample), 8.0)
                _ink.update { it.copy(lasso = listOf(sample)) }
            }
            InkToolMode.ERASER_STROKE -> {
                eraseInk(pageIndex, listOf(sample), 7.0)
                _ink.update { it.copy(lasso = listOf(sample)) }
            }
            InkToolMode.LASSO -> {
                if (state.selected.isNotEmpty()) {
                    inkMoveBase = state.strokes
                    inkMoveAnchor = sample
                } else {
                    _ink.update {
                        it.copy(lasso = listOf(sample), selected = emptySet(), active = null)
                    }
                }
            }
        }
    }

    fun extendInkStroke(
        fractionX: Float,
        fractionY: Float,
        pressure: Float,
        tilt: Float,
        stylusButton: Boolean = false
    ) {
        val state = _ink.value
        val pageIndex = inkGesturePage
            ?: state.active?.pageIndex
            ?: return
        val sample = inkSampleAt(pageIndex, fractionX, fractionY, pressure, tilt) ?: return
        val mode = if (stylusButton || inkEraserOverride) InkToolMode.ERASER_POINT else state.mode
        when (mode) {
            InkToolMode.PEN, InkToolMode.HIGHLIGHTER -> {
                val active = state.active ?: return
                val last = active.samples.lastOrNull()
                if (last != null && kotlin.math.hypot(sample.x - last.x, sample.y - last.y) < 0.12) return
                _ink.update { it.copy(active = active.copy(samples = active.samples + sample)) }
            }
            InkToolMode.ERASER_POINT -> {
                eraseInk(pageIndex, listOf(sample), 8.0)
                _ink.update { it.copy(lasso = listOf(sample)) }
            }
            InkToolMode.ERASER_STROKE -> {
                val path = state.lasso + sample
                eraseInk(pageIndex, path, 7.0)
                _ink.update { it.copy(lasso = path) }
            }
            InkToolMode.LASSO -> {
                val anchor = inkMoveAnchor
                val base = inkMoveBase
                if (anchor != null && base != null) {
                    val dx = sample.x - anchor.x
                    val dy = sample.y - anchor.y
                    val moved = base.map {
                        if (it.id in state.selected) InkGeometry.move(it, dx, dy) else it
                    }
                    changedInk(moved)
                } else {
                    _ink.update { it.copy(lasso = it.lasso + sample) }
                }
            }
        }
    }

    fun finishInkStroke(held: Boolean) {
        val state = _ink.value
        val mode = if (inkEraserOverride) InkToolMode.ERASER_POINT else state.mode
        when (mode) {
            InkToolMode.PEN, InkToolMode.HIGHLIGHTER -> {
                val active = state.active
                if (active != null) {
                    val raw = if (active.samples.size == 1) {
                        val point = active.samples.first()
                        listOf(point, point.copy(x = point.x + 0.01))
                    } else {
                        active.samples
                    }
                    val snap = InkGeometry.detectShape(raw, held)
                    val samples = snap?.samples ?: InkGeometry.forSaving(raw)
                    if (samples.size >= 2) {
                        val finished = active.copy(samples = samples)
                        changedInk(state.strokes + finished)
                    }
                }
                _ink.update { it.copy(active = null) }
            }
            InkToolMode.ERASER_POINT, InkToolMode.ERASER_STROKE -> {
                _ink.update { it.copy(lasso = emptyList()) }
            }
            InkToolMode.LASSO -> {
                if (inkMoveAnchor == null) {
                    val pageIndex = inkGesturePage
                    val selected = if (pageIndex == null) {
                        emptySet()
                    } else {
                        InkGeometry.lassoSelect(state.strokesOn(pageIndex), state.lasso)
                    }
                    _ink.update { it.copy(selected = selected, lasso = emptyList()) }
                } else {
                    inkMoveAnchor = null
                    inkMoveBase = null
                }
            }
        }
        inkGesturePage = null
        inkEraserOverride = false
        endInkAction()
    }

    private fun eraseInk(pageIndex: Int, path: List<InkSample>, tolerance: Double) {
        if (path.isEmpty()) return
        val current = _ink.value.strokes
        val remaining = current.filterNot {
            it.pageIndex == pageIndex && InkGeometry.strokeHitsPath(it, path, tolerance)
        }
        if (remaining.size != current.size) changedInk(remaining)
    }

    private fun beginInkAction() {
        if (inkActionBase == null) inkActionBase = _ink.value.strokes
    }

    private fun changedInk(strokes: List<InkStroke>) {
        _ink.update { it.copy(strokes = strokes, dirty = true) }
        _edit.update { it.copy(inkDirty = true) }
    }

    private fun endInkAction() {
        val base = inkActionBase ?: return
        inkActionBase = null
        if (base != _ink.value.strokes) {
            inkUndo.addLast(base)
            while (inkUndo.size > 60) inkUndo.removeFirst()
            inkRedo.clear()
        }
        publishInkHistory()
    }

    private fun publishInkHistory() {
        _ink.update { it.copy(canUndo = inkUndo.isNotEmpty(), canRedo = inkRedo.isNotEmpty()) }
    }

    fun undoInk() {
        val previous = inkUndo.removeLastOrNull() ?: return
        inkRedo.addLast(_ink.value.strokes)
        _ink.update {
            it.copy(
                strokes = previous,
                active = null,
                lasso = emptyList(),
                selected = emptySet(),
                dirty = true,
                canUndo = inkUndo.isNotEmpty(),
                canRedo = true
            )
        }
        _edit.update { it.copy(inkDirty = true) }
    }

    fun redoInk() {
        val next = inkRedo.removeLastOrNull() ?: return
        inkUndo.addLast(_ink.value.strokes)
        _ink.update {
            it.copy(
                strokes = next,
                active = null,
                lasso = emptyList(),
                selected = emptySet(),
                dirty = true,
                canUndo = true,
                canRedo = inkRedo.isNotEmpty()
            )
        }
        _edit.update { it.copy(inkDirty = true) }
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
        val box = viewBox(pageIndex) ?: return
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
                    val source = DocumentAccess.openSource(getApplication(), path).getOrThrow()
                    PdfSignatures.verify(source, PlatformTrustStore).getOrThrow()
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
        _export.value = ViewerExport(
            outcome.bytes,
            outcome.suggestedName,
            ViewerExportKind.SIGNED
        )
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
                    val bytes = DocumentAccess.readBytes(getApplication(), path).getOrThrow()
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
                    val local = withContext(Dispatchers.IO) { ensureLocalPath() }
                    val signed = PdfSigner.sign(File(local), key, options).getOrThrow()
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
        val box = viewBox(pageIndex) ?: return null
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
                                summary = ocrNoTextSummary(searchable)
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
        _export.value = ViewerExport(
            result.bytes,
            "$base-searchable.pdf",
            ViewerExportKind.SEARCHABLE
        )
    }

    fun undoAnnotation() = _edit.update {
        it.copy(annotations = it.annotations.dropLast(1), inkDraft = null)
    }

    fun clearAnnotations() = _edit.update { it.copy(annotations = emptyList(), inkDraft = null) }

    // ------------------------------------------------------- redaction (UI-4)

    /**
     * Starts the redaction session without throwing away marks the user already
     * placed. The original document is never touched; [applyRedactions] writes
     * new bytes and then verifies those bytes before offering them for saving.
     */
    fun startRedactionSession() {
        _redaction.update {
            it.copy(
                active = true,
                error = null,
                outcome = null,
                find = RedactionFindUi(),
                pageSelection = emptySet()
            )
        }
        clearSelection()
    }

    /** Leaves the marking UI. Pending marks are kept until the session is cleared. */
    fun endRedaction() {
        _redaction.update {
            it.copy(active = false, find = RedactionFindUi(), pageSelection = emptySet())
        }
        clearSelection()
    }

    fun setRedactionTool(tool: RedactionTool) {
        _redaction.update { it.copy(tool = tool, error = null) }
        if (tool != RedactionTool.TEXT) clearSelection()
    }

    fun setRedactionProperties(properties: RedactionProperties) {
        _redaction.update {
            it.copy(
                properties = properties.copy(
                    overlayText = properties.overlayText.take(48),
                    fontSize = properties.fontSize.coerceIn(8f, 32f)
                )
            )
        }
    }

    fun applyRedactionPropertiesToMarks() {
        _redaction.update { state ->
            val properties = state.properties
            state.copy(
                marks = state.marks.map {
                    it.copy(
                        overlayText = properties.overlayText.takeIf(String::isNotBlank),
                        color = properties.coverColor,
                        fontSize = properties.fontSize
                    )
                }
            )
        }
    }

    fun toggleRedactionPage(pageIndex: Int) {
        if (pageIndex !in pages.indices) return
        _redaction.update { state ->
            val selected = state.pageSelection
            state.copy(
                pageSelection = if (pageIndex in selected) selected - pageIndex else selected + pageIndex,
                error = null
            )
        }
    }

    fun selectAllRedactionPages(pageCount: Int) {
        _redaction.update {
            it.copy(pageSelection = pages.indices.take(pageCount.coerceAtMost(pages.size)).toSet())
        }
    }

    fun clearRedactionPageSelection() = _redaction.update { it.copy(pageSelection = emptySet()) }

    fun markRedactionPages() {
        val state = _redaction.value
        if (state.pageSelection.isEmpty()) return
        val existing = state.marks.filter { it.type == RedactionMarkType.PAGE }.map { it.pageIndex }.toSet()
        val newMarks = state.pageSelection.sorted().filterNot { it in existing }.map { pageIndex ->
            newRedactionMark(
                pageIndex = pageIndex,
                type = RedactionMarkType.PAGE,
                rects = emptyList(),
                properties = state.properties
            )
        }
        if (newMarks.isEmpty()) {
            _message.value = "Those pages already have whole-page marks"
            return
        }
        _redaction.update {
            it.copy(
                marks = it.marks + newMarks,
                pageSelection = emptySet(),
                outcome = null,
                error = null
            )
        }
    }

    /**
     * Converts a drag in page fractions to unrotated PDF page space and adds a
     * box mark. Tiny accidental taps are ignored.
     */
    fun addRedactionArea(pageIndex: Int, left: Float, top: Float, right: Float, bottom: Float) {
        if (pageIndex !in pages.indices) return
        val a = toUserSpace(
            pageIndex,
            left.coerceIn(0f, 1f),
            top.coerceIn(0f, 1f)
        ) ?: return
        val b = toUserSpace(
            pageIndex,
            right.coerceIn(0f, 1f),
            bottom.coerceIn(0f, 1f)
        ) ?: return
        val rect = PdfRect(a[0], a[1], b[0], b[1])
        if (rect.width < 2.0 || rect.height < 2.0) {
            _message.value = "Drag a larger box to mark content"
            return
        }
        addRedactionMark(
            newRedactionMark(
                pageIndex = pageIndex,
                type = RedactionMarkType.AREA,
                rects = listOf(rect),
                properties = _redaction.value.properties
            )
        )
    }

    /** Tap-to-mark an image. The smallest image containing the tap wins. */
    fun markRedactionImage(pageIndex: Int, fractionX: Float, fractionY: Float) {
        if (pageIndex !in pages.indices) return
        val point = toUserSpace(pageIndex, fractionX, fractionY) ?: return
        textScope.launch {
            val page = pages.getOrNull(pageIndex) ?: return@launch
            val display = runCatching { page.buildDisplayList(AndroidImageDecoder) }.getOrNull()
            if (display == null) {
                _message.value = "The page's images could not be read"
                return@launch
            }
            val bounds = display.images.mapNotNull { image ->
                val corners = listOf(
                    image.matrix.apply(0.0, 0.0),
                    image.matrix.apply(1.0, 0.0),
                    image.matrix.apply(0.0, 1.0),
                    image.matrix.apply(1.0, 1.0)
                )
                val xs = corners.map { it[0] }
                val ys = corners.map { it[1] }
                PdfRect(xs.min(), ys.min(), xs.max(), ys.max())
            }.filter { !it.isEmpty && it.contains(point[0], point[1]) }
            val image = bounds.minByOrNull { it.width * it.height }
            if (image == null) {
                _message.value = "No image under that point. Tap inside a picture."
                return@launch
            }
            addRedactionMark(
                newRedactionMark(
                    pageIndex = pageIndex,
                    type = RedactionMarkType.IMAGE,
                    rects = listOf(image),
                    properties = _redaction.value.properties
                )
            )
        }
    }

    fun markRedactionSelection() {
        val selection = _selection.value ?: return
        if (selection.rects.isEmpty()) return
        val rects = selection.rects.map { PdfRect(it.x0, it.y0, it.x1, it.y1) }
        addRedactionMark(
            newRedactionMark(
                pageIndex = selection.pageIndex,
                type = RedactionMarkType.TEXT_SELECTION,
                rects = rects,
                expectedText = selection.text.takeIf { it.isNotBlank() },
                properties = _redaction.value.properties
            )
        )
        clearSelection()
    }

    fun setRedactionFindMode(mode: RedactionFindMode) {
        _redaction.update {
            it.copy(
                find = it.find.copy(
                    mode = mode,
                    matches = emptyList(),
                    checked = emptySet(),
                    searched = false,
                    error = null
                )
            )
        }
    }

    fun setRedactionFindQuery(query: String) {
        _redaction.update { it.copy(find = it.find.copy(query = query, error = null)) }
    }

    fun findRedactions() {
        val doc = document ?: return
        val find = _redaction.value.find
        if (find.running) return
        if (find.mode == RedactionFindMode.REGEX && find.query.isBlank()) {
            _redaction.update { it.copy(find = it.find.copy(error = "Enter a pattern first")) }
            return
        }
        _redaction.update {
            it.copy(find = it.find.copy(running = true, searched = false, error = null))
        }
        textScope.launch {
            val result = runCatching {
                PdfRedaction.find(
                    document = doc,
                    query = if (find.mode == RedactionFindMode.REGEX) find.query else "",
                    regex = find.mode == RedactionFindMode.REGEX,
                    preset = find.mode.preset
                )
            }
            if (!isActive) return@launch
            result.fold(
                onSuccess = { matches ->
                    _redaction.update {
                        it.copy(
                            find = it.find.copy(
                                running = false,
                                searched = true,
                                matches = matches,
                                checked = matches.indices.toSet(),
                                error = null
                            )
                        )
                    }
                },
                onFailure = { error ->
                    _redaction.update {
                        it.copy(
                            find = it.find.copy(
                                running = false,
                                searched = true,
                                matches = emptyList(),
                                checked = emptySet(),
                                error = error.message ?: "The search pattern is invalid"
                            )
                        )
                    }
                }
            )
        }
    }

    fun toggleRedactionFindMatch(index: Int) {
        _redaction.update { state ->
            val matches = state.find.matches
            if (index !in matches.indices) return@update state
            val checked = state.find.checked
            state.copy(
                find = state.find.copy(
                    checked = if (index in checked) checked - index else checked + index
                )
            )
        }
    }

    fun toggleAllRedactionFindMatches() {
        _redaction.update { state ->
            val checked = if (state.find.allChecked) emptySet() else state.find.matches.indices.toSet()
            state.copy(find = state.find.copy(checked = checked))
        }
    }

    fun markRedactionFindMatches() {
        val state = _redaction.value
        val checked = state.find.checked
        if (checked.isEmpty()) return
        val marks = state.find.matches.mapIndexedNotNull { index, match ->
            if (index !in checked) return@mapIndexedNotNull null
            newRedactionMark(
                pageIndex = match.pageIndex,
                type = RedactionMarkType.TEXT_SELECTION,
                rects = match.rects,
                expectedText = match.text,
                properties = state.properties
            )
        }
        if (marks.isEmpty()) return
        _redaction.update {
            it.copy(
                marks = it.marks + marks,
                find = RedactionFindUi(mode = it.find.mode, query = it.find.query),
                outcome = null,
                error = null
            )
        }
    }

    fun removeRedactionMark(id: Long) = _redaction.update { it.removeMark(id) }

    fun updateRedactionMark(mark: RedactionMarkUi) = _redaction.update { it.updateMark(mark) }

    fun clearRedactionMarks() = _redaction.update {
        it.copy(marks = emptyList(), outcome = null, error = null)
    }

    /**
     * Applies the pending marks, reopens the new bytes and verifies them with
     * [PdfRedaction.verify]. The outcome is only published after that check
     * completes; a verification exception is an error, not a success.
     */
    fun applyRedactions() {
        val doc = document ?: return
        val state = _redaction.value
        if (state.marks.isEmpty() || state.busy) return
        val expected = state.marks.mapNotNull { it.expectedText?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
        _redaction.update { it.copy(busy = true, error = null, outcome = null) }
        textScope.launch {
            val result = runCatching {
                val redacted = PdfRedaction.apply(
                    doc,
                    state.marks.map { it.toEngineMark() },
                    AndroidImageDecoder
                )
                val verification = PdfDocument.open(ByteArraySource(redacted.bytes))
                    .getOrThrow()
                    .use { verifier ->
                        PdfRedaction.verify(
                            verifier,
                            expected,
                            AndroidImageDecoder,
                            redacted.unredacted
                        )
                    }
                redacted to verification
            }
            if (!isActive) return@launch
            result.fold(
                onSuccess = { (redacted, verification) ->
                    _redaction.update {
                        it.copy(
                            busy = false,
                            outcome = RedactionOutcome(
                                bytes = redacted.bytes,
                                suggestedName = redactedName(),
                                verification = verification.toUi(),
                                marksApplied = redacted.marksApplied.takeIf { count -> count > 0 }
                                    ?: state.marks.size,
                                warnings = redacted.warnings
                            ),
                            error = null
                        )
                    }
                },
                onFailure = { error ->
                    _redaction.update {
                        it.copy(
                            busy = false,
                            error = error.message ?: "The redacted copy could not be verified",
                            outcome = null
                        )
                    }
                }
            )
        }
    }

    fun dismissRedactionOutcome() = _redaction.update { it.copy(outcome = null) }

    fun saveRedactionCopy() {
        val outcome = _redaction.value.outcome ?: return
        _export.value = ViewerExport(
            outcome.bytes,
            outcome.suggestedName,
            ViewerExportKind.REDACTION
        )
    }

    // -------------------------------------------------------- sanitize (UI-4)

    fun prepareSanitize() {
        _sanitize.update { it.copy(running = false, outcome = null, error = null) }
    }

    fun setSanitizeOption(item: SanitizeItem, enabled: Boolean) {
        _sanitize.update {
            it.copy(
                options = it.options.withRemoval(item, enabled),
                outcome = null,
                error = null
            )
        }
    }

    fun setSanitizeFormHandling(handling: FormDataHandling) {
        _sanitize.update {
            it.copy(
                options = it.options.copy(formData = handling),
                outcome = null,
                error = null
            )
        }
    }

    fun cleanDocument() {
        val doc = document ?: return
        val state = _sanitize.value
        if (state.running) return
        _sanitize.update { it.copy(running = true, outcome = null, error = null) }
        textScope.launch {
            val result = runCatching {
                PdfSanitizer.sanitize(doc, state.options).getOrThrow()
            }
            if (!isActive) return@launch
            result.fold(
                onSuccess = { sanitized ->
                    _sanitize.update {
                        it.copy(
                            running = false,
                            outcome = SanitizeOutcome(
                                bytes = sanitized.bytes,
                                report = sanitized.report,
                                suggestedName = cleanName()
                            ),
                            error = null
                        )
                    }
                },
                onFailure = { error ->
                    _sanitize.update {
                        it.copy(
                            running = false,
                            error = error.message ?: "The document could not be cleaned",
                            outcome = null
                        )
                    }
                }
            )
        }
    }

    fun resetSanitizeOutcome() = _sanitize.update { it.copy(outcome = null, error = null) }

    fun saveSanitizeCopy() {
        val outcome = _sanitize.value.outcome ?: return
        _export.value = ViewerExport(
            outcome.bytes,
            outcome.suggestedName,
            ViewerExportKind.SANITIZE
        )
    }

    private fun newRedactionMark(
        pageIndex: Int,
        type: RedactionMarkType,
        rects: List<PdfRect>,
        properties: RedactionProperties,
        expectedText: String? = null
    ): RedactionMarkUi = RedactionMarkUi(
        id = nextRedactionMarkId++,
        pageIndex = pageIndex,
        type = type,
        rects = rects,
        expectedText = expectedText,
        overlayText = properties.overlayText.takeIf { it.isNotBlank() },
        color = properties.coverColor,
        fontSize = properties.fontSize
    )

    private fun addRedactionMark(mark: RedactionMarkUi) {
        _redaction.update { it.addMark(mark).copy(active = true) }
    }

    private fun redactedName(): String {
        val name = _state.value.displayName ?: "document.pdf"
        return "${name.substringBeforeLast('.')}-redacted.pdf"
    }

    private fun cleanName(): String {
        val name = _state.value.displayName ?: "document.pdf"
        return "${name.substringBeforeLast('.')}-clean.pdf"
    }

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
     * Builds a new PDF with the pending annotations and field values (T12) and
     * writes it back to the source when the source is writable (T55).
     *
     * Annotations, comments, stamps and form values use an incremental update
     * when the file has a usable startxref; a rebuilt file falls back to a full
     * rewrite. Autosave calls this without showing a dialog; a manual Save with
     * the "Ask each time" setting parks the bytes in [export] for the existing
     * Save/Save-a-copy choice.
     */
    fun save(automatic: Boolean = false) {
        val doc = document ?: return
        if (saveInFlight) return
        val current = _edit.value
        if (!current.hasPendingChanges) {
            if (!automatic) _message.value = "Nothing to save yet"
            return
        }
        val inkState = _ink.value
        val inkSpecs = if (current.inkDirty) {
            inkState.strokes.map { it.toSpec() }
        } else {
            emptyList()
        }
        val droppedInk = if (current.inkDirty) {
            inkState.savedAnnotationIds
        } else {
            emptySet()
        }
        _edit.update { it.copy(saving = true) }
        _save.update { it.copy(status = ViewerSaveStatus.SAVING) }
        val path = currentPath
        textScope.launch {
            val result = runCatching {
                // The bytes the document was opened from when they are known:
                // an edit must extend the file the user saw (T55).
                val originalBytes = openedBytes
                    ?: DocumentAccess.readBytes(getApplication(), path).getOrThrow()
                val specs = current.annotations + inkSpecs
                val pages = doc.pages()
                val existingAnnots = pages.sumOf { it.annotations().size }
                val expectation = SaveExpectation(
                    pageCount = pages.size,
                    minimumAnnotations = (existingAnnots - droppedInk.size + specs.size)
                        .coerceAtLeast(0)
                )
                val incremental = !doc.isEncrypted &&
                    doc.file.startXrefOffset != null &&
                    originalBytes.isNotEmpty()
                val bytes = if (incremental) {
                    PdfPageOps.editIncremental(
                        doc,
                        originalBytes,
                        specs,
                        current.fieldValues,
                        droppedInk
                    ).getOrThrow()
                } else {
                    PdfPageOps.edit(
                        doc,
                        specs,
                        current.fieldValues,
                        droppedInk
                    ).getOrThrow()
                }
                ViewerExport(
                    bytes = bytes,
                    suggestedName = editedCopyName(),
                    kind = ViewerExportKind.EDIT,
                    originalBytes = originalBytes,
                    writeMode = if (incremental) SaveWriteMode.INCREMENTAL else SaveWriteMode.FULL_REWRITE,
                    expectation = expectation
                )
            }
            if (!isActive) return@launch
            result.fold(
                onSuccess = { pending -> dispatchSave(pending, automatic) },
                onFailure = { error ->
                    _edit.update { it.copy(saving = false) }
                    _save.update {
                        it.copy(
                            status = ViewerSaveStatus.ERROR,
                            message = error.message ?: "Save failed"
                        )
                    }
                    _message.value = error.message ?: "Save failed"
                }
            )
        }
    }

    /**
     * Sends a verified edit either straight back to a writable source or to
     * the existing Save/Save-a-copy dialog.
     */
    private fun dispatchSave(pending: ViewerExport, automatic: Boolean) {
        val mode = AppSettings.autosaveMode(getApplication())
        val canInPlace = canWriteCurrentSource()
        _edit.update { it.copy(saving = false) }
        if (hasExistingSignatures() && !automatic) {
            pendingSignatureSave = pending
            _save.update {
                it.copy(
                    status = ViewerSaveStatus.UNSAVED,
                    signatureWarning =
                        "This document already has a digital signature. Saving now adds a new " +
                            "revision after it, so the signature will still cover the old " +
                            "revision but no longer the whole file."
                )
            }
            return
        }
        if (automatic) {
            if (mode == AutosaveMode.IN_PLACE && canInPlace) {
                performInPlaceSave(pending)
            }
            return
        }
        when {
            canInPlace && mode == AutosaveMode.IN_PLACE -> performInPlaceSave(pending)
            canInPlace && mode == AutosaveMode.ASK -> {
                _save.update { it.copy(status = ViewerSaveStatus.UNSAVED) }
                _export.value = pending
            }
            else -> {
                _save.update { it.copy(status = ViewerSaveStatus.UNSAVED) }
                if (!performRememberedCopySave(pending)) {
                    _export.value = pending
                }
            }
        }
    }

    /** Confirms the one-time signature warning and continues the save. */
    fun confirmSignatureSave() {
        val pending = pendingSignatureSave ?: return
        pendingSignatureSave = null
        _save.update { it.copy(signatureWarning = null) }
        if (canWriteCurrentSource() && AppSettings.autosaveMode(getApplication()) == AutosaveMode.IN_PLACE) {
            performInPlaceSave(pending)
        } else {
            _export.value = pending
        }
    }

    fun cancelSignatureSave() {
        pendingSignatureSave = null
        _save.update { it.copy(signatureWarning = null, status = ViewerSaveStatus.UNSAVED) }
        _edit.update { it.copy(saving = false) }
    }

    private fun hasExistingSignatures(): Boolean =
        _signatures.value.verification?.signatures?.isNotEmpty() == true

    private fun canWriteCurrentSource(): Boolean {
        val location = _state.value.sourceLocation.ifBlank { currentPath }
        if (location.isBlank()) return false
        return if (DocumentAccess.isContentLocation(location)) {
            _state.value.canSaveInPlace
        } else {
            localFilePath?.let { File(it).canWrite() } == true
        }
    }

    private fun saveTarget(location: String): com.pdfpaper.app.data.save.SaveTarget? =
        if (DocumentAccess.isContentLocation(location)) {
            ContentSaveTarget(getApplication(), location)
        } else {
            File(location).takeIf { it.canWrite() }?.let { FileSaveTarget(it) }
        }

    /**
     * Writes the already verified bytes back to the current source through the
     * T55 coordinator. Every error path reopens the untouched original.
     */
    private fun performInPlaceSave(pending: ViewerExport, overwriteConflict: Boolean = false) {
        if (saveInFlight) return
        val location = _state.value.sourceLocation.ifBlank { currentPath }
        val target = saveTarget(location)
        if (target == null) {
            _save.update {
                it.copy(
                    status = ViewerSaveStatus.UNSAVED,
                    message = "This file is read-only here. Save a copy instead."
                )
            }
            _export.value = pending
            return
        }
        val expectation = pending.expectation ?: SaveExpectation(_state.value.pageCount)
        val original = pending.originalBytes
        if (original == null) {
            _save.update { it.copy(status = ViewerSaveStatus.ERROR, message = "The edit could not be verified") }
            return
        }
        saveInFlight = true
        _edit.update { it.copy(saving = true) }
        _save.update { it.copy(status = ViewerSaveStatus.SAVING, message = null, conflict = false) }
        displayNameOverride = currentDisplayName
        // Captured before the close below, which clears it: the fingerprint of
        // the file as it was opened is what detects a change made elsewhere.
        val opened = openedFingerprint
        val versionsKey = documentOriginKey.ifBlank { location }
        closeCurrentDocument()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                saveCoordinator.save(
                    target = target,
                    originalBytes = original,
                    editedBytes = pending.bytes,
                    expectation = expectation,
                    mode = if (overwriteConflict) {
                        SaveWriteMode.FULL_REWRITE
                    } else {
                        pending.writeMode
                    },
                    versions = VersionStore(getApplication(), versionsKey),
                    overwriteConflict = overwriteConflict,
                    openedFingerprint = opened
                )
            }
            saveInFlight = false
            result.fold(
                onSuccess = { report ->
                    applySavedState()
                    _save.update {
                        it.copy(
                            status = ViewerSaveStatus.SAVED,
                            message = if (report.fallbackToRewrite) {
                                "Saved. The provider did not support an incremental update, so the file was rewritten."
                            } else {
                                "Saved"
                            },
                            conflict = false,
                            conflictMessage = null
                        )
                    }
                    _message.value = "Saved in place"
                    openWith("")
                },
                onFailure = { error ->
                    if (error is FileChangedException) {
                        autosaveJob?.cancel()
                        pendingConflictSave = pending
                        _save.update {
                            it.copy(
                                status = ViewerSaveStatus.ERROR,
                                conflict = true,
                                conflictMessage = error.message
                            )
                        }
                        _message.value = error.message
                    } else {
                        _save.update {
                            it.copy(
                                status = ViewerSaveStatus.ERROR,
                                message = error.message ?: "Could not save",
                                conflict = false
                            )
                        }
                        _message.value = error.message ?: "Could not save"
                    }
                    _edit.update { it.copy(saving = false) }
                    openWith("")
                }
            )
        }
    }

    /** Clears the pending edit after a successful save; the reopen adds the real objects. */
    private fun applySavedState() {
        _edit.update {
            it.copy(
                annotations = emptyList(),
                fieldValues = emptyMap(),
                saving = false,
                inkDirty = false
            )
        }
        resetInkState()
    }

    /** Reloads the current file, discarding the edits that triggered a conflict. */
    fun reloadAfterConflict() {
        pendingConflictSave = null
        _save.update {
            it.copy(status = ViewerSaveStatus.IDLE, conflict = false, conflictMessage = null)
        }
        _edit.update { it.copy(saving = false) }
        closeCurrentDocument()
        openWith("")
    }

    /** Offers the conflicted edit as a copy instead of overwriting the newer file. */
    fun saveConflictAsCopy() {
        val pending = pendingConflictSave ?: return
        pendingConflictSave = null
        _save.update { it.copy(conflict = false, conflictMessage = null) }
        _export.value = pending
    }

    /** The user explicitly chose to replace the newer file. */
    fun overwriteAfterConflict() {
        val pending = pendingConflictSave ?: return
        pendingConflictSave = null
        performInPlaceSave(pending, overwriteConflict = true)
    }

    fun dismissSaveMenu() {
        // "Not now" leaves the edits unsaved; the next real change schedules
        // autosave again.
        _save.update {
            it.copy(
                conflict = false,
                conflictMessage = null,
                status = if (_edit.value.hasPendingChanges) {
                    ViewerSaveStatus.UNSAVED
                } else {
                    ViewerSaveStatus.IDLE
                }
            )
        }
    }

    /** Saves immediately when the app is paused/stopped, if autosave is on. */
    fun autosaveOnPause() {
        if (!canWriteCurrentSource()) return
        if (AppSettings.autosaveMode(getApplication()) != AutosaveMode.IN_PLACE) return
        if (!_edit.value.hasPendingChanges || _edit.value.saving) return
        autosaveJob?.cancel()
        save(automatic = true)
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        if (!canWriteCurrentSource()) return
        if (AppSettings.autosaveMode(getApplication()) != AutosaveMode.IN_PLACE) return
        autosaveJob = viewModelScope.launch {
            delay(autosaveDelayMs)
            if (_edit.value.hasPendingChanges && !_edit.value.saving && !saveInFlight) {
                save(automatic = true)
            }
        }
    }

    /** Writes the pending export to the user-chosen [uri]. */
    fun completeExport(uri: Uri) {
        val pending = _export.value ?: return
        _export.value = null
        if (pending.kind == ViewerExportKind.EDIT) {
            _edit.update { it.copy(saving = true) }
            _save.update { it.copy(status = ViewerSaveStatus.SAVING) }
            writeEditCopy(pending, uri)
            return
        }
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
                    _message.value = when (pending.kind) {
                        ViewerExportKind.REDACTION -> "Saved a verified redacted copy"
                        ViewerExportKind.SANITIZE -> "Saved a clean copy"
                        ViewerExportKind.SEARCHABLE -> "Saved a searchable copy"
                        ViewerExportKind.SIGNED -> "Saved a signed copy"
                        ViewerExportKind.HIGHLIGHTS ->
                            "Saved a copy with ${pending.highlightCount} highlights"
                        ViewerExportKind.EDIT -> "Saved"
                    }
                    // A highlight copy is a side product: the viewed document
                    // must stay the one the reader opened (T65).
                    if (pending.kind != ViewerExportKind.HIGHLIGHTS) {
                        reloadSaved(pending.bytes)
                    }
                },
                onFailure = { error ->
                    _export.value = pending
                    _message.value = error.message ?: "Save failed"
                }
            )
        }
    }

    /**
     * Writes an edited document to a copy the user just chose and remembers
     * that destination for this read-only source (T55), so later saves reuse
     * it instead of asking for another file.
     */
    private fun writeEditCopy(pending: ViewerExport, uri: Uri) {
        val app = getApplication<Application>()
        val originalKey = documentOriginKey.ifBlank {
            _state.value.sourceLocation.ifBlank { currentPath }
        }
        val destination = uri.toString()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    VersionStore(app, originalKey).record(
                        pending.originalBytes ?: ByteArray(0)
                    )
                    val resolver = app.contentResolver
                    resolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(pending.bytes)
                        output.flush()
                    } ?: throw IOException("Could not open the destination for writing")
                    val readBack = DocumentAccess.readBytes(app, destination).getOrThrow()
                    if (!readBack.contentEquals(pending.bytes)) {
                        throw IOException("The copy was not written completely")
                    }
                }
            }
            result.fold(
                onSuccess = {
                    AppSettings.setSaveCopyChoice(app, originalKey, destination)
                    copyDestination = destination
                    documentOriginKey = destination
                    displayNameOverride = currentDisplayName
                    localFilePath = null
                    currentPath = destination
                    _edit.update { it.copy(saving = false) }
                    _save.update {
                        it.copy(
                            status = ViewerSaveStatus.SAVED,
                            message = "Saved a copy; later saves update it in place",
                            canSaveInPlace = true
                        )
                    }
                    _message.value = "Saved a copy"
                    openWith("")
                },
                onFailure = { error ->
                    _export.value = pending
                    _edit.update { it.copy(saving = false) }
                    _save.update {
                        it.copy(
                            status = ViewerSaveStatus.ERROR,
                            message = error.message ?: "Could not save the copy"
                        )
                    }
                    _message.value = error.message ?: "Could not save the copy"
                }
            )
        }
    }

    fun cancelExport() {
        _export.value = null
        _edit.update { it.copy(saving = false) }
        _save.update { it.copy(status = ViewerSaveStatus.UNSAVED) }
    }

    /**
     * Saves over the original content URI. This is offered only when the
     * provider accepted a writable descriptor at open time; the T55
     * coordinator verifies the bytes before writing.
     */
    fun saveExportInPlace() {
        val pending = _export.value ?: return
        if (!canWriteCurrentSource()) {
            _message.value = "This file is read-only here. Use Save a copy instead."
            return
        }
        _export.value = null
        performInPlaceSave(pending)
    }

    /**
     * Saves into the copy destination remembered for a read-only source, if
     * there is one. Returns true when this handled the save.
     */
    fun performRememberedCopySave(pending: ViewerExport): Boolean {
        val key = documentOriginKey.ifBlank { _state.value.sourceLocation }
        val destination = AppSettings.saveCopyChoice(getApplication(), key)
            ?: AppSettings.saveCopyChoice(
                getApplication(),
                _state.value.sourceLocation.ifBlank { currentPath }
            )
            ?: return false
        _edit.update { it.copy(saving = true) }
        _save.update { it.copy(status = ViewerSaveStatus.SAVING, message = null) }
        writeToExistingCopy(pending, destination)
        return true
    }

    /** UI entry point: save now, reusing a remembered copy for a read-only file. */
    fun saveNow() {
        if (_export.value != null) {
            saveIntoRememberedCopyOrInPlace()
            return
        }
        save()
    }

    private fun saveIntoRememberedCopyOrInPlace() {
        val pending = _export.value ?: return
        if (canWriteCurrentSource()) {
            _export.value = null
            performInPlaceSave(pending)
        } else if (!performRememberedCopySave(pending)) {
            _message.value = "Choose where to save the copy"
        }
    }

    private fun writeToExistingCopy(pending: ViewerExport, destination: String) {
        val app = getApplication<Application>()
        val target = ContentSaveTarget(app, destination)
        val expectation = pending.expectation ?: SaveExpectation(_state.value.pageCount)
        val key = documentOriginKey.ifBlank { _state.value.sourceLocation.ifBlank { currentPath } }
        saveInFlight = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val original = target.read().getOrThrow()
                    saveCoordinator.save(
                        target = target,
                        originalBytes = original,
                        editedBytes = pending.bytes,
                        expectation = expectation,
                        mode = SaveWriteMode.FULL_REWRITE,
                        versions = VersionStore(app, key)
                    ).getOrThrow()
                }
            }
            saveInFlight = false
            result.fold(
                onSuccess = {
                    copyDestination = destination
                    documentOriginKey = destination
                    displayNameOverride = currentDisplayName
                    localFilePath = null
                    currentPath = destination
                    _edit.update { it.copy(saving = false) }
                    applySavedState()
                    _save.update {
                        it.copy(
                            status = ViewerSaveStatus.SAVED,
                            message = "Saved",
                            canSaveInPlace = true
                        )
                    }
                    _message.value = "Saved"
                    openWith("")
                },
                onFailure = { error ->
                    _edit.update { it.copy(saving = false) }
                    _export.value = pending
                    _save.update {
                        it.copy(
                            status = ViewerSaveStatus.ERROR,
                            message = error.message ?: "Could not save the copy"
                        )
                    }
                    _message.value = error.message ?: "Could not save the copy"
                }
            )
        }
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
            localFilePath = file.absolutePath
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
                withContext(Dispatchers.IO) {
                    DocumentAccess.readBytes(getApplication(), path).getOrThrow()
                }
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
            ?: viewBox(pageIndex)?.let { PageGeometry.plan(it, 1.0) }
            ?: return null
        return pageFractionToUserSpace(plan, fractionX, fractionY)
    }

    private fun suggestedName(): String {
        val base = (_state.value.displayName ?: "document.pdf").substringBeforeLast('.')
        return "$base-annotated.pdf"
    }

    /** T55: the copy name offered for a read-only source. */
    private fun editedCopyName(): String {
        val name = _state.value.displayName ?: "document.pdf"
        val base = name.substringBeforeLast('.', name)
        return "$base (edited).pdf"
    }

    /** T65: the copy name offered for highlight-all. */
    private fun highlightedCopyName(): String {
        val name = _state.value.displayName ?: "document.pdf"
        val base = name.substringBeforeLast('.', name)
        return "$base-highlighted.pdf"
    }

    // ------------------------------------------------- version history (T55)

    fun loadVersions() {
        val key = documentOriginKey.ifBlank {
            _state.value.sourceLocation.ifBlank { currentPath }
        }
        if (key.isBlank()) {
            _save.update { it.copy(versions = emptyList()) }
            return
        }
        viewModelScope.launch {
            val versions = withContext(Dispatchers.IO) {
                runCatching {
                    VersionStore(getApplication(), key).list().map {
                        ViewerVersionEntry(it.id, it.savedAt, it.sizeBytes)
                    }
                }.getOrDefault(emptyList())
            }
            _save.update { it.copy(versions = versions) }
        }
    }

    // ---- Annotations hub (T56) ---------------------------------------------

    private var hubIndexJob: Job? = null
    private var hubDocumentId: Long? = null

    /**
     * Re-reads the open file for the Annotations hub in the background. The
     * viewer never waits for it: a big document still paints its first page
     * immediately, and the hub catches up on the next refresh if this run is
     * cancelled by a new open.
     */
    private fun indexForHub() {
        val path = currentPath
        if (path.isBlank()) return
        val name = currentDisplayName
        hubIndexJob?.cancel()
        hubIndexJob = viewModelScope.launch {
            val indexed = withContext(Dispatchers.IO) {
                OpenedDocumentIndex.refresh(getApplication(), path, name)
            }
            indexed.onSuccess { hubDocumentId = it.documentId }
        }
    }

    /**
     * Adds an app bookmark for the page on screen. The bookmark lives in the
     * local index; when the user asked for bookmarks in the PDF outline, the
     * outline is written through the T55 save path right away.
     */
    fun addBookmark(pageIndex: Int, label: String, color: String?) {
        val path = currentPath
        if (path.isBlank()) {
            _message.value = "No document is open"
            return
        }
        val name = currentDisplayName
        viewModelScope.launch {
            val documentId = hubDocumentId ?: withContext(Dispatchers.IO) {
                OpenedDocumentIndex.refresh(getApplication(), path, name)
                    .getOrNull()?.documentId
            }
            if (documentId == null) {
                _message.value = "The bookmark could not be added to the index"
                return@launch
            }
            hubDocumentId = documentId
            val added = runCatching {
                OpenedDocumentIndex.addBookmark(
                    getApplication(),
                    documentId,
                    pageIndex.coerceAtLeast(0),
                    label.trim().takeIf { it.isNotBlank() },
                    color
                )
            }
            if (added.isFailure) {
                _message.value = added.exceptionOrNull()?.message
                    ?: "The bookmark could not be added"
                return@launch
            }
            if (!AppSettings.bookmarkOutlineEnabled(getApplication())) {
                _message.value = "Bookmark added"
                return@launch
            }
            val written = withContext(Dispatchers.IO) {
                AnnotationEditor(getApplication()).syncBookmarks(documentId)
            }
            _message.value = written.fold(
                onSuccess = { saved ->
                    if (saved) "Bookmark added and the PDF outline was written" else "Bookmark added"
                },
                onFailure = { error ->
                    "Bookmark added; the outline could not be written: " +
                        (error.message ?: "unknown error")
                }
            )
            if (written.isSuccess) loadVersions()
        }
    }

    /** Writes a stored pre-save version back over the current file. */
    fun restoreVersion(id: String) {
        val location = _state.value.sourceLocation.ifBlank { currentPath }
        val target = saveTarget(location)
        if (target == null) {
            _message.value = "This file is read-only here; a previous version cannot be restored in place."
            return
        }
        if (saveInFlight) return
        val key = documentOriginKey.ifBlank { location }
        val expectation = SaveExpectation(_state.value.pageCount)
        saveInFlight = true
        _save.update { it.copy(status = ViewerSaveStatus.SAVING) }
        displayNameOverride = currentDisplayName
        closeCurrentDocument()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val store = VersionStore(getApplication(), key)
                    val bytes = store.read(id).getOrThrow()
                    val current = target.read().getOrThrow()
                    saveCoordinator.save(
                        target = target,
                        originalBytes = current,
                        editedBytes = bytes,
                        expectation = expectation,
                        mode = SaveWriteMode.FULL_REWRITE,
                        versions = store,
                        overwriteConflict = true
                    ).getOrThrow()
                }
            }
            saveInFlight = false
            result.fold(
                onSuccess = {
                    _save.update {
                        it.copy(status = ViewerSaveStatus.SAVED, message = "Previous version restored")
                    }
                    _message.value = "Previous version restored"
                    openWith("")
                    loadVersions()
                },
                onFailure = { error ->
                    _save.update {
                        it.copy(
                            status = ViewerSaveStatus.ERROR,
                            message = error.message ?: "Could not restore the version"
                        )
                    }
                    _message.value = error.message ?: "Could not restore the version"
                    openWith("")
                }
            )
        }
    }

    fun clearVersions() {
        val key = documentOriginKey.ifBlank {
            _state.value.sourceLocation.ifBlank { currentPath }
        }
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching { VersionStore(getApplication(), key).clear().getOrThrow() }
                    .getOrDefault(0)
            }
            _save.update { it.copy(versions = emptyList()) }
            _message.value = if (removed == 0) {
                "No stored versions to clear"
            } else {
                "Cleared $removed stored version${if (removed == 1) "" else "s"}"
            }
        }
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
        resetInkState()
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
        presentCache.clear()
        layouts.clear()
        _bitmaps.value = emptyMap()
        _plans.value = emptyMap()
        runCatching { document?.close() }
        document = null
        pages = emptyList()
        _pageBoxes.value = emptyList()
        fitGeneration++
        fitJob?.cancel()
        _fit.value = FitContentState()
        _viewBoxes.value = emptyList()
        resetReflowState()
        // T68b: layer state belongs to the document that was open.
        annotationReportJob?.cancel()
        annotationReportJob = null
        _annotationFilterReport.value = null
        _annotationLayers.value = AnnotationLayersState.SHOW_ALL
        _isLayersSheetOpen.value = false
    }

    /** Text layout of one page; must be called on the document dispatcher. */
    private fun layoutFor(index: Int): PageLayout? {
        layouts.get(index)?.let { return it }
        val page = pages.getOrNull(index) ?: return null
        val layout = page.layout()
        layouts.put(index, layout)
        return layout
    }

    private fun resetInkState() {
        inkUndo.clear()
        inkRedo.clear()
        inkLoaded = false
        inkNextId = 1L
        inkActionBase = null
        inkMoveBase = null
        inkMoveAnchor = null
        inkGesturePage = null
        inkEraserOverride = false
        _ink.value = InkUiState()
    }

    private fun clearTextState() {
        searchJob?.cancel()
        _search.value = ViewerSearchState()
        clearRegex()
        _selection.value = null
        selectionAnchor = null
        layouts.clear()
        fieldsLoaded = false
        _edit.value = ViewerEditState()
        resetInkState()
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
        _redaction.value = RedactionUiState()
        nextRedactionMarkId = 1L
        _sanitize.value = SanitizeUiState()
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

        /** The small render the T62 look suggestion measures (a few hundred KB). */
        const val LOOK_HINT_PIXEL_WIDTH: Int = 360

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
