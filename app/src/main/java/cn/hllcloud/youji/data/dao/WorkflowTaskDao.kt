package cn.hllcloud.youji.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.hllcloud.youji.data.entity.WorkflowTaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * 工作流任务 DAO
 * 对应设计文档 V3 第 3.1 节 workflow_tasks 表。
 */
@Dao
interface WorkflowTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: WorkflowTaskEntity): Long

    @Update
    suspend fun update(task: WorkflowTaskEntity)

    @Query("SELECT * FROM workflow_tasks WHERE id = :id")
    fun getById(id: Long): Flow<WorkflowTaskEntity?>

    @Query("SELECT * FROM workflow_tasks WHERE id = :id")
    suspend fun getByIdOnce(id: Long): WorkflowTaskEntity?

    @Query("SELECT * FROM workflow_tasks ORDER BY createdAt DESC")
    fun getAll(): Flow<List<WorkflowTaskEntity>>

    @Query("SELECT * FROM workflow_tasks WHERE status = :status ORDER BY createdAt DESC")
    fun getByStatus(status: String): Flow<List<WorkflowTaskEntity>>

    /**
     * 启动时静默将未完成任务标记为 paused。
     * 对应设计 2.4 / 4.9 节 markUnfinishedTasksAsPaused()。
     */
    @Query("UPDATE workflow_tasks SET status = 'paused' WHERE status IN ('pending', 'running')")
    suspend fun markUnfinishedAsPaused()

    @Query("UPDATE workflow_tasks SET status = :status, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, errorMessage: String?)

    @Query("UPDATE workflow_tasks SET currentPhaseIndex = :index WHERE id = :id")
    suspend fun updatePhaseIndex(id: Long, index: Int)

    @Query("UPDATE workflow_tasks SET geocodeTotalCount = :count WHERE id = :id")
    suspend fun updateGeocodeTotal(id: Long, count: Int)

    @Query("UPDATE workflow_tasks SET geocodeDoneCount = geocodeDoneCount + 1 WHERE id = :id")
    suspend fun incrementGeocodeDone(id: Long)

    @Query("UPDATE workflow_tasks SET createdNoteId = :noteId WHERE id = :id")
    suspend fun updateCreatedNoteId(id: Long, noteId: Long)

    @Query("UPDATE workflow_tasks SET hasPendingEdit = :hasPendingEdit, lastDiffJson = :lastDiffJson WHERE id = :id")
    suspend fun updatePendingEdit(id: Long, hasPendingEdit: Int, lastDiffJson: String?)

    /**
     * 编辑照片后更新任务输入照片列表。
     * 对应设计 V3 第 5.1/5.2/5.3 节，三种场景都需要把新照片列表回写到 input_photo_paths。
     */
    @Query("UPDATE workflow_tasks SET inputPhotoPaths = :paths WHERE id = :id")
    suspend fun updateInputPhotoPaths(id: Long, paths: String)

    @Query("DELETE FROM workflow_tasks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
