package app.jacket.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.jacket.mobile.lampac.LampacRuntimeInstaller
import app.jacket.mobile.lampac.LampacRuntimePhase
import app.jacket.mobile.lampac.LampacRuntimeService
import app.jacket.mobile.lampac.LampacRuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LampaApp(onExit: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val runtime by LampacRuntimeState.state.collectAsState()
    var settingsVisible by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(false) }
    var checkingInstallation by remember { mutableStateOf(true) }
    val busy = checkingInstallation || runtime.phase == LampacRuntimePhase.INSTALLING ||
        runtime.phase == LampacRuntimePhase.STARTING ||
        runtime.phase == LampacRuntimePhase.STOPPING
    // Read the delegated StateFlow snapshot once; this also keeps nullable progress
    // smart-casts stable inside Compose's deferred progress lambda.
    val progress = runtime.progress

    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) {
            runCatching { LampacRuntimeInstaller(context).isInstalled() }.getOrDefault(false)
        }
        checkingInstallation = false
        if (installed && runtime.phase == LampacRuntimePhase.IDLE) {
            LampacRuntimeState.update(
                LampacRuntimePhase.INSTALLED,
                "Полный Lampac NextGen установлен. Сервер остановлен."
            )
        }
    }

    LaunchedEffect(runtime.phase) {
        when (runtime.phase) {
            LampacRuntimePhase.INSTALLED, LampacRuntimePhase.RUNNING -> installed = true
            LampacRuntimePhase.ERROR -> {
                installed = withContext(Dispatchers.IO) {
                    runCatching { LampacRuntimeInstaller(context).isInstalled() }.getOrDefault(false)
                }
            }
            else -> Unit
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        LampaWebViewScreen(
            modifier = Modifier.fillMaxSize(),
            onBack = onExit
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            shape = RoundedCornerShape(18.dp)
        ) {
            TextButton(onClick = { settingsVisible = true }) {
                Text("Lampac")
            }
        }
    }

    if (settingsVisible) {
        AlertDialog(
            onDismissRequest = { if (!busy) settingsVisible = false },
            title = {
                Text("Полный Lampac NextGen", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(runtime.message)
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("$progress%")
                    }
                    Text(
                        "Устанавливается официальный полный серверный архив, .NET 10, Chromium, FFmpeg и GStreamer. Требуется несколько гигабайт свободной памяти. Настройки сервера сохраняются при обновлении приложения.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (runtime.logs.isNotEmpty()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(10.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                runtime.logs.forEach { line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(runtime.logs.joinToString("\n")))
                            }
                        ) {
                            Text("Копировать полный журнал")
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        checkingInstallation -> {
                            Button(enabled = false, onClick = {}) { Text("Проверка Lampac…") }
                        }
                        runtime.phase == LampacRuntimePhase.RUNNING -> {
                            Button(
                                enabled = !busy,
                                onClick = { LampacRuntimeService.stop(context) }
                            ) { Text("Остановить") }
                        }
                        installed -> {
                            Button(
                                enabled = !busy,
                                onClick = { LampacRuntimeService.start(context) }
                            ) { Text("Запустить") }
                        }
                        else -> {
                            Button(
                                enabled = !busy,
                                onClick = { LampacRuntimeService.installAndStart(context) }
                            ) { Text("Установить полный Lampac") }
                        }
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = { settingsVisible = false }
                    ) { Text("Закрыть") }
                }
            }
        )
    }
}
