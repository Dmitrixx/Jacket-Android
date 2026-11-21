package app.jacket.mobile.ui

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import android.view.View
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import app.jacket.mobile.torrent.PlaybackProgressStore
import app.jacket.mobile.torrent.PlaybackSubtitle
import kotlinx.coroutines.delay

private const val PLAYER_SEEK_STEP_MS = 10_000L

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun JacketPlayerScreen(
    title: String,
    streamUrl: String,
    progressKey: String? = null,
    initialPositionMs: Long = 0L,
    subtitles: List<PlaybackSubtitle> = emptyList(),
    httpHeaders: Map<String, String> = emptyMap(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val onBackState by rememberUpdatedState(onBack)
    val progressStore = remember(context) { PlaybackProgressStore(context) }
    var playWhenReady by remember(streamUrl) { mutableStateOf(true) }
    var playerHasFocus by remember(streamUrl) { mutableStateOf(false) }
    var playbackError by remember(streamUrl) { mutableStateOf<String?>(null) }
    val safeHeaders = httpHeaders.filterKeys {
        it.equals("Referer", true) || it.equals("User-Agent", true)
    }.filterValues { it.isNotBlank() && it.length <= 512 }
    val player = remember(streamUrl, subtitles, safeHeaders) {
        val subtitleConfigurations = subtitles.map { subtitle ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                .setMimeType(subtitle.mimeType)
                .setLanguage(subtitle.language)
                .setLabel(subtitle.label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        }
        val streamUri = Uri.parse(streamUrl)
        val isLoopbackStream = streamUri.host == "127.0.0.1" || streamUri.host == "localhost"
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(if (isLoopbackStream) 120_000 else 30_000)
            .setDefaultRequestProperties(safeHeaders)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
            setMediaItem(
                MediaItem.Builder()
                    .setUri(streamUrl)
                    .apply {
                        val path = Uri.parse(streamUrl).lastPathSegment.orEmpty().lowercase()
                        when {
                            path.endsWith(".m3u8") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                            path.endsWith(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
                        }
                    }
                    .setSubtitleConfigurations(subtitleConfigurations)
                    .build()
            )
            if (initialPositionMs > 0L) seekTo(initialPositionMs)
            prepare()
            playWhenReady = true
        }
    }

    fun togglePlayback() {
        if (player.isPlaying || player.playWhenReady) {
            player.pause()
            playWhenReady = false
        } else {
            player.play()
            playWhenReady = true
        }
    }

    BackHandler(onBack = onBackState)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(isReady: Boolean, reason: Int) {
                playWhenReady = isReady
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.localizedMessage ?: "Проверьте ссылку и доступ к потоку"
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) playbackError = null
            }
        }
        player.addListener(listener)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            progressKey?.let { key ->
                progressStore.save(key, player.currentPosition, player.duration.coerceAtLeast(0L))
            }
            player.removeListener(listener)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            player.release()
        }
    }

    LaunchedEffect(player, progressKey) {
        while (true) {
            delay(5_000)
            if (progressKey != null) {
                progressStore.save(progressKey, player.currentPosition, player.duration.coerceAtLeast(0L))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBackState) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .onPreviewKeyEvent { event ->
                    if (!playerHasFocus || event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionLeft -> {
                                player.seekTo((player.currentPosition - PLAYER_SEEK_STEP_MS).coerceAtLeast(0L))
                                true
                            }
                            Key.DirectionRight -> {
                                val duration = player.duration
                                val target = player.currentPosition + PLAYER_SEEK_STEP_MS
                                player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                                true
                            }
                            Key.MediaPlayPause,
                            Key.MediaPlay,
                            Key.MediaPause,
                            Key.DirectionCenter,
                            Key.Enter -> {
                                togglePlayback()
                                true
                            }
                            else -> false
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    .aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
                            playerHasFocus = focused
                        }
                        requestFocus()
                    }
                },
                update = { it.player = player }
            )
            if (playbackError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Не удалось начать воспроизведение",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            playbackError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(onClick = {
                            playbackError = null
                            player.prepare()
                            player.playWhenReady = true
                        }) { Text("Повторить") }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = { player.seekTo((player.currentPosition - PLAYER_SEEK_STEP_MS).coerceAtLeast(0L)) }
                ) {
                    Icon(Icons.Default.FastRewind, contentDescription = "Назад на 10 секунд")
                }
                IconButton(onClick = { togglePlayback() }) {
                    Icon(
                        if (playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playWhenReady) "Пауза" else "Воспроизвести"
                    )
                }
                IconButton(
                    onClick = {
                        val duration = player.duration
                        val target = player.currentPosition + PLAYER_SEEK_STEP_MS
                        player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                    }
                ) {
                    Icon(Icons.Default.FastForward, contentDescription = "Вперёд на 10 секунд")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        TrackSelectionDialogBuilder(context, "Аудиодорожка", player, C.TRACK_TYPE_AUDIO)
                            .setShowDisableOption(false)
                            .build()
                            .show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Audiotrack, contentDescription = null)
                    Text("Аудио")
                }
                OutlinedButton(
                    onClick = {
                        TrackSelectionDialogBuilder(context, "Субтитры", player, C.TRACK_TYPE_TEXT)
                            .setShowDisableOption(true)
                            .build()
                            .show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Subtitles, contentDescription = null)
                    Text("Субтитры")
                }
            }
            Button(
                onClick = {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(android.net.Uri.parse(streamUrl), "video/*")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(Intent.createChooser(intent, "Открыть во внешнем плеере"))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null)
                Text("Открыть во внешнем плеере")
            }
            Text(
                "Источник: локальный поток 127.0.0.1 или прямая лицензированная ссылка. Дорожки выбираются из того, что содержит файл.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
