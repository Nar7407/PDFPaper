package com.pdfpaper.app.ui.viewer

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.render.toArgb
import com.pdfpaper.app.data.CustomStampStore
import com.pdfpaper.app.data.ReadingMode
import com.pdfpaper.app.data.ReadingOrientation
import com.pdfpaper.app.data.readingOffsetPx
import com.pdfpaper.app.edit.EditFontFamily
import com.pdfpaper.app.edit.EditableBounds
import com.pdfpaper.app.edit.EditableHandle
import com.pdfpaper.app.edit.EditableObject
import com.pdfpaper.app.edit.EditableObjectType
import com.pdfpaper.app.edit.EditableStyle
import com.pdfpaper.app.edit.EditingRuntime
import com.pdfpaper.app.input.ViewerKeyRouter
import com.pdfpaper.app.office.OfficeCache
import com.pdfpaper.app.ui.PasswordDialog
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.redact.RedactionBusyDialog
import com.pdfpaper.app.ui.redact.RedactionConfirmDialog
import com.pdfpaper.app.ui.redact.RedactionPanel
import com.pdfpaper.app.ui.redact.RedactionSheet
import com.pdfpaper.app.ui.redact.RedactionVerificationDialog
import com.pdfpaper.app.ui.search.RegexPanel
import com.pdfpaper.app.ui.search.RegexResults
import com.pdfpaper.app.ui.search.RegexUiState
import com.pdfpaper.app.ui.search.RegexScope
import com.pdfpaper.app.ui.search.regexFileName
import com.pdfpaper.app.ui.search.rowsToCsv
import com.pdfpaper.engine.text.RegexFlag
import com.pdfpaper.app.ui.components.findActivity
import com.pdfpaper.app.ui.present.PresenterOverlay
import com.pdfpaper.app.ui.safety.SafetyBanner
import com.pdfpaper.app.ui.safety.SafetyViewModel
import com.pdfpaper.app.ui.snip.SnipOverlay
import com.pdfpaper.app.ui.snip.SnipViewModel
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.app.ui.viewer.ink.InkFocusOverlay
import com.pdfpaper.app.ui.viewer.ink.InkGeometry
import com.pdfpaper.app.ui.viewer.ink.InkInputPolicy
import com.pdfpaper.app.ui.viewer.ink.InkPalette
import com.pdfpaper.app.ui.viewer.ink.InkSample
import com.pdfpaper.app.ui.viewer.ink.InkStroke
import com.pdfpaper.app.ui.viewer.ink.InkToolMode
import com.pdfpaper.app.ui.viewer.ink.InkTransform
import com.pdfpaper.app.ui.viewer.ink.InkUiState
import com.pdfpaper.app.ui.crop.PendingCrop
import com.pdfpaper.app.ui.viewer.fit.FitContentSheet
import com.pdfpaper.app.ui.viewer.flow.ColumnFlowOverlay
import com.pdfpaper.app.ui.viewer.flow.FlowPosition
import com.pdfpaper.app.ui.viewer.reflow.ReflowHintBanner
import com.pdfpaper.app.ui.viewer.reflow.ReflowOverlay
import com.pdfpaper.app.ui.viewer.reflow.ReflowPosition
import com.pdfpaper.app.ui.viewer.reflow.offsetForFraction
import com.pdfpaper.app.ui.viewer.reflow.pageFraction
import com.pdfpaper.app.ui.viewer.turn.TurnAction
import com.pdfpaper.app.ui.motion.LocalReduceMotion
import com.pdfpaper.app.ui.motion.NoFling
import com.pdfpaper.app.ui.theme.LocalSunlightMode
import com.pdfpaper.app.ui.viewer.turn.TurnAnimation
import com.pdfpaper.app.ui.viewer.turn.TurnDirection
import com.pdfpaper.app.ui.viewer.turn.TurnKeys
import com.pdfpaper.app.ui.viewer.turn.TurnMode
import com.pdfpaper.app.ui.viewer.turn.TurnMotion
import com.pdfpaper.app.ui.viewer.turn.TurnPolicy
import com.pdfpaper.app.ui.viewer.turn.TurnPosition
import com.pdfpaper.app.ui.viewer.turn.TurnRepeat
import com.pdfpaper.app.ui.viewer.turn.TurnScroll
import com.pdfpaper.app.ui.viewer.turn.TurnSettings
import com.pdfpaper.app.ui.viewer.turn.TurnSystem
import com.pdfpaper.app.ui.viewer.turn.TurnZones
import com.pdfpaper.app.ui.tools.ToolDefinition
import com.pdfpaper.app.ui.tools.ToolRegistry
import com.pdfpaper.app.ui.tools.ToolSection
import com.pdfpaper.safety.PdfSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.pdfpaper.engine.annot.AnnotationSpec
import com.pdfpaper.engine.annot.HighlightSpec
import com.pdfpaper.engine.annot.InkSpec
import com.pdfpaper.engine.annot.NoteSpec
import com.pdfpaper.engine.annot.ReplySpec
import com.pdfpaper.engine.annot.StampSpec
import com.pdfpaper.engine.annot.StatusSpec
import com.pdfpaper.engine.annot.UnderlineSpec
import com.pdfpaper.engine.annot.InkTool
import com.pdfpaper.engine.form.FieldKind
import com.pdfpaper.engine.form.PdfField
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.links.LinkTarget
import com.pdfpaper.engine.render.PageBox
import com.pdfpaper.engine.render.PageRenderPlan
import com.pdfpaper.engine.text.TextRect
import com.pdfpaper.app.speech.ReadAloudHub
import com.pdfpaper.app.ui.viewer.speech.ReadAloudBar
import com.pdfpaper.app.ui.viewer.speech.ReadAloudSheet
import com.pdfpaper.app.ui.viewer.speech.speechHighlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 6f

/** Task T58: a double tap in the centre zooms to this factor (and back to 1). */
private const val DOUBLE_TAP_ZOOM = 2f

/** The text object being created or edited, plus where it belongs. */
private data class TextTarget(
    val pageIndex: Int,
    val bounds: EditableBounds,
    val existing: EditableObject? = null
)

/**
 * Continuous page viewer (T9) with text search and selection (T11): a
 * LazyColumn of page bitmaps rendered in the background by [ViewerViewModel],
 * with pinch-to-zoom and explicit zoom buttons. Renders are requested at the
 * width the page is actually drawn at, so a zoom change re-renders only the
 * visible pages. Search hits and the current selection are drawn over the
 * bitmap in page space, and a tap (or long-press then drag) selects the word
 * under the finger.
 */
