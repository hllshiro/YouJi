package cn.hllcloud.youji.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 写作风格实体
 * 内置"纪实""美化"两种风格，支持自定义风格管理。
 *
 * 对应设计文档 V3 第 3.1 节 writing_styles 表。
 */
@Entity(tableName = "writing_styles")
data class WritingStyleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,                                     // 风格名称
    val promptGuideline: String,                          // 风格提示词指导
    val openingTone: String? = null,                      // 开篇语气（可选）
    val closingTone: String? = null,                      // 结尾语气（可选）

    @ColumnInfo(defaultValue = "0")
    val isBuiltin: Int = 0,                               // 是否内置风格（0/1）

    val createdAt: Long = System.currentTimeMillis(),
)
