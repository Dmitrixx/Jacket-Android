package app.jacket.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jacket.mobile.data.CatalogItem
import app.jacket.mobile.data.IndexerConfig
import app.jacket.mobile.data.IndexerRegistry
import app.jacket.mobile.data.MediaCatalogClient
import app.jacket.mobile.data.MediaDetails
import app.jacket.mobile.data.MediaSection
import app.jacket.mobile.data.MediaSettingsStore
import app.jacket.mobile.data.SourceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI state and orchestration for the self-contained catalog/indexer app. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = MediaSettingsStore(application)
    private val catalog = MediaCatalogClient()
    private val indexers = IndexerRegistry(catalog)
    private var catalogJob: Job? = null
    private var sourcesJob: Job? = null

    private val _tmdbToken = MutableStateFlow(settings.tmdbToken())
    val tmdbToken: StateFlow<String> = _tmdbToken.asStateFlow()

    private val _customIndexers = MutableStateFlow(settings.indexers())
    val customIndexers: StateFlow<List<IndexerConfig>> = _customIndexers.asStateFlow()

    private val _homeSections = MutableStateFlow<List<MediaSection>>(emptyList())
    val homeSections: StateFlow<List<MediaSection>> = _homeSections.asStateFlow()

    private val _items = MutableStateFlow<List<CatalogItem>>(emptyList())
    val items: StateFlow<List<CatalogItem>> = _items.asStateFlow()

    private val _favorites = MutableStateFlow(settings.favorites())
    val favorites: StateFlow<List<CatalogItem>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow(settings.history())
    val history: StateFlow<List<CatalogItem>> = _history.asStateFlow()

    private val _selectedDetails = MutableStateFlow<MediaDetails?>(null)
    val selectedDetails: StateFlow<MediaDetails?> = _selectedDetails.asStateFlow()

    private val _sourceResults = MutableStateFlow<List<SourceResult>>(emptyList())
    val sourceResults: StateFlow<List<SourceResult>> = _sourceResults.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSearchingSources = MutableStateFlow(false)
    val isSearchingSources: StateFlow<Boolean> = _isSearchingSources.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _isError = MutableStateFlow(false)
    val isError: StateFlow<Boolean> = _isError.asStateFlow()

    fun loadHome(force: Boolean = false) {
        if (!force && _homeSections.value.isNotEmpty()) return
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            _isLoading.value = true
            clearMessage()
            try {
                val sections = withContext(Dispatchers.IO) {
                    catalog.loadHome(_tmdbToken.value)
                }
                _homeSections.value = sections
                _message.value = if (sections.isEmpty()) "Каталог пока пуст. Проверьте подключение к интернету." else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message ?: "Не удалось загрузить главную страницу")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadMovies() = loadList { catalog.movies(_tmdbToken.value) }
    fun loadSeries() = loadList { catalog.series(_tmdbToken.value) }

    fun search(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) {
            _items.value = emptyList()
            _message.value = "Введите название фильма или сериала"
            _isError.value = true
            return
        }
        loadList { catalog.search(clean, _tmdbToken.value) }
    }

    fun openItem(item: CatalogItem) {
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            _isLoading.value = true
            _selectedDetails.value = MediaDetails(item = item, description = item.overview, pageUrl = item.catalogUrl)
            _sourceResults.value = emptyList()
            clearMessage()
            try {
                val details = withContext(Dispatchers.IO) {
                    catalog.tmdbDetails(item, _tmdbToken.value)
                }
                _selectedDetails.value = details
                settings.addToHistory(details.item)
                _history.value = settings.history()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _selectedDetails.value = app.jacket.mobile.data.MediaDetails(
                    item = item,
                    description = item.overview,
                    pageUrl = item.catalogUrl
                )
                showError(error.message ?: "Не удалось загрузить подробности")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun closeDetails() {
        catalogJob?.cancel()
        _selectedDetails.value = null
        _sourceResults.value = emptyList()
    }

    fun findSources(item: CatalogItem) {
        sourcesJob?.cancel()
        sourcesJob = viewModelScope.launch {
            _isSearchingSources.value = true
            _sourceResults.value = emptyList()
            clearMessage()
            try {
                val found = withContext(Dispatchers.IO) {
                    indexers.search(item.title, _customIndexers.value)
                }
                _sourceResults.value = found
                _message.value = if (found.isEmpty()) {
                    "Источники не найдены. Можно добавить собственные RSS/Atom-индексаторы в настройках."
                } else {
                    "Найдено источников: ${found.size}"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message ?: "Ошибка поиска источников")
            } finally {
                _isSearchingSources.value = false
            }
        }
    }

    fun toggleFavorite(item: CatalogItem) {
        val current = _favorites.value
        val updated = if (current.any { it.id == item.id }) {
            current.filterNot { it.id == item.id }
        } else {
            (listOf(item) + current).take(MAX_FAVORITES)
        }
        settings.saveFavorites(updated)
        _favorites.value = updated
        _message.value = if (updated.any { it.id == item.id }) "Добавлено в избранное" else "Удалено из избранного"
        _isError.value = false
    }

    fun isFavorite(item: CatalogItem): Boolean = _favorites.value.any { it.id == item.id }

    fun saveTmdbToken(token: String) {
        settings.saveTmdbToken(token)
        _tmdbToken.value = settings.tmdbToken()
        _homeSections.value = emptyList()
        _items.value = emptyList()
        _message.value = if (_tmdbToken.value.isBlank()) {
            "Используется открытый каталог Internet Archive"
        } else {
            "TMDB подключён. Каталог будет обновлён."
        }
        _isError.value = false
        loadHome(force = true)
    }

    fun addIndexer(name: String, urlTemplate: String) {
        try {
            settings.addIndexer(name, urlTemplate)
            _customIndexers.value = settings.indexers()
            _message.value = "RSS/Atom-источник добавлен"
            _isError.value = false
        } catch (error: Exception) {
            showError(error.message ?: "Не удалось добавить источник")
        }
    }

    fun removeIndexer(id: String) {
        settings.removeIndexer(id)
        _customIndexers.value = settings.indexers()
        _message.value = "Источник удалён"
        _isError.value = false
    }

    fun refreshFavoritesAndHistory() {
        _favorites.value = settings.favorites()
        _history.value = settings.history()
    }

    private fun loadList(block: () -> List<CatalogItem>) {
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            _isLoading.value = true
            _items.value = emptyList()
            clearMessage()
            try {
                _items.value = withContext(Dispatchers.IO) { block() }
                if (_items.value.isEmpty()) _message.value = "Ничего не найдено"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message ?: "Не удалось загрузить каталог")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun clearMessage() {
        _message.value = null
        _isError.value = false
    }

    private fun showError(message: String) {
        _message.value = message
        _isError.value = true
    }

    private companion object {
        const val MAX_FAVORITES = 200
    }
}
