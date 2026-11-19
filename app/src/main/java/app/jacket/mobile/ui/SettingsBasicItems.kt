package app.jacket.mobile.ui

import androidx.compose.foundation.lazy.LazyListScope

internal fun LazyListScope.settingsBasicItems(
    selectedSection: String,
    viewModel: MainViewModel,
    context: android.content.Context,
    token: String,
    onTokenChange: (String) -> Unit,
    downloadLimit: String,
    onDownloadLimitChange: (String) -> Unit,
    uploadLimit: String,
    onUploadLimitChange: (String) -> Unit,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit
) {
            if (selectedSection == "catalog") {
            item {
                Text("Каталог фильмов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Без ключей работает открытый каталог Internet Archive. Дополнительно можно подключить TMDB для популярных фильмов, сериалов, рейтингов и постеров.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { onTokenChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("TMDB Read Access Token (необязательно)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    minLines = 2
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.saveTmdbToken(token) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Сохранить токен") }
                    OutlinedButton(
                        onClick = {
                            onTokenChange("")
                            viewModel.saveTmdbToken("")
                        }
                    ) { Text("Сбросить") }
                }
                Text(
                    "Токен TMDB хранится в Android Keystore. TMDB предоставляет метаданные и изображения, но не видеопотоки.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            }

            if (selectedSection == "network") {
            item {
                Text("Загрузки и сеть", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Лимиты применяются ко всей торрент-сессии. Значение 0 означает отсутствие ограничения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = downloadLimit,
                        onValueChange = { onDownloadLimitChange(it.filter(Char::isDigit).take(7)) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Загрузка КБ/с") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = uploadLimit,
                        onValueChange = { onUploadLimitChange(it.filter(Char::isDigit).take(7)) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Отдача КБ/с") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Только по Wi-Fi", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Новые задачи будут ждать Wi-Fi. Уже запущенные загрузки и просмотр не отключаются автоматически.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = wifiOnly, onCheckedChange = { onWifiOnlyChange(it) })
                }
                Button(
                    onClick = {
                        TorrentEngine.setRateLimits(
                            context,
                            downloadLimit.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            uploadLimit.toIntOrNull()?.coerceAtLeast(0) ?: 0
                        )
                        TorrentEngine.setWifiOnly(context, wifiOnly)
                        Toast.makeText(context, "Настройки загрузки сохранены", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сохранить настройки загрузки") }
                Text(
                    "Папка загрузок: ${TorrentEngine.downloadDirectory(context).absolutePath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            }

}