@OptIn(ExperimentalFoundationApi::class) // rememberSnapFlingBehavior (T58)
@Composable
fun ViewerScreen(
    path: String,
    displayName: String? = null,
    /**
     * Task T38b: the source the reader opened, used as the document's identity
     * so the reading session survives the cache copy the viewer reads.
     */
    origin: String? = null,
    startInEditMode: Boolean = false,
    startWithForms: Boolean = false,
    startEditTool: String? = null,
    startPanel: String? = null,
    /** Task T50: open at this zero-based page and run [startQuery] there. */
    startPageIndex: Int = 0,
    startQuery: String? = null,
    /**
     * Task T56: `l,b,r,t` in PDF user space of an annotation the hub asked to
     * show. It is drawn as a temporary outline on [startPageIndex] and fades
     * after a few seconds, so opening a row from the hub points at the mark.
     */
    startHighlight: String? = null,
    /**
     * Task T38b: reports the page and pixel offset of the **first laid-out
     * frame**, before the reader has touched anything. It exists so a test can
     * prove the restored position was already on screen instead of a page-1
     * frame that scrolled away.
     */
    onFirstFrame: (pageIndex: Int, offsetPx: Int) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
    onOrganize: (path: String, toolId: String?) -> Unit = { _, _ -> },
    onOpenInfo: (String) -> Unit = {},
    onNewPdf: () -> Unit = {},
    onScan: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** Opens the static safety report for the current document (T49). */
    onOpenSafety: (path: String, displayName: String?) -> Unit = { _, _ -> },
    /** Opens a document tool (compress, watermark, page numbers, Bates) for [path]. */
    onOpenTool: (toolId: String, path: String) -> Unit = { _, _ -> },
    viewModel: ViewerViewModel = viewModel()
) {
    val safetyViewModel: SafetyViewModel = viewModel()
    val safety by safetyViewModel.state.collectAsState()
    // T60: the snip tool's own state holder (it renders and detects off the
    // main thread, so it keeps its work across a configuration change).
    val snipViewModel: SnipViewModel = viewModel()
    val snipState by snipViewModel.state.collectAsState()
    val state by viewModel.state.collectAsState()
    val pageBoxes by viewModel.pageBoxes.collectAsState()
    val viewBoxes by viewModel.viewBoxes.collectAsState()
    val fit by viewModel.fit.collectAsState()
    // T57: with fit content on, the drawn page is its content box, so every
    // overlay (selection, ink, links, scroll targets) uses the same geometry.
    val boxes = if (viewBoxes.size == pageBoxes.size && viewBoxes.isNotEmpty()) {
        viewBoxes
    } else {
        pageBoxes
    }
    val bitmaps by viewModel.bitmaps.collectAsState()
    val plans by viewModel.plans.collectAsState()
    val search by viewModel.search.collectAsState()
    val regexSheet by viewModel.regex.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val edit by viewModel.edit.collectAsState()
    val redaction by viewModel.redaction.collectAsState()
    val ink by viewModel.ink.collectAsState()
    val objectEdit by viewModel.objectEdit.collectAsState()
    val export by viewModel.export.collectAsState()
    val saveState by viewModel.save.collectAsState()
    val message by viewModel.message.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val commentsLoading by viewModel.commentsLoading.collectAsState()
    val commentsError by viewModel.commentsError.collectAsState()
    val commentReplies by viewModel.commentReplies.collectAsState()
    val commentStatuses by viewModel.commentStatuses.collectAsState()
    val signatures by viewModel.signatures.collectAsState()
    val signing by viewModel.signing.collectAsState()
    val ocr by viewModel.ocr.collectAsState()
    // T33/T33b: the reflow overlay, its extracted pages and rendered regions.
    val reflow by viewModel.reflow.collectAsState()
    val reflowRegions by viewModel.reflowRegions.collectAsState()
    // T59: the column-flow overlay and its rendered segments.
    val flow by viewModel.flow.collectAsState()
    val flowBitmaps by viewModel.flowBitmaps.collectAsState()
    // T32/T32b: the resolved page theme and its change counter.
    val pageTheme by viewModel.pageTheme.collectAsState()
    val themeGeneration by viewModel.themeGeneration.collectAsState()
    // T62: the document's look (filter and sliders) and its suggestion.
    val look by viewModel.look.collectAsState()
    val lookFromDocument by viewModel.lookFromDocument.collectAsState()
    val lookHint by viewModel.lookHint.collectAsState()
    // T63: the E-Ink profile actually in force for this document.
    val eink by viewModel.eink.collectAsState()
    // T63: reduce motion and direct sunlight are app-level display choices.
    val reduceMotion = LocalReduceMotion.current
    val sunlight = LocalSunlightMode.current
    // T38b: the orientation the reader chose for this document.
    val readingOrientation by viewModel.readingOrientation.collectAsState()
    val systemDark = isSystemInDarkTheme()
    // T62: the surface behind the page is the look's paper, exactly what the
    // renderer clears its bitmap to, so no light edge shows around a page.
    val pageBackground = remember(pageTheme, look) { lookBackground(look, pageTheme) }

    // T38b: the restored zoom is used for the very first frame, so the first
    // page is already drawn at the reader's size. The override stays 0 until
    // the reader zooms; a restored value is never overwritten by a default.
    var zoomOverride by rememberSaveable { mutableFloatStateOf(0f) }
    val zoom = if (zoomOverride <= 0f) state.startZoom else zoomOverride
    var showSearch by rememberSaveable { mutableStateOf(!startQuery.isNullOrBlank()) }
    var query by rememberSaveable { mutableStateOf(startQuery.orEmpty()) }
    var showForms by rememberSaveable { mutableStateOf(startWithForms) }
    var noteTarget by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    var commentTarget by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    var commentPlacement by remember { mutableStateOf(false) }
    var textTarget by remember { mutableStateOf<TextTarget?>(null) }
    var pendingImageUri by remember { mutableStateOf<Uri?>(null) }
    var replaceImageTarget by remember { mutableStateOf<EditableObject?>(null) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var showLook by rememberSaveable { mutableStateOf(false) }
    var showEink by rememberSaveable { mutableStateOf(false) }
    var showFitContent by rememberSaveable { mutableStateOf(false) }
    var activeToolId by rememberSaveable { mutableStateOf<String?>(null) }
    var showComments by rememberSaveable { mutableStateOf(false) }
    var showStamps by rememberSaveable { mutableStateOf(false) }
    // T65: the regex sheet (document scope), opened from the find row.
    var showRegex by rememberSaveable { mutableStateOf(false) }
    // T65: the redaction hand-off opens T28's panel on its Find tab; the
    // confirmation is required before any redaction bytes are built.
    var redactionPanel by rememberSaveable { mutableStateOf(RedactionPanel.FIND) }
    var showRedactionConfirm by remember { mutableStateOf(false) }
    var showSignatures by rememberSaveable { mutableStateOf(false) }
    var showOcr by rememberSaveable { mutableStateOf(false) }
    // T60: the marquee snip tool. It is an overlay over the whole viewer, so it
    // works the same in page, column-flow and reflow modes.
    var snipActive by rememberSaveable { mutableStateOf(false) }
    var snipPath by rememberSaveable { mutableStateOf<String?>(null) }
    var showSignOptions by rememberSaveable { mutableStateOf(false) }
    var showVisualSignature by rememberSaveable { mutableStateOf(false) }
    var showDigitalSignature by rememberSaveable { mutableStateOf(false) }
    var signaturePlacement by remember { mutableStateOf<SignaturePlacementUi?>(null) }
    var pendingSignature by remember { mutableStateOf<PendingSignature?>(null) }
    // Device-lock confirmation: a pending signing action waits for the system
    // credential screen, or for the explicit fallback when no lock is set.
    var pendingDeviceAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showDeviceFallback by remember { mutableStateOf(false) }
    var stampPlacement by remember { mutableStateOf<StampPlacement?>(null) }
    var linkCard by remember { mutableStateOf<LinkCardRequest?>(null) }
    var pinnedLink by remember { mutableStateOf<LinkTarget?>(null) }
    var externalLink by remember { mutableStateOf<ExternalLinkRequest?>(null) }
    var linkBackStack by remember { mutableStateOf<List<ReadingPosition>>(emptyList()) }
    // Annotation count when the current tool opened, so Cancel can undo its work.
    var toolAnnotationStart by remember { mutableIntStateOf(0) }
    var saveChoice by remember { mutableStateOf<ViewerExport?>(null) }
    var pendingRegexExport by remember { mutableStateOf<RegexUiState?>(null) }
    var showVersions by remember { mutableStateOf(false) }
    // Task T53: presentation mode is an overlay over the open document.
    var presenting by rememberSaveable(path) { mutableStateOf(false) }
    var presentStartPage by rememberSaveable { mutableIntStateOf(0) }
    // Task T56: the annotation the hub opened this document for.
    var focusMark by remember(path) { mutableStateOf<Pair<Int, PdfRect>?>(null) }
    // Task T56: the page a bookmark dialog is open for, if any.
    var bookmarkTarget by remember { mutableStateOf<Int?>(null) }

    val context = LocalContext.current

    // ---- Task T58: one-handed reading ------------------------------------
    var turnPrefs by remember { mutableStateOf(AppSettings.turnSettings(context)) }
    // T63: reduce motion pins the reading mode to whole-page snaps and makes
    // every turn instant; the stored preferences are left untouched.
    val turn = if (reduceMotion) {
        turnPrefs.copy(mode = TurnMode.PAGE_SNAP, animation = TurnAnimation.INSTANT)
    } else {
        turnPrefs
    }
    var toolbarsVisible by rememberSaveable { mutableStateOf(true) }
    var pageSurfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var showVolumeHint by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val animationsRemoved = remember(context) { TurnSystem.animationsRemoved(context) }
    val hapticsEnabled = remember(context) { TurnSystem.hapticsEnabled(context) }

    // ---- Task T63: ghosting and direct sunlight --------------------------
    var flashPhase by remember { mutableIntStateOf(0) }
    var flashTick by remember { mutableIntStateOf(0) }
    var turnsSinceClear by remember { mutableIntStateOf(0) }
    var autoClearTurns by remember { mutableIntStateOf(AppSettings.einkAutoClearTurns(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.autosaveOnPause()
                // Settings can change while a document is open (the reader may
                // have gone to Settings and come back), so re-read on return.
                Lifecycle.Event.ON_START -> {
                    turnPrefs = AppSettings.turnSettings(context)
                    autoClearTurns = AppSettings.einkAutoClearTurns(context)
                    viewModel.refreshPageTheme()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val author = AppSettings.authorName(context)

    // T64: the reading runs in the foreground service; the screen only shows
    // what the hub says it is doing and sends it commands.
    val readAloud by ReadAloudHub.state.collectAsState()
    var showReadAloudSheet by rememberSaveable(path) { mutableStateOf(false) }
    var speechOfferDismissed by rememberSaveable(path) { mutableStateOf(false) }
    // T34: the paragraph reached is written whenever it changes, so a killed
    // process loses at most the paragraph being spoken.
    LaunchedEffect(readAloud.active, readAloud.pageIndex, readAloud.paragraphIndex) {
        if (readAloud.active) {
            viewModel.saveSpeechPosition(readAloud.pageIndex, readAloud.paragraphIndex)
        }
    }
    LaunchedEffect(readAloud.active) {
        if (!readAloud.active) showReadAloudSheet = false
    }

    val demoEngine = EditingRuntime.isDemo(context)
    val activeToolName = activeToolId?.let { ToolRegistry.byId(it)?.name }

    // A PDF produced from a .docx/.pptx carries a sidecar with the conversion
    // notes; its presence is what turns the simplified-view banner on.
    val converted = remember(path) { OfficeCache.read(path) }
    var bannerDismissed by rememberSaveable(path) { mutableStateOf(false) }
    var safetyDismissed by rememberSaveable(path) { mutableStateOf(false) }
    val safetyBannerEnabled = remember(path) { AppSettings.showSafetyBanner(context) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val replace = replaceImageTarget
            if (replace != null) {
                viewModel.replaceImageObject(replace.id, uri.toString())
                replaceImageTarget = null
            } else {
                pendingImageUri = uri
                viewModel.setObjectTool(ObjectEditTool.IMAGE)
            }
        } else {
            replaceImageTarget = null
        }
    }

    val pickNewImage = {
        replaceImageTarget = null
        pendingImageUri = null
        imagePicker.launch("image/*")
    }

    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenWidthPx = with(density) { screenWidthDp.dp.toPx() }
    // Render resolution follows a coarse zoom step so a pinch does not queue a
    // render per frame; the on-screen width follows the smooth value.
    val renderZoom = (zoom * 10f).roundToInt() / 10f
    val contentWidth = with(density) { (screenWidthDp.dp * zoom).coerceAtLeast(screenWidthDp.dp) }

    // T38b: the page list is *created* at the restored page and offset, so the
    // first frame the reader sees is their own spot - there is no page 1 to
    // scroll away from. The token rebuilds the list once per document; a
    // rotation keeps the same token, so the exact saved scroll position is
    // restored instead of being re-estimated.
    val startPage = state.startPage
    val startOffsetPx = remember(state.restoreToken) {
        val box = boxes.getOrNull(startPage)
        val aspect = if (box != null && box.rotatedWidth > 0.0 && box.rotatedHeight > 0.0) {
            (box.rotatedWidth / box.rotatedHeight).toFloat()
        } else {
            612f / 792f
        }
        // The offset is estimated from the page's own aspect before it has been
        // measured; it is a few pixels off at most, and it is the difference
        // between showing the right paragraph and showing page 1.
        val pageWidthPx = with(density) { (contentWidth - 16.dp).toPx() }.roundToInt()
        readingOffsetPx(state.startFraction, pageWidthPx, aspect)
    }
    val listState = rememberSaveable(
        state.restoreToken,
        saver = LazyListState.Saver
    ) { LazyListState(startPage, startOffsetPx) }
    val hScroll = rememberScrollState()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // ---- Task T58: page turning ------------------------------------------
    // Turning steps aside while another mode owns the finger: annotating,
    // drawing, selecting text, filling a form or placing a mark.
    val placingMark = stampPlacement != null || signaturePlacement != null ||
        pendingSignature != null || commentPlacement
    val inkMode = edit.active && edit.tool == AnnotationTool.INK
    val turningEnabled = TurnPolicy.enabled(
        settings = turn,
        editing = edit.active,
        inkMode = inkMode,
        selectionActive = selection != null,
        formFilling = showForms,
        placingMark = placingMark
    )
    val edgeLayerActive = TurnPolicy.edgeLayerActive(
        settings = turn,
        editing = edit.active,
        inkMode = inkMode,
        selectionActive = selection != null,
        formFilling = showForms,
        placingMark = placingMark
    )
    // Horizontal paging needs the whole width for a page, so a zoomed-in page
    // keeps the vertical list (where panning still works) rather than fighting
    // the pager for the same drag.
    val horizontalMode = turn.mode == TurnMode.HORIZONTAL && zoom <= 1.001f
    val hListState = rememberSaveable(
        state.restoreToken,
        saver = LazyListState.Saver
    ) { LazyListState(startPage, 0) }
    val snapFling = rememberSnapFlingBehavior(listState)
    val defaultFling = ScrollableDefaults.flingBehavior()
    // The page the reader is actually on. It drives the position readout and
    // the volume keys, and it is the page under the finger for an edge tap.
    val readingPage by remember(horizontalMode) {
        derivedStateOf {
            if (horizontalMode) {
                hListState.firstVisibleItemIndex
            } else {
                // A visible item reports its full size, so the part that is
                // really on screen has to be cut out of the viewport bounds;
                // otherwise every page in view ties and the top one wins.
                val info = listState.layoutInfo
                val start = info.viewportStartOffset
                val end = info.viewportEndOffset
                TurnPosition.currentPage(
                    info.visibleItemsInfo.map { item ->
                        val top = maxOf(item.offset, start)
                        val bottom = minOf(item.offset + item.size, end)
                        item.index to (bottom - top).coerceAtLeast(0)
                    }
                )
            }
        }
    }
    val animateTurns =
        TurnMotion.effective(turn.animation, animationsRemoved, reduceMotion) ==
            TurnAnimation.ANIMATED
    val tick = {
        if (turn.hapticTick && hapticsEnabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    // T33: hand the reading spot across in both directions. Page view reports
    // the top of the viewport as a page and a fraction of that page; reflow
    // reports the page under the viewport and the fraction scrolled into it.
    val openReflowAt: (ReflowPosition) -> Unit = { position ->
        if (state.pageCount > 0) {
            showSearch = false
            selection?.let { viewModel.clearSelection() }
            viewModel.openReflow(position)
        }
    }
    val openReflowHere: () -> Unit = {
        val info = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == readingPage }
        val offset = when {
            info == null -> 0
            info.offset < 0 -> -info.offset
            else -> 0
        }
        val height = info?.size ?: 0
        openReflowAt(ReflowPosition(readingPage, pageFraction(offset, height)))
    }
    val exitReflow: () -> Unit = {
        val position = viewModel.closeReflow()
        scope.launch {
            val last = (state.pageCount - 1).coerceAtLeast(0)
            val page = position.page.coerceIn(0, last)
            if (horizontalMode) {
                hListState.scrollToItem(page)
                return@launch
            }
            listState.scrollToItem(page)
            withFrameNanos { }
            val height = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == page }?.size
                ?: bitmaps[page]?.height
                ?: 0
            if (height > 0 && position.fraction > 0f) {
                listState.scrollToItem(page, offsetForFraction(position.fraction, height))
            }
        }
    }
    // T59: column flow opens where the reader is and hands the page back on exit.
    val openFlowAt: (FlowPosition) -> Unit = { position ->
        if (state.pageCount > 0) {
            showSearch = false
            selection?.let { viewModel.clearSelection() }
            viewModel.openColumnFlow(position)
        }
    }
    val openFlowHere: () -> Unit = { openFlowAt(FlowPosition(readingPage, 0)) }
    val exitFlow: () -> Unit = {
        val position = viewModel.closeColumnFlow()
        scope.launch {
            val last = (state.pageCount - 1).coerceAtLeast(0)
            val page = position.page.coerceIn(0, last)
            if (horizontalMode) {
                hListState.scrollToItem(page)
            } else {
                listState.scrollToItem(page)
            }
        }
    }
    // [from] is the page the finger (or the readout) was on. Turning is
    // relative to it, not to the top of the viewport: on the last page the top
    // item is still the page above, and going back from it must move one page.
    val turnPage: (TurnDirection, Int?) -> Unit = { direction, from ->
        if (flow.active) {
            // T59: one segment at a time, so the same keys and edges walk the
            // reading order instead of whole pages.
            if (turningEnabled) tick()
            viewModel.flowStep(if (direction == TurnDirection.FORWARD) 1 else -1)
        } else if (turningEnabled) {
            tick()
            val forward = direction == TurnDirection.FORWARD
            scope.launch {
                val last = (state.pageCount - 1).coerceAtLeast(0)
                val base = (from ?: readingPage).coerceIn(0, last)
                when {
                    horizontalMode -> {
                        val target = (base + if (forward) 1 else -1).coerceIn(0, last)
                        if (animateTurns) {
                            hListState.animateScrollToItem(target)
                        } else {
                            hListState.scrollToItem(target)
                        }
                    }
                    // Page snap always moves exactly one page: a partial screen
                    // step would land between two snap points.
                    turn.action == TurnAction.PAGE || turn.mode == TurnMode.PAGE_SNAP -> {
                        val target = (base + if (forward) 1 else -1).coerceIn(0, last)
                        if (animateTurns) {
                            listState.animateScrollToItem(target)
                        } else {
                            listState.scrollToItem(target)
                        }
                    }
                    else -> {
                        val pageHeight = bitmaps[base]?.height?.toFloat()
                            ?: (pageSurfaceSize.height * 1.4f)
                        val step = TurnScroll.stepPx(
                            viewportHeightPx = pageSurfaceSize.height.toFloat(),
                            pageHeightPx = pageHeight,
                            lines = turn.overlapLines
                        )
                        val delta = if (forward) step else -step
                        if (animateTurns) {
                            listState.animateScrollBy(delta)
                        } else {
                            listState.scrollBy(delta)
                        }
                    }
                }
            }
        }
    }
    // The key handler outlives a recomposition, so it must read the newest
    // turn lambda rather than the one from the composition that installed it.
    val currentTurnPage by rememberUpdatedState(turnPage)

    // A dialog or a text field keeps the volume keys for itself: a note being
    // typed must not page the document underneath it.
    val dialogOpen = saveChoice != null || saveState.conflict ||
        saveState.signatureWarning != null ||
        showVersions || showTools || showComments || showStamps || showSignatures ||
        showOcr || showSignOptions || showVisualSignature || showDigitalSignature ||
        showDeviceFallback || showFitContent || noteTarget != null || commentTarget != null ||
        textTarget != null || linkCard != null || externalLink != null || pinnedLink != null ||
        bookmarkTarget != null || showSearch || showVolumeHint
    DisposableEffect(turn, turningEnabled, presenting, dialogOpen) {
        val installed: ((KeyEvent) -> Boolean)? =
            if (!presenting && turningEnabled && turn.volumeKeys && !dialogOpen) {
                var lastEventTime = 0L
                val handler: (KeyEvent) -> Boolean = { event ->
                    val direction = TurnKeys.direction(event.keyCode, turn)
                    when {
                        direction == null -> false
                        // The release is consumed too, or the system would still
                        // see a half press and change the volume.
                        event.action == KeyEvent.ACTION_UP -> true
                        !TurnRepeat.accepts(event.repeatCount, event.eventTime, lastEventTime) -> true
                        else -> {
                            lastEventTime = event.eventTime
                            currentTurnPage(direction, null)
                            true
                        }
                    }
                }
                handler
            } else {
                null
            }
        ViewerKeyRouter.handler = installed
        onDispose {
            if (ViewerKeyRouter.handler === installed) ViewerKeyRouter.handler = null
        }
    }
    // The one-time offer to claim the volume keys, shown shortly after the
    // first page appears so it never competes with the document opening.
    LaunchedEffect(state.pageCount, state.isLoading, state.error) {
        if (
            state.pageCount > 0 &&
            !state.isLoading &&
            state.error == null &&
            !AppSettings.turnVolumeHintShown(context)
        ) {
            kotlinx.coroutines.delay(2_500)
            showVolumeHint = true
        }
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) viewModel.completeExport(uri) else viewModel.cancelExport()
    }
    // T65: regex exports (CSV/TXT) write to the SAF without touching the save
    // lifecycle; the sheet remembers what it is about to write.
    val createDocumentForRegex = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val snapshot = pendingRegexExport ?: return@rememberLauncherForActivityResult
        pendingRegexExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        val text = rowsToCsv(snapshot.visibleRows)
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray())
            }
        }
    }

    val keyguard = remember(context) {
        context.getSystemService(KeyguardManager::class.java)
    }
    val deviceCredentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingDeviceAction
        pendingDeviceAction = null
        if (result.resultCode == Activity.RESULT_OK) {
            action?.invoke()
        } else {
            viewModel.showMessage("Signing was not confirmed")
        }
    }
    fun confirmWithDeviceLock(action: () -> Unit) {
        val intent = runCatching {
            keyguard?.createConfirmDeviceCredentialIntent(
                "Confirm signing",
                "Unlock to sign this PDF"
            )
        }.getOrNull()
        if (intent == null) {
            pendingDeviceAction = action
            showDeviceFallback = true
        } else {
            pendingDeviceAction = action
            deviceCredentialLauncher.launch(intent)
        }
    }

    // T64: the reading asks the page into view once per request.
    LaunchedEffect(readAloud.scrollToken) {
        val page = readAloud.scrollPageIndex
        if (readAloud.scrollToken != 0L &&
            page in 0 until state.pageCount &&
            AppSettings.speechAutoScroll(context)
        ) {
            listState.scrollToItem(page)
        }
    }

    LaunchedEffect(path, displayName, origin, startPageIndex) {
        viewModel.load(path, displayName, origin, startPageIndex)
    }
    // The safety check runs after the document is open, in the background, so
    // it never delays the first page. It reads the source the viewer actually
    // opened (a pipe may already have been copied into the cache by then).
    LaunchedEffect(state.isLoading, state.error, state.requiresPassword, state.sourceLocation) {
        if (!state.isLoading && state.error == null && !state.requiresPassword) {
            val safetyPath = state.sourceLocation.ifBlank { path }
            if (safetyPath.isNotBlank()) safetyViewModel.scan(safetyPath)
        }
    }
    LaunchedEffect(path) {
        linkCard = null
        pinnedLink = null
        externalLink = null
        linkBackStack = emptyList()
        pendingSignature = null
        signaturePlacement = null
        showSignOptions = false
        showVisualSignature = false
        showDigitalSignature = false
    }
    // T32b: the theme depends on the document and on the system, so it is
    // re-resolved when either changes; the view model drops stale bitmaps.
    LaunchedEffect(path, systemDark) { viewModel.refreshPageTheme(systemDark) }
    LaunchedEffect(path, startInEditMode, startWithForms, startEditTool, state.isLoading, state.pageCount) {
        if (startInEditMode && !state.isLoading && state.error == null && state.pageCount > 0) {
            if (startEditTool.isNullOrBlank()) {
                viewModel.setEditMode(true)
            } else {
                viewModel.startObjectEditing(startEditTool)
                activeToolId = ToolRegistry.byViewerTool(startEditTool)?.id
            }
            if (startWithForms) showForms = true
        }
    }
    // An All tools entry can ask the viewer to open a panel directly.
    var openedStartPanel by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startPanel, state.isLoading, state.pageCount) {
        if (openedStartPanel || state.isLoading || state.error != null ||
            state.pageCount <= 0 || startPanel.isNullOrBlank()
        ) {
            return@LaunchedEffect
        }
        openedStartPanel = true
        when (startPanel) {
            "comments" -> showComments = true
            "stamps" -> showStamps = true
            "signatures" -> {
                showSignatures = true
                viewModel.loadSignatures()
            }
            "ocr" -> showOcr = true
            // T60: the All tools entry opens the snip overlay on the document
            // it just cached.
            "snip" -> {
                snipPath = state.sourceLocation.ifBlank { path }
                snipActive = true
            }
            "sign" -> showSignOptions = true
            // T59: the All tools entry opens column flow on the document it
            // just cached.
            "flow" -> openFlowHere()
            "read-aloud" -> viewModel.startReadAloud(readingPage)
            "present" -> {
                presentStartPage = startPageIndex
                presenting = true
            }
        }
    }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.dismissMessage()
    }
    // T60: a new snip path loads the document for the overlay. Loading is
    // cancelled by a second call, so a fast re-open cannot leave two renders.
    LaunchedEffect(snipPath) {
        snipPath?.let { snipViewModel.load(it) }
    }
    // ---- Task T38b: the reading session ----------------------------------
    // The list state was already created at the restored page, so there is no
    // page-1 frame to scroll away from; this block only handles what is not
    // geometry: a zoom reset per document, the saved mode and tool, and saving.
    LaunchedEffect(state.restoreToken) { zoomOverride = 0f }
    // "Fit content" is a mode the session remembers (T57). The view model
    // resolves it before the state says loaded; nothing to do here but keep the
    // sheet's own switches in sync, which it reads from the view model.
    var openedStartReflow by rememberSaveable(path) { mutableStateOf(false) }
    LaunchedEffect(state.restoreToken, state.isLoading, state.pageCount) {
        if (openedStartReflow || state.isLoading || state.error != null) {
            return@LaunchedEffect
        }
        val start = state.startReflow ?: return@LaunchedEffect
        if (state.pageCount <= 0) return@LaunchedEffect
        openedStartReflow = true
        viewModel.openReflow(start)
    }
    // T59: a session saved in column flow opens in column flow, on the same
    // segment. Detection is per page, so this costs one page's work.
    var openedStartFlow by rememberSaveable(path) { mutableStateOf(false) }
    LaunchedEffect(state.restoreToken, state.isLoading, state.pageCount) {
        if (openedStartFlow || state.isLoading || state.error != null) {
            return@LaunchedEffect
        }
        val start = state.startFlow ?: return@LaunchedEffect
        if (state.pageCount <= 0) return@LaunchedEffect
        openedStartFlow = true
        viewModel.openColumnFlow(start)
    }
    // The tool the reader had selected last comes back as a label, so the top
    // bar says what it said when the document was closed. A tool the catalogue
    // no longer knows is dropped rather than shown as a blank name.
    var restoredTool by rememberSaveable(path) { mutableStateOf(false) }
    LaunchedEffect(state.restoreToken, state.isLoading, state.pageCount) {
        if (restoredTool || state.isLoading || state.pageCount <= 0) return@LaunchedEffect
        restoredTool = true
        val id = state.startToolId ?: return@LaunchedEffect
        if (activeToolId == null && ToolRegistry.byId(id) != null) activeToolId = id
    }
    // Where the reader is right now, as a page plus how far into it.
    val currentFraction: () -> Float = {
        if (reflow.active || flow.active) {
            // Reflow reports its own fraction; a flow segment is exact, so the
            // page itself is the position.
            if (reflow.active) viewModel.reflowPositionNow().fraction else 0f
        } else {
            val info = if (horizontalMode) hListState.layoutInfo else listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == readingPage }
            if (item == null || item.size <= 0) {
                0f
            } else {
                pageFraction((info.viewportStartOffset - item.offset).coerceAtLeast(0), item.size)
            }
        }
    }
    val currentMode: () -> ReadingMode = {
        when {
            reflow.active -> ReadingMode.REFLOW
            flow.active -> ReadingMode.COLUMN_FLOW
            fit.mode != com.pdfpaper.app.ui.viewer.fit.FitContentMode.OFF -> ReadingMode.FIT_CONTENT
            else -> ReadingMode.PAGE
        }
    }
    // The values a save must read are the ones on screen when it runs, not the
    // ones captured when the effect started. In column flow the page is the
    // overlay's, not the page list's.
    val savePage: () -> Int = {
        if (flow.active) viewModel.flowPositionNow().page else readingPage
    }
    val saveZoom by rememberUpdatedState(zoom)
    val saveTool by rememberUpdatedState(activeToolId)
    val saveFraction by rememberUpdatedState(currentFraction)
    val saveMode by rememberUpdatedState(currentMode)
    val persistReading: () -> Unit = {
        val page = savePage()
        viewModel.saveReadingState(
            pageIndex = page,
            fraction = saveFraction(),
            zoom = saveZoom,
            mode = saveMode(),
            toolId = saveTool,
            flowOrder = if (flow.active) viewModel.flowPositionNow().order else 0
        )
    }
    // Debounced while reading: a scroll that settles for 700 ms is a position
    // worth keeping, and a fling does not write on every frame.
    LaunchedEffect(state.isLoading, state.pageCount) {
        if (state.isLoading || state.pageCount <= 0) return@LaunchedEffect
        snapshotFlow {
            Triple(savePage(), saveFraction(), saveZoom)
        }.collectLatest {
            delay(700)
            persistReading()
        }
    }
    // A backgrounded or closing viewer must not lose the last scroll, which the
    // debounce above may not have written yet.
    val readingLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(readingLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) persistReading()
        }
        readingLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            readingLifecycleOwner.lifecycle.removeObserver(observer)
            persistReading()
        }
    }
    // T38b: the page and offset of the first frame that is actually laid out.
    // A saved session is applied when the list is *created*, so this fires at
    // the reader's own page; a page-1 frame would mean the restore happened
    // after the first paint.
    var reportedFirstFrame by remember(state.restoreToken) { mutableStateOf(false) }
    LaunchedEffect(state.restoreToken, state.isLoading, state.pageCount, horizontalMode) {
        if (reportedFirstFrame || state.isLoading || state.pageCount <= 0) {
            return@LaunchedEffect
        }
        val list = if (horizontalMode) hListState else listState
        snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull()?.index }
            .filterNotNull()
            .first()
        reportedFirstFrame = true
        onFirstFrame(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
    }
    // The orientation the reader chose for this document. Setting it asks the
    // system to rotate; the activity is recreated, and the list state above is
    // restored from the saved instance state, so the same text stays on screen.
    val activity = remember(context) { context.findActivity() }
    // T63: the ghosting flash. A full black then white refresh is short
    // enough not to read as a flicker and long enough for the panel to settle.
    LaunchedEffect(flashTick) {
        if (flashTick <= 0) return@LaunchedEffect
        flashPhase = 1
        kotlinx.coroutines.delay(180)
        flashPhase = 2
        kotlinx.coroutines.delay(180)
        flashPhase = 0
    }
    // A page turn is the moment a reflective panel starts to ghost. Only the
    // automatic option counts; the Clear screen button is always available.
    LaunchedEffect(readingPage, eink.enabled, autoClearTurns) {
        if (!eink.enabled || autoClearTurns <= 0) {
            turnsSinceClear = 0
            return@LaunchedEffect
        }
        turnsSinceClear += 1
        if (turnsSinceClear >= autoClearTurns) {
            turnsSinceClear = 0
            flashTick += 1
        }
    }
    // T63: direct sunlight pins the reading window to full brightness. It is a
    // window attribute, so no permission is involved; the previous value is
    // restored when the viewer closes or the setting is turned off.
    DisposableEffect(activity, sunlight) {
        val window = activity?.window
        if (window == null || !sunlight) return@DisposableEffect onDispose { }
        val attributes = window.attributes
        val previous = attributes.screenBrightness
        attributes.screenBrightness = 1f
        window.attributes = attributes
        onDispose {
            val restore = window.attributes
            restore.screenBrightness = previous
            window.attributes = restore
        }
    }
    // The *declared* orientation, read from the manifest rather than from the
    // live activity: the system persists a requested orientation across the
    // recreation it causes, so asking the activity would make "Auto" sticky.
    val manifestOrientation = remember(activity) {
        activity?.let { act ->
            runCatching {
                act.packageManager
                    .getActivityInfo(act.componentName, 0)
                    .screenOrientation
            }.getOrNull()
        } ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    LaunchedEffect(activity, readingOrientation) {
        val act = activity ?: return@LaunchedEffect
        val target = when (readingOrientation) {
            ReadingOrientation.AUTO -> manifestOrientation
            ReadingOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ReadingOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (act.requestedOrientation != target) act.requestedOrientation = target
    }
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = manifestOrientation }
    }
    // Task T56: the hub asked for one annotation to be pointed at. The outline
    // is transient - it is a hint, not part of the document.
    LaunchedEffect(startHighlight, path) {
        val rect = com.pdfpaper.app.annot.parseBounds(startHighlight) ?: return@LaunchedEffect
        focusMark = startPageIndex to rect
        delay(4000)
        focusMark = null
    }
    // Debounced search: run once the user pauses typing. Closing Find clears
    // the highlights again. The first search from a library result prefers the
    // page that was tapped; later typing selects the first match again.
    var appliedSearchPage by rememberSaveable(path) { mutableStateOf(false) }
    LaunchedEffect(showSearch, query) {
        if (!showSearch) {
            viewModel.setSearchQuery("")
            return@LaunchedEffect
        }
        delay(250)
        viewModel.setSearchQuery(
            query,
            preferredPage = if (appliedSearchPage) null else startPageIndex
        )
        if (query.isNotBlank()) appliedSearchPage = true
    }
    // Ask for a destination once the engine has produced the new bytes (T55).
    // A writable source with "ask each time" offers Save in place first; a
    // read-only source (or "always save a copy") goes to the copy picker.
    LaunchedEffect(export) {
        val pending = export ?: return@LaunchedEffect
        // A highlight copy is never written over the open file (T65).
        if (pending.kind == ViewerExportKind.HIGHLIGHTS) {
            createDocument.launch(pending.suggestedName)
            return@LaunchedEffect
        }
        if (state.canSaveInPlace &&
            saveState.autosaveMode != com.pdfpaper.app.data.AutosaveMode.COPY
        ) {
            saveChoice = pending
        } else {
            createDocument.launch(pending.suggestedName)
        }
    }
    // Jump to whichever match is active.
    val activeMatch = search.activeMatch
    LaunchedEffect(activeMatch, reduceMotion) {
        if (activeMatch != null) {
            // T63: a jump is a page transition too; reduce motion lands on it.
            if (reduceMotion) {
                listState.scrollToItem(activeMatch.pageIndex)
            } else {
                listState.animateScrollToItem(activeMatch.pageIndex)
            }
        }
    }

    // Leaving the active tool keeps its work; cancelling drops what the tool
    // session added. Either way the viewer leaves edit mode.
    val closeTool = {
        activeToolId = null
        commentPlacement = false
        viewModel.setEditMode(false)
    }
    val cancelTool = {
        viewModel.trimAnnotationsTo(toolAnnotationStart)
        activeToolId = null
        commentPlacement = false
        viewModel.setEditMode(false)
    }
    // T33b: a page whose body text lands under 12 sp at the current zoom gets
    // one suggestion. It is only ever a suggestion: the viewer never switches
    // mode by itself, and "Not now" is remembered for this document.
    LaunchedEffect(readingPage, zoom, state.pageCount, reflow.active, reflow.hintDismissed) {
        if (reflow.active || state.pageCount == 0) return@LaunchedEffect
        delay(1500)
        val scale = plans[readingPage]?.scale ?: return@LaunchedEffect
        viewModel.checkReflowHint(readingPage, scale)
    }
    val activateTool: (ToolDefinition) -> Unit = { tool ->
        showTools = false
        when (tool.viewerAction) {
            "comments" -> showComments = true
            "stamps" -> showStamps = true
            "signatures" -> {
                showSignatures = true
                viewModel.loadSignatures()
            }
            "ocr" -> showOcr = true
            // T60: the snip tool works on a real File, so a content:// source is
            // materialised once, exactly like the other document tools.
            "snip" -> viewModel.withLocalDocument { local ->
                snipPath = local
                snipActive = true
            }
            "sign" -> {
                closeTool()
                showSignOptions = true
            }
            // T59: column flow is a full-screen overlay; it opens at the page
            // the reader is on and covers the page list behind it.
            "flow" -> openFlowHere()
            // T64: the reading starts where the reader is looking.
            "read-aloud" -> viewModel.startReadAloud(readingPage)
            else -> when (tool.section) {
                ToolSection.PAGES -> viewModel.withLocalDocument { local ->
                    onOrganize(local, tool.id)
                }
                ToolSection.EDIT, ToolSection.ANNOTATE -> {
                    toolAnnotationStart = edit.annotations.size
                    viewModel.startViewerTool(tool.viewerTool ?: "select")
                    activeToolId = tool.id
                }
                ToolSection.REDACT ->
                    // UI-4 is still being built; the redact screens are not
                    // wired into the viewer yet.
                    viewModel.showMessage("${tool.name} is coming soon")
                ToolSection.FORMS_SECURITY ->
                    if (tool.id == "safety") {
                        onOpenSafety(state.sourceLocation.ifBlank { path }, state.displayName)
                    } else if (tool.id == "fill-form") {
                        toolAnnotationStart = edit.annotations.size
                        viewModel.startViewerTool("select")
                        showForms = true
                        activeToolId = tool.id
                    } else {
                        viewModel.showMessage("${tool.name} is coming soon")
                    }
                ToolSection.DOCUMENT -> when (tool.id) {
                    "info" -> viewModel.withLocalDocument { local -> onOpenInfo(local) }
                    "safety" -> onOpenSafety(
                        state.sourceLocation.ifBlank { path },
                        state.displayName
                    )
                    "new-pdf" -> onNewPdf()
                    "scan" -> onScan()
                    "save", "save-as" -> viewModel.saveAll()
                    "share" -> sharePdf(
                        context,
                        state.sourceLocation.ifBlank { path },
                        state.displayName
                    )
                    // The document is already open, so these tools can work on
                    // it straight away. Content sources are materialised once
                    // because those tools still take a File.
                    "compress", "crop", "watermark", "page-numbers", "bates", "export-images" ->
                        viewModel.withLocalDocument { local -> onOpenTool(tool.id, local) }
                    "present" -> {
                        presentStartPage = listState.firstVisibleItemIndex
                        presenting = true
                    }
                    "reflow" -> openReflowHere()
                    else -> viewModel.showMessage("${tool.name} is coming soon")
                }
            }
        }
    }

    // ------------------------------------------------------ links (T36/T61)

    // Annotation, stylus, selection and form modes own the tap. The stylus
    // check lives in wordSelectionGestures so link taps stay finger-only.
    val linkTapEnabled = !edit.active &&
        !showForms &&
        !snipActive &&
        selection == null &&
        activeToolId == null &&
        stampPlacement == null &&
        signaturePlacement == null &&
        pendingSignature == null &&
        !commentPlacement

    val jumpToLink: (LinkTarget) -> Unit = { target ->
        if (target.pageIndex !in 0 until state.pageCount) {
            viewModel.showMessage("That target is outside this document")
        } else {
            linkBackStack = pushPosition(
                linkBackStack,
                ReadingPosition(
                    pageIndex = listState.firstVisibleItemIndex,
                    offsetPx = listState.firstVisibleItemScrollOffset,
                    horizontalPx = hScroll.value
                )
            )
            linkCard = null
            val measured = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == target.pageIndex }
                ?.size
            val horizontalPadding = with(density) { 16.dp.toPx() }
            val verticalPadding = with(density) { 12.dp.toPx() }
            val pageWidth = (with(density) { contentWidth.toPx() } - horizontalPadding)
                .coerceAtLeast(1f)
            val box = boxes.getOrNull(target.pageIndex)
            val aspect = if (box != null && box.rotatedWidth > 0.0 && box.rotatedHeight > 0.0) {
                (box.rotatedWidth / box.rotatedHeight).toFloat()
            } else {
                612f / 792f
            }
            val rowHeight = measured
                ?: ((pageWidth / aspect) + verticalPadding).roundToInt()
            val viewport = listState.layoutInfo.viewportSize.height
            val offset = linkScrollOffset(target.pageFractionTop, rowHeight, viewport)
            scope.launch {
                // T63: no animated scroll under reduce motion.
                if (reduceMotion) {
                    listState.scrollToItem(target.pageIndex, offset)
                } else {
                    listState.animateScrollToItem(target.pageIndex, offset)
                }
            }
        }
    }

    val restoreReadingPosition = {
        val (position, remaining) = popPosition(linkBackStack)
        if (position != null) {
            linkBackStack = remaining
            scope.launch {
                if (reduceMotion) {
                    listState.scrollToItem(position.pageIndex, position.offsetPx)
                } else {
                    listState.animateScrollToItem(position.pageIndex, position.offsetPx)
                }
                hScroll.scrollTo(position.horizontalPx)
            }
        }
    }

    val onLinkTap: (Int, Float, Float) -> Boolean = { index, fx, fy ->
        var handled = false
        if (linkTapEnabled) {
            val link = viewModel.linkAtFraction(index, fx, fy)
            if (link != null) {
                handled = true
                val uri = link.uri
                val file = link.file
                when {
                    uri != null -> externalLink = ExternalLinkRequest(
                        uri = uri,
                        safety = PdfSafety.inspectLink(uri, link.text)
                    )
                    file != null -> externalLink = ExternalLinkRequest(
                        file = file,
                        note = link.note ?: "This link points to another file."
                    )
                    AppSettings.linkTapBehavior(context) ==
                        com.pdfpaper.app.data.LinkTapBehavior.JUMP_DIRECTLY &&
                        link.targets.isNotEmpty() -> jumpToLink(link.targets.first())
                    else -> linkCard = LinkCardRequest(link)
                }
            }
        }
        handled
    }

    val openExternal: () -> Unit = {
        val request = externalLink
        externalLink = null
        val uriText = request?.uri
        val file = request?.file
        if (uriText != null) {
            val parsed = runCatching { Uri.parse(uriText) }.getOrNull()
            val scheme = parsed?.scheme?.lowercase()
            if (parsed == null || scheme !in setOf("http", "https", "mailto")) {
                viewModel.showMessage("This link type is not supported")
            } else {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, parsed)) }
                    .onFailure { viewModel.showMessage("No app can open this link") }
            }
        } else if (file != null) {
            viewModel.showMessage("Opening linked files is not supported yet")
        }
    }

    BackHandler(enabled = ink.focusPage != null) {
        viewModel.setInkFocusPage(null)
    }

    // T60: Back leaves the snip overlay before it touches the document.
    BackHandler(enabled = snipActive) {
        snipActive = false
        snipPath = null
    }

    // T59: Back leaves column flow and puts the reader back on that page.
    BackHandler(enabled = flow.active) { exitFlow() }

    // Back exits the sheet, then the active tool, and only then the document.
    BackHandler(
        enabled = showTools || activeToolId != null || commentPlacement ||
            stampPlacement != null || signaturePlacement != null ||
            pendingSignature != null || pinnedLink != null
    ) {
        when {
            showTools -> showTools = false
            signaturePlacement != null -> signaturePlacement = null
            pendingSignature != null -> pendingSignature = null
            stampPlacement != null -> stampPlacement = null
            pinnedLink != null -> pinnedLink = null
            else -> closeTool()
        }
    }

    saveChoice?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                saveChoice = null
                viewModel.cancelExport()
            },
            title = { Text("Save changes") },
            text = {
                Text(
                    "This file can be written back to its original location. " +
                        "Save in place replaces the original and keeps the last three " +
                        "pre-save versions; Save a copy keeps both."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveChoice = null
                        viewModel.saveExportInPlace()
                    }
                ) {
                    Text("Save in place")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        saveChoice = null
                        createDocument.launch(pending.suggestedName)
                    }
                ) {
                    Text("Save a copy")
                }
            }
        )
    }

    saveState.signatureWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelSignatureSave() },
            title = { Text("Existing signature") },
            text = { Text(warning) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmSignatureSave() }) {
                    Text("Save anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelSignatureSave() }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (saveState.conflict) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSaveMenu() },
            title = { Text("This file changed elsewhere") },
            text = {
                Text(
                    saveState.conflictMessage
                        ?: "The file was changed by another app after you opened it. " +
                        "Reload it, save your work as a copy, or overwrite the newer file."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.overwriteAfterConflict() }) {
                    Text("Overwrite anyway")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.reloadAfterConflict() }) {
                        Text("Reload")
                    }
                    TextButton(onClick = { viewModel.saveConflictAsCopy() }) {
                        Text("Save as copy")
                    }
                }
            }
        )
    }

    if (showVersions) {
        AlertDialog(
            onDismissRequest = { showVersions = false },
            title = { Text("Previous versions") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (saveState.versions.isEmpty()) {
                        Text("No stored versions for this document.")
                    } else {
                        Text(
                            "The last three pre-save copies are kept in app-private storage.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        saveState.versions.forEachIndexed { index, version ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (index == 0) "Newest" else "Version ${index + 1}",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        java.time.Instant.ofEpochMilli(version.savedAt)
                                            .atZone(java.time.ZoneId.systemDefault())
                                            .format(
                                                java.time.format.DateTimeFormatter.ofPattern(
                                                    "yyyy-MM-dd HH:mm"
                                                )
                                            ) + " · ${version.sizeBytes / 1024} KB",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        showVersions = false
                                        viewModel.restoreVersion(version.id)
                                    }
                                ) {
                                    Text("Restore")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearVersions()
                        showVersions = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showVersions = false }) { Text("Close") }
            }
        )
    }

    // Task T58: the one-time offer to let the volume keys turn pages lives in
    // the page area as a banner rather than a dialog, so it never blocks a tap
    // or a scroll. A dismissal is remembered, so the offer never nags.
    val dismissVolumeHint: (Boolean) -> Unit = { enable ->
        if (enable) {
            turnPrefs = turnPrefs.copy(volumeKeys = true)
            AppSettings.setTurnSettings(context, turnPrefs)
        }
        showVolumeHint = false
        AppSettings.setTurnVolumeHintShown(context, true)
    }

    val saveLabel = when {
        saveState.status == ViewerSaveStatus.SAVING || edit.saving -> "Saving..."
        saveState.status == ViewerSaveStatus.UNSAVED -> "Unsaved"
        saveState.status == ViewerSaveStatus.SAVED -> "Saved"
        saveState.status == ViewerSaveStatus.ERROR -> "Save failed"
        else -> null
    }
    val subtitle = listOfNotNull(
        activeToolName ?: if (state.pageCount > 0) "${state.pageCount} pages" else null,
        saveLabel
    ).joinToString(" · ").ifBlank { null }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            val showToolbar = toolbarsVisible || !edgeLayerActive
            if (showToolbar) {
            ViewerToolbar(
                title = state.displayName ?: "PDF",
                subtitle = subtitle,
                zoom = zoom,
                findActive = showSearch,
                canPresent = state.pageCount > 0,
                canUndo = objectEdit.canUndo || edit.annotations.isNotEmpty(),
                canRedo = objectEdit.canRedo,
                versionCount = saveState.versions.size,
                onVersions = {
                    viewModel.loadVersions()
                    showVersions = true
                },
                onPresent = {
                    if (state.pageCount > 0) {
                        presentStartPage = listState.firstVisibleItemIndex
                        presenting = true
                    }
                },
                onBookmark = { bookmarkTarget = listState.firstVisibleItemIndex },
                onBack = {
                    when {
                        showTools -> showTools = false
                        activeToolId != null -> closeTool()
                        else -> onBack()
                    }
                },
                onZoomIn = { zoomOverride = (zoom * 1.25f).coerceAtMost(MAX_ZOOM) },
                onZoomOut = { zoomOverride = (zoom / 1.25f).coerceAtLeast(MIN_ZOOM) },
                onFit = { zoomOverride = 1f },
                fitLabel = if (fit.active) "Fit: ${fit.mode.short}" else "Fit content",
                fitActive = fit.active,
                onFitContent = { showFitContent = true },
                onReflow = openReflowHere,
                onFlow = openFlowHere,
                onFind = { showSearch = !showSearch },
                onUndo = viewModel::undoAny,
                onRedo = viewModel::redoAny,
                onMore = { showTools = true }
            )
            } else {
                // Task T58: a hidden toolbar must never trap the reader. This
                // strip is the visible way back, and it repeats the page
                // number so the reading position stays readable.
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { toolbarsVisible = true },
                            modifier = Modifier.testTag(UiTags.VIEWER_SHOW_TOOLBAR)
                        ) {
                            Text("Show toolbar")
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (state.pageCount > 0) {
                                "${(readingPage + 1).coerceIn(1, state.pageCount)}/${state.pageCount}"
                            } else {
                                ""
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.size(12.dp))
                    }
                }
            }

            if (converted != null && !bannerDismissed && !edit.active) {
                SimplifiedViewBanner(
                    warnings = converted.warnings,
                    onSaveAsPdf = { viewModel.saveConvertedPdf(converted.pdfName) },
                    onOpenInAnotherApp = {
                        openPdfInAnotherApp(context, path, converted.sourceName)
                    },
                    onDismiss = { bannerDismissed = true }
                )
            }

            val safetyReport = safety.report
            if (
                safetyBannerEnabled &&
                !safetyDismissed &&
                !edit.active &&
                safetyReport != null &&
                safetyReport.hasNoteworthyFindings
            ) {
                SafetyBanner(
                    report = safetyReport,
                    onOpenDetails = {
                        onOpenSafety(state.sourceLocation.ifBlank { path }, state.displayName)
                    },
                    onDismiss = { safetyDismissed = true }
                )
            }

            if (edit.active) {
                EditBar(
                    toolName = activeToolName,
                    objectState = objectEdit,
                    markupTool = edit.tool,
                    annotationCount = edit.annotations.size +
                        if (edit.tool == AnnotationTool.INK) ink.strokes.size else 0,
                    fieldCount = edit.fields.size,
                    showForms = showForms,
                    saving = edit.saving,
                    hasMarkupChanges = edit.hasPendingChanges,
                    onObjectTool = viewModel::setObjectTool,
                    onAddImage = pickNewImage,
                    onMarkupTool = viewModel::setTool,
                    onUndoObject = viewModel::undoObjectEdit,
                    onRedoObject = viewModel::redoObjectEdit,
                    onUndoMarkup = {
                        if (edit.tool == AnnotationTool.INK && ink.canUndo) {
                            viewModel.undoInk()
                        } else {
                            viewModel.undoAnnotation()
                        }
                    },
                    onClearMarkup = {
                        if (edit.tool == AnnotationTool.INK) {
                            viewModel.clearInkStrokes()
                        } else {
                            viewModel.clearAnnotations()
                        }
                    },
                    onSave = viewModel::saveAll,
                    onToggleForms = { showForms = !showForms },
                    onDone = closeTool,
                    onCancel = cancelTool
                )
                if (edit.tool == AnnotationTool.INK) {
                    InkToolbar(
                        ink = ink,
                        currentPage = listState.firstVisibleItemIndex,
                        onMode = viewModel::setInkMode,
                        onColor = viewModel::setInkColor,
                        onWidth = viewModel::setInkWidth,
                        onUndo = viewModel::undoInk,
                        onRedo = viewModel::redoInk,
                        onClear = viewModel::clearInkStrokes,
                        onDeleteSelection = viewModel::deleteSelectedInk,
                        onSelectAll = { viewModel.selectAllInkOnPage(listState.firstVisibleItemIndex) },
                        onStylusOnly = viewModel::setStylusOnly,
                        onFocus = {
                            viewModel.setInkMode(InkToolMode.PEN)
                            viewModel.setInkFocusPage(listState.firstVisibleItemIndex)
                        }
                    )
                }
                if (showForms) {
                    FormPanel(
                        fields = edit.fields,
                        values = edit.fieldValues,
                        loading = edit.loadingFields,
                        onValue = viewModel::setFieldValue
                    )
                }
            }

            if (showSearch) {
                SearchRow(
                    query = query,
                    matchCount = search.matches.size,
                    activeIndex = search.activeIndex,
                    running = search.running,
                    onQueryChange = { query = it },
                    onPrevious = viewModel::previousMatch,
                    onNext = viewModel::nextMatch,
                    onOpenRegex = { showRegex = true }
                )
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.error != null -> Text(
                        text = state.error ?: "Unknown error",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    )
                    // T33: the reflow overlay covers this area, so the page list
                    // behind it is not built (and nothing is rendered for it).
                    // T60: the snip overlay covers it the same way.
                    // T59: so does column flow.
                    reflow.active || snipActive || flow.active -> Unit
                    else -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { pageSurfaceSize = it }
                            .testTag(UiTags.VIEWER_PAGE_SURFACE)
                            .then(
                                if (horizontalMode) Modifier else Modifier.horizontalScroll(hScroll)
                            )
                            .pinchToZoom { factor -> zoomOverride = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM) }
                    ) {
                        val pageContent: @Composable (Int, Boolean) -> Unit = { index, fillSlot ->
                                val searchRects = remember(search.matches, index) {
                                    search.matches.filter { it.pageIndex == index }.flatMap { it.rects }
                                }
                                val selectionRects = selection?.takeIf { it.pageIndex == index }?.rects
                                    ?: emptyList()
                                val pageAnnotations = remember(edit.annotations, index) {
                                    edit.annotations.filter { it.pageIndex == index }
                                }
                                val inkDraft = edit.inkDraft?.takeIf { it.pageIndex == index }
                                val pageInk = remember(ink.strokes, index) {
                                    ink.strokes.filter { it.pageIndex == index }
                                }
                                val pageActiveInk = ink.active?.takeIf { it.pageIndex == index }
                                val pageObjects = remember(objectEdit.objects, index) {
                                    objectEdit.objects.filter { it.pageIndex == index }
                                }
                                PageItem(
                                    index = index,
                                    box = boxes.getOrNull(index),
                                    bitmap = bitmaps[index],
                                    plan = plans[index],
                                    searchRects = searchRects,
                                    selectionRects = selectionRects,
                                    speechRects = speechHighlight(readAloud, index),
                                    focusRect = focusMark?.takeIf { it.first == index }?.second,
                                    editTool = if (edit.active) edit.tool else null,
                                    objectTool = if (edit.active) objectEdit.tool else null,
                                    objects = pageObjects,
                                    selectedObjectId = objectEdit.selectedId,
                                    annotations = pageAnnotations,
                                    inkDraft = inkDraft,
                                    inkState = ink,
                                    inkStrokes = pageInk,
                                    activeInk = pageActiveInk,
                                    targetPixelWidth = (screenWidthPx * renderZoom).roundToInt(),
                                    themeGeneration = themeGeneration,
                                    pageBackground = pageBackground,
                                    onVisible = viewModel::onPageVisible,
                                    onHidden = viewModel::onPageHidden,
                                    linkTapEnabled = linkTapEnabled,
                                    onTap = onLinkTap,
                                    onSelect = viewModel::selectAt,
                                    onInkStart = viewModel::startInkStroke,
                                    onInkExtend = viewModel::extendInkStroke,
                                    onInkEnd = viewModel::finishInkStroke,
                                    onNoteTap = { i, fx, fy ->
                                        if (commentPlacement) {
                                            commentTarget = Triple(i, fx, fy)
                                        } else {
                                            noteTarget = Triple(i, fx, fy)
                                        }
                                    },
                                    onPlaceText = { i, fx, fy ->
                                        textTarget = TextTarget(i, EditableBounds.around(fx, fy))
                                    },
                                    onPlaceImage = { i, fx, fy ->
                                        val uri = pendingImageUri
                                        if (uri == null) {
                                            pickNewImage()
                                        } else {
                                            viewModel.addImageObject(
                                                pageIndex = i,
                                                bounds = EditableBounds.around(
                                                    x = fx,
                                                    y = fy,
                                                    width = 0.5f,
                                                    height = 0.34f
                                                ),
                                                contentUri = uri.toString()
                                            )
                                            pendingImageUri = null
                                        }
                                    },
                                    onSelectObject = viewModel::selectObject,
                                    onBoundsChange = viewModel::updateObjectBounds,
                                    onClearObjectSelection = viewModel::clearObjectSelection,
                                    stamp = stampPlacement?.takeIf { it.pageIndex == index },
                                    stampAuthor = author,
                                    onStampTransform = { cx, cy, width ->
                                        stampPlacement = stampPlacement?.copy(
                                            centerX = cx,
                                            centerY = cy,
                                            widthFraction = width
                                        )
                                    },
                                    signature = signaturePlacement?.takeIf { it.pageIndex == index },
                                    onSignatureTransform = { cx, cy, width ->
                                        signaturePlacement = signaturePlacement?.copy(
                                            centerX = cx,
                                            centerY = cy,
                                            widthFraction = width
                                        )
                                    },
                                    signatureTap = pendingSignature != null,
                                    onSignatureTap = { i, fx, fy ->
                                        val pending = pendingSignature
                                        if (pending != null) {
                                            signaturePlacement = pending.at(i, fx, fy)
                                            pendingSignature = null
                                        }
                                    },
                                    turnSettings = if (edgeLayerActive) turn else null,
                                    onTurn = { direction -> turnPage(direction, index) },
                                    onCenterTap = { toolbarsVisible = !toolbarsVisible },
                                    onCenterDoubleTap = {
                                        zoomOverride = if (zoom > 1.001f) 1f else DOUBLE_TAP_ZOOM
                                    },
                                    fillSlot = fillSlot
                                )
                        }
                        if (horizontalMode) {
                            LazyRow(
                                state = hListState,
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                // T63: no coasting under reduce motion; the page
                                // stops the moment the finger lifts.
                                flingBehavior = if (reduceMotion) {
                                    NoFling
                                } else {
                                    ScrollableDefaults.flingBehavior()
                                }
                            ) {
                                items(state.pageCount) { index ->
                                    val aspect = remember(boxes.getOrNull(index)) {
                                        val pageBox = boxes.getOrNull(index)
                                        val w = pageBox?.rotatedWidth ?: 612.0
                                        val h = pageBox?.rotatedHeight ?: 792.0
                                        if (h > 0.0) (w / h).toFloat() else 612f / 792f
                                    }
                                    // The whole page is fitted inside its screen
                                    // (T58). Filling the height instead would push
                                    // a portrait page's left and right edge zones
                                    // off the display, so a thumb could only ever
                                    // reach the centre.
                                    BoxWithConstraints(modifier = Modifier.fillParentMaxSize()) {
                                        val slotAspect =
                                            if (maxHeight > 0.dp) maxWidth / maxHeight else 1f
                                        val pageBox = if (aspect > slotAspect) {
                                            Modifier.fillMaxWidth().aspectRatio(aspect)
                                        } else {
                                            Modifier.fillMaxHeight().aspectRatio(aspect)
                                        }
                                        Box(modifier = pageBox.align(Alignment.Center)) {
                                            pageContent(index, true)
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .width(contentWidth)
                                    .fillMaxHeight(),
                                flingBehavior = when {
                                    reduceMotion -> NoFling
                                    turn.mode == TurnMode.PAGE_SNAP -> snapFling
                                    else -> defaultFling
                                }
                            ) {
                                items(state.pageCount) { index -> pageContent(index, false) }
                            }
                        }
                    }
                }
                // T62: sepia for a yellowed scan, inverted monochrome for a
                // line drawing. It is a banner, never an automatic change.
                val hint = lookHint
                if (hint != null && !edit.active && !reflow.active && state.pageCount > 0 && !reduceMotion) {
                    LookHintBanner(
                        message = hint.message,
                        filter = hint.filter,
                        onApply = viewModel::applyLookHint,
                        onDismiss = viewModel::dismissLookHint,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 12.dp)
                            .padding(top = 8.dp)
                    )
                }
                if (reflow.hint && !reflow.active && state.pageCount > 0 && !reduceMotion) {
                    ReflowHintBanner(
                        onTryReflow = {
                            viewModel.clearReflowHint()
                            openReflowHere()
                        },
                        onDismiss = viewModel::dismissReflowHint,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 12.dp)
                            // Leave room for the look hint (T62) above it.
                            .padding(top = if (hint == null) 8.dp else 76.dp)
                    )
                }
                if (showVolumeHint) {
                    VolumeKeyHint(
                        onEnable = { dismissVolumeHint(true) },
                        onDismiss = { dismissVolumeHint(false) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 76.dp)
                    )
                }
                // T64: the reading's transport, when the service is reading.
                if (readAloud.active) {
                    ReadAloudBar(
                        onOpenOptions = { showReadAloudSheet = true },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 76.dp)
                    )
                }
                if (showReadAloudSheet && readAloud.active) {
                    ReadAloudSheet(
                        state = readAloud,
                        onDismiss = { showReadAloudSheet = false }
                    )
                }
                // T34: a saved speech spot is an offer, never an automatic
                // restart: starting to talk on open would surprise the reader.
                if (!readAloud.active &&
                    state.hasSavedSpeech &&
                    !speechOfferDismissed
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(50),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 76.dp)
                            .testTag(UiTags.VIEWER_SPEECH_CONTINUE)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Continue reading aloud from page ${state.startSpeechPage + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clickable {
                                        speechOfferDismissed = true
                                        viewModel.startReadAloud(
                                            state.startSpeechPage,
                                            state.startSpeechParagraph
                                        )
                                    }
                                    .padding(start = 16.dp, top = 10.dp, bottom = 10.dp)
                            )
                            androidx.compose.material3.TextButton(
                                onClick = { speechOfferDismissed = true }
                            ) {
                                Text("Not now")
                            }
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
                if (linkBackStack.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = RoundedCornerShape(50),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                            .testTag(UiTags.LINK_BACK)
                            .clickable { restoreReadingPosition() }
                    ) {
                        Text(
                            "Back to where I was",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
                pinnedLink?.let { pinned ->
                    PinnedLinkPanel(
                        target = pinned,
                        onClose = { pinnedLink = null },
                        onGoTo = jumpToLink,
                        loadPreview = viewModel::linkPreview,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }

            val placing = stampPlacement
            if (placing != null) {
                StampPlacementBar(
                    placement = placing,
                    onCancel = { stampPlacement = null },
                    onPlace = {
                        viewModel.addStamp(
                            design = placing.design,
                            pageIndex = placing.pageIndex,
                            centerX = placing.centerX,
                            centerY = placing.centerY,
                            widthFraction = placing.widthFraction
                        )
                        stampPlacement = null
                    }
                )
            }

            val pending = pendingSignature
            if (pending != null) {
                SignatureTapBar(
                    pending = pending,
                    onCancel = { pendingSignature = null }
                )
            }

            val placingSignature = signaturePlacement
            if (placingSignature != null) {
                SignaturePlacementBar(
                    placement = placingSignature,
                    onCancel = { signaturePlacement = null },
                    onApply = {
                        val chosen = placingSignature
                        signaturePlacement = null
                        if (chosen.kind == SignaturePlacementKind.VISUAL) {
                            val draft = chosen.draft
                            if (draft == null) {
                                viewModel.showMessage("The signature is empty.")
                            } else {
                                viewModel.placeVisualSignature(
                                    draft = draft,
                                    pageIndex = chosen.pageIndex,
                                    centerX = chosen.centerX,
                                    centerY = chosen.centerY,
                                    widthFraction = chosen.widthFraction,
                                    captionName = chosen.captionName,
                                    captionDate = chosen.captionDate
                                )
                            }
                        } else {
                            val certificate = chosen.certificate
                            if (certificate == null) {
                                viewModel.showMessage("Choose a signing certificate first.")
                            } else {
                                confirmWithDeviceLock {
                                    viewModel.signDigitally(
                                        certificate = certificate,
                                        reason = chosen.reason,
                                        location = chosen.location,
                                        visible = true,
                                        pageIndex = chosen.pageIndex,
                                        centerX = chosen.centerX,
                                        centerY = chosen.centerY,
                                        widthFraction = chosen.widthFraction
                                    )
                                }
                            }
                        }
                    }
                )
            }

            val selectedObject = objectEdit.selected
            if (selectedObject != null) {
                SelectedObjectBar(
                    objectState = selectedObject,
                    onEditText = {
                        textTarget = TextTarget(
                            pageIndex = selectedObject.pageIndex,
                            bounds = selectedObject.bounds,
                            existing = selectedObject
                        )
                    },
                    onReplaceImage = {
                        replaceImageTarget = selectedObject
                        imagePicker.launch("image/*")
                    },
                    onDelete = { viewModel.deleteObject(selectedObject.id) },
                    onDone = viewModel::clearObjectSelection
                )
            }

            val current = selection
            if (current != null) {
                SelectionBar(
                    text = current.text,
                    allowHighlight = edit.active,
                    onHighlight = viewModel::highlightSelection,
                    onCopy = { clipboard.setText(AnnotatedString(current.text)) },
                    onSelectAll = { viewModel.selectAll(current.pageIndex) },
                    onClear = viewModel::clearSelection
                )
            }
        }
            val focusPage = ink.focusPage
            if (focusPage != null && focusPage in 0 until state.pageCount) {
                val focusBox = boxes.getOrNull(focusPage)
                val focusAspect = if (focusBox != null && focusBox.rotatedHeight > 0.0) {
                    (focusBox.rotatedWidth / focusBox.rotatedHeight).toFloat()
                } else {
                    612f / 792f
                }
                InkFocusOverlay(
                    pageIndex = focusPage,
                    pageCount = state.pageCount,
                    bitmap = bitmaps[focusPage],
                    plan = plans[focusPage],
                    aspect = focusAspect,
                    state = ink,
                    onNeedPage = { index, widthPx ->
                        viewModel.onPageVisible(index, widthPx)
                    },
                    onStart = viewModel::startInkStroke,
                    onExtend = viewModel::extendInkStroke,
                    onEnd = viewModel::finishInkStroke,
                    onMode = viewModel::setInkMode,
                    onColor = viewModel::setInkColor,
                    onWidth = viewModel::setInkWidth,
                    onUndo = viewModel::undoInk,
                    onRedo = viewModel::redoInk,
                    onClose = { viewModel.setInkFocusPage(null) }
                )
            }
            if (presenting) {
                PresenterOverlay(
                    path = state.sourceLocation.ifBlank { path },
                    pageCount = state.pageCount,
                    startPage = presentStartPage.coerceIn(
                        0,
                        (state.pageCount - 1).coerceAtLeast(0)
                    ),
                    displayName = state.displayName,
                    renderPage = { index, width ->
                        viewModel.renderPresenterPage(index, width)
                    },
                    onExit = { presenting = false },
                    onSaveInk = { specs ->
                        viewModel.addPresenterInk(specs)
                        presenting = false
                    }
                )
            }
            if (reflow.active) {
                ReflowOverlay(
                    state = reflow,
                    pageCount = state.pageCount,
                    regions = reflowRegions,
                    onBack = exitReflow,
                    onRequestPage = viewModel::requestReflowPage,
                    onRequestRegion = viewModel::requestReflowRegion,
                    onPosition = viewModel::updateReflowPosition,
                    onSettings = viewModel::setReflowSettings,
                    onOcr = { page, language -> viewModel.runReflowOcr(page, language) },
                    onDismissMessage = viewModel::clearReflowMessage
                )
            }
            // T59: column flow. It covers the page area like reflow does, and
            // paints the page surface behind it with the look's own paper so no
            // light edge shows through.
            if (flow.active) {
                ColumnFlowOverlay(
                    state = flow,
                    pageCount = state.pageCount,
                    displayName = state.displayName,
                    bitmaps = flowBitmaps,
                    background = pageBackground,
                    onClose = exitFlow,
                    onRequestPage = viewModel::requestFlowPage,
                    onRequestSegment = viewModel::requestFlowSegment,
                    onStep = { steps -> viewModel.flowStep(steps) },
                    onSettings = viewModel::setFlowSettings,
                    onToggleFullPage = viewModel::toggleFlowFullPage,
                    onMergeNext = viewModel::flowMergeNext,
                    onSplitHere = viewModel::flowSplitHere,
                    onResetPage = viewModel::flowResetPage,
                    onDismissMessage = viewModel::dismissFlowMessage
                )
            }
            // T60: the marquee snip tool. It is drawn above the page area (and
            // above reflow, though the two are never active at once) because the
            // marquee has to reach the whole page surface.
            if (snipActive) {
                SnipOverlay(
                    state = snipState,
                    viewModel = snipViewModel,
                    onClose = {
                        snipActive = false
                        snipPath = null
                    }
                )
            }
            // T63: the ghosting clear. A sheet or dialog lives above the root
            // surface, so an open dialog is never erased by the flash.
            if (flashPhase != 0) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (flashPhase == 1) Color.Black else Color.White)
                        .testTag(UiTags.EINK_FLASH)
                )
            }
        }
    }

    val shownLink = linkCard
    if (shownLink != null) {
        LinkPreviewSheet(
            request = shownLink,
            onDismiss = { linkCard = null },
            onGoTo = jumpToLink,
            onPin = { target ->
                pinnedLink = target
                linkCard = null
            },
            onCopy = { target ->
                val text = target.copyText.ifBlank { target.title }
                clipboard.setText(AnnotatedString(text))
                viewModel.showMessage("Copied preview text")
            },
            loadPreview = viewModel::linkPreview
        )
    }

    val external = externalLink
    if (external != null) {
        ExternalLinkDialog(
            request = external,
            onDismiss = { externalLink = null },
            onCopy = {
                val text = external.uri ?: external.file.orEmpty()
                if (text.isNotBlank()) {
                    clipboard.setText(AnnotatedString(text))
                    viewModel.showMessage("Link copied")
                }
                externalLink = null
            },
            onOpen = openExternal
        )
    }

    bookmarkTarget?.let { page ->
        BookmarkDialog(
            pageIndex = page,
            initialOutline = AppSettings.bookmarkOutlineEnabled(context),
            onDismiss = { bookmarkTarget = null },
            onConfirm = { label, color, outline ->
                AppSettings.setBookmarkOutlineEnabled(context, outline)
                viewModel.addBookmark(page, label, color)
                bookmarkTarget = null
            }
        )
    }

    val target = noteTarget
    if (target != null) {        NoteDialog(
            author = author,
            onDismiss = { noteTarget = null },
            onConfirm = { text ->
                viewModel.addNote(target.first, target.second, target.third, text, title = author)
                noteTarget = null
            }
        )
    }

    val commentAt = commentTarget
    if (commentAt != null) {
        NoteDialog(
            title = "Add comment",
            fieldLabel = "Comment",
            author = author,
            onDismiss = {
                commentTarget = null
                commentPlacement = false
            },
            onConfirm = { text ->
                viewModel.addNote(
                    commentAt.first,
                    commentAt.second,
                    commentAt.third,
                    text,
                    title = author
                )
                commentTarget = null
                commentPlacement = false
                showComments = true
            }
        )
    }

    val textEdit = textTarget
    if (textEdit != null) {
        TextObjectDialog(
            existing = textEdit.existing,
            onDismiss = { textTarget = null },
            onConfirm = { content, style ->
                val existing = textEdit.existing
                if (existing == null) {
                    viewModel.addTextObject(
                        pageIndex = textEdit.pageIndex,
                        bounds = textEdit.bounds,
                        content = content,
                        style = style
                    )
                } else {
                    viewModel.updateTextObject(existing.id, content, style)
                }
                textTarget = null
            }
        )
    }

    if (showFitContent) {
        FitContentSheet(
            state = fit,
            pages = pageBoxes,
            currentPage = listState.firstVisibleItemIndex,
            renderPreview = viewModel::renderFitPreview,
            onMode = viewModel::setFitMode,
            onMargins = viewModel::setFitMargins,
            onIgnoreHeaderFooter = viewModel::setFitIgnoreHeaderFooter,
            onMakePermanent = {
                val boxes = viewModel.fitCropBoxes()
                // The crop screen reads a real file: a content:// document is
                // materialised first, exactly like the viewer's other tools.
                viewModel.withLocalDocument { local ->
                    PendingCrop.put(local, boxes)
                    showFitContent = false
                    onOpenTool("crop", local)
                }
            },
            onDismiss = { showFitContent = false }
        )
    }

    if (showTools) {
        ViewerToolsSheet(
            fileName = state.displayName ?: "PDF",
            demoEngine = demoEngine,
            onDismiss = { showTools = false },
            onSelect = activateTool,
            look = look,
            onLookFilter = { filter -> viewModel.applyLook(look.copy(filter = filter)) },
            onOpenLook = {
                showTools = false
                showLook = true
            },
            // T63: the one-tap E-Ink switch and its profiles, live over the
            // page behind the sheet.
            eink = eink,
            onEinkEnabled = viewModel::setEinkEnabled,
            onEinkProfile = { profile ->
                viewModel.applyEink(
                    eink.settings.copy(profile = profile),
                    forDocument = eink.fromDocument
                )
            },
            onEinkClear = { flashTick += 1 },
            onOpenEink = {
                showTools = false
                showEink = true
            },
            // T38b: the orientation this document is read in, and a way to
            // forget the stored spot without leaving the page.
            orientation = readingOrientation,
            onOrientation = viewModel::setReadingOrientation,
            onForgetPosition = viewModel::forgetReadingState,
            // The sheet covers the snackbar host, so close it before reporting
            // that the tool is not available yet.
            onUnavailable = { tool ->
                showTools = false
                viewModel.showMessage("${tool.name} is coming soon")
            }
        )
    }

    // T62: the full look sheet. Every change is applied as it is made, so the
    // page behind the sheet is the preview and dismissing never discards work.
    if (showLook) {
        LookSheet(
            look = look,
            fromDocument = lookFromDocument,
            onApply = viewModel::applyLook,
            onReset = viewModel::resetLook,
            onUseAsDefault = viewModel::useLookAsDefault,
            onDismiss = { showLook = false }
        )
    }

    // T63: the E-Ink profile sheet. Like the look sheet, it edits live.
    if (showEink) {
        EinkSheet(
            display = eink,
            autoClearTurns = autoClearTurns,
            onApply = { settings, forDocument ->
                viewModel.setEinkFromDocument(settings, forDocument)
            },
            onEnabledChange = viewModel::setEinkEnabled,
            onDocumentOverride = { fromDocument ->
                viewModel.setEinkFromDocument(eink.settings, fromDocument)
            },
            onAutoClear = {
                autoClearTurns = it
                turnsSinceClear = 0
                AppSettings.setEinkAutoClearTurns(context, it)
            },
            onClearScreen = { flashTick += 1 },
            onDismiss = { showEink = false }
        )
    }

    if (showComments) {
        val drafts = remember(edit.annotations, author) {
            edit.annotations.filterIsInstance<NoteSpec>().map { note ->
                CommentDraftUi(note.pageIndex, note.title ?: author, note.contents)
            }
        }
        CommentsSheet(
            saved = comments,
            localReplies = commentReplies,
            localStatuses = commentStatuses,
            drafts = drafts,
            author = author,
            loading = commentsLoading,
            error = commentsError,
            onDismiss = { showComments = false },
            onAddComment = {
                showComments = false
                toolAnnotationStart = edit.annotations.size
                viewModel.startViewerTool("note")
                activeToolId = "comments"
                commentPlacement = true
            },
            onReply = { thread, text ->
                viewModel.addCommentReply(thread.id, thread.pageIndex, text)
            },
            onResolve = { thread, resolved ->
                viewModel.setCommentResolved(thread.id, thread.pageIndex, resolved)
            },
            onOpenSettings = {
                showComments = false
                onOpenSettings()
            }
        )
    }

    if (showStamps) {
        StampSheet(
            author = author,
            onDismiss = { showStamps = false },
            onSelect = { design ->
                showStamps = false
                val custom = CustomStampStore(context).load()
                    .firstOrNull { it.id == design.id }
                val page = listState.firstVisibleItemIndex
                    .coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
                stampPlacement = StampPlacement(
                    design = design,
                    pageIndex = page,
                    centerX = 0.5f,
                    centerY = 0.5f,
                    widthFraction = 0.36f,
                    imageBytes = custom?.image
                )
            }
        )
    }

    // T65: "Mark all as redactions" closes the regex sheet and leaves the
    // pattern in T28's own Find panel. The regex side never runs that search.
    LaunchedEffect(regexSheet.handoff) {
        if (regexSheet.handoff != null) {
            showRegex = false
            redactionPanel = RedactionPanel.FIND
            viewModel.clearRegexHandoff()
        }
    }

    if (showRegex) RegexSheetHost(
        regexState = regexSheet,
        displayName = state.displayName ?: "",
        onDismiss = { showRegex = false },
        viewModel = viewModel,
        onTest = { pattern: String, flags: Set<com.pdfpaper.engine.text.RegexFlag> ->
            scope.launch {
                delay(350)
                viewModel.regexTester(pattern, flags)
            }
        },
        onExport = { snapshot ->
            pendingRegexExport = snapshot
            createDocumentForRegex.launch(
                regexFileName(RegexScope.DOCUMENT, state.displayName ?: "document", csv = true)
            )
        },
        onRowFocus = { pageIndex, rect ->
            focusMark = pageIndex to PdfRect(rect.x0, rect.y0, rect.x1, rect.y1)
            scope.launch {
                listState.scrollToItem(pageIndex)
                delay(4000)
                focusMark = null
            }
        }
    )

    if (redaction.active) {
        RedactionSheet(
            state = redaction,
            panel = redactionPanel,
            selectionText = selection?.text,
            pageCount = state.pageCount,
            onDismiss = viewModel::endRedaction,
            onPanelChange = { redactionPanel = it },
            onToolChange = viewModel::setRedactionTool,
            onMarkSelection = viewModel::markRedactionSelection,
            onFindModeChange = viewModel::setRedactionFindMode,
            onFindQueryChange = viewModel::setRedactionFindQuery,
            onFind = viewModel::findRedactions,
            onToggleFindMatch = viewModel::toggleRedactionFindMatch,
            onToggleAllFindMatches = viewModel::toggleAllRedactionFindMatches,
            onMarkFound = viewModel::markRedactionFindMatches,
            onJumpToPage = { page -> scope.launch { listState.scrollToItem(page) } },
            onPageToggle = viewModel::toggleRedactionPage,
            onSelectAllPages = { viewModel.selectAllRedactionPages(state.pageCount) },
            onClearPageSelection = viewModel::clearRedactionPageSelection,
            onMarkPages = viewModel::markRedactionPages,
            onPropertiesChange = viewModel::setRedactionProperties,
            onApplyPropertiesToMarks = viewModel::applyRedactionPropertiesToMarks,
            onRemoveMark = viewModel::removeRedactionMark,
            onUpdateMark = viewModel::updateRedactionMark,
            onClearMarks = viewModel::clearRedactionMarks,
            onMarkOnPage = {
                val page = listState.firstVisibleItemIndex
                    .coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
                viewModel.addRedactionArea(page, 0.2f, 0.2f, 0.8f, 0.8f)
            },
            onApply = { if (redaction.marks.isNotEmpty()) showRedactionConfirm = true }
        )
    }
    if (showRedactionConfirm) {
        RedactionConfirmDialog(
            markCount = redaction.marks.size,
            pageCount = redaction.marks.map { it.pageIndex }.distinct().size,
            onDismiss = { showRedactionConfirm = false },
            onConfirm = {
                showRedactionConfirm = false
                viewModel.applyRedactions()
            }
        )
    }
    if (redaction.busy) RedactionBusyDialog()
    redaction.outcome?.let { outcome ->
        RedactionVerificationDialog(
            outcome = outcome,
            onSave = {
                viewModel.dismissRedactionOutcome()
                viewModel.saveRedactionCopy()
            },
            onDismiss = viewModel::dismissRedactionOutcome
        )
    }

    if (showSignOptions) {
        SignOptionsSheet(
            hasUnsavedEdits = edit.hasPendingChanges,
            onDismiss = { showSignOptions = false },
            onVisual = {
                showSignOptions = false
                showVisualSignature = true
            },
            onDigital = {
                showSignOptions = false
                showDigitalSignature = true
            }
        )
    }

    if (showVisualSignature) {
        VisualSignatureDialog(
            author = author,
            onDismiss = { showVisualSignature = false },
            onConfirm = { draft, captionName, captionDate ->
                showVisualSignature = false
                pendingSignature = PendingSignature(
                    kind = SignaturePlacementKind.VISUAL,
                    draft = draft,
                    captionName = captionName,
                    captionDate = captionDate,
                    widthFraction = 0.4f
                )
            }
        )
    }

    if (showDigitalSignature) {
        DigitalSignatureDialog(
            onDismiss = { showDigitalSignature = false },
            onContinue = { certificate, reason, location, visible ->
                showDigitalSignature = false
                val page = listState.firstVisibleItemIndex
                    .coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
                if (visible) {
                    pendingSignature = PendingSignature(
                        kind = SignaturePlacementKind.DIGITAL,
                        certificate = certificate,
                        reason = reason,
                        location = location,
                        widthFraction = 0.44f
                    )
                } else {
                    confirmWithDeviceLock {
                        viewModel.signDigitally(
                            certificate = certificate,
                            reason = reason,
                            location = location,
                            visible = false,
                            pageIndex = page,
                            centerX = 0.5f,
                            centerY = 0.5f,
                            widthFraction = 0.44f
                        )
                    }
                }
            }
        )
    }

    if (signing.busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Signing...") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Writing the signature and checking it on this device.")
                }
            },
            confirmButton = {}
        )
    }

    signing.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearSigningResult,
            title = { Text("Could not sign") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::clearSigningResult) { Text("OK") }
            }
        )
    }

    signing.outcome?.let { outcome ->
        SigningResultDialog(
            outcome = outcome,
            onSave = viewModel::exportSigned,
            onDone = viewModel::clearSigningResult
        )
    }

    if (showDeviceFallback) {
        AlertDialog(
            onDismissRequest = {
                showDeviceFallback = false
                pendingDeviceAction = null
            },
            title = { Text("Confirm signing") },
            text = {
                Text(
                    "This device has no screen lock set up, or the lock screen is not " +
                        "available. Tap Confirm to sign this PDF. For stronger protection, " +
                        "set a screen lock in Android Settings."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeviceFallback = false
                        val action = pendingDeviceAction
                        pendingDeviceAction = null
                        action?.invoke()
                    },
                    modifier = Modifier.testTag(UiTags.DEVICE_CONFIRM_FALLBACK)
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeviceFallback = false
                        pendingDeviceAction = null
                    }
                ) { Text("Cancel") }
            }
        )
    }

    if (showSignatures) {
        SignaturesSheet(
            state = signatures,
            onDismiss = { showSignatures = false },
            onReload = viewModel::reloadSignatures
        )
    }

    if (showOcr) {
        OcrSheet(
            state = ocr,
            currentPage = listState.firstVisibleItemIndex + 1,
            pageCount = state.pageCount,
            onStart = { language, allPages ->
                viewModel.startOcr(language, allPages, listState.firstVisibleItemIndex)
            },
            onSave = {
                showOcr = false
                viewModel.exportSearchable()
            },
            onCancel = viewModel::cancelOcr,
            onDismiss = { showOcr = false }
        )
    }

    if (state.requiresPassword) {
        PasswordDialog(
            fileName = state.displayName,
            incorrect = state.passwordError != null,
            onDismiss = viewModel::cancelUnlock,
            onSubmit = viewModel::unlock
        )
    }
}

