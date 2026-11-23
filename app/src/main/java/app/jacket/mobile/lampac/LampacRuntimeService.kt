package app.jacket.mobile.lampac

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.jacket.mobile.BuildConfig
import app.jacket.mobile.MainActivity
import app.jacket.mobile.R
import id.or.oo.pr.engine.ProotLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-started owner of the embedded Lampac process.
 *
 * It runs only while the user has explicitly installed or started the server.
 * No boot receiver or background auto-start is registered.
 */
class LampacRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandLock = Any()
    @Volatile private var serverSession: ProotLauncher.Session? = null
    private var operationJob: Job? = null
    private var monitorJob: Job? = null
    private val isStopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            startAsForeground("Останавливаем Lampac…")
            stopServer()
            return START_NOT_STICKY
        }

        startAsForeground("Подготовка встроенного Lampac…")
        synchronized(commandLock) {
            if (operationJob?.isActive == true) {
                updateNotification(LampacRuntimeState.state.value.message)
                return START_NOT_STICKY
            }
            operationJob = scope.launch {
                when (action) {
                    ACTION_INSTALL_AND_START -> installAndStart()
                    ACTION_START -> startInstalledServer()
                    else -> {
                        LampacRuntimeState.update(LampacRuntimePhase.ERROR, "Неизвестная команда Lampac")
                        finishService()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun installAndStart() {
        LampacRuntimeState.clearLogs()
        LampacRuntimeState.update(LampacRuntimePhase.INSTALLING, "Установка встроенного Lampac…")
        updateNotification("Установка встроенного Lampac…")
        try {
            val installer = LampacRuntimeInstaller(applicationContext)
            var lastNotificationAt = 0L
            installer.install { progress ->
                val phase = progress.phase
                val message = progress.message
                val value = progress.percent
                LampacRuntimeState.update(LampacRuntimePhase.INSTALLING, message, value)
                LampacRuntimeState.appendLog(message)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastNotificationAt >= NOTIFICATION_UPDATE_INTERVAL_MS ||
                    phase == "installed" || value == 100
                ) {
                    lastNotificationAt = now
                    updateNotification(value?.let { "$message • $it%" } ?: message)
                }
            }
            LampacRuntimeState.update(LampacRuntimePhase.INSTALLED, "Lampac установлен. Запускаем сервер…")
            startInstalledServer()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            fail("Установка Lampac не завершилась: ${safeMessage(error)}")
        }
    }

    private suspend fun startInstalledServer() {
        val installer = LampacRuntimeInstaller(applicationContext)
        if (!installer.isInstalled()) {
            LampacRuntimeState.update(
                LampacRuntimePhase.ERROR,
                "Встроенный Lampac не установлен. Сначала выберите «Установить полный Lampac»."
            )
            finishService()
            return
        }
        if (serverSession != null) {
            LampacRuntimeState.update(LampacRuntimePhase.RUNNING, "Lampac уже работает на 127.0.0.1:9118")
            updateNotification("Lampac работает • 127.0.0.1:9118")
            return
        }

        isStopping.set(false)
        LampacRuntimeState.update(
            LampacRuntimePhase.STARTING,
            "Запускаем Lampac. Первый запуск может занять до 5 минут: .NET компилирует модули…"
        )
        updateNotification("Запускаем Lampac…")
        try {
            val host = LampaProotHost(applicationContext)
            LampacProotBootstrap.prepare(host)
            // Existing installs keep their original init.conf across APK updates.
            // Apply the safe Android module profile before launching Core.dll.
            LampacEmbeddedConfig.ensureAndroidRuntimeConfig(File(host.debianRootfsDir, "opt/lampac"))
            val commands = LampacProotCommands(host)
            val command = "cd /opt/lampac && " +
                "export DOTNET_ROOT=/opt/dotnet " +
                "DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=0 " +
                "DOTNET_CLI_TELEMETRY_OPTOUT=1 " +
                "DOTNET_NOLOGO=1 " +
                "PATH=/opt/dotnet:\$PATH && " +
                "exec /opt/dotnet/dotnet /opt/lampac/Core.dll"
            // Keep a side reference: withContext may throw on return if the
            // service is cancelled just after PRoot starts, otherwise the newly
            // spawned PTY session would be lost and the child could be orphaned.
            val launchedSession = AtomicReference<ProotLauncher.Session?>()
            val session = try {
                withContext(Dispatchers.IO) {
                    commands.start(listOf("login", "debian", "--user", "root", "--", command))
                        .also(launchedSession::set)
                }
            } catch (cancelled: CancellationException) {
                launchedSession.get()?.let { pendingSession ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { pendingSession.write(byteArrayOf(3)) }
                        repeat(30) {
                            if (pendingSession.exitCode != null) return@withContext
                            delay(50)
                        }
                        pendingSession.close()
                    }
                }
                throw cancelled
            }
            serverSession = session
            monitorServer(session)
            waitForServerReady(session)
            if (serverSession === session && !isStopping.get()) {
                LampacRuntimeState.update(LampacRuntimePhase.RUNNING, "Lampac работает • http://127.0.0.1:9118")
                updateNotification("Lampac работает • 127.0.0.1:9118")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Stop the monitor before our intentional Ctrl+C cleanup so the
            // expected signal exit (130) cannot replace the actual startup error.
            isStopping.set(true)
            stopSession()
            monitorJob?.cancel()
            serverSession = null
            fail("Не удалось запустить Lampac: ${safeMessage(error)}")
        }
    }

    private fun monitorServer(session: ProotLauncher.Session) {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            val outputLines = LampacOutputLineBuffer { line ->
                if (line.isNotBlank()) {
                    LampacRuntimeState.appendLog(line)
                    if (LampacRuntimeState.state.value.phase == LampacRuntimePhase.STARTING) {
                        updateNotification(line.take(100))
                    }
                }
            }
            while (isActive && !isStopping.get()) {
                val count = runCatching { session.read(buffer) }.getOrDefault(-1)
                when {
                    count > 0 -> outputLines.append(buffer, count)
                    count <= 0 && session.exitCode != null -> break
                    else -> delay(35)
                }
            }
            outputLines.finish()

            val exitCode = session.exitCode
            if (serverSession === session && !isStopping.get()) {
                withContext(Dispatchers.Main.immediate) {
                    serverSession = null
                    if (exitCode == 0) {
                        LampacRuntimeState.update(LampacRuntimePhase.INSTALLED, "Lampac остановился")
                    } else {
                        LampacRuntimeState.update(
                            LampacRuntimePhase.ERROR,
                            "Lampac завершился с кодом ${exitCode ?: "неизвестно"}. Проверьте журнал."
                        )
                    }
                    updateNotification(LampacRuntimeState.state.value.message)
                    finishService()
                }
            }
        }
    }

    private suspend fun waitForServerReady(session: ProotLauncher.Session) {
        val startedAtMs = android.os.SystemClock.elapsedRealtime()
        while (true) {
            if (isStopping.get()) throw CancellationException("Lampac остановлен пользователем")
            session.exitCode?.let { exitCode ->
                throw IOException("Процесс Lampac завершился с кодом ${exitCode} до готовности")
            }
            if (withContext(Dispatchers.IO) { probeLoopback() }) return
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (LampacStartupPolicy.hasTimedOut(startedAtMs, nowMs)) {
                throw IOException(
                    "Lampac не ответил за 5 минут. При первом запуске .NET компилирует модули; " +
                        "проверьте журнал и свободную память."
                )
            }
            delay(LampacStartupPolicy.PROBE_INTERVAL_MS)
        }
    }

    private fun probeLoopback(): Boolean {
        val connection = runCatching {
            (URL("http://127.0.0.1:9118/version?type=hash").openConnection() as HttpURLConnection).apply {
                connectTimeout = 900
                readTimeout = 1_200
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Lampa-Android/${BuildConfig.VERSION_NAME}")
            }
        }.getOrNull() ?: return false
        return try {
            val status = connection.responseCode
            status in 200..299 && connection.inputStream.use { it.read() >= 0 }
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun stopServer() {
        if (!isStopping.compareAndSet(false, true)) return
        operationJob?.cancel()
        scope.launch {
            LampacRuntimeState.update(LampacRuntimePhase.STOPPING, "Останавливаем Lampac…")
            updateNotification("Останавливаем Lampac…")
            stopSession()
            monitorJob?.cancel()
            serverSession = null
            if (LampacRuntimeInstaller(applicationContext).isInstalled()) {
                LampacRuntimeState.update(LampacRuntimePhase.INSTALLED, "Lampac остановлен")
            } else {
                LampacRuntimeState.update(LampacRuntimePhase.IDLE, "Lampac ещё не установлен")
            }
            finishService()
        }
    }

    private suspend fun stopSession() {
        val session = serverSession ?: return
        withContext(Dispatchers.IO) {
            runCatching { session.write(byteArrayOf(3)) }
            repeat(40) {
                if (session.exitCode != null) return@withContext
                delay(100)
            }
            session.close()
        }
    }

    private fun fail(message: String) {
        LampacRuntimeState.update(LampacRuntimePhase.ERROR, message)
        LampacRuntimeState.appendLog(message)
        updateNotification(message)
        scope.launch {
            delay(1_500)
            finishService()
        }
    }

    private fun finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground(status: String) {
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, LampacRuntimeService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lampa)
            .setContentTitle("Lampa • Lampac")
            .setContentText(LampacLogSanitizer.sanitize(status).take(140))
            .setContentIntent(openPending)
            .setOngoing(serverSession != null || operationJob?.isActive == true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_lampa, "Остановить", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Встроенный Lampac",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Статус локального медиасервера Lampa"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun safeMessage(error: Throwable): String =
        LampacLogSanitizer.sanitize(error.message.orEmpty()).take(200)
            .ifBlank { error.javaClass.simpleName }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isStopping.set(true)
        monitorJob?.cancel()
        serverSession?.close()
        serverSession = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "lampa-lampac-runtime"
        private const val NOTIFICATION_ID = 4412
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1_000L

        const val ACTION_INSTALL_AND_START = "app.jacket.mobile.lampac.INSTALL_AND_START"
        const val ACTION_START = "app.jacket.mobile.lampac.START"
        const val ACTION_STOP = "app.jacket.mobile.lampac.STOP"

        fun installAndStart(context: Context) =
            startForeground(context, ACTION_INSTALL_AND_START)

        fun start(context: Context) =
            startForeground(context, ACTION_START)

        fun stop(context: Context) {
            val intent = Intent(context, LampacRuntimeService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        private fun startForeground(context: Context, action: String) {
            val intent = Intent(context, LampacRuntimeService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
