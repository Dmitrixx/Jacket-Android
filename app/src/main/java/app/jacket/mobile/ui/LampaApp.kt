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
    val runtime by LampacRuntimeState.state.collectAsState()
    var settingsVisible by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(false) }
    val busy = runtime.phase == LampacRuntimePhase.INSTALLING ||
        runtime.phase == LampacRuntimePhase.STARTING ||
        runtime.phase == LampacRuntimePhase.STOPPING

    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) {
            runCatching { LampacRuntimeInstaller(context).isInstalled() }.getOrDefault(false)
        }
        if (installed && runtime.phase == LampacRuntimePhase.IDLE) {
            LampacRuntimeState.update(
                LampacRuntimePhase.INSTALLED,
                "Полный Lampac NextGen установлен. Сервер остановлен."
            )
        }
    }

    LaunchedEffect(runtime.phase) {
        if (runtime.phase == LampacRuntimePhase.INSTALLED ||
            runtime.phase == LampacRuntimePhase.RUNNING
        ) {
            installed = true
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
                    if (runtime.progress != null) {
                        LinearProgressIndicator(
                            progress = { runtime.progress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("\${runtime.progress}%")
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
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                runtime.logs.takeLast(8).forEach { line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
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
