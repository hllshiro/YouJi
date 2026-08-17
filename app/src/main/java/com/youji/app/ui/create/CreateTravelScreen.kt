package com.youji.app.ui.create

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.youji.app.R
import com.youji.app.YouJiApplication
import com.youji.app.data.entity.PhotoEntity
import com.youji.app.util.DateFormatUtil
import com.youji.app.util.FileUtil
import com.youji.app.util.PermissionUtil
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

/**
 * 创建游记页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTravelScreen(
    editNoteId: Long? = null,
    onNavigateBack: () -> Unit,
    onSaved: (Long) -> Unit,
    viewModel: CreateTravelViewModel = viewModel(
        factory = CreateTravelViewModel.Factory(
            LocalContext.current.applicationContext as YouJiApplication
        )
    )
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val vlmSettings by viewModel.vlmSettings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var cameraPhotoFile by remember { mutableStateOf<File?>(null) }
    var showGenerateOptions by remember { mutableStateOf(false) }
    // 标记是否已经请求过权限，用于区分"首次未请求"和"被永久拒绝"
    var hasRequestedCameraPermission by remember { mutableStateOf(false) }
    var hasRequestedGalleryPermission by remember { mutableStateOf(false) }
    // 永久拒绝权限时显示引导对话框
    var showPermissionSettingsDialog by remember { mutableStateOf(false) }
    var permissionSettingsMessage by remember { mutableStateOf("") }

    // 初始化编辑模式
    LaunchedEffect(editNoteId) {
        if (editNoteId != null) {
            viewModel.setEditMode(editNoteId)
        }
    }

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
            // 国产ROM兼容：即使返回false也可能授予了USER_SELECTED，再校验一次
            galleryLauncher.launch("image/*")
        } else {
            // 用户拒绝了。判断是否被永久拒绝（部分国产ROM如MIUI可能直接永久拒绝）
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

    // 保存
    val saveAndExit: () -> Unit = {
        scope.launch {
            val result = viewModel.save()
            if (result != null) {
                onSaved(result)
            } else {
                snackbarHostState.showSnackbar("请至少添加标题、内容或照片")
            }
        }
    }

    // 选择日期
    val showDatePicker = { isStart: Boolean ->
        val cal = Calendar.getInstance()
        if (isStart) {
            uiState.startDate?.let { cal.timeInMillis = it }
        } else {
            uiState.endDate?.let { cal.timeInMillis = it }
        }
        val listener = DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month)
            cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            if (isStart) {
                viewModel.updateStartDate(cal.timeInMillis)
            } else {
                viewModel.updateEndDate(cal.timeInMillis)
            }
        }
        DatePickerDialog(
            context,
            listener,
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (editNoteId != null) stringResource(R.string.create_edit)
                               else stringResource(R.string.create_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = saveAndExit) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.common_save),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
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

                        // 照片预览行
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            // 拍照按钮
                            item {
                                PhotoActionButton(
                                    icon = Icons.Default.CameraAlt,
                                    label = stringResource(R.string.create_take_photo),
                                    onClick = {
                                        // 国产ROM兼容：先检查权限，避免无权限直接启动相机崩溃
                                        if (PermissionUtil.hasCameraPermission(context)) {
                                            launchCamera()
                                        } else {
                                            cameraPermissionLauncher.launch(
                                                PermissionUtil.getCameraPermissions().toTypedArray()
                                            )
                                        }
                                    }
                                )
                            }

                            // 图库选择按钮
                            item {
                                PhotoActionButton(
                                    icon = Icons.Default.PhotoLibrary,
                                    label = stringResource(R.string.create_select_photos),
                                    onClick = {
                                        // 国产ROM兼容：先检查读图权限
                                        if (PermissionUtil.hasReadImagePermission(context)) {
                                            galleryLauncher.launch("image/*")
                                        } else {
                                            galleryPermissionLauncher.launch(
                                                PermissionUtil.getReadImagePermissions().toTypedArray()
                                            )
                                        }
                                    }
                                )
                            }

                            // 已选照片
                            items(uiState.selectedPhotos, key = { it.id.takeIf { it != 0L } ?: it.filePath }) { photo ->
                                SelectedPhotoItem(
                                    photo = photo,
                                    onRemove = { viewModel.removePhoto(photo) }
                                )
                            }
                        }
                    }
                }
            }

            // 日期范围
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
                        Text(
                            text = stringResource(R.string.create_date_range),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            DateItem(
                                label = stringResource(R.string.create_start_date),
                                dateText = DateFormatUtil.formatDate(uiState.startDate),
                                onClick = { showDatePicker(true) },
                                modifier = Modifier.weight(1f)
                            )
                            DateItem(
                                label = stringResource(R.string.create_end_date),
                                dateText = DateFormatUtil.formatDate(uiState.endDate),
                                onClick = { showDatePicker(false) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // 生成内容按钮
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (uiState.selectedPhotos.isEmpty()) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("请先添加照片")
                                }
                            } else {
                                viewModel.generateLocalContent()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(imageVector = Icons.Default.AutoFixHigh, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "智能生成", style = MaterialTheme.typography.labelLarge)
                    }

                    if (vlmSettings.enabled) {
                        Button(
                            onClick = {
                                if (uiState.selectedPhotos.isEmpty()) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("请先添加照片")
                                    }
                                } else {
                                    viewModel.generateVlmContent()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(vertical = 12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "VLM生成", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // 加载中或错误
            item {
                if (uiState.isGenerating) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "正在生成游记内容...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                uiState.generateError?.let { error ->
                    LaunchedEffect(error) {
                        snackbarHostState.showSnackbar("生成失败: $error")
                        viewModel.clearGenerateError()
                    }
                }
            }

            // VLM使用提示
            if (uiState.isGeneratedByVlm) {
                item {
                    AssistChip(
                        onClick = {},
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        label = { Text("内容由VLM大模型生成，建议您编辑润色") },
                        colors = AssistChipDefaults.assistChipColors(
                            leadingIconContentColor = MaterialTheme.colorScheme.primary,
                            labelColor = MaterialTheme.colorScheme.primary,
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // 标题输入
            item {
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedTextField(
                    value = uiState.title,
                    onValueChange = { viewModel.updateTitle(it) },
                    label = { Text(stringResource(R.string.create_title_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // 正文输入
            item {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.content,
                    onValueChange = { viewModel.updateContent(it) },
                    label = { Text(stringResource(R.string.create_content_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(300.dp),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.6f
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
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
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .size(96.dp)
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
    onRemove: () -> Unit
) {
    Box(modifier = Modifier.size(96.dp)) {
        val painter = rememberAsyncImagePainter(model = File(photo.filePath))
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
        if (painter.state is AsyncImagePainter.State.Loading) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(12.dp))
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

@Composable
private fun DateItem(
    label: String,
    dateText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.DateRange,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = dateText.ifBlank { "请选择日期" },
            style = MaterialTheme.typography.titleSmall,
            color = if (dateText.isBlank()) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.onSurface
        )
    }
}
