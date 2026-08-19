package cn.hllcloud.youji.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appDataStore by preferencesDataStore(name = "app_preferences")

/**
 * 应用级别偏好设置仓储。
 *
 * 当前维护两类标记：
 * - [setupCompleted]：首次启动配置引导页状态记忆，对应设计文档 V3 第 2.5 节
 * - [amapKey]：高德 Web 服务 Key，对应设计文档 V3 第 2.6 节"设置页加地图服务区块"
 *
 * 启动时 [cn.hllcloud.youji.YouJiApplication] 通过 runBlocking 读取 [amapKey]
 * 一次性构建 [cn.hllcloud.youji.util.geocoder.GeocoderService] 回退链；
 * 用户在设置页修改后需重启 App 才能生效（高德服务会动态拼入回退链）。
 */
class AppPreferencesRepository(private val context: Context) {

    private object Keys {
        val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        val AMAP_KEY = stringPreferencesKey("amap_key")
    }

    /**
     * 是否已完成首次配置（VLM 测试 + 地理编码测试均通过）。
     *
     * 启动时由 MainActivity 的 Splash 路由订阅，决定进入首页还是引导页。
     */
    val setupCompleted: Flow<Boolean> = context.appDataStore.data.map { prefs ->
        prefs[Keys.SETUP_COMPLETED] ?: false
    }

    /**
     * 高德 Web 服务 Key（可选）。用户在设置页配置，用于增强地理编码准确度。
     * 空字符串表示未配置，回退到系统 Geocoder + Nominatim。
     */
    val amapKey: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[Keys.AMAP_KEY] ?: ""
    }

    /**
     * 标记完成配置。SetupWizardScreen 在两项测试均通过时调用。
     */
    suspend fun setSetupCompleted(value: Boolean) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.SETUP_COMPLETED] = value
        }
    }

    /**
     * 保存高德 Key。设置页"地图服务"区块调用，保存后需重启 App 才能生效。
     */
    suspend fun setAmapKey(key: String) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.AMAP_KEY] = key.trim()
        }
    }
}
