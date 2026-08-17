package cn.hllcloud.youji.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 游记实体
 */
@Entity(tableName = "travel_notes")
data class TravelNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val coverPhotoPath: String? = null,
    val startDate: Long? = null, // 行程开始日期
    val endDate: Long? = null, // 行程结束日期
    val locationSummary: String? = null, // 位置摘要
    val isGeneratedByVlm: Boolean = false, // 是否由VLM生成
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
