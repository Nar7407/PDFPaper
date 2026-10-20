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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.pdfpaper.app.ui.components.AppBottomBar
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.app.ui.components.WorkspaceHeader
import com.pdfpaper.app.ui.compress.CompressScreen
import com.pdfpaper.app.ui.export.ExportImagesScreen
import com.pdfpaper.app.ui.home.HomeScreen
import com.pdfpaper.app.ui.home.HomeViewModel
import com.pdfpaper.app.ui.mark.BatesScreen
import com.pdfpaper.app.ui.mark.PageNumbersScreen
import com.pdfpaper.app.ui.mark.WatermarkScreen
import com.pdfpaper.app.ui.open.OpenDocumentScreen
import com.pdfpaper.app.ui.pages.PagesScreen
import com.pdfpaper.app.ui.recent.RecentScreen
import com.pdfpaper.app.ui.scan.ScanScreen
import com.pdfpaper.app.ui.settings.SettingsScreen
import com.pdfpaper.app.ui.tools.InfoScreen
import com.pdfpaper.app.ui.tools.ToolDefinition
import com.pdfpaper.app.ui.tools.ToolDestination
import com.pdfpaper.app.ui.tools.ToolRegistry
import com.pdfpaper.app.ui.tools.ToolDetailScreen
import com.pdfpaper.app.ui.tools.ToolsScreen
import com.pdfpaper.app.ui.newpdf.NewPdfScreen
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
    const val SETTINGS = "settings"

    fun tool(id: String): String = "tool/${Uri.encode(id)}"

    fun viewer(
        path: String,
        startInEdit: Boolean = false,
        startWithForms: Boolean = false,
        startEditTool: String? = null,
        startPanel: String? = null
    ): String =
        "$VIEWER?path=${Uri.encode(path)}&edit=$startInEdit&forms=$startWithForms" +
            "&tool=${Uri.encode(startEditTool.orEmpty())}&panel=${Uri.encode(startPanel.orEmpty())}"

    fun pages(path: String, hint: String? = null, tool: String? = null): String {
        val base = "$PAGES?path=${Uri.encode(path)}&tool=${Uri.encode(tool.orEmpty())}"
        return if (hint.isNullOrBlank()) base else "$base&hint=${Uri.encode(hint)}"
    }

    fun info(path: String): String = "$INFO?path=${Uri.encode(path)}"

    /** The compress screen, with the document to squeeze. */
    fun compress(path: String): String = "$COMPRESS?path=${Uri.encode(path)}"

    /** The mark screens, with the document and the tool to use on it. */
    fun mark(path: String, tool: String): String =
        "$MARK?path=${Uri.encode(path)}&tool=${Uri.encode(tool)}"

    /** The export-images screen for one document. */
    fun exportImages(path: String): String = "$EXPORT_IMAGES?path=${Uri.encode(path)}"

    /** The shared open flow: copy, detect, convert if needed, then the viewer. */
    fun open(uri: String, name: String? = null): String =
        "$OPEN?uri=${Uri.encode(uri)}&name=${Uri.encode(name.orEmpty())}"
}

@Composable
fun AppNav(
    navController: NavHostController = rememberNavController(),
    incomingDocument: Uri? = null,
    onIncomingConsumed: () -> Unit = {}
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showWorkspaceHeader = route == Routes.HOME ||
        route == Routes.TOOLS ||
        route == Routes.RECENT

    val context = LocalContext.current
    val homeViewModel: HomeViewModel = viewModel()
    val homeState by homeViewModel.state.collectAsState()
    val debuggable = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    var showDebugMenu by remember { mutableStateOf(false) }

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

    // A VIEW intent from another app (file manager, mail attachment) enters
    // through the same flow as the picker.
    LaunchedEffect(incomingDocument) {
        val uri = incomingDocument ?: return@LaunchedEffect
        navController.navigate(Routes.open(uri.toString()))
        onIncomingConsumed()
    }

    Scaffold(
        topBar = {
            if (showWorkspaceHeader) {
                WorkspaceHeader(
                    onOpen = { picker.launch(OfficeKind.PICKER_MIME_TYPES) },
                    onSearch = { navController.navigateTopLevel(Routes.TOOLS) },
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
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpen = { picker.launch(OfficeKind.PICKER_MIME_TYPES) },
                    onNewPdf = { navController.navigate(Routes.NEW_PDF) },
                    onBrowseTools = { navController.navigateTopLevel(Routes.TOOLS) },
                    onBrowseRecent = { navController.navigateTopLevel(Routes.RECENT) },
                    onOpenTool = { tool -> openTool(tool) },
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
                    onOpened = { pdf ->
                        navController.navigate(Routes.viewer(pdf.absolutePath)) {
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
                    onOpenExport = { path -> navController.navigate(Routes.exportImages(path)) }
                )
            }
            composable(
                route = "${Routes.VIEWER}?path={path}&edit={edit}&forms={forms}&tool={tool}&panel={panel}",
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
                    }
                )
            ) { entry ->
                ViewerScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    startInEditMode = entry.arguments?.getBoolean("edit") ?: false,
                    startWithForms = entry.arguments?.getBoolean("forms") ?: false,
                    startEditTool = entry.arguments?.getString("tool").orEmpty(),
                    startPanel = entry.arguments?.getString("panel").orEmpty(),
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
                    onOpenTool = { toolId, documentPath ->
                        navController.navigate(
                            when (toolId) {
                                "compress" -> Routes.compress(documentPath)
                                "export-images" -> Routes.exportImages(documentPath)
                                else -> Routes.mark(documentPath, toolId)
                            }
                        )
                    }
                )
            }
            composable(
                route = "${Routes.PAGES}?path={path}&hint={hint}&tool={tool}",
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
                    }
                )
            ) { entry ->
                PagesScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    hint = entry.arguments?.getString("hint"),
                    toolId = entry.arguments?.getString("tool").orEmpty(),
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
