package app.jacket.mobile.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import app.jacket.mobile.MainActivity
import app.jacket.mobile.torrent.PlaybackProgressStore
import app.jacket.mobile.torrent.PlaybackSubtitle
import kotlinx.coroutines.delay

private const val PLAYER_PREFS = "jacket_player_chrome"
private const val CHROME_HIDE_MS = 4_500L

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun JacketPlayerScreen(
    title: String,
    streamUrl: String,
    progressKey: String? = null,
    initialPositionMs: Long = 0L,
    subtitles: List<PlaybackSubtitle> = emptyList(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val onBackState by rememberUpdatedState(onBack)
    val progressStore = remember(context) { PlaybackProgressStore(context) }
    val isTv = (LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    val saved = remember(context) { readPlayerChrome(context) }
    var playWhenReady by remember(streamUrl) { mutableStateOf(true) }
    var playbackError by remember(streamUrl) { mutableStateOf<String?>(null) }
    var controlsVisible by remember(streamUrl) { mutableStateOf(true) }
    var chromeToken by remember(streamUrl) { mutableIntStateOf(0) }
    var speed by remember(streamUrl) { mutableFloatStateOf(saved.first) }
    var fit by remember(streamUrl) { mutableStateOf(saved.second) }
    var sleepMinutes by remember(streamUrl) { mutableIntStateOf(0) }
    var positionMs by remember(streamUrl) { mutableLongStateOf(initialPositionMs.coerceAtLeast(0L)) }
    var durationMs by remember(streamUrl) { mutableLongStateOf(0L) }
    var scrubbing by remember(streamUrl) { mutableStateOf(false) }
    var seekHint by remember(streamUrl) { mutableStateOf<String?>(null) }
    var inPip by remember(streamUrl) { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    val hiddenFocus = remember(streamUrl) { FocusRequester() }
    val playFocus = remember(streamUrl) { FocusRequester() }
    var playerView by remember(streamUrl) { mutableStateOf<PlayerView?>(null) }

    val player = remember(streamUrl, subtitles) {
        buildPlayer(context, streamUrl, subtitles, initialPositionMs)
    }

    fun reveal() {
        controlsVisible = true
        chromeToken++
    }

    fun seekBy(deltaMs: Long) {
        val duration = player.duration
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        val bounded = if (duration > 0L) target.coerceAtMost(duration) else target
        player.seekTo(bounded)
        positionMs = bounded
        seekHint = if (deltaMs < 0L) "−10 с" else "+10 с"
        reveal()
    }

    fun togglePlayback() {
        if (player.isPlaying || player.playWhenReady) {
            player.pause()
            playWhenReady = false
        } else {
            player.play()
            playWhenReady = true
        }
        reveal()
    }

    fun applyFit(next: PlaybackChrome.Fit) {
        fit = next
        playerView?.resizeMode = next.toResizeMode()
        writePlayerChrome(context, speed, next)
        reveal()
    }

    BackHandler {
        if (controlsVisible && !inPip) controlsVisible = false else onBackState()
    }

    DisposableEffect(activity, player) {
        val host = activity as? MainActivity
        host?.pipModeListener = { pip ->
            inPip = pip
            if (pip && playWhenReady) player.play()
        }
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            host?.pipModeListener = null
            insets?.show(WindowInsetsCompat.Type.systemBars())
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(isReady: Boolean, reason: Int) {
                playWhenReady = isReady
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.localizedMessage ?: "Проверьте ссылку и доступ к потоку"
                controlsVisible = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) playbackError = null
            }
        }
        player.addListener(listener)
        onDispose {
            progressKey?.let { key ->
                progressStore.save(key, player.currentPosition, player.duration.coerceAtLeast(0L))
            }
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, speed) { player.setPlaybackSpeed(speed) }

    LaunchedEffect(playerView, fit) { playerView?.resizeMode = fit.toResizeMode() }

    LaunchedEffect(player, progressKey) {
        while (true) {
            delay(1_000)
            if (!scrubbing) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                val duration = player.duration
                durationMs = if (duration > 0L) duration else 0L
            }
            if (progressKey != null) {
                progressStore.save(progressKey, player.currentPosition, player.duration.coerceAtLeast(0L))
            }
        }
    }

    LaunchedEffect(controlsVisible, playWhenReady, chromeToken, inPip) {
        if (controlsVisible && playWhenReady && !inPip) {
            delay(CHROME_HIDE_MS)
            controlsVisible = false
        }
    }

    LaunchedEffect(controlsVisible, inPip) {
        if (inPip) return@LaunchedEffect
        runCatching {
            if (controlsVisible) playFocus.requestFocus() else hiddenFocus.requestFocus()
        }
    }

    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes <= 0) return@LaunchedEffect
        delay(sleepMinutes * 60_000L)
        player.pause()
        onBackState()
    }

    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(700)
            seekHint = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (inPip || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (!controlsVisible) {
                    when (event.key) {
                        Key.DirectionLeft -> seekBy(-PlaybackChrome.SEEK_STEP_MS)
                        Key.DirectionRight -> seekBy(PlaybackChrome.SEEK_STEP_MS)
                        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause,
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> togglePlayback()
                        else -> reveal()
                    }
                    true
                } else {
                    when (event.key) {
                        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                            togglePlayback()
                            true
                        }
                        else -> {
                            reveal()
                            false
                        }
                    }
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = false
                    resizeMode = fit.toResizeMode()
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    isFocusable = false
                    playerView = this
                }
            },
            update = {
                it.player = player
                it.resizeMode = fit.toResizeMode()
                playerView = it
            }
        )

        if (!inPip && !controlsVisible) {
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .focusRequester(hiddenFocus)
                        .clickable { seekBy(-PlaybackChrome.SEEK_STEP_MS) }
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable { reveal() }
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable { seekBy(PlaybackChrome.SEEK_STEP_MS) }
                )
            }
        }

        if (seekHint != null && !inPip) {
            Text(
                seekHint.orEmpty(),
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC111722), RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (!inPip && controlsVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xC0000000),
                            0.28f to Color.Transparent,
                            0.58f to Color.Transparent,
                            1f to Color(0xE0000000)
                        )
                    )
                    .clickable { controlsVisible = false }
            )
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackState) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
                    }
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        style = if (isTv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    if (!isTv) {
                        IconButton(onClick = { enterPictureInPicture(activity) }) {
                            Icon(Icons.Default.PictureInPicture, "Картинка в картинке", tint = Color.White)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isTv) 36.dp else 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (durationMs > 0L) {
                        Slider(
                            value = positionMs.coerceIn(0L, durationMs).toFloat(),
                            onValueChange = {
                                scrubbing = true
                                positionMs = it.toLong()
                                reveal()
                            },
                            onValueChangeFinished = {
                                player.seekTo(positionMs)
                                scrubbing = false
                                reveal()
                            },
                            valueRange = 0f..durationMs.toFloat()
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(PlaybackChrome.formatClock(positionMs), color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (durationMs > 0L) "−${PlaybackChrome.formatClock((durationMs - positionMs).coerceAtLeast(0L))}"
                            else "буферизация",
                            color = Color(0xFFD0D7E2),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { seekBy(-PlaybackChrome.SEEK_STEP_MS) }, modifier = Modifier.size(if (isTv) 64.dp else 52.dp)) {
                            Icon(Icons.Default.FastRewind, "Назад на 10 секунд", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        IconButton(
                            onClick = { togglePlayback() },
                            modifier = Modifier.size(if (isTv) 76.dp else 64.dp).focusRequester(playFocus)
                        ) {
                            Icon(
                                if (playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (playWhenReady) "Пауза" else "Воспроизвести",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        IconButton(onClick = { seekBy(PlaybackChrome.SEEK_STEP_MS) }, modifier = Modifier.size(if (isTv) 64.dp else 52.dp)) {
                            Icon(Icons.Default.FastForward, "Вперёд на 10 секунд", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayerChip(Icons.Default.Speed, PlaybackChrome.speedLabel(speed), Modifier.weight(1f)) {
                            speed = PlaybackChrome.nextSpeed(speed)
                            writePlayerChrome(context, speed, fit)
                            reveal()
                        }
                        PlayerChip(Icons.Default.AspectRatio, PlaybackChrome.fitLabel(fit), Modifier.weight(1f)) {
                            applyFit(PlaybackChrome.nextFit(fit))
                        }
                        PlayerChip(Icons.Default.Bedtime, PlaybackChrome.sleepLabel(sleepMinutes), Modifier.weight(1f)) {
                            sleepMinutes = PlaybackChrome.nextSleepMinutes(sleepMinutes)
                            reveal()
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayerChip(Icons.Default.Audiotrack, "Аудио", Modifier.weight(1f)) {
                            TrackSelectionDialogBuilder(context, "Аудиодорожка", player, C.TRACK_TYPE_AUDIO)
                                .setShowDisableOption(false)
                                .build()
                                .show()
                            reveal()
                        }
                        PlayerChip(Icons.Default.Subtitles, "Субтитры", Modifier.weight(1f)) {
                            TrackSelectionDialogBuilder(context, "Субтитры", player, C.TRACK_TYPE_TEXT)
                                .setShowDisableOption(true)
                                .build()
                                .show()
                            reveal()
                        }
                        PlayerChip(Icons.Default.OpenInNew, "Внешний", Modifier.weight(1f)) {
                            openExternalPlayer(context, streamUrl)
                            reveal()
                        }
                    }
                    if (playbackError != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Не удалось начать воспроизведение", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(playbackError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = {
                                    playbackError = null
                                    player.prepare()
                                    player.playWhenReady = true
                                    reveal()
                                }) { Text("Повторить") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, maxLines = 1)
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun buildPlayer(
    context: Context,
    streamUrl: String,
    subtitles: List<PlaybackSubtitle>,
    initialPositionMs: Long
): ExoPlayer {
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
    val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
        .apply {
            setMediaItem(
                MediaItem.Builder()
                    .setUri(streamUrl)
                    .apply {
                        val path = streamUri.lastPathSegment.orEmpty().lowercase()
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

private fun PlaybackChrome.Fit.toResizeMode(): Int = when (this) {
    PlaybackChrome.Fit.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    PlaybackChrome.Fit.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    PlaybackChrome.Fit.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
}

private fun readPlayerChrome(context: Context): Pair<Float, PlaybackChrome.Fit> {
    val prefs = context.getSharedPreferences(PLAYER_PREFS, Context.MODE_PRIVATE)
    val speed = prefs.getFloat("speed", 1f).takeIf { candidate ->
        PlaybackChrome.SPEEDS.any { kotlin.math.abs(it - candidate) < 0.02f }
    } ?: 1f
    val fit = runCatching { PlaybackChrome.Fit.valueOf(prefs.getString("fit", "Fit").orEmpty()) }
        .getOrDefault(PlaybackChrome.Fit.Fit)
    return speed to fit
}

private fun writePlayerChrome(context: Context, speed: Float, fit: PlaybackChrome.Fit) {
    context.getSharedPreferences(PLAYER_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putFloat("speed", speed)
        .putString("fit", fit.name)
        .apply()
}

private fun enterPictureInPicture(activity: Activity?) {
    val host = activity ?: return
    val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
    runCatching { host.enterPictureInPictureMode(params) }
}

private fun openExternalPlayer(context: Context, streamUrl: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(streamUrl), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Открыть во внешнем плеере"))
    }
}
