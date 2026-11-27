package app.jacket.mobile.byedpi.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.jacket.mobile.MainActivity
import app.jacket.mobile.R
import app.jacket.mobile.byedpi.ByeDpiServiceContract

class ByeDpiVpnService : VpnService() {
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

        startForegroundCompat(createNotification("Запускается VPN-туннель ByeDPI"))
        val args = intent.getStringArrayExtra(ByeDpiServiceContract.EXTRA_ARGS)
        if (args.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (session == null) session = ByeDpiServiceSession(this, this)
        session?.start(
            args,
            intent.getStringExtra(ByeDpiServiceContract.EXTRA_DNS).orEmpty(),
            intent.getBooleanExtra(ByeDpiServiceContract.EXTRA_IPV6, false)
        )
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        session?.stop()
        super.onRevoke()
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else null
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lampa)
            .setContentTitle("Lampa • ByeDPI VPN")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    2,
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
                    "ByeDPI VPN",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "lampa_byedpi_vpn"
        const val NOTIFICATION_ID = 3202
    }
}
