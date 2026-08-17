package com.youji.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.youji.app.data.entity.PhotoEntity
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
}