@Composable
private fun ViewerToolbar(
    title: String,
    subtitle: String?,
    zoom: Float,
    findActive: Boolean,
    canPresent: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    versionCount: Int,
    onVersions: () -> Unit,
    onPresent: () -> Unit,
    onBookmark: () -> Unit,
    onBack: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    fitLabel: String = "Fit content",
    fitActive: Boolean = false,
    onFitContent: () -> Unit = {},
    onReflow: () -> Unit = {},
    /** T59: one column at a time. */
    onFlow: () -> Unit = {},
    onFind: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onMore: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
                IconButton(onClick = onFind) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = if (findActive) "Close search" else "Search"
                    )
                }
                IconButton(
                    onClick = onBookmark,
                    modifier = Modifier.testTag(UiTags.VIEWER_BOOKMARK_BUTTON)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BookmarkAdd,
                        contentDescription = "Bookmark this page"
                    )
                }
                IconButton(
                    onClick = onPresent,
                    enabled = canPresent,
                    modifier = Modifier.testTag(UiTags.VIEWER_PRESENT_BUTTON)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Slideshow,
                        contentDescription = "Present"
                    )
                }
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Undo,
                        contentDescription = "Undo"
                    )
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Redo,
                        contentDescription = "Redo"
                    )
                }
                IconButton(
                    onClick = onMore,
                    modifier = Modifier.testTag(UiTags.VIEWER_TOOLS_BUTTON)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More tools"
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onZoomOut) { Text("−") }
                Text("${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = onZoomIn) { Text("+") }
                TextButton(onClick = onFit) { Text("Fit") }
                TextButton(
                    onClick = onReflow,
                    modifier = Modifier.testTag(UiTags.VIEWER_REFLOW)
                ) {
                    Text("Reflow")
                }
                TextButton(
                    onClick = onFlow,
                    modifier = Modifier.testTag(UiTags.VIEWER_FLOW)
                ) {
                    Text("Column flow")
                }
                TextButton(
                    onClick = onFitContent,
                    modifier = Modifier.testTag(UiTags.FIT_CONTENT_BUTTON)
                ) {
                    Text(
                        text = fitLabel,
                        fontWeight = if (fitActive) FontWeight.SemiBold else null
                    )
                }
                if (versionCount > 0) {
                    TextButton(onClick = onVersions) {
                        Text("Versions ($versionCount)")
                    }
                }
            }
        }
    }
}

