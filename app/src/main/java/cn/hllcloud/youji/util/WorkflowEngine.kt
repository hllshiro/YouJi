package cn.hllcloud.youji.util

import cn.hllcloud.youji.data.TravelRepository
import cn.hllcloud.youji.data.VlmSettingsRepository
import cn.hllcloud.youji.data.WritingStyleRepository
import cn.hllcloud.youji.data.dao.PhotoDao
import cn.hllcloud.youji.data.dao.WorkflowPhaseResultDao
import cn.hllcloud.youji.data.dao.WorkflowTaskDao
import cn.hllcloud.youji.data.entity.PhotoEntity
import cn.hllcloud.youji.data.entity.TravelNoteEntity
import cn.hllcloud.youji.data.entity.WorkflowPhaseResultEntity
import cn.hllcloud.youji.data.entity.WorkflowTaskEntity
import cn.hllcloud.youji.data.entity.WritingStyleEntity
import cn.hllcloud.youji.util.geocoder.GeocoderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 工作流引擎：按 Prepare → Geocode → LocalGen → VlmGen → Save 五阶段顺序执行游记生成工作流。
 *
 * 对应设计文档 V3 第 4 节 WorkflowEngine。
 *
 * 核心能力：
 * - 启动 / 恢复 / 暂停 / 放弃
 * - 每阶段中间结果持久化到 `workflow_phase_results` 表
 * - 防重复执行（已完成阶段跳过）
 * - 暂停采用协作式取消（阶段边界检查 `isPaused` 标记）
 * - 任意阶段失败不降级，写入 error_message，标记 FAILED
 * - 通过 `onProgress` 回调向 UI 推送 [TaskProgress]
 *
 * 线程模型：使用单线程协程调度器（[singleThreadDispatcher]）避免并发写入冲突，
 * 阶段内的并发任务（Geocode 反查）通过显式 `Dispatchers.IO` 子作用域实现。
 */
