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
internal fun DownloadsScreen(onBack: () -> Unit) {
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
