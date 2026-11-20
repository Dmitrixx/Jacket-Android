@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
internal fun HomeScreen(
    viewModel: MainViewModel,
    modifier: Modifier,
    onOpen: (CatalogItem) -> Unit,
    onShowMovies: () -> Unit
) {
    val sections by viewModel.homeSections.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()
    val isError by viewModel.isError.collectAsState()
    val context = LocalContext.current
    val playbackStore = remember(context) { PlaybackSessionStore(context) }
    val playbackSessions by playbackStore.sessions.collectAsState()
    val isTv = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (playbackSessions.isNotEmpty()) {
            item(key = "continue-watching") {
                ContinueWatchingShelf(
                    sessions = playbackSessions,
                    isTv = isTv,
                    onResume = { session -> TorrentDownloadService.play(context, session.infoHash, session.fileIndex) },
                    onRemove = { session -> PlaybackProgressStore(context).clear(PlaybackProgressStore.torrentKey(session.infoHash, session.fileIndex)) }
                )
            }
        }
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
    val context = LocalContext.current
    val isTv = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isTv && focused) 1.018f else 1f, label = "hero-focus")
    Card(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = if (isTv) 28.dp else 16.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .border(if (isTv && focused) 3.dp else 0.dp,
                if (isTv && focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                MaterialTheme.shapes.extraLarge)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = if (focused) 10.dp else 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val height = if (isTv) 370.dp else 238.dp
        Box {
            RemotePoster(url = item.backdropUrl ?: item.posterUrl, modifier = Modifier.fillMaxWidth().height(height))
            Box(Modifier.fillMaxWidth().height(height).background(
                Brush.verticalGradient(0f to Color.Transparent, 0.38f to Color(0x33070A10), 1f to Color(0xF5070A10))
            ))
            Row(Modifier.align(Alignment.TopStart).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.small) {
                    Text("JACKET • ВЫБОР", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onPrimary)
                }
                if (item.rating != null && item.rating > 0.0) {
                    Surface(color = Color(0xD9171D27), shape = MaterialTheme.shapes.small) {
                        Text("★ %.1f".format(item.rating), Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("РЕКОМЕНДУЕМ ПОСМОТРЕТЬ", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(item.title,
                    style = if (isTv) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 2)
                Text(listOfNotNull(item.year.takeIf { it != "Год неизвестен" }, item.kind.label).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD8DEE9))
                Surface(color = Color(0xE6FFFFFF), shape = MaterialTheme.shapes.large) {
                    Text("Подробнее  →", Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = Color(0xFF111722), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingShelf(
    sessions: List<PlaybackSession>,
    isTv: Boolean,
    onResume: (PlaybackSession) -> Unit,
    onRemove: (PlaybackSession) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Продолжить просмотр", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Ваш прогресс сохранён на этом устройстве", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(sessions.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sessions, key = { it.key }) { session ->
                var focused by remember { mutableStateOf(false) }
                val progress = session.progress
                Card(
                    modifier = Modifier.width(if (isTv) 320.dp else 270.dp).height(if (isTv) 190.dp else 176.dp)
                        .onFocusChanged { focused = it.isFocused }
                        .border(if (isTv && focused) 3.dp else 0.dp,
                            if (isTv && focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                            MaterialTheme.shapes.large)
                        .clickable { onResume(session) },
                    shape = MaterialTheme.shapes.large,
                    elevation = CardDefaults.cardElevation(defaultElevation = if (focused) 8.dp else 2.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().background(
                            Brush.linearGradient(listOf(Color(0xFF29384B), Color(0xFF151B27), Color(0xFF10151E)))
                        ))
                        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("СМОТРЕЛИ РАНЬШЕ", Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold)
                                Text(if (session.durationMs > 0L) "${(progress * 100).toInt()}%" else "СОХРАНЕНО",
                                    style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                            Text(session.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                color = Color.White, maxLines = 2)
                            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(5.dp),
                                color = MaterialTheme.colorScheme.primary, trackColor = Color(0xFF465163))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val left = (session.durationMs - session.positionMs).coerceAtLeast(0L) / 60_000L
                                Text(if (session.durationMs > 0L) "Осталось ~${left / 60} ч ${left % 60} мин"
                                    else "Продолжить с сохранённой позиции",
                                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFD0D7E2), maxLines = 1)
                                IconButton(onClick = { onRemove(session) }) {
                                    Icon(Icons.Default.DeleteOutline, "Убрать из продолжения", tint = Color(0xFFD0D7E2))
                                }
                                IconButton(onClick = { onResume(session) }) {
                                    Icon(Icons.Default.PlayArrow, "Продолжить просмотр", tint = MaterialTheme.colorScheme.primary)
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
private fun MediaCard(item: CatalogItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isTv = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isTv && focused) 1.045f else 1f, label = "poster-focus")
    Column(
        modifier = modifier.width(if (isTv) 194.dp else 148.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .border(if (isTv && focused) 3.dp else 0.dp,
                if (isTv && focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                MaterialTheme.shapes.large)
            .clickable(onClick = onClick).padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box {
            RemotePoster(url = item.posterUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium),
                contentDescription = item.title)
            if (item.rating != null && item.rating > 0.0) {
                Surface(Modifier.align(Alignment.TopEnd).padding(7.dp), color = Color(0xE610141D),
                    shape = MaterialTheme.shapes.small) {
                    Text("★ %.1f".format(item.rating), Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 2, minLines = 2)
        Text(listOfNotNull(item.year.takeIf { it != "Год неизвестен" }, item.kind.label).joinToString(" • "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
internal fun CatalogGridScreen(
    title: String,
    items: List<CatalogItem>,
    loading: Boolean,
    message: String?,
    modifier: Modifier,
    onOpen: (CatalogItem) -> Unit
) {
    val isTv = (LocalContext.current.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
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
                columns = if (isTv) GridCells.Adaptive(minSize = 190.dp) else GridCells.Fixed(2),
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
internal fun SearchScreen(
    viewModel: MainViewModel,
    modifier: Modifier,
    onOpen: (CatalogItem) -> Unit
) {
    val context = LocalContext.current
    val isTv = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    var query by rememberSaveable { mutableStateOf("") }
    val webProviders by viewModel.webSearchProviders.collectAsState()
    val items by viewModel.items.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()
    val isError by viewModel.isError.collectAsState()

    LaunchedEffect(query) {
        kotlinx.coroutines.delay(400)
        if (query.trim().length >= 2) viewModel.search(query.trim())
        else viewModel.clearSearch()
    }

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
        val activeWebProviders = webProviders.filter { it.enabled }
        if (query.trim().length >= 2 && activeWebProviders.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Искать также на сайтах",
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(activeWebProviders, key = { "search-${it.id}" }) { provider ->
                        OutlinedButton(
                            onClick = { openWebsiteSearch(context, provider, query.trim()) }
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(provider.name, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (message != null) {
            MessageCard(message.orEmpty(), isError, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (loading && items.isEmpty()) {
            LoadingState()
        } else if (items.isNotEmpty()) {
            LazyVerticalGrid(
                columns = if (isTv) GridCells.Adaptive(minSize = 190.dp) else GridCells.Fixed(2),
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
                "Поиск выполняется по каталогу. Для расширения поиска добавьте RSS/Atom или Torznab-индексаторы в настройках.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
internal fun LibraryScreen(
    viewModel: MainViewModel,
    modifier: Modifier,
    onOpen: (CatalogItem) -> Unit,
    onDownloads: () -> Unit
) {
    val favorites by viewModel.favorites.collectAsState()
    val history by viewModel.history.collectAsState()
    val context = LocalContext.current
    val playbackStore = remember(context) { PlaybackSessionStore(context) }
    val playbackSessions by playbackStore.sessions.collectAsState()
    val isTv = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

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
        if (playbackSessions.isNotEmpty()) {
            item(key = "library-continue-watching") {
                ContinueWatchingShelf(
                    sessions = playbackSessions, isTv = isTv,
                    onResume = { session -> TorrentDownloadService.play(context, session.infoHash, session.fileIndex) },
                    onRemove = { session -> PlaybackProgressStore(context).clear(PlaybackProgressStore.torrentKey(session.infoHash, session.fileIndex)) }
                )
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
