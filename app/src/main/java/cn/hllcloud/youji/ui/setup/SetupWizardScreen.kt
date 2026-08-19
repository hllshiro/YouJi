package cn.hllcloud.youji.ui.setup

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.hllcloud.youji.YouJiApplication
import cn.hllcloud.youji.data.VlmSettings
import cn.hllcloud.youji.ui.settings.ModelSelectorField
import cn.hllcloud.youji.ui.settings.SettingsInputField
import cn.hllcloud.youji.ui.settings.SettingsViewModel
import cn.hllcloud.youji.ui.settings.TestResultCard

/**
 * 首次启动配置引导页。对应设计文档 V3 第 2.5 节。
 *
 * 进入条件：`setup_completed` 标记为 false（首次启动或设置页修改后重置）。
 *
 * UI 结构：
 * - 顶部欢迎语
 * - Card 1: VLM 配置（baseUrl + apiKey + 模型下拉 + 测试按钮）
 * - Card 2: 地理编码服务可用性展示
 * - 底部「完成配置后开始使用」按钮：仅当 VLM 测试通过 AND geo 可用时启用，
 *   点击后保存配置 + 置 setup_completed=true + 调用 [onSetupComplete] 跳转首页
 *
 * 复用 SettingsViewModel 提供的 VLM 测试 / 模型拉取 / geo 检测能力，
 * 避免逻辑重复。
 *
 * @param onSetupComplete 配置完成后回调，调用方据此跳转首页（popUpTo setup inclusive）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    onSetupComplete: () -> Unit,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            LocalContext.current.applicationContext as YouJiApplication
        )
    )
) {
    val currentSettings by viewModel.vlmSettings.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()
    val geoAvailable by viewModel.geoAvailable.collectAsStateWithLifecycle()

    // 本地编辑状态：用户输入即更新，未保存直到点击「完成配置」
    var localSettings by remember(currentSettings) { mutableStateOf(currentSettings) }

    // 测试结果自动重置：每次修改 baseUrl/apiKey/modelName 后清空旧测试结果
    LaunchedEffect(localSettings.apiUrl, localSettings.apiKey, localSettings.modelName) {
        viewModel.clearTestState()
    }

    // 进入页面时检测地理编码可用性
    LaunchedEffect(Unit) {
        viewModel.checkGeoService()
    }

    val vlmPassed = testState is SettingsViewModel.VlmTestState.Success
    val geoOk = geoAvailable == true
    val canComplete = vlmPassed && geoOk

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "欢迎使用 YouJi",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "使用前需要完成以下配置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
            // ===== Card 1: VLM 大模型 API =====
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
                                text = "1. VLM 大模型 API",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "接入大模型 AI 才能生成游记，必填",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusPill(passed = vlmPassed)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsInputField(
                        label = "API 地址",
                        value = localSettings.apiUrl,
                        onValueChange = {
                            localSettings = localSettings.copy(
                                apiUrl = it,
                                enabled = true
                            )
                        },
                        placeholder = "https://api.openai.com/v1/chat/completions",
                        icon = Icons.Default.Language
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SettingsInputField(
                        label = "API Key",
                        value = localSettings.apiKey,
                        onValueChange = {
                            localSettings = localSettings.copy(
                                apiKey = it,
                                enabled = true
                            )
                        },
                        placeholder = "sk-...",
                        icon = Icons.Default.Key,
                        isPassword = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 模型选择（下拉 + 自定义回退）
                    ModelSelectorField(
                        localSettings = localSettings,
                        onModelChange = {
                            localSettings = localSettings.copy(
                                modelName = it,
                                enabled = true
                            )
                        },
                        viewModel = viewModel
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 测试按钮
                    Button(
                        onClick = {
                            // 测试时先临时启用，避免 enabled=false 导致测试失败
                            viewModel.testVlmConnection(localSettings.copy(enabled = true))
                        },
                        enabled = testState !is SettingsViewModel.VlmTestState.Testing,
                        modifier = Modifier.fillMaxWidth(),
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
                            Icon(
                                imageVector = Icons.Default.NetworkCheck,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("测试连接（必须通过）", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    // 测试结果展示（复用 SettingsScreen 的 TestResultCard）
                    when (val state = testState) {
                        is SettingsViewModel.VlmTestState.Success -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            TestResultCard(
                                icon = Icons.Default.CheckCircle,
                                iconTint = Color(0xFF2E7D32),
                                containerColor = Color(0xFF2E7D32).copy(alpha = 0.12f),
                                title = "测试通过 · 支持 Vision",
                                message = state.message,
                                extra = "耗时 ${state.latencyMs}ms"
                            )
                        }
                        is SettingsViewModel.VlmTestState.NoVision -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            TestResultCard(
                                icon = Icons.Default.Error,
                                iconTint = Color(0xFFEF6C00),
                                containerColor = Color(0xFFEF6C00).copy(alpha = 0.12f),
                                title = "API 可达，但不支持 Vision",
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
                }
            }

            // ===== Card 2: 地理编码服务 =====
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
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
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "2. 地理编码服务",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "用于把照片 GPS 反查为地名，必填",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusPill(passed = geoOk)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 服务可用性展示
                    when (geoAvailable) {
                        null -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "正在检测...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        true -> {
                            InfoBox(
                                icon = Icons.Default.CheckCircle,
                                iconTint = Color(0xFF2E7D32),
                                containerColor = Color(0xFF2E7D32).copy(alpha = 0.12f),
                                text = "服务可用：系统 Geocoder 或 Nominatim 在线服务可正常调用"
                            )
                        }
                        false -> {
                            InfoBox(
                                icon = Icons.Default.Error,
                                iconTint = Color(0xFFBA1A1A),
                                containerColor = Color(0xFFBA1A1A).copy(alpha = 0.12f),
                                text = "服务不可用：系统 Geocoder 不可用且 Nominatim 网络不通。" +
                                    "请检查网络后重试，或在设置页配置高德 Key"
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== 底部「完成配置后开始使用」按钮 =====
            Button(
                onClick = {
                    viewModel.completeSetup(localSettings.copy(enabled = true)) {
                        onSetupComplete()
                    }
                },
                enabled = canComplete,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = "完成配置后开始使用",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (!canComplete) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildString {
                        if (!vlmPassed) append("• VLM 测试未通过\n")
                        if (!geoOk) append("• 地理编码服务未通过")
                    }.trimEnd(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * 状态徽标：✓ 通过 / ✗ 未通过。用于卡片右上角。
 */
@Composable
private fun StatusPill(passed: Boolean) {
    val (color, text) = if (passed) {
        Color(0xFF2E7D32) to "已通过"
    } else {
        Color(0xFFBA1A1A) to "未通过"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 简单信息提示框（带左侧图标），用于 geo 卡片的状态展示。
 */
@Composable
private fun InfoBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    containerColor: Color,
    text: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = iconTint
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
