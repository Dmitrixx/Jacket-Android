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
import app.jacket.mobile.data.IndexerConfig
import app.jacket.mobile.data.IndexerProtocol
import app.jacket.mobile.data.ReleaseHeuristics
import app.jacket.mobile.data.MediaKind
import app.jacket.mobile.data.MediaSection
import app.jacket.mobile.data.SourceResult
import app.jacket.mobile.data.formatBytes
import app.jacket.mobile.torrent.TorrentDownloadService
import app.jacket.mobile.torrent.TorrentEngine
import app.jacket.mobile.torrent.TorrentPlaybackBus
import app.jacket.mobile.torrent.PlaybackProgressStore
import app.jacket.mobile.torrent.PlaybackSession
import app.jacket.mobile.torrent.PlaybackSessionStore
import app.jacket.mobile.torrent.TorrentQueueStore
import java.io.File

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
    var playerProgressKey by rememberSaveable { mutableStateOf<String?>(null) }
    var playerInitialPosition by rememberSaveable { mutableLongStateOf(0L) }
    var playerSubtitles by remember { mutableStateOf(emptyList<app.jacket.mobile.torrent.PlaybackSubtitle>()) }
    var isTorrentPlayback by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val torrentPlayback by TorrentPlaybackBus.active.collectAsState()
    val details by viewModel.selectedDetails.collectAsState()
    val isTv = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    val loading by viewModel.isLoading.collectAsState()

    LaunchedEffect(torrentPlayback) {
        torrentPlayback?.let { request ->
            playerTitle = request.title
            playerUrl = request.url
            playerProgressKey = request.progressKey
            playerInitialPosition = request.initialPositionMs
            playerSubtitles = request.subtitles
            isTorrentPlayback = true
        }
    }

    fun closePlayer() {
        if (isTorrentPlayback) TorrentDownloadService.stopPlayback(context)
        playerUrl = null
        playerTitle = ""
        playerProgressKey = null
        playerInitialPosition = 0L
        playerSubtitles = emptyList()
        isTorrentPlayback = false
    }

    // Android TV remotes dispatch Back through the system dispatcher; unwind the current screen first.
    BackHandler(enabled = playerUrl != null || showSettings || showDownloads || details != null) {
        when {
            playerUrl != null -> closePlayer()
            showSettings -> showSettings = false
            showDownloads -> showDownloads = false
            details != null -> viewModel.closeDetails()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadHome()
        if (TorrentQueueStore(context).all().isNotEmpty()) TorrentDownloadService.restore(context)
    }

    if (playerUrl != null) {
        JacketPlayerScreen(
            title = playerTitle,
            streamUrl = playerUrl.orEmpty(),
            progressKey = playerProgressKey,
            initialPositionMs = playerInitialPosition,
            subtitles = playerSubtitles,
            onBack = ::closePlayer
        )
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
                playerProgressKey = PlaybackProgressStore.urlKey(url)
                playerInitialPosition = PlaybackProgressStore(context).position(playerProgressKey!!)
                playerSubtitles = emptyList()
                isTorrentPlayback = false
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(42.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            Color(0xFFE99A3C),
                                            Color(0xFF9D6AF0)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("J", style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black, color = Color(0xFF17120D))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("JACKET", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                            Text("КИНО  •  СЕРИАЛЫ  •  ТОРРЕНТЫ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.6.sp)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showDownloads = true }) {
                        Icon(Icons.Default.Download, contentDescription = "Загрузки", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        },
        bottomBar = {
            if (!isTv) {
                MainNavigationBar(
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it }
                )
            }
        }
    ) { padding ->
        if (isTv) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                MainNavigationRail(
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it }
                )
                MainTabContent(
                    selectedTab = selectedTab,
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    onShowMovies = { selectedTab = TAB_MOVIES },
                    onDownloads = { showDownloads = true }
                )
            }
        } else {
            MainTabContent(
                selectedTab = selectedTab,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
                onShowMovies = { selectedTab = TAB_MOVIES },
                onDownloads = { showDownloads = true }
            )
        }
    }
}

