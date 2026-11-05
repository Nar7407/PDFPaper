package com.pdfpaper.app.ui.navigation

import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.data.DocumentAccess
import com.pdfpaper.app.data.ImagePdf
import com.pdfpaper.app.data.IncomingKind
import com.pdfpaper.app.data.IncomingRequest
import com.pdfpaper.app.launcher.LauncherAction
import com.pdfpaper.app.launcher.LauncherLaunchRequest
import com.pdfpaper.app.ui.UiTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pdfpaper.app.edit.EditingRuntime
import com.pdfpaper.app.office.OfficeKind
import com.pdfpaper.app.ui.annot.AnnotationsScreen
import com.pdfpaper.app.ui.components.AppBottomBar
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.app.ui.components.WorkspaceHeader
import com.pdfpaper.app.ui.compress.CompressScreen
import com.pdfpaper.app.ui.crop.CropScreen
import com.pdfpaper.app.ui.export.ExportImagesScreen
import com.pdfpaper.app.ui.home.HomeScreen
import com.pdfpaper.app.ui.home.HomeViewModel
import com.pdfpaper.app.ui.mark.BatesScreen
import com.pdfpaper.app.ui.mark.PageNumbersScreen
import com.pdfpaper.app.ui.mark.WatermarkScreen
import com.pdfpaper.app.ui.motion.LocalReduceMotion
import com.pdfpaper.app.ui.motion.NavMotion
import com.pdfpaper.app.ui.open.OpenDocumentScreen
import com.pdfpaper.app.ui.pages.PagesScreen
import com.pdfpaper.app.ui.pages.TocSplitRoute
import com.pdfpaper.app.ui.recent.RecentScreen
import com.pdfpaper.app.ui.scan.ScanScreen
import com.pdfpaper.app.ui.safety.SafetyScreen
import com.pdfpaper.app.ui.settings.SettingsScreen
import com.pdfpaper.app.ui.tools.InfoScreen
import com.pdfpaper.app.ui.tools.ToolDefinition
import com.pdfpaper.app.ui.tools.ToolDestination
import com.pdfpaper.app.ui.tools.ToolRegistry
import com.pdfpaper.app.ui.tools.ToolDetailScreen
import com.pdfpaper.app.ui.tools.ToolsScreen
import com.pdfpaper.app.ui.newpdf.NewPdfScreen
import com.pdfpaper.app.ui.search.SearchScreen
import com.pdfpaper.app.ui.viewer.ViewerScreen

object Routes {
    const val HOME = "home"
    const val TOOLS = "tools"
    const val RECENT = "recent"
    const val TOOL = "tool/{toolId}"
    const val VIEWER = "viewer"
    const val PAGES = "pages"
    const val INFO = "info"
    const val OPEN = "open"
    const val NEW_PDF = "new-pdf"
    const val SCAN = "scan"
    const val COMPRESS = "compress"
    const val MARK = "mark"
    const val EXPORT_IMAGES = "export-images"
    const val CROP = "crop"
    const val SETTINGS = "settings"
    const val SAFETY = "safety"
    const val SEARCH = "search"
    const val ANNOTATIONS = "annotations"
    const val SPLIT_TOC = "split-toc"

    fun tool(id: String): String = "tool/${Uri.encode(id)}"

    fun viewer(
        path: String,
        startInEdit: Boolean = false,
        startWithForms: Boolean = false,
        startEditTool: String? = null,
        startPanel: String? = null,
        displayName: String? = null,
        startPageIndex: Int = 0,
        startQuery: String? = null,
        highlightBounds: String? = null,
        /** Task T38b: the source the reader opened, used as the session key. */
        origin: String? = null
    ): String =
        "$VIEWER?path=${Uri.encode(path)}&edit=$startInEdit&forms=$startWithForms" +
            "&tool=${Uri.encode(startEditTool.orEmpty())}&panel=${Uri.encode(startPanel.orEmpty())}" +
            "&name=${Uri.encode(displayName.orEmpty())}&page=$startPageIndex" +
            "&q=${Uri.encode(startQuery.orEmpty())}" +
            "&hl=${Uri.encode(highlightBounds.orEmpty())}" +
            "&origin=${Uri.encode(origin.orEmpty())}"

