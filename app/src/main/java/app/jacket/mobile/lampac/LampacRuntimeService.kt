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
import kotlinx.coroutines.cancelAndJoin
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
 * Do not depend on which platform-specific Node binary the generic upstream
 * Lampac ZIP contains. The Playwright JS driver is architecture-independent;
 * use Debian's ARM64 Node package via PLAYWRIGHT_NODEJS_PATH.
 */
internal fun buildLampacServerCommand(): String =
    "cd /opt/lampac && " +
        "export DOTNET_ROOT=/opt/dotnet " +
        "DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=0 " +
        "DOTNET_CLI_TELEMETRY_OPTOUT=1 " +
        "DOTNET_NOLOGO=1 " +
        "PLAYWRIGHT_NODEJS_PATH=/usr/bin/node " +
        "PATH=/opt/dotnet:\$PATH && " +
        "exec /opt/dotnet/dotnet /opt/lampac/Core.dll"

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
            val activeOperation = operationJob
            if (activeOperation?.isActive == true) {
                if (action == ACTION_RESTART &&
                    LampacRuntimeState.state.value.phase == LampacRuntimePhase.RUNNING
                ) {
                    // The initial START operation publishes RUNNING just before it
                    // completes. Queue a restart behind it instead of dropping the
                    // user's module-save request during that small transition.
                    operationJob = scope.launch {
                        activeOperation.join()
                        restartInstalledServer()
                    }
                } else {
                    updateNotification(LampacRuntimeState.state.value.message)
                }
                return START_NOT_STICKY
            }
            operationJob = scope.launch {
                when (action) {
                    ACTION_INSTALL_AND_START -> installAndStart()
                    ACTION_START -> startInstalledServer()
                    ACTION_RESTART -> restartInstalledServer()
                    ACTION_UPDATE_LAMPAC -> updateInstalledLampac(intent)
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

    private suspend fun updateInstalledLampac(intent: Intent?) {
        val version = intent?.getStringExtra(EXTRA_UPDATE_VERSION).orEmpty()
        val url = intent?.getStringExtra(EXTRA_UPDATE_URL).orEmpty()
        val sha256 = intent?.getStringExtra(EXTRA_UPDATE_SHA256).orEmpty()
        val sizeBytes = intent?.getLongExtra(EXTRA_UPDATE_SIZE, -1L) ?: -1L

        LampacRuntimeState.clearLogs()
        try {
            val installer = LampacRuntimeInstaller(applicationContext)
            check(installer.isInstalled()) { "Сначала установите встроенный Lampac" }
            check(version.isNotBlank() && url.isNotBlank() && sha256.isNotBlank() && sizeBytes > 0L) {
                "Данные обновления Lampac неполные"
            }

            LampacRuntimeState.update(LampacRuntimePhase.STOPPING, "Останавливаем Lampac перед обновлением…")
            updateNotification("Обновление Lampac…")
            isStopping.set(true)
            ExternalMediaProxy.stop()
            stopSession()
            monitorJob?.cancelAndJoin()
            monitorJob = null
            serverSession = null
            isStopping.set(false)

            var lastNotificationAt = 0L
            installer.updateFromRelease(version, url, sha256, sizeBytes) { progress ->
                LampacRuntimeState.update(LampacRuntimePhase.INSTALLING, progress.message, progress.percent)
                LampacRuntimeState.appendLog(progress.message)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastNotificationAt >= NOTIFICATION_UPDATE_INTERVAL_MS ||
                    progress.phase == "updated" || progress.percent == 100
                ) {
                    lastNotificationAt = now
                    updateNotification(progress.percent?.let { "${progress.message} • $it%" } ?: progress.message)
                }
            }
            LampacRuntimeState.update(LampacRuntimePhase.INSTALLED, "Lampac обновлён. Запускаем сервер…")
            startInstalledServer()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = "Обновление Lampac не завершилось: ${safeMessage(error)}"
            LampacRuntimeState.appendLog(message)
            // A staged update never replaces the active server until the archive
            // has passed validation. On failure, bring the old server back online.
            if (LampacRuntimeInstaller(applicationContext).isInstalled()) {
                LampacRuntimeState.update(LampacRuntimePhase.INSTALLED, "Обновление отменено. Запускаем прежний Lampac…")
                runCatching { startInstalledServer() }
                if (LampacRuntimeState.state.value.phase != LampacRuntimePhase.RUNNING) fail(message)
            } else {
                fail(message)
            }
        }
    }

    private suspend fun startInstalledServer(aladinPatchRestartAttempt: Int = 0) {
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
            if (!ExternalMediaProxy.start()) {
                LampacRuntimeState.appendLog("Не удалось запустить локальный прокси внешнего плеера.")
            }
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

            // Aladin's Accepts-Controls value can make its generated /proxy URL exceed
            // Kestrel's request-line limit. Force compact, server-stored proxy keys
            // before Lampac dynamically compiles modules on this startup.
            val providerPatch = withContext(Dispatchers.IO) {
                LampacProviderCompatibilityPatches.apply(File(host.debianRootfsDir, "opt/lampac"))
            }
            when {
                providerPatch.updatedFiles > 0 ->
                    LampacRuntimeState.appendLog("Aladin: применено исправление длинных /proxy-ссылок.")
                providerPatch.alreadyPatchedFiles > 0 ->
                    LampacRuntimeState.appendLog("Aladin: исправление коротких /proxy-ссылок уже установлено.")
                providerPatch.filesFound == 0 ->
                    LampacRuntimeState.appendLog("Aladin: исходник динамического модуля не найден; проверьте комплект Lampac.")
            }

            // Keep all balancers in the selector. Lampac checks every provider,
            // but the stock plugin discards show=false entries before startSource,
            // preventing the user from manually retrying grey/negative providers.
            val onlineUiPatch = runCatching {
                withContext(Dispatchers.IO) {
                    LampacOnlinePluginCompatibilityPatches.apply(File(host.debianRootfsDir, "opt/lampac"))
                }
            }
            onlineUiPatch.onSuccess { report ->
                when {
                    report.updatedFiles > 0 ->
                        LampacRuntimeState.appendLog("Балансеры: включён ручной выбор всех источников, включая отмеченные без результата.")
                    report.alreadyPatchedFiles > 0 ->
                        LampacRuntimeState.appendLog("Балансеры: ручной выбор всех источников уже включён.")
                    report.filesFound == 0 ->
                        LampacRuntimeState.appendLog("Балансеры: Online/plugin.js не найден; ручной выбор всех источников не применён.")
                }
            }.onFailure { error ->
                LampacRuntimeState.appendLog(
                    "Балансеры: не удалось применить исправление ручного выбора (${error.javaClass.simpleName})."
                )
            }

            // Existing installs keep their original init.conf across APK updates.
            // Apply the safe Android module profile before launching Core.dll.
            LampacEmbeddedConfig.ensureAndroidRuntimeConfig(File(host.debianRootfsDir, "opt/lampac"))
            val commands = LampacProotCommands(host)
            val command = buildLampacServerCommand()
            // Keep a side reference: withContext may throw on return if the
            // service is cancelled just after PRoot starts, otherwise the newly
            // spawned PTY session would be lost and the child could be orphaned.
            val launchedSession = AtomicReference<ProotLauncher.Session?>()
            val session = try {
                withContext(Dispatchers.IO) {
                    commands.start(listOf("login", LAMPAC_DEBIAN_ALIAS, "--user", "root", "--", command))
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

            // ModuleRepository.UpdateModules runs inside Core.dll startup and may
            // download/replace the dynamic Aladin source after our pre-start patch.
            // Re-apply after the first ready signal; if it changed the source, restart
            // once so Lampac compiles the patched source (DynamicModule is off in the
            // Android profile, so a file change alone cannot trigger hot recompilation).
            val postStartupPatch = withContext(Dispatchers.IO) {
                LampacProviderCompatibilityPatches.apply(File(host.debianRootfsDir, "opt/lampac"))
            }
            if (postStartupPatch.updatedFiles > 0) {
                LampacRuntimeState.appendLog(
                    "Aladin: исходник обновлён репозиторием модулей; перезапускаем Lampac для компиляции исправления."
                )
                if (aladinPatchRestartAttempt >= 1) {
                    throw IOException("Исходник Aladin повторно заменён во время запуска; исправление не удалось закрепить.")
                }

                isStopping.set(true)
                stopSession()
                monitorJob?.cancelAndJoin()
                monitorJob = null
                serverSession = null
                isStopping.set(false)
                startInstalledServer(aladinPatchRestartAttempt + 1)
                return
            }

            if (serverSession === session && !isStopping.get()) {
                if (!ExternalMediaProxy.start()) {
                    LampacRuntimeState.appendLog("Не удалось запустить локальный прокси внешнего плеера.")
                }
                LampacRuntimeState.update(LampacRuntimePhase.RUNNING, "Lampac работает • http://127.0.0.1:9118")
                updateNotification("Lampac работает • 127.0.0.1:9118")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Stop the monitor before our intentional Ctrl+C cleanup so the
            // expected signal exit (130) cannot replace the actual startup error.
            isStopping.set(true)
            ExternalMediaProxy.stop()
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
                    ExternalMediaProxy.stop()
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

    private suspend fun restartInstalledServer() {
        if (!LampacRuntimeInstaller(applicationContext).isInstalled()) {
            LampacRuntimeState.update(
                LampacRuntimePhase.ERROR,
                "Встроенный Lampac не установлен. Сначала выберите «Установить полный Lampac»."
            )
            finishService()
            return
        }

        LampacRuntimeState.update(LampacRuntimePhase.STOPPING, "Применяем настройки модулей и перезапускаем Lampac…")
        updateNotification("Перезапуск Lampac…")
        isStopping.set(true)
        ExternalMediaProxy.stop()
        stopSession()
        monitorJob?.cancelAndJoin()
        monitorJob = null
        serverSession = null
        isStopping.set(false)
        startInstalledServer()
    }

    private fun stopServer() {
        if (!isStopping.compareAndSet(false, true)) return
        ExternalMediaProxy.stop()
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
        ExternalMediaProxy.stop()
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
        ExternalMediaProxy.stop()
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
        const val ACTION_RESTART = "app.jacket.mobile.lampac.RESTART"
        const val ACTION_UPDATE_LAMPAC = "app.jacket.mobile.lampac.UPDATE_LAMPAC"
        const val ACTION_STOP = "app.jacket.mobile.lampac.STOP"

        private const val EXTRA_UPDATE_VERSION = "update_version"
        private const val EXTRA_UPDATE_URL = "update_url"
        private const val EXTRA_UPDATE_SHA256 = "update_sha256"
        private const val EXTRA_UPDATE_SIZE = "update_size"

        fun installAndStart(context: Context) =
            startForeground(context, ACTION_INSTALL_AND_START)

        fun start(context: Context) =
            startForeground(context, ACTION_START)

        fun restart(context: Context) =
            startForeground(context, ACTION_RESTART)

        fun updateLampac(
            context: Context,
            version: String,
            url: String,
            sha256: String,
            sizeBytes: Long
        ) {
            val intent = Intent(context, LampacRuntimeService::class.java)
                .setAction(ACTION_UPDATE_LAMPAC)
                .putExtra(EXTRA_UPDATE_VERSION, version)
                .putExtra(EXTRA_UPDATE_URL, url)
                .putExtra(EXTRA_UPDATE_SHA256, sha256)
                .putExtra(EXTRA_UPDATE_SIZE, sizeBytes)
            ContextCompat.startForegroundService(context, intent)
        }

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
