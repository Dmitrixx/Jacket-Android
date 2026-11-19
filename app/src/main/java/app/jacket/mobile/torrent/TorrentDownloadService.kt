package app.jacket.mobile.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.jacket.mobile.R
import app.jacket.mobile.MainActivity
import app.jacket.mobile.data.MediaSettingsStore
import app.jacket.mobile.data.IndexerProtocol
import app.jacket.mobile.data.LocalNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Single-process foreground owner of the BitTorrent session and loopback media server. */
class TorrentDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollingStarted = false
    private val commandMutex = Mutex()
    private val playbackActive = AtomicBoolean(false)
    private var streamServer: LocalStreamServer? = null
    private var streamingTorrentId: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_RESTORE
        startForegroundFor(playbackActive.get() || action == ACTION_PLAY)
        serviceScope.launch {
            commandMutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    TorrentEngine.start(applicationContext)
                    when (action) {
                        ACTION_ADD -> {
                            val magnet = intent?.getStringExtra(EXTRA_MAGNET).orEmpty()
                            val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
                            require(magnet.startsWith("magnet:?")) { "Для загрузки нужна magnet-ссылка" }
                            TorrentEngine.add(applicationContext, magnet, title)
                        }
                        ACTION_ADD_TORRENT -> {
                            val path = intent?.getStringExtra(EXTRA_TORRENT_PATH).orEmpty()
                            require(path.isNotBlank()) { "Путь к .torrent не указан" }
                            TorrentEngine.addTorrent(applicationContext, File(path), intent?.getStringExtra(EXTRA_TITLE))
                        }
                        ACTION_ADD_TORRENT_URL -> {
                            val url = intent?.getStringExtra(EXTRA_TORRENT_URL).orEmpty()
                            val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
                            val file = downloadTorrentFile(
                                url,
                                title,
                                intent?.getStringExtra(EXTRA_INDEXER_ID)
                            )
                            TorrentEngine.addTorrent(applicationContext, file, title)
                        }
                        ACTION_PAUSE -> TorrentEngine.pause(applicationContext, intent?.getStringExtra(EXTRA_ID).orEmpty())
                        ACTION_RESUME -> TorrentEngine.resume(applicationContext, intent?.getStringExtra(EXTRA_ID).orEmpty())
                        ACTION_SELECT_FILE -> TorrentEngine.selectFile(
                            applicationContext,
                            intent?.getStringExtra(EXTRA_ID).orEmpty(),
                            intent?.getIntExtra(EXTRA_FILE_INDEX, -1) ?: -1
                        )
                        ACTION_REMOVE -> TorrentEngine.remove(
                            applicationContext,
                            intent?.getStringExtra(EXTRA_ID).orEmpty(),
                            intent?.getBooleanExtra(EXTRA_DELETE_FILES, false) ?: false
                        )
                        ACTION_PLAY -> startTorrentPlayback(
                            intent?.getStringExtra(EXTRA_ID).orEmpty(),
                            intent?.getIntExtra(EXTRA_FILE_INDEX, -1) ?: -1
                        )
                        ACTION_STOP_PLAYBACK -> stopTorrentPlayback()
                        ACTION_PAUSE_SELECTED -> {
                            val id = intent?.getStringExtra(EXTRA_ID).orEmpty()
                            if (TorrentEngine.snapshots.value.firstOrNull { it.id == id }?.paused == true) {
                                TorrentEngine.resume(applicationContext, id)
                            } else {
                                TorrentEngine.pause(applicationContext, id)
                            }
                        }
                    }
                    TorrentEngine.refresh()
                }
                startPolling()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("Ошибка: ${error.message ?: "не удалось выполнить операцию"}", null))
            }
            }
        }
        return START_STICKY
    }

    private suspend fun startTorrentPlayback(id: String, fileIndex: Int) {
        require(id.matches(Regex("(?i)([a-f0-9]{40}|[a-f0-9]{64})"))) { "Некорректный infohash" }
        require(fileIndex >= 0) { "Не выбран файл раздачи" }
        val target = withContext(Dispatchers.IO) {
            if (TorrentQueueStore(applicationContext).isPaused(id)) {
                TorrentEngine.resume(applicationContext, id)
            }
            if (streamingTorrentId != null && streamingTorrentId != id) {
                TorrentEngine.clearStreamPriorities(streamingTorrentId!!)
            }
            selectFileWhenReady(id, fileIndex)
            val server = streamServer ?: LocalStreamServer(
                metadata = TorrentEngine::target,
                awaitRange = { hash, index, start, length ->
                    try {
                        TorrentEngine.awaitRange(hash, index, start, length)
                    } catch (error: Exception) {
                        TorrentEngine.reportError(hash, error.message ?: "Ошибка локального видеопотока")
                        throw error
                    }
                },
                cacheDirectory = File(applicationContext.cacheDir, "torrent-stream")
            ).also {
                it.start()
                streamServer = it
                TorrentEngine.setLocalStreamPort(it.port)
            }
            streamingTorrentId = id
            val item = TorrentEngine.target(id, fileIndex)
            val subtitles = TorrentEngine.subtitleTargets(id, fileIndex).map { subtitle ->
                val language = subtitle.name.substringAfterLast('/').substringBeforeLast('.')
                    .substringAfterLast('.')
                    .takeIf { it.matches(Regex("(?i)[a-z]{2,3}")) }
                PlaybackSubtitle(
                    url = server.url(id, subtitle.fileIndex),
                    mimeType = if (subtitle.name.endsWith(".vtt", true)) "text/vtt" else "application/x-subrip",
                    language = language,
                    label = subtitle.name.substringAfterLast('/')
                )
            }
            Triple(server.url(id, fileIndex), item, subtitles)
        }
        val key = PlaybackProgressStore.torrentKey(id, fileIndex)
        val savedPosition = PlaybackProgressStore(applicationContext).position(key)
        PlaybackSessionStore(applicationContext).begin(id, fileIndex, target.second.name, savedPosition)
        playbackActive.set(true)
        startForegroundFor(mediaPlayback = true, status = "Смотрим: ${target.second.name}")
        TorrentPlaybackBus.publish(
            StreamPlaybackRequest(
                title = target.second.name,
                url = target.first,
                progressKey = key,
                initialPositionMs = savedPosition,
                subtitles = target.third
            )
        )
    }

    private suspend fun selectFileWhenReady(id: String, fileIndex: Int) {
        var lastError: Exception? = null
        repeat(60) {
            try {
                TorrentEngine.selectFile(applicationContext, id, fileIndex)
                return
            } catch (error: IllegalStateException) {
                val message = error.message.orEmpty().lowercase()
                val retryable = "подключается" in message || "метаданные" in message ||
                    ("не найден" in message && TorrentQueueStore(applicationContext).get(id) != null)
                if (!retryable) throw error
                lastError = error
                delay(500)
            }
        }
        throw java.net.SocketTimeoutException(
            "Не удалось получить метаданные раздачи за 30 секунд: ${lastError?.message.orEmpty()}"
        )
    }

    private suspend fun stopTorrentPlayback() {
        playbackActive.set(false)
        TorrentPlaybackBus.publish(null)
        withContext(Dispatchers.IO) {
            streamingTorrentId?.let(TorrentEngine::clearStreamPriorities)
            streamingTorrentId = null
            streamServer?.close()
            streamServer = null
            TorrentEngine.setLocalStreamPort(null)
        }
        startForegroundFor(mediaPlayback = false, status = "Загрузка продолжается в фоне")
    }

    private fun startPolling() {
        if (pollingStarted) return
        pollingStarted = true
        serviceScope.launch {
            var lastResumeSave = 0L
            while (isActive) {
                commandMutex.withLock {
                    withContext(Dispatchers.IO) {
                        TorrentEngine.restorePending(applicationContext)
                        TorrentEngine.refresh()
                        val now = System.currentTimeMillis()
                        if (now - lastResumeSave >= RESUME_SAVE_INTERVAL_MS) {
                            TorrentEngine.saveAllResumeData()
                            lastResumeSave = now
                        }
                    }
                }
                updateNotification()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun updateNotification() {
        val rows = TorrentEngine.snapshots.value
        val activeStream = TorrentPlaybackBus.active.value
        val selected = rows.firstOrNull { it.files.any { f -> f.isSelected } }
        val down = rows.sumOf { it.downloadBytesPerSecond.toLong() }
        val up = rows.sumOf { it.uploadBytesPerSecond.toLong() }
        val text = when {
            activeStream != null && selected != null -> {
                val file = selected.files.firstOrNull { it.isSelected }
                "${activeStream.title} • ${(file?.progress ?: 0f).times(100).toInt()}% • ↓ ${formatRate(down)}"
            }
            rows.isEmpty() -> "Нет активных загрузок"
            else -> "${rows.size} задач · ↓ ${formatRate(down)} · ↑ ${formatRate(up)}"
        }
        val pauseId = selected?.id ?: rows.firstOrNull { !it.paused }?.id
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text, pauseId))
    }

    private fun startForegroundFor(mediaPlayback: Boolean, status: String = "Запуск торрент-движка…") {
        val notification = notification(status, TorrentEngine.snapshots.value.firstOrNull { !it.paused }?.id)
        val type = if (mediaPlayback) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun downloadTorrentFile(urlValue: String, title: String, indexerId: String?): File {
        val sourceConfig = indexerId?.let { wanted ->
            MediaSettingsStore(applicationContext).indexers().firstOrNull { it.id == wanted }
        }
        var currentUrl = urlValue
        val originalUri = Uri.parse(urlValue)
        require(LocalNetwork.isAllowedUrl(urlValue)) {
            "Для torrent-файла нужен HTTPS или HTTP-адрес локального сервера"
        }
        val keyParameter = if (sourceConfig?.protocol == IndexerProtocol.JACKETT) {
            "jackett_apikey"
        } else "apikey"
        if (sourceConfig != null && sourceConfig.apiKey.isNotBlank()) {
            val uri = Uri.parse(currentUrl)
            currentUrl = if (uri.getQueryParameter(keyParameter) == null) {
                uri.buildUpon().appendQueryParameter(keyParameter, sourceConfig.apiKey).build().toString()
            } else currentUrl
        }

        val dir = File(filesDir, "torrent-imports").apply { mkdirs() }
        val safeName = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64).ifBlank { "release" }
        val target = File.createTempFile("${safeName}_", ".torrent", dir)
        var activeUrl = currentUrl
        var redirectCount = 0
        try {
            while (true) {
                val uri = Uri.parse(activeUrl)
                val allowedRedirect = when (uri.scheme?.lowercase()) {
                    "https" -> LocalNetwork.isAllowedUrl(activeUrl)
                    "http" -> originalUri.scheme.equals("http", true) &&
                        uri.host.equals(originalUri.host, ignoreCase = true) &&
                        effectivePort(uri) == effectivePort(originalUri) &&
                        LocalNetwork.isAllowedUrl(activeUrl)
                    else -> false
                }
                require(allowedRedirect && uri.userInfo == null && !uri.host.isNullOrBlank()) {
                    "Перенаправление torrent-файла ведёт на небезопасный адрес"
                }
                val sameOrigin = uri.scheme.equals(originalUri.scheme, true) &&
                    uri.host.equals(originalUri.host, ignoreCase = true) &&
                    effectivePort(uri) == effectivePort(originalUri)
                val requestUrl = if (
                    sameOrigin && sourceConfig?.apiKey?.isNotBlank() == true &&
                    uri.getQueryParameter(keyParameter) == null
                ) {
                    uri.buildUpon().appendQueryParameter(keyParameter, sourceConfig.apiKey).build().toString()
                } else activeUrl
                val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 25_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "JacketAndroid/2.1")
                    if (sameOrigin && sourceConfig != null) {
                        if (sourceConfig.username.isNotBlank() || sourceConfig.password.isNotBlank()) {
                            val credentials = "${sourceConfig.username}:${sourceConfig.password}"
                            val encoded = android.util.Base64.encodeToString(
                                credentials.toByteArray(Charsets.UTF_8),
                                android.util.Base64.NO_WRAP
                            )
                            setRequestProperty("Authorization", "Basic $encoded")
                        }
                        if (sourceConfig.cookie.isNotBlank()) setRequestProperty("Cookie", sourceConfig.cookie.take(4096))
                        if (sourceConfig.apiKey.isNotBlank() && sourceConfig.protocol != IndexerProtocol.JACKETT) {
                            setRequestProperty("X-Api-Key", sourceConfig.apiKey.take(512))
                        }
                    }
                }
                try {
                    val code = connection.responseCode
                    if (code in REDIRECT_CODES) {
                        if (redirectCount++ >= MAX_TORRENT_REDIRECTS) throw IOException("Слишком много перенаправлений .torrent")
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Сервер вернул перенаправление без Location")
                        activeUrl = URL(activeUrl).toURI().resolve(URI(location)).toString()
                        continue
                    }
                    if (code !in 200..299) throw IOException("HTTP $code при загрузке .torrent")
                    val declaredLength = connection.getHeaderFieldLong("Content-Length", -1L)
                    if (declaredLength > TorrentQueueStore.MAX_TORRENT_BYTES) {
                        throw IOException("Файл .torrent превышает 8 МиБ")
                    }
                    connection.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > TorrentQueueStore.MAX_TORRENT_BYTES) {
                                    throw IOException("Файл .torrent превышает 8 МиБ")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    return target
                } finally {
                    connection.disconnect()
                }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun effectivePort(uri: Uri): Int =
        if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    @android.annotation.TargetApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Only the dataSync mode is time-limited. Active viewing uses mediaPlayback mode.
        if (playbackActive.get()) {
            startForegroundFor(mediaPlayback = true, status = "Воспроизведение продолжается")
            return
        }
        serviceScope.launch {
            commandMutex.withLock {
                val saved = withContext(Dispatchers.IO) { TorrentEngine.stop() }
                if (!saved) {
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        notification("Не все данные загрузок успели сохраниться. При следующем запуске будет выполнена проверка.", null)
                    )
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        streamServer?.close()
        streamServer = null
        if (playbackActive.get()) TorrentPlaybackBus.publish(null)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Jacket • загрузки и просмотр", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String, torrentId: String?): Notification {
        val pauseIntent = torrentId?.let { id ->
            PendingIntent.getService(
                this,
                id.hashCode(),
                Intent(this, TorrentDownloadService::class.java)
                    .setAction(ACTION_PAUSE_SELECTED)
                    .putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val openIntent = PendingIntent.getActivity(
            this,
            4102,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jacket)
            .setContentTitle("Jacket")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (pauseIntent != null) builder.addAction(
            R.drawable.ic_jacket,
            if (TorrentEngine.snapshots.value.firstOrNull { it.id == torrentId }?.paused == true) "Продолжить" else "Пауза",
            pauseIntent
        )
        return builder.build()
    }

    private fun formatRate(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024L * 1024L -> "%.1f МБ/с".format(bytesPerSecond / (1024.0 * 1024.0))
        bytesPerSecond >= 1024L -> "%.0f КБ/с".format(bytesPerSecond / 1024.0)
        else -> "$bytesPerSecond Б/с"
    }

    companion object {
        const val EXTRA_MAGNET = "magnet"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ID = "torrent_id"
        const val EXTRA_FILE_INDEX = "file_index"
        const val EXTRA_TORRENT_PATH = "torrent_path"
        const val EXTRA_TORRENT_URL = "torrent_url"
        const val EXTRA_INDEXER_ID = "indexer_id"
        const val EXTRA_DELETE_FILES = "delete_files"

        private const val ACTION_RESTORE = "app.jacket.action.RESTORE"
        private const val ACTION_ADD = "app.jacket.action.ADD"
        private const val ACTION_ADD_TORRENT = "app.jacket.action.ADD_TORRENT"
        private const val ACTION_ADD_TORRENT_URL = "app.jacket.action.ADD_TORRENT_URL"
        private const val ACTION_PAUSE = "app.jacket.action.PAUSE"
        private const val ACTION_RESUME = "app.jacket.action.RESUME"
        private const val ACTION_SELECT_FILE = "app.jacket.action.SELECT_FILE"
        private const val ACTION_REMOVE = "app.jacket.action.REMOVE"
        private const val ACTION_PLAY = "app.jacket.action.PLAY"
        private const val ACTION_STOP_PLAYBACK = "app.jacket.action.STOP_PLAYBACK"
        private const val ACTION_PAUSE_SELECTED = "app.jacket.action.PAUSE_SELECTED"
        private const val CHANNEL_ID = "jacket_downloads"
        private const val NOTIFICATION_ID = 4101
        private const val POLL_INTERVAL_MS = 1_000L
        private const val RESUME_SAVE_INTERVAL_MS = 30_000L
        private const val MAX_TORRENT_REDIRECTS = 3
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        private fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, TorrentDownloadService::class.java).setAction(action).apply(extras)
            ContextCompat.startForegroundService(context, intent)
        }

        fun restore(context: Context) = send(context, ACTION_RESTORE)
        fun add(context: Context, magnet: String, title: String) = send(context, ACTION_ADD) {
            putExtra(EXTRA_MAGNET, magnet)
            putExtra(EXTRA_TITLE, title)
        }
        fun addTorrentFile(context: Context, path: String, title: String? = null) = send(context, ACTION_ADD_TORRENT) {
            putExtra(EXTRA_TORRENT_PATH, path)
            putExtra(EXTRA_TITLE, title)
        }
        fun addTorrentUrl(context: Context, url: String, title: String, indexerId: String? = null) = send(context, ACTION_ADD_TORRENT_URL) {
            putExtra(EXTRA_TORRENT_URL, url)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_INDEXER_ID, indexerId)
        }
        fun pause(context: Context, id: String) = send(context, ACTION_PAUSE) { putExtra(EXTRA_ID, id) }
        fun resume(context: Context, id: String) = send(context, ACTION_RESUME) { putExtra(EXTRA_ID, id) }
        fun selectFile(context: Context, id: String, fileIndex: Int) = send(context, ACTION_SELECT_FILE) {
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_FILE_INDEX, fileIndex)
        }
        fun remove(context: Context, id: String, deleteFiles: Boolean = false) = send(context, ACTION_REMOVE) {
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_DELETE_FILES, deleteFiles)
        }
        fun play(context: Context, id: String, fileIndex: Int) = send(context, ACTION_PLAY) {
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_FILE_INDEX, fileIndex)
        }
        fun stopPlayback(context: Context) = send(context, ACTION_STOP_PLAYBACK)
    }
}
