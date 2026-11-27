package app.jacket.mobile.byedpi.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.jacket.mobile.MainActivity
import app.jacket.mobile.R
import app.jacket.mobile.byedpi.ByeDpiServiceContract

class ByeDpiProxyService : Service() {
    private var session: ByeDpiServiceSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ByeDpiServiceContract.ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(createNotification("Запускается локальный SOCKS5 прокси"))
        val args = intent.getStringArrayExtra(ByeDpiServiceContract.EXTRA_ARGS)
        if (args.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (session == null) session = ByeDpiServiceSession(this, null)
        session?.start(
            args,
            intent.getStringExtra(ByeDpiServiceContract.EXTRA_DNS).orEmpty(),
            intent.getBooleanExtra(ByeDpiServiceContract.EXTRA_IPV6, false)
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lampa)
            .setContentTitle("Lampa • ByeDPI")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    1,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "ByeDPI Proxy",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "lampa_byedpi_proxy"
        const val NOTIFICATION_ID = 3201
    }
}
