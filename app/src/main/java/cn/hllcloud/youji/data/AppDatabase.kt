package cn.hllcloud.youji.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import cn.hllcloud.youji.data.dao.PhotoDao
import cn.hllcloud.youji.data.dao.TravelNoteDao
import cn.hllcloud.youji.data.entity.PhotoEntity
import cn.hllcloud.youji.data.entity.TravelNoteEntity

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
