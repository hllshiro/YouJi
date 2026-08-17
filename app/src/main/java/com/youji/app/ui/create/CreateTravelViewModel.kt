package com.youji.app.ui.create

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.youji.app.YouJiApplication
import com.youji.app.data.TravelRepository
import com.youji.app.data.VlmSettings
import com.youji.app.data.VlmSettingsRepository
import com.youji.app.data.entity.PhotoEntity
import com.youji.app.data.entity.TravelNoteEntity
import com.youji.app.util.DateFormatUtil
import com.youji.app.util.ExifUtil
import com.youji.app.util.FileUtil
import com.youji.app.util.GeneratedContent
import com.youji.app.util.LocalContentGenerator
import com.youji.app.util.VlmClient
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
 * 创建游记ViewModel
 */
class CreateTravelViewModel(
    application: Application,
    private val repository: TravelRepository,
    private val vlmSettingsRepository: VlmSettingsRepository
) : AndroidViewModel(application) {

    private val vlmClient = VlmClient()

    private val _uiState = MutableStateFlow(CreateUiState())
    val uiState: StateFlow<CreateUiState> = _uiState.asStateFlow()

    val vlmSettings: StateFlow<VlmSettings> = vlmSettingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = VlmSettings()
        )

    /**
     * 添加照片（从Uri导入）
     */
    fun addPhotoFromUri(uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 复制文件到内部存储
                val fileName = "import_${System.currentTimeMillis()}.jpg"
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

        // 自动更新日期范围
        updateDateRangeFromPhotos(currentPhotos)
    }

    /**
     * 移除照片
     */
    fun removePhoto(photo: PhotoEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 如果文件在photos目录，删除本地文件
                if (photo.filePath.contains("/photos/")) {
                    FileUtil.deleteFile(photo.filePath)
                }
            }
            val currentPhotos = _uiState.value.selectedPhotos.toMutableList()
            currentPhotos.remove(photo)
            _uiState.value = _uiState.value.copy(selectedPhotos = currentPhotos)
            updateDateRangeFromPhotos(currentPhotos)
        }
    }

    private fun updateDateRangeFromPhotos(photos: List<PhotoEntity>) {
        val times = photos.mapNotNull { it.takenAt }.sorted()
        if (times.isNotEmpty()) {
            val start = DateFormatUtil.getStartOfDay(times.first())
            val end = DateFormatUtil.getEndOfDay(times.last())
            _uiState.value = _uiState.value.copy(
                startDate = start,
                endDate = end
            )
        }
    }

    /**
     * 更新标题
     */
    fun updateTitle(title: String) {
        _uiState.value = _uiState.value.copy(title = title)
    }

    /**
     * 更新内容
     */
    fun updateContent(content: String) {
        _uiState.value = _uiState.value.copy(content = content)
    }

    /**
     * 更新开始日期
     */
    fun updateStartDate(timestamp: Long) {
        _uiState.value = _uiState.value.copy(startDate = DateFormatUtil.getStartOfDay(timestamp))
    }

    /**
     * 更新结束日期
     */
    fun updateEndDate(timestamp: Long) {
        _uiState.value = _uiState.value.copy(endDate = DateFormatUtil.getEndOfDay(timestamp))
    }

    /**
     * 设置编辑模式（加载已有游记）
     */
    fun setEditMode(noteId: Long) {
        viewModelScope.launch {
            val note = repository.getTravelNoteByIdOnce(noteId)
            val photos = repository.getPhotosByNoteIdOnce(noteId)
            if (note != null) {
                _uiState.value = CreateUiState(
                    editNoteId = noteId,
                    title = note.title,
                    content = note.content,
                    startDate = note.startDate,
                    endDate = note.endDate,
                    selectedPhotos = photos,
                    isGeneratedByVlm = note.isGeneratedByVlm
                )
            }
        }
    }

    /**
     * 使用本地算法生成内容
     */
    fun generateLocalContent() {
        val photos = _uiState.value.selectedPhotos
        if (photos.isEmpty()) return

        val generated = LocalContentGenerator.generateContent(photos, _uiState.value.title)
        applyGeneratedContent(generated, useVlm = false)
    }

    /**
     * 使用VLM生成内容
     */
    fun generateVlmContent() {
        val photos = _uiState.value.selectedPhotos
        if (photos.isEmpty()) return

        _uiState.value = _uiState.value.copy(isGenerating = true, generateError = null)

        viewModelScope.launch {
            val settings = vlmSettings.value
            val result = withContext(Dispatchers.IO) {
                vlmClient.generateTravelContent(settings, photos)
            }
            result.onSuccess { vlmContent ->
                // 先用本地算法获取标题、日期等元信息
                val base = LocalContentGenerator.generateContent(photos, _uiState.value.title)
                val generated = GeneratedContent(
                    title = base.title,
                    content = vlmContent,
                    locationSummary = base.locationSummary,
                    startDate = base.startDate,
                    endDate = base.endDate,
                    coverPhotoPath = base.coverPhotoPath
                )
                applyGeneratedContent(generated, useVlm = true)
                _uiState.value = _uiState.value.copy(isGenerating = false)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    generateError = error.message ?: "生成失败"
                )
            }
        }
    }

    private fun applyGeneratedContent(generated: GeneratedContent, useVlm: Boolean) {
        _uiState.value = _uiState.value.copy(
            title = generated.title,
            content = generated.content,
            locationSummary = generated.locationSummary,
            startDate = generated.startDate ?: _uiState.value.startDate,
            endDate = generated.endDate ?: _uiState.value.endDate,
            coverPhotoPath = generated.coverPhotoPath,
            isGeneratedByVlm = useVlm
        )
    }

    /**
     * 清除生成错误
     */
    fun clearGenerateError() {
        _uiState.value = _uiState.value.copy(generateError = null)
    }

    /**
     * 保存游记
     */
    suspend fun save(): Long? {
        val state = _uiState.value
        if (state.title.isBlank() && state.content.isBlank() && state.selectedPhotos.isEmpty()) {
            return null
        }

        val coverPath = state.coverPhotoPath
            ?: state.selectedPhotos.firstOrNull()?.filePath

        val noteEntity = TravelNoteEntity(
            id = state.editNoteId ?: 0,
            title = state.title.ifBlank { "未命名游记" },
            content = state.content,
            coverPhotoPath = coverPath,
            startDate = state.startDate,
            endDate = state.endDate,
            locationSummary = state.locationSummary,
            isGeneratedByVlm = state.isGeneratedByVlm,
            createdAt = if (state.editNoteId != null) {
                repository.getTravelNoteByIdOnce(state.editNoteId)?.createdAt ?: System.currentTimeMillis()
            } else {
                System.currentTimeMillis()
            }
        )

        return withContext(Dispatchers.IO) {
            if (state.editNoteId != null) {
                // 更新模式
                repository.updateTravelNote(noteEntity)
                // 先移除旧的照片关联
                repository.deletePhotosByNoteId(state.editNoteId)
                // 重新关联照片
                val photosWithId = state.selectedPhotos.map { it.copy(travelNoteId = state.editNoteId) }
                repository.insertPhotos(photosWithId)
                state.editNoteId
            } else {
                // 创建模式
                repository.createTravelNoteWithPhotos(noteEntity, state.selectedPhotos)
            }
        }
    }

    data class CreateUiState(
        val editNoteId: Long? = null,
        val title: String = "",
        val content: String = "",
        val startDate: Long? = null,
        val endDate: Long? = null,
        val selectedPhotos: List<PhotoEntity> = emptyList(),
        val coverPhotoPath: String? = null,
        val locationSummary: String? = null,
        val isGenerating: Boolean = false,
        val generateError: String? = null,
        val isGeneratedByVlm: Boolean = false
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
                app.vlmSettingsRepository
            ) as T
        }
    }
}
