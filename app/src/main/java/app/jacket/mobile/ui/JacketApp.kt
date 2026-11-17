package app.jacket.mobile.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.jacket.mobile.data.CatalogItem
import app.jacket.mobile.data.MediaKind
import app.jacket.mobile.data.MediaSection
import app.jacket.mobile.data.SourceResult
import app.jacket.mobile.data.formatBytes
import app.jacket.mobile.torrent.TorrentDownloadService
import app.jacket.mobile.torrent.TorrentEngine

private const val TAB_HOME = 0
private const val TAB_MOVIES = 1
private const val TAB_SERIES = 2
private const val TAB_SEARCH = 3
private const val TAB_LIBRARY = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JacketApp(viewModel: MainViewModel) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_HOME) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    var playerUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var playerTitle by rememberSaveable { mutableStateOf("") }

    val details by viewModel.selectedDetails.collectAsState()
    val loading by viewModel.isLoading.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadHome() }

    if (playerUrl != null) {
        JacketPlayerScreen(title = playerTitle, streamUrl = playerUrl.orEmpty()) {
            playerUrl = null
        }
        return
    }

    if (showSettings) {
        SettingsScreen(
            viewModel = viewModel,
            onBack = { showSettings = false }
        )
        return
    }

    if (showDownloads) {
        DownloadsScreen(onBack = { showDownloads = false })
        return
    }

    if (details != null) {
        DetailScreen(
            viewModel = viewModel,
            onBack = viewModel::closeDetails,
            onPlay = { title, url ->
                playerTitle = title
                playerUrl = url
            }
        )
        return
    }

    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            TAB_HOME -> viewModel.loadHome()
            TAB_MOVIES -> viewModel.loadMovies()
            TAB_SERIES -> viewModel.loadSeries()
            TAB_SEARCH -> Unit
            TAB_LIBRARY -> viewModel.refreshFavoritesAndHistory()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("JACKET", fontWeight = FontWeight.Black)
                        Text(
                            "КИНО • КАТАЛОГ • ИСТОЧНИКИ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDownloads = true }) {
                        Icon(Icons.Default.Download, contentDescription = "Загрузки")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        },
        bottomBar = {
            MainNavigationBar(
                selectedTab = selectedTab,
                onSelect = { selectedTab = it }
            )
        }
    ) { padding ->
        when (selectedTab) {
            TAB_HOME -> HomeScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
                onOpen = viewModel::openItem,
                onShowMovies = { selectedTab = TAB_MOVIES }
            )
            TAB_MOVIES -> CatalogGridScreen(
                title = "Фильмы",
                items = viewModel.items.collectAsState().value,
                loading = loading,
                message = viewModel.message.collectAsState().value,
                modifier = Modifier.padding(padding),
                onOpen = viewModel::openItem
            )
            TAB_SERIES -> CatalogGridScreen(
                title = "Сериалы",
                items = viewModel.items.collectAsState().value,
                loading = loading,
                message = viewModel.message.collectAsState().value,
                modifier = Modifier.padding(padding),
                onOpen = viewModel::openItem
            )
            TAB_SEARCH -> SearchScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
                onOpen = viewModel::openItem
            )
            else -> LibraryScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
                onOpen = viewModel::openItem,
                onDownloads = { showDownloads = true }
            )
        }
    }
}

@Composable
private fun MainNavigationBar(
    selectedTab: Int,
    onSelect: (Int) -> Unit
) {
    NavigationBar {
        NavigationBarItem(
            selected = selectedTab == TAB_HOME,
            onClick = { onSelect(TAB_HOME) },
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text("Главная") }
        )
        NavigationBarItem(
            selected = selectedTab == TAB_MOVIES,
            onClick = { onSelect(TAB_MOVIES) },
            icon = { Icon(Icons.Default.Movie, contentDescription = null) },
            label = { Text("Фильмы") }
        )
        NavigationBarItem(
            selected = selectedTab == TAB_SERIES,
            onClick = { onSelect(TAB_SERIES) },
            icon = { Icon(Icons.Default.Tv, contentDescription = null) },
            label = { Text("Сериалы") }
        )
        NavigationBarItem(
            selected = selectedTab == TAB_SEARCH,
            onClick = { onSelect(TAB_SEARCH) },
            icon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text("Поиск") }
        )
        NavigationBarItem(
            selected = selectedTab == TAB_LIBRARY,
            onClick = { onSelect(TAB_LIBRARY) },
            icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
            label = { Text("Моё") }
        )
    }
}

