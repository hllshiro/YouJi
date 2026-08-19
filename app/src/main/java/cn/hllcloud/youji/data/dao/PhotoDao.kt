package cn.hllcloud.youji.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.hllcloud.youji.data.entity.PhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: PhotoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(photos: List<PhotoEntity>): List<Long>

    @Update
    suspend fun update(photo: PhotoEntity)

    @Delete
    suspend fun delete(photo: PhotoEntity)

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM photos WHERE travelNoteId = :travelNoteId")
    suspend fun deleteByTravelNoteId(travelNoteId: Long)

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun getById(id: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE travelNoteId = :travelNoteId ORDER BY takenAt ASC, id ASC")
    fun getByTravelNoteId(travelNoteId: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE travelNoteId = :travelNoteId ORDER BY takenAt ASC, id ASC")
    suspend fun getByTravelNoteIdOnce(travelNoteId: Long): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE travelNoteId IS NULL ORDER BY createdAt DESC")
    fun getUnassignedPhotos(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE takenAt BETWEEN :startTime AND :endTime ORDER BY takenAt ASC")
    fun getPhotosInDateRange(startTime: Long, endTime: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE takenAt BETWEEN :startTime AND :endTime ORDER BY takenAt ASC")
    suspend fun getPhotosInDateRangeOnce(startTime: Long, endTime: Long): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE workflowTaskId = :taskId")
    suspend fun getByWorkflowTaskId(taskId: Long): List<PhotoEntity>

    @Query("UPDATE photos SET locationName = :name WHERE id = :photoId")
    suspend fun updateLocationName(photoId: Long, name: String)

    @Query("UPDATE photos SET travelNoteId = :noteId, workflowTaskId = NULL WHERE workflowTaskId = :taskId")
    suspend fun rebindToNote(taskId: Long, noteId: Long)

    /**
     * 把单张照片关联到游记（用于增量更新时新增照片 rebind）。
     * 与 [rebindToNote] 区别：后者按 taskId 批量 rebind；本方法保留 workflowTaskId 不动，
     * 仅在场景三增量更新过程中使用——added 照片先 rebind 到 noteId 让用户可见，
     * 同时 workflowTaskId 继续保留以便审计。
     */
    @Query("UPDATE photos SET travelNoteId = :noteId WHERE id = :photoId")
    suspend fun updateTravelNoteId(photoId: Long, noteId: Long)

    @Query("DELETE FROM photos WHERE workflowTaskId = :taskId")
    suspend fun deleteByWorkflowTaskId(taskId: Long)
}
