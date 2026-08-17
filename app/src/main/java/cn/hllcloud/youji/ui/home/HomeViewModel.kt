package cn.hllcloud.youji.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.hllcloud.youji.YouJiApplication
import cn.hllcloud.youji.data.TravelRepository
import cn.hllcloud.youji.data.entity.TravelNoteEntity
import cn.hllcloud.youji.util.DateFormatUtil
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页ViewModel
 */
class HomeViewModel(
    application: Application,
    private val repository: TravelRepository
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

    fun deleteNote(noteId: Long) {
        viewModelScope.launch {
            repository.deleteTravelNoteById(noteId)
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

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as YouJiApplication
            return HomeViewModel(application, app.repository) as T
        }
    }
}
