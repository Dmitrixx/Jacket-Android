package app.jacket.mobile.byedpi.services

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import app.jacket.mobile.MainActivity
import app.jacket.mobile.R
import app.jacket.mobile.byedpi.ByeDpiController
import app.jacket.mobile.byedpi.core.ByeDpiNative
import app.jacket.mobile.byedpi.core.ByedpiVpnSocketProtector
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

internal class ByeDpiServiceSession(
    private val service: Service,
    private val vpnService: VpnService?
) {
    companion object {
        private const val TAG = "ByeDpiSession"
        private const val SOCKS_HOST = "127.0.0.1"
        private const val SOCKS_PORT = 1080
        private const val START_TIMEOUT_MS = 15_000L
    }

    @Volatile private var stopping = false
    @Volatile private var engine: ByeDpiNative? = null
    @Volatile private var proxyThread: Thread? = null
    @Volatile private var tunnelThread: Thread? = null
    @Volatile private var startupThread: Thread? = null
    @Volatile private var tun: ParcelFileDescriptor? = null

    fun start(args: Array<String>, dns: String, ipv6: Boolean) {
        if (engine != null || stopping) return
        emit(ByeDpiController.STATE_STARTING, "Запускаем ByeDPI…")

        try {
            if (vpnService != null) {
                ByedpiVpnSocketProtector.attach(vpnService)
                tun = establishTunnel(dns, ipv6)
            }

            val native = ByeDpiNative()
            engine = native

            proxyThread = thread(name = "ByeDPI-Proxy", isDaemon = true) {
                val result = runCatching { native.startProxy(args) }
                    .getOrElse { error ->
                        Log.e(TAG, "ByeDPI native proxy failed", error)
                        -1
                    }
                if (!stopping) {
                    fail("ByeDPI завершился с кодом $result")
                }
            }

            startupThread = thread(name = "ByeDPI-Startup", isDaemon = true) {
                try {
                    awaitSocksProxy()
                    if (vpnService != null) {
                        val descriptor = tun ?: throw IOException("VPN TUN не создан")
                        val config = buildTunnelConfig()
                        tunnelThread = thread(name = "ByeDPI-HEV", isDaemon = true) {
                            val result = runCatching {
                                native.startTunnel(config, descriptor.fd)
                            }.getOrElse { error ->
                                Log.e(TAG, "HEV tunnel failed", error)
                                -1
                            }
                            if (!stopping) fail("Туннель ByeDPI завершился с кодом $result")
                        }
                        Thread.sleep(150)
                    }
                    if (!stopping) {
                        emit(
                            ByeDpiController.STATE_RUNNING,
                            if (vpnService != null) "ByeDPI VPN работает" else "SOCKS5 прокси работает на 127.0.0.1:1080"
                        )
                    }
                } catch (error: Exception) {
                    if (!stopping) fail(error.message ?: "Не удалось запустить ByeDPI")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to initialize ByeDPI", error)
            fail(error.message ?: "Не удалось запустить ByeDPI")
        }
    }

    fun stop() {
        if (stopping) return
        stopping = true
        emit(ByeDpiController.STATE_STOPPING, "Останавливаем ByeDPI…")
        thread(name = "ByeDPI-Stop", isDaemon = true) {
            runCatching { engine?.stopTunnel() }
            runCatching { engine?.stopProxy() }

            runCatching { startupThread?.join(1_000) }
            runCatching { tunnelThread?.join(2_000) }
            runCatching { proxyThread?.join(2_000) }
            runCatching { tun?.close() }
            tun = null
            vpnService?.let(ByedpiVpnSocketProtector::detach)

            emit(ByeDpiController.STATE_STOPPED, "ByeDPI остановлен")
            runCatching { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }
            service.stopSelf()

            // The native engine owns process-global state and permits one run per process.
            // It is isolated in :byedpi_native so a clean process restart is safe.
            Process.killProcess(Process.myPid())
        }
    }

    private fun fail(message: String) {
        if (stopping) return
        emit(ByeDpiController.STATE_ERROR, message)
        stop()
    }

    private fun establishTunnel(dns: String, ipv6: Boolean): ParcelFileDescriptor {
        val vpn = vpnService ?: throw IllegalStateException("VPN-сервис не запущен")
        val builder = vpn.Builder()
            .setSession("Lampa ByeDPI")
            .setMtu(8500)
            .addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(service, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

        if (ipv6) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }
        if (dns.isNotBlank()) builder.addDnsServer(dns)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        return builder.establish() ?: throw IOException("Android не разрешил создать VPN TUN")
    }

    private fun awaitSocksProxy() {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (!stopping && System.currentTimeMillis() < deadline) {
            val ready = runCatching {
                Socket().use { socket ->
                    vpnService?.protect(socket)
                    socket.connect(InetSocketAddress(SOCKS_HOST, SOCKS_PORT), 300)
                    socket.soTimeout = 500
                    socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                    socket.getInputStream().read() == 5 &&
                        socket.getInputStream().read() == 0
                }
            }.getOrDefault(false)
            if (ready) return
            Thread.sleep(75)
        }
        throw IOException("SOCKS5 прокси не открыл порт 1080 за 15 секунд")
    }

    private fun buildTunnelConfig(): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: 8500")
        appendLine("misc:")
        appendLine("  task-stack-size: 81920")
        appendLine("socks5:")
        appendLine("  address: $SOCKS_HOST")
        appendLine("  port: $SOCKS_PORT")
        appendLine("  udp: udp")
    }

    private fun emit(state: String, message: String) {
        service.getSharedPreferences(
            ByeDpiController.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putString(ByeDpiController.KEY_STATE, state)
            .putString(ByeDpiController.KEY_MESSAGE, message)
            .apply()
        service.sendBroadcast(
            Intent(ByeDpiController.ACTION_STATE)
                .setPackage(service.packageName)
                .putExtra(ByeDpiController.EXTRA_STATE, state)
                .putExtra(ByeDpiController.EXTRA_MESSAGE, message)
        )
    }
}
