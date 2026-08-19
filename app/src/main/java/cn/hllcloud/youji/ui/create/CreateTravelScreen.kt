package cn.hllcloud.youji.ui.create

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import cn.hllcloud.youji.R
import cn.hllcloud.youji.YouJiApplication
import cn.hllcloud.youji.data.entity.PhotoEntity
import cn.hllcloud.youji.util.FileUtil
import cn.hllcloud.youji.util.PermissionUtil
import kotlinx.coroutines.launch
import java.io.File

/**
 * 创建游记页（V3 版本）。
 *
 * 仅保留：照片选择区（3列网格）+ 风格选择行（Chip 形态）+ 操作按钮组
 * （保存草稿 / 开始生成）。
 *
 * 移除的组件：标题输入框、日期范围选择卡、正文输入框、智能生成 / VLM 生成按钮、
 * 生成进度条（移到独立进度页）、VLM 提示 Chip。
 *
 * 对应设计文档 V3 第 2.1 节、Task 6。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateTravelScreen(
    onNavigateBack: () -> Unit,
    onDraftSaved: (Long) -> Unit,
    onWorkflowStarted: (Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStyleManager: () -> Unit,
    viewModel: CreateTravelViewModel = viewModel(
        factory = CreateTravelViewModel.Factory(
            LocalContext.current.applicationContext as YouJiApplication
        )
    )
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val styles by viewModel.styles.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var cameraPhotoFile by remember { mutableStateOf<File?>(null) }

    // 标记是否已经请求过权限，用于区分"首次未请求"和"被永久拒绝"
    var hasRequestedCameraPermission by remember { mutableStateOf(false) }
    var hasRequestedGalleryPermission by remember { mutableStateOf(false) }
    // 永久拒绝权限时显示引导对话框
    var showPermissionSettingsDialog by remember { mutableStateOf(false) }
    var permissionSettingsMessage by remember { mutableStateOf("") }

    // 图库选择
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        viewModel.addPhotosFromUris(uris, context)
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
     * 启动相机（已具备权限时调用）
     * 国产ROM兼容：显式授予URI读写权限，避免部分系统裁剪/拍照时崩溃
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
        hasRequestedGalleryPermission = true
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
        hasRequestedCameraPermission = true
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

    // 默认选中第一个内置风格（仅在首次风格列表加载完毕且用户未选时）
    LaunchedEffect(styles) {
        if (uiState.selectedStyleId == null && styles.isNotEmpty()) {
            val firstBuiltin = styles.firstOrNull { it.isBuiltin == 1 } ?: styles.first()
            viewModel.selectStyle(firstBuiltin)
        }
    }

    // 保存草稿
    val saveDraft: () -> Unit = {
        if (uiState.selectedPhotos.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("请先选择照片") }
        } else {
            scope.launch {
                val taskId = viewModel.saveDraft()
                if (taskId > 0) {
                    onDraftSaved(taskId)
                } else {
                    snackbarHostState.showSnackbar("保存草稿失败")
                }
            }
        }
    }

    // 开始生成
    val startWorkflow: () -> Unit = {
        scope.launch {
            val (taskId, error) = viewModel.startWorkflow()
            if (taskId > 0) {
                onWorkflowStarted(taskId)
            } else if (error != null) {
                // 配置未完成时，提示并跳转设置引导
                snackbarHostState.showSnackbar(error)
                if (error.contains("VLM") || error.contains("地理编码")) {
                    onNavigateToSettings()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.create_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null)
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
            // 照片选择区域
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
                                text = stringResource(R.string.create_select_photos),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(
                                    R.string.create_photos_count,
                                    uiState.selectedPhotos.size
                                ),
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
                                label = stringResource(R.string.create_take_photo),
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
                                label = stringResource(R.string.create_select_photos),
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
                        if (uiState.selectedPhotos.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Divider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            uiState.selectedPhotos.chunked(3).forEach { rowPhotos ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowPhotos.forEach { photo ->
                                        SelectedPhotoItem(
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
                        }
                    }
                }
            }

            // 风格选择行
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "写作风格",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            // 风格管理入口（对应设计 V3 第 2.1 节"风格选择行 +管理"链接）
                            TextButton(onClick = onNavigateToStyleManager) {
                                Text("管理", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        // 风格 Chip 列表：FlowRow 自动换行
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            styles.forEach { style ->
                                FilterChip(
                                    selected = style.id == uiState.selectedStyleId,
                                    onClick = { viewModel.selectStyle(style) },
                                    label = { Text(style.name) },
                                    leadingIcon = if (style.isBuiltin == 1) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.AddAPhoto,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }

            // 操作按钮组
            item {
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 保存草稿：仅持久化，不启动工作流
                    OutlinedButton(
                        onClick = saveDraft,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        enabled = uiState.selectedPhotos.isNotEmpty()
                    ) {
                        Icon(imageVector = Icons.Default.Drafts, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "保存草稿", style = MaterialTheme.typography.labelLarge)
                    }
                    // 开始生成：校验通过后启动工作流
                    Button(
                        onClick = startWorkflow,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        ),
                        enabled = uiState.selectedPhotos.isNotEmpty() && uiState.selectedStyleId != null
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "开始生成", style = MaterialTheme.typography.labelLarge)
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
                    Text(stringResource(R.string.detail_cancel))
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            )
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.primary
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
private fun SelectedPhotoItem(
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
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
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
