package cn.hllcloud.youji.ui.workflow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import cn.hllcloud.youji.YouJiApplication
import cn.hllcloud.youji.util.Phase

/**
 * 工作流状态区宿主 Composable。
 *
 * 用途：当外部页面（如详情页）拿到一个 [taskId] 时，通过此 Composable 嵌入
 * 一个独立的 [WorkflowViewModel] 实例并渲染 [WorkflowStatusSection]。
 *
 * 该 Composable 内部调用 `viewModel()` 创建 VM，因 VM 实例需与外部 ViewModel
 * 生命周期解耦（外部 DetailViewModel 持有 noteId，本 VM 持有 taskId），
 * 通过 key 隔离避免被外部清空。
 *
 * 内置暂停/放弃确认对话框与一次性错误事件消费，对应设计 V3 2.3/2.4 节。
 *
 * 增量更新（场景三）直接调用 [WorkflowViewModel.incrementalUpdate]，无需外部传 lambda，
 * 因为引擎流程已封装在 VM 内。
 *
 * @param taskId 关联的工作流任务 id
 * @param onEditPhotos 点击「编辑照片」（跳转到 [cn.hllcloud.youji.ui.edit.EditPhotosScreen]）
 */
@Composable
fun WorkflowStatusSectionHost(
    taskId: Long,
    onEditPhotos: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as YouJiApplication
    val viewModel: WorkflowViewModel = viewModel(
        factory = WorkflowViewModel.Factory(app, taskId),
        key = "workflow_status_$taskId"
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isWorking by viewModel.isWorking.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()

    var showPauseDialog by remember { mutableStateOf(false) }
    var showAbandonDialog by remember { mutableStateOf(false) }

    WorkflowStatusSection(
        state = state,
        isWorking = isWorking,
        onStart = {
            // pending/paused/failed → resume；retry 同样调用 resumeTask
            when (state.status) {
                WorkflowStatus.PENDING,
                WorkflowStatus.PAUSED,
                WorkflowStatus.FAILED -> viewModel.resumeTask()
                else -> { /* 已完成或运行中，按钮不应出现 */ }
            }
        },
        onPause = { showPauseDialog = true },
        onAbandon = { showAbandonDialog = true },
        onEditPhotos = onEditPhotos,
        onIncrementalUpdate = { viewModel.incrementalUpdate() }
    )

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
            },
            onDismiss = { showAbandonDialog = false }
        )
    }

    // 一次性错误事件消费：避免重复展示
    LaunchedEffect(actionError) {
        actionError?.let {
            viewModel.consumeActionError()
        }
    }
}
