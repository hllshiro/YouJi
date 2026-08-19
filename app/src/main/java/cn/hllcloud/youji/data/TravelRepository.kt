package cn.hllcloud.youji.data

import cn.hllcloud.youji.data.dao.PhotoDao
import cn.hllcloud.youji.data.dao.TravelNoteDao
import cn.hllcloud.youji.data.dao.WorkflowTaskDao
import cn.hllcloud.youji.data.entity.PhotoEntity
import cn.hllcloud.youji.data.entity.TravelNoteEntity
import cn.hllcloud.youji.data.entity.WorkflowTaskEntity
import kotlinx.coroutines.flow.Flow

class TravelRepository(
    private val photoDao: PhotoDao,
    private val travelNoteDao: TravelNoteDao,
    private val workflowTaskDao: WorkflowTaskDao? = null
) {

    // ---- Photo 相关 ----

    suspend fun insertPhoto(photo: PhotoEntity): Long {
        return photoDao.insert(photo)
    }

    suspend fun insertPhotos(photos: List<PhotoEntity>): List<Long> {
        return photoDao.insertAll(photos)
    }

    suspend fun updatePhoto(photo: PhotoEntity) {
        photoDao.update(photo)
    }

    suspend fun deletePhoto(photo: PhotoEntity) {
        photoDao.delete(photo)
    }

    suspend fun deletePhotosByNoteId(noteId: Long) {
        photoDao.deleteByTravelNoteId(noteId)
    }

    fun getPhotosByNoteId(noteId: Long): Flow<List<PhotoEntity>> {
        return photoDao.getByTravelNoteId(noteId)
    }

    suspend fun getPhotosByNoteIdOnce(noteId: Long): List<PhotoEntity> {
        return photoDao.getByTravelNoteIdOnce(noteId)
    }

    fun getUnassignedPhotos(): Flow<List<PhotoEntity>> {
        return photoDao.getUnassignedPhotos()
    }

    fun getPhotosInDateRange(startTime: Long, endTime: Long): Flow<List<PhotoEntity>> {
        return photoDao.getPhotosInDateRange(startTime, endTime)
    }

    suspend fun getPhotosInDateRangeOnce(startTime: Long, endTime: Long): List<PhotoEntity> {
        return photoDao.getPhotosInDateRangeOnce(startTime, endTime)
    }

    // ---- TravelNote 相关 ----

    suspend fun insertTravelNote(note: TravelNoteEntity): Long {
        return travelNoteDao.insert(note)
    }

    suspend fun updateTravelNote(note: TravelNoteEntity) {
        val updatedNote = note.copy(updatedAt = System.currentTimeMillis())
        travelNoteDao.update(updatedNote)
    }

    suspend fun deleteTravelNoteById(id: Long) {
        deletePhotosByNoteId(id)
        travelNoteDao.deleteById(id)
    }

    fun getTravelNoteById(id: Long): Flow<TravelNoteEntity?> {
        return travelNoteDao.getById(id)
    }

    suspend fun getTravelNoteByIdOnce(id: Long): TravelNoteEntity? {
        return travelNoteDao.getByIdOnce(id)
    }

    /**
     * 增量更新游记正文（不修改其他字段）。对应设计 V3 第 5.3 节场景三：
     * added 照片由 VLM 生成新内容后写入 note.content，
     * removed 照片由本地正则替换清除对应段落后写入。
     */
    suspend fun updateTravelNoteContent(id: Long, content: String) {
        travelNoteDao.updateContent(id, content, System.currentTimeMillis())
    }

    fun getAllTravelNotes(): Flow<List<TravelNoteEntity>> {
        return travelNoteDao.getAll()
    }

    suspend fun getAllTravelNotesOnce(): List<TravelNoteEntity> {
        return travelNoteDao.getAllOnce()
    }

    fun getTravelNotesByDateRange(startDate: Long?, endDate: Long?): Flow<List<TravelNoteEntity>> {
        return travelNoteDao.getByDateRange(startDate, endDate)
    }

    /**
     * 创建游记并关联照片
     */
    suspend fun createTravelNoteWithPhotos(
        note: TravelNoteEntity,
        photos: List<PhotoEntity>
    ): Long {
        val noteId = travelNoteDao.insert(note)
        if (photos.isNotEmpty()) {
            val updatedPhotos = photos.map { it.copy(travelNoteId = noteId) }
            photoDao.insertAll(updatedPhotos)
        }
        return noteId
    }

    // ---- WorkflowTask 相关 ----

    /**
     * 获取所有 paused 状态的工作流任务（待恢复）。
     *
     * 用于首页"待恢复任务"区块订阅，对应设计 V3 第 2.4 节。
     * 若 Repository 未注入 [WorkflowTaskDao]（旧调用方），返回空流以保证向后兼容。
     */
    fun getPausedWorkflowTasks(): Flow<List<WorkflowTaskEntity>> {
        val dao = workflowTaskDao ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return dao.getByStatus("paused")
    }

    /**
     * 获取所有 failed 状态的工作流任务。
     *
     * 用于首页"待恢复任务"区块展示失败任务的错误原因摘要，对应
     * DESIGN_V3 失败信息显示要求。若 Repository 未注入 [WorkflowTaskDao]，
     * 返回空流以保证向后兼容。
     */
    fun getFailedWorkflowTasks(): Flow<List<WorkflowTaskEntity>> {
        val dao = workflowTaskDao ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return dao.getByStatus("failed")
    }
}
