package cn.hllcloud.youji.ui.edit

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import cn.hllcloud.youji.YouJiApplication
import cn.hllcloud.youji.data.entity.PhotoEntity
import cn.hllcloud.youji.util.FileUtil
import cn.hllcloud.youji.util.PermissionUtil
import kotlinx.coroutines.launch
import java.io.File

/**
 * 编辑照片页。对应设计 V3 第 5.1/5.2/5.3 节。
 *
 * 用户从详情页或草稿页点「编辑照片」后进入此页。本页：
 * - 加载 [taskId] 对应 workflow_task 的 `inputPhotoPaths` 重建为可编辑列表（3 列网格）
 * - 复用创建页的拍照/图库选择交互（含国产 ROM 权限兼容与永久拒绝引导）
 * - 「保存」按钮调用 [EditPhotosViewModel.saveEdit]，引擎 [cn.hllcloud.youji.util.WorkflowEngine.applyPhotoEdit]
 *   计算 diff 并按场景一（pending）/场景二（paused/failed）/场景三（completed）执行副作用
 * - 任务 running 时禁用编辑并显示警告条（应先暂停），保存成功后返回上一页
 *
 * @param taskId 工作流任务 id
 * @param onNavigateBack 保存成功或用户取消时回调（通常返回详情页）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPhotosScreen(
    taskId: Long,
    onNavigateBack: () -> Unit,
    viewModel: EditPhotosViewModel = viewModel(
        factory = EditPhotosViewModel.Factory(
            LocalContext.current.applicationContext as YouJiApplication,
            taskId
        )
    )
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var cameraPhotoFile by remember { mutableStateOf<File?>(null) }

    // 永久拒绝权限时显示引导对话框
    var showPermissionSettingsDialog by remember { mutableStateOf(false) }
    var permissionSettingsMessage by remember { mutableStateOf("") }

    val canEdit = uiState.taskStatus != "running"

    // 图库选择
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            viewModel.addPhotoFromUri(uri, context)
        }
    }

    // 拍照
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraPhotoFile?.let { file ->
                viewModel.addPhotoFromFile(file)
            }
        }
        cameraPhotoFile = null
    }

    /**
     * 启动相机（已具备权限时调用）。
     * 国产ROM兼容：显式授予URI读写权限，避免部分系统裁剪/拍照时崩溃。
     */
    val launchCamera: () -> Unit = {
        try {
            val photoFile = FileUtil.createImageFile(context)
            cameraPhotoFile = photoFile
            val photoUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
            val resInfo = context.packageManager.queryIntentActivities(
                Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            for (resolveInfo in resInfo) {
                val packageName = resolveInfo.activityInfo.packageName
                context.grantUriPermission(packageName, photoUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            cameraLauncher.launch(photoUri)
        } catch (e: Exception) {
            e.printStackTrace()
            scope.launch {
                snackbarHostState.showSnackbar("无法启动相机: ${e.message ?: "未知错误"}")
            }
        }
    }

    // 图库权限请求（兼容Android 13+ READ_MEDIA_IMAGES与老版本READ_EXTERNAL_STORAGE）
    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it } ||
            PermissionUtil.hasReadImagePermission(context)
        if (granted) {
            galleryLauncher.launch("image/*")
        } else {
            val activity = context.findActivity()
            val deniedPermanently = activity != null &&
                PermissionUtil.isPermissionPermanentlyDenied(activity, PermissionUtil.getReadImagePermissions())
            if (deniedPermanently) {
                permissionSettingsMessage = "需要图片访问权限才能选择照片。您此前已选择\"不再询问\"，请到设置中手动开启权限。"
                showPermissionSettingsDialog = true
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar("需要图片访问权限才能选择照片")
                }
            }
        }
    }

    // 相机权限请求
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            launchCamera()
        } else {
            val activity = context.findActivity()
            val deniedPermanently = activity != null &&
                PermissionUtil.isPermissionPermanentlyDenied(activity, PermissionUtil.getCameraPermissions())
            if (deniedPermanently) {
                permissionSettingsMessage = "需要相机权限才能拍照。您此前已选择\"不再询问\"，请到设置中手动开启权限。"
                showPermissionSettingsDialog = true
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar("需要相机权限才能拍照")
                }
            }
        }
    }

    // 一次性错误事件消费
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    // 保存成功：showSnackbar 会挂起到 Snackbar 消失（约 1.5s），
    // 用户看到提示后立即返回上一页。注意 consumeResult 与 onNavigateBack 之间不能有
    // 挂起点——consume 改变 key 会触发 LaunchedEffect 重启，若中间有挂起会被取消。
    LaunchedEffect(uiState.resultMessage) {
        uiState.resultMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeResult()
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "编辑照片",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    // 顶部右侧也放一个保存按钮，方便快速保存
                    if (canEdit && !uiState.isSaving) {
                        IconButton(onClick = { viewModel.saveEdit() }) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // 任务运行中警告条
            if (!canEdit) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFFF3E0)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFEF6C00),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "任务运行中，请先暂停再编辑照片",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFEF6C00)
                            )
                        }
                    }
                }
            }

            // 照片编辑区域
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "当前照片",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "共 ${uiState.photos.size} 张",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 拍照 + 图库按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            PhotoActionButton(
                                icon = Icons.Default.CameraAlt,
                                label = "拍照",
                                enabled = canEdit,
                                onClick = {
                                    if (PermissionUtil.hasCameraPermission(context)) {
                                        launchCamera()
                                    } else {
                                        cameraPermissionLauncher.launch(
                                            PermissionUtil.getCameraPermissions().toTypedArray()
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            PhotoActionButton(
                                icon = Icons.Default.PhotoLibrary,
                                label = "从图库选择",
                                enabled = canEdit,
                                onClick = {
                                    if (PermissionUtil.hasReadImagePermission(context)) {
                                        galleryLauncher.launch("image/*")
                                    } else {
                                        galleryPermissionLauncher.launch(
                                            PermissionUtil.getReadImagePermissions().toTypedArray()
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // 已选照片网格：每行3个，超过换行
                        if (uiState.photos.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Divider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            uiState.photos.chunked(3).forEach { rowPhotos ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowPhotos.forEach { photo ->
                                        EditablePhotoItem(
                                            photo = photo,
                                            onRemove = { viewModel.removePhoto(photo) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(3 - rowPhotos.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        } else {
                            Spacer(modifier = Modifier.height(16.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "暂无照片，请添加",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 保存按钮
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.saveEdit() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    enabled = canEdit && !uiState.isSaving && uiState.photos.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    )
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "保存中...", style = MaterialTheme.typography.labelLarge)
                    } else {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "保存编辑", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // 场景说明（帮助用户理解保存后的行为）
            item {
                Spacer(modifier = Modifier.height(16.dp))
                val scenarioHint = when (uiState.taskStatus) {
                    "pending" -> "草稿状态：保存后修改照片列表，回到详情页点「开始生成」启动工作流。"
                    "paused", "failed" -> "已暂停/失败：保存后会清理已生成内容，回到详情页点「恢复/重试」继续。"
                    "completed" -> "已完成：保存后标记待应用编辑，回到详情页点「增量更新」让AI生成新内容。"
                    "running" -> "任务运行中：请先暂停任务再编辑照片。"
                    else -> ""
                }
                if (scenarioHint.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = scenarioHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }

    // 永久拒绝权限时引导去系统设置开启
    if (showPermissionSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionSettingsDialog = false },
            title = { Text("需要权限") },
            text = { Text(permissionSettingsMessage) },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionSettingsDialog = false
                    PermissionUtil.openAppSettings(context)
                }) {
                    Text("去设置", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionSettingsDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 从Context中查找Activity（用于shouldShowRequestPermissionRationale）。
 * 国产ROM兼容：Compose中LocalContext通常是Activity，但有时为ContextWrapper。
 */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun PhotoActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 0.5f else 0.2f),
                shape = RoundedCornerShape(12.dp)
            )
            .background(
                if (enabled) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
            )
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EditablePhotoItem(
    photo: PhotoEntity,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val painter = rememberAsyncImagePainter(model = File(photo.filePath))
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        if (painter.state is AsyncImagePainter.State.Loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        // 删除按钮
        Surface(
            modifier = Modifier
                .size(24.dp)
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-4).dp),
            shape = CircleShape,
            color = Color(0xFFBA1A1A)
        ) {
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.White
                )
            }
        }
    }
}
