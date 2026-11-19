package app.jacket.mobile.ui

import androidx.compose.foundation.lazy.LazyListScope
import app.jacket.mobile.data.WebSearchProvider

internal fun LazyListScope.settingsWebsiteItems(
    selectedSection: String,
    viewModel: MainViewModel,
    context: android.content.Context,
    webSearchProviders: List<WebSearchProvider>,
    webEditingId: String,
    onWebEditingIdChange: (String) -> Unit,
    webName: String,
    onWebNameChange: (String) -> Unit,
    webUrlTemplate: String,
    onWebUrlTemplateChange: (String) -> Unit
) {
            if (selectedSection == "sites") {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (webEditingId.isBlank()) "Добавить сайт поиска" else "Изменить сайт",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "В списке уже есть популярные сайты поиска. Они открываются во внешнем браузере; RSS / Atom и Torznab-индексаторы работают отдельно и показывают результаты внутри Jacket.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { viewModel.restoreDefaultTrackerShortcuts() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Восстановить список сайтов и трекеров")
                        }
                        OutlinedTextField(
                            value = webName,
                            onValueChange = { onWebNameChange(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Название сайта") },
                            placeholder = { Text("Например, мой видеокаталог") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = webUrlTemplate,
                            onValueChange = { onWebUrlTemplateChange(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("URL поиска с {query}") },
                            placeholder = { Text("https://site.example/search?q={query}") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            minLines = 2
                        )
                        Button(
                            onClick = {
                                val saved = viewModel.saveWebSearchProvider(
                                    WebSearchProvider(
                                        id = webEditingId,
                                        name = webName,
                                        searchUrlTemplate = webUrlTemplate
                                    )
                                )
                                if (saved) {
                                    onWebEditingIdChange("")
                                    onWebNameChange("")
                                    onWebUrlTemplateChange("")
                                    Toast.makeText(context, "Сайт поиска сохранён", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Проверьте название и HTTPS-шаблон с {query}", Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (webEditingId.isBlank()) "Добавить сайт" else "Сохранить изменения")
                        }
                        if (webEditingId.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    onWebEditingIdChange("")
                                    onWebNameChange("")
                                    onWebUrlTemplateChange("")
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Отменить редактирование") }
                        }
                        Text(
                            "Добавляйте только сайты, доступ к которым разрешён в вашей стране и на законном основании. Jacket не обходит блокировки, CAPTCHA, авторизацию или DRM.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(webSearchProviders, key = { "web-${it.id}" }) { provider ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(provider.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (provider.enabled) "Включён • поиск в браузере" else "Отключён",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = provider.enabled,
                                    onCheckedChange = { viewModel.setWebSearchProviderEnabled(provider.id, it) }
                                )
                            }
                            Text(provider.searchUrlTemplate, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { openWebsiteSearch(context, provider, "Jacket test") },
                                    enabled = provider.enabled
                                ) { Text("Проверить") }
                                OutlinedButton(
                                    onClick = {
                                        onWebEditingIdChange(provider.id)
                                        onWebNameChange(provider.name)
                                        onWebUrlTemplateChange(provider.searchUrlTemplate)
                                    }
                                ) { Text("Изменить") }
                                OutlinedButton(onClick = { viewModel.removeWebSearchProvider(provider.id) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить")
                                }
                            }
                        }
                    }
                }
                if (webSearchProviders.isEmpty()) {
                    item { MessageCard("Сайтов пока нет. Добавьте HTTPS-шаблон поиска выше.", false) }
                }
            }


}
