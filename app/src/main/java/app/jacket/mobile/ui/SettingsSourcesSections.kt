package app.jacket.mobile.ui

import android.content.Intent
import android.content.Context
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
import androidx.compose.foundation.lazy.LazyListScope
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

internal class SettingsSourcesEditorState {
    var webEditingId by mutableStateOf("")
    var webName by mutableStateOf("")
    var webUrlTemplate by mutableStateOf("")

    var editingId by mutableStateOf("")
    var sourceName by mutableStateOf("")
    var sourceUrl by mutableStateOf("")
    var protocol by mutableStateOf(IndexerProtocol.TORZNAB)
    var apiKey by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var cookie by mutableStateOf("")
    var categories by mutableStateOf("")
    var tags by mutableStateOf("")
    var freeleechOnly by mutableStateOf(false)
    var sortBy by mutableStateOf("created")
    var sortOrder by mutableStateOf("desc")
    var manualQuery by mutableStateOf("")

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
}

internal fun LazyListScope.settingsSourcesSections(
    selectedSettingsSection: String,
    viewModel: MainViewModel,
    context: Context,
    editor: SettingsSourcesEditorState,
    webSearchProviders: List<WebSearchProvider>,
    indexers: List<IndexerConfig>,
    statuses: Map<String, String>,
    sourceResults: List<SourceResult>,
    searchingSources: Boolean,
    message: String?,
    isError: Boolean,
    onImportIndexers: () -> Unit,
    onExportIndexers: () -> Unit
) {

            if (selectedSettingsSection == "sites") {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (editor.webEditingId.isBlank()) "Добавить сайт поиска" else "Изменить сайт",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "В списке уже есть популярные сайты поиска. Они открываются во внешнем браузере; RSS / Atom и Torznab-индексаторы работают отдельно и показывают результаты внутри Jacket.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { viewModel.restoreDefaultTrackerShortcuts() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Восстановить список сайтов и трекеров")
                        }
                        OutlinedTextField(
                            value = editor.webName,
                            onValueChange = { editor.webName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Название сайта") },
                            placeholder = { Text("Например, мой видеокаталог") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = editor.webUrlTemplate,
                            onValueChange = { editor.webUrlTemplate = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("URL поиска с {query}") },
                            placeholder = { Text("https://site.example/search?q={query}") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            minLines = 2
                        )
                        Button(
                            onClick = {
                                val saved = viewModel.saveWebSearchProvider(
                                    WebSearchProvider(
                                        id = editor.webEditingId,
                                        name = editor.webName,
                                        searchUrlTemplate = editor.webUrlTemplate
                                    )
                                )
                                if (saved) {
                                    editor.webEditingId = ""
                                    editor.webName = ""
                                    editor.webUrlTemplate = ""
                                    Toast.makeText(context, "Сайт поиска сохранён", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Проверьте название и HTTPS-шаблон с {query}", Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (editor.webEditingId.isBlank()) "Добавить сайт" else "Сохранить изменения")
                        }
                        if (editor.webEditingId.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    editor.webEditingId = ""
                                    editor.webName = ""
                                    editor.webUrlTemplate = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Отменить редактирование") }
                        }
                        Text(
                            "Добавляйте только сайты, доступ к которым разрешён в вашей стране и на законном основании. Jacket не обходит блокировки, CAPTCHA, авторизацию или DRM.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(webSearchProviders, key = { "web-${it.id}" }) { provider ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(provider.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (provider.enabled) "Включён • поиск в браузере" else "Отключён",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = provider.enabled,
                                    onCheckedChange = { viewModel.setWebSearchProviderEnabled(provider.id, it) }
                                )
                            }
                            Text(provider.searchUrlTemplate, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { openWebsiteSearch(context, provider, "Jacket test") },
                                    enabled = provider.enabled
                                ) { Text("Проверить") }
                                OutlinedButton(
                                    onClick = {
                                        editor.webEditingId = provider.id
                                        editor.webName = provider.name
                                        editor.webUrlTemplate = provider.searchUrlTemplate
                                    }
                                ) { Text("Изменить") }
                                OutlinedButton(onClick = { viewModel.removeWebSearchProvider(provider.id) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить")
                                }
                            }
                        }
                    }
                }
                if (webSearchProviders.isEmpty()) {
                    item { MessageCard("Сайтов пока нет. Добавьте HTTPS-шаблон поиска выше.", false) }
                }
            }


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
                    if (editor.editingId.isBlank()) "Добавить агрегатор / индексатор" else "Изменить индексатор",
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
                        onClick = { editor.protocol = IndexerProtocol.TORZNAB },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (editor.protocol == IndexerProtocol.TORZNAB) "✓ Torznab" else "Torznab") }
                    OutlinedButton(
                        onClick = { editor.protocol = IndexerProtocol.RSS },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (editor.protocol == IndexerProtocol.RSS) "✓ RSS / Atom" else "RSS / Atom") }
                }
                OutlinedTextField(
                    value = editor.sourceName,
                    onValueChange = { editor.sourceName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название индексатора") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = editor.sourceUrl,
                    onValueChange = { editor.sourceUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Адрес сайта / URL поиска") },
                    placeholder = { Text("https://tracker.example/api?t=search&q={query}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    minLines = 2
                )
                OutlinedTextField(
                    value = editor.apiKey,
                    onValueChange = { editor.apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = editor.username,
                    onValueChange = { editor.username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Логин") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = editor.password,
                    onValueChange = { editor.password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                OutlinedTextField(
                    value = editor.cookie,
                    onValueChange = { editor.cookie = it },
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
                    value = editor.categories,
                    onValueChange = { editor.categories = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Категории Torznab (ID через запятую)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = editor.tags,
                    onValueChange = { editor.tags = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Теги (через запятую)") },
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = editor.freeleechOnly, onCheckedChange = { editor.freeleechOnly = it })
                    Text("Только Freeleech")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editor.sortBy,
                        onValueChange = { editor.sortBy = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Сортировка") },
                        placeholder = { Text("created") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editor.sortOrder,
                        onValueChange = { editor.sortOrder = it },
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
                                id = editor.editingId,
                                name = editor.sourceName,
                                searchUrlTemplate = editor.sourceUrl,
                                protocol = editor.protocol,
                                apiKey = editor.apiKey,
                                username = editor.username,
                                password = editor.password,
                                cookie = editor.cookie,
                                categories = editor.categories,
                                tags = editor.tags,
                                freeleechOnly = editor.freeleechOnly,
                                sortBy = editor.sortBy,
                                sortOrder = editor.sortOrder
                            )
                        )
                        if (saved) editor.clearIndexerEditor()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (editor.editingId.isBlank()) "Добавить индексатор" else "Сохранить изменения")
                }
                if (editor.editingId.isNotBlank()) {
                    OutlinedButton(onClick = { editor.clearIndexerEditor() }, modifier = Modifier.fillMaxWidth()) {
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
                    OutlinedButton(onClick = { onExportIndexers() }, modifier = Modifier.weight(1f)) {
                        Text("Экспорт JSON")
                    }
                    OutlinedButton(onClick = { onImportIndexers() }, modifier = Modifier.weight(1f)) {
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
                                onClick = { viewModel.searchOneIndexer(indexer.id, editor.manualQuery) },
                                enabled = editor.manualQuery.isNotBlank() && !searchingSources
                            ) { Text("Пробный поиск") }
                            OutlinedButton(onClick = { editor.editIndexer(indexer) }) { Text("Изменить") }
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
                    value = editor.manualQuery,
                    onValueChange = { editor.manualQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название фильма, сериала или релиза") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.searchIndexers(editor.manualQuery) })
                )
                Button(
                    onClick = { viewModel.searchIndexers(editor.manualQuery) },
                    enabled = !searchingSources && editor.manualQuery.isNotBlank(),
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
                    "Экспорт JSON содержит адреса и настройки, но не API key, пароль или cookie. После импорта добавьте секреты заново. Секреты в локальном хранилище шифруются через Android Keystore. Используйте только источники, к которым у вас есть законный доступ. Некоторые сайты требуют собственный браузерный вход, CAPTCHA или интерактивную 2FA — такие схемы пока не автоматизируются; для них можно добавить вручную выданный editor.cookie.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }            }


}
