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
import app.jacket.mobile.byedpi.ByeDpiController
import app.jacket.mobile.byedpi.ByeDpiServiceContract

class ByeDpiProxyService : Service() {
    private var session: ByeDpiServiceSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startIntent = intent
            ?: ByeDpiController.restoreSessionIntent(this, vpnMode = false)
            ?: run {
                stopSelf(startId)
                return START_NOT_STICKY
            }

        when (startIntent.action) {
            ByeDpiServiceContract.ACTION_QUERY -> {
                val current = session
                if (current != null) {
                    current.publishCurrentState()
                    // QUERY must not downgrade the running foreground service to NOT_STICKY.
                    return if (ByeDpiController.isSessionWanted(this)) {
                        START_REDELIVER_INTENT
                    } else {
                        START_NOT_STICKY
                    }
                }

                val restored = ByeDpiController.restoreSessionIntent(this, vpnMode = false)
                if (restored != null) return startSession(restored, startId)

                ByeDpiController.publishState(
                    this,
                    ByeDpiController.STATE_STOPPED,
                    "ByeDPI остановлен"
                )
                stopSelf(startId)
                return START_NOT_STICKY
            }

            ByeDpiServiceContract.ACTION_STOP -> {
                ByeDpiController.clearSession(this)
                val current = session
                if (current != null) {
                    current.stop()
                } else {
                    ByeDpiController.publishState(
                        this,
                        ByeDpiController.STATE_STOPPED,
                        "ByeDPI уже остановлен"
                    )
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }

            ByeDpiServiceContract.ACTION_START -> return startSession(startIntent, startId)

            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    private fun startSession(startIntent: Intent, startId: Int): Int {
        startForegroundCompat(createNotification("Запускается локальный SOCKS5 прокси ByeDPI"))
        val args = startIntent.getStringArrayExtra(ByeDpiServiceContract.EXTRA_ARGS)
        if (args.isNullOrEmpty()) {
            ByeDpiController.clearSession(this)
            ByeDpiController.publishState(
                this,
                ByeDpiController.STATE_ERROR,
                "Не переданы параметры запуска ByeDPI"
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val dns = startIntent.getStringExtra(ByeDpiServiceContract.EXTRA_DNS).orEmpty()
        val ipv6 = startIntent.getBooleanExtra(ByeDpiServiceContract.EXTRA_IPV6, false)
        if (!ByeDpiController.persistSession(this, false, args, dns, ipv6)) {
            ByeDpiController.publishState(
                this,
                ByeDpiController.STATE_ERROR,
                "Не удалось сохранить конфигурацию SOCKS5 ByeDPI"
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (session == null) session = ByeDpiServiceSession(this, null)
        session?.start(args, dns, ipv6)
        return START_REDELIVER_INTENT
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
