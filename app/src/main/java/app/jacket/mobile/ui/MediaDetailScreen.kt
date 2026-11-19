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
internal fun DetailScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onAddWebsite: () -> Unit,
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
    val webSearchProviders by viewModel.webSearchProviders.collectAsState()
    var showWebsiteSearch by rememberSaveable { mutableStateOf(false) }
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

                    OutlinedButton(
                        onClick = { showWebsiteSearch = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Поиск на сайтах (${webSearchProviders.count { it.enabled }})")
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
    if (showWebsiteSearch) {
        ModalBottomSheet(onDismissRequest = { showWebsiteSearch = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Источник", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Выберите сайт. Поиск откроется в браузере с названием «${details?.item?.title.orEmpty()}».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val enabledWebProviders = webSearchProviders.filter { it.enabled }
                if (enabledWebProviders.isEmpty()) {
                    MessageCard("Сайты поиска ещё не добавлены или отключены.", false)
                    Button(
                        onClick = {
                            showWebsiteSearch = false
                            onAddWebsite()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Добавить сайт поиска")
                    }
                } else {
                    enabledWebProviders.forEach { provider ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                showWebsiteSearch = false
                                val query = details?.item?.let { media ->
                                    listOfNotNull(
                                        media.title,
                                        media.year.takeIf { it != "Год неизвестен" }
                                    ).joinToString(" ")
                                }.orEmpty()
                                if (query.isNotBlank()) openWebsiteSearch(context, provider, query)
                            },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                modifier = Modifier.padding(15.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(10.dp).size(24.dp)
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(provider.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Искать «${details?.item?.title.orEmpty()}»",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                Icon(Icons.Default.OpenInNew, contentDescription = "Открыть")
                            }
                        }
                    }
                    Text(
                        "Сайт открывается во внешнем браузере. Jacket не обходит авторизацию и не извлекает поток со страницы.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

}

@Composable
internal fun SourceCard(
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
                Text(
                    source.tracker?.takeIf { it.isNotBlank() } ?: source.providerName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                if (!source.tracker.isNullOrBlank()) {
                    Text(
                        "через ${source.providerName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
