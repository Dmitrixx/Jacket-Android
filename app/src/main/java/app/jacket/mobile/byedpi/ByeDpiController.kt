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
    const val STATE_STARTING = "starting"
    const val STATE_RUNNING = "running"
    const val STATE_STOPPING = "stopping"
    const val STATE_ERROR = "error"

    const val PREFERENCES_NAME = "lampa_bye_dpi"
    const val KEY_STATE = "engine_state"
    const val KEY_MESSAGE = "engine_message"

    fun start(
        context: Context,
        vpnMode: Boolean,
        args: Array<String>,
        dns: String,
        ipv6: Boolean
    ) {
        val intent = Intent(
            context,
            if (vpnMode) ByeDpiVpnService::class.java else ByeDpiProxyService::class.java
        ).apply {
            action = ByeDpiServiceContract.ACTION_START
            putExtra(ByeDpiServiceContract.EXTRA_ARGS, args)
            putExtra(ByeDpiServiceContract.EXTRA_DNS, dns)
            putExtra(ByeDpiServiceContract.EXTRA_IPV6, ipv6)
        }
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATE, STATE_STARTING)
            .putString(KEY_MESSAGE, "Запускаем ByeDPI…")
            .apply()
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATE, STATE_STOPPING)
            .putString(KEY_MESSAGE, "Останавливаем ByeDPI…")
            .apply()
        context.stopService(Intent(context, ByeDpiVpnService::class.java))
        context.stopService(Intent(context, ByeDpiProxyService::class.java))
    }
}

internal object ByeDpiServiceContract {
    const val ACTION_START = "app.jacket.mobile.byedpi.START"
    const val EXTRA_ARGS = "args"
    const val EXTRA_DNS = "dns"
    const val EXTRA_IPV6 = "ipv6"
}
