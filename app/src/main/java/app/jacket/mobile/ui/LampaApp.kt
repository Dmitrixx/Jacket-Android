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
import androidx.compose.material3.Switch
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
import android.content.Intent
import android.net.Uri
import app.jacket.mobile.adblock.AdBlockPolicy
import app.jacket.mobile.lampac.LampacRuntimeInstaller
import app.jacket.mobile.lampac.LampacModuleInfo
import app.jacket.mobile.lampac.LampacRuntimePhase
import app.jacket.mobile.lampac.LampacRuntimeService
import app.jacket.mobile.lampac.LampacRuntimeState
import app.jacket.mobile.lampac.LampaUpdateManager
import app.jacket.mobile.lampac.UpdateCheckResult
import androidx.compose.runtime.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BYEDPI_APPLICATION_ID = "io.github.romanvht.byedpi"
private const val BYEDPI_RELEASES_URL = "https://github.com/romanvht/ByeByeDPI/releases/latest"

@Composable
fun LampaApp(onExit: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val runtime by LampacRuntimeState.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val runtimeLogScrollState = rememberScrollState()
    LaunchedEffect(runtime.logs.size) {
        runtimeLogScrollState.scrollTo(runtimeLogScrollState.maxValue)
    }
    var settingsVisible by remember { mutableStateOf(false) }
    var adBlockSettingsVisible by remember { mutableStateOf(false) }
    var byeDpiSettingsVisible by remember { mutableStateOf(false) }
    var blockAdDomains by remember { mutableStateOf(true) }
    var blockTrackers by remember { mutableStateOf(false) }
    var removeStreamAds by remember { mutableStateOf(true) }
    var modulesVisible by remember { mutableStateOf(false) }
    var moduleLoading by remember { mutableStateOf(false) }
    var moduleSaving by remember { mutableStateOf(false) }
    var moduleError by remember { mutableStateOf<String?>(null) }
    var moduleSearch by remember { mutableStateOf("") }
    var moduleList by remember { mutableStateOf(emptyList<LampacModuleInfo>()) }
    var enabledModuleIds by remember { mutableStateOf(emptySet<String>()) }
    var installed by remember { mutableStateOf(false) }
    var checkingInstallation by remember { mutableStateOf(true) }
    var updatesVisible by remember { mutableStateOf(false) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateCheck by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var lampaUpdating by remember { mutableStateOf(false) }
    var lampaUpdateProgress by remember { mutableStateOf<Int?>(null) }
    var webAssetRevision by remember { mutableStateOf(0) }
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

    LaunchedEffect(runtime.phase, updatesVisible) {
        // Refresh the displayed Lampac version when the update service has
        // completed its restart, and whenever the update centre is opened.
        if (updatesVisible && runtime.phase == LampacRuntimePhase.RUNNING && updateCheck != null) {
            updateChecking = true
            val result = withContext(Dispatchers.IO) {
                runCatching { LampaUpdateManager(context).checkUpdates() }
            }
            result.onSuccess { updateCheck = it }
                .onFailure { updateError = it.message ?: "Не удалось обновить сведения о версиях" }
            updateChecking = false
        }
    }

    LaunchedEffect(adBlockSettingsVisible) {
        if (adBlockSettingsVisible) {
            val preferences = context.getSharedPreferences(
                AdBlockPolicy.PREFERENCES_NAME,
                android.content.Context.MODE_PRIVATE
            )
            blockAdDomains = preferences.getBoolean(AdBlockPolicy.KEY_BLOCK_AD_DOMAINS, true)
            blockTrackers = preferences.getBoolean(AdBlockPolicy.KEY_BLOCK_TRACKERS, false)
            removeStreamAds = preferences.getBoolean(AdBlockPolicy.KEY_REMOVE_STREAM_ADS, true)
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
        key(webAssetRevision) {
            LampaWebViewScreen(
                modifier = Modifier.fillMaxSize(),
                onBack = onExit,
                onOpenLampacSettings = { settingsVisible = true },
                onOpenAdBlockSettings = { adBlockSettingsVisible = true },
                onOpenByeDpi = { byeDpiSettingsVisible = true }
            )
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
                            enabled = !busy && !moduleSaving && !lampaUpdating,
                            onClick = {
                                settingsVisible = false
                                updatesVisible = true
                            }
                        ) {
                            Text("Обновления Lampa и Lampac")
                        }
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
                                    .verticalScroll(runtimeLogScrollState),
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

    if (adBlockSettingsVisible) {
        AlertDialog(
            onDismissRequest = { adBlockSettingsVisible = false },
            title = { Text("Блокировка рекламы", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Удалять рекламу внутри видеопотока", fontWeight = FontWeight.Medium)
                            Text(
                                "Удалять отдельные HLS-сегменты и DASH-периоды, когда источник помечает рекламную вставку cue/SCTE-35 или явно называет период рекламным.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = removeStreamAds,
                            onCheckedChange = { enabled ->
                                removeStreamAds = enabled
                                context.getSharedPreferences(
                                    AdBlockPolicy.PREFERENCES_NAME,
                                    android.content.Context.MODE_PRIVATE
                                ).edit().putBoolean(AdBlockPolicy.KEY_REMOVE_STREAM_ADS, enabled).apply()
                            }
                        )
                    }
                    Text(
                        "Немаркированную рекламу, склеенную с фильмом в тех же сегментах, этот фильтр определить не может. Для неё потребуется распознавание аудио/видео; автоматическое удаление без пропусков пока не гарантируется.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Рекламные домены", fontWeight = FontWeight.Medium)
                            Text(
                                "Блокировать известные сторонние рекламные сети в запросах WebView.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = blockAdDomains,
                            onCheckedChange = { enabled ->
                                blockAdDomains = enabled
                                context.getSharedPreferences(
                                    AdBlockPolicy.PREFERENCES_NAME,
                                    android.content.Context.MODE_PRIVATE
                                ).edit().putBoolean(AdBlockPolicy.KEY_BLOCK_AD_DOMAINS, enabled).apply()
                            }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Аналитические трекеры", fontWeight = FontWeight.Medium)
                            Text(
                                "Дополнительно блокировать известные сервисы аналитики. Некоторые сайты могут потерять статистику или отдельные функции.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = blockTrackers,
                            onCheckedChange = { enabled ->
                                blockTrackers = enabled
                                context.getSharedPreferences(
                                    AdBlockPolicy.PREFERENCES_NAME,
                                    android.content.Context.MODE_PRIVATE
                                ).edit().putBoolean(AdBlockPolicy.KEY_BLOCK_TRACKERS, enabled).apply()
                            }
                        )
                    }
                    Text(
                        "Важно: фильтры действуют на сетевые запросы внутри Lampa WebView. Они не удаляют рекламные фрагменты, встроенные в HLS/DASH-видеопоток, и не фильтруют запросы внешних плееров VLC/MX Player.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { adBlockSettingsVisible = false }) {
                    Text("Готово")
                }
            }
        )
    }

    if (byeDpiSettingsVisible) {
        val byeDpiLaunchIntent = context.packageManager.getLaunchIntentForPackage(BYEDPI_APPLICATION_ID)
        AlertDialog(
            onDismissRequest = { byeDpiSettingsVisible = false },
            title = { Text("ByeByeDPI", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "ByeByeDPI — инструмент обхода DPI, а не блокировщик рекламы. Он работает как отдельное приложение и открывает собственные настройки, стратегии, DNS и списки приложений."
                    )
                    Text(
                        "Его VPN-режим перенаправляет трафик локально на устройстве. Это не удалённый VPN: он не шифрует трафик и не скрывает публичный IP-адрес.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (byeDpiLaunchIntent == null) {
                            "ByeByeDPI пока не установлено на устройстве."
                        } else {
                            "ByeByeDPI установлено. Откройте приложение, чтобы изменить параметры или запустить службу."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (byeDpiLaunchIntent != null) {
                        Button(
                            onClick = {
                                byeDpiLaunchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(byeDpiLaunchIntent)
                                byeDpiSettingsVisible = false
                            }
                        ) { Text("Открыть настройки") }
                    } else {
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(BYEDPI_RELEASES_URL))
                                )
                                byeDpiSettingsVisible = false
                            }
                        ) { Text("Скачать ByeByeDPI") }
                    }
                    TextButton(onClick = { byeDpiSettingsVisible = false }) {
                        Text("Закрыть")
                    }
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

    if (updatesVisible) {
        val latestLampa = updateCheck?.lampa
        val latestLampac = updateCheck?.lampac
        AlertDialog(
            onDismissRequest = { if (!updateChecking && !lampaUpdating) updatesVisible = false },
            title = { Text("Центр обновлений", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Lampa и Lampac обновляются независимо от APK приложения.")
                    Button(
                        enabled = !updateChecking && !lampaUpdating && !busy,
                        onClick = {
                            coroutineScope.launch {
                                updateChecking = true
                                updateError = null
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { LampaUpdateManager(context).checkUpdates() }
                                }
                                result.onSuccess { updateCheck = it }
                                    .onFailure { updateError = it.message ?: "Не удалось проверить обновления" }
                                updateChecking = false
                            }
                        }
                    ) {
                        Text(if (updateChecking) "Проверяем…" else "Проверить обновления")
                    }
                    if (updateChecking) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (updateError != null) {
                        Text(
                            updateError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (runtime.phase == LampacRuntimePhase.INSTALLING ||
                        runtime.phase == LampacRuntimePhase.STOPPING ||
                        runtime.phase == LampacRuntimePhase.STARTING
                    ) {
                        Text(runtime.message)
                        if (runtime.progress != null) {
                            LinearProgressIndicator(
                                progress = { runtime.progress!!.coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    updateCheck?.let { checked ->
                        Text("LAMPA", fontWeight = FontWeight.Bold)
                        Text("Установлена сборка: ${checked.localLampaBuild.take(7)}")
                        if (checked.lampaError != null) {
                            Text("Проверка Lampa: ${checked.lampaError}", color = MaterialTheme.colorScheme.error)
                        } else if (latestLampa == null) {
                            Text("Новых веб-обновлений нет.")
                        } else {
                            Text("Доступна сборка: ${latestLampa.displayVersion} • Lampa ${latestLampa.versionName}")
                            Text("Исходники: ${latestLampa.sourceCommit.take(7)}")
                            Button(
                                enabled = !lampaUpdating && !updateChecking && !busy,
                                onClick = {
                                    coroutineScope.launch {
                                        lampaUpdating = true
                                        lampaUpdateProgress = 0
                                        updateError = null
                                        val result = runCatching {
                                            LampaUpdateManager(context).updateLampa(latestLampa) { percent, message ->
                                                lampaUpdateProgress = percent
                                                LampacRuntimeState.appendLog(message)
                                            }
                                        }
                                        result.onSuccess {
                                            webAssetRevision += 1
                                            val refreshed = withContext(Dispatchers.IO) {
                                                runCatching { LampaUpdateManager(context).checkUpdates() }
                                            }
                                            refreshed.onSuccess { updateCheck = it }
                                            updateError = "Lampa обновлена. Интерфейс перезагружен."
                                        }.onFailure {
                                            updateError = it.message ?: "Не удалось обновить Lampa"
                                        }
                                        lampaUpdating = false
                                    }
                                }
                            ) {
                                Text(if (lampaUpdating) "Обновление…" else "Обновить Lampa")
                            }
                            if (lampaUpdating) {
                                LinearProgressIndicator(
                                    progress = { (lampaUpdateProgress ?: 0).coerceIn(0, 100) / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("${lampaUpdateProgress ?: 0}%")
                            }
                        }

                        Text("LAMPAC NEXTGEN", fontWeight = FontWeight.Bold)
                        Text("Установлена версия: ${checked.localLampacVersion ?: "не обнаружена"}")
                        if (checked.lampacError != null) {
                            Text("Проверка Lampac: ${checked.lampacError}", color = MaterialTheme.colorScheme.error)
                        } else if (latestLampac == null) {
                            Text("Новой стабильной версии Lampac не найдено.")
                        } else {
                            Text("Доступна версия: ${latestLampac.version}")
                            Text("Архив будет проверен по SHA-256 перед установкой.")
                            Button(
                                enabled = !updateChecking && !busy && !lampaUpdating,
                                onClick = {
                                    LampacRuntimeService.updateLampac(
                                        context = context,
                                        version = latestLampac.version,
                                        url = latestLampac.assetUrl,
                                        sha256 = latestLampac.sha256,
                                        sizeBytes = latestLampac.sizeBytes
                                    )
                                }
                            ) { Text("Обновить Lampac") }
                        }
                    } ?: Text("Нажмите «Проверить обновления», чтобы получить актуальные версии.")
                    Text(
                        "Обновление самого Android-приложения по-прежнему устанавливается отдельным APK.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !updateChecking && !lampaUpdating && !busy,
                    onClick = { updatesVisible = false }
                ) { Text("Закрыть") }
            }
        )
    }

}
