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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
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
import app.jacket.mobile.lampac.LampacModuleInfo
import app.jacket.mobile.lampac.LampacRuntimePhase
import app.jacket.mobile.lampac.LampacRuntimeService
import app.jacket.mobile.lampac.LampacRuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LampaApp(onExit: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val runtime by LampacRuntimeState.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var settingsVisible by remember { mutableStateOf(false) }
    var modulesVisible by remember { mutableStateOf(false) }
    var moduleLoading by remember { mutableStateOf(false) }
    var moduleSaving by remember { mutableStateOf(false) }
    var moduleError by remember { mutableStateOf<String?>(null) }
    var moduleSearch by remember { mutableStateOf("") }
    var moduleList by remember { mutableStateOf(emptyList<LampacModuleInfo>()) }
    var enabledModuleIds by remember { mutableStateOf(emptySet<String>()) }
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

    LaunchedEffect(modulesVisible) {
        if (modulesVisible) {
            moduleLoading = true
            moduleError = null
            moduleSearch = ""
            val result = withContext(Dispatchers.IO) {
                runCatching { LampacRuntimeInstaller(context).readModuleSettings() }
            }
            result.onSuccess { settings ->
                moduleList = settings.modules
                enabledModuleIds = settings.enabledModuleIds
            }.onFailure { error ->
                moduleError = error.message ?: "Не удалось прочитать список модулей"
            }
            moduleLoading = false
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
                    if (installed) {
                        TextButton(
                            enabled = !busy && !moduleSaving,
                            onClick = {
                                settingsVisible = false
                                modulesVisible = true
                            }
                        ) {
                            Text("Выбрать загружаемые модули")
                        }
                        Text(
                            "Ненужные модули можно отключить: они будут исключены из компиляции при следующем запуске Lampac.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
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

    if (modulesVisible) {
        val availableModules = moduleList.filter { it.enabledInManifest }
        val filteredModules = moduleList.filter { module ->
            moduleSearch.isBlank() ||
                module.id.contains(moduleSearch, ignoreCase = true) ||
                module.groupId?.contains(moduleSearch, ignoreCase = true) == true
        }
        val groupedModules = filteredModules.groupBy { it.groupId ?: "Основные модули" }
        val allAvailableIds = availableModules.map { it.id }.toSet()

        AlertDialog(
            onDismissRequest = {
                if (!moduleSaving) {
                    modulesVisible = false
                    settingsVisible = true
                }
            },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Модули Lampac", fontWeight = FontWeight.Bold)
                    Text(
                        "${enabledModuleIds.intersect(allAvailableIds).size} из ${availableModules.size} включено",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Список читается из manifest.json установленного Lampac. Отключённые модули не будут компилироваться при запуске.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !moduleLoading && !moduleSaving,
                            onClick = { enabledModuleIds = allAvailableIds }
                        ) { Text("Включить все") }
                        TextButton(
                            enabled = !moduleLoading && !moduleSaving,
                            onClick = { enabledModuleIds = emptySet() }
                        ) { Text("Отключить все") }
                    }
                    OutlinedTextField(
                        value = moduleSearch,
                        onValueChange = { moduleSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Поиск модуля") }
                    )

                    when {
                        moduleLoading -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("Читаем список модулей…")
                        }
                        moduleError != null -> {
                            Text(
                                moduleError.orEmpty(),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        moduleList.isEmpty() -> {
                            Text("В установленном архиве не найдены manifest.json модулей.")
                        }
                        filteredModules.isEmpty() -> {
                            Text("По этому запросу модули не найдены.")
                        }
                        else -> {
                            groupedModules.forEach { (groupName, entries) ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            groupName,
                                            modifier = Modifier.weight(1f),
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        val groupIds = entries
                                            .filter { it.enabledInManifest }
                                            .map { it.id }
                                            .toSet()
                                        TextButton(
                                            enabled = groupIds.isNotEmpty() && !moduleSaving,
                                            onClick = { enabledModuleIds = enabledModuleIds + groupIds }
                                        ) { Text("Все") }
                                        TextButton(
                                            enabled = groupIds.isNotEmpty() && !moduleSaving,
                                            onClick = { enabledModuleIds = enabledModuleIds - groupIds }
                                        ) { Text("Нет") }
                                    }
                                    entries.forEach { module ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = module.id in enabledModuleIds,
                                                enabled = module.enabledInManifest && !moduleSaving,
                                                onCheckedChange = { checked ->
                                                    enabledModuleIds = if (checked) {
                                                        enabledModuleIds + module.id
                                                    } else {
                                                        enabledModuleIds - module.id
                                                    }
                                                }
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(module.id, style = MaterialTheme.typography.bodyMedium)
                                                if (!module.enabledInManifest) {
                                                    Text(
                                                        "Запрещён в manifest.json",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (moduleSaving) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Сохраняем настройки модулей…")
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !moduleLoading && !moduleSaving && moduleError == null && moduleList.isNotEmpty(),
                        onClick = {
                            val restartAfterSave = runtime.phase == LampacRuntimePhase.RUNNING
                            coroutineScope.launch {
                                moduleSaving = true
                                moduleError = null
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        LampacRuntimeInstaller(context).saveModuleSettings(enabledModuleIds)
                                    }
                                }
                                moduleSaving = false
                                result.onSuccess {
                                    modulesVisible = false
                                    settingsVisible = true
                                    if (restartAfterSave) {
                                        LampacRuntimeService.restart(context)
                                    }
                                }.onFailure { error ->
                                    moduleError = error.message ?: "Не удалось сохранить список модулей"
                                }
                            }
                        }
                    ) {
                        Text(
                            when {
                                moduleSaving -> "Сохранение…"
                                runtime.phase == LampacRuntimePhase.RUNNING -> "Сохранить и перезапустить"
                                else -> "Сохранить"
                            }
                        )
                    }
                    TextButton(
                        enabled = !moduleSaving,
                        onClick = {
                            modulesVisible = false
                            settingsVisible = true
                        }
                    ) { Text("Отмена") }
                }
            }
        )
    }
}