/**
 * T54 ink toolbar: drawing mode, colour, width, history, stylus-only input and
 * the focused low-latency canvas. It stays a compact horizontal strip so it
 * does not reduce the page more than the existing edit bar.
 */
@Composable
private fun InkToolbar(
    ink: InkUiState,
    currentPage: Int,
    onMode: (InkToolMode) -> Unit,
    onColor: (PdfColor) -> Unit,
    onWidth: (Double) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit,
    onDeleteSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onStylusOnly: (Boolean) -> Unit,
    onFocus: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InkChip("Pen", ink.mode == InkToolMode.PEN) { onMode(InkToolMode.PEN) }
            InkChip("Highlighter", ink.mode == InkToolMode.HIGHLIGHTER) {
                onMode(InkToolMode.HIGHLIGHTER)
            }
            InkChip("Eraser", ink.mode == InkToolMode.ERASER_STROKE) {
                onMode(InkToolMode.ERASER_STROKE)
            }
            InkChip("Point eraser", ink.mode == InkToolMode.ERASER_POINT) {
                onMode(InkToolMode.ERASER_POINT)
            }
            InkChip("Lasso", ink.mode == InkToolMode.LASSO) { onMode(InkToolMode.LASSO) }
            Spacer(Modifier.width(6.dp))
            if (ink.mode == InkToolMode.PEN || ink.mode == InkToolMode.HIGHLIGHTER) {
                for (color in InkPalette.colors) {
                    val selected = color.red == ink.color.red &&
                        color.green == ink.color.green &&
                        color.blue == ink.color.blue
                    Box(
                        modifier = Modifier
                            .padding(3.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(
                                Color(
                                    color.red.toFloat(),
                                    color.green.toFloat(),
                                    color.blue.toFloat()
                                )
                            )
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                shape = CircleShape
                            )
                            .clickable { onColor(color) }
                            .testTag("ink_color_${color.red}_${color.green}_${color.blue}")
                    )
                }
            }
            if (ink.mode == InkToolMode.PEN) {
                Spacer(Modifier.width(4.dp))
                for (width in InkPalette.widths) {
                    InkChip("${width.roundToInt()} pt", ink.width == width) { onWidth(width) }
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onUndo, enabled = ink.canUndo) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo ink")
            }
            IconButton(onClick = onRedo, enabled = ink.canRedo) {
                Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo ink")
            }
            InkChip("Stylus only", ink.stylusOnly) { onStylusOnly(!ink.stylusOnly) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                InkChip("Low-latency canvas", false, onFocus)
            }
            if (ink.mode == InkToolMode.LASSO) {
                InkChip("Select page", false, onSelectAll)
                if (ink.selected.isNotEmpty()) {
                    TextButton(onClick = onDeleteSelection) { Text("Delete selected") }
                }
            }
            if (ink.strokes.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Clear ink") }
            }
            if (ink.loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text(
                text = "Page ${currentPage + 1}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun InkChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EditBar(
    toolName: String?,
    objectState: ObjectEditState,
    markupTool: AnnotationTool,
    annotationCount: Int,
    fieldCount: Int,
    showForms: Boolean,
    saving: Boolean,
    hasMarkupChanges: Boolean,
    onObjectTool: (ObjectEditTool) -> Unit,
    onAddImage: () -> Unit,
    onMarkupTool: (AnnotationTool) -> Unit,
    onUndoObject: () -> Unit,
    onRedoObject: () -> Unit,
    onUndoMarkup: () -> Unit,
    onClearMarkup: () -> Unit,
    onSave: () -> Unit,
    onToggleForms: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.VIEWER_TOOL_MODE_BAR)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = toolName ?: "Edit",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag(UiTags.VIEWER_TOOL_CANCEL)
                ) { Text("Cancel") }
                TextButton(
                    onClick = onDone,
                    modifier = Modifier.testTag(UiTags.VIEWER_TOOL_DONE)
                ) { Text("Done") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag(UiTags.EDIT_TOOLBAR),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                EditAction(
                    label = "Select",
                    icon = Icons.Outlined.NearMe,
                    selected = objectState.tool == ObjectEditTool.SELECT,
                    tag = UiTags.EDIT_SELECT
                ) { onObjectTool(ObjectEditTool.SELECT) }
                EditAction(
                    label = "Add text",
                    icon = Icons.Outlined.TextFields,
                    selected = objectState.tool == ObjectEditTool.TEXT,
                    tag = UiTags.EDIT_ADD_TEXT
                ) { onObjectTool(ObjectEditTool.TEXT) }
                EditAction(
                    label = "Add image",
                    icon = Icons.Outlined.AddPhotoAlternate,
                    selected = objectState.tool == ObjectEditTool.IMAGE,
                    tag = UiTags.EDIT_ADD_IMAGE,
                    onClick = onAddImage
                )
                EditAction(
                    label = "Undo",
                    icon = Icons.AutoMirrored.Outlined.Undo,
                    enabled = objectState.canUndo,
                    tag = UiTags.EDIT_UNDO,
                    onClick = onUndoObject
                )
                EditAction(
                    label = "Redo",
                    icon = Icons.AutoMirrored.Outlined.Redo,
                    enabled = objectState.canRedo,
                    tag = UiTags.EDIT_REDO,
                    onClick = onRedoObject
                )
                EditAction(
                    label = "Save",
                    icon = Icons.Outlined.Save,
                    enabled = (objectState.dirty || hasMarkupChanges) && !saving,
                    tag = UiTags.EDIT_SAVE,
                    onClick = onSave
                )
            }
            if (objectState.fake) {
                Text(
                    text = "Demo engine - text and image edits are simulated, not written to the PDF.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ToolButton("Highlight", markupTool == AnnotationTool.HIGHLIGHT) {
                    onMarkupTool(AnnotationTool.HIGHLIGHT)
                }
                ToolButton("Ink", markupTool == AnnotationTool.INK) {
                    onMarkupTool(AnnotationTool.INK)
                }
                ToolButton("Note", markupTool == AnnotationTool.NOTE) {
                    onMarkupTool(AnnotationTool.NOTE)
                }
                Text("$annotationCount", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = onUndoMarkup, enabled = annotationCount > 0) { Text("Undo") }
                TextButton(onClick = onClearMarkup, enabled = annotationCount > 0) { Text("Clear") }
                if (fieldCount > 0) {
                    TextButton(onClick = onToggleForms) {
                        Text(if (showForms) "Hide fields" else "Fields ($fieldCount)")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean = false,
    enabled: Boolean = true,
    tag: String,
    onClick: () -> Unit
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        selected -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .testTag(tag)
            .width(58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = contentColor,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                Color.Transparent
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    ) {
        Text(label)
    }
}

/** Actions for the object selected on the page. */
@Composable
private fun SelectedObjectBar(
    objectState: EditableObject,
    onEditText: () -> Unit,
    onReplaceImage: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag(UiTags.EDIT_OBJECT_BAR),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (objectState.type == EditableObjectType.TEXT) {
                        Icons.Outlined.TextFields
                    } else {
                        Icons.Outlined.Photo
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (objectState.type == EditableObjectType.TEXT) {
                            "Text selected"
                        } else {
                            "Image selected"
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Drag to move. Corner handles resize.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (objectState.type == EditableObjectType.TEXT) {
                    TextButton(onClick = onEditText) { Text("Edit text") }
                } else {
                    TextButton(onClick = onReplaceImage) { Text("Replace") }
                }
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

private val TEXT_COLORS = listOf(
    0xFF111111L to "Black",
    0xFF444444L to "Charcoal",
    0xFF777777L to "Grey"
)

/** Add/edit text with font, size and colour, all monochrome-friendly. */
@Composable
private fun TextObjectDialog(
    existing: EditableObject?,
    onDismiss: () -> Unit,
    onConfirm: (String, EditableStyle) -> Unit
) {
    val initialStyle = existing?.style ?: EditableStyle()
    var content by remember(existing?.id) { mutableStateOf(existing?.content.orEmpty()) }
    var family by remember(existing?.id) { mutableStateOf(initialStyle.fontFamily) }
    var fontSize by remember(existing?.id) { mutableFloatStateOf(initialStyle.fontSize) }
    var color by remember(existing?.id) { mutableStateOf(initialStyle.color) }
    var fontMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add text" else "Edit text") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Text") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Box(modifier = Modifier.padding(top = 12.dp)) {
                    OutlinedButton(onClick = { fontMenu = true }) {
                        Text("Font: ${family.label}")
                    }
                    DropdownMenu(
                        expanded = fontMenu,
                        onDismissRequest = { fontMenu = false }
                    ) {
                        EditFontFamily.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    family = option
                                    fontMenu = false
                                }
                            )
                        }
                    }
                }
                Text(
                    text = "Size ${fontSize.roundToInt()}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    valueRange = 8f..48f
                )
                Text(
                    text = "Colour",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for ((argb, label) in TEXT_COLORS) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .border(
                                        width = if (color == argb) 2.dp else 1.dp,
                                        color = if (color == argb) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = CircleShape
                                    )
                                    .padding(5.dp)
                                    .background(Color(argb), CircleShape)
                                    .clickable { color = argb }
                            )
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        content.trim(),
                        EditableStyle(family, fontSize, color)
                    )
                },
                enabled = content.isNotBlank()
            ) {
                Text(if (existing == null) "Add" else "Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Editors for every AcroForm field (T12): text, checkbox, radio and choice. */
@Composable
private fun FormPanel(
    fields: List<PdfField>,
    values: Map<String, String>,
    loading: Boolean,
    onValue: (String, String) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        when {
            loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Text("  Loading fields…", style = MaterialTheme.typography.bodySmall)
            }
            fields.isEmpty() -> Text(
                "This document has no fillable fields.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp)
            )
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(fields) { field -> FieldRow(field, values, onValue) }
            }
        }
    }
}

