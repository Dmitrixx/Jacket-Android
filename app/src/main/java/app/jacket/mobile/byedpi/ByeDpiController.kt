package app.jacket.mobile.byedpi

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.jacket.mobile.byedpi.services.ByeDpiProxyService
import app.jacket.mobile.byedpi.services.ByeDpiVpnService

internal object ByeDpiController {
    const val ACTION_STATE = "app.jacket.mobile.BYEDPI_STATE"
    const val EXTRA_STATE = "state"
    const val EXTRA_MESSAGE = "message"

    const val STATE_STOPPED = "stopped"
    const val STATE_CHECKING = "checking"
    const val STATE_STARTING = "starting"
    const val STATE_RUNNING = "running"
    const val STATE_STOPPING = "stopping"
    const val STATE_ERROR = "error"

    const val PREFERENCES_NAME = "lampa_bye_dpi"
    const val KEY_STATE = "engine_state"
    const val KEY_MESSAGE = "engine_message"
    const val KEY_MODE = "mode"

    fun start(
        context: Context,
        vpnMode: Boolean,
        args: Array<String>,
        dns: String,
        ipv6: Boolean
    ): Boolean {
        val serviceClass = if (vpnMode) ByeDpiVpnService::class.java else ByeDpiProxyService::class.java
        val intent = Intent(context, serviceClass).apply {
            action = ByeDpiServiceContract.ACTION_START
            putExtra(ByeDpiServiceContract.EXTRA_ARGS, args)
            putExtra(ByeDpiServiceContract.EXTRA_DNS, dns)
            putExtra(ByeDpiServiceContract.EXTRA_IPV6, ipv6)
        }

        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, if (vpnMode) "vpn" else "proxy")
            .apply()
        publishState(context, STATE_STARTING, "Запускаем ByeDPI…")

        return runCatching {
            ContextCompat.startForegroundService(context, intent)
            true
        }.getOrElse { error ->
            publishState(
                context,
                STATE_ERROR,
                "Не удалось отправить команду запуска ByeDPI: ${error.message ?: error.javaClass.simpleName}"
            )
            false
        }
    }

    fun query(context: Context) {
        publishState(context, STATE_CHECKING, "Проверяем службу ByeDPI…")
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val vpnMode = preferences.getString(KEY_MODE, "vpn") != "proxy"
        val serviceClass = if (vpnMode) ByeDpiVpnService::class.java else ByeDpiProxyService::class.java

        runCatching {
            context.startService(
                Intent(context, serviceClass).setAction(ByeDpiServiceContract.ACTION_QUERY)
            )
        }.onFailure { error ->
            publishState(
                context,
                STATE_ERROR,
                "Не удалось проверить службу ByeDPI: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    fun stop(context: Context) {
        publishState(context, STATE_STOPPING, "Останавливаем ByeDPI…")
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val vpnMode = preferences.getString(KEY_MODE, "vpn") != "proxy"
        val serviceClass = if (vpnMode) ByeDpiVpnService::class.java else ByeDpiProxyService::class.java

        runCatching {
            context.startService(
                Intent(context, serviceClass).setAction(ByeDpiServiceContract.ACTION_STOP)
            )
        }.onFailure { error ->
            publishState(
                context,
                STATE_ERROR,
                "Не удалось отправить команду остановки ByeDPI: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    fun publishState(context: Context, state: String, message: String) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATE, state)
            .putString(KEY_MESSAGE, message)
            .apply()
        context.sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(context.packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }
}

internal object ByeDpiServiceContract {
    const val ACTION_START = "app.jacket.mobile.byedpi.START"
    const val ACTION_QUERY = "app.jacket.mobile.byedpi.QUERY"
    const val ACTION_STOP = "app.jacket.mobile.byedpi.STOP"
    const val EXTRA_ARGS = "args"
    const val EXTRA_DNS = "dns"
    const val EXTRA_IPV6 = "ipv6"
}
