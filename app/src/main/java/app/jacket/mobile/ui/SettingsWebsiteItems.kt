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
import androidx.compose.foundation.lazy.LazyListScope

internal fun LazyListScope.settingsWebsiteItems(
    selectedSection: String,
    viewModel: MainViewModel,
    context: android.content.Context,
    webSearchProviders: List<WebSearchProvider>,
    webEditingId: String,
    onWebEditingIdChange: (String) -> Unit,
    webName: String,
    onWebNameChange: (String) -> Unit,
    webUrlTemplate: String,
    onWebUrlTemplateChange: (String) -> Unit
) {
            if (selectedSection == "sites") {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (webEditingId.isBlank()) "Добавить сайт поиска" else "Изменить сайт",
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
                            value = webName,
                            onValueChange = { onWebNameChange(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Название сайта") },
                            placeholder = { Text("Например, мой видеокаталог") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = webUrlTemplate,
                            onValueChange = { onWebUrlTemplateChange(it) },
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
                                        id = webEditingId,
                                        name = webName,
                                        searchUrlTemplate = webUrlTemplate
                                    )
                                )
                                if (saved) {
                                    onWebEditingIdChange("")
                                    onWebNameChange("")
                                    onWebUrlTemplateChange("")
                                    Toast.makeText(context, "Сайт поиска сохранён", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Проверьте название и HTTPS-шаблон с {query}", Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (webEditingId.isBlank()) "Добавить сайт" else "Сохранить изменения")
                        }
                        if (webEditingId.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    onWebEditingIdChange("")
                                    onWebNameChange("")
                                    onWebUrlTemplateChange("")
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
                                        onWebEditingIdChange(provider.id)
                                        onWebNameChange(provider.name)
                                        onWebUrlTemplateChange(provider.searchUrlTemplate)
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


}
