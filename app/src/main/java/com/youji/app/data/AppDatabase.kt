package com.youji.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.youji.app.data.dao.PhotoDao
import com.youji.app.data.dao.TravelNoteDao
import com.youji.app.data.entity.PhotoEntity
import com.youji.app.data.entity.TravelNoteEntity

@Database(
    entities = [PhotoEntity::class, TravelNoteEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao
    abstract fun travelNoteDao(): TravelNoteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "youji_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
