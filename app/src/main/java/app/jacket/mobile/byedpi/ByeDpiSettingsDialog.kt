package app.jacket.mobile.byedpi

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.jacket.mobile.byedpi.core.ByeDpiArgumentParser
import app.jacket.mobile.byedpi.services.ByeDpiVpnService
import java.util.Locale

private const val PREFS_NAME = "lampa_bye_dpi"
private const val KEY_MODE = "mode"
private const val KEY_STRATEGY = "strategy"
private const val KEY_DNS = "dns"
private const val KEY_IPV6 = "ipv6"
private const val DEFAULT_STRATEGY = "--disorder 1"

@Composable
internal fun ByeDpiSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var mode by remember {
        mutableStateOf(preferences.getString(KEY_MODE, "vpn") ?: "vpn")
    }
    var strategy by remember {
        mutableStateOf(preferences.getString(KEY_STRATEGY, DEFAULT_STRATEGY) ?: DEFAULT_STRATEGY)
    }
    var dns by remember {
        mutableStateOf(preferences.getString(KEY_DNS, "1.1.1.1") ?: "1.1.1.1")
    }
    var ipv6 by remember { mutableStateOf(preferences.getBoolean(KEY_IPV6, false)) }
    var serviceState by remember { mutableStateOf(ByeDpiController.STATE_STOPPED) }
    var serviceMessage by remember { mutableStateOf("Служба остановлена") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingArgs by remember { mutableStateOf<List<String>?>(null) }

    fun startService(args: List<String>) {
        ByeDpiController.start(
            context = context,
            vpnMode = mode == "vpn",
            args = args.toTypedArray(),
            dns = dns.trim(),
            ipv6 = ipv6
        )
        serviceState = ByeDpiController.STATE_STARTING
        serviceMessage = "Запускаем локальный ByeDPI…"
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val args = pendingArgs
        pendingArgs = null
        if (result.resultCode == Activity.RESULT_OK && args != null) {
            startService(args)
        } else {
            error = "Разрешение Android на создание VPN не предоставлено."
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != ByeDpiController.ACTION_STATE) return
                serviceState = intent.getStringExtra(ByeDpiController.EXTRA_STATE)
                    ?: ByeDpiController.STATE_STOPPED
                serviceMessage = intent.getStringExtra(ByeDpiController.EXTRA_MESSAGE)
                    ?: "Состояние ByeDPI обновлено"
                if (serviceState == ByeDpiController.STATE_ERROR) {
                    error = serviceMessage
                } else if (serviceState == ByeDpiController.STATE_RUNNING) {
                    error = null
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ByeDpiController.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val active = serviceState == ByeDpiController.STATE_STARTING ||
        serviceState == ByeDpiController.STATE_RUNNING

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ByeDPI • обход DPI", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "ByeDPI встроен непосредственно в Lampa. Настройки и служба находятся в этом меню; отдельное приложение устанавливать не нужно."
                )

                Text("Режим работы", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = mode == "vpn",
                        enabled = !active,
                        onClick = { mode = "vpn" }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("VPN всего устройства")
                        Text(
                            "Перенаправляет трафик через локальный ByeDPI. Android запросит системное разрешение.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = mode == "proxy",
                        enabled = !active,
                        onClick = { mode = "proxy" }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Локальный SOCKS5")
                        Text(
                            "Запускает прокси 127.0.0.1:1080 без захвата всего трафика устройства.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = strategy,
                    onValueChange = {
                        strategy = it
                        error = null
                    },
                    enabled = !active,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Стратегия ByeDPI") },
                    supportingText = {
                        Text("Например: --disorder 1. Можно вставить полную строку параметров ByeDPI.")
                    },
                    minLines = 2,
                    maxLines = 4
                )

                OutlinedTextField(
                    value = dns,
                    onValueChange = { dns = it },
                    enabled = !active && mode == "vpn",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("DNS-сервер") }
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("IPv6")
                        Text(
                            "Добавить IPv6-маршрут в локальный VPN-туннель.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = ipv6,
                        enabled = !active && mode == "vpn",
                        onCheckedChange = { ipv6 = it }
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        when (serviceState) {
                            ByeDpiController.STATE_RUNNING -> "Работает"
                            ByeDpiController.STATE_STARTING -> "Запуск"
                            ByeDpiController.STATE_ERROR -> "Ошибка"
                            else -> "Остановлено"
                        },
                        fontWeight = FontWeight.SemiBold,
                        color = if (serviceState == ByeDpiController.STATE_ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Text(serviceMessage, style = MaterialTheme.typography.bodySmall)
                }

                if (error != null) {
                    Text(
                        error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(
                    "Это локальный обход DPI, а не удалённый VPN: трафик не отправляется на сторонний сервер, не шифруется этим инструментом и публичный IP-адрес не меняется. Android разрешает только один активный VPN одновременно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !active && serviceState != ByeDpiController.STATE_STARTING,
                    onClick = {
                        val parsed = runCatching { ByeDpiArgumentParser.build(strategy) }
                        parsed.onSuccess { args ->
                            if (mode == "vpn" && dns.isNotBlank() &&
                                !dns.matches(Regex("""[0-9a-fA-F:.]+"""))
                            ) {
                                error = "DNS должен быть указан IP-адресом IPv4 или IPv6."
                                return@Button
                            }
                            preferences.edit()
                                .putString(KEY_MODE, mode)
                                .putString(KEY_STRATEGY, strategy)
                                .putString(KEY_DNS, dns.trim())
                                .putBoolean(KEY_IPV6, ipv6)
                                .apply()
                            error = null
                            if (mode == "vpn") {
                                pendingArgs = args.toList()
                                val prepare = VpnService.prepare(context)
                                if (prepare == null) {
                                    pendingArgs = null
                                    startService(args.toList())
                                } else {
                                    vpnPermissionLauncher.launch(prepare)
                                }
                            } else {
                                startService(args.toList())
                            }
                        }.onFailure {
                            error = it.message ?: "Не удалось разобрать параметры ByeDPI"
                        }
                    }
                ) {
                    Text("Запустить")
                }

                TextButton(
                    enabled = active || serviceState == ByeDpiController.STATE_ERROR,
                    onClick = {
                        ByeDpiController.stop(context)
                        serviceState = ByeDpiController.STATE_STOPPED
                        serviceMessage = "Остановка ByeDPI…"
                    }
                ) {
                    Text("Остановить")
                }
                TextButton(onClick = onDismiss) {
                    Text("Закрыть")
                }
            }
        }
    )
}
