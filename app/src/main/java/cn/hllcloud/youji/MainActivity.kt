package cn.hllcloud.youji

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.hllcloud.youji.ui.create.CreateTravelScreen
import cn.hllcloud.youji.ui.detail.DetailScreen
import cn.hllcloud.youji.ui.edit.EditPhotosScreen
import cn.hllcloud.youji.ui.home.HomeScreen
import cn.hllcloud.youji.ui.oneimage.OneImageScreen
import cn.hllcloud.youji.ui.settings.SettingsScreen
import cn.hllcloud.youji.ui.setup.SetupWizardScreen
import cn.hllcloud.youji.ui.style.StyleManagerScreen
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
    /**
     * 启动决策路由。无 UI，仅读取 [setupCompleted] 后跳转到 [Setup] 或 [Home]，
     * 自身从回退栈中移除，避免用户从首页按返回回到空白 Splash。
     *
     * 对应设计 V3 第 2.5 节：首次启动或检测到配置未完成时强制进入引导页。
     */
    object Splash : Screen("splash", "")
    object Setup : Screen("setup", "首次配置")
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
    object WorkflowProgress : Screen("workflow_progress/{taskId}", "工作流进度") {
        fun createRoute(taskId: Long): String = "workflow_progress/$taskId"
    }
    object EditPhotos : Screen("edit_photos/{taskId}", "编辑照片") {
        fun createRoute(taskId: Long): String = "edit_photos/$taskId"
    }
    object StyleManager : Screen("style_manager", "风格管理")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val context = LocalContext.current
    val app = context.applicationContext as YouJiApplication

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
            startDestination = Screen.Splash.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // 启动决策：读取 setupCompleted，导航到 Setup 或 Home，自身移出回退栈
            composable(Screen.Splash.route) {
                val setupCompleted by app.appPreferencesRepository.setupCompleted
                    .collectAsStateWithLifecycle(initialValue = null)
                LaunchedEffect(setupCompleted) {
                    when (setupCompleted) {
                        true -> {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Splash.route) { inclusive = true }
                            }
                        }
                        false -> {
                            navController.navigate(Screen.Setup.route) {
                                popUpTo(Screen.Splash.route) { inclusive = true }
                            }
                        }
                        null -> { /* 等待 DataStore 首次加载，显示空白 splash */ }
                    }
                }
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "YouJi",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 首次启动配置引导页（对应设计 V3 第 2.5 节）
            composable(Screen.Setup.route) {
                SetupWizardScreen(
                    onSetupComplete = {
                        // 配置完成：弹栈到 Splash（已被移除）后跳转首页，
                        // 同时确保回退栈中不残留 Setup 路由
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    }
                )
            }

            // 首页
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToCreate = {
                        navController.navigate(Screen.Create.createRoute())
                    },
                    onNavigateToDetail = { noteId ->
                        navController.navigate(Screen.Detail.createRoute(noteId))
                    },
                    onNavigateToWorkflowProgress = { taskId ->
                        // 从首页恢复任务：跳转进度页观察执行过程，
                        // 弹栈到首页避免回退链混乱
                        navController.navigate(Screen.WorkflowProgress.createRoute(taskId)) {
                            popUpTo(Screen.Home.route)
                        }
                    }
                )
            }

            // 设置页
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // 创建游记
            composable(Screen.Create.route) {
                CreateTravelScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onDraftSaved = { taskId ->
                        // 草稿保存：返回首页。Task 7 将扩展 DetailScreen 支持
                        // workflowTaskId 加载，届时改为跳转详情页（PENDING 状态显示开始生成按钮）
                        navController.popBackStack()
                    },
                    onWorkflowStarted = { taskId ->
                        // 启动工作流后跳转进度页（Task 8 实现具体页面）
                        navController.navigate(Screen.WorkflowProgress.createRoute(taskId)) {
                            popUpTo(Screen.Home.route)
                        }
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onNavigateToStyleManager = {
                        navController.navigate(Screen.StyleManager.route)
                    }
                )
            }

            // 风格管理页（对应设计 V3 第 2.1 节"风格选择行 +管理"链接）
            composable(Screen.StyleManager.route) {
                StyleManagerScreen(
                    onNavigateBack = { navController.popBackStack() }
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
                    onNavigateToEditPhotos = { taskId ->
                        // 跳转编辑照片页（参数为 workflowTaskId）
                        navController.navigate(Screen.EditPhotos.createRoute(taskId))
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

            // 工作流进度页（对应设计 V3 第 2.2 节）
            composable(Screen.WorkflowProgress.route) { entry ->
                val taskId = entry.arguments?.getString("taskId")?.toLongOrNull() ?: return@composable
                cn.hllcloud.youji.ui.workflow.WorkflowProgressScreen(
                    taskId = taskId,
                    onCompleted = { id ->
                        // 完成后跳转详情页，弹栈到首页避免回退到进度页
                        navController.popBackStack()
                        navController.navigate(Screen.Detail.createRoute(id))
                    },
                    onAbandoned = {
                        // 放弃后返回首页
                        navController.popBackStack(Screen.Home.route, inclusive = false)
                    }
                )
            }

            // 编辑照片页（对应设计 V3 第 5.1/5.2/5.3 节，三种场景共用）
            composable(Screen.EditPhotos.route) { entry ->
                val taskId = entry.arguments?.getString("taskId")?.toLongOrNull() ?: return@composable
                EditPhotosScreen(
                    taskId = taskId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