@Composable
private fun HomeScreen(
    viewModel: MainViewModel,
    modifier: Modifier,
    onOpen: (CatalogItem) -> Unit,
    onShowMovies: () -> Unit
) {
    val sections by viewModel.homeSections.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()
    val isError by viewModel.isError.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (sections.firstOrNull()?.items?.isNotEmpty() == true) {
            item {
                HeroCard(
                    item = sections.first().items.first(),
                    onClick = { onOpen(sections.first().items.first()) }
                )
            }
        }

        item {
            SectionHeading(
                title = "Каталог",
                action = "Все фильмы",
                onAction = onShowMovies
            )
        }

        if (loading && sections.isEmpty()) {
            item { LoadingState() }
        }

        if (message != null && sections.isEmpty()) {
            item { MessageCard(message.orEmpty(), isError) }
        }

        sections.forEach { section ->
            item(key = section.title) {
                MediaShelf(section = section, onOpen = onOpen)
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Источники прямо в приложении", fontWeight = FontWeight.Bold)
                    Text(
                        "Jacket использует собственный каталог Internet Archive и ваши прямые RSS/Atom-индексаторы. Отдельный сервер Jackett не нужен.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCard(item: CatalogItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box {
            RemotePoster(
                url = item.backdropUrl ?: item.posterUrl,
                modifier = Modifier.fillMaxWidth().height(190.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color(0xFF07101C))))
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("РЕКОМЕНДУЕМ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White, maxLines = 2)
                Text(
                    listOfNotNull(item.year.takeIf { it != "Год неизвестен" }, item.kind.label).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
        }
    }
}

@Composable
private fun MediaShelf(
    section: MediaSection,
    onOpen: (CatalogItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            section.title,
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(section.items, key = { it.id }) { item ->
                MediaCard(item = item, onClick = { onOpen(item) })
            }
        }
    }
}

@Composable
private fun MediaCard(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(142.dp).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box {
            RemotePoster(
                url = item.posterUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium),
                contentDescription = item.title
            )
            if (item.rating != null && item.rating > 0.0) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        String.format("%.1f", item.rating),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
        Text(
            item.year,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CatalogGridScreen(
    title: String,
    items: List<CatalogItem>,
    loading: Boolean,
    message: String?,
    modifier: Modifier,
    onOpen: (CatalogItem) -> Unit
) {
    Column(modifier.fillMaxSize()) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        if (loading && items.isEmpty()) {
            LoadingState()
        } else if (items.isEmpty()) {
            MessageCard(message ?: "Каталог пуст", false)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                gridItems(items, key = { it.id }) { item ->
                    MediaCard(item, onClick = { onOpen(item) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(
    viewModel: MainViewModel,
    modifier: Modifier,
    onOpen: (CatalogItem) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val items by viewModel.items.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()
    val isError by viewModel.isError.collectAsState()

    Column(modifier.fillMaxSize()) {
        Text(
            "Найти фильм или сериал",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                label = { Text("Название") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) })
            )
            Button(onClick = { viewModel.search(query) }, enabled = query.isNotBlank() && !loading) {
                Icon(Icons.Default.Search, contentDescription = "Найти")
            }
        }
        if (message != null) {
            MessageCard(message.orEmpty(), isError, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (loading && items.isEmpty()) {
            LoadingState()
        } else if (items.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                gridItems(items, key = { it.id }) { item ->
                    MediaCard(item, onClick = { onOpen(item) }, modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            Text(
                "Поиск выполняется по каталогу. Для расширения поиска добавьте прямые RSS/Atom-индексаторы в настройках.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun LibraryScreen(
    viewModel: MainViewModel,
    modifier: Modifier,
    onOpen: (CatalogItem) -> Unit,
    onDownloads: () -> Unit
) {
    val favorites by viewModel.favorites.collectAsState()
    val history by viewModel.history.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Моя библиотека", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Избранное и история просмотров", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDownloads) {
                    Icon(Icons.Default.Download, contentDescription = "Загрузки")
                }
            }
        }
        item {
            MediaShelf(
                section = MediaSection("Избранное", favorites),
                onOpen = onOpen
            )
        }
        item {
            MediaShelf(
                section = MediaSection("Недавно открывали", history),
                onOpen = onOpen
            )
        }
        if (favorites.isEmpty() && history.isEmpty()) {
            item {
                MessageCard("Добавляйте фильмы в избранное и открывайте карточки, чтобы они появились здесь.", false)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onPlay: (String, String) -> Unit
) {
    val details by viewModel.selectedDetails.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val sources by viewModel.sourceResults.collectAsState()
    val searchingSources by viewModel.isSearchingSources.collectAsState()
    val message by viewModel.message.collectAsState()
    val isError by viewModel.isError.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(details?.item?.title ?: "Карточка фильма", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    details?.item?.let { item ->
                        IconButton(onClick = { viewModel.toggleFavorite(item) }) {
                            Icon(
                                if (favorites.any { it.id == item.id }) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Избранное"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (loading && details == null) {
            LoadingState(Modifier.padding(padding))
        } else if (details == null) {
            MessageCard("Карточка недоступна", true, Modifier.padding(padding))
        } else {
            val item = details!!.item
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RemotePoster(
                    url = item.backdropUrl ?: item.posterUrl,
                    modifier = Modifier.fillMaxWidth().height(210.dp)
                )
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(item.year.takeIf { it != "Год неизвестен" }, item.kind.label, item.rating?.let { "★ %.1f".format(it) }, details!!.runtime)
                            .joinToString("  •  "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (details!!.creator != null) Text("Автор: ${details!!.creator}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        details!!.description.ifBlank { "Описание отсутствует." },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!details!!.licenseUrl.isNullOrBlank()) {
                        Text(
                            "Лицензия указана источником: ${details!!.licenseUrl}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (details!!.streamUrl != null) {
                        Button(
                            onClick = { onPlay(item.title, details!!.streamUrl!!) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Смотреть в Jacket")
                        }
                    }

                    if (details!!.torrentUrl != null) {
                        OutlinedButton(
                            onClick = { openExternal(context, details!!.torrentUrl!!) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Открыть torrent-файл")
                        }
                    }

                    if (!details!!.pageUrl.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = { openExternal(context, details!!.pageUrl!!) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Страница источника")
                        }
                    }

                    Button(
                        onClick = { viewModel.findSources(item) },
                        enabled = !searchingSources,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (searchingSources) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (searchingSources) "Ищем источники…" else "Найти источники")
                    }
                }

                if (message != null) {
                    MessageCard(message.orEmpty(), isError, Modifier.padding(horizontal = 16.dp))
                }

                if (sources.isNotEmpty()) {
                    Text(
                        "Доступные источники",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        sources.forEach { source ->
                            SourceCard(
                                source = source,
                                onPlay = { url -> onPlay(source.title, url) },
                                onMagnet = { magnet -> TorrentDownloadService.add(context, magnet, source.title) },
                                onTorrent = { url -> openExternal(context, url) },
                                onPage = { url -> openExternal(context, url) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SourceCard(
    source: SourceResult,
    onPlay: (String) -> Unit,
    onMagnet: (String) -> Unit,
    onTorrent: (String) -> Unit,
    onPage: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(source.title, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(source.providerName, source.sizeBytes?.let(::formatBytes), source.seeders?.let { "Сиды: $it" })
                    .joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    source.streamUrl != null -> OutlinedButton(onClick = { onPlay(source.streamUrl) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("Смотреть")
                    }
                    source.magnetUri != null -> OutlinedButton(onClick = { onMagnet(source.magnetUri) }) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Text("Скачать")
                    }
                    source.downloadUrl != null -> OutlinedButton(onClick = { onTorrent(source.downloadUrl) }) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Text("Torrent")
                    }
                    source.pageUrl != null -> OutlinedButton(onClick = { onPage(source.pageUrl) }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                        Text("Открыть")
                    }
                }
                if (source.pageUrl != null && source.streamUrl != null) {
                    IconButton(onClick = { onPage(source.pageUrl) }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "Страница источника")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val downloads by TorrentEngine.snapshots.collectAsState()

    LaunchedEffect(Unit) { TorrentDownloadService.restore(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Загрузки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { TorrentDownloadService.restore(context) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                }
            )
        }
    ) { padding ->
        if (downloads.isEmpty()) {
            MessageCard(
                "Загрузок пока нет. Откройте карточку источника с magnet-ссылкой и добавьте её в очередь.",
                false,
                Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(downloads, key = { it.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.name, fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(item.progress, modifier = Modifier.fillMaxWidth())
                            Text(
                                "${(item.progress * 100).toInt()}% • ${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "↓ ${formatRate(item.downloadBytesPerSecond)}   ↑ ${formatRate(item.uploadBytesPerSecond)}   сиды ${item.seeds} • пиры ${item.peers}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        if (item.paused) TorrentDownloadService.resume(context, item.id)
                                        else TorrentDownloadService.pause(context, item.id)
                                    }
                                ) {
                                    Icon(if (item.paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                                    Text(if (item.paused) "Продолжить" else "Пауза")
                                }
                                IconButton(onClick = { TorrentDownloadService.remove(context, item.id) }) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val currentToken by viewModel.tmdbToken.collectAsState()
    val indexers by viewModel.customIndexers.collectAsState()
    val context = LocalContext.current
    var token by remember(currentToken) { mutableStateOf(currentToken) }
    var sourceName by rememberSaveable { mutableStateOf("") }
    var sourceUrl by rememberSaveable { mutableStateOf("") }
    val message by viewModel.message.collectAsState()
    val isError by viewModel.isError.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки и источники") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
            item {
                Text("Каталог фильмов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Без ключей работает открытый каталог Internet Archive. Дополнительно можно подключить TMDB, чтобы получить популярные фильмы, сериалы, рейтинги, постеры и описания.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("TMDB Read Access Token (необязательно)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    minLines = 2
                )
                Button(
                    onClick = { viewModel.saveTmdbToken(token) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сохранить токен каталога") }
                Text(
                    "Токен хранится в Android Keystore. Получить его можно в настройках API своего аккаунта themoviedb.org. TMDB предоставляет метаданные и изображения, но не видеопотоки. Это приложение не связано с TMDB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Text("Встроенные индексаторы", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Internet Archive", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Прямой поиск открытых медиа и файлов. Сервер Jackett не требуется. Для встроенного воспроизведения проверяется наличие записи о лицензии.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                Text("Добавить прямой RSS/Atom-индексатор", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Укажите HTTPS-ссылку на RSS/Atom-поиск конкретного источника. В URL обязательно должен быть маркер {query}, который приложение заменит поисковой фразой. Это прямое подключение к ленте, не к Jackett API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = sourceName,
                    onValueChange = { sourceName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название источника") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = sourceUrl,
                    onValueChange = { sourceUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS URL с {query}") },
                    placeholder = { Text("https://example.org/rss?q={query}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    minLines = 2
                )
                Button(
                    onClick = {
                        viewModel.addIndexer(sourceName, sourceUrl)
                        sourceName = ""
                        sourceUrl = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить источник")
                }
            }
            if (indexers.isNotEmpty()) {
                item {
                    Text("Мои индексаторы (${indexers.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                items(indexers, key = { it.id }) { indexer ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(indexer.name, fontWeight = FontWeight.SemiBold)
                                Text(indexer.searchUrlTemplate, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                            IconButton(onClick = { viewModel.removeIndexer(indexer.id) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить источник")
                            }
                        }
                    }
                }
            }
            if (message != null) {
                item { MessageCard(message.orEmpty(), isError) }
            }
            item {
                Text(
                    "Подключайте только источники, которыми вы вправе пользоваться, и соблюдайте их правила и лицензии. Jacket не включает списки пиратских трекеров и не обходит платный доступ.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            action,
            modifier = Modifier.clickable(onClick = onAction).padding(6.dp),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MessageCard(
    message: String,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.10f),
        contentColor = color,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun openExternal(context: android.content.Context, url: String) {
    runCatching {
        val uri = Uri.parse(url)
        require(uri.scheme in setOf("https", "magnet")) { "Небезопасная ссылка" }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, "Открыть через…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "Не удалось открыть ссылку: ${it.message}", Toast.LENGTH_LONG).show()
    }
}

private fun formatRate(bytesPerSecond: Int): String = when {
    bytesPerSecond >= 1024 * 1024 -> "%.1f МБ/с".format(bytesPerSecond / (1024.0 * 1024.0))
    bytesPerSecond >= 1024 -> "%.0f КБ/с".format(bytesPerSecond / 1024.0)
    else -> "$bytesPerSecond Б/с"
}
