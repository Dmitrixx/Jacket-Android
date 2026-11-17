package app.jacket.mobile.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.jacket.mobile.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-visible foreground service that owns the native BitTorrent session. */
class TorrentDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollingStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Запуск торрент-движка…"))
        val action = intent?.action ?: ACTION_RESTORE
        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    TorrentEngine.start(applicationContext)
                    when (action) {
                        ACTION_ADD -> {
                            val magnet = intent?.getStringExtra(EXTRA_MAGNET).orEmpty()
                            val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
                            require(magnet.startsWith("magnet:?")) { "Для встроенной загрузки нужна magnet-ссылка" }
                            TorrentEngine.add(applicationContext, magnet, title)
                        }
                        ACTION_PAUSE -> TorrentEngine.pause(applicationContext, intent?.getStringExtra(EXTRA_ID).orEmpty())
                        ACTION_RESUME -> TorrentEngine.resume(applicationContext, intent?.getStringExtra(EXTRA_ID).orEmpty())
                        ACTION_REMOVE -> TorrentEngine.remove(applicationContext, intent?.getStringExtra(EXTRA_ID).orEmpty())
                    }
                    TorrentEngine.refresh()
                }
                startPolling()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("Ошибка: ${error.message ?: "не удалось запустить загрузку"}"))
            }
        }
        return START_STICKY
    }

    private fun startPolling() {
        if (pollingStarted) return
        pollingStarted = true
        serviceScope.launch {
            while (isActive) {
                withContext(Dispatchers.IO) { TorrentEngine.refresh() }
                val rows = TorrentEngine.snapshots.value
                val down = rows.sumOf { it.downloadBytesPerSecond.toLong() }
                val up = rows.sumOf { it.uploadBytesPerSecond.toLong() }
                val label = if (rows.isEmpty()) "Нет активных загрузок" else
                    "${rows.size} задач · ↓ ${formatRate(down)} · ↑ ${formatRate(up)}"
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(label))
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    @android.annotation.TargetApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ grants dataSync foreground services at most six background hours per 24h.
        // Stop the native session cleanly; persisted magnet tasks are restored next time the user starts Jacket.
        serviceScope.launch {
            withContext(Dispatchers.IO) { TorrentEngine.stop() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Загрузки Jacket", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jacket)
            .setContentTitle("Jacket — торрент-клиент")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun formatRate(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024L * 1024L -> "%.1f МБ/с".format(bytesPerSecond / (1024.0 * 1024.0))
        bytesPerSecond >= 1024L -> "%.0f КБ/с".format(bytesPerSecond / 1024.0)
        else -> "$bytesPerSecond Б/с"
    }

    companion object {
        const val EXTRA_MAGNET = "magnet"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ID = "torrent_id"

        private const val ACTION_RESTORE = "app.jacket.action.RESTORE"
        private const val ACTION_ADD = "app.jacket.action.ADD"
        private const val ACTION_PAUSE = "app.jacket.action.PAUSE"
        private const val ACTION_RESUME = "app.jacket.action.RESUME"
        private const val ACTION_REMOVE = "app.jacket.action.REMOVE"
        private const val CHANNEL_ID = "jacket_downloads"
        private const val NOTIFICATION_ID = 4101
        private const val POLL_INTERVAL_MS = 1_000L

        private fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, TorrentDownloadService::class.java).setAction(action).apply(extras)
            ContextCompat.startForegroundService(context, intent)
        }

        fun restore(context: Context) = send(context, ACTION_RESTORE)
        fun add(context: Context, magnet: String, title: String) = send(context, ACTION_ADD) {
            putExtra(EXTRA_MAGNET, magnet)
            putExtra(EXTRA_TITLE, title)
        }
        fun pause(context: Context, id: String) = send(context, ACTION_PAUSE) { putExtra(EXTRA_ID, id) }
        fun resume(context: Context, id: String) = send(context, ACTION_RESUME) { putExtra(EXTRA_ID, id) }
        fun remove(context: Context, id: String) = send(context, ACTION_REMOVE) { putExtra(EXTRA_ID, id) }
    }
}
