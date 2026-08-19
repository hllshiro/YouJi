package cn.hllcloud.youji.ui.style

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.hllcloud.youji.YouJiApplication
import cn.hllcloud.youji.data.WritingStyleRepository
import cn.hllcloud.youji.data.entity.WritingStyleEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 写作风格管理页 ViewModel。
 *
 * 对应设计文档 V3 第 2.1 节"风格选择行 +管理"链接的目标页面。
 *
 * 暴露风格列表 [styles]，提供新增 / 修改 / 删除自定义风格的方法。
 * 内置风格（纪实 / 美化）可编辑 promptGuideline/openingTone/closingTone，
 * 但 [delete] 在 Repository 层防御性拒绝删除内置项。
 */
class StyleManagerViewModel(
    application: Application,
    private val writingStyleRepository: WritingStyleRepository
) : AndroidViewModel(application) {

    /**
     * 全量监听风格列表（内置优先 + 创建时间升序）。
     */
    val styles: StateFlow<List<WritingStyleEntity>> = writingStyleRepository.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * 创建自定义风格。Repository 强制 isBuiltin=0。
     *
     * 调用方在 UI 层做基础校验（名称/提示词非空），调用后通过 [styles] Flow
     * 观察列表更新即可看到新增项。方法本身 fire-and-forget，无返回值。
     *
     * @param name 风格名
     * @param promptGuideline 提示词指导
     * @param openingTone 开篇语气（可空）
     * @param closingTone 结尾语气（可空）
     */
    fun createStyle(
        name: String,
        promptGuideline: String,
        openingTone: String?,
        closingTone: String?
    ) {
        if (name.isBlank() || promptGuideline.isBlank()) return
        viewModelScope.launch {
            val entity = WritingStyleEntity(
                name = name.trim(),
                promptGuideline = promptGuideline.trim(),
                openingTone = openingTone?.trim()?.takeIf { it.isNotBlank() },
                closingTone = closingTone?.trim()?.takeIf { it.isNotBlank() },
                isBuiltin = 0
            )
            writingStyleRepository.create(entity)
        }
    }

    /**
     * 更新风格。内置风格保留原 isBuiltin=1，自定义保留 isBuiltin=0。
     */
    fun updateStyle(style: WritingStyleEntity) {
        if (style.name.isBlank() || style.promptGuideline.isBlank()) return
        viewModelScope.launch {
            writingStyleRepository.update(style)
        }
    }

    /**
     * 删除风格。内置风格在 Repository 层被拒绝。
     */
    fun deleteStyle(style: WritingStyleEntity) {
        if (style.isBuiltin == 1) return
        viewModelScope.launch {
            writingStyleRepository.delete(style)
        }
    }

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as YouJiApplication
            return StyleManagerViewModel(application, app.writingStyleRepository) as T
        }
    }
}
