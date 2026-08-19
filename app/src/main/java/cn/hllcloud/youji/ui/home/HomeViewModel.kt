package cn.hllcloud.youji.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.hllcloud.youji.YouJiApplication
import cn.hllcloud.youji.data.TravelRepository
import cn.hllcloud.youji.data.entity.TravelNoteEntity
import cn.hllcloud.youji.data.entity.WorkflowTaskEntity
import cn.hllcloud.youji.util.DateFormatUtil
import cn.hllcloud.youji.util.Phase
import cn.hllcloud.youji.util.WorkflowEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel。
 *
 * 暴露两组数据：
 * - [travelNotes]：已生成的游记列表（来自 [TravelRepository]）
 * - [pendingTasks]：上一会话遗留或本次会话暂停 / 失败的工作流任务
 *   （status=paused 或 failed），对应设计 V3 第 2.4 节
 *   "启动恢复机制 + 首页待恢复区块"及失败信息显示要求。
 *
 * 恢复操作通过 [WorkflowEngine.resume] 异步触发，引擎内部跳过已完成阶段，
 * 调用方立即返回，UI 通过 DAO Flow 观察状态变化。
 */
class HomeViewModel(
    application: Application,
    private val repository: TravelRepository,
    private val workflowEngine: WorkflowEngine
) : AndroidViewModel(application) {

    val travelNotes: StateFlow<List<TravelNoteUiModel>> = repository.getAllTravelNotes()
        .map { notes ->
            notes.map { entity -> entity.toUiModel() }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * 待处理任务列表（status=paused 或 failed）。
     *
     * 合并 [TravelRepository.getPausedWorkflowTasks] 与
     * [TravelRepository.getFailedWorkflowTasks] 两个流：
     * - paused 任务展示「恢复 / 放弃」按钮，对应"待恢复"语义
     * - failed 任务展示「重试 / 放弃」按钮 + 错误原因摘要，对应
     *   设计 V3 失败信息显示要求
     *
     * 排序：failed 任务在前（需用户优先关注），paused 任务在后；
     * 同状态内按 createdAt DESC（最近创建的优先展示在顶部）。
     * 首页在 [travelNotes] 之上展示该区块；列表为空时不渲染。
     */
    val pendingTasks: StateFlow<List<PendingTaskUiModel>> =
        combine(
            repository.getPausedWorkflowTasks(),
            repository.getFailedWorkflowTasks()
        ) { paused, failed ->
            // failed 在前、paused 在后；组内按 createdAt DESC
            val failedSorted = failed.sortedByDescending { it.createdAt }
                .map { it.toPendingUiModel() }
            val pausedSorted = paused.sortedByDescending { it.createdAt }
                .map { it.toPendingUiModel() }
            failedSorted + pausedSorted
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    fun deleteNote(noteId: Long) {
        viewModelScope.launch {
            repository.deleteTravelNoteById(noteId)
        }
    }

    /**
     * 恢复单个任务。转发到 [WorkflowEngine.resume]，引擎异步执行，
     * 完成后 UI 通过 [pendingTasks]/[travelNotes] Flow 自动刷新。
     *
     * 调用方通常会跳转到进度页观察执行过程，本方法只负责触发恢复。
     */
    fun resumeTask(taskId: Long) {
        viewModelScope.launch {
            try {
                workflowEngine.resume(taskId) { /* 进度由进度页订阅，首页忽略 */ }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 恢复全部任务。对应设计 V3 第 2.4 节"恢复全部"按钮。
     *
     * 仅对 status=paused 的任务自动触发恢复；failed 任务需用户在卡片上
     * 主动点击「重试」（避免静默重试一个已知失败的任务）。列表为空时返回 null。
     *
     * 引擎内部使用单线程调度器串行执行多个恢复，依次触发即可。
     * 第一个被恢复任务的进度由调用方（首页）通过跳转进度页观察，
     * 其余任务在引擎内部排队。
     *
     * @return 第一个被恢复任务的 id，供 UI 跳转进度页；列表为空时返回 null
     */
    fun resumeAll(): Long? {
        val pausedTasks = pendingTasks.value.filter { it.status == "paused" }
        val firstId = pausedTasks.firstOrNull()?.taskId ?: return null
        viewModelScope.launch {
            // 串行 resume，避免并发触发多份 runLoop
            for (task in pausedTasks) {
                try {
                    workflowEngine.resume(task.taskId) { }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return firstId
    }

    /**
     * 放弃单个任务。转发到 [WorkflowEngine.abandon]，引擎写入固定失败文案
     * "用户手动停止任务"。放弃后任务从待恢复列表消失（status → failed），
     * 但保留 created_note_id 关联的已生成游记（若有）。
     */
    fun abandonTask(taskId: Long) {
        viewModelScope.launch {
            try {
                workflowEngine.abandon(taskId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    data class TravelNoteUiModel(
        val id: Long,
        val title: String,
        val content: String,
        val coverPhotoPath: String?,
        val dateRangeText: String,
        val locationSummary: String?,
        val updatedAtText: String
    )

    /**
     * 待处理任务的精简 UI 模型。
     *
     * @param taskId 任务 id
     * @param status 任务状态原始值（"paused" 或 "failed"），UI 据此切换按钮文案与配色
     * @param title 显示标题，优先用风格名，无则 fallback "未命名游记"
     * @param currentPhaseText 当前阶段文案（如"解析地理位置"），来自 [Phase.displayName]
     * @param currentIndex 当前阶段序号（0-based），用于 (N/M) 展示
     * @param totalPhases 总阶段数
     * @param createdAtText 创建时间文案
     * @param photoCount 关联的照片数量
     * @param errorMessage 失败任务的错误信息（暂停任务一般为空）
     */
    data class PendingTaskUiModel(
        val taskId: Long,
        val status: String,
        val title: String,
        val currentPhaseText: String,
        val currentIndex: Int,
        val totalPhases: Int,
        val createdAtText: String,
        val photoCount: Int,
        val errorMessage: String?
    )

    private fun TravelNoteEntity.toUiModel(): TravelNoteUiModel {
        return TravelNoteUiModel(
            id = id,
            title = title,
            content = content,
            coverPhotoPath = coverPhotoPath,
            dateRangeText = DateFormatUtil.formatDateRange(startDate, endDate),
            locationSummary = locationSummary,
            updatedAtText = "更新于 ${DateFormatUtil.formatShort(updatedAt)}"
        )
    }

    private fun WorkflowTaskEntity.toPendingUiModel(): PendingTaskUiModel {
        val phase = Phase.fromName(currentPhase)
        val photoCount = parseInputPhotoPathCount(inputPhotoPaths)
        return PendingTaskUiModel(
            taskId = id,
            status = status,
            title = selectedStyleName?.takeIf { it.isNotBlank() }
                ?: "未命名游记",
            currentPhaseText = phase.displayName,
            currentIndex = currentPhaseIndex,
            totalPhases = totalPhases,
            createdAtText = DateFormatUtil.formatShort(createdAt),
            photoCount = photoCount,
            errorMessage = errorMessage
        )
    }

    /**
     * 简单解析 inputPhotoPaths（JSON 数组）的长度，失败返回 0。
     * 仅用于展示"共 N 张照片"，不做严格校验。
     */
    private fun parseInputPhotoPathCount(json: String): Int {
        return try {
            val array = org.json.JSONArray(json)
            array.length()
        } catch (e: Exception) {
            0
        }
    }

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as YouJiApplication
            return HomeViewModel(application, app.repository, app.workflowEngine) as T
        }
    }
}