@Composable
private fun MainNavigationRail(
    selectedTab: Int,
    onSelect: (Int) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        NavigationRail(containerColor = Color.Transparent) {
            NavigationRailItem(
                selected = selectedTab == TAB_HOME,
                onClick = { onSelect(TAB_HOME) },
                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                label = { Text("Главная") }
            )
            NavigationRailItem(
                selected = selectedTab == TAB_MOVIES,
                onClick = { onSelect(TAB_MOVIES) },
                icon = { Icon(Icons.Default.Movie, contentDescription = null) },
                label = { Text("Фильмы") }
            )
            NavigationRailItem(
                selected = selectedTab == TAB_SERIES,
                onClick = { onSelect(TAB_SERIES) },
                icon = { Icon(Icons.Default.Tv, contentDescription = null) },
                label = { Text("Сериалы") }
            )
            NavigationRailItem(
                selected = selectedTab == TAB_SEARCH,
                onClick = { onSelect(TAB_SEARCH) },
                icon = { Icon(Icons.Default.Search, contentDescription = null) },
                label = { Text("Поиск") }
            )
            NavigationRailItem(
                selected = selectedTab == TAB_LIBRARY,
                onClick = { onSelect(TAB_LIBRARY) },
                icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                label = { Text("Моё") }
            )
        }
    }
}

@Composable
private fun MainTabContent(
    selectedTab: Int,
    viewModel: MainViewModel,
    modifier: Modifier,
    onShowMovies: () -> Unit,
    onDownloads: () -> Unit
) {
    val loading by viewModel.isLoading.collectAsState()
    when (selectedTab) {
        TAB_HOME -> HomeScreen(
            viewModel = viewModel,
            modifier = modifier,
            onOpen = viewModel::openItem,
            onShowMovies = onShowMovies
        )
        TAB_MOVIES -> CatalogGridScreen(
            title = "Фильмы",
            items = viewModel.items.collectAsState().value,
            loading = loading,
            message = viewModel.message.collectAsState().value,
            modifier = modifier,
            onOpen = viewModel::openItem
        )
        TAB_SERIES -> CatalogGridScreen(
            title = "Сериалы",
            items = viewModel.items.collectAsState().value,
            loading = loading,
            message = viewModel.message.collectAsState().value,
            modifier = modifier,
            onOpen = viewModel::openItem
        )
        TAB_SEARCH -> SearchScreen(
            viewModel = viewModel,
            modifier = modifier,
            onOpen = viewModel::openItem
        )
        else -> LibraryScreen(
            viewModel = viewModel,
            modifier = modifier,
            onOpen = viewModel::openItem,
            onDownloads = onDownloads
        )
    }
}

