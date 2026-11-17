package app.jacket.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jacket.mobile.data.ConnectionConfig
import app.jacket.mobile.data.JackettClient
import app.jacket.mobile.data.SecureSettings
import app.jacket.mobile.data.TorrentEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val secureSettings = SecureSettings(application)
    private val jackettClient = JackettClient()

    private val _config = MutableStateFlow(secureSettings.load())
    val config: StateFlow<ConnectionConfig> = _config.asStateFlow()

    private val _results = MutableStateFlow<List<TorrentEntry>>(emptyList())
    val results: StateFlow<List<TorrentEntry>> = _results.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _isError = MutableStateFlow(false)
    val isError: StateFlow<Boolean> = _isError.asStateFlow()

    fun saveSettings(serverUrl: String, apiKey: String) {
        val newConfig = ConnectionConfig(serverUrl.trim().trimEnd('/'), apiKey.trim())
        secureSettings.save(newConfig)
        _config.value = newConfig
        _message.value = "Настройки сохранены"
        _isError.value = false
    }

    fun testConnection(serverUrl: String, apiKey: String) {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _message.value = "Проверяем подключение…"
            _isError.value = false
            try {
                withContext(Dispatchers.IO) {
                    jackettClient.testConnection(serverUrl.trim().trimEnd('/'), apiKey.trim())
                }
                _message.value = "Подключение к Jackett успешно"
            } catch (error: Exception) {
                _message.value = error.message ?: "Не удалось подключиться к Jackett"
                _isError.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun search(query: String) {
        val cleanQuery = query.trim()
        val current = _config.value
        if (cleanQuery.isBlank()) {
            _message.value = "Введите название для поиска"
            _isError.value = true
            return
        }
        if (current.serverUrl.isBlank() || current.apiKey.isBlank()) {
            _message.value = "Сначала настройте подключение к Jackett"
            _isError.value = true
            return
        }
        if (_isLoading.value) return

        viewModelScope.launch {
            _isLoading.value = true
            _message.value = "Ищем на подключённых индексаторах…"
            _isError.value = false
            _results.value = emptyList()
            try {
                val found = withContext(Dispatchers.IO) {
                    jackettClient.search(current.serverUrl, current.apiKey, cleanQuery)
                }
                _results.value = found
                _message.value = if (found.isEmpty()) {
                    "Ничего не найдено. Проверьте индексаторы в Jackett."
                } else {
                    "Найдено результатов: ${found.size}"
                }
            } catch (error: Exception) {
                _message.value = error.message ?: "Ошибка поиска"
                _isError.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearMessage() {
        _message.value = null
        _isError.value = false
    }
}
