package com.pdfpaper.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pdfpaper.app.ui.home.HomeScreen
import com.pdfpaper.app.ui.viewer.ViewerScreen

object Routes {
    const val HOME = "home"
    const val VIEWER = "viewer"

    fun viewer(path: String): String = "$VIEWER?path=${Uri.encode(path)}"
}

@Composable
fun AppNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenViewer = { cached ->
                    navController.navigate(Routes.viewer(cached.file.absolutePath))
                }
            )
        }
        composable(
            route = "${Routes.VIEWER}?path={path}",
            arguments = listOf(
                navArgument("path") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            ViewerScreen(
                path = backStackEntry.arguments?.getString("path").orEmpty()
            )
        }
    }
}
