package com.youji.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.youji.app.YouJiApplication
import com.youji.app.data.VlmSettings
import com.youji.app.data.VlmSettingsRepository
import com.youji.app.util.VlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页面ViewModel
 */
class SettingsViewModel(
    application: Application,
    private val vlmSettingsRepository: VlmSettingsRepository
) : AndroidViewModel(application) {

    private val vlmClient = VlmClient()

    val vlmSettings: StateFlow<VlmSettings> = vlmSettingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = VlmSettings()
        )

    private val _testState = MutableStateFlow<VlmTestState>(VlmTestState.Idle)
    val testState: StateFlow<VlmTestState> = _testState.asStateFlow()

    fun updateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = vlmSettings.value
            vlmSettingsRepository.saveSettings(current.copy(enabled = enabled))
        }
    }

    fun updateApiUrl(url: String) {
        viewModelScope.launch {
            val current = vlmSettings.value
            vlmSettingsRepository.saveSettings(current.copy(apiUrl = url))
        }
    }

    fun updateApiKey(key: String) {
        viewModelScope.launch {
            val current = vlmSettings.value
            vlmSettingsRepository.saveSettings(current.copy(apiKey = key))
        }
    }

    fun updateModelName(name: String) {
        viewModelScope.launch {
            val current = vlmSettings.value
            vlmSettingsRepository.saveSettings(current.copy(modelName = name))
        }
    }

    fun updateCustomPrompt(prompt: String) {
        viewModelScope.launch {
            val current = vlmSettings.value
            vlmSettingsRepository.saveSettings(current.copy(customPrompt = prompt))
        }
    }

    fun saveAll(settings: VlmSettings) {
        viewModelScope.launch {
            vlmSettingsRepository.saveSettings(settings)
        }
    }

    /**
     * 测试VLM API连通性并检测Vision能力。
     * 直接使用传入的配置（不必先保存），方便用户先测试再决定是否保存。
     */
    fun testVlmConnection(settings: VlmSettings) {
        if (settings.apiUrl.isBlank()) {
            _testState.value = VlmTestState.Error("API地址未配置，请先填写API地址")
            return
        }
        _testState.value = VlmTestState.Testing
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                vlmClient.testVisionCapability(settings)
            }
            _testState.value = when {
                !result.success && result.message.contains("不支持图像") -> {
                    VlmTestState.NoVision(result.message)
                }
                !result.success -> {
                    VlmTestState.Error(result.message)
                }
                else -> {
                    VlmTestState.Success(result.message, result.latencyMs)
                }
            }
        }
    }

    fun clearTestState() {
        _testState.value = VlmTestState.Idle
    }

    /**
     * VLM测试状态
     */
    sealed class VlmTestState {
        /** 初始/空闲 */
        object Idle : VlmTestState()
        /** 测试中 */
        object Testing : VlmTestState()
        /** 测试通过且支持Vision */
        data class Success(val message: String, val latencyMs: Long) : VlmTestState()
        /** API连通但不支持Vision */
        data class NoVision(val message: String) : VlmTestState()
        /** 连通失败或其它错误 */
        data class Error(val message: String) : VlmTestState()
    }

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as YouJiApplication
            return SettingsViewModel(application, app.vlmSettingsRepository) as T
        }
    }
}
