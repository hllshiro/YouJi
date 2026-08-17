package com.youji.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "vlm_settings")

/**
 * VLM大模型配置数据存储
 */
class VlmSettingsRepository(private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("vlm_enabled")
        val API_URL = stringPreferencesKey("vlm_api_url")
        val API_KEY = stringPreferencesKey("vlm_api_key")
        val MODEL_NAME = stringPreferencesKey("vlm_model_name")
        val CUSTOM_PROMPT = stringPreferencesKey("vlm_custom_prompt")
    }

    val settings: Flow<VlmSettings> = context.dataStore.data.map { prefs ->
        VlmSettings(
            enabled = prefs[Keys.ENABLED] ?: false,
            apiUrl = prefs[Keys.API_URL] ?: "",
            apiKey = prefs[Keys.API_KEY] ?: "",
            modelName = prefs[Keys.MODEL_NAME] ?: "",
            customPrompt = prefs[Keys.CUSTOM_PROMPT] ?: DEFAULT_PROMPT
        )
    }

    suspend fun saveSettings(settings: VlmSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ENABLED] = settings.enabled
            prefs[Keys.API_URL] = settings.apiUrl
            prefs[Keys.API_KEY] = settings.apiKey
            prefs[Keys.MODEL_NAME] = settings.modelName
            prefs[Keys.CUSTOM_PROMPT] = settings.customPrompt.ifBlank { DEFAULT_PROMPT }
        }
    }

    companion object {
        const val DEFAULT_PROMPT = """请根据以下旅行照片和位置信息，生成一段优美的游记文字。
要求：
1. 文字风格文艺优美，适合分享
2. 结合照片内容和地理位置
3. 包含时间、地点、天气等细节（如有）
4. 字数在200-500字之间
5. 分段展示，结构清晰"""
    }
}

data class VlmSettings(
    val enabled: Boolean = false,
    val apiUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "",
    val customPrompt: String = VlmSettingsRepository.DEFAULT_PROMPT
)