@Composable
private fun MainNavigationBar(
    selectedTab: Int,
    onSelect: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 5.dp,
        shadowElevation = 8.dp
    ) {
        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
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
private fun CatalogGridScreen(
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
private fun SearchScreen(
    viewModel: MainViewModel,
    modifier: Modifier,
    onOpen: (CatalogItem) -> Unit
) {
    val isTv = (LocalContext.current.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    var query by rememberSaveable { mutableStateOf("") }
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
private fun LibraryScreen(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onPlay: (String, String) -> Unit
) {
    val details by viewModel.selectedDetails.collectAsState()
    val tmdbToken by viewModel.tmdbToken.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val sources by viewModel.sourceResults.collectAsState()
    val searchingSources by viewModel.isSearchingSources.collectAsState()
    val seasons by viewModel.seasons.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val selectedSeason by viewModel.selectedSeasonNumber.collectAsState()
    val loadingEpisodes by viewModel.isLoadingEpisodes.collectAsState()
    val message by viewModel.message.collectAsState()
    val isError by viewModel.isError.collectAsState()
    val context = LocalContext.current
    val indexerErrors by viewModel.indexerErrors.collectAsState()
    var minSeeders by rememberSaveable { mutableStateOf("0") }
    var qualityFilter by rememberSaveable { mutableStateOf("Все") }
    var hdrOnly by rememberSaveable { mutableStateOf(false) }
    var magnetOnly by rememberSaveable { mutableStateOf(false) }
    var torrentOnly by rememberSaveable { mutableStateOf(false) }
    val minSeedersValue = minSeeders.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val filteredSources = sources.filter { source ->
        val release = ReleaseHeuristics.classify(source.title)
        (source.seeders ?: 0) >= minSeedersValue &&
            (qualityFilter == "Все" || release.quality == qualityFilter) &&
            (!hdrOnly || release.hdr || release.dolbyVision) &&
            (!magnetOnly || source.magnetUri != null) &&
            (!torrentOnly || source.downloadUrl != null)
    }
    val isTv = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

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
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .height(if (isTv) 390.dp else 300.dp)
                        .clip(RoundedCornerShape(28.dp))
                ) {
                    RemotePoster(url = item.backdropUrl ?: item.posterUrl, modifier = Modifier.fillMaxSize())
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color(0x22070A10),
                                0.34f to Color(0x44070A10),
                                1f to Color(0xF5070A10)
                            )
                        )
                    )
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .padding(if (isTv) 28.dp else 20.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        MetadataPill(
                            text = if (item.kind == MediaKind.SERIES) "ОРИГИНАЛЬНАЯ КАРТОЧКА • СЕРИАЛ" else "КАРТОЧКА ФИЛЬМА",
                            accent = true
                        )
                        Text(
                            item.title,
                            style = if (isTv) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Black, color = Color.White, maxLines = 2
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOfNotNull(
                                item.year.takeIf { it != "Год неизвестен" },
                                item.kind.label,
                                item.rating?.let { "★ %.1f".format(it) },
                                details!!.runtime
                            ).forEach { value -> MetadataPill(value) }
                        }
                        Text(
                            details!!.description.ifBlank { "Описание отсутствует." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFE2E7EF),
                            maxLines = if (isTv) 3 else 2
                        )
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (details!!.creator != null) {
                        Text("Автор: ${details!!.creator}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                    if (!details!!.licenseUrl.isNullOrBlank()) {
                        Text(
                            "Лицензия указана источником: ${details!!.licenseUrl}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (item.kind == MediaKind.SERIES) {
                        Spacer(Modifier.height(8.dp))
                        Text("Сезоны и серии", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        when {
                            tmdbToken.isBlank() -> {
                                MessageCard(
                                    "Введите TMDB Read Access Token в настройках, чтобы загружать сезоны и серии.",
                                    false
                                )
                            }
                            seasons.isEmpty() -> {
                                Text(
                                    "Нет данных о сезонах или они ещё загружаются.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            else -> {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(seasons, key = { it.seasonNumber }) { season ->
                                        val selected = selectedSeason == season.seasonNumber
                                        OutlinedButton(
                                            onClick = { viewModel.selectSeason(item, season.seasonNumber) }
                                        ) {
                                            Text(if (selected) "✓ ${season.name}" else season.name)
                                        }
                                    }
                                }
                                if (loadingEpisodes) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(10.dp))
                                        Text("Загружаем серии…")
                                    }
                                } else if (episodes.isEmpty()) {
                                    Text(
                                        "Для выбранного сезона серии не найдены.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    episodes.forEach { episode ->
                                        Card(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (episode.stillUrl != null) {
                                                    RemotePoster(
                                                        url = episode.stillUrl,
                                                        modifier = Modifier.width(if (isTv) 180.dp else 112.dp)
                                                            .aspectRatio(16f / 9f)
                                                    )
                                                }
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(
                                                        "${episode.releaseCode} • ${episode.name}",
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    if (!episode.airDate.isNullOrBlank()) {
                                                        Text(
                                                            episode.airDate,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    if (episode.overview.isNotBlank()) {
                                                        Text(
                                                            episode.overview,
                                                            maxLines = if (isTv) 4 else 3,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    OutlinedButton(
                                                        onClick = { viewModel.findSources(item, episode) },
                                                        enabled = !searchingSources
                                                    ) {
                                                        Icon(Icons.Default.Search, contentDescription = null)
                                                        Spacer(Modifier.width(6.dp))
                                                        Text("Найти эту серию")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
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
                            onClick = { TorrentDownloadService.addTorrentUrl(context, details!!.torrentUrl!!, item.title) },
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

                if (indexerErrors.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Источники с ошибками", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        indexerErrors.forEach { failure ->
                            MessageCard("${failure.indexerName}: ${failure.message}", true)
                        }
                    }
                }

                if (sources.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Доступные раздачи • ${filteredSources.size} из ${sources.size}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = minSeeders,
                                onValueChange = { value -> minSeeders = value.filter(Char::isDigit).take(5) },
                                modifier = Modifier.weight(1f),
                                label = { Text("Мин. сидов") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedButton(onClick = {
                                qualityFilter = when (qualityFilter) {
                                    "Все" -> "720p"
                                    "720p" -> "1080p"
                                    "1080p" -> "2160p"
                                    else -> "Все"
                                }
                            }) { Text(qualityFilter) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { hdrOnly = !hdrOnly }) { Text(if (hdrOnly) "✓ HDR / DV" else "HDR / DV") }
                            OutlinedButton(onClick = {
                                magnetOnly = !magnetOnly
                                if (magnetOnly) torrentOnly = false
                            }) { Text(if (magnetOnly) "✓ Magnet" else "Magnet") }
                            OutlinedButton(onClick = {
                                torrentOnly = !torrentOnly
                                if (torrentOnly) magnetOnly = false
                            }) { Text(if (torrentOnly) "✓ .torrent" else ".torrent") }
                        }
                    }

                    if (filteredSources.isEmpty()) {
                        MessageCard("По этим фильтрам раздачи не найдены. Ослабьте фильтры или сбросьте их.", false, Modifier.padding(horizontal = 16.dp))
                    } else {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            filteredSources.forEach { source ->
                                SourceCard(
                                    source = source,
                                    onPlay = { url -> onPlay(source.title, url) },
                                    onMagnet = { magnet -> TorrentDownloadService.add(context, magnet, source.title) },
                                    onTorrent = { url -> TorrentDownloadService.addTorrentUrl(context, url, source.title, source.indexerId) },
                                    onPage = { url -> openExternal(context, url) }
                                )
                            }
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
    val release = ReleaseHeuristics.classify(source.title)
    val badges = listOfNotNull(
        release.quality,
        release.videoCodec,
        if (release.hdr) "HDR" else null,
        if (release.dolbyVision) "Dolby Vision" else null
    ).plus(release.audioTags).plus(release.sourceTags).distinct()
    val facts = listOfNotNull(
        source.providerName,
        source.category,
        source.sizeBytes?.let(::formatBytes),
        source.seeders?.let { "↑ $it сидов" },
        source.leechers?.let { "↓ $it пиров" }
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
                    Text("РАЗДАЧА", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold)
                }
                Text(source.providerName ?: "Источник", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(source.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 3)
            if (badges.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(badges) { badge -> MetadataPill(badge, accent = true) }
                }
            }
            if (facts.isNotEmpty()) {
                Text(facts.joinToString("  •  "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                when {
                    source.streamUrl != null -> Button(onClick = { onPlay(source.streamUrl) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Смотреть")
                    }
                    source.magnetUri != null -> Button(onClick = { onMagnet(source.magnetUri) }) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Добавить")
                    }
                    source.downloadUrl != null -> Button(onClick = { onTorrent(source.downloadUrl) }) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Загрузить")
                    }
                    source.pageUrl != null -> OutlinedButton(onClick = { onPage(source.pageUrl) }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Открыть")
                    }
                }
                Spacer(Modifier.weight(1f))
                if (source.pageUrl != null) {
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
    val importTorrent = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val directory = File(context.filesDir, "torrent-imports").apply { mkdirs() }
                val output = File.createTempFile("picker_", ".torrent", directory)
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Не удалось открыть выбранный файл" }
                    output.outputStream().use { stream ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= TorrentQueueStore.MAX_TORRENT_BYTES) { "Файл .torrent больше 8 МиБ" }
                            stream.write(buffer, 0, count)
                        }
                    }
                }
                output
            }.onSuccess { file ->
                TorrentDownloadService.addTorrentFile(context, file.absolutePath, file.nameWithoutExtension)
                Toast.makeText(context, ".torrent добавлен во встроенную очередь", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Ошибка импорта: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

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
                    IconButton(onClick = { importTorrent.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }) {
                        Icon(Icons.Default.Add, contentDescription = "Импортировать .torrent")
                    }
                    IconButton(onClick = { TorrentDownloadService.restore(context) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                }
            )
        }
    ) { padding ->
        if (downloads.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MessageCard(
                    "Загрузок пока нет. Добавьте magnet-ссылку из результатов поиска или импортируйте .torrent-файл.",
                    false,
                    Modifier.padding(16.dp)
                )
                OutlinedButton(onClick = { importTorrent.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Импортировать .torrent")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(downloads, key = { it.id }) { item ->
                    var confirmDeleteFiles by remember(item.id) { mutableStateOf(false) }
                    if (confirmDeleteFiles) {
                        AlertDialog(
                            onDismissRequest = { confirmDeleteFiles = false },
                            title = { Text("Удалить скачанные файлы?") },
                            text = { Text("Файлы этой раздачи будут удалены из папки загрузок. Удаление нельзя отменить.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmDeleteFiles = false
                                    TorrentDownloadService.remove(context, item.id, deleteFiles = true)
                                }) { Text("Удалить файлы") }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmDeleteFiles = false }) { Text("Отмена") }
                            }
                        )
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.name, fontWeight = FontWeight.SemiBold)
                            if (item.state == "WAITING_FOR_WIFI") {
                                MessageCard("Ожидает подключения к Wi-Fi. Задача сохранена и начнётся автоматически.", false)
                            } else if (item.state == "QUEUED") {
                                Text("Ожидает запуска", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (item.localStreamPort != null) {
                                Text("Локальный видеосервер: 127.0.0.1:${item.localStreamPort}", style = MaterialTheme.typography.labelSmall)
                            }
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
                            if (item.error != null) {
                                MessageCard(item.error, true)
                            }
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
                                OutlinedButton(
                                    onClick = { TorrentDownloadService.remove(context, item.id, deleteFiles = false) }
                                ) { Text("Убрать из очереди") }
                            }
                            TextButton(
                                onClick = { confirmDeleteFiles = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                                Text("Удалить скачанные файлы…")
                            }
                            if (item.files.isNotEmpty()) {
                                Text("Файлы раздачи (${item.files.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                item.files.forEach { file ->
                                    Column(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(file.path.substringAfterLast('/'), fontWeight = FontWeight.Medium, maxLines = 2)
                                        Text(
                                            "${formatBytes(file.sizeBytes)} • ${(file.progress * 100).toInt()}% загружено",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        LinearProgressIndicator(file.progress, modifier = Modifier.fillMaxWidth())
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    TorrentDownloadService.selectFile(context, item.id, file.index)
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) { Text(if (file.isSelected) "Выбран для просмотра" else "Выбрать файл") }
                                            Button(
                                                onClick = { TorrentDownloadService.play(context, item.id, file.index) },
                                                enabled = !item.paused
                                            ) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                                Text("Смотреть")
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    "Получаем метаданные. Список файлов появится автоматически.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
    val statuses by viewModel.indexerStatuses.collectAsState()
    val sourceResults by viewModel.sourceResults.collectAsState()
    val searchingSources by viewModel.isSearchingSources.collectAsState()
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
                    "Без ключей работает открытый каталог Internet Archive. Дополнительно можно подключить TMDB для популярных фильмов, сериалов, рейтингов и постеров.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("TMDB Read Access Token (необязательно)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    minLines = 2
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.saveTmdbToken(token) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Сохранить токен") }
                    OutlinedButton(
                        onClick = {
                            token = ""
                            viewModel.saveTmdbToken("")
                        }
                    ) { Text("Сбросить") }
                }
                Text(
                    "Токен TMDB хранится в Android Keystore. TMDB предоставляет метаданные и изображения, но не видеопотоки.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Text("Загрузки и сеть", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Лимиты применяются ко всей торрент-сессии. Значение 0 означает отсутствие ограничения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = downloadLimit,
                        onValueChange = { downloadLimit = it.filter(Char::isDigit).take(7) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Загрузка КБ/с") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = uploadLimit,
                        onValueChange = { uploadLimit = it.filter(Char::isDigit).take(7) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Отдача КБ/с") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Только по Wi-Fi", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Новые задачи будут ждать Wi-Fi. Уже запущенные загрузки и просмотр не отключаются автоматически.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                }
                Button(
                    onClick = {
                        TorrentEngine.setRateLimits(
                            context,
                            downloadLimit.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            uploadLimit.toIntOrNull()?.coerceAtLeast(0) ?: 0
                        )
                        TorrentEngine.setWifiOnly(context, wifiOnly)
                        Toast.makeText(context, "Настройки загрузки сохранены", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сохранить настройки загрузки") }
                Text(
                    "Папка загрузок: ${TorrentEngine.downloadDirectory(context).absolutePath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
                Text(
                    if (editingId.isBlank()) "Добавить агрегатор / индексатор" else "Изменить индексатор",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Поддерживаются прямые RSS/Atom и совместимые Torznab/Newznab API. URL должен быть HTTPS и содержать {query}; API-ключ можно указать ниже или в URL как {apikey}.",
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
                Text("Настроенные индексаторы (${indexers.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
            items(indexers, key = { it.id }) { indexer ->
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
            }
        }
    }
}

@Composable
private fun MetadataPill(text: String, accent: Boolean = false) {
    Surface(
        color = if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color(0xC91A2432),
        contentColor = if (accent) MaterialTheme.colorScheme.primary else Color.White,
        shape = RoundedCornerShape(50.dp)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
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
