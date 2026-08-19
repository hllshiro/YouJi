package cn.hllcloud.youji.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.hllcloud.youji.data.entity.WorkflowPhaseResultEntity
import kotlinx.coroutines.flow.Flow

/**
 * 工作流阶段中间结果 DAO
 * 对应设计文档 V3 第 3.1 节 workflow_phase_results 表、第 3.6 节防重复执行逻辑。
 */
@Dao
interface WorkflowPhaseResultDao {

    @Query("SELECT * FROM workflow_phase_results WHERE taskId = :taskId AND phase = :phase LIMIT 1")
    suspend fun getByTaskAndPhase(taskId: Long, phase: String): WorkflowPhaseResultEntity?

    @Query("SELECT * FROM workflow_phase_results WHERE taskId = :taskId ORDER BY id ASC")
    fun getByTaskId(taskId: Long): Flow<List<WorkflowPhaseResultEntity>>

    /**
     * 插入或替换：依赖 (taskId, phase) 唯一索引实现 upsert。
     * 注意：REPLACE 策略在主键冲突或唯一约束冲突时都会触发替换，用于阶段结果更新。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(result: WorkflowPhaseResultEntity): Long

    @Query("DELETE FROM workflow_phase_results WHERE taskId = :taskId")
    suspend fun deleteByTaskId(taskId: Long)

    /**
     * 删除指定任务的指定阶段结果。对应设计 V3 第 5.2 节：
     * 执行中编辑照片后，把 local_gen / vlm_gen / save 阶段结果清空，
     * 让下次 resume 时重新执行（防重复执行的 COMPLETED 判定会因此失败）。
     */
    @Query("DELETE FROM workflow_phase_results WHERE taskId = :taskId AND phase = :phase")
    suspend fun deleteByTaskAndPhase(taskId: Long, phase: String)
}