@Composable
private fun FieldRow(field: PdfField, values: Map<String, String>, onValue: (String, String) -> Unit) {
    val value = values[field.name] ?: field.value ?: ""
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(field.name, style = MaterialTheme.typography.labelMedium)
        when (field.kind) {
            FieldKind.TEXT, FieldKind.CHOICE ->
                if (field.kind == FieldKind.CHOICE && field.options.isNotEmpty()) {
                    Column {
                        for (option in field.options) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = value == option.exportValue,
                                    onClick = { onValue(field.name, option.exportValue) }
                                )
                                Text(option.displayValue, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { onValue(field.name, it) },
                        singleLine = !field.multiline,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            FieldKind.CHECKBOX -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = value.isNotEmpty() && value != "Off",
                    onCheckedChange = { checked ->
                        val on = field.onStates.firstOrNull() ?: "Yes"
                        onValue(field.name, if (checked) on else "Off")
                    }
                )
                Text("checked", style = MaterialTheme.typography.bodySmall)
            }
            FieldKind.RADIO -> Column {
                val states = field.onStates.ifEmpty { field.options.map { it.exportValue } }
                for (state in states) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = value == state, onClick = { onValue(field.name, state) })
                        Text(
                            field.options.firstOrNull { it.exportValue == state }?.displayValue ?: state,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            else -> Text(
                "(${field.kind.name.lowercase()} field — read only)",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun NoteDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    title: String = "New note",
    fieldLabel: String = "Note",
    author: String? = null
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(if (title == "Add comment") "Add comment" else "Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(fieldLabel) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                if (author != null) {
                    Text(
                        "Signed as $author",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    )
}

@Composable
private fun SearchRow(
    query: String,
    matchCount: Int,
    activeIndex: Int,
    running: Boolean,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenRegex: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text("Find in document") },
            modifier = Modifier.weight(1f)
        )
        val label = when {
            running -> "…"
            matchCount == 0 -> "0"
            else -> "${activeIndex + 1}/$matchCount"
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = onPrevious, enabled = matchCount > 0) { Text("‹") }
        TextButton(onClick = onNext, enabled = matchCount > 0) { Text("›") }
        TextButton(
            onClick = onOpenRegex,
            modifier = Modifier.testTag(UiTags.REGEX_VIEWER_OPEN)
        ) {
            Text("Regex")
        }
    }
}

@Composable
private fun SelectionBar(
    text: String,
    allowHighlight: Boolean,
    onHighlight: () -> Unit,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = text.replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (allowHighlight) {
                TextButton(onClick = onHighlight) { Text("Highlight") }
            }
            TextButton(onClick = onCopy) { Text("Copy") }
            TextButton(onClick = onSelectAll) { Text("Select all") }
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
private fun PageItem(
    index: Int,
    box: PageBox?,
    bitmap: Bitmap?,
    plan: PageRenderPlan?,
    searchRects: List<TextRect>,
    selectionRects: List<TextRect>,
    /** T64: the words the service is speaking right now, drawn in amber. */
    speechRects: List<TextRect>,
    focusRect: PdfRect?,
    editTool: AnnotationTool?,
    objectTool: ObjectEditTool?,
    objects: List<EditableObject>,
    selectedObjectId: String?,
    annotations: List<AnnotationSpec>,
    inkDraft: InkDraft?,
    inkState: InkUiState,
    inkStrokes: List<InkStroke>,
    activeInk: InkStroke?,
    targetPixelWidth: Int,
    /** Bumped when the page theme changes, so the item asks for a new render. */
    themeGeneration: Int,
    /** Paper colour of the current page theme (T32/T32b). */
    pageBackground: Color,
    onVisible: (Int, Int) -> Unit,
    onHidden: (Int) -> Unit,
    linkTapEnabled: Boolean,
    onTap: (Int, Float, Float) -> Boolean,
    onSelect: (Int, Float, Float, Boolean) -> Unit,
    onInkStart: (Int, Float, Float, Float, Float, Boolean) -> Unit,
    onInkExtend: (Float, Float, Float, Float, Boolean) -> Unit,
    onInkEnd: (Boolean) -> Unit,
    onNoteTap: (Int, Float, Float) -> Unit,
    onPlaceText: (Int, Float, Float) -> Unit,
    onPlaceImage: (Int, Float, Float) -> Unit,
    onSelectObject: (String) -> Unit,
    onBoundsChange: (String, EditableBounds) -> Unit,
    onClearObjectSelection: () -> Unit,
    stamp: StampPlacement?,
    stampAuthor: String,
    onStampTransform: (Float, Float, Float) -> Unit,
    signature: SignaturePlacementUi?,
    onSignatureTransform: (Float, Float, Float) -> Unit,
    signatureTap: Boolean,
    onSignatureTap: (Int, Float, Float) -> Unit,
    /** Task T58: non-null while the reading gesture layer owns a plain tap. */
    turnSettings: TurnSettings? = null,
    onTurn: (TurnDirection) -> Unit = {},
    onCenterTap: () -> Unit = {},
    onCenterDoubleTap: () -> Unit = {},
    /** True when the caller sized this page itself (horizontal paging). */
    fillSlot: Boolean = false
) {
    val aspect = remember(box) {
        val (w, h) = if (box != null) box.rotatedWidth to box.rotatedHeight else 612.0 to 792.0
        if (h > 0.0) (w / h).toFloat() else 612f / 792f
    }
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    val inkTracker = remember(index) { InkGestureTracker() }

    // The key handed to pointerInput must change whenever the active gesture
    // changes. Sharing the Unit key meant switching from text selection to a
    // note/text tap kept the old word-selection handler installed.
    val gesture = when {
        signatureTap ->
            Modifier.noteTapGesture(key = "signature-place-$index") { fx, fy ->
                onSignatureTap(index, fx, fy)
            }
        editTool == AnnotationTool.INK -> Modifier.stylusInkGestures(
            key = "ink-$index-${inkState.stylusOnly}-${inkState.mode}-$pageSize",
            pageIndex = index,
            pageWidth = pageSize.width.toFloat(),
            pageHeight = pageSize.height.toFloat(),
            stylusOnly = inkState.stylusOnly,
            tracker = inkTracker,
            onStart = onInkStart,
            onExtend = onInkExtend,
            onEnd = onInkEnd
        )
        editTool == AnnotationTool.NOTE ->
            Modifier.noteTapGesture(key = "note-$index") { fx, fy -> onNoteTap(index, fx, fy) }
        objectTool == ObjectEditTool.TEXT ->
            Modifier.noteTapGesture(key = ObjectEditTool.TEXT to objects) { fx, fy ->
                if (objects.none { it.bounds.contains(fx, fy) }) onPlaceText(index, fx, fy)
            }
        objectTool == ObjectEditTool.IMAGE ->
            Modifier.noteTapGesture(key = ObjectEditTool.IMAGE to objects) { fx, fy ->
                if (objects.none { it.bounds.contains(fx, fy) }) onPlaceImage(index, fx, fy)
            }
        objectTool == ObjectEditTool.SELECT ->
            Modifier.noteTapGesture(key = ObjectEditTool.SELECT to objects) { fx, fy ->
                if (objects.none { it.bounds.contains(fx, fy) }) onClearObjectSelection()
            }
        turnSettings != null -> Modifier.pageTurnGestures(
            key = "turn-$index-$linkTapEnabled-${turnSettings.zonePercent}-" +
                "${turnSettings.leftHanded}-${turnSettings.verticalZones}",
            settings = turnSettings,
            onTurn = onTurn,
            onCenterTap = onCenterTap,
            onCenterDoubleTap = onCenterDoubleTap,
            onTap = { fx, fy -> onTap(index, fx, fy) }
        ) { fx, fy, extend ->
            onSelect(index, fx, fy, extend)
        }
        else -> Modifier.wordSelectionGestures(
            key = "select-$index-$linkTapEnabled",
            onTap = { fx, fy -> onTap(index, fx, fy) }
        ) { fx, fy, extend ->
            onSelect(index, fx, fy, extend)
        }
    }

    Box(
        modifier = Modifier
            .then(if (fillSlot) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .then(if (fillSlot) Modifier else Modifier.aspectRatio(aspect))
            .shadow(2.dp)
            .background(pageBackground)
            .onSizeChanged { pageSize = it }
            .then(gesture),
        contentAlignment = Alignment.Center
    ) {
        // The box is part of the key: turning "Fit content" on or off drops
        // the old bitmap, and the page must ask for a render of the new box
        // even when the target width did not change (T57). The theme
        // generation does the same for a dark/true-black switch (T32b).
        LaunchedEffect(index, targetPixelWidth, box, themeGeneration) {
            onVisible(index, targetPixelWidth)
        }
        DisposableEffect(index) {
            onDispose { onHidden(index) }
        }

        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillWidth
            )
            HighlightOverlay(plan, searchRects, selectionRects, speechRects)
            FocusMarkOverlay(plan, focusRect)
            PendingOverlay(plan, annotations, inkDraft)
            if (editTool == AnnotationTool.INK) {
                InkOverlay(
                    plan = plan,
                    strokes = inkStrokes,
                    active = activeInk,
                    lasso = inkState.lasso,
                    selected = inkState.selected,
                    mode = inkState.mode
                )
            }
            ObjectLayer(
                objects = objects,
                selectedId = selectedObjectId,
                editing = objectTool != null,
                onSelect = onSelectObject,
                onBoundsChange = onBoundsChange
            )
            if (stamp != null) {
                StampPlacementOverlay(
                    placement = stamp,
                    author = stampAuthor,
                    modifier = Modifier.fillMaxSize(),
                    onTransform = onStampTransform
                )
            }
            if (signature != null) {
                SignaturePlacementOverlay(
                    placement = signature,
                    modifier = Modifier.fillMaxSize(),
                    onTransform = onSignatureTransform
                )
            }
        } else {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
    }
}

/**
 * Draws editable text and image objects over the page. Bounds are page
 * fractions, so the same object stays put through zoom and re-render.
 */
@Composable
private fun ObjectLayer(
    objects: List<EditableObject>,
    selectedId: String?,
    editing: Boolean,
    onSelect: (String) -> Unit,
    onBoundsChange: (String, EditableBounds) -> Unit
) {
    if (objects.isEmpty()) return
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pageWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val pageHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        for (item in objects) {
            EditableObjectBox(
                item = item,
                selected = editing && item.id == selectedId,
                editing = editing,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                onSelect = onSelect,
                onBoundsChange = onBoundsChange
            )
        }
    }
}

@Composable
private fun EditableObjectBox(
    item: EditableObject,
    selected: Boolean,
    editing: Boolean,
    pageWidth: Float,
    pageHeight: Float,
    onSelect: (String) -> Unit,
    onBoundsChange: (String, EditableBounds) -> Unit
) {
    val density = LocalDensity.current
    var liveBounds by remember(item.id, item.bounds) { mutableStateOf(item.bounds) }
    val handle = remember(item.id) { mutableStateOf(EditableHandle.NONE) }

    val widthPx = (liveBounds.width * pageWidth).coerceAtLeast(1f)
    val heightPx = (liveBounds.height * pageHeight).coerceAtLeast(1f)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (liveBounds.left * pageWidth).roundToInt(),
                    (liveBounds.top * pageHeight).roundToInt()
                )
            }
            .width(with(density) { widthPx.toDp() })
            .height(with(density) { heightPx.toDp() })
            .zIndex(if (selected) 2f else 1f)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(2.dp)
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (editing) {
                    Modifier
                        .pointerInput(item.id, selected, pageWidth, pageHeight) {
                            detectTapGestures { onSelect(item.id) }
                        }
                        .pointerInput(item.id, selected, pageWidth, pageHeight) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    onSelect(item.id)
                                    handle.value = if (selected) {
                                        val grip = 28.dp.toPx()
                                        when {
                                            start.x <= grip && start.y <= grip ->
                                                EditableHandle.TOP_LEFT
                                            start.x >= size.width - grip && start.y <= grip ->
                                                EditableHandle.TOP_RIGHT
                                            start.x <= grip && start.y >= size.height - grip ->
                                                EditableHandle.BOTTOM_LEFT
                                            start.x >= size.width - grip && start.y >= size.height - grip ->
                                                EditableHandle.BOTTOM_RIGHT
                                            else -> EditableHandle.NONE
                                        }
                                    } else {
                                        EditableHandle.NONE
                                    }
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    val dx = drag.x / pageWidth
                                    val dy = drag.y / pageHeight
                                    liveBounds = if (handle.value == EditableHandle.NONE) {
                                        liveBounds.movedBy(dx, dy)
                                    } else {
                                        liveBounds.resized(handle.value, dx, dy)
                                    }
                                },
                                onDragEnd = { onBoundsChange(item.id, liveBounds) },
                                onDragCancel = { liveBounds = item.bounds }
                            )
                        }
                } else {
                    Modifier
                }
            )
    ) {
        EditableObjectContent(item = item, pagePixelWidth = pageWidth, compact = false)
        if (selected) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val grip = 5.dp.toPx()
                val points = listOf(
                    Offset(0f, 0f),
                    Offset(size.width, 0f),
                    Offset(0f, size.height),
                    Offset(size.width, size.height)
                )
                for (point in points) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(point.x - grip, point.y - grip),
                        size = Size(grip * 2f, grip * 2f)
                    )
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(point.x - grip + 1f, point.y - grip + 1f),
                        size = Size(grip * 2f - 2f, grip * 2f - 2f),
                        style = Stroke(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun EditableObjectContent(
    item: EditableObject,
    pagePixelWidth: Float,
    compact: Boolean
) {
    when (item.type) {
        EditableObjectType.TEXT -> {
            val size = (item.style.fontSize * pagePixelWidth / 612f)
                .coerceAtLeast(7f)
                .sp
            Text(
                text = item.content,
                color = Color(item.style.color),
                fontFamily = when (item.style.fontFamily) {
                    EditFontFamily.SANS -> FontFamily.SansSerif
                    EditFontFamily.SERIF -> FontFamily.Serif
                    EditFontFamily.MONO -> FontFamily.Monospace
                },
                fontSize = size,
                lineHeight = (size.value * 1.15f).sp,
                maxLines = if (compact) 2 else 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxSize().padding(2.dp)
            )
        }
        EditableObjectType.IMAGE -> {
            val bitmap = rememberUriImage(item.content)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Placed image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Photo,
                        contentDescription = "Placed image",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberUriImage(uri: String): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    return bitmap
}

/**
 * Search hits (yellow), the selection (blue) and the words being spoken (amber)
 * drawn over the page bitmap.
 */
@Composable
private fun HighlightOverlay(
    plan: PageRenderPlan?,
    searchRects: List<TextRect>,
    selectionRects: List<TextRect>,
    speechRects: List<TextRect>
) {
    if (plan == null) return
    if (searchRects.isEmpty() && selectionRects.isEmpty() && speechRects.isEmpty()) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawTextRects(plan, searchRects, Color(0x66FFEB3B))
        drawTextRects(plan, selectionRects, Color(0x6690CAF9))
        drawTextRects(plan, speechRects, Color(0x66FFB300))
    }
}

/**
 * T56: a bright outline around the annotation the hub opened the file for. It
 * is drawn over the page bitmap, never into it, and disappears on its own.
 */
@Composable
private fun FocusMarkOverlay(plan: PageRenderPlan?, rect: PdfRect?) {
    if (plan == null || rect == null || rect.isEmpty) return
    if (plan.pixelWidth <= 0 || plan.pixelHeight <= 0) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val sx = size.width / plan.pixelWidth
        val sy = size.height / plan.pixelHeight
        val a = plan.matrix.apply(rect.left, rect.bottom)
        val b = plan.matrix.apply(rect.right, rect.top)
        val left = (min(a[0], b[0]) * sx).toFloat()
        val top = (min(a[1], b[1]) * sy).toFloat()
        val width = ((max(a[0], b[0]) - min(a[0], b[0])) * sx).toFloat()
        val height = ((max(a[1], b[1]) - min(a[1], b[1])) * sy).toFloat()
        if (width <= 0f || height <= 0f) return@Canvas
        val pad = 4f
        drawRect(
            color = Color(0x33FF6D00),
            topLeft = Offset(left - pad, top - pad),
            size = Size(width + pad * 2, height + pad * 2)
        )
        drawRect(
            color = Color(0xFFFF6D00),
            topLeft = Offset(left - pad, top - pad),
            size = Size(width + pad * 2, height + pad * 2),
            style = Stroke(width = 2.5f)
        )
    }
}

/** Maps page-space rectangles through the render plan onto the drawn bitmap. */
private fun DrawScope.drawTextRects(plan: PageRenderPlan, rects: List<TextRect>, color: Color) {    if (plan.pixelWidth <= 0 || plan.pixelHeight <= 0) return
    val sx = size.width / plan.pixelWidth
    val sy = size.height / plan.pixelHeight
    for (rect in rects) {
        val a = plan.matrix.apply(rect.x0, rect.y0)
        val b = plan.matrix.apply(rect.x1, rect.y1)
        val left = min(a[0], b[0]) * sx
        val top = min(a[1], b[1]) * sy
        val width = (max(a[0], b[0]) - min(a[0], b[0])) * sx
        val height = (max(a[1], b[1]) - min(a[1], b[1])) * sy
        if (width <= 0.0 || height <= 0.0) continue
        drawRect(
            color = color,
            topLeft = Offset(left.toFloat(), top.toFloat()),
            size = Size(width.toFloat(), height.toFloat())
        )
    }
}

/** Pending annotations (highlight, ink, note) drawn until the file is saved (T12). */
@Composable
private fun PendingOverlay(
    plan: PageRenderPlan?,
    annotations: List<AnnotationSpec>,
    inkDraft: InkDraft?
) {
    if (plan == null || (annotations.isEmpty() && inkDraft == null)) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawPendingAnnotations(plan, annotations, inkDraft)
    }
}

