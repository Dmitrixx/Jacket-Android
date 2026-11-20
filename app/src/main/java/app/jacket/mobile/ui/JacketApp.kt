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
            onAddWebsite = {
                viewModel.closeDetails()
                showSettings = true
            },
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
internal fun MetadataPill(text: String, accent: Boolean = false) {
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
internal fun SectionHeading(title: String, action: String, onAction: () -> Unit) {
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
internal fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun MessageCard(
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

internal fun openWebsiteSearch(
    context: android.content.Context,
    provider: WebSearchProvider,
    query: String
) {
    runCatching {
        val url = WebSearchUrlBuilder.build(provider.searchUrlTemplate, query)
        openExternal(context, url)
    }.onFailure {
        Toast.makeText(context, "Не удалось открыть поиск: ${it.message}", Toast.LENGTH_LONG).show()
    }
}

internal fun openExternal(context: android.content.Context, url: String) {
    runCatching {
        val uri = Uri.parse(url)
        require(uri.scheme in setOf("https", "magnet")) { "Небезопасная ссылка" }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, "Открыть через…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "Не удалось открыть ссылку: ${it.message}", Toast.LENGTH_LONG).show()
    }
}

internal fun formatRate(bytesPerSecond: Int): String = when {
    bytesPerSecond >= 1024 * 1024 -> "%.1f МБ/с".format(bytesPerSecond / (1024.0 * 1024.0))
    bytesPerSecond >= 1024 -> "%.0f КБ/с".format(bytesPerSecond / 1024.0)
    else -> "$bytesPerSecond Б/с"
}
