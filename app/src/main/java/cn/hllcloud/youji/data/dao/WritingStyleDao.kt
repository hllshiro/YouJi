package cn.hllcloud.youji.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.hllcloud.youji.data.entity.WritingStyleEntity
import kotlinx.coroutines.flow.Flow

/**
 * 写作风格 DAO
 * 对应设计文档 V3 第 3.1 节 writing_styles 表。
 * 内置"纪实""美化"风格由 AppDatabase.onCreate 在首次建库时注入。
 */
@Dao
interface WritingStyleDao {

    @Query("SELECT * FROM writing_styles ORDER BY isBuiltin DESC, createdAt ASC")
    fun getAll(): Flow<List<WritingStyleEntity>>

    @Query("SELECT * FROM writing_styles WHERE id = :id")
    suspend fun getById(id: Long): WritingStyleEntity?

    @Query("SELECT * FROM writing_styles WHERE isBuiltin = 1 ORDER BY id ASC")
    fun getBuiltin(): Flow<List<WritingStyleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(style: WritingStyleEntity): Long

    @Update
    suspend fun update(style: WritingStyleEntity)

    @Delete
    suspend fun delete(style: WritingStyleEntity)
}