    fun pages(
        path: String,
        hint: String? = null,
        tool: String? = null,
        extraPaths: List<String> = emptyList()
    ): String {
        val base = "$PAGES?path=${Uri.encode(path)}&tool=${Uri.encode(tool.orEmpty())}"
        val withHint = if (hint.isNullOrBlank()) base else "$base&hint=${Uri.encode(hint)}"
        if (extraPaths.isEmpty()) return withHint
        // Unit separators survive Uri.encode and cannot occur in a real path.
        return "$withHint&extra=${Uri.encode(extraPaths.joinToString("\u001F"))}"
    }

    fun info(path: String): String = "$INFO?path=${Uri.encode(path)}"

    /** T66: split at the table of contents. */
    fun splitByChapters(path: String): String = "$SPLIT_TOC?path=${Uri.encode(path)}"

    /** The compress screen, with the document to squeeze. */
    fun compress(path: String): String = "$COMPRESS?path=${Uri.encode(path)}"

    /** The mark screens, with the document and the tool to use on it. */
    fun mark(path: String, tool: String): String =
        "$MARK?path=${Uri.encode(path)}&tool=${Uri.encode(tool)}"

    /** The export-images screen for one document. */
    fun exportImages(path: String): String = "$EXPORT_IMAGES?path=${Uri.encode(path)}"

    /** The crop screen for one document. */
    fun crop(path: String): String = "$CROP?path=${Uri.encode(path)}"

    /** The static safety report for one document. */
    fun safety(path: String, displayName: String? = null): String =
        "$SAFETY?path=${Uri.encode(path)}&name=${Uri.encode(displayName.orEmpty())}"

    /** Task T50: the local full-text search screen. */
    fun search(): String = SEARCH

    /** The shared open flow: copy, detect, convert if needed, then the viewer. */
    fun open(uri: String, name: String? = null): String =
        "$OPEN?uri=${Uri.encode(uri)}&name=${Uri.encode(name.orEmpty())}"
}

