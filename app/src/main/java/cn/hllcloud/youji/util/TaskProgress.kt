package cn.hllcloud.youji.util

/**
 * 工作流阶段枚举。
 *
 * 对应设计文档 V3 第 4.5 节，按执行顺序排列：
 * PREPARE → GEOCODE → LOCAL_GEN → VLM_GEN → SAVE。
 */
enum class Phase {
    PREPARE,
    GEOCODE,
    LOCAL_GEN,
    VLM_GEN,
    SAVE;

    val order: Int get() = ordinal

    val displayName: String
        get() = when (this) {
            PREPARE -> "读取照片信息"
            GEOCODE -> "解析地理位置"
            LOCAL_GEN -> "整理行程内容"
            VLM_GEN -> "AI 生成游记"
            SAVE -> "保存游记"
        }

    companion object {
        /**
         * 按执行顺序返回全部阶段，便于 WorkflowEngine 顺序迭代。
         */
        fun ordered(): List<Phase> = listOf(PREPARE, GEOCODE, LOCAL_GEN, VLM_GEN, SAVE)

        /**
         * 从字符串反序列化，无效输入回退到 [PREPARE]。
         */
        fun fromName(name: String?): Phase =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: PREPARE
    }
}

/**
 * 单个阶段的状态枚举。
 *
 * - PENDING：尚未开始执行
 * - RUNNING：执行中
 * - COMPLETED：已完成（成功）
 * - FAILED：执行失败
 */
enum class PhaseStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED;

    val statusName: String get() = name.lowercase()

    companion object {
        fun fromName(name: String?): PhaseStatus =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: PENDING
    }
}

/**
 * 工作流执行进度快照，由 [WorkflowEngine] 通过 onProgress 回调向 UI 推送。
 *
 * 对应设计文档 V3 第 4.5 节 TaskProgress。
 *
 * @param taskId 任务 id
 * @param currentPhase 当前执行阶段
 * @param phaseStatuses 各阶段的状态映射（key 为 [Phase]，value 为 [PhaseStatus]）
 * @param currentIndex 当前阶段序号（0-based）
 * @param totalPhases 总阶段数（默认 5）
 * @param geocodeDone Geocode 阶段已反查数（细粒度展示，仅 Geocode 阶段有意义）
 * @param geocodeTotal Geocode 阶段总照片数
 * @param message 状态文案（如失败原因），可空
 */
data class TaskProgress(
    val taskId: Long,
    val currentPhase: Phase,
    val phaseStatuses: Map<Phase, PhaseStatus>,
    val currentIndex: Int,
    val totalPhases: Int,
    val geocodeDone: Int = 0,
    val geocodeTotal: Int = 0,
    val message: String? = null
)

/**
 * 启动前置校验结果。
 *
 * 对应设计文档 V3 第 4.4 节 canStartWorkflow()。
 *
 * @param canStart 是否可以启动
 * @param missingVlm 是否缺少 VLM 配置（启用/URL/key/模型名任一缺失即 true）
 * @param missingGeo 是否缺少地理编码配置（所有 GeocoderService 不可用即 true）
 */
data class WorkflowStartCheck(
    val canStart: Boolean,
    val missingVlm: Boolean,
    val missingGeo: Boolean
)

/**
 * 工作流异常基类。
 *
 * 用于工作流内部显式抛出的可恢复/可展示错误：
 * - Prepare 阶段无有效 EXIF 信息
 * - VLM 调用失败
 * - 阶段依赖未满足（如本地生成阶段未完成）
 *
 * 这些异常会被 [WorkflowEngine.start]/[WorkflowEngine.resume] 的 catch 块统一捕获，
 * 写入 workflow_task 的 error_message 字段。
 */
class WorkflowException(message: String, cause: Throwable? = null) : Exception(message, cause)
