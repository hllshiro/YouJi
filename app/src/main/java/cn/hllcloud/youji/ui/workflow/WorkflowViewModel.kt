package cn.hllcloud.youji.ui.workflow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.hllcloud.youji.YouJiApplication
import cn.hllcloud.youji.data.entity.WorkflowPhaseResultEntity
import cn.hllcloud.youji.data.entity.WorkflowTaskEntity
import cn.hllcloud.youji.util.Phase
import cn.hllcloud.youji.util.PhaseStatus
import cn.hllcloud.youji.util.WorkflowEngine
import cn.hllcloud.youji.util.WorkflowException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 工作流状态 ViewModel（详情页与进度页共用）。
 *
 * 对应设计文档 V3 第 2.3 节"进度页和详情页共用同一套工作流状态 ViewModel"。
 *
 * 通过 [taskId] 从 DAO 订阅 workflow_task + workflow_phase_results，组合出 UI 状态。
 * 操作方法（[resumeTask]/[pauseTask]/[abandonTask]）直接转发到 [WorkflowEngine]。
 *
 * 引擎 [WorkflowEngine.resume] 在内部协程中异步执行，本 VM 不阻塞等待；
 * 任务完成/失败事件通过观察 [taskFlow] 的状态变化自动触发。
 */
class WorkflowViewModel(
    application: Application,
    private val workflowEngine: WorkflowEngine,
    private val app: YouJiApplication,
    private val taskId: Long
) : AndroidViewModel(application) {

    /** 工作流任务实体（可观测）。 */
    private val taskFlow = app.database.workflowTaskDao().getById(taskId)

    /** 阶段中间结果列表（可观测）。 */
    private val phaseResultsFlow = app.database.workflowPhaseResultDao().getByTaskId(taskId)

    /**
     * UI 状态：组合 task + phase results，并附加操作中标志。
     */
    val uiState: StateFlow<WorkflowUiState> =
        combine(taskFlow, phaseResultsFlow) { task, results ->
            buildUiState(task, results)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = WorkflowUiState(taskId = taskId)
        )

    /** 操作进行中标志（避免按钮重复点击）。 */
    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    /** 操作错误信息（一次性，UI 用 LaunchedEffect 消费）。 */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    /** 最近一次进度回调消息，用于进度页展示细粒度文本（如 "解析中 (3/8 张)"）。 */
    private val _progressMessage = MutableStateFlow<String?>(null)
    val progressMessage: StateFlow<String?> = _progressMessage.asStateFlow()

    /** 完成事件（一次性 ID），UI 监听后跳转详情页。 */
    private val _completedEvent = MutableStateFlow<Long?>(null)
    val completedEvent: StateFlow<Long?> = _completedEvent.asStateFlow()

    init {
        // 监听任务状态变化：完成时发完成事件，失败时发错误事件
        viewModelScope.launch {
            uiState
                .map { Triple(it.status, it.createdNoteId ?: taskId, it.errorMessage) }
                .distinctUntilChanged()
                .collect { (status, id, errorMessage) ->
                    when (status) {
                        WorkflowStatus.COMPLETED -> {
                            if (_completedEvent.value == null) {
                                _completedEvent.value = id
                            }
                        }
                        WorkflowStatus.FAILED -> {
                            if (errorMessage != null && _actionError.value == null) {
                                _actionError.value = errorMessage
                            }
                        }
                        else -> { /* 其他状态不触发事件 */ }
                    }
                }
        }
    }

    /** 清空一次性事件（UI 消费后调用）。 */
    fun consumeActionError() { _actionError.value = null }
    fun consumeCompletedEvent() { _completedEvent.value = null }

    /**
     * 启动 / 恢复任务。覆盖 pending（草稿启动）/paused/failed（重试）三种入口。
     * 引擎在内部协程中异步执行，本方法立即返回——UI 通过 [uiState] 观察进度。
     */
    fun resumeTask() {
        if (_isWorking.value) return
        _isWorking.value = true
        viewModelScope.launch {
            try {
                workflowEngine.resume(taskId) { progress ->
                    _progressMessage.value = formatProgressMessage(progress)
                }
                // resume() 立即返回（引擎内部协程异步执行），无需在此处发完成事件
                // 完成事件由 init 块中的 uiState 观察者触发
            } catch (e: WorkflowException) {
                _actionError.value = e.message ?: "恢复失败"
            } catch (e: Exception) {
                _actionError.value = e.message ?: "恢复失败"
            } finally {
                _isWorking.value = false
            }
        }
    }

    /**
     * 暂停任务（协作式取消：当前阶段到达安全停止点后退出）。
     */
    fun pauseTask() {
        if (_isWorking.value) return
        _isWorking.value = true
        viewModelScope.launch {
            try {
                workflowEngine.pause(taskId)
            } catch (e: Exception) {
                _actionError.value = e.message ?: "暂停失败"
            } finally {
                _isWorking.value = false
            }
        }
    }

    /**
     * 放弃任务：固定写入 error_message = "用户手动停止任务"，不可恢复。
     */
    fun abandonTask() {
        if (_isWorking.value) return
        _isWorking.value = true
        viewModelScope.launch {
            try {
                workflowEngine.abandon(taskId)
            } catch (e: Exception) {
                _actionError.value = e.message ?: "放弃失败"
            } finally {
                _isWorking.value = false
            }
        }
    }

    /**
     * 重试失败任务。语义等同于 [resumeTask]——已完成阶段会被跳过。
     */
    fun retryTask() = resumeTask()

    /**
     * 增量更新游记。对应设计 V3 第 5.3 节场景三。
     *
     * 调用前置条件：task.status == COMPLETED 且 hasPendingEdit == 1。
     * 引擎 [WorkflowEngine.runIncrementalUpdate] 在内部协程中异步执行，
     * 本方法立即返回，UI 通过 [uiState] 观察状态变化（status 由 completed → running → completed）。
     *
     * 失败时（如 VLM 调用失败）通过 [actionError] 推送错误信息，引擎已写 task.status = failed。
     */
    fun incrementalUpdate() {
        if (_isWorking.value) return
        _isWorking.value = true
        viewModelScope.launch {
            try {
                workflowEngine.runIncrementalUpdate(taskId) { progress ->
                    _progressMessage.value = formatProgressMessage(progress)
                }
                // 引擎异步执行，完成事件由 init 块中的 uiState 观察者触发
            } catch (e: WorkflowException) {
                _actionError.value = e.message ?: "增量更新失败"
            } catch (e: Exception) {
                _actionError.value = e.message ?: "增量更新失败"
            } finally {
                _isWorking.value = false
            }
        }
    }

    // ===== 内部辅助 =====

    private fun buildUiState(
        task: WorkflowTaskEntity?,
        results: List<WorkflowPhaseResultEntity>
    ): WorkflowUiState {
        if (task == null) {
            return WorkflowUiState(taskId = taskId, status = WorkflowStatus.IDLE)
        }
        val phaseStatuses = Phase.ordered().associateWith { phase ->
            // 优先从 phase_results 表读取实际状态；缺失时回退到 task 状态推断
            val result = results.firstOrNull { it.phase == phase.name }
            when {
                result != null -> PhaseStatus.fromName(result.status)
                // task 整体 completed 时所有阶段都视为已完成
                task.status == "completed" -> PhaseStatus.COMPLETED
                // 当前阶段正在执行
                phase.name.equals(task.currentPhase, ignoreCase = true) &&
                    task.status == "running" -> PhaseStatus.RUNNING
                // 失败时当前阶段标记为 FAILED
                phase.name.equals(task.currentPhase, ignoreCase = true) &&
                    task.status == "failed" -> PhaseStatus.FAILED
                else -> PhaseStatus.PENDING
            }
        }
        return WorkflowUiState(
            taskId = task.id,
            status = WorkflowStatus.fromName(task.status),
            currentPhase = Phase.fromName(task.currentPhase),
            currentIndex = task.currentPhaseIndex,
            totalPhases = task.totalPhases,
            geocodeDone = task.geocodeDoneCount,
            geocodeTotal = task.geocodeTotalCount,
            errorMessage = task.errorMessage,
            createdNoteId = task.createdNoteId,
            phaseStatuses = phaseStatuses,
            selectedStyleName = task.selectedStyleName
        )
    }

    /**
     * 将 TaskProgress 格式化为 UI 文案，如 "(2/5) 解析地理位置 (3/8 张)"。
     */
    private fun formatProgressMessage(progress: cn.hllcloud.youji.util.TaskProgress): String {
        val builder = StringBuilder()
        builder.append("(${progress.currentIndex + 1}/${progress.totalPhases}) ")
            .append(progress.currentPhase.displayName)
        if (progress.currentPhase == Phase.GEOCODE && progress.geocodeTotal > 0) {
            builder.append(" (${progress.geocodeDone}/${progress.geocodeTotal} 张)")
        }
        progress.message?.let { builder.append(" · ").append(it) }
        return builder.toString()
    }

    data class WorkflowUiState(
        val taskId: Long = 0,
        val status: WorkflowStatus = WorkflowStatus.IDLE,
        val currentPhase: Phase = Phase.PREPARE,
        val currentIndex: Int = 0,
        val totalPhases: Int = 5,
        val geocodeDone: Int = 0,
        val geocodeTotal: Int = 0,
        val errorMessage: String? = null,
        val createdNoteId: Long? = null,
        val phaseStatuses: Map<Phase, PhaseStatus> = emptyMap(),
        val selectedStyleName: String? = null
    ) {
        /** "(N/M) 阶段名" 格式的进度文本，对应设计 4.3 节。 */
        val progressText: String
            get() = "(${currentIndex + 1}/$totalPhases) ${currentPhase.displayName}"

        /** Geocode 阶段的细粒度文本，如 "3/8 张"。 */
        val geocodeProgressText: String?
            get() = if (currentPhase == Phase.GEOCODE && geocodeTotal > 0) {
                "$geocodeDone/$geocodeTotal 张"
            } else null
    }

    class Factory(
        private val application: Application,
        private val taskId: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as YouJiApplication
            return WorkflowViewModel(
                application,
                app.workflowEngine,
                app,
                taskId
            ) as T
        }
    }
}

/**
 * 工作流任务状态枚举（UI 层使用，对应 workflow_task.status 列）。
 */
enum class WorkflowStatus {
    IDLE,
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED;

    companion object {
        fun fromName(name: String?): WorkflowStatus =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: IDLE
    }

    /** 状态徽标文案，对应设计 2.3 节状态徽标。 */
    val displayName: String
        get() = when (this) {
            IDLE -> "未启动"
            PENDING -> "草稿"
            RUNNING -> "运行中"
            PAUSED -> "已暂停"
            COMPLETED -> "已完成"
            FAILED -> "失败"
        }
}
