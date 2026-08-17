package cn.hllcloud.youji.data

import cn.hllcloud.youji.data.dao.PhotoDao
import cn.hllcloud.youji.data.dao.TravelNoteDao
import cn.hllcloud.youji.data.entity.PhotoEntity
import cn.hllcloud.youji.data.entity.TravelNoteEntity
import kotlinx.coroutines.flow.Flow

class TravelRepository(
    private val photoDao: PhotoDao,
    private val travelNoteDao: TravelNoteDao
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
}