private fun DrawScope.pagePoint(plan: PageRenderPlan, x: Double, y: Double): Offset {
    val p = plan.matrix.apply(x, y)
    return Offset(
        (p[0] * size.width / plan.pixelWidth).toFloat(),
        (p[1] * size.height / plan.pixelHeight).toFloat()
    )
}

private fun DrawScope.drawPendingAnnotations(
    plan: PageRenderPlan,
    annotations: List<AnnotationSpec>,
    inkDraft: InkDraft?
) {
    if (plan.pixelWidth <= 0 || plan.pixelHeight <= 0) return
    val highlightColor = Color(0x99FFE082)
    val noteColor = Color(0xFFFFE082)
    val noteBorder = Color(0xFFB58900)
    val inkColor = Color(0xFF1A1A1A)
    for (spec in annotations) {
        when (spec) {
            is HighlightSpec -> for (r in spec.rects) {
                val a = pagePoint(plan, r.left, r.top)
                val b = pagePoint(plan, r.right, r.bottom)
                drawRect(
                    color = highlightColor,
                    topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
                    size = Size(abs(b.x - a.x), abs(b.y - a.y))
                )
            }
            is InkSpec -> for (stroke in spec.strokes) drawPolyline(plan, stroke, inkColor, 3f)
            is NoteSpec -> {
                val a = pagePoint(plan, spec.rect.left, spec.rect.top)
                val b = pagePoint(plan, spec.rect.right, spec.rect.bottom)
                val topLeft = Offset(min(a.x, b.x), min(a.y, b.y))
                val extent = Size(abs(b.x - a.x).coerceAtLeast(10f), abs(b.y - a.y).coerceAtLeast(10f))
                drawRect(color = noteColor, topLeft = topLeft, size = extent)
                drawRect(color = noteBorder, topLeft = topLeft, size = extent, style = Stroke(1.5f))
            }
            // Comments written by the engine CLI (T22) and stamps (T23); the
            // viewer has no tool that creates them, so nothing is pending.
            is UnderlineSpec, is ReplySpec, is StatusSpec, is StampSpec -> Unit
            else -> Unit
        }
    }
    if (inkDraft != null) drawPolyline(plan, inkDraft.points, inkColor, 3f)
}