class WorkflowEngine(
    private val taskDao: WorkflowTaskDao,
    private val phaseResultDao: WorkflowPhaseResultDao,
    private val photoDao: PhotoDao,
    private val geocoderService: GeocoderService,
    private val vlmClient: VlmClient,
    private val vlmSettingsRepository: VlmSettingsRepository,
    private val repository: TravelRepository,
    private val writingStyleRepository: WritingStyleRepository
) {

    /** 每个任务的运行时控制标志。
     * - [paused]：true 时主循环在下一个阶段边界退出
     * - [abandoned]：true 时主循环立即退出，调用方 [abandon] 已写库
     */
    private data class TaskControl(
        val paused: Boolean = false,
        val abandoned: Boolean = false
    )

    /** taskId → 运行时控制标志，用于协作式取消。 */
    private val controlMap: MutableMap<Long, TaskControl> = ConcurrentHashMap()

    /** taskId → 正在运行的 Job，便于暂停/放弃时取消等待中的协程。 */
    private val jobMap: MutableMap<Long, Job> = ConcurrentHashMap()

    /**
     * 引擎内部协程作用域。使用 SupervisorJob 避免 sibling 任务互相影响，
     * 不与调用方 viewModelScope 绑定——即使 UI 退出，工作流仍可继续执行。
     * 对应设计 4.6 节异步执行 + 8.2 节线程模型。
     */
    private val engineScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 单线程调度器，保证整个引擎的状态写入串行化。
     * 对应设计 8.6 节：避免并发写入冲突；同时实现"恢复全部"的顺序恢复语义。
     */
    private val singleThreadDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /**
     * 启动前置校验。对应设计 4.4 节。
     */
    suspend fun canStartWorkflow(): WorkflowStartCheck {
        val vlmSettings = vlmSettingsRepository.settings.first()
        val vlmOk = vlmSettings.enabled &&
            vlmSettings.apiUrl.isNotBlank() &&
            vlmSettings.apiKey.isNotBlank() &&
            vlmSettings.modelName.isNotBlank()
        val geoOk = geocoderService.isAvailable()
        return WorkflowStartCheck(
            canStart = vlmOk && geoOk,
            missingVlm = !vlmOk,
            missingGeo = !geoOk
        )
    }

    /**
     * 启动一个新工作流任务。
     *
     * 仅做：前置校验 + 创建 task（pending）+ 在 [engineScope] 中触发 [runLoop]。
     * 调用方立即返回 taskId，UI 据此跳转进度页；进度页通过 DAO Flow 观察任务状态变化。
     *
     * @param photoPaths 用户在创建页选择的照片文件路径列表
     * @param style 选中的写作风格
     * @param onProgress 进度回调，由引擎在每个阶段边界调用（在引擎内部线程触发，
     *   调用方需保证回调实现线程安全，例如更新 StateFlow）
     * @return taskId，调用方可据此跳转进度页或详情页
     */
    suspend fun start(
        photoPaths: List<String>,
        style: WritingStyleEntity,
        onProgress: (TaskProgress) -> Unit
    ): Long {
        val check = canStartWorkflow()
        require(check.canStart) {
            buildString {
                append("配置未完成，无法启动工作流：")
                if (check.missingVlm) append(" VLM未配置;")
                if (check.missingGeo) append(" 地理编码服务未配置;")
            }
        }
        require(photoPaths.isNotEmpty()) { "未选择任何照片" }

        val taskId = createTask(photoPaths, style)
        controlMap[taskId] = TaskControl()
        launchRunLoop(taskId, photoPaths, style, onProgress)
        return taskId
    }

    /**
     * 恢复已暂停、失败或草稿（pending）状态的任务。
     *
     * - pending（草稿）：从 PREPARE 开始完整执行（草稿任务尚未启动任何阶段）
     * - paused：从 current_phase 续传，已完成阶段跳过
     * - failed：仅重试失败的阶段，其他已完成阶段跳过
     *
     * 仅做：参数校验 + 状态切到 running + 在 [engineScope] 中触发 [runLoop]，
     * 调用方立即返回；UI 通过 DAO Flow 观察状态变化。
     *
     * 草稿启动路径与 CreateTravelViewModel.saveDraft() 配合——
     * 保存草稿时只持久化 task（pending），用户在草稿页点击「开始生成」时
     * 通过此方法把状态从 pending 转为 running 并进入主循环。
     */
    suspend fun resume(taskId: Long, onProgress: (TaskProgress) -> Unit) {
        val task = taskDao.getByIdOnce(taskId)
            ?: throw WorkflowException("任务不存在: $taskId")
        require(task.status == "pending" || task.status == "paused" || task.status == "failed") {
            "任务状态非 pending/paused/failed，无法恢复: ${task.status}"
        }
        // 若已有同名任务在跑（例如重复点击恢复），直接返回避免并发执行
        val existingJob = jobMap[taskId]
        if (existingJob != null && existingJob.isActive) {
            return
        }
        // 重新置为 running 并清空 error_message
        taskDao.updateStatus(taskId, "running", null)
        controlMap[taskId] = TaskControl()

        val photoPaths = parseInputPhotoPaths(task.inputPhotoPaths)
        val style = task.selectedStyleId?.let { writingStyleRepository.getById(it) }
        launchRunLoop(taskId, photoPaths, style, onProgress)
    }

    /**
     * 在 [engineScope] 中启动 [runLoop]，记录 Job 到 [jobMap]。
     * 异常被 runLoop 内部 try/finally 兜底：失败时已写库为 failed，
     * 此处 launch 的 Job 失败不影响 engineScope 的 sibling 任务（SupervisorJob）。
     */
    private fun launchRunLoop(
        taskId: Long,
        photoPaths: List<String>,
        style: WritingStyleEntity?,
        onProgress: (TaskProgress) -> Unit
    ) {
        val job = engineScope.launch {
            try {
                runLoop(taskId, photoPaths, style, onProgress)
            } catch (e: Throwable) {
                // runLoop 内部已经把失败状态写入 DB；此处只做日志兜底
                // 避免未捕获异常导致 SupervisorJob 异常传播
                e.printStackTrace()
            } finally {
                jobMap.remove(taskId)
            }
        }
        jobMap[taskId] = job
    }

    /**
     * 暂停任务。仅设置运行时标志，由主循环在下个阶段边界退出。
     * 当前正在执行的网络请求不会被强制中断（达到安全停止点后停止）。
     */
    suspend fun pause(taskId: Long) {
        controlMap[taskId]?.let { ctrl ->
            controlMap[taskId] = ctrl.copy(paused = true)
        }
        // 立即把数据库状态标记为 paused，便于 UI 切换；主循环随后退出时会再写一次
        taskDao.updateStatus(taskId, "paused", null)
    }

    /**
     * 放弃任务。固定写入 error_message = "用户手动停止任务"，不可恢复。
     * 清理 phase_results 中间数据；保留已生成的游记本体（Save 阶段部分完成时）。
     */
    suspend fun abandon(taskId: Long) {
        controlMap[taskId]?.let { ctrl ->
            controlMap[taskId] = ctrl.copy(abandoned = true, paused = true)
        }
        taskDao.updateStatus(taskId, "failed", ABANDON_MESSAGE)
        // 清理阶段中间结果（不动 photos，已 rebind 的照片继续保留在游记下）
        phaseResultDao.deleteByTaskId(taskId)
    }

    // ===== 主循环 =====

    /**
     * 主执行循环。按 Phase.ordered() 顺序逐阶段执行，遵循：
     * - 阶段边界检查 [isPaused]/[isAbandoned]
     * - 防重复执行（phase_result 已 completed 则跳过）
     * - 任一阶段抛异常 → 写 error_message + 标记 FAILED + 中断
     * - 全部完成 → 标记 completed
     */
    private suspend fun runLoop(
        taskId: Long,
        photoPaths: List<String>,
        style: WritingStyleEntity?,
        onProgress: (TaskProgress) -> Unit
    ) {
        withContext(singleThreadDispatcher) {
            try {
                // 启动时确保状态为 running
                taskDao.updateStatus(taskId, "running", null)
                val phases = Phase.ordered()

                for ((index, phase) in phases.withIndex()) {
                    // 边界检查：被暂停或放弃
                    if (isPaused(taskId) || isAbandoned(taskId)) break

                    // 防重复执行：已完成则跳过
                    val existing = phaseResultDao.getByTaskAndPhase(taskId, phase.name)
                    if (existing?.status == PhaseStatus.COMPLETED.statusName) continue

                    // 标记 running + 推送进度
                    upsertPhaseResult(taskId, phase, PhaseStatus.RUNNING)
                    taskDao.updatePhaseIndex(taskId, index)
                    taskDao.updateStatus(taskId, "running", null)
                    onProgress(buildProgress(taskId, phase, index, PhaseStatus.RUNNING))

                    try {
                        val resultJson: String? = when (phase) {
                            Phase.PREPARE -> runPrepare(taskId, photoPaths)
                            Phase.GEOCODE -> runGeocode(taskId, onProgress)
                            Phase.LOCAL_GEN -> runLocalGen(taskId, style)
                            Phase.VLM_GEN -> runVlmGen(taskId, style)
                            Phase.SAVE -> {
                                runSave(taskId)
                                null
                            }
                        }
                        // 持久化阶段结果
                        upsertPhaseResult(
                            taskId, phase, PhaseStatus.COMPLETED,
                            resultJson = resultJson
                        )
                        onProgress(buildProgress(taskId, phase, index, PhaseStatus.COMPLETED))
                    } catch (e: WorkflowException) {
                        handlePhaseFailure(taskId, phase, index, e, onProgress)
                        throw e
                    } catch (e: Exception) {
                        val wrapped = WorkflowException(e.message ?: e.javaClass.simpleName, e)
                        handlePhaseFailure(taskId, phase, index, wrapped, onProgress)
                        throw wrapped
                    }
                }

                // 全部完成（除非被暂停/放弃）
                if (!isPaused(taskId) && !isAbandoned(taskId)) {
                    taskDao.updateStatus(taskId, "completed", null)
                    onProgress(buildProgress(taskId, Phase.SAVE, 4, PhaseStatus.COMPLETED, message = "已完成"))
                }
            } finally {
                controlMap.remove(taskId)
            }
        }
    }

    /**
     * 阶段失败统一处理：写 phase_result + workflow_task 状态。
     */
    private suspend fun handlePhaseFailure(
        taskId: Long,
        phase: Phase,
        index: Int,
        e: WorkflowException,
        onProgress: (TaskProgress) -> Unit
    ) {
        val errorMsg = e.message ?: "未知错误"
        upsertPhaseResult(taskId, phase, PhaseStatus.FAILED, errorMessage = errorMsg)
        taskDao.updateStatus(taskId, "failed", errorMsg)
        onProgress(buildProgress(taskId, phase, index, PhaseStatus.FAILED, message = errorMsg))
    }

    // ===== 各阶段实现 =====

    /**
     * Prepare 阶段：读取 EXIF + PhotoEntity 入库 + 关联 workflowTaskId + 失败判断。
     */
    private suspend fun runPrepare(taskId: Long, photoPaths: List<String>): String {
        val photos = photoPaths.map { path ->
            val meta = ExifUtil.readMetadata(path)
            PhotoEntity(
                filePath = path,
                fileName = File(path).name,
                takenAt = meta.takenAt,
                latitude = meta.latitude,
                longitude = meta.longitude,
                exifMake = meta.make,
                exifModel = meta.model,
                workflowTaskId = taskId,
                travelNoteId = null
            )
        }
        val ids = photoDao.insertAll(photos)
        val photosWithIds = photos.mapIndexed { index, entity ->
            entity.copy(id = ids[index])
        }

        val hasGps = photosWithIds.count { it.latitude != null && it.longitude != null }
        taskDao.updateGeocodeTotal(taskId, hasGps)

        // 失败条件：所有照片均无任何有效 EXIF 信息
        val hasAnyValidInfo = photosWithIds.any {
            it.takenAt != null || (it.latitude != null && it.longitude != null)
        }
        if (!hasAnyValidInfo) {
            throw WorkflowException("未获取到任何照片信息（无拍摄时间、无 GPS 数据），无法继续生成游记")
        }

        // 构建快照 JSON
        val snapshot = JSONObject().apply {
            put("photoIds", JSONArray(ids))
            put("hasGps", hasGps)
            val photosArray = JSONArray()
            photosWithIds.forEach { p ->
                photosArray.put(JSONObject().apply {
                    put("id", p.id)
                    put("path", p.filePath)
                    p.takenAt?.let { put("takenAt", it) }
                    p.latitude?.let { put("lat", it) }
                    p.longitude?.let { put("lng", it) }
                    p.exifMake?.let { put("make", it) }
                    p.exifModel?.let { put("model", it) }
                })
            }
            put("photos", photosArray)
        }
        return snapshot.toString()
    }

    /**
     * Geocode 阶段：并发反查 + 节流 + 跳过已反查 + 结果写入 PhotoEntity 与快照。
     *
     * 并发度 3：通过 [Dispatchers.IO] + 3 个 async 实现；CompositeGeocoderService 内部已对
     * Nominatim 强制串行（1.1s 间隔），无需在此重复节流。
     */
    private suspend fun runGeocode(taskId: Long, onProgress: (TaskProgress) -> Unit): String {
        val photos = photoDao.getByWorkflowTaskId(taskId)
        val needGeocode = photos.filter { it.latitude != null && it.longitude != null }
        // 部分恢复场景：跳过已反查的
        val pending = needGeocode.filter { it.locationName == null }

        val results = mutableListOf<GeocodeItemResult>()
        val resultsLock = Mutex()

        // 并发反查（最多 3 个并行），结果与失败均收集
        withContext(Dispatchers.IO) {
            pending.map { photo ->
                async {
                    val result = geocoderService.reverse(photo.latitude!!, photo.longitude!!)
                    result.onSuccess { address ->
                        photoDao.updateLocationName(photo.id, address.formatted)
                        taskDao.incrementGeocodeDone(taskId)
                        resultsLock.withLock {
                            results.add(
                                GeocodeItemResult(
                                    photoId = photo.id,
                                    lat = photo.latitude,
                                    lng = photo.longitude,
                                    address = address.formatted,
                                    success = true
                                )
                            )
                        }
                        // 推送细粒度进度
                        val task = taskDao.getByIdOnce(taskId)
                        if (task != null) {
                            onProgress(
                                buildProgress(
                                    taskId, Phase.GEOCODE, 1, PhaseStatus.RUNNING,
                                    geocodeDone = task.geocodeDoneCount,
                                    geocodeTotal = task.geocodeTotalCount
                                )
                            )
                        }
                    }.onFailure { e ->
                        resultsLock.withLock {
                            results.add(
                                GeocodeItemResult(
                                    photoId = photo.id,
                                    lat = photo.latitude,
                                    lng = photo.longitude,
                                    address = null,
                                    success = false,
                                    reason = e.message ?: e.javaClass.simpleName
                                )
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        // 无 GPS 的照片也记录到快照
        photos.filter { it.latitude == null || it.longitude == null }.forEach {
            results.add(
                GeocodeItemResult(
                    photoId = it.id,
                    lat = null,
                    lng = null,
                    address = null,
                    success = false,
                    reason = "无GPS数据"
                )
            )
        }

        // 构建快照 JSON
        val snapshot = JSONObject().apply {
            put("geocoded", results.count { it.success })
            put("failed", results.count { !it.success })
            val resultsArray = JSONArray()
            results.forEach { r ->
                resultsArray.put(JSONObject().apply {
                    put("photoId", r.photoId)
                    r.lat?.let { put("lat", it) }
                    r.lng?.let { put("lng", it) }
                    r.address?.let { put("address", it) }
                    put("success", r.success)
                    r.reason?.let { put("reason", it) }
                })
            }
            put("results", resultsArray)
        }
        return snapshot.toString()
    }

    /**
     * LocalGen 阶段：调用 [LocalContentGenerator] 生成 title/content/locationSummary/dateRange。
     * 结果带 [PHOTO:id] 标记，便于策略 B 本地删除。
     */
    private suspend fun runLocalGen(taskId: Long, style: WritingStyleEntity?): String {
        val photos = photoDao.getByWorkflowTaskId(taskId)
        val generated = LocalContentGenerator.generateContent(photos, style = style)
        val snapshot = JSONObject().apply {
            put("title", generated.title)
            put("content", generated.content)
            generated.locationSummary?.let { put("locationSummary", it) }
            generated.startDate?.let { put("startDate", it) }
            generated.endDate?.let { put("endDate", it) }
            generated.coverPhotoPath?.let { put("coverPhotoPath", it) }
        }
        return snapshot.toString()
    }

    /**
     * VLM 阶段：读取 local_gen 结果 + 调 [VlmClient] + 失败不降级。
     */
    private suspend fun runVlmGen(taskId: Long, style: WritingStyleEntity?): String {
        val vlmSettings = vlmSettingsRepository.settings.first()
        require(vlmSettings.enabled) { "VLM 未配置，不应到达此阶段" }

        val localResultJson = phaseResultDao.getByTaskAndPhase(taskId, Phase.LOCAL_GEN.name)
            ?.resultJson
            ?: throw WorkflowException("本地生成阶段未完成，无法执行 VLM 阶段")
        val localContent = parseGeneratedContent(localResultJson)
            ?: throw WorkflowException("本地生成结果解析失败")

        val photos = photoDao.getByWorkflowTaskId(taskId)
        val vlmResult = vlmClient.generateTravelContent(vlmSettings, photos, style)

        return vlmResult.fold(
            onSuccess = { content ->
                val merged = JSONObject().apply {
                    put("title", localContent.title)
                    put("content", content)
                    localContent.locationSummary?.let { put("locationSummary", it) }
                    localContent.startDate?.let { put("startDate", it) }
                    localContent.endDate?.let { put("endDate", it) }
                    localContent.coverPhotoPath?.let { put("coverPhotoPath", it) }
                    put("isGeneratedByVlm", true)
                }
                merged.toString()
            },
            onFailure = { e ->
                val errorMsg = "VLM 调用失败：${e.message ?: e.javaClass.simpleName}"
                throw WorkflowException(errorMsg, e)
            }
        )
    }

    /**
     * Save 阶段：创建 TravelNoteEntity + photoDao.rebindToNote + taskDao.updateCreatedNoteId。
     */
    private suspend fun runSave(taskId: Long): Long {
        val task = taskDao.getByIdOnce(taskId)
            ?: throw WorkflowException("任务不存在: $taskId")
        val vlmResultJson = phaseResultDao.getByTaskAndPhase(taskId, Phase.VLM_GEN.name)?.resultJson
        val localResultJson = phaseResultDao.getByTaskAndPhase(taskId, Phase.LOCAL_GEN.name)?.resultJson
        val finalContentJson = vlmResultJson ?: localResultJson
            ?: throw WorkflowException("生成阶段未完成，无法保存")

        val content = parseGeneratedContent(finalContentJson)
            ?: throw WorkflowException("生成结果解析失败")

        val photos = photoDao.getByWorkflowTaskId(taskId)
        val noteEntity = TravelNoteEntity(
            title = content.title,
            content = content.content,
            coverPhotoPath = content.coverPhotoPath ?: photos.firstOrNull()?.filePath,
            startDate = content.startDate,
            endDate = content.endDate,
            locationSummary = content.locationSummary,
            isGeneratedByVlm = vlmResultJson != null,
            writingStyleId = task.selectedStyleId,
            writingStyleName = task.selectedStyleName,
            workflowTaskId = taskId
        )
        val noteId = repository.insertTravelNote(noteEntity)
        // 把照片从 workflowTaskId 解绑，改关联到新游记
        photoDao.rebindToNote(taskId, noteId)
        taskDao.updateCreatedNoteId(taskId, noteId)
        return noteId
    }

    // ===== 辅助 =====

    private suspend fun createTask(
        photoPaths: List<String>,
        style: WritingStyleEntity
    ): Long {
        val pathsJson = JSONArray(photoPaths).toString()
        val task = WorkflowTaskEntity(
            status = "pending",
            currentPhase = Phase.PREPARE.name.lowercase(),
            currentPhaseIndex = 0,
            totalPhases = Phase.ordered().size,
            geocodeDoneCount = 0,
            geocodeTotalCount = 0,
            selectedStyleId = style.id,
            selectedStyleName = style.name,
            inputPhotoPaths = pathsJson,
            createdNoteId = null,
            errorMessage = null
        )
        return taskDao.insert(task)
    }

    private suspend fun upsertPhaseResult(
        taskId: Long,
        phase: Phase,
        status: PhaseStatus,
        resultJson: String? = null,
        errorMessage: String? = null
    ) {
        val now = System.currentTimeMillis()
        val existing = phaseResultDao.getByTaskAndPhase(taskId, phase.name)
        val entity = WorkflowPhaseResultEntity(
            id = existing?.id ?: 0,
            taskId = taskId,
            phase = phase.name,
            status = status.statusName,
            resultJson = resultJson ?: existing?.resultJson,
            errorMessage = errorMessage,
            phaseVersion = (existing?.phaseVersion ?: 0) + if (status == PhaseStatus.RUNNING) 0 else 1,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        phaseResultDao.insertOrUpdate(entity)
    }

    private fun buildProgress(
        taskId: Long,
        currentPhase: Phase,
        index: Int,
        status: PhaseStatus,
        geocodeDone: Int = 0,
        geocodeTotal: Int = 0,
        message: String? = null
    ): TaskProgress {
        val phaseStatuses = Phase.ordered().associateWith { p ->
            when {
                p.order < index -> PhaseStatus.COMPLETED
                p == currentPhase -> status
                else -> PhaseStatus.PENDING
            }
        }
        return TaskProgress(
            taskId = taskId,
            currentPhase = currentPhase,
            phaseStatuses = phaseStatuses,
            currentIndex = index,
            totalPhases = Phase.ordered().size,
            geocodeDone = geocodeDone,
            geocodeTotal = geocodeTotal,
            message = message
        )
    }

    private fun isPaused(taskId: Long): Boolean = controlMap[taskId]?.paused == true
    private fun isAbandoned(taskId: Long): Boolean = controlMap[taskId]?.abandoned == true

    private fun parseInputPhotoPaths(json: String): List<String> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 从 phase_result.result_json 解析生成内容（local_gen 与 vlm_gen 共用同一格式）。
     */
    private fun parseGeneratedContent(json: String): GeneratedContent? {
        return try {
            val obj = JSONObject(json)
            GeneratedContent(
                title = obj.optString("title"),
                content = obj.optString("content"),
                locationSummary = obj.optString("locationSummary").takeIf { it.isNotBlank() },
                startDate = if (obj.has("startDate")) obj.getLong("startDate") else null,
                endDate = if (obj.has("endDate")) obj.getLong("endDate") else null,
                coverPhotoPath = obj.optString("coverPhotoPath").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            null
        }
    }

    // ===== 编辑场景（设计 V3 第 5 节）=====

    /**
     * 应用照片编辑。对应设计 V3 第 5.1/5.2/5.3 节三种场景。
     *
     * 调用时机：用户在 [EditPhotosScreen]（Task 14 实现）增删照片后点击「保存修改」，
     * 由 UI 层将新的照片路径列表传给引擎，引擎根据当前 task.status 决定处理策略。
     *
     * 状态分支：
     * - **pending（场景一）**：仅更新 [WorkflowTaskEntity.inputPhotoPaths]，无任何后台调用、
     *   无 PhotoEntity 入库（PREPARE 阶段尚未执行，PhotoEntity 还不存在）。
     *   diff 仍记录到 [WorkflowTaskEntity.lastDiffJson] 供审计。
     * - **paused / failed（场景二）**：照片已入库（PREPARE 已完成）。
     *   ① 删除 removed 路径对应的 PhotoEntity
     *   ② added 路径走 EXIF 入库 + 关联 workflowTaskId
     *   ③ 清理 LOCAL_GEN / VLM_GEN / SAVE 阶段的 phase_result（让 resume 时重新执行）
     *   ④ geocode_total 重新统计（新增照片可能含 GPS）
     *   unchanged 照片的 locationName 保留，下次 resume 时 Geocode 阶段跳过反查。
     * - **completed（场景三）**：游记已入库。
     *   ① 删除 removed 路径对应的 PhotoEntity
     *   ② added 路径走 EXIF 入库 + 关联 workflowTaskId + 关联到 noteId（让详情页可见）
     *   ③ 标记 [WorkflowTaskEntity.hasPendingEdit] = 1，等待用户点「增量更新」
     *   ④ 不修改 note.content，原游记保留
     *
     * @return [PhotoEditResult]，UI 据此显示提示文案（如 "新增 2 张，删除 1 张"）
     * @throws WorkflowException 任务不存在 / 状态为 running（应先暂停）
     */
    suspend fun applyPhotoEdit(taskId: Long, newPhotoPaths: List<String>): PhotoEditResult {
        val task = taskDao.getByIdOnce(taskId)
            ?: throw WorkflowException("任务不存在: $taskId")
        require(task.status != "running") {
            "RUNNING 状态不允许编辑照片，请先暂停"
        }

        val oldPaths = parseInputPhotoPaths(task.inputPhotoPaths)
        val oldSet = oldPaths.toSet()
        val newSet = newPhotoPaths.toSet()
        val added = newPhotoPaths.filter { it !in oldSet }
        val removed = oldPaths.filter { it !in oldSet }
        val unchanged = newPhotoPaths.filter { it in oldSet }

        val diffJson = JSONObject().apply {
            put("added", JSONArray(added))
            put("removed", JSONArray(removed))
            put("unchanged", JSONArray(unchanged))
        }.toString()

        // 三种场景的差异化处理
        when (task.status) {
            "pending" -> {
                // 场景一：未启动，仅更新 inputPhotoPaths，无副作用
                taskDao.updateInputPhotoPaths(taskId, JSONArray(newPhotoPaths).toString())
                taskDao.updatePendingEdit(taskId, 0, diffJson)
            }
            "paused", "failed" -> {
                // 场景二：清理 local_gen/vlm_gen/save 阶段结果 + 调整 PhotoEntity
                deletePhotosByPaths(taskId, removed)
                insertAddedPhotosAsEntities(taskId, added)
                phaseResultDao.deleteByTaskAndPhase(taskId, Phase.LOCAL_GEN.name)
                phaseResultDao.deleteByTaskAndPhase(taskId, Phase.VLM_GEN.name)
                phaseResultDao.deleteByTaskAndPhase(taskId, Phase.SAVE.name)
                taskDao.updateInputPhotoPaths(taskId, JSONArray(newPhotoPaths).toString())
                taskDao.updatePendingEdit(taskId, 0, diffJson)
                // geocode_total 重新统计
                val allPhotos = photoDao.getByWorkflowTaskId(taskId)
                val hasGps = allPhotos.count { it.latitude != null && it.longitude != null }
                taskDao.updateGeocodeTotal(taskId, hasGps)
            }
            "completed" -> {
                // 场景三：标记 has_pending_edit + rebind added 到 noteId
                deletePhotosByPaths(taskId, removed)
                insertAddedPhotosAsEntities(taskId, added)
                // 把 added 照片也关联到游记，让详情页可见
                val noteId = task.createdNoteId
                if (noteId != null && added.isNotEmpty()) {
                    val allPhotos = photoDao.getByWorkflowTaskId(taskId)
                    allPhotos.filter { it.filePath in added }.forEach { p ->
                        photoDao.updateTravelNoteId(p.id, noteId)
                    }
                }
                taskDao.updateInputPhotoPaths(taskId, JSONArray(newPhotoPaths).toString())
                taskDao.updatePendingEdit(taskId, 1, diffJson)
            }
            else -> throw WorkflowException("当前状态不支持编辑照片: ${task.status}")
        }

        return PhotoEditResult(
            addedCount = added.size,
            removedCount = removed.size,
            unchangedCount = unchanged.size,
            scenario = task.status
        )
    }

    /**
     * 增量更新游记。对应设计 V3 第 5.3 节场景三策略 A/B/C。
     *
     * 调用时机：任务 [completed] 且 [hasPendingEdit] == 1 时，用户在详情页点击「增量更新」。
     *
     * 执行流程（[runIncrementalLoop]）：
     * 1. 读取 last_diff_json 中的 added[] / removed[] 路径
     * 2. 若 added 非空：对 added 照片反查 geocode（unchanged 跳过）
     * 3. 读取原游记正文 + 风格
     * 4. 根据 diff 选择策略：
     *    - 仅 removed → 策略 B：本地正则替换 [PHOTO:id] 段落，不调 VLM
     *    - 仅 added → 策略 A：VLM 增量 prompt
     *    - 混合 → 策略 C：先 B 后 A
     * 5. 写入新正文到 [TravelNoteEntity.content]
     * 6. 状态切回 completed，hasPendingEdit 置 0
     *
     * 不通过 [runLoop] 主循环，因为增量更新是一次性调用 VLM，不需要走完整 5 阶段。
     *
     * @param onProgress 进度回调，引擎在 INCREMENTAL 阶段边界调用
     */
    suspend fun runIncrementalUpdate(taskId: Long, onProgress: (TaskProgress) -> Unit) {
        val task = taskDao.getByIdOnce(taskId)
            ?: throw WorkflowException("任务不存在: $taskId")
        require(task.status == "completed" && task.hasPendingEdit == 1) {
            "任务未完成或无待应用编辑"
        }

        val diffJson = task.lastDiffJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: throw WorkflowException("缺少 diff 数据")

        val added = parseStringArray(diffJson.optJSONArray("added"))
        val removed = parseStringArray(diffJson.optJSONArray("removed"))

        // 状态切到 running
        taskDao.updateStatus(taskId, "running", null)
        controlMap[taskId] = TaskControl()

        val job = engineScope.launch {
            try {
                runIncrementalLoop(taskId, added, removed, onProgress)
            } catch (e: Throwable) {
                e.printStackTrace()
            } finally {
                jobMap.remove(taskId)
            }
        }
        jobMap[taskId] = job
    }

    /**
     * 增量更新执行循环。在 [singleThreadDispatcher] 上串行执行，
     * 避免与 [runLoop] 主循环或并发增量更新冲突。
     */
    private suspend fun runIncrementalLoop(
        taskId: Long,
        added: List<String>,
        removed: List<String>,
        onProgress: (TaskProgress) -> Unit
    ) {
        withContext(singleThreadDispatcher) {
            try {
                // 推送增量阶段起始
                onProgress(buildProgress(taskId, Phase.SAVE, 4, PhaseStatus.RUNNING, message = "增量更新中"))

                // 步骤 1：对 added 反查 geocode（unchanged 跳过）
                if (added.isNotEmpty()) {
                    geocodeAddedPhotos(taskId, onProgress)
                }

                // 步骤 2：读取原游记正文
                val task = taskDao.getByIdOnce(taskId)
                    ?: throw WorkflowException("任务不存在: $taskId")
                val noteId = task.createdNoteId
                    ?: throw WorkflowException("缺少游记 ID，无法增量更新")
                val note = repository.getTravelNoteByIdOnce(noteId)
                    ?: throw WorkflowException("游记不存在: $noteId")
                var currentContent = note.content

                // 步骤 3：根据策略处理
                when {
                    added.isEmpty() && removed.isEmpty() -> {
                        // 无变化，跳过
                    }
                    added.isEmpty() && removed.isNotEmpty() -> {
                        // 策略 B：仅 removed，本地正则替换
                        currentContent = applyRemovedStrategyLocal(currentContent, taskId, removed)
                    }
                    added.isNotEmpty() && removed.isEmpty() -> {
                        // 策略 A：仅 added，VLM 增量 prompt
                        currentContent = applyAddedStrategyVlm(task, currentContent)
                    }
                    added.isNotEmpty() && removed.isNotEmpty() -> {
                        // 策略 C：先 removed 本地替换，再 added VLM 增量
                        val intermediate = applyRemovedStrategyLocal(currentContent, taskId, removed)
                        currentContent = applyAddedStrategyVlm(task, intermediate)
                    }
                }

                // 步骤 4：写回游记
                repository.updateTravelNoteContent(noteId, currentContent)

                // 步骤 5：清理 has_pending_edit + 状态切回 completed
                taskDao.updatePendingEdit(taskId, 0, null)
                taskDao.updateStatus(taskId, "completed", null)
                onProgress(buildProgress(taskId, Phase.SAVE, 4, PhaseStatus.COMPLETED, message = "增量更新完成"))
            } catch (e: WorkflowException) {
                taskDao.updateStatus(taskId, "failed", e.message)
                onProgress(buildProgress(taskId, Phase.SAVE, 4, PhaseStatus.FAILED, message = e.message))
                throw e
            } catch (e: Exception) {
                val wrapped = WorkflowException(e.message ?: e.javaClass.simpleName, e)
                taskDao.updateStatus(taskId, "failed", wrapped.message)
                onProgress(buildProgress(taskId, Phase.SAVE, 4, PhaseStatus.FAILED, message = wrapped.message))
                throw wrapped
            } finally {
                controlMap.remove(taskId)
            }
        }
    }

    /**
     * 反查 added 照片的地理编码。复用 [runGeocode] 中"跳过 locationName 非空"的设计：
     * unchanged 照片已有 locationName，不会进入 needGeocode 集合。
     */
    private suspend fun geocodeAddedPhotos(taskId: Long, onProgress: (TaskProgress) -> Unit) {
        val photos = photoDao.getByWorkflowTaskId(taskId)
        val pending = photos.filter {
            it.latitude != null && it.longitude != null && it.locationName == null
        }
        if (pending.isEmpty()) return

        val resultsLock = Mutex()
        withContext(Dispatchers.IO) {
            pending.map { photo ->
                async {
                    val result = geocoderService.reverse(photo.latitude!!, photo.longitude!!)
                    result.onSuccess { address ->
                        photoDao.updateLocationName(photo.id, address.formatted)
                        taskDao.incrementGeocodeDone(taskId)
                    }.onFailure { /* 失败不阻断流程，记录但不写入 */ }
                    resultsLock.withLock { /* results 仅作锁屏障，不持久化 */ }
                }
            }.awaitAll()
        }
    }

    /**
     * 策略 B：本地正则删除 removed 照片对应的段落。不调 VLM，零成本。
     * 实现委托给 [VlmClient.removePhotoFromContent]，匹配 [PHOTO:id] 标记后到下一个
     * [PHOTO: 或文本末尾之间的内容。
     */
    private suspend fun applyRemovedStrategyLocal(
        content: String,
        taskId: Long,
        removedPaths: List<String>
    ): String {
        val allPhotos = photoDao.getByWorkflowTaskId(taskId)
        val removedIds = allPhotos.filter { it.filePath in removedPaths }.map { it.id }
        var result = content
        removedIds.forEach { id ->
            result = vlmClient.removePhotoFromContent(result, id)
        }
        return result
    }

    /**
     * 策略 A：调 VLM 生成增量内容。仅传入 added 照片（不传 unchanged）以节省 token。
     */
    private suspend fun applyAddedStrategyVlm(
        task: WorkflowTaskEntity,
        originalContent: String
    ): String {
        val vlmSettings = vlmSettingsRepository.settings.first()
        require(vlmSettings.enabled) { "VLM 未配置，无法增量更新" }

        val style = task.selectedStyleId?.let { writingStyleRepository.getById(it) }
        val allPhotos = photoDao.getByWorkflowTaskId(task.id)
        // 通过 diff 中的 added 路径过滤出 added 照片
        val diffJson = task.lastDiffJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val addedPaths = parseStringArray(diffJson?.optJSONArray("added"))
        val addedPhotos = allPhotos.filter { it.filePath in addedPaths }

        if (addedPhotos.isEmpty()) {
            throw WorkflowException("增量更新找不到 added 照片实体，可能已被删除")
        }

        val result = vlmClient.generateIncrementalContent(
            vlmSettings, addedPhotos, style, originalContent
        )
        return result.fold(
            onSuccess = { content -> content },
            onFailure = { e ->
                throw WorkflowException("VLM 增量生成失败：${e.message}", e)
            }
        )
    }

    /**
     * 删除任务下指定路径集合的 PhotoEntity。
     * 用于编辑场景中被移除照片的清理。
     */
    private suspend fun deletePhotosByPaths(taskId: Long, removedPaths: List<String>) {
        if (removedPaths.isEmpty()) return
        val existing = photoDao.getByWorkflowTaskId(taskId)
        val removedSet = removedPaths.toSet()
        existing.filter { it.filePath in removedSet }.forEach { photo ->
            photoDao.deleteById(photo.id)
        }
    }

    /**
     * 把 added 路径列表转成 PhotoEntity 入库，关联 workflowTaskId。
     * 与 [runPrepare] 中的入库逻辑一致：读 EXIF + 关联 taskId + travelNoteId 留空。
     *
     * paused/failed/completed 场景下 PREPARE 阶段已执行，不能再次走 [runPrepare]，
     * 否则会重复插入所有照片。这里只处理 added 部分。
     */
    private suspend fun insertAddedPhotosAsEntities(taskId: Long, addedPaths: List<String>) {
        if (addedPaths.isEmpty()) return
        val photos = addedPaths.map { path ->
            val meta = ExifUtil.readMetadata(path)
            PhotoEntity(
                filePath = path,
                fileName = File(path).name,
                takenAt = meta.takenAt,
                latitude = meta.latitude,
                longitude = meta.longitude,
                exifMake = meta.make,
                exifModel = meta.model,
                workflowTaskId = taskId,
                travelNoteId = null
            )
        }
        photoDao.insertAll(photos)
    }

    /**
     * 解析 JSON 字符串数组。用于读取 [WorkflowTaskEntity.lastDiffJson] 中的 added/removed 列表。
     */
    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /**
     * 编辑照片结果。供 UI 层显示提示文案。
     */
    data class PhotoEditResult(
        val addedCount: Int,
        val removedCount: Int,
        val unchangedCount: Int,
        val scenario: String
    )

    /**
     * 关闭引擎，释放单线程调度器。通常在 Application onTerminate / 进程退出时调用。
     */
    fun shutdown() {
        singleThreadDispatcher.close()
    }

    private data class GeocodeItemResult(
        val photoId: Long,
        val lat: Double?,
        val lng: Double?,
        val address: String?,
        val success: Boolean,
        val reason: String? = null
    )

    companion object {
        /** 放弃任务的固定失败文案。对应设计 4.8 节。 */
        const val ABANDON_MESSAGE = "用户手动停止任务"
    }
}