@Composable
fun AppNav(
    navController: NavHostController = rememberNavController(),
    incomingRequest: IncomingRequest? = null,
    onIncomingConsumed: (Long) -> Unit = {},
    launchRequest: LauncherLaunchRequest? = null,
    onLaunchConsumed: (Long) -> Unit = {}
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showWorkspaceHeader = route == Routes.HOME ||
        route == Routes.TOOLS ||
        route == Routes.RECENT ||
        route == Routes.ANNOTATIONS

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val homeViewModel: HomeViewModel = viewModel()
    val debuggable = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    var showDebugMenu by remember { mutableStateOf(false) }
    var incomingPrompt by remember { mutableStateOf<IncomingRequest?>(null) }
    var incomingPromptKind by remember { mutableStateOf<IncomingKind?>(null) }
    var incomingBusy by remember { mutableStateOf<String?>(null) }
    var incomingError by remember { mutableStateOf<String?>(null) }

    // One picker for the whole workspace, so Open works from the header and
    // from the Home card and both land in the same open flow. PDFs, Word and
    // PowerPoint files (and the legacy formats, which get an explanation) are
    // offered; the copied bytes decide what actually happens.
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            homeViewModel.onPickerCancelled()
        } else {
            navController.navigate(Routes.open(uri.toString()))
        }
    }

    /** Shared All tools / Home behaviour: creation routes skip the PDF picker. */
    fun openTool(tool: ToolDefinition) {
        when (tool.destination) {
            ToolDestination.NEW_PDF -> navController.navigate(Routes.NEW_PDF)
            ToolDestination.SCAN -> navController.navigate(Routes.SCAN)
            else -> navController.navigate(Routes.tool(tool.id))
        }
    }

    // A static launcher shortcut or widget button enters here (T48). The request
    // is consumed after routing so a configuration change cannot replay it.
    LaunchedEffect(launchRequest?.id) {
        val request = launchRequest ?: return@LaunchedEffect
        try {
            when (request.action) {
                LauncherAction.OPEN_PDF -> picker.launch(OfficeKind.PICKER_MIME_TYPES)
                LauncherAction.SCAN -> navController.navigate(Routes.SCAN)
                LauncherAction.MERGE -> navController.navigate(Routes.tool("merge"))
                LauncherAction.RECENT -> navController.navigateTopLevel(Routes.RECENT)
            }
        } finally {
            onLaunchConsumed(request.id)
        }
    }

    // A VIEW or SEND intent from another app enters here. One document uses
    // the normal open flow; several PDFs offer Merge; images offer Make a PDF.
    // The MIME type is never trusted - every URI is classified from its bytes.
    LaunchedEffect(incomingRequest?.id) {
        val request = incomingRequest ?: return@LaunchedEffect
        // Consume the request only after it has been routed. Clearing it first
        // would change this effect's key and cancel the classification.
        try {
            if (request.error != null) {
                incomingError = request.error
                return@LaunchedEffect
            }
            incomingBusy = "Reading the shared files..."
            val classified = withContext(Dispatchers.IO) {
                request.uris.map { uri -> DocumentAccess.peekKind(context, uri.toString()) }
            }
            incomingBusy = null
            val failure = classified.firstOrNull { it.isFailure }
            if (failure != null) {
                incomingError = failure.exceptionOrNull()?.message
                    ?: "The shared file could not be read"
                return@LaunchedEffect
            }
            val kinds = classified.map { it.getOrThrow() }
            when {
                kinds.all { it == IncomingKind.PDF } && kinds.size > 1 -> {
                    incomingPromptKind = IncomingKind.PDF
                    incomingPrompt = request
                }
                kinds.all { it == IncomingKind.IMAGE } -> {
                    incomingPromptKind = IncomingKind.IMAGE
                    incomingPrompt = request
                }
                kinds.size == 1 -> {
                    navController.navigate(Routes.open(request.uris.single().toString()))
                }
                else -> {
                    incomingError = "Share several PDFs to merge them, or several images " +
                        "to make a PDF. Office documents can be opened one at a time."
                }
            }
        } finally {
            onIncomingConsumed(request.id)
        }
    }

    Scaffold(
        topBar = {
            if (showWorkspaceHeader) {
                WorkspaceHeader(
                    onOpen = { picker.launch(OfficeKind.PICKER_MIME_TYPES) },
                    onSearch = { navController.navigate(Routes.SEARCH) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onDebug = if (debuggable) {
                        { showDebugMenu = true }
                    } else {
                        null
                    }
                )
            }
        },
        bottomBar = {
            if (showWorkspaceHeader) {
                AppBottomBar(
                    currentRoute = route,
                    onNavigate = { destination -> navController.navigateTopLevel(destination) }
                )
            }
        }
    ) { padding ->
        // T63: under reduce motion a screen change is a cut, not a fade.
        val reduceMotion = LocalReduceMotion.current
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
            enterTransition = { NavMotion.enter(reduceMotion) },
            exitTransition = { NavMotion.exit(reduceMotion) },
            popEnterTransition = { NavMotion.enter(reduceMotion) },
            popExitTransition = { NavMotion.exit(reduceMotion) }
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpen = { picker.launch(OfficeKind.PICKER_MIME_TYPES) },
                    onNewPdf = { navController.navigate(Routes.NEW_PDF) },
                    onBrowseTools = { navController.navigateTopLevel(Routes.TOOLS) },
                    onBrowseRecent = { navController.navigateTopLevel(Routes.RECENT) },
                    onOpenTool = { tool -> openTool(tool) },
                    onSearchLibrary = { navController.navigate(Routes.SEARCH) },
                    onContinueReading = { last ->
                        // T38b: the origin is the session key, so reopening it
                        // through the shared open flow lands on the saved page.
                        navController.navigate(Routes.open(last.origin, last.displayName))
                    },
                    viewModel = homeViewModel
                )
            }
            composable(Routes.TOOLS) {
                ToolsScreen(
                    onOpenTool = { tool -> openTool(tool) }
                )
            }
            composable(Routes.RECENT) {
                RecentScreen(
                    onOpenDocument = { recent ->
                        navController.navigate(Routes.open(recent.uri, recent.displayName))
                    }
                )
            }
            composable(Routes.ANNOTATIONS) {
                AnnotationsScreen(
                    onOpenAnnotation = { target ->
                        navController.navigate(
                            Routes.viewer(
                                path = target.documentUri,
                                displayName = target.displayName,
                                startPageIndex = target.pageIndex,
                                highlightBounds = target.bounds
                            )
                        )
                    }
                )
            }
            composable(
                route = "${Routes.OPEN}?uri={uri}&name={name}",
                arguments = listOf(
                    navArgument("uri") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("name") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                OpenDocumentScreen(
                    uri = entry.arguments?.getString("uri").orEmpty(),
                    fallbackName = entry.arguments?.getString("name")?.takeIf { it.isNotBlank() },
                    onBack = { navController.popBackStack() },
                    onOpened = { document, origin ->
                        navController.navigate(
                            Routes.viewer(
                                path = document.value,
                                displayName = document.displayName,
                                origin = origin
                            )
                        ) {
                            popUpTo(Routes.OPEN) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.NEW_PDF) {
                NewPdfScreen(
                    onBack = { navController.popBackStack() },
                    onCreated = { path ->
                        navController.navigate(Routes.viewer(path)) {
                            popUpTo(Routes.NEW_PDF) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.SCAN) {
                ScanScreen(
                    onBack = { navController.popBackStack() },
                    onCreated = { path ->
                        navController.navigate(Routes.viewer(path)) {
                            popUpTo(Routes.SCAN) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenResult = { path, name, pageIndex, query ->
                        navController.navigate(
                            Routes.viewer(
                                path = path,
                                displayName = name,
                                startPageIndex = pageIndex,
                                startQuery = query
                            )
                        )
                    },
                    onNeedsOcr = { path, name, pageIndex ->
                        navController.navigate(
                            Routes.viewer(
                                path = path,
                                displayName = name,
                                startPanel = "ocr",
                                startPageIndex = pageIndex
                            )
                        )
                    }
                )
            }
            composable(
                route = "${Routes.SAFETY}?path={path}&name={name}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("name") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                SafetyScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    displayName = entry.arguments?.getString("name")?.takeIf { it.isNotBlank() },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "${Routes.COMPRESS}?path={path}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                CompressScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() }
                )
            }
            composable(
                route = "${Routes.CROP}?path={path}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                CropScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() }
                )
            }
            composable(
                route = "${Routes.EXPORT_IMAGES}?path={path}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                ExportImagesScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "${Routes.MARK}?path={path}&tool={tool}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("tool") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                val path = entry.arguments?.getString("path").orEmpty()
                val tool = entry.arguments?.getString("tool").orEmpty()
                when (tool) {
                    "watermark" -> WatermarkScreen(
                        path = path,
                        onBack = { navController.popBackStack() },
                        onSaved = { navController.popBackStack() }
                    )
                    "page-numbers" -> PageNumbersScreen(
                        path = path,
                        onBack = { navController.popBackStack() },
                        onSaved = { navController.popBackStack() }
                    )
                    "bates" -> BatesScreen(
                        path = path,
                        onBack = { navController.popBackStack() },
                        onSaved = { navController.popBackStack() }
                    )
                    else -> MarkToolMissing(onBack = { navController.popBackStack() })
                }
            }
            composable(
                route = Routes.TOOL,
                arguments = listOf(
                    navArgument("toolId") { type = NavType.StringType }
                )
            ) { entry ->
                ToolDetailScreen(
                    toolId = entry.arguments?.getString("toolId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenOrganizer = { path, hint ->
                        navController.navigate(Routes.pages(path, hint))
                    },
                    onOpenViewer = { path, edit, forms, editTool, panel ->
                        navController.navigate(Routes.viewer(path, edit, forms, editTool, panel))
                    },
                    onOpenInfo = { path -> navController.navigate(Routes.info(path)) },
                    onOpenCompress = { path -> navController.navigate(Routes.compress(path)) },
                    onOpenMark = { path, tool -> navController.navigate(Routes.mark(path, tool)) },
                    onOpenExport = { path -> navController.navigate(Routes.exportImages(path)) },
                    onOpenCrop = { path -> navController.navigate(Routes.crop(path)) },
                    onOpenSafety = { path -> navController.navigate(Routes.safety(path)) }
                )
            }
            composable(
                route = "${Routes.VIEWER}?path={path}&edit={edit}&forms={forms}" +
                    "&tool={tool}&panel={panel}&name={name}&page={page}&q={q}" +
                    "&hl={hl}&origin={origin}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("edit") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                    navArgument("forms") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                    navArgument("tool") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("panel") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("name") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("page") {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                    navArgument("q") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("hl") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("origin") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                ViewerScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    displayName = entry.arguments?.getString("name")?.takeIf { it.isNotBlank() },
                    origin = entry.arguments?.getString("origin")?.takeIf { it.isNotBlank() },
                    startInEditMode = entry.arguments?.getBoolean("edit") ?: false,
                    startWithForms = entry.arguments?.getBoolean("forms") ?: false,
                    startEditTool = entry.arguments?.getString("tool").orEmpty(),
                    startPanel = entry.arguments?.getString("panel").orEmpty(),
                    startPageIndex = entry.arguments?.getInt("page") ?: 0,
                    startQuery = entry.arguments?.getString("q").orEmpty(),
                    startHighlight = entry.arguments?.getString("hl").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOrganize = { path, toolId ->
                        navController.navigate(
                            Routes.pages(path, ToolRegistry.byId(toolId.orEmpty())?.organizerHint, toolId)
                        )
                    },
                    onOpenInfo = { path -> navController.navigate(Routes.info(path)) },
                    onNewPdf = { navController.navigate(Routes.NEW_PDF) },
                    onScan = { navController.navigate(Routes.SCAN) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenSafety = { documentPath, name ->
                        navController.navigate(Routes.safety(documentPath, name))
                    },
                    onOpenTool = { toolId, documentPath ->
                        navController.navigate(
                            when (toolId) {
                                "compress" -> Routes.compress(documentPath)
                                "crop" -> Routes.crop(documentPath)
                                "export-images" -> Routes.exportImages(documentPath)
                                else -> Routes.mark(documentPath, toolId)
                            }
                        )
                    }
                )
            }
            composable(
                route = "${Routes.PAGES}?path={path}&hint={hint}&tool={tool}&extra={extra}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("hint") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("tool") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("extra") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                PagesScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    hint = entry.arguments?.getString("hint"),
                    toolId = entry.arguments?.getString("tool").orEmpty(),
                    extraPaths = entry.arguments?.getString("extra")
                        ?.takeIf { it.isNotBlank() }
                        ?.split("\u001F")
                        ?.filter { it.isNotBlank() }
                        .orEmpty(),
                    onBack = { navController.popBackStack() },
                    onSplitChapters = {
                        val path = entry.arguments?.getString("path").orEmpty()
                        if (path.isNotBlank()) {
                            navController.navigate(Routes.splitByChapters(path))
                        }
                    }
                )
            }
            composable(
                route = "${Routes.SPLIT_TOC}?path={path}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                TocSplitRoute(
                    path = entry.arguments?.getString("path").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "${Routes.INFO}?path={path}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                InfoScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }

    incomingPrompt?.let { request ->
        val images = incomingPromptKind == IncomingKind.IMAGE
        AlertDialog(
            onDismissRequest = {
                incomingPrompt = null
                incomingPromptKind = null
            },
            modifier = Modifier.testTag(UiTags.INCOMING_PROMPT),
            title = {
                Text(
                    if (images) {
                        "Make a PDF from these images?"
                    } else {
                        "Merge these ${request.uris.size} PDFs?"
                    }
                )
            },
            text = {
                Text(
                    if (images) {
                        "PDFPaper will put each image on its own page and open the result."
                    } else {
                        "Merge the shared PDFs into one document, or open only the first one."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(
                        if (images) UiTags.INCOMING_MAKE_PDF else UiTags.INCOMING_MERGE
                    ),
                    onClick = {
                        incomingPrompt = null
                        incomingPromptKind = null
                        if (images) {
                            // Navigation must happen on the main thread. A plain
                            // scope.launch can resume on a background dispatcher
                            // after withContext(IO), which makes navigate() throw.
                            scope.launch(Dispatchers.Main.immediate) {
                                incomingBusy = "Making a PDF from the images..."
                                val result = withContext(Dispatchers.IO) {
                                    ImagePdf.create(context, request.uris)
                                }
                                incomingBusy = null
                                result.fold(
                                    onSuccess = { file ->
                                        navController.navigate(
                                            Routes.viewer(
                                                file.absolutePath,
                                                displayName = file.name
                                            )
                                        )
                                    },
                                    onFailure = { error ->
                                        incomingError = error.message
                                            ?: "The images could not be made into a PDF"
                                    }
                                )
                            }
                        } else {
                            scope.launch(Dispatchers.Main.immediate) {
                                incomingBusy = "Preparing the merge..."
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        request.uris.map { uri ->
                                            DocumentAccess.materialize(
                                                context,
                                                uri.toString(),
                                                DocumentAccess.displayName(context, uri.toString())
                                            ).getOrThrow()
                                        }
                                    }
                                }
                                incomingBusy = null
                                result.fold(
                                    onSuccess = { files: List<File> ->
                                        navController.navigate(
                                            Routes.pages(
                                                path = files.first().absolutePath,
                                                hint = "The shared PDFs were merged. " +
                                                    "Use Save to write the combined PDF.",
                                                extraPaths = files.drop(1).map { it.absolutePath }
                                            )
                                        )
                                    },
                                    onFailure = { error ->
                                        incomingError = error.message
                                            ?: "The shared PDFs could not be prepared"
                                    }
                                )
                            }
                        }
                    }
                ) {
                    Text(if (images) "Make a PDF" else "Merge PDFs")
                }
            },
            dismissButton = {
                if (images) {
                    TextButton(
                        onClick = {
                            incomingPrompt = null
                            incomingPromptKind = null
                        }
                    ) {
                        Text("Cancel")
                    }
                } else {
                    TextButton(
                        modifier = Modifier.testTag(UiTags.INCOMING_OPEN_FIRST),
                        onClick = {
                            val first = request.uris.firstOrNull()
                            incomingPrompt = null
                            incomingPromptKind = null
                            if (first != null) {
                                navController.navigate(Routes.open(first.toString()))
                            }
                        }
                    ) {
                        Text("Open the first")
                    }
                }
            }
        )
    }

    incomingBusy?.let { stage ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Incoming document") },
            text = {
                Column {
                    CircularProgressIndicator()
                    Text(stage, modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = {}
        )
    }

    incomingError?.let { message ->
        AlertDialog(
            onDismissRequest = { incomingError = null },
            title = { Text("This file cannot be opened") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { incomingError = null }) { Text("Close") }
            }
        )
    }

    if (showDebugMenu) {
        AlertDialog(
            onDismissRequest = { showDebugMenu = false },
            title = { Text("Debug") },
            text = {
                Column {
                    Text(
                        text = if (debuggable) {
                            "Editing engine: FakeEngine (demo)"
                        } else {
                            "Editing engine: PDF engine (coming soon)"
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (debuggable) {
                            EditingRuntime.DEMO_NOTICE
                        } else {
                            "Text and image editing will be enabled when the PDF " +
                                "engine exposes a content-editing API."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDebugMenu = false }) { Text("Close") }
            }
        )
    }
}

private fun NavHostController.navigateTopLevel(destination: String) {
    navigate(destination) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Shown when a mark route is opened with a tool that does not exist. */
@Composable
private fun MarkToolMissing(onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = "Mark", onBack = onBack)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("That tool is not in this catalogue.")
            }
        }
    }
}
