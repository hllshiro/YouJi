package cn.hllcloud.youji.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.hllcloud.youji.R
import cn.hllcloud.youji.YouJiApplication
import cn.hllcloud.youji.data.VlmSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 设置页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            LocalContext.current.applicationContext as YouJiApplication
        )
    )
) {
    val currentSettings by viewModel.vlmSettings.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearDataConfirm by remember { mutableStateOf(false) }

    // 临时编辑状态
    var localSettings by remember(currentSettings) { mutableStateOf(currentSettings) }
    var hasChanges by remember { mutableStateOf(false) }

    // 测试状态自动重置：每次修改配置后清空旧的测试结果
    LaunchedEffect(localSettings.apiUrl, localSettings.apiKey, localSettings.modelName) {
        viewModel.clearTestState()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (hasChanges) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    viewModel.saveAll(localSettings)
                                    hasChanges = false
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.settings_saved),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.settings_save),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // VLM大模型配置区
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_vlm),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "接入大模型AI生成更个性化的游记",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = localSettings.enabled,
                            onCheckedChange = {
                                localSettings = localSettings.copy(enabled = it)
                                hasChanges = true
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }

                    if (localSettings.enabled) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(20.dp))

                        // API地址
                        SettingsInputField(
                            label = stringResource(R.string.settings_vlm_api_url),
                            value = localSettings.apiUrl,
                            onValueChange = {
                                localSettings = localSettings.copy(apiUrl = it)
                                hasChanges = true
                            },
                            placeholder = "https://api.openai.com/v1/chat/completions",
                            icon = Icons.Default.Language
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // API Key
                        SettingsInputField(
                            label = stringResource(R.string.settings_vlm_api_key),
                            value = localSettings.apiKey,
                            onValueChange = {
                                localSettings = localSettings.copy(apiKey = it)
                                hasChanges = true
                            },
                            placeholder = "sk-...",
                            icon = Icons.Default.Key,
                            isPassword = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 模型名称 - 下拉选择 + 自定义输入回退
                        ModelSelectorField(
                            localSettings = localSettings,
                            onModelChange = {
                                localSettings = localSettings.copy(modelName = it)
                                hasChanges = true
                            },
                            viewModel = viewModel
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 自定义提示词
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.settings_vlm_prompt),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = localSettings.customPrompt,
                                onValueChange = {
                                    localSettings = localSettings.copy(customPrompt = it)
                                    hasChanges = true
                                },
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.settings_vlm_prompt_hint),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 测试API连通性 & Vision能力
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 测试按钮：使用当前未保存的配置进行测试
                            Button(
                                onClick = { viewModel.testVlmConnection(localSettings) },
                                enabled = testState !is SettingsViewModel.VlmTestState.Testing,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(vertical = 12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            ) {
                                if (testState is SettingsViewModel.VlmTestState.Testing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("测试中...", style = MaterialTheme.typography.labelLarge)
                                } else {
                                    Icon(imageVector = Icons.Default.NetworkCheck, contentDescription = null,
                                        modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("测试API（Vision）", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }

                        // 测试结果展示
                        when (val state = testState) {
                            is SettingsViewModel.VlmTestState.Success -> {
                                Spacer(modifier = Modifier.height(10.dp))
                                TestResultCard(
                                    icon = Icons.Default.CheckCircle,
                                    iconTint = Color(0xFF2E7D32),
                                    containerColor = Color(0xFF2E7D32).copy(alpha = 0.12f),
                                    title = "测试通过 · 支持Vision",
                                    message = state.message,
                                    extra = "耗时 ${state.latencyMs}ms"
                                )
                            }
                            is SettingsViewModel.VlmTestState.NoVision -> {
                                Spacer(modifier = Modifier.height(10.dp))
                                TestResultCard(
                                    icon = Icons.Default.VisibilityOff,
                                    iconTint = Color(0xFFEF6C00),
                                    containerColor = Color(0xFFEF6C00).copy(alpha = 0.12f),
                                    title = "API可达，但不支持Vision",
                                    message = state.message,
                                    extra = "请更换支持视觉的模型，如 gpt-4o / qwen-vl-max"
                                )
                            }
                            is SettingsViewModel.VlmTestState.Error -> {
                                Spacer(modifier = Modifier.height(10.dp))
                                TestResultCard(
                                    icon = Icons.Default.Error,
                                    iconTint = Color(0xFFBA1A1A),
                                    containerColor = Color(0xFFBA1A1A).copy(alpha = 0.12f),
                                    title = "测试失败",
                                    message = state.message,
                                    extra = null
                                )
                            }
                            else -> { /* Idle / Testing: 不显示结果卡片 */ }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 提示
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                                )
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "提示：数据仅在需要生成VLM内容时发送至您配置的API服务器。请确保使用可信的服务端地址。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // 数据管理区
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showClearDataConfirm = true }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = Color(0xFFBA1A1A),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_clear_data),
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFFBA1A1A)
                            )
                            Text(
                                text = "删除所有游记和照片数据",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 关于
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_about_content),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // 清除数据确认
    if (showClearDataConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearDataConfirm = false },
            title = { Text("清除所有数据") },
            text = { Text(stringResource(R.string.settings_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    // 清除数据库
                    val app = context.applicationContext as YouJiApplication
                    scope.launch {
                        val allNotes = app.repository.getAllTravelNotesOnce()
                        allNotes.forEach { note ->
                            app.repository.deleteTravelNoteById(note.id)
                        }
                        Toast.makeText(context, "已清除所有数据", Toast.LENGTH_SHORT).show()
                    }
                    showClearDataConfirm = false
                }) {
                    Text(stringResource(R.string.detail_confirm), color = Color(0xFFBA1A1A))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataConfirm = false }) {
                    Text(stringResource(R.string.detail_cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isPassword: Boolean = false
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

/**
 * VLM API测试结果展示卡片
 */
@Composable
private fun TestResultCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    containerColor: Color,
    title: String,
    message: String,
    extra: String?
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .padding(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = iconTint
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = iconTint,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!extra.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = extra,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 模型选择字段。
 *
 * 行为：
 * - 当 baseUrl 和 apiKey 都已填写且 models 拉取成功 → 显示下拉选择框
 * - 当 models 拉取失败或为空 → 自动回退到自定义输入框，并显示错误提示和"切换为下拉"按钮
 * - 用户可主动点击"自定义输入"按钮切换到输入框模式（例如想填一个 /models 接口没返回的模型 id）
 * - 用户可从自定义输入模式切回下拉模式
 *
 * 拉取时机：
 * - 进入页面时如已配置 baseUrl+apiKey 但 models 还未拉取过（Idle），自动触发一次
 * - 用户修改 baseUrl 或 apiKey 后，外部会清空 modelsState 为 Idle，本组件再次自动触发
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectorField(
    localSettings: VlmSettings,
    onModelChange: (String) -> Unit,
    viewModel: SettingsViewModel
) {
    val modelsState by viewModel.modelsState.collectAsStateWithLifecycle()
    val useCustomInput by viewModel.useCustomModelInput.collectAsStateWithLifecycle()

    // baseUrl 或 apiKey 变化时重新拉取模型列表。
    // - 两者都已填写且非自定义模式：防抖 500ms 后触发拉取，避免每次按键都发请求
    // - 任一为空：重置 modelsState，避免显示上一个 API 拉取到的旧模型列表
    LaunchedEffect(localSettings.apiUrl, localSettings.apiKey) {
        if (localSettings.apiUrl.isNotBlank() && localSettings.apiKey.isNotBlank() && !useCustomInput) {
            delay(500)
            viewModel.fetchModels(localSettings)
        } else if (localSettings.apiUrl.isBlank() || localSettings.apiKey.isBlank()) {
            viewModel.resetModelsState()
        }
    }

    // models 拉取成功后，如果当前 modelName 为空或不在列表中，自动选中第一个
    // 仅更新 localSettings，不保存到 repository，避免覆盖用户未保存的 apiUrl/apiKey
    LaunchedEffect(modelsState) {
        if (modelsState is SettingsViewModel.ModelsState.Success && !useCustomInput) {
            val models = (modelsState as SettingsViewModel.ModelsState.Success).models
            val current = localSettings.modelName
            if (models.isNotEmpty() && (current.isBlank() || !models.contains(current))) {
                onModelChange(models.first())
            }
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_vlm_model),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            // 切换按钮：在下拉模式和自定义输入模式之间切换
            TextButton(
                onClick = {
                    if (useCustomInput) {
                        viewModel.switchToDropdownModel()
                        // 切回下拉时若 baseUrl/apiKey 已填，重新拉取，避免显示旧 API 的模型列表
                        if (localSettings.apiUrl.isNotBlank() && localSettings.apiKey.isNotBlank()) {
                            viewModel.fetchModels(localSettings)
                        }
                    } else {
                        viewModel.switchToCustomModelInput()
                    }
                }
            ) {
                Icon(
                    imageVector = if (useCustomInput) Icons.Default.Refresh else Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (useCustomInput) "切回下拉" else "自定义输入",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        when {
            // 用户主动选择自定义输入模式
            useCustomInput -> {
                OutlinedTextField(
                    value = localSettings.modelName,
                    onValueChange = onModelChange,
                    placeholder = {
                        Text(
                            text = "输入模型 id，如 gpt-4o / qwen-vl-max",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
            // 拉取中
            modelsState is SettingsViewModel.ModelsState.Loading -> {
                OutlinedTextField(
                    value = "正在获取可用模型...",
                    onValueChange = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            // 拉取成功 → 下拉选择
            modelsState is SettingsViewModel.ModelsState.Success -> {
                val models = (modelsState as SettingsViewModel.ModelsState.Success).models
                var expanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = localSettings.modelName,
                        onValueChange = {},
                        readOnly = true,
                        placeholder = {
                            Text(
                                text = if (models.isEmpty()) "无可用模型" else "请选择模型",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        models.forEach { modelId ->
                            DropdownMenuItem(
                                text = { Text(modelId) },
                                onClick = {
                                    onModelChange(modelId)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
            // 拉取失败 → 回退到输入框并显示错误提示
            modelsState is SettingsViewModel.ModelsState.Error -> {
                val errMsg = (modelsState as SettingsViewModel.ModelsState.Error).message
                OutlinedTextField(
                    value = localSettings.modelName,
                    onValueChange = onModelChange,
                    placeholder = {
                        Text(
                            text = "获取模型列表失败，请手动输入模型 id",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    isError = true,
                    supportingText = {
                        Text(
                            text = errMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        errorBorderColor = MaterialTheme.colorScheme.error
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { viewModel.fetchModels(localSettings) }
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("重试获取模型列表", style = MaterialTheme.typography.labelMedium)
                }
            }
            // Idle 或 baseUrl/apiKey 未填 → 输入框
            else -> {
                OutlinedTextField(
                    value = localSettings.modelName,
                    onValueChange = onModelChange,
                    placeholder = {
                        Text(
                            text = if (localSettings.apiUrl.isBlank() || localSettings.apiKey.isBlank()) {
                                "请先填写 baseUrl 和 apiKey 后自动获取"
                            } else {
                                "输入模型 id，如 gpt-4o / qwen-vl-max"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    enabled = !(localSettings.apiUrl.isBlank() || localSettings.apiKey.isBlank()),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}
