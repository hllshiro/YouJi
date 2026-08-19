package cn.hllcloud.youji.ui.create

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.hllcloud.youji.YouJiApplication
import cn.hllcloud.youji.data.TravelRepository
import cn.hllcloud.youji.data.VlmSettings
import cn.hllcloud.youji.data.VlmSettingsRepository
import cn.hllcloud.youji.data.WritingStyleRepository
import cn.hllcloud.youji.data.entity.PhotoEntity
import cn.hllcloud.youji.data.entity.WritingStyleEntity
import cn.hllcloud.youji.util.ExifUtil
import cn.hllcloud.youji.util.FileUtil
import cn.hllcloud.youji.util.WorkflowEngine
import cn.hllcloud.youji.util.WorkflowStartCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 创建游记页 ViewModel（V3 版本）。
 *
 * 仅负责：照片选择 + 风格选择 + 保存草稿 / 启动工作流。
 * 不再包含手动生成内容（智能生成 / VLM 生成）的入口与状态——
 * 工作流由 [WorkflowEngine.start] 自动执行五阶段。
 *
 * 对应设计文档 V3 第 2.1 节、Task 6。
 */
class CreateTravelViewModel(
    application: Application,
    private val repository: TravelRepository,
    private val vlmSettingsRepository: VlmSettingsRepository,
    private val writingStyleRepository: WritingStyleRepository,
    private val workflowEngine: WorkflowEngine
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CreateUiState())
    val uiState: StateFlow<CreateUiState> = _uiState.asStateFlow()

    /** 全局自增序号，配合时间戳保证导入照片文件名唯一，避免并发/同毫秒覆盖。 */
    private val photoSeq = java.util.concurrent.atomic.AtomicLong(0)

    /** 风格列表（内置 + 自定义）。 */
    val styles: StateFlow<List<WritingStyleEntity>> = writingStyleRepository.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** VLM 设置，用于「开始生成」按钮的可点击性提示。 */
    val vlmSettings: StateFlow<VlmSettings> = vlmSettingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = VlmSettings()
        )

    /**
     * 批量添加照片（从图库多选）。所有 URI 在单个协程内顺序处理，
     * 避免 [addPhotoFromUri] 逐个启动协程导致的"读-改-写"竞态与同名文件覆盖。
     */
    fun addPhotosFromUris(uris: List<Uri>, context: android.content.Context) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val fileName = "import_${System.currentTimeMillis()}_${photoSeq.incrementAndGet()}.jpg"
                    val copiedFile = FileUtil.copyUriToInternal(context, uri, fileName)
                    if (copiedFile != null) {
                        addPhotoFileInternal(copiedFile)
                    }
                }
            }
        }
    }

    /**
     * 添加照片（从单个 Uri 导入，如外部单次调用）
     */
    fun addPhotoFromUri(uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val fileName = "import_${System.currentTimeMillis()}_${photoSeq.incrementAndGet()}.jpg"
                val copiedFile = FileUtil.copyUriToInternal(context, uri, fileName)
                if (copiedFile != null) {
                    addPhotoFileInternal(copiedFile)
                }
            }
        }
    }

    /**
     * 添加照片（拍照后）
     */
    fun addPhotoFromFile(file: File) {
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
            exifModel = metadata.model
        )
        val currentPhotos = _uiState.value.selectedPhotos.toMutableList()
        currentPhotos.add(photo)
        // 按时间排序
        currentPhotos.sortBy { it.takenAt ?: it.createdAt }
        _uiState.value = _uiState.value.copy(selectedPhotos = currentPhotos)
    }

    /**
     * 移除照片
     */
    fun removePhoto(photo: PhotoEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (photo.filePath.contains("/photos/")) {
                    FileUtil.deleteFile(photo.filePath)
                }
            }
            val currentPhotos = _uiState.value.selectedPhotos.toMutableList()
            currentPhotos.remove(photo)
            _uiState.value = _uiState.value.copy(selectedPhotos = currentPhotos)
        }
    }

    /**
     * 选中某个风格（单选）。
     */
    fun selectStyle(style: WritingStyleEntity) {
        _uiState.value = _uiState.value.copy(selectedStyleId = style.id)
    }

    /**
     * 启动前置校验：VLM + 地理编码是否均已配置。
     * 由 UI 调用，「开始生成」按钮按下时先校验，未通过则跳转设置引导。
     */
    suspend fun checkCanStart(): WorkflowStartCheck = workflowEngine.canStartWorkflow()

    /**
     * 保存草稿：仅持久化照片列表 + 风格选择到 workflow_task（status=pending），
     * 不触发任何后台调用。用户可在草稿页继续增删照片、换风格，反复保存无副作用。
     *
     * 对应设计 V3 第 2.1 节「保存草稿」。
     *
     * @return 创建/更新的 workflow_task id；-1 表示无有效内容
     */
    suspend fun saveDraft(): Long {
        val state = _uiState.value
        if (state.selectedPhotos.isEmpty()) return -1

        val photoPaths = state.selectedPhotos.map { it.filePath }
        val style = state.selectedStyleId?.let { id -> styles.value.firstOrNull { it.id == id } }
        // 通过 WorkflowEngine.start 创建 pending 任务但不启动工作流：复用 createTask 逻辑
        // 但 V3 的 start() 立即执行，因此这里走"先持久化草稿"路径——直接落库 workflow_task（pending）
        // 然后 UI 跳转详情页让用户继续点「开始生成」。
        return persistDraftTask(photoPaths, style)
    }

    /**
     * 启动工作流：校验通过后调用 WorkflowEngine.start，进入进度页。
     *
     * @return Pair<taskId, errorMessage>：成功时 taskId>0 且 errorMessage=null；
     *         失败时 taskId=-1 且 errorMessage 非空
     */
    suspend fun startWorkflow(): Pair<Long, String?> {
        val state = _uiState.value
        if (state.selectedPhotos.isEmpty()) {
            return Pair(-1L, "请先选择照片")
        }
        val check = workflowEngine.canStartWorkflow()
        if (!check.canStart) {
            val msg = buildString {
                append("配置未完成：")
                if (check.missingVlm) append(" VLM未配置;")
                if (check.missingGeo) append(" 地理编码服务未配置;")
            }
            return Pair(-1L, msg)
        }
        val photoPaths = state.selectedPhotos.map { it.filePath }
        val style = state.selectedStyleId?.let { id -> styles.value.firstOrNull { it.id == id } }
            ?: styles.value.firstOrNull { it.isBuiltin == 1 }
            ?: return Pair(-1L, "未选择风格且无内置风格可用")

        return try {
            // start 内部会先 require(canStart) 再 createTask + runLoop
            val taskId = workflowEngine.start(
                photoPaths = photoPaths,
                style = style,
                onProgress = { /* 进度由进度页 ViewModel 自己消费 */ }
            )
            Pair(taskId, null)
        } catch (e: IllegalArgumentException) {
            Pair(-1L, e.message ?: "启动失败")
        } catch (e: Exception) {
            Pair(-1L, e.message ?: "启动失败")
        }
    }

    /**
     * 落库 pending 草稿任务。仅持久化 inputPhotoPaths + selectedStyleId，不启动工作流。
     * 此处直接构造 WorkflowTaskEntity，避免调用 WorkflowEngine.start（会立即 runLoop）。
     */
    private suspend fun persistDraftTask(
        photoPaths: List<String>,
        style: WritingStyleEntity?
    ): Long {
        // 复用 engine 内部的 createTask 不可访问；直接通过 DAO 写入
        val app = getApplication<YouJiApplication>()
        val taskDao = app.database.workflowTaskDao()
        val task = cn.hllcloud.youji.data.entity.WorkflowTaskEntity(
            status = "pending",
            currentPhase = "prepare",
            currentPhaseIndex = 0,
            totalPhases = 5,
            geocodeDoneCount = 0,
            geocodeTotalCount = 0,
            selectedStyleId = style?.id,
            selectedStyleName = style?.name,
            inputPhotoPaths = org.json.JSONArray(photoPaths).toString(),
            createdNoteId = null,
            errorMessage = null
        )
        return withContext(Dispatchers.IO) {
            taskDao.insert(task)
        }
    }

    /**
     * UI 状态：仅照片列表 + 选中风格 id（无标题/正文/生成进度等）。
     */
    data class CreateUiState(
        val selectedPhotos: List<PhotoEntity> = emptyList(),
        val selectedStyleId: Long? = null
    )

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as YouJiApplication
            return CreateTravelViewModel(
                application,
                app.repository,
                app.vlmSettingsRepository,
                app.writingStyleRepository,
                app.workflowEngine
            ) as T
        }
    }
}
