package com.pdfpaper.app.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.home.HomeScreen
import com.pdfpaper.app.ui.pages.PagesScreen
import com.pdfpaper.app.ui.recent.RecentScreen
import com.pdfpaper.app.ui.tools.InfoScreen
import com.pdfpaper.app.ui.tools.ToolDetailScreen
import com.pdfpaper.app.ui.tools.ToolsScreen
import com.pdfpaper.app.ui.viewer.ViewerScreen

object Routes {
    const val HOME = "home"
    const val TOOLS = "tools"
    const val RECENT = "recent"
    const val TOOL = "tool/{toolId}"
    const val VIEWER = "viewer"
    const val PAGES = "pages"
    const val INFO = "info"

    fun tool(id: String): String = "tool/${Uri.encode(id)}"

    fun viewer(
        path: String,
        startInEdit: Boolean = false,
        startWithForms: Boolean = false
    ): String =
        "$VIEWER?path=${Uri.encode(path)}&edit=$startInEdit&forms=$startWithForms"

    fun pages(path: String, hint: String? = null): String {
        val base = "$PAGES?path=${Uri.encode(path)}"
        return if (hint.isNullOrBlank()) base else "$base&hint=${Uri.encode(hint)}"
    }

    fun info(path: String): String = "$INFO?path=${Uri.encode(path)}"
}

@Composable
fun AppNav(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showBottomBar = route == Routes.HOME ||
        route == Routes.TOOLS ||
        route == Routes.RECENT ||
        route == Routes.TOOL

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                AppBottomBar(
                    currentRoute = route,
                    onNavigate = { destination ->
                        navController.navigate(destination) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
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
                    onOpenViewer = { cached ->
                        navController.navigate(Routes.viewer(cached.file.absolutePath))
                    },
                    onBrowseTools = { navController.navigateTopLevel(Routes.TOOLS) },
                    onBrowseRecent = { navController.navigateTopLevel(Routes.RECENT) }
                )
            }
            composable(Routes.TOOLS) {
                ToolsScreen(
                    onOpenTool = { tool -> navController.navigate(Routes.tool(tool.id)) }
                )
            }
            composable(Routes.RECENT) {
                RecentScreen(
                    onOpenViewer = { cached ->
                        navController.navigate(Routes.viewer(cached.file.absolutePath))
                    }
                )
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
                    onOpenViewer = { path, edit, forms ->
                        navController.navigate(Routes.viewer(path, edit, forms))
                    },
                    onOpenInfo = { path -> navController.navigate(Routes.info(path)) }
                )
            }
            composable(
                route = "${Routes.VIEWER}?path={path}&edit={edit}&forms={forms}",
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
                    }
                )
            ) { entry ->
                ViewerScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    startInEditMode = entry.arguments?.getBoolean("edit") ?: false,
                    startWithForms = entry.arguments?.getBoolean("forms") ?: false,
                    onBack = { navController.popBackStack() },
                    onOrganize = { path -> navController.navigate(Routes.pages(path)) }
                )
            }
            composable(
                route = "${Routes.PAGES}?path={path}&hint={hint}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("hint") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                PagesScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    hint = entry.arguments?.getString("hint"),
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
}

private fun NavHostController.navigateTopLevel(destination: String) {
    navigate(destination) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppBottomBar(currentRoute: String?, onNavigate: (String) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Routes.HOME,
            onClick = { onNavigate(Routes.HOME) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("Home") },
            modifier = Modifier.testTag(UiTags.NAV_HOME)
        )
        NavigationBarItem(
            selected = currentRoute == Routes.TOOLS || currentRoute == Routes.TOOL,
            onClick = { onNavigate(Routes.TOOLS) },
            icon = { Icon(Icons.Filled.Build, contentDescription = null) },
            label = { Text("Tools") },
            modifier = Modifier.testTag(UiTags.NAV_TOOLS)
        )
        NavigationBarItem(
            selected = currentRoute == Routes.RECENT,
            onClick = { onNavigate(Routes.RECENT) },
            icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
            label = { Text("Recent") },
            modifier = Modifier.testTag(UiTags.NAV_RECENT)
        )
    }
}
