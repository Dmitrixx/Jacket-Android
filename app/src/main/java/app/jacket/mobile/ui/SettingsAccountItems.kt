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

internal fun LazyListScope.settingsAccountItems(
    selectedSection: String,
    viewModel: MainViewModel,
    context: android.content.Context,
    filmixToken: String,
    filmixCode: String?,
    filmixStatus: String,
    isFilmixPairing: Boolean,
    hdRezkaSettings: HdRezkaSettings,
    hdRezkaStatus: String,
    isHdRezkaBusy: Boolean,
    hdMirror: String,
    onHdMirrorChange: (String) -> Unit,
    hdLogin: String,
    onHdLoginChange: (String) -> Unit,
    hdPassword: String,
    onHdPasswordChange: (String) -> Unit,
    hdCookie: String,
    onHdCookieChange: (String) -> Unit
) {
            if (selectedSection == "filmix") {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "ПОДКЛЮЧЕНИЕ FILMIX",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.2.sp
                            )
                            Text("Привязка устройства", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold)
                            Text(
                                "Jacket запросит одноразовый код. Открой страницу устройств Filmix, введи код и вернись сюда — подтверждение проверяется автоматически.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Статус", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        filmixStatus,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (filmixToken.isNotBlank()) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (!filmixCode.isNullOrBlank()) {
                                        Text(
                                            filmixCode.orEmpty(),
                                            style = MaterialTheme.typography.displayMedium,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 8.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            "Код временный. Не передавай его другим людям.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            if (isFilmixPairing) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                OutlinedButton(
                                    onClick = { viewModel.cancelFilmixPairing() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Отменить подключение") }
                            } else if (filmixToken.isBlank()) {
                                Button(
                                    onClick = { viewModel.startFilmixPairing() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Tv, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (filmixCode == null) "Получить код Filmix" else "Получить новый код")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.disconnectFilmix() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Отключить устройство")
                                }
                            }
                            if (!filmixCode.isNullOrBlank()) {
                                Button(
                                    onClick = { openExternal(context, FilmixDeviceAuth.ACTIVATION_URL) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Открыть страницу активации")
                                }
                            }
                            Text(
                                "Токен устройства хранится в зашифрованном виде через Android Keystore. Пароль Filmix приложению не нужен.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (selectedSection == "hdrezka") {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "АККАУНТ И СЕССИЯ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.2.sp
                            )
                            Text("HDRezka", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold)
                            Text(
                                "Укажи рабочее HTTPS-зеркало и данные своего аккаунта. После успешного входа Jacket сохранит Cookie-сессию локально в зашифрованном виде.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = hdMirror,
                                onValueChange = { onHdMirrorChange(it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Зеркало HDRezka") },
                                placeholder = { Text("https://standby-rezka.tv") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = hdLogin,
                                onValueChange = { onHdLoginChange(it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Логин или email") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = hdPassword,
                                onValueChange = { onHdPasswordChange(it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Пароль") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    viewModel.loginHdRezka(
                                        HdRezkaSettings(
                                            mirror = hdMirror,
                                            login = hdLogin,
                                            password = hdPassword
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isHdRezkaBusy && hdLogin.isNotBlank() && hdPassword.isNotBlank()
                            ) {
                                if (isHdRezkaBusy) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                } else {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (isHdRezkaBusy) "Подключение…" else "Войти и сохранить сессию")
                            }
                            Text(
                                hdRezkaStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hdRezkaSettings.connected) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("Ручная Cookie-сессия", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                value = hdCookie,
                                onValueChange = { onHdCookieChange(it.take(8192)) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Cookie / session") },
                                placeholder = { Text("dle_user_id=…; dle_password=…") },
                                visualTransformation = PasswordVisualTransformation(),
                                minLines = 2
                            )
                            Text(
                                "Ручной вариант — вставь Cookie после обычного входа в браузере. Не отправляй её никому: она даёт доступ к аккаунту.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = {
                                    viewModel.saveHdRezkaSettings(
                                        HdRezkaSettings(
                                            mirror = hdMirror,
                                            login = hdLogin,
                                            password = hdPassword,
                                            cookie = hdCookie
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isHdRezkaBusy && hdCookie.isNotBlank()
                            ) { Text("Сохранить Cookie-сессию") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.disconnectHdRezka()
                                        onHdCookieChange("")
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Удалить сессию") }
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            openExternal(context, HdRezkaLoginClient.validateMirror(hdMirror))
                                        }.onFailure {
                                            Toast.makeText(context, it.message ?: "Некорректное зеркало", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Открыть зеркало") }
                            }
                            Text(
                                "Если сайт требует CAPTCHA или Cloudflare, автоматический вход остановится. Jacket не обходит защиту; в таком случае используй Cookie из своего браузера.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

}
