package cn.hllcloud.youji.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.hllcloud.youji.YouJiApplication
import cn.hllcloud.youji.data.VlmSettings
import cn.hllcloud.youji.data.VlmSettingsRepository
import cn.hllcloud.youji.util.VlmClient
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

    // 模型列表拉取状态
    private val _modelsState = MutableStateFlow<ModelsState>(ModelsState.Idle)
    val modelsState: StateFlow<ModelsState> = _modelsState.asStateFlow()

    // 是否强制使用自定义输入（用户主动切换为输入框模式）
    private val _useCustomModelInput = MutableStateFlow(false)
    val useCustomModelInput: StateFlow<Boolean> = _useCustomModelInput.asStateFlow()

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
            // baseUrl 变化后清空已缓存的模型列表，UI 会根据新状态决定是否重新拉取
            _modelsState.value = ModelsState.Idle
            _useCustomModelInput.value = false
        }
    }

    fun updateApiKey(key: String) {
        viewModelScope.launch {
            val current = vlmSettings.value
            vlmSettingsRepository.saveSettings(current.copy(apiKey = key))
            _modelsState.value = ModelsState.Idle
            _useCustomModelInput.value = false
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
     * 拉取可用模型列表。成功后只更新 modelsState，不自动保存到 repository。
     * 自动选中第一个模型的逻辑由 UI 层通过 onModelChange 处理（仅更新 localSettings）。
     */
    fun fetchModels(settings: VlmSettings) {
        if (settings.apiUrl.isBlank() || settings.apiKey.isBlank()) {
            _modelsState.value = ModelsState.Error("请先填写 baseUrl 和 apiKey")
            return
        }
        _modelsState.value = ModelsState.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                vlmClient.fetchModels(settings)
            }
            _modelsState.value = result.fold(
                onSuccess = { models ->
                    ModelsState.Success(models)
                },
                onFailure = { ModelsState.Error(it.message ?: it.javaClass.simpleName) }
            )
        }
    }

    /**
     * 用户主动切换为自定义输入模式（即使后续拉取成功也保持输入框）。
     */
    fun switchToCustomModelInput() {
        _useCustomModelInput.value = true
    }

    /**
     * 从自定义输入模式切回下拉模式。
     */
    fun switchToDropdownModel() {
        _useCustomModelInput.value = false
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
                result.success && result.supportsVision -> {
                    VlmTestState.Success(result.message, result.latencyMs)
                }
                result.success && !result.supportsVision -> {
                    VlmTestState.NoVision(result.message)
                }
                else -> {
                    VlmTestState.Error(result.message)
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

    /**
     * 模型列表拉取状态
     */
    sealed class ModelsState {
        /** 初始/空闲 */
        object Idle : ModelsState()
        /** 拉取中 */
        object Loading : ModelsState()
        /** 拉取成功 */
        data class Success(val models: List<String>) : ModelsState()
        /** 拉取失败（用户可切换为自定义输入） */
        data class Error(val message: String) : ModelsState()
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