private fun DrawScope.drawPolyline(plan: PageRenderPlan, points: List<Double>, color: Color, width: Float) {
    var i = 0
    while (i + 3 < points.size) {
        drawLine(
            color = color,
            start = pagePoint(plan, points[i], points[i + 1]),
            end = pagePoint(plan, points[i + 2], points[i + 3]),
            strokeWidth = width
        )
        i += 2
    }
}


/** Editable ink layer, lasso trail and selection outline (T54). */
@Composable
private fun InkOverlay(
    plan: PageRenderPlan?,
    strokes: List<InkStroke>,
    active: InkStroke?,
    lasso: List<InkSample>,
    selected: Set<Long>,
    mode: InkToolMode
) {
    if (plan == null) return
    if (strokes.isEmpty() && active == null && lasso.size < 2) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        for (stroke in strokes) drawInkStroke(plan, stroke, stroke.id in selected)
        if (active != null) drawInkStroke(plan, active, false)
        if (lasso.size >= 2) {
            val path = Path()
            val first = pagePoint(plan, lasso[0].x, lasso[0].y)
            path.moveTo(first.x, first.y)
            for (sample in lasso.drop(1)) {
                val point = pagePoint(plan, sample.x, sample.y)
                path.lineTo(point.x, point.y)
            }
            drawPath(
                path = path,
                color = Color(0xFF00897B),
                style = Stroke(
                    width = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
            )
        }
    }
}

