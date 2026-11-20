package app.jacket.mobile.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jacket.mobile.data.CatalogItem
import app.jacket.mobile.data.FilmixDeviceAuth
import app.jacket.mobile.data.FilmixPendingDevice
import app.jacket.mobile.data.HdRezkaLoginClient
import app.jacket.mobile.data.HdRezkaSettings
import app.jacket.mobile.data.IndexerConfig
import app.jacket.mobile.data.IndexerFailure
import app.jacket.mobile.data.IndexerRegistry
import app.jacket.mobile.data.JackettUrl
import app.jacket.mobile.data.MediaCatalogClient
import app.jacket.mobile.data.MediaDetails
import app.jacket.mobile.data.MediaSection
import app.jacket.mobile.data.MediaSeason
import app.jacket.mobile.data.MediaEpisode
import app.jacket.mobile.data.MediaSettingsStore
import app.jacket.mobile.data.WebSearchProvider
import app.jacket.mobile.BuildConfig
import app.jacket.mobile.data.SourceResult
import app.jacket.mobile.data.TrackerSearchClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var filmixPairingJob: Job? = null
    private var hdRezkaLoginJob: Job? = null

    private val _tmdbToken = MutableStateFlow(settings.tmdbToken())
    val tmdbToken: StateFlow<String> = _tmdbToken.asStateFlow()

    private val _customIndexers = MutableStateFlow(settings.indexers())
    val customIndexers: StateFlow<List<IndexerConfig>> = _customIndexers.asStateFlow()

    private val _webSearchProviders = MutableStateFlow(settings.webSearchProviders())
    val webSearchProviders: StateFlow<List<WebSearchProvider>> = _webSearchProviders.asStateFlow()

    private val _filmixToken = MutableStateFlow(settings.filmixToken())
    val filmixToken: StateFlow<String> = _filmixToken.asStateFlow()

    private val _filmixCode = MutableStateFlow<String?>(null)
    val filmixCode: StateFlow<String?> = _filmixCode.asStateFlow()

    private val _filmixStatus = MutableStateFlow(
        if (_filmixToken.value.isBlank()) "Устройство не авторизовано" else "Токен сохранён"
    )
    val filmixStatus: StateFlow<String> = _filmixStatus.asStateFlow()

    private val _isFilmixPairing = MutableStateFlow(false)
    val isFilmixPairing: StateFlow<Boolean> = _isFilmixPairing.asStateFlow()

    private val _hdRezkaSettings = MutableStateFlow(settings.hdRezkaSettings())
    val hdRezkaSettings: StateFlow<HdRezkaSettings> = _hdRezkaSettings.asStateFlow()

    private val _hdRezkaStatus = MutableStateFlow(
        if (_hdRezkaSettings.value.connected) "Сессия HDRezka сохранена" else "Не авторизовано"
    )
    val hdRezkaStatus: StateFlow<String> = _hdRezkaStatus.asStateFlow()

    private val _isHdRezkaBusy = MutableStateFlow(false)
    val isHdRezkaBusy: StateFlow<Boolean> = _isHdRezkaBusy.asStateFlow()

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
        sourcesJob?.cancel()
        _items.value = emptyList()
        _sourceResults.value = emptyList()
        _indexerErrors.value = emptyList()
        _isSearchingSources.value = false
        _message.value = null
        _isError.value = false
    }

    fun search(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) {
            clearSearch()
            _message.value = "Введите название фильма или сериала"
            _isError.value = true
            return
        }
        sourcesJob?.cancel()
        _sourceResults.value = emptyList()
        _indexerErrors.value = emptyList()
        _isSearchingSources.value = false
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

    /** Fetches and parses releases from one selected tracker inside Jacket. */
    fun searchTracker(provider: WebSearchProvider, query: String) {
        if (query.isBlank()) {
            showError("Введите название для поиска")
            return
        }
        sourcesJob?.cancel()
        sourcesJob = viewModelScope.launch {
            _isSearchingSources.value = true
            _sourceResults.value = emptyList()
            _indexerErrors.value = emptyList()
            clearMessage()
            try {
                val results = withContext(Dispatchers.IO) {
                    if (provider.id == "tracker-archive") {
                        catalog.archiveSources(query.trim())
                    } else {
                        TrackerSearchClient.search(provider, query.trim())
                    }
                }
                _sourceResults.value = results
                _message.value = if (results.isEmpty()) {
                    "На сайте не найдено раздач с прямой magnet или .torrent ссылкой."
                } else {
                    "Найдено раздач: ${results.size} • выберите релиз."
                }
                _isError.value = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message ?: "Не удалось разобрать результаты трекера")
            } finally {
                _isSearchingSources.value = false
            }
        }
    }

    /** Searches every enabled built-in tracker and combines the releases into one list. */
    fun searchAllTrackers(query: String) {
        if (query.isBlank()) {
            showError("Введите название для поиска")
            return
        }
        val providers = _webSearchProviders.value.filter {
            it.enabled && TrackerSearchClient.supports(it.id)
        }
        if (providers.isEmpty()) {
            showError("Включите хотя бы один встроенный трекер в настройках сайтов")
            return
        }
        sourcesJob?.cancel()
        sourcesJob = viewModelScope.launch {
            _isSearchingSources.value = true
            _sourceResults.value = emptyList()
            _indexerErrors.value = emptyList()
            clearMessage()
            try {
                val outcome = withContext(Dispatchers.IO) {
                    TrackerSearchClient.searchAll(providers, query.trim())
                }
                _sourceResults.value = outcome.results
                val summary = if (outcome.results.isEmpty()) {
                    "На включённых трекерах не найдено раздач с прямыми magnet или .torrent ссылками."
                } else {
                    "Найдено раздач: ${outcome.results.size} • трекеров ответило: ${providers.size - outcome.failures.size}/${providers.size}."
                }
                _message.value = if (outcome.failures.isEmpty()) summary else {
                    "$summary Ошибок: ${outcome.failures.size}."
                }
                _isError.value = outcome.results.isEmpty() && outcome.failures.isNotEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message ?: "Не удалось выполнить поиск по трекерам")
            } finally {
                _isSearchingSources.value = false
            }
        }
    }

    /** Builds a release query from a catalog item and searches every enabled built-in tracker. */
    fun findSourcesOnAllTrackers(item: CatalogItem, episode: MediaEpisode? = null) {
        val year = item.year.takeIf { it.matches(Regex("\\d{4}")) }
        val query = if (episode != null) {
            listOf(item.title, episode.releaseCode, episode.name).joinToString(" ")
        } else {
            listOfNotNull(item.title, year).joinToString(" ")
        }
        searchAllTrackers(query)
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

    /** Optional connection to the user's own Jackett server. Nothing is bundled in the APK. */
    fun saveJackett(address: String, apiKey: String, indexerFilter: String) {
        val base = JackettUrl.normalizeBase(address)
        if (base == null) {
            showError("Некорректный адрес Jackett. HTTP разрешён только для локального адреса.")
            return
        }
        if (apiKey.isBlank()) {
            showError("Введите API-ключ из панели Jackett")
            return
        }
        val existing = _customIndexers.value.firstOrNull {
            it.protocol == app.jacket.mobile.data.IndexerProtocol.JACKETT
        }
        val config = IndexerConfig(
            id = existing?.id.orEmpty(),
            name = "Jackett",
            searchUrlTemplate = JackettUrl.searchTemplate(base, indexerFilter),
            protocol = app.jacket.mobile.data.IndexerProtocol.JACKETT,
            apiKey = apiKey.trim()
        )
        if (saveIndexer(config)) {
            _customIndexers.value.firstOrNull {
                it.protocol == app.jacket.mobile.data.IndexerProtocol.JACKETT
            }?.let { testIndexer(it.id) }
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

    fun startFilmixPairing() {
        filmixPairingJob?.cancel()
        filmixPairingJob = viewModelScope.launch {
            _isFilmixPairing.value = true
            _filmixCode.value = null
            _filmixStatus.value = "Запрашиваем код у Filmix…"
            try {
                val deviceId = settings.filmixDeviceId()
                val deviceName = Build.MODEL?.take(80)?.ifBlank { "Jacket" } ?: "Jacket"
                val vendor = Build.MANUFACTURER?.take(80)?.ifBlank { "Android" } ?: "Android"
                val osVersion = "Android ${Build.VERSION.RELEASE.orEmpty()}"
                val appVersion = BuildConfig.VERSION_NAME
                val pending = withContext(Dispatchers.IO) {
                    FilmixDeviceAuth.requestCode(deviceId, deviceName, vendor, osVersion, appVersion)
                }
                _filmixCode.value = pending.code
                _filmixStatus.value = "Введите код на странице устройств Filmix. Проверяем подтверждение автоматически."
                val deadline = System.currentTimeMillis() + FILMIX_PAIRING_TIMEOUT_MS

                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(FILMIX_PAIRING_POLL_MS)
                    val activated = try {
                        withContext(Dispatchers.IO) {
                            FilmixDeviceAuth.pollActivation(pending, deviceName, vendor, osVersion, appVersion)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        _filmixStatus.value = "Нет ответа от Filmix. Повторяем проверку…"
                        null
                    }
                    if (!activated.isNullOrBlank()) {
                        settings.saveFilmixToken(activated)
                        _filmixToken.value = settings.filmixToken()
                        _filmixCode.value = null
                        _filmixStatus.value = "Устройство авторизовано"
                        return@launch
                    }
                }
                if (isActive) {
                    _filmixStatus.value = "Время действия запроса истекло. Получите новый код."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _filmixStatus.value = FilmixDeviceAuth.userFriendlyError(error)
                _filmixCode.value = null
            } finally {
                _isFilmixPairing.value = false
            }
        }
    }

    fun cancelFilmixPairing() {
        filmixPairingJob?.cancel()
        filmixPairingJob = null
        _isFilmixPairing.value = false
        _filmixCode.value = null
        _filmixStatus.value = if (_filmixToken.value.isBlank()) "Запрос отменён" else "Токен сохранён"
    }

    fun disconnectFilmix() {
        cancelFilmixPairing()
        settings.clearFilmixToken()
        _filmixToken.value = ""
        _filmixStatus.value = "Устройство не авторизовано"
    }

    fun saveHdRezkaSettings(config: HdRezkaSettings) {
        try {
            val saved = config.copy(connected = config.cookie.isNotBlank())
            settings.saveHdRezkaSettings(saved)
            _hdRezkaSettings.value = settings.hdRezkaSettings()
            _hdRezkaStatus.value = if (saved.connected) "Cookie-сессия сохранена" else "Данные сохранены; вход не выполнен"
        } catch (error: Exception) {
            _hdRezkaStatus.value = error.message ?: "Не удалось сохранить настройки HDRezka"
        }
    }

    fun loginHdRezka(config: HdRezkaSettings) {
        hdRezkaLoginJob?.cancel()
        hdRezkaLoginJob = viewModelScope.launch {
            _isHdRezkaBusy.value = true
            _hdRezkaStatus.value = "Подключаемся к HDRezka по HTTPS…"
            try {
                settings.saveHdRezkaSettings(config.copy(cookie = "", connected = false))
                val result = withContext(Dispatchers.IO) {
                    HdRezkaLoginClient.login(config.mirror, config.login, config.password)
                }
                val saved = config.copy(
                    mirror = result.activeMirror,
                    cookie = result.cookieHeader,
                    connected = true
                )
                settings.saveHdRezkaSettings(saved)
                _hdRezkaSettings.value = settings.hdRezkaSettings()
                _hdRezkaStatus.value = "Авторизация подтверждена. Cookie-сессия сохранена."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _hdRezkaSettings.value = settings.hdRezkaSettings()
                _hdRezkaStatus.value = HdRezkaLoginClient.userFriendlyError(error)
            } finally {
                _isHdRezkaBusy.value = false
            }
        }
    }

    fun checkHdRezkaMirror(mirror: String) {
        hdRezkaLoginJob?.cancel()
        hdRezkaLoginJob = viewModelScope.launch {
            _isHdRezkaBusy.value = true
            _hdRezkaStatus.value = "Проверяем доступность зеркала HDRezka…"
            try {
                val active = withContext(Dispatchers.IO) {
                    HdRezkaLoginClient.probeMirror(mirror)
                }
                val current = settings.hdRezkaSettings()
                settings.saveHdRezkaSettings(current.copy(mirror = active))
                _hdRezkaSettings.value = settings.hdRezkaSettings()
                _hdRezkaStatus.value = "Зеркало доступно: $active"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _hdRezkaStatus.value = HdRezkaLoginClient.userFriendlyError(error)
            } finally {
                _isHdRezkaBusy.value = false
            }
        }
    }

    fun disconnectHdRezka() {
        settings.disconnectHdRezka()
        _hdRezkaSettings.value = settings.hdRezkaSettings()
        _hdRezkaStatus.value = "Сессия удалена"
    }

    fun restoreDefaultTrackerShortcuts() {
        _webSearchProviders.value = settings.restoreDefaultTrackerShortcuts()
        _message.value = "Список сайтов поиска восстановлен"
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
        const val FILMIX_PAIRING_TIMEOUT_MS = 15 * 60 * 1000L
        const val FILMIX_PAIRING_POLL_MS = 10_000L
    }
}
