package app.jacket.mobile.ui

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import app.jacket.mobile.torrent.PlaybackProgressStore
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun JacketPlayerScreen(
    title: String,
    streamUrl: String,
    progressKey: String? = null,
    initialPositionMs: Long = 0L,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val onBackState by rememberUpdatedState(onBack)
    val progressStore = remember(context) { PlaybackProgressStore(context) }
    val player = remember(streamUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            if (initialPositionMs > 0L) seekTo(initialPositionMs)
            prepare()
            playWhenReady = true
        }
    }

    BackHandler(onBack = onBackState)

    DisposableEffect(player) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            progressKey?.let { key ->
                progressStore.save(key, player.currentPosition, player.duration.coerceAtLeast(0L))
            }
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
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                    }
                },
                update = { it.player = player }
            )
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
