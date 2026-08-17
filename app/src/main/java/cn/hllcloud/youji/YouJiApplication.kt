package cn.hllcloud.youji

import android.app.Application
import cn.hllcloud.youji.data.AppDatabase
import cn.hllcloud.youji.data.TravelRepository
import cn.hllcloud.youji.data.VlmSettingsRepository

/**
 * 全局Application类
 */
class YouJiApplication : Application() {

    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy {
        TravelRepository(database.photoDao(), database.travelNoteDao())
    }
    val vlmSettingsRepository by lazy { VlmSettingsRepository(this) }

    companion object {
        private var INSTANCE: YouJiApplication? = null
        fun get(): YouJiApplication = INSTANCE
            ?: throw IllegalStateException("Application not created")
    }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
    }
}
