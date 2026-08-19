package cn.hllcloud.youji.ui.workflow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.hllcloud.youji.YouJiApplication
import kotlinx.coroutines.launch

/**
 * 工作流进度页（全屏，对应设计 V3 第 2.2 节）。
 *
 * 用户从创建游记页点「开始生成」后跳转至此，全程显示：
 * - 当前阶段名 + "(N/M)" 序号进度（无百分比、无权重，对应 4.3 节）
 * - 阶段列表（✅ 已完成 / ⏳ 进行中 / ⬜ 待执行）
 * - Geocode 阶段额外展示反查细粒度 "(3/8 张)"
 * - 底部按钮按状态切换：暂停 / 恢复 / 完成 / 重试 / 放弃
 *
 * 运行中按返回键不退出（避免误操作）；完成后自动跳转详情页。
 *
 * @param taskId 工作流任务 id
 * @param onCompleted 完成后跳转详情页，参数为 noteId（或 taskId 兜底）
 * @param onAbandoned 放弃后回调（通常返回创建页或首页）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowProgressScreen(
    taskId: Long,
    onCompleted: (Long) -> Unit,
    onAbandoned: () -> Unit,
    viewModel: WorkflowViewModel = viewModel(
        factory = WorkflowViewModel.Factory(
            LocalContext.current.applicationContext as YouJiApplication,
            taskId
        )
    )
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isWorking by viewModel.isWorking.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val completedEvent by viewModel.completedEvent.collectAsStateWithLifecycle()
    val progressMessage by viewModel.progressMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showPauseDialog by remember { mutableStateOf(false) }
    var showAbandonDialog by remember { mutableStateOf(false) }

    // 运行中禁用系统返回键，避免误操作中断任务
    BackHandler(enabled = state.status == WorkflowStatus.RUNNING) {
        // 不退出，提示用户主动暂停
        scope.launch {
            snackbarHostState.showSnackbar("任务运行中，请先暂停再退出")
        }
    }
    // 暂停/失败状态下允许返回（用户已主动操作）
    BackHandler(enabled = state.status == WorkflowStatus.PAUSED || state.status == WorkflowStatus.FAILED) {
        onAbandoned()
    }

    // 错误事件展示
    LaunchedEffect(actionError) {
        actionError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeActionError()
        }
    }

    // 任务完成时自动跳转：等待 800ms 展示完成状态后跳转详情页。
    // 对应设计 2.2 节"任务完成后自动关闭，跳转到游记详情页"。
    LaunchedEffect(state.status) {
        if (state.status == WorkflowStatus.COMPLETED) {
            kotlinx.coroutines.delay(800)
            onCompleted(state.createdNoteId ?: taskId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "正在生成游记...",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    // 状态徽标 + 当前阶段进度
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(status = state.status)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = state.progressText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Geocode 细粒度进度
                    state.geocodeProgressText?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // 实时进度文案（如 VLM 调用中的 "解析中 (3/8 张)"）
                    progressMessage?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // 阶段列表
                    PhaseProgressList(state = state)

                    // 失败时显示错误原因
                    val errorMsg = state.errorMessage
                    if (state.status == WorkflowStatus.FAILED && !errorMsg.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFFDECEC)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color(0xFFBA1A1A)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = errorMsg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFBA1A1A)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // 底部按钮（按状态切换）
                    ProgressScreenButtons(
                        status = state.status,
                        isWorking = isWorking,
                        onPause = { showPauseDialog = true },
                        onResume = { viewModel.resumeTask() },
                        onRetry = { viewModel.retryTask() },
                        onAbandon = { showAbandonDialog = true },
                        onComplete = {
                            onCompleted(state.createdNoteId ?: taskId)
                        }
                    )
                }
            }
        }
    }

    if (showPauseDialog) {
        PauseConfirmDialog(
            onConfirm = {
                showPauseDialog = false
                viewModel.pauseTask()
            },
            onDismiss = { showPauseDialog = false },
            currentPhaseProgress = state.currentPhase.displayName
        )
    }

    if (showAbandonDialog) {
        AbandonConfirmDialog(
            onConfirm = {
                showAbandonDialog = false
                viewModel.abandonTask()
                onAbandoned()
            },
            onDismiss = { showAbandonDialog = false }
        )
    }
}

/**
 * 进度页底部按钮组。对应设计 V3 第 2.2 节底部按钮规则。
 */
@Composable
private fun ProgressScreenButtons(
    status: WorkflowStatus,
    isWorking: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onAbandon: () -> Unit,
    onComplete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (status) {
            WorkflowStatus.RUNNING -> {
                OutlinedButton(
                    onClick = onPause,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("暂停")
                }
            }
            WorkflowStatus.PAUSED -> {
                Button(
                    onClick = onResume,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    )
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("恢复")
                }
            }
            WorkflowStatus.COMPLETED -> {
                Button(
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E7E34),
                        contentColor = Color.White
                    )
                ) {
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("完成")
                }
            }
            WorkflowStatus.FAILED -> {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                    enabled = !isWorking,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    )
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("重试")
                }
                OutlinedButton(
                    onClick = onAbandon,
                    modifier = Modifier.weight(1f),
                    enabled = !isWorking,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("放弃")
                }
            }
            WorkflowStatus.IDLE,
            WorkflowStatus.PENDING -> {
                // 进度页不应进入这两种状态（pending 在创建页就应启动后跳转过来）
                // 兜底显示恢复按钮，允许用户手动触发
                Button(
                    onClick = onResume,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("开始")
                }
            }
        }
    }
}
