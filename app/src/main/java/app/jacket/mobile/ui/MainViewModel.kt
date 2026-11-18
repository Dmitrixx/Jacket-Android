package app.jacket.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jacket.mobile.data.CatalogItem
import app.jacket.mobile.data.IndexerConfig
import app.jacket.mobile.data.IndexerFailure
import app.jacket.mobile.data.IndexerRegistry
import app.jacket.mobile.data.MediaCatalogClient
import app.jacket.mobile.data.MediaDetails
import app.jacket.mobile.data.MediaSection
import app.jacket.mobile.data.MediaSeason
import app.jacket.mobile.data.MediaEpisode
import app.jacket.mobile.data.MediaSettingsStore
import app.jacket.mobile.data.WebSearchProvider
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
    private var indexerTestJob: Job? = null
    private var episodeJob: Job? = null

    private val _tmdbToken = MutableStateFlow(settings.tmdbToken())
    val tmdbToken: StateFlow<String> = _tmdbToken.asStateFlow()

    private val _customIndexers = MutableStateFlow(settings.indexers())
    val customIndexers: StateFlow<List<IndexerConfig>> = _customIndexers.asStateFlow()

    private val _webSearchProviders = MutableStateFlow(settings.webSearchProviders())
    val webSearchProviders: StateFlow<List<WebSearchProvider>> = _webSearchProviders.asStateFlow()

    private val _indexerStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val indexerStatuses: StateFlow<Map<String, String>> = _indexerStatuses.asStateFlow()

    private val _indexerErrors = MutableStateFlow<List<IndexerFailure>>(emptyList())
    val indexerErrors: StateFlow<List<IndexerFailure>> = _indexerErrors.asStateFlow()

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

    private val _seasons = MutableStateFlow<List<MediaSeason>>(emptyList())
    val seasons: StateFlow<List<MediaSeason>> = _seasons.asStateFlow()

    private val _episodes = MutableStateFlow<List<MediaEpisode>>(emptyList())
    val episodes: StateFlow<List<MediaEpisode>> = _episodes.asStateFlow()

    private val _selectedSeasonNumber = MutableStateFlow<Int?>(null)
    val selectedSeasonNumber: StateFlow<Int?> = _selectedSeasonNumber.asStateFlow()

    private val _isLoadingEpisodes = MutableStateFlow(false)
    val isLoadingEpisodes: StateFlow<Boolean> = _isLoadingEpisodes.asStateFlow()

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

    fun clearSearch() {
        catalogJob?.cancel()
        _items.value = emptyList()
        _message.value = null
        _isError.value = false
    }

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
        episodeJob?.cancel()
        _seasons.value = emptyList()
        _episodes.value = emptyList()
        _selectedSeasonNumber.value = null
        _isLoadingEpisodes.value = false
        catalogJob = viewModelScope.launch {
            _isLoading.value = true
            _selectedDetails.value = MediaDetails(item = item, description = item.overview, pageUrl = item.catalogUrl)
            _sourceResults.value = emptyList()
            _indexerErrors.value = emptyList()
            clearMessage()
            try {
                val token = _tmdbToken.value
                val details = withContext(Dispatchers.IO) {
                    catalog.tmdbDetails(item, token)
                }
                _selectedDetails.value = details
                settings.addToHistory(details.item)
                _history.value = settings.history()

                // Seasons are optional metadata. A TMDB failure must never hide the card.
                if (item.kind == app.jacket.mobile.data.MediaKind.SERIES && token.isNotBlank()) {
                    val availableSeasons = runCatching {
                        withContext(Dispatchers.IO) { catalog.seasons(item, token) }
                    }.getOrDefault(emptyList())
                    _seasons.value = availableSeasons
                    val firstSeason = availableSeasons.firstOrNull { it.seasonNumber == 1 }
                        ?: availableSeasons.firstOrNull()
                    if (firstSeason != null) {
                        _selectedSeasonNumber.value = firstSeason.seasonNumber
                        _isLoadingEpisodes.value = true
                        _episodes.value = runCatching {
                            withContext(Dispatchers.IO) {
                                catalog.episodes(item, firstSeason.seasonNumber, token)
                            }
                        }.getOrDefault(emptyList())
                        _isLoadingEpisodes.value = false
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _selectedDetails.value = MediaDetails(
                    item = item,
                    description = item.overview,
                    pageUrl = item.catalogUrl
                )
                showError(error.message ?: "Не удалось загрузить подробности")
            } finally {
                _isLoading.value = false
                _isLoadingEpisodes.value = false
            }
        }
    }

    fun closeDetails() {
        catalogJob?.cancel()
        episodeJob?.cancel()
        _selectedDetails.value = null
        _sourceResults.value = emptyList()
        _indexerErrors.value = emptyList()
        _seasons.value = emptyList()
        _episodes.value = emptyList()
        _selectedSeasonNumber.value = null
        _isLoadingEpisodes.value = false
    }

    fun selectSeason(item: CatalogItem, seasonNumber: Int) {
        if (seasonNumber <= 0 || _selectedSeasonNumber.value == seasonNumber) return
        episodeJob?.cancel()
        _selectedSeasonNumber.value = seasonNumber
        _episodes.value = emptyList()
        _isLoadingEpisodes.value = true
        episodeJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    catalog.episodes(item, seasonNumber, _tmdbToken.value)
                }
                _episodes.value = loaded
                if (loaded.isEmpty()) {
                    _message.value = "В этом сезоне TMDB не вернул серии"
                    _isError.value = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message ?: "Не удалось загрузить список серий")
            } finally {
                _isLoadingEpisodes.value = false
            }
        }
    }

    fun findSources(item: CatalogItem, episode: MediaEpisode? = null) {
        val year = item.year.takeIf { it.matches(Regex("\\d{4}")) }
        val query = if (episode != null) {
            listOf(item.title, episode.releaseCode, episode.name).joinToString(" ")
        } else {
            listOfNotNull(item.title, year).joinToString(" ")
        }
        runSourceSearch(query, includeArchive = true)
    }

    /** Manual search from the indexer dashboard; queries all enabled user indexers in parallel order. */
    fun searchIndexers(query: String) {
        if (query.isBlank()) {
            showError("Введите название для ручного поиска")
            return
        }
        runSourceSearch(query.trim(), includeArchive = false)
    }

    private fun runSourceSearch(query: String, includeArchive: Boolean) {
        sourcesJob?.cancel()
        sourcesJob = viewModelScope.launch {
            _isSearchingSources.value = true
            _sourceResults.value = emptyList()
            _indexerErrors.value = emptyList()
            clearMessage()
            try {
                val outcome = withContext(Dispatchers.IO) {
                    indexers.searchDetailed(query, _customIndexers.value, includeArchive)
                }
                _sourceResults.value = outcome.results
                _indexerErrors.value = outcome.failures
                val resultMessage = if (outcome.results.isEmpty()) {
                    "Ничего не найдено"
                } else {
                    "Найдено результатов: ${outcome.results.size}"
                }
                _message.value = if (outcome.failures.isEmpty()) resultMessage else {
                    "$resultMessage • ошибок источников: ${outcome.failures.size}"
                }
                _isError.value = outcome.results.isEmpty() && outcome.failures.isNotEmpty()
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

    fun saveIndexer(config: IndexerConfig): Boolean {
        return try {
            val saved = settings.saveIndexer(config)
            _customIndexers.value = settings.indexers()
            _indexerStatuses.value = _indexerStatuses.value - saved.id
            _message.value = "Индексатор «${saved.name}» сохранён"
            _isError.value = false
            true
        } catch (error: Exception) {
            showError(error.message ?: "Не удалось сохранить индексатор")
            false
        }
    }
    fun saveWebSearchProvider(provider: WebSearchProvider): Boolean {
        return try {
            settings.saveWebSearchProvider(provider)
            _webSearchProviders.value = settings.webSearchProviders()
            _message.value = "Сайт поиска сохранён"
            _isError.value = false
            true
        } catch (error: Exception) {
            showError(error.message ?: "Не удалось сохранить сайт поиска")
            false
        }
    }

    fun setWebSearchProviderEnabled(id: String, enabled: Boolean) {
        settings.setWebSearchProviderEnabled(id, enabled)
        _webSearchProviders.value = settings.webSearchProviders()
    }

    fun removeWebSearchProvider(id: String) {
        settings.removeWebSearchProvider(id)
        _webSearchProviders.value = settings.webSearchProviders()
        _message.value = "Сайт поиска удалён"
        _isError.value = false
    }

    /** Kept for compatibility with the simple RSS add flow. */
    fun addIndexer(name: String, urlTemplate: String) {
        saveIndexer(IndexerConfig(id = "", name = name, searchUrlTemplate = urlTemplate))
    }

    fun setIndexerEnabled(id: String, enabled: Boolean) {
        settings.setIndexerEnabled(id, enabled)
        _customIndexers.value = settings.indexers()
    }

    fun removeIndexer(id: String) {
        settings.removeIndexer(id)
        _customIndexers.value = settings.indexers()
        _indexerStatuses.value = _indexerStatuses.value - id
        _message.value = "Индексатор удалён"
        _isError.value = false
    }
    fun exportIndexersJson(): String = settings.exportIndexersJson()

    fun importIndexersJson(json: String): Int {
        return try {
            val count = settings.importIndexersJson(json)
            _customIndexers.value = settings.indexers()
            _message.value = "Импортировано индексаторов: $count. Секреты нужно ввести заново."
            _isError.value = false
            count
        } catch (error: Exception) {
            showError(error.message ?: "Не удалось импортировать список индексаторов")
            0
        }
    }


    fun searchOneIndexer(id: String, query: String) {
        val config = _customIndexers.value.firstOrNull { it.id == id } ?: return
        if (query.isBlank()) {
            showError("Введите название для пробного поиска")
            return
        }
        sourcesJob?.cancel()
        sourcesJob = viewModelScope.launch {
            _isSearchingSources.value = true
            _sourceResults.value = emptyList()
            _indexerErrors.value = emptyList()
            clearMessage()
            try {
                val results = withContext(Dispatchers.IO) { indexers.searchOne(config, query) }
                _sourceResults.value = results
                _message.value = if (results.isEmpty()) "Пробный поиск: результатов нет" else "Пробный поиск: ${results.size} результатов"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _indexerErrors.value = listOf(
                    IndexerFailure(config.id, config.name, error.message ?: "Ошибка пробного поиска")
                )
                showError("Пробный поиск «${config.name}»: ${error.message ?: "ошибка источника"}")
            } finally {
                _isSearchingSources.value = false
            }
        }
    }

    fun testIndexer(id: String) {
        val config = _customIndexers.value.firstOrNull { it.id == id } ?: return
        indexerTestJob?.cancel()
        indexerTestJob = viewModelScope.launch {
            _indexerStatuses.value = _indexerStatuses.value + (id to "Проверка…")
            val result = runCatching { withContext(Dispatchers.IO) { indexers.test(config) } }
            _indexerStatuses.value = _indexerStatuses.value + (
                id to result.fold(
                    onSuccess = { "Доступен • результатов теста: $it" },
                    onFailure = { "Ошибка: ${it.message ?: "не удалось подключиться"}" }
                )
            )
        }
    }

    fun testAllIndexers() {
        indexerTestJob?.cancel()
        indexerTestJob = viewModelScope.launch {
            val configs = _customIndexers.value.filter { it.enabled }
            if (configs.isEmpty()) {
                _message.value = "Нет включённых индексаторов для проверки"
                _isError.value = false
                return@launch
            }
            configs.forEach { _indexerStatuses.value = _indexerStatuses.value + (it.id to "Проверка…") }
            val failures = withContext(Dispatchers.IO) { indexers.testAll(configs) }
            val failedIds = failures.map { it.indexerId }.toSet()
            configs.forEach { config ->
                val failure = failures.firstOrNull { it.indexerId == config.id }
                _indexerStatuses.value = _indexerStatuses.value + (
                    config.id to if (failure == null) "Доступен" else "Ошибка: ${failure.message}"
                )
            }
            val ok = configs.size - failedIds.size
            _message.value = "Проверка завершена: $ok из ${configs.size} доступны"
            _isError.value = ok == 0
        }
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
