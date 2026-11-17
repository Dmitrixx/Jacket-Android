package app.jacket.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.jacket.mobile.copyToClipboard
import app.jacket.mobile.data.ConnectionConfig
import app.jacket.mobile.data.TorrentEntry
import app.jacket.mobile.data.formatBytes
import app.jacket.mobile.openTorrent
import app.jacket.mobile.torrent.TorrentDownloadService
import app.jacket.mobile.torrent.TorrentEngine
import app.jacket.mobile.torrent.TorrentQueueStore

@Composable
fun JacketApp(viewModel: MainViewModel) {
    val config by viewModel.config.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(config.serverUrl.isBlank() || config.apiKey.isBlank()) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    if (showSettings) {
        SettingsScreen(
            config = config,
            isLoading = viewModel.isLoading.collectAsState().value,
            message = viewModel.message.collectAsState().value,
            isError = viewModel.isError.collectAsState().value,
            onTest = viewModel::testConnection,
            onSave = { url, key ->
                viewModel.saveSettings(url, key)
                showSettings = false
            },
            onBack = { showSettings = false }
        )
    } else if (selectedTab == 0) {
        SearchScreen(
            viewModel = viewModel,
            onOpenSettings = { showSettings = true },
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it }
        )
    } else {
        DownloadsScreen(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by viewModel.results.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()
    val isError by viewModel.isError.collectAsState()
    val config by viewModel.config.collectAsState()
    val context = LocalContext.current

    Scaffold(
        bottomBar = { AppNavigationBar(selectedTab, onTabSelected) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Jacket", fontWeight = FontWeight.Bold)
                        Text(
                            text = config.serverUrl.ifBlank { "Сервер не настроен" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Поиск по индексаторам Jackett",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Название или запрос") },
                placeholder = { Text("Например: Ubuntu 24.04") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) })
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.search(query) },
                enabled = !loading && query.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp).width(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                } else {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (loading) "Подождите…" else "Найти торренты")
            }

            if (message != null) {
                Spacer(Modifier.height(12.dp))
                StatusMessage(message = message.orEmpty(), isError = isError)
            }

            Spacer(Modifier.height(12.dp))
            if (results.isEmpty() && !loading) {
                EmptyResultsHint()
            } else {
                Text(
                    "Результаты (${results.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(results, key = { it.id }) { entry ->
                        TorrentCard(
                            entry = entry,
                            onOpen = {
                                val magnet = entry.magnetUri
                                if (magnet != null) {
                                    TorrentDownloadService.add(context, magnet, entry.title)
                                } else {
                                    openTorrent(context, entry)
                                }
                            },
                            onCopy = {
                                entry.openUri?.let { copyToClipboard(context, it) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppNavigationBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            icon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text("Поиск") }
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            icon = { Icon(Icons.Default.Download, contentDescription = null) },
            label = { Text("Загрузки") }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreen(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val context = LocalContext.current
    val downloads by TorrentEngine.snapshots.collectAsState()

    LaunchedEffect(Unit) {
        if (TorrentQueueStore(context).all().isNotEmpty()) TorrentDownloadService.restore(context)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Загрузки", fontWeight = FontWeight.Bold) }) },
        bottomBar = { AppNavigationBar(selectedTab, onTabSelected) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Активные задачи: ${downloads.size}", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { TorrentDownloadService.restore(context) }) { Text("Обновить") }
            }
            if (downloads.isEmpty()) {
                EmptyDownloadsHint()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(downloads, key = { it.id }) { item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                LinearProgressIndicator(progress = item.progress, modifier = Modifier.fillMaxWidth())
                                Text(
                                    "${(item.progress * 100).toInt()}% · ${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "↓ ${formatRate(item.downloadBytesPerSecond)}  ·  ↑ ${formatRate(item.uploadBytesPerSecond)}  ·  сиды ${item.seeds}  ·  пиры ${item.peers}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    when {
                                        item.paused -> "Приостановлено"
                                        item.progress >= 1f -> "Раздача завершена"
                                        item.state == "DOWNLOADING_METADATA" -> "Получение метаданных"
                                        item.state.startsWith("CHECKING") -> "Проверка файлов"
                                        else -> "Загрузка"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            if (item.paused) TorrentDownloadService.resume(context, item.id)
                                            else TorrentDownloadService.pause(context, item.id)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            if (item.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            contentDescription = null
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (item.paused) "Продолжить" else "Пауза")
                                    }
                                    OutlinedButton(onClick = { TorrentDownloadService.remove(context, item.id) }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить задачу")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDownloadsHint() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Загрузок пока нет", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Найдите раздачу во вкладке «Поиск» и нажмите «Скачать». Jacket покажет прогресс, скорость, сиды и пиры. Удаление задачи не удаляет уже загруженные файлы.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatRate(bytesPerSecond: Int): String = when {
    bytesPerSecond >= 1024 * 1024 -> "%.1f МБ/с".format(bytesPerSecond / (1024.0 * 1024.0))
    bytesPerSecond >= 1024 -> "%.0f КБ/с".format(bytesPerSecond / 1024.0)
    else -> "$bytesPerSecond Б/с"
}

@Composable
private fun EmptyResultsHint() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Как начать", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "1. Подключите работающий сервер Jackett в настройках.\n" +
                    "2. Введите запрос и нажмите «Найти торренты».\n" +
                    "3. Нажмите «Скачать», чтобы загрузить magnet прямо в Jacket. Файлы сохраняются в папку приложения Downloads.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TorrentCard(
    entry: TorrentEntry,
    onOpen: () -> Unit,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = listOfNotNull(
                    formatBytes(entry.sizeBytes),
                    entry.seeders?.let { "Сиды: $it" },
                    entry.leechers?.let { "Пиры: $it" },
                    entry.indexer?.let { "Источник: $it" }
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onOpen, enabled = entry.openUri != null, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (entry.magnetUri != null) "Скачать" else "Открыть")
                }
                OutlinedButton(onClick = onCopy, enabled = entry.openUri != null) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Копировать ссылку")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    config: ConnectionConfig,
    isLoading: Boolean,
    message: String?,
    isError: Boolean,
    onTest: (String, String) -> Unit,
    onSave: (String, String) -> Unit,
    onBack: () -> Unit
) {
    var serverUrl by remember(config.serverUrl) { mutableStateOf(config.serverUrl) }
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Подключение к Jackett") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Адрес сервера",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Jacket — Android-клиент. Сам сервер Jackett должен быть запущен отдельно на ПК, NAS или другом устройстве, доступном по сети.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("URL Jackett") },
                placeholder = { Text("http://192.168.1.10:9117") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API-ключ") },
                placeholder = { Text("Ключ из панели Jackett") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
            )
            Text(
                "В Jackett API-ключ находится в верхней части веб-панели. Стандартный порт — 9117. Для доступа из телефона сервер и телефон должны быть в одной сети либо сервер должен быть доступен через защищённое соединение.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (message != null) {
                StatusMessage(message = message, isError = isError)
            }

            OutlinedButton(
                onClick = { onTest(serverUrl, apiKey) },
                enabled = !isLoading && serverUrl.isNotBlank() && apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isLoading) "Проверяем…" else "Проверить соединение")
            }
            Button(
                onClick = { onSave(serverUrl, apiKey) },
                enabled = serverUrl.isNotBlank() && apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Сохранить")
            }
            Text(
                "API-ключ хранится в зашифрованном виде через Android Keystore. Для удалённого подключения используйте HTTPS или VPN; открытый HTTP подходит только для доверенной локальной сети.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusMessage(message: String, isError: Boolean) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        color = color.copy(alpha = 0.10f),
        contentColor = color,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = message,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
