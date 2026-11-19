package app.jacket.mobile.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jacket.mobile.data.CatalogItem
import app.jacket.mobile.data.FilmixDeviceAuth
import app.jacket.mobile.data.HdRezkaLoginClient
import app.jacket.mobile.data.HdRezkaSettings
import app.jacket.mobile.data.IndexerConfig
import app.jacket.mobile.data.IndexerProtocol
import app.jacket.mobile.data.ReleaseHeuristics
import app.jacket.mobile.data.MediaKind
import app.jacket.mobile.data.MediaSection
import app.jacket.mobile.data.SourceResult
import app.jacket.mobile.data.WebSearchProvider
import app.jacket.mobile.data.WebSearchUrlBuilder
import app.jacket.mobile.data.formatBytes
import app.jacket.mobile.torrent.TorrentDownloadService
import app.jacket.mobile.torrent.TorrentEngine
import app.jacket.mobile.torrent.TorrentPlaybackBus
import app.jacket.mobile.torrent.PlaybackProgressStore
import app.jacket.mobile.torrent.PlaybackSession
import app.jacket.mobile.torrent.PlaybackSessionStore
import app.jacket.mobile.torrent.TorrentQueueStore
import java.io.File


@Composable
internal fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    var selectedSettingsSection by rememberSaveable { mutableStateOf("home") }
    val currentToken by viewModel.tmdbToken.collectAsState()
    val indexers by viewModel.customIndexers.collectAsState()
    val webSearchProviders by viewModel.webSearchProviders.collectAsState()
    val statuses by viewModel.indexerStatuses.collectAsState()
    val sourceResults by viewModel.sourceResults.collectAsState()
    val searchingSources by viewModel.isSearchingSources.collectAsState()
    val filmixToken by viewModel.filmixToken.collectAsState()
    val filmixCode by viewModel.filmixCode.collectAsState()
    val filmixStatus by viewModel.filmixStatus.collectAsState()
    val isFilmixPairing by viewModel.isFilmixPairing.collectAsState()
    val hdRezkaSettings by viewModel.hdRezkaSettings.collectAsState()
    val hdRezkaStatus by viewModel.hdRezkaStatus.collectAsState()
    val isHdRezkaBusy by viewModel.isHdRezkaBusy.collectAsState()
    val context = LocalContext.current
    var token by remember(currentToken) { mutableStateOf(currentToken) }
    val savedRates = remember(context) { TorrentEngine.rateLimits(context) }
    var downloadLimit by rememberSaveable { mutableStateOf(savedRates.first.toString()) }
    var uploadLimit by rememberSaveable { mutableStateOf(savedRates.second.toString()) }
    var wifiOnly by rememberSaveable { mutableStateOf(TorrentEngine.isWifiOnly(context)) }
    val exportIndexers = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(viewModel.exportIndexersJson().toByteArray(Charsets.UTF_8))
                } ?: error("Не удалось создать файл")
            }.onFailure { Toast.makeText(context, "Экспорт не удался: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val importIndexers = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var size = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= 256 * 1024) { "JSON-файл больше 256 КиБ" }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                } ?: error("Не удалось открыть файл")
                viewModel.importIndexersJson(bytes.toString(Charsets.UTF_8))
            }.onFailure { Toast.makeText(context, "Импорт не удался: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    var webEditingId by rememberSaveable { mutableStateOf("") }
    var webName by rememberSaveable { mutableStateOf("") }
    var webUrlTemplate by rememberSaveable { mutableStateOf("") }

    var hdMirror by remember(hdRezkaSettings.mirror) {
        mutableStateOf(hdRezkaSettings.mirror.ifBlank { "https://standby-rezka.tv" })
    }
    var hdLogin by remember(hdRezkaSettings.login) { mutableStateOf(hdRezkaSettings.login) }
    var hdPassword by remember(hdRezkaSettings.password) { mutableStateOf(hdRezkaSettings.password) }
    var hdCookie by remember(hdRezkaSettings.cookie) { mutableStateOf(hdRezkaSettings.cookie) }

    var editingId by remember { mutableStateOf("") }
    var sourceName by remember { mutableStateOf("") }
    var sourceUrl by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(IndexerProtocol.TORZNAB) }
    var apiKey by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var freeleechOnly by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf("created") }
    var sortOrder by remember { mutableStateOf("desc") }
    var manualQuery by remember { mutableStateOf("") }

    val message by viewModel.message.collectAsState()
    val isError by viewModel.isError.collectAsState()

    fun clearIndexerEditor() {
        editingId = ""
        sourceName = ""
        sourceUrl = ""
        protocol = IndexerProtocol.TORZNAB
        apiKey = ""
        username = ""
        password = ""
        cookie = ""
        categories = ""
        tags = ""
        freeleechOnly = false
        sortBy = "created"
        sortOrder = "desc"
    }

    fun editIndexer(config: IndexerConfig) {
        editingId = config.id
        sourceName = config.name
        sourceUrl = config.searchUrlTemplate
        protocol = config.protocol
        apiKey = config.apiKey
        username = config.username
        password = config.password
        cookie = config.cookie
        categories = config.categories
        tags = config.tags
        freeleechOnly = config.freeleechOnly
        sortBy = config.sortBy
        sortOrder = config.sortOrder
    }

    BackHandler(enabled = selectedSettingsSection != "home") {
        selectedSettingsSection = "home"
    }

    val sectionTitle = when (selectedSettingsSection) {
        "catalog" -> "Каталог"
        "sites" -> "Сайты и трекеры"
        "indexers" -> "Торрент-источники"
        "filmix" -> "Filmix"
        "hdrezka" -> "HDRezka"
        "network" -> "Загрузки и сеть"
        "about" -> "О приложении"
        else -> "Настройки"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sectionTitle) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedSettingsSection == "home") onBack()
                        else selectedSettingsSection = "home"
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (selectedSettingsSection == "home") "Назад" else "К разделам")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            if (selectedSettingsSection == "home") {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(26.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFF202D40),
                                        Color(0xFF18202F),
                                        Color(0xFF29203C)
                                    )
                                )
                            ).padding(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "JACKET  •  CONTROL CENTER",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 1.2.sp
                                    )
                                    Text(
                                        "Всё под контролем",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    Text(
                                        "Каталог, сайты поиска, торрент-источники и воспроизведение.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFCCD5E2)
                                    )
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Settings,
                                            contentDescription = null,
                                            modifier = Modifier.size(30.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    SettingsMenuRow(
                        title = "Filmix",
                        subtitle = "Подключение устройства по одноразовому коду",
                        badge = if (filmixToken.isNotBlank()) "Подключён" else "Не подключён",
                        onClick = { selectedSettingsSection = "filmix" },
                        icon = { Icon(Icons.Default.Tv, contentDescription = null) }
                    )
                }
                item {
                    SettingsMenuRow(
                        title = "HDRezka",
                        subtitle = "Зеркало, вход по логину и паролю, сессия",
                        badge = if (hdRezkaSettings.connected) "В сети" else "Настроить",
                        onClick = { selectedSettingsSection = "hdrezka" },
                        icon = { Icon(Icons.Default.Movie, contentDescription = null) }
                    )
                }
                item {
                    SettingsMenuRow(
                        title = "Сайты и трекеры",
                        subtitle = "Готовый список популярных сайтов и свои поисковые ссылки",
                        badge = webSearchProviders.size.toString(),
                        onClick = { selectedSettingsSection = "sites" },
                        icon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                }
                item {
                    SettingsMenuRow(
                        title = "Торрент-источники",
                        subtitle = "Jackett (опционально), RSS / Atom, Torznab и API-ключи",
                        badge = indexers.count { it.enabled }.toString(),
                        onClick = { selectedSettingsSection = "indexers" },
                        icon = { Icon(Icons.Default.Download, contentDescription = null) }
                    )
                }
                item {
                    SettingsMenuRow(
                        title = "Каталог и постеры",
                        subtitle = "Метаданные, рейтинги и TMDB",
                        onClick = { selectedSettingsSection = "catalog" },
                        icon = { Icon(Icons.Default.Movie, contentDescription = null) }
                    )
                }
                item {
                    SettingsMenuRow(
                        title = "Загрузки и сеть",
                        subtitle = "Скорость, Wi-Fi и папка файлов",
                        onClick = { selectedSettingsSection = "network" },
                        icon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                    )
                }
                item {
                    SettingsMenuRow(
                        title = "О приложении",
                        subtitle = "Версия, архитектура и безопасность",
                        onClick = { selectedSettingsSection = "about" },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) }
                    )
                }
            }

            settingsBasicItems(
                selectedSection = selectedSettingsSection,
                viewModel = viewModel,
                context = context,
                token = token,
                onTokenChange = { token = it },
                downloadLimit = downloadLimit,
                onDownloadLimitChange = { downloadLimit = it },
                uploadLimit = uploadLimit,
                onUploadLimitChange = { uploadLimit = it },
                wifiOnly = wifiOnly,
                onWifiOnlyChange = { wifiOnly = it }
            )

            settingsAccountItems(
                selectedSection = selectedSettingsSection,
                viewModel = viewModel,
                context = context,
                filmixToken = filmixToken,
                filmixCode = filmixCode,
                filmixStatus = filmixStatus,
                isFilmixPairing = isFilmixPairing,
                hdRezkaSettings = hdRezkaSettings,
                hdRezkaStatus = hdRezkaStatus,
                isHdRezkaBusy = isHdRezkaBusy,
                hdMirror = hdMirror,
                onHdMirrorChange = { hdMirror = it },
                hdLogin = hdLogin,
                onHdLoginChange = { hdLogin = it },
                hdPassword = hdPassword,
                onHdPasswordChange = { hdPassword = it },
                hdCookie = hdCookie,
                onHdCookieChange = { hdCookie = it }
            )

            settingsWebsiteItems(
                selectedSection = selectedSettingsSection,
                viewModel = viewModel,
                context = context,
                webSearchProviders = webSearchProviders,
                webEditingId = webEditingId,
                onWebEditingIdChange = { webEditingId = it },
                webName = webName,
                onWebNameChange = { webName = it },
                webUrlTemplate = webUrlTemplate,
                onWebUrlTemplateChange = { webUrlTemplate = it }
            )

            if (selectedSettingsSection == "indexers") {
            item {
                Text("Встроенные источники", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Internet Archive", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Каталог открытых медиа доступен напрямую, отдельный сервер Jackett не нужен.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                val savedJackett = indexers.firstOrNull { it.protocol == IndexerProtocol.JACKETT }
                JackettSetupCard(
                    saved = savedJackett,
                    status = savedJackett?.let { statuses[it.id] },
                    onSave = viewModel::saveJackett,
                    onTest = { savedJackett?.let { viewModel.testIndexer(it.id) } },
                    onRemove = { savedJackett?.let { viewModel.removeIndexer(it.id) } }
                )
            }

            item {
                Text(
                    if (editingId.isBlank()) "Добавить агрегатор / индексатор" else "Изменить индексатор",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Jackett подключается опционально к вашему серверу. Для прямых источников подходят RSS/Atom и Torznab/Newznab; HTTP разрешён только для локального адреса. RSS/Atom может публиковать прямые HTTPS-видео, HLS и DASH. URL поиска содержит {query}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { protocol = IndexerProtocol.TORZNAB },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (protocol == IndexerProtocol.TORZNAB) "✓ Torznab" else "Torznab") }
                    OutlinedButton(
                        onClick = { protocol = IndexerProtocol.RSS },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (protocol == IndexerProtocol.RSS) "✓ RSS / Atom" else "RSS / Atom") }
                }
                OutlinedTextField(
                    value = sourceName,
                    onValueChange = { sourceName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название индексатора") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = sourceUrl,
                    onValueChange = { sourceUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Адрес сайта / URL поиска") },
                    placeholder = { Text("https://tracker.example/api?t=search&q={query}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    minLines = 2
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Логин") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                OutlinedTextField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Cookie / session (при необходимости)") },
                    visualTransformation = PasswordVisualTransformation(),
                    minLines = 2
                )
                Text(
                    "Для частных индексаторов можно использовать логин/пароль, API key или cookie активной сессии. Cookie можно вставить вручную после входа на сайт.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = categories,
                    onValueChange = { categories = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Категории Torznab (ID через запятую)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Теги (через запятую)") },
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = freeleechOnly, onCheckedChange = { freeleechOnly = it })
                    Text("Только Freeleech")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sortBy,
                        onValueChange = { sortBy = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Сортировка") },
                        placeholder = { Text("created") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = sortOrder,
                        onValueChange = { sortOrder = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Порядок") },
                        placeholder = { Text("desc") },
                        singleLine = true
                    )
                }
                Button(
                    onClick = {
                        val saved = viewModel.saveIndexer(
                            IndexerConfig(
                                id = editingId,
                                name = sourceName,
                                searchUrlTemplate = sourceUrl,
                                protocol = protocol,
                                apiKey = apiKey,
                                username = username,
                                password = password,
                                cookie = cookie,
                                categories = categories,
                                tags = tags,
                                freeleechOnly = freeleechOnly,
                                sortBy = sortBy,
                                sortOrder = sortOrder
                            )
                        )
                        if (saved) clearIndexerEditor()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (editingId.isBlank()) "Добавить индексатор" else "Сохранить изменения")
                }
                if (editingId.isNotBlank()) {
                    OutlinedButton(onClick = { clearIndexerEditor() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Отменить редактирование")
                    }
                }
            }

            item {
                Text("Настроенные индексаторы (${indexers.count { it.protocol != IndexerProtocol.JACKETT }})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.testAllIndexers() }, modifier = Modifier.weight(1f)) {
                        Text("Тестировать все")
                    }
                    OutlinedButton(onClick = { exportIndexers.launch("jacket-indexers.json") }, modifier = Modifier.weight(1f)) {
                        Text("Экспорт JSON")
                    }
                    OutlinedButton(onClick = { importIndexers.launch(arrayOf("application/json", "text/json")) }, modifier = Modifier.weight(1f)) {
                        Text("Импорт JSON")
                    }
                }
                Text(
                    "Включайте/отключайте источники, проверяйте соединение и редактируйте регистрационные данные.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(indexers.filter { it.protocol != IndexerProtocol.JACKETT }, key = { it.id }) { indexer ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(indexer.name, fontWeight = FontWeight.SemiBold)
                                Text(indexer.protocol.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Switch(
                                checked = indexer.enabled,
                                onCheckedChange = { viewModel.setIndexerEnabled(indexer.id, it) }
                            )
                        }
                        Text(indexer.searchUrlTemplate, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text(
                            statuses[indexer.id] ?: if (indexer.apiKey.isNotBlank() || indexer.username.isNotBlank() || indexer.cookie.isNotBlank()) "Авторизация настроена" else "Без дополнительных данных авторизации",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (statuses[indexer.id]?.startsWith("Ошибка") == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.testIndexer(indexer.id) }) { Text("Тест") }
                            OutlinedButton(
                                onClick = { viewModel.searchOneIndexer(indexer.id, manualQuery) },
                                enabled = manualQuery.isNotBlank() && !searchingSources
                            ) { Text("Пробный поиск") }
                            OutlinedButton(onClick = { editIndexer(indexer) }) { Text("Изменить") }
                            OutlinedButton(onClick = { viewModel.removeIndexer(indexer.id) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить")
                                Text("Удалить")
                            }
                        }
                    }
                }
            }

            item {
                Text("Ручной поиск по индексаторам", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = manualQuery,
                    onValueChange = { manualQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название фильма, сериала или релиза") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.searchIndexers(manualQuery) })
                )
                Button(
                    onClick = { viewModel.searchIndexers(manualQuery) },
                    enabled = !searchingSources && manualQuery.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (searchingSources) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (searchingSources) "Поиск…" else "Искать во всех включённых")
                }
            }

            if (message != null) item { MessageCard(message.orEmpty(), isError) }
            if (sourceResults.isNotEmpty()) {
                item {
                    Text("Найденные релизы (${sourceResults.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                items(sourceResults, key = { it.id }) { source ->
                    SourceCard(
                        source = source,
                        onPlay = { },
                        onMagnet = { magnet -> TorrentDownloadService.add(context, magnet, source.title) },
                        onTorrent = { url -> openExternal(context, url) },
                        onPage = { url -> openExternal(context, url) }
                    )
                }
            }

            item {
                Text(
                    "Экспорт JSON содержит адреса и настройки, но не API key, пароль или cookie. После импорта добавьте секреты заново. Секреты в локальном хранилище шифруются через Android Keystore. Используйте только источники, к которым у вас есть законный доступ. Некоторые сайты требуют собственный браузерный вход, CAPTCHA или интерактивную 2FA — такие схемы пока не автоматизируются; для них можно добавить вручную выданный cookie.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }            }

            if (selectedSettingsSection == "about") {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("JACKET 2.4", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                            Text("Единое приложение для Android, планшетов и Android TV.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Есть Filmix-привязка по коду и настройки HDRezka. RSS / Atom и Torznab обрабатываются внутри приложения; трекер-пресеты открываются в браузере.")
                            Text("Jacket не извлекает медиапотоки с произвольных страниц и не обходит ограничения сайта.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

        }
    }
}


@Composable
private fun SettingsMenuRow(
    title: String,
    subtitle: String,
    badge: String? = null,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isTv = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isTv && focused) 1.018f else 1f,
        label = "settings-row-focus"
    )
    Card(
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (isTv && focused) 2.dp else 0.dp,
                color = if (isTv && focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(22.dp)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isTv && focused) 7.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (focused) 0.22f else 0.14f),
                shape = RoundedCornerShape(15.dp)
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { icon() }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            if (badge != null) MetadataPill(badge, accent = true)
            Icon(
                Icons.Default.OpenInNew,
                contentDescription = "Открыть раздел",
                tint = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


@Composable
internal fun SettingsMenuRow(
    title: String,
    subtitle: String,
    badge: String? = null,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isTv = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isTv && focused) 1.018f else 1f,
        label = "settings-row-focus"
    )
    Card(
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (isTv && focused) 2.dp else 0.dp,
                color = if (isTv && focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(22.dp)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isTv && focused) 7.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (focused) 0.22f else 0.14f),
                shape = RoundedCornerShape(15.dp)
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { icon() }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            if (badge != null) MetadataPill(badge, accent = true)
            Icon(
                Icons.Default.OpenInNew,
                contentDescription = "Открыть раздел",
                tint = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