private fun DrawScope.drawInkStroke(
    plan: PageRenderPlan,
    stroke: InkStroke,
    selected: Boolean
) {
    if (stroke.samples.isEmpty() || plan.pixelWidth <= 0 || plan.pixelHeight <= 0) return
    val points = if (stroke.samples.size >= 3) {
        InkGeometry.catmullRom(stroke.samples, subdivisions = 2)
    } else {
        stroke.samples
    }
    val color = Color(
        stroke.color.red.toFloat(),
        stroke.color.green.toFloat(),
        stroke.color.blue.toFloat()
    )
    val fullWidth = InkTransform.widthPx(stroke, plan, size.width).coerceAtLeast(1f)
    val alpha = if (stroke.highlighter) stroke.opacity.toFloat() else 1f
    val blend = if (stroke.highlighter) BlendMode.Multiply else BlendMode.SrcOver
    if (points.size == 1) {
        drawCircle(
            color = color,
            radius = fullWidth / 2f,
            center = pagePoint(plan, points[0].x, points[0].y),
            alpha = alpha,
            blendMode = blend
        )
    } else {
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val pressure = (a.pressure + b.pressure) / 2.0
            val width = if (stroke.highlighter) {
                fullWidth
            } else {
                fullWidth * (0.35 + 0.65 * pressure).toFloat()
            }
            drawLine(
                color = color,
                start = pagePoint(plan, a.x, a.y),
                end = pagePoint(plan, b.x, b.y),
                strokeWidth = width.coerceAtLeast(1f),
                cap = StrokeCap.Round,
                alpha = alpha,
                blendMode = blend
            )
        }
    }
    if (selected) {
        val box = InkGeometry.bounds(stroke.samples, stroke.width) ?: return
        val a = pagePoint(plan, box.left, box.bottom)
        val b = pagePoint(plan, box.right, box.top)
        drawRect(
            color = Color(0xFF00897B),
            topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
            size = Size(abs(b.x - a.x), abs(b.y - a.y)),
            style = Stroke(width = 1.5f)
        )
    }
}

/**
 * Tap to select the word under the finger, or hold and drag to extend the
 * selection by word. Movement before the long-press timeout is left to the
 * parent, so scrolling still works.
 */
/**
 * T58 reading gestures. An edge tap fires on release with no double-tap wait,
 * so a thumb never feels a delay; only the centre waits, to tell a single tap
 * (toggle the toolbars) from a double tap (zoom). While this layer is
 * installed, text selection starts with a long press plus drag, which is what
 * keeps a plain tap free for turning. A drag that is a scroll is left to the
 * list, so paging and pinch-to-zoom keep working.
 */
private fun Modifier.pageTurnGestures(
    key: Any?,
    settings: TurnSettings,
    onTurn: (TurnDirection) -> Unit,
    onCenterTap: () -> Unit,
    onCenterDoubleTap: () -> Unit,
    onTap: ((fx: Float, fy: Float) -> Boolean)? = null,
    onSelect: (fx: Float, fy: Float, extend: Boolean) -> Unit
): Modifier = pointerInput(key) {
    fun fraction(offset: Offset): Pair<Float, Float> =
        offset.x / size.width.coerceAtLeast(1) to offset.y / size.height.coerceAtLeast(1)

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var dragging = false
        var moved = false
        var lifted: Offset? = null
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                lifted = change.position
                break
            }
            if ((change.position - down.position).getDistance() > slop) {
                moved = true
                if (
                    !dragging &&
                    change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                ) {
                    dragging = true
                    val (fx, fy) = fraction(down.position)
                    onSelect(fx, fy, false)
                }
                if (dragging) {
                    val (fx, fy) = fraction(change.position)
                    onSelect(fx, fy, true)
                    change.consume()
                }
            }
        }
        val up = lifted
        if (dragging) {
            if (up != null) onSelect(fraction(up).first, fraction(up).second, false)
            return@awaitEachGesture
        }
        if (moved || up == null) return@awaitEachGesture

        val zone = TurnZones.zoneAt(
            x = up.x,
            y = up.y,
            width = size.width.toFloat(),
            height = size.height.toFloat(),
            settings = settings
        )
        val direction = TurnZones.directionFor(zone, settings)
        if (direction != null) {
            onTurn(direction)
            return@awaitEachGesture
        }

        val (fx, fy) = fraction(up)
        // A link wins over the toolbar toggle: the tap is where the user aimed.
        val finger = down.type == PointerType.Touch || down.type == PointerType.Mouse
        if (finger && onTap?.invoke(fx, fy) == true) return@awaitEachGesture

        // Only the centre waits for a possible second tap.
        val secondWasDrag = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
            val secondDown = awaitFirstDown(requireUnconsumed = false)
            var secondMoved = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == secondDown.id } ?: break
                if ((change.position - secondDown.position).getDistance() > slop) {
                    secondMoved = true
                }
                if (!change.pressed) break
            }
            secondMoved
        }
        when (secondWasDrag) {
            null -> onCenterTap()
            false -> onCenterDoubleTap()
            // The second gesture became a scroll, so neither action applies.
            true -> Unit
        }
    }
}

/**
 * T58: the one-time volume-key offer. It is a card, not a dialog: reading can
 * continue underneath it, and both answers are ordinary buttons so TalkBack
 * reaches them without a gesture that page turning could swallow.
 */
@Composable
private fun VolumeKeyHint(
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
        modifier = modifier.testTag(UiTags.VIEWER_VOLUME_HINT)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                "Turn pages with the volume keys?",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                "Volume down goes forward, volume up goes back. You can change this in " +
                    "Settings > Reading.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(UiTags.VIEWER_VOLUME_HINT_DISMISS)
                ) {
                    Text("Not now")
                }
                TextButton(
                    onClick = onEnable,
                    modifier = Modifier.testTag(UiTags.VIEWER_VOLUME_HINT_ENABLE)
                ) {
                    Text("Enable")
                }
            }
        }
    }
}

private fun Modifier.wordSelectionGestures(
    key: Any?,
    onTap: ((fx: Float, fy: Float) -> Boolean)? = null,
    onSelect: (fx: Float, fy: Float, extend: Boolean) -> Unit
): Modifier = pointerInput(key) {
    fun fraction(offset: Offset): Pair<Float, Float> =
        offset.x / size.width.coerceAtLeast(1) to offset.y / size.height.coerceAtLeast(1)

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var dragging = false
        var tap = true
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (tap && !dragging) {
                    val (fx, fy) = fraction(change.position)
                    // A finger tap may hit a link; a stylus tap always selects
                    // text so annotating with a pen keeps working (T61).
                    val finger = down.type == PointerType.Touch || down.type == PointerType.Mouse
                    val handled = finger && onTap?.invoke(fx, fy) == true
                    if (!handled) onSelect(fx, fy, false)
                } else if (dragging) {
                    val (fx, fy) = fraction(change.position)
                    onSelect(fx, fy, false)
                }
                break
            }
            if ((change.position - down.position).getDistance() > slop) {
                if (!dragging && change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
                    dragging = true
                    val (fx, fy) = fraction(down.position)
                    onSelect(fx, fy, false)
                }
                if (dragging) {
                    val (fx, fy) = fraction(change.position)
                    onSelect(fx, fy, true)
                    change.consume()
                } else {
                    tap = false
                }
            }
        }
    }
}

/** Per-page gesture state; remembered so a recomposition cannot split a stroke. */
private class InkGestureTracker {
    var active = false
    var lastMoveTime = 0L
    var eraser = false
    var penActive = false
    var lastPenTime = 0L
}

private fun tiltDegrees(event: MotionEvent, historical: Int = -1): Float {
    val radians = if (historical >= 0) {
        event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, 0, historical)
    } else {
        event.getAxisValue(MotionEvent.AXIS_TILT)
    }
    if (!radians.isFinite()) return 0f
    return Math.toDegrees(radians.toDouble()).toFloat().coerceIn(0f, 90f)
}

private fun pressureOf(event: MotionEvent, historical: Int = -1): Float {
    val value = if (historical >= 0) {
        event.getHistoricalPressure(0, historical)
    } else {
        event.pressure
    }
    return if (!value.isFinite() || value <= 0.001f) 1f else value.coerceIn(0f, 1f)
}

/**
 * T54 stylus input. `MotionEvent` is used instead of Compose pointer changes
 * because only the platform event exposes pressure, `AXIS_TILT` and the
 * historical samples a fast pen produces between frames. A stylus button is an
 * eraser, and stylus-only mode lets a finger keep scrolling/zooming.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.stylusInkGestures(
    key: Any?,
    pageIndex: Int,
    pageWidth: Float,
    pageHeight: Float,
    stylusOnly: Boolean,
    tracker: InkGestureTracker,
    onStart: (Int, Float, Float, Float, Float, Boolean) -> Unit,
    onExtend: (Float, Float, Float, Float, Boolean) -> Unit,
    onEnd: (Boolean) -> Unit
): Modifier = pointerInteropFilter { event ->
    val width = pageWidth.coerceAtLeast(1f)
    val height = pageHeight.coerceAtLeast(1f)
    val toolType = event.getToolType(0)
    val stylus = InkInputPolicy.isStylus(toolType)
    if (stylus) tracker.lastPenTime = event.eventTime
    // Palm rejection: a finger contact is ignored while the pen is down or was
    // just lifted. Without this, the heel of a hand can scroll the page or add
    // a second stroke as soon as the reader switches tools.
    val palm = InkInputPolicy.isPalm(
        toolType,
        event.eventTime,
        tracker.penActive,
        tracker.lastPenTime
    )
    if (palm) return@pointerInteropFilter false
    fun send(eventX: Float, eventY: Float, historical: Int = -1) {
        onExtend(
            eventX / width,
            eventY / height,
            pressureOf(event, historical),
            tiltDegrees(event, historical),
            tracker.eraser
        )
    }
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            if (InkInputPolicy.rejectedByStylusOnly(toolType, stylusOnly)) {
                return@pointerInteropFilter false
            }
            tracker.active = true
            tracker.penActive = stylus
            tracker.eraser = InkInputPolicy.isEraser(toolType, event.buttonState)
            tracker.lastMoveTime = event.eventTime
            onStart(
                pageIndex,
                event.x / width,
                event.y / height,
                pressureOf(event),
                tiltDegrees(event),
                tracker.eraser
            )
            true
        }
        MotionEvent.ACTION_MOVE -> {
            if (!tracker.active) return@pointerInteropFilter false
            if (InkInputPolicy.rejectedByStylusOnly(toolType, stylusOnly)) {
                return@pointerInteropFilter true
            }
            for (index in 0 until event.historySize) {
                send(event.getHistoricalX(0, index), event.getHistoricalY(0, index), index)
            }
            send(event.x, event.y)
            tracker.lastMoveTime = event.eventTime
            true
        }
        MotionEvent.ACTION_UP -> {
            if (!tracker.active) return@pointerInteropFilter false
            send(event.x, event.y)
            val held = event.eventTime - tracker.lastMoveTime >= 550L
            tracker.active = false
            tracker.penActive = false
            onEnd(held)
            true
        }
        MotionEvent.ACTION_CANCEL -> {
            if (!tracker.active) return@pointerInteropFilter false
            tracker.active = false
            tracker.penActive = false
            onEnd(false)
            true
        }
        else -> false
    }
}

/** Tap anywhere on the page to drop a sticky note there (T12). */
private fun Modifier.noteTapGesture(
    key: Any?,
    onTap: (fx: Float, fy: Float) -> Unit
): Modifier = pointerInput(key) {
    fun fraction(offset: Offset): Pair<Float, Float> =
        offset.x / size.width.coerceAtLeast(1) to offset.y / size.height.coerceAtLeast(1)

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var moved = false
        var tapped = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (!moved) tapped = true
                break
            }
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                moved = true
            }
        }
        if (tapped) {
            val (fx, fy) = fraction(down.position)
            onTap(fx, fy)
        }
    }
}

/**
 * Pinch-to-zoom that leaves single-pointer gestures alone, so the LazyColumn
 * keeps scrolling (and the horizontal pan keeps working) while two fingers
 * scale the content.
 */
private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                val factor = event.calculateZoom()
                if (factor.isFinite() && factor > 0f && factor != 1f) {
                    onZoom(factor)
                    event.changes.forEach { it.consume() }
                }
            }
            if (event.changes.none { it.pressed }) break
        }
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "Viewer edit mode - light")
@Composable
private fun ViewerEditModeLightPreview() {
    PDFPaperTheme(darkTheme = false) {
        val sample = EditableObject(
            id = "preview-text",
            type = EditableObjectType.TEXT,
            pageIndex = 0,
            bounds = EditableBounds(0.12f, 0.22f, 0.72f, 0.32f),
            content = "Project notes",
            style = EditableStyle(fontSize = 26f, color = 0xFF111111)
        )
        val objectState = ObjectEditState(
            objects = listOf(sample),
            selectedId = sample.id,
            tool = ObjectEditTool.SELECT,
            canUndo = true,
            canRedo = false,
            dirty = true,
            fake = true,
            engineName = "FakeEngine (demo)"
        )
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ViewerToolbar(
                title = "Project notes.pdf",
                subtitle = "Edit text",
                zoom = 1f,
                findActive = false,
                canPresent = false,
                canUndo = true,
                canRedo = false,
                versionCount = 0,
                onVersions = {},
                onPresent = {},
                onBookmark = {},
                onBack = {},
                onZoomIn = {},
                onZoomOut = {},
                onFit = {},
                onReflow = {},
                onFind = {},
                onUndo = {},
                onRedo = {},
                onMore = {}
            )
            EditBar(
                toolName = "Edit text",
                objectState = objectState,
                markupTool = AnnotationTool.HIGHLIGHT,
                annotationCount = 2,
                fieldCount = 0,
                showForms = false,
                saving = false,
                hasMarkupChanges = false,
                onObjectTool = {},
                onAddImage = {},
                onMarkupTool = {},
                onUndoObject = {},
                onRedoObject = {},
                onUndoMarkup = {},
                onClearMarkup = {},
                onSave = {},
                onToggleForms = {},
                onDone = {},
                onCancel = {}
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp)) {
                Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
                    ObjectLayer(
                        objects = listOf(sample),
                        selectedId = sample.id,
                        editing = true,
                        onSelect = {},
                        onBoundsChange = { _, _ -> }
                    )
                }
            }
            SelectedObjectBar(
                objectState = sample,
                onEditText = {},
                onReplaceImage = {},
                onDelete = {},
                onDone = {}
            )
        }
    }
}


/**
 * Task T56: asks for a bookmark label and colour. A bookmark is app data; the
 * checkbox mirrors the setting that also writes bookmarks into the PDF outline,
 * so the user can decide once and have it remembered.
 */
@Composable
private fun BookmarkDialog(
    pageIndex: Int,
    initialOutline: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (label: String, color: String?, outline: Boolean) -> Unit
) {
    var label by remember { mutableStateOf("Page ${pageIndex + 1}") }
    var color by remember { mutableStateOf(com.pdfpaper.app.annot.AnnotationColors.default) }
    var outline by remember { mutableStateOf(initialOutline) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmark page ${pageIndex + 1}") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("Label") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.VIEWER_BOOKMARK_LABEL)
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    for (swatch in com.pdfpaper.app.annot.AnnotationColors.all) {
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(com.pdfpaper.app.annot.AnnotationColors.argb(swatch.hex)))
                                .clickable { color = swatch.hex }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = outline, onCheckedChange = { outline = it })
                    Text(
                        "Also write bookmarks into the PDF outline",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label, color, outline) },
                modifier = Modifier.testTag(UiTags.VIEWER_BOOKMARK_ADD)
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** T65: the regex bottom sheet in the viewer (document scope). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegexSheetHost(
    regexState: RegexUiState,
    displayName: String,
    onDismiss: () -> Unit,
    viewModel: ViewerViewModel,
    onTest: (String, Set<RegexFlag>) -> Unit,
    onExport: (RegexUiState) -> Unit,
    onRowFocus: (Int, com.pdfpaper.engine.text.TextRect) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // The panel and the results list need the whole sheet: a half-expanded
        // sheet clips the last rows and the export actions.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(UiTags.REGEX_SHEET)
    ) {
        // The sheet gives the grouped list a bounded height: a weighted
        // LazyColumn inside a verticalScroll column would measure to zero and
        // hide every result row.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
        ) {
            RegexPanel(
                state = regexState,
                saved = emptyList(),
                onPatternChange = {
                    viewModel.setRegexPattern(it)
                    onTest(it, regexState.flags)
                },
                onFlagsChange = {
                    viewModel.setRegexFlags(it)
                    onTest(regexState.pattern, it)
                },
                onRun = { viewModel.runDocumentRegex(regexState.pattern, regexState.flags) },
                onCancel = viewModel::cancelDocumentRegex,
                onExample = { example ->
                    viewModel.setRegexPattern(example.pattern)
                    onTest(example.pattern, regexState.flags)
                },
                onSavePattern = {},
                onApplySaved = {},
                onRenameSaved = { _, _ -> },
                onDeleteSaved = {}
            )
            RegexResults(
                state = regexState,
                onActive = viewModel::setRegexActive,
                onRow = { row ->
                    val bounds = row.rects.firstOrNull()
                    if (bounds != null) {
                        onRowFocus(row.pageIndex, bounds)
                    }
                },
                onUniqueChange = viewModel::setRegexUniqueOnly,
                onExport = { onExport(regexState) },
                onHighlightAll = { viewModel.highlightRegexMatches() },
                onRedactAll = { viewModel.handOffRegexToRedactions() },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
