package com.youji.app.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.youji.app.YouJiApplication
import com.youji.app.data.TravelRepository
import com.youji.app.data.entity.PhotoEntity
import com.youji.app.data.entity.TravelNoteEntity
import com.youji.app.util.DateFormatUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 游记详情ViewModel
 */
class DetailViewModel(
    application: Application,
    private val repository: TravelRepository,
    private val noteId: Long
) : AndroidViewModel(application) {

    val note: Flow<TravelNoteEntity?> = repository.getTravelNoteById(noteId)
    val photos: Flow<List<PhotoEntity>> = repository.getPhotosByNoteId(noteId)

    val uiState: StateFlow<DetailUiState> = combine(note, photos) { n, p ->
        DetailUiState(
            note = n,
            photos = p,
            dateRangeText = DateFormatUtil.formatDateRange(n?.startDate, n?.endDate),
            daysCount = DateFormatUtil.daysBetween(n?.startDate, n?.endDate)
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DetailUiState()
    )

    fun deleteNote(onDeleted: () -> Unit) {
        viewModelScope.launch {
            // 删除相关图片文件
            val photoList = repository.getPhotosByNoteIdOnce(noteId)
            photoList.forEach { photo ->
                if (photo.filePath.contains("/photos/")) {
                    java.io.File(photo.filePath).delete()
                }
            }
            repository.deleteTravelNoteById(noteId)
            onDeleted()
        }
    }

    data class DetailUiState(
        val note: TravelNoteEntity? = null,
        val photos: List<PhotoEntity> = emptyList(),
        val dateRangeText: String = "",
        val daysCount: Int = 0
    )

    class Factory(
        private val application: Application,
        private val noteId: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as YouJiApplication
            return DetailViewModel(application, app.repository, noteId) as T
        }
    }
}
