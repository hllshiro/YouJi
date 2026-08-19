package cn.hllcloud.youji.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 工作流任务实体
 * 记录一次游记生成任务的完整生命周期（创建 / 运行 / 暂停 / 完成 / 失败）。
 *
 * 对应设计文档 V3 第 3.1 节 workflow_tasks 表。
 */
@Entity(tableName = "workflow_tasks")
data class WorkflowTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(defaultValue = "pending")
    val status: String = "pending",                       // 'pending' | 'running' | 'paused' | 'completed' | 'failed'

    @ColumnInfo(defaultValue = "prepare")
    val currentPhase: String = "prepare",                // 'prepare' | 'geocode' | 'local_gen' | 'vlm_gen' | 'save'

    @ColumnInfo(defaultValue = "0")
    val currentPhaseIndex: Int = 0,                       // 当前阶段序号（0-based），用于 (N/M) 展示

    @ColumnInfo(defaultValue = "5")
    val totalPhases: Int = 5,                             // 总阶段数，默认 5

    @ColumnInfo(defaultValue = "0")
    val geocodeDoneCount: Int = 0,                        // Geocode 阶段已反查数（额外细粒度）

    @ColumnInfo(defaultValue = "0")
    val geocodeTotalCount: Int = 0,                       // Geocode 阶段总照片数

    val selectedStyleId: Long? = null,                    // 选中的风格 ID
    val selectedStyleName: String? = null,                // 冗余存储风格名
    val inputPhotoPaths: String,                          // JSON: ["path1", "path2", ...]
    val createdNoteId: Long? = null,                      // 若已保存则关联游记 ID
    val errorMessage: String? = null,                     // 失败原因

    @ColumnInfo(defaultValue = "0")
    val hasPendingEdit: Int = 0,                          // 是否有未应用的编辑（0/1）

    val lastDiffJson: String? = null,                     // 上次编辑 diff（added/removed id 列表）

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
