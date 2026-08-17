package com.youji.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.youji.app.YouJiApplication
import com.youji.app.data.VlmSettings
import com.youji.app.data.VlmSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页面ViewModel
 */
class SettingsViewModel(
    application: Application,
    private val vlmSettingsRepository: VlmSettingsRepository
) : AndroidViewModel(application) {

    val vlmSettings: StateFlow<VlmSettings> = vlmSettingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = VlmSettings()
        )

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
