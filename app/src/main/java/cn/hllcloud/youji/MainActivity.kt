package cn.hllcloud.youji

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.hllcloud.youji.ui.create.CreateTravelScreen
import cn.hllcloud.youji.ui.detail.DetailScreen
import cn.hllcloud.youji.ui.home.HomeScreen
import cn.hllcloud.youji.ui.oneimage.OneImageScreen
import cn.hllcloud.youji.ui.settings.SettingsScreen
import cn.hllcloud.youji.ui.theme.YouJiTheme

/**
 * 主Activity
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YouJiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation()
                }
            }
        }
    }
}

sealed class Screen(val route: String, val label: String) {
    object Home : Screen("home", "首页")
    object Settings : Screen("settings", "设置")
    object Create : Screen("create?noteId={noteId}", "创建") {
        fun createRoute(noteId: Long? = null): String {
            return if (noteId != null) "create?noteId=$noteId" else "create"
        }
    }
    object Detail : Screen("detail/{noteId}", "详情") {
        fun createRoute(noteId: Long): String = "detail/$noteId"
    }
    object OneImage : Screen("oneimage/{noteId}", "一图流") {
        fun createRoute(noteId: Long): String = "oneimage/$noteId"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // 只有首页和设置显示底部导航
    val showBottomBar = currentDestination?.route in listOf(
        Screen.Home.route, Screen.Settings.route
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    listOf(
                        Triple(Screen.Home, Icons.Default.Home, R.string.nav_home),
                        Triple(Screen.Settings, Icons.Default.Settings, R.string.nav_settings)
                    ).forEach { (screen, icon, labelRes) ->
                        NavigationBarItem(
                            selected = currentDestination?.hierarchy?.any {
                                it.route == screen.route
                            } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // 首页
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToCreate = {
                        navController.navigate(Screen.Create.createRoute())
                    },
                    onNavigateToDetail = { noteId ->
                        navController.navigate(Screen.Detail.createRoute(noteId))
                    }
                )
            }

            // 设置页
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // 创建/编辑游记
            composable(Screen.Create.route) { entry ->
                val noteId = entry.arguments?.getString("noteId")?.toLongOrNull()
                CreateTravelScreen(
                    editNoteId = noteId,
                    onNavigateBack = { navController.popBackStack() },
                    onSaved = { newNoteId ->
                        // 保存成功后，回到首页或者跳转详情
                        if (noteId == null) {
                            // 新建时跳转详情页
                            navController.popBackStack()
                            navController.navigate(Screen.Detail.createRoute(newNoteId))
                        } else {
                            navController.popBackStack()
                        }
                    }
                )
            }

            // 游记详情
            composable(Screen.Detail.route) { entry ->
                val noteId = entry.arguments?.getString("noteId")?.toLongOrNull() ?: return@composable
                DetailScreen(
                    noteId = noteId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToEdit = { id ->
                        navController.navigate(Screen.Create.createRoute(id))
                    },
                    onNavigateToOneImage = { id ->
                        navController.navigate(Screen.OneImage.createRoute(id))
                    },
                    onNavigateToShare = { id ->
                        navController.navigate(Screen.OneImage.createRoute(id))
                    },
                    onDeleted = {
                        navController.popBackStack()
                    }
                )
            }

            // 一图流
            composable(Screen.OneImage.route) { entry ->
                val noteId = entry.arguments?.getString("noteId")?.toLongOrNull() ?: return@composable
                OneImageScreen(
                    noteId = noteId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
