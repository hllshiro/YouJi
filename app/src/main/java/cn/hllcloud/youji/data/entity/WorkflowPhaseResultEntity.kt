package cn.hllcloud.youji.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 工作流阶段中间结果实体
 * 记录每个阶段的执行状态与产物 JSON，支持断点续传与防重复执行。
 *
 * 对应设计文档 V3 第 3.1 节 workflow_phase_results 表。
 * (taskId, phase) 唯一索引：同一任务的同一阶段仅保留一条记录，便于 upsert 与断点恢复定位。
 */
@Entity(
    tableName = "workflow_phase_results",
    indices = [Index(value = ["taskId", "phase"], unique = true)]
)
data class WorkflowPhaseResultEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val taskId: Long,
    val phase: String,                                    // 'prepare' | 'geocode' | 'local_gen' | 'vlm_gen' | 'save'

    @ColumnInfo(defaultValue = "pending")
    val status: String = "pending",                      // 'pending' | 'running' | 'completed' | 'failed'

    val resultJson: String? = null,                       // 阶段产物 JSON（见设计 3.3 节）
    val errorMessage: String? = null,

    @ColumnInfo(defaultValue = "1")
    val phaseVersion: Int = 1,                            // 每次编辑后递增，追踪结果版本

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
