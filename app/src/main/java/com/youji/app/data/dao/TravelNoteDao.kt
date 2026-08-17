package com.youji.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.youji.app.data.entity.TravelNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TravelNoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: TravelNoteEntity): Long

    @Update
    suspend fun update(note: TravelNoteEntity)

    @Delete
    suspend fun delete(note: TravelNoteEntity)

    @Query("DELETE FROM travel_notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM travel_notes WHERE id = :id")
    fun getById(id: Long): Flow<TravelNoteEntity?>

    @Query("SELECT * FROM travel_notes WHERE id = :id")
    suspend fun getByIdOnce(id: Long): TravelNoteEntity?

    @Query("SELECT * FROM travel_notes ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<TravelNoteEntity>>

    @Query("SELECT * FROM travel_notes ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<TravelNoteEntity>

    @Query("""
        SELECT * FROM travel_notes 
        WHERE (:startDate IS NULL OR startDate >= :startDate)
        AND (:endDate IS NULL OR endDate <= :endDate)
        ORDER BY updatedAt DESC
    """)
    fun getByDateRange(startDate: Long?, endDate: Long?): Flow<List<TravelNoteEntity>>

    @Query("UPDATE travel_notes SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTimestamp(id: Long, updatedAt: Long)
}
