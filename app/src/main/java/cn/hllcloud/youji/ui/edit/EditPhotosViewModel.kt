package cn.hllcloud.youji.ui.edit

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.hllcloud.youji.YouJiApplication
import cn.hllcloud.youji.data.entity.PhotoEntity
import cn.hllcloud.youji.util.ExifUtil
import cn.hllcloud.youji.util.FileUtil
import cn.hllcloud.youji.util.WorkflowEngine
import cn.hllcloud.youji.util.WorkflowException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * 编辑照片页 ViewModel。对应设计 V3 第 5.1/5.2/5.3 节。
 *
 * 职责：
 * 1. 订阅 workflow_task 状态，把 `inputPhotoPaths` JSON 解析为可编辑的 PhotoEntity 列表
 *    （仅初始化一次；之后由用户在 UI 上增删照片时维护本地副本）。
 * 2. 任务处于 running 时禁止编辑（引擎会抛异常），UI 据此禁用按钮并提示先暂停。
 * 3. [saveEdit] 把新路径列表交给 [WorkflowEngine.applyPhotoEdit] 计算并应用 diff——
 *    增删图片的持久化与 diff 计算全部由引擎统一处理，VM 只负责在内存中编辑路径列表。
 */
class EditPhotosViewModel(
    application: Application,
    private val workflowEngine: WorkflowEngine,
    private val app: YouJiApplication,
    private val taskId: Long
) : AndroidViewModel(application) {

    /** 工作流任务（可观测），用于读取 status 与 inputPhotoPaths。 */
    private val taskFlow = app.database.workflowTaskDao().getById(taskId)

    private val _taskStatus = MutableStateFlow("pending")
    private val _editablePhotos = MutableStateFlow<List<PhotoEntity>>(emptyList())
    private val _isSaving = MutableStateFlow(false)
    private val _resultMessage = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<String?>(null)

    /** 标记 editablePhotos 是否已初始化，避免 task 流后续重发覆盖用户的编辑。 */
    private var photosInitialized = false

    init {
        // 订阅任务流：首次拿到任务时用 inputPhotoPaths 初始化本地副本；
        // 之后只跟随 task.status 变化（如 running → paused 时 UI 状态同步更新）。
        viewModelScope.launch {
            taskFlow.collect { task ->
                if (task == null) return@collect
                _taskStatus.value = task.status
                if (!photosInitialized) {
                    _editablePhotos.value = parseInputPhotoPaths(task.inputPhotoPaths)
                    photosInitialized = true
                }
            }
        }
    }

    /**
     * UI 状态：组合 task.status + 当前编辑副本 + 操作标志与一次性事件。
     */
    val uiState: StateFlow<EditUiState> =
        combine(
            _taskStatus,
            _editablePhotos,
            _isSaving,
            _resultMessage,
            _error
        ) { status, photos, isSaving, resultMessage, error ->
            EditUiState(
                taskStatus = status,
                photos = photos,
                isSaving = isSaving,
                resultMessage = resultMessage,
                error = error
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = EditUiState()
        )

    /** 是否可编辑（running 时禁止）。 */
    val canEdit: Boolean
        get() = _taskStatus.value != "running"

    /**
     * 添加照片（从 Uri 导入）。复制到应用私有目录后读 EXIF，append 到本地列表。
     */
    fun addPhotoFromUri(uri: Uri, context: Context) {
        if (!canEdit) {
            _error.value = "任务运行中，请先暂停再编辑"
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val fileName = "import_${System.currentTimeMillis()}.jpg"
                val copiedFile = FileUtil.copyUriToInternal(context, uri, fileName)
                if (copiedFile != null) {
                    addPhotoFileInternal(copiedFile)
                } else {
                    _error.value = "复制照片失败"
                }
            }
        }
    }

    /**
     * 添加照片（拍照后）。
     */
    fun addPhotoFromFile(file: File) {
        if (!canEdit) {
            _error.value = "任务运行中，请先暂停再编辑"
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                addPhotoFileInternal(file)
            }
        }
    }

    private fun addPhotoFileInternal(file: File) {
        val metadata = ExifUtil.readMetadata(file.absolutePath)
        val photo = PhotoEntity(
            filePath = file.absolutePath,
            fileName = file.name,
            takenAt = metadata.takenAt,
            latitude = metadata.latitude,
            longitude = metadata.longitude,
            exifMake = metadata.make,
            exifModel = metadata.model,
            workflowTaskId = taskId
        )
        val current = _editablePhotos.value.toMutableList()
        current.add(photo)
        _editablePhotos.value = current
    }

    /**
     * 移除照片（仅从本地列表删除，磁盘文件由引擎 [applyPhotoEdit] 在保存时统一清理）。
     */
    fun removePhoto(photo: PhotoEntity) {
        if (!canEdit) {
            _error.value = "任务运行中，请先暂停再编辑"
            return
        }
        val current = _editablePhotos.value.toMutableList()
        // 用 filePath 作为唯一标识，因为重建的 PhotoEntity 没有 id
        current.removeAll { it.filePath == photo.filePath }
        _editablePhotos.value = current
    }

    /**
     * 保存编辑。把当前 [editablePhotos] 的路径列表交给引擎 [WorkflowEngine.applyPhotoEdit]，
     * 引擎计算 diff 并按场景一/二/三执行副作用。
     *
     * 成功时把 [WorkflowEngine.PhotoEditResult] 格式化为文案写入 [resultMessageFlow]，
     * 并通过 [onSaved] 回调通知 UI（如导航返回）；失败时把异常信息写入 [errorFlow]。
     */
    fun saveEdit(onSaved: (WorkflowEngine.PhotoEditResult) -> Unit = {}) {
        if (!canEdit) {
            _error.value = "任务运行中，请先暂停再编辑"
            return
        }
        if (_isSaving.value) return
        val photos = _editablePhotos.value
        if (photos.isEmpty()) {
            _error.value = "至少保留一张照片"
            return
        }
        _isSaving.value = true
        viewModelScope.launch {
            try {
                val newPaths = photos.map { it.filePath }
                val result = workflowEngine.applyPhotoEdit(taskId, newPaths)
                _resultMessage.value = formatResultMessage(result)
                // 用户已完成本轮编辑，重置标志让后续重新进入页面时可以重新初始化
                photosInitialized = false
                onSaved(result)
            } catch (e: WorkflowException) {
                _error.value = e.message ?: "保存失败"
            } catch (e: Exception) {
                _error.value = e.message ?: "保存失败"
            } finally {
                _isSaving.value = false
            }
        }
    }

    /** 一次性结果文案消费。 */
    fun consumeResult() { _resultMessage.value = null }
    fun consumeError() { _error.value = null }

    // ===== 内部辅助 =====

    /**
     * 把 inputPhotoPaths JSON 解析为最小 PhotoEntity 列表（仅 filePath + fileName，
     * EXIF 元数据在引擎 [WorkflowEngine.insertAddedPhotosAsEntities] 中会重新读取）。
     *
     * 已存在的照片不需要重新读 EXIF——它们在 Prepare 阶段已落库，
     * 删除后引擎也会按 filePath 找到对应 PhotoEntity 进行清理。
     */
    private fun parseInputPhotoPaths(json: String?): List<PhotoEntity> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { idx ->
                val path = array.getString(idx)
                PhotoEntity(
                    filePath = path,
                    fileName = File(path).name,
                    workflowTaskId = taskId
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun formatResultMessage(result: WorkflowEngine.PhotoEditResult): String {
        val parts = mutableListOf<String>()
        if (result.addedCount > 0) parts += "新增 ${result.addedCount} 张"
        if (result.removedCount > 0) parts += "删除 ${result.removedCount} 张"
        if (result.unchangedCount > 0) parts += "保留 ${result.unchangedCount} 张"
        val scenario = when (result.scenario) {
            "pending" -> "草稿已更新"
            "paused", "failed" -> "已应用，可点「恢复/重试」继续"
            "completed" -> "已应用，点「增量更新」生成新内容"
            else -> ""
        }
        val base = if (parts.isEmpty()) "无变化" else parts.joinToString("，")
        return if (scenario.isBlank()) base else "$base · $scenario"
    }

    /**
     * UI 状态。
     */
    data class EditUiState(
        val taskStatus: String = "pending",
        val photos: List<PhotoEntity> = emptyList(),
        val isSaving: Boolean = false,
        val resultMessage: String? = null,
        val error: String? = null
    )

    class Factory(
        private val application: Application,
        private val taskId: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as YouJiApplication
            return EditPhotosViewModel(
                application,
                app.workflowEngine,
                app,
                taskId
            ) as T
        }
    }
}
