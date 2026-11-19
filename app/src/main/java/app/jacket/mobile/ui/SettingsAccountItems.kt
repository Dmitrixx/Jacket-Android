package app.jacket.mobile.ui

import androidx.compose.foundation.lazy.LazyListScope
import app.jacket.mobile.data.HdRezkaSettings
internal fun LazyListScope.settingsAccountItems(
    selectedSection: String,
    viewModel: MainViewModel,
    context: android.content.Context,
    filmixToken: String,
    filmixCode: String?,
    filmixStatus: String,
    isFilmixPairing: Boolean,
    hdRezkaSettings: HdRezkaSettings,
    hdRezkaStatus: String,
    isHdRezkaBusy: Boolean,
    hdMirror: String,
    onHdMirrorChange: (String) -> Unit,
    hdLogin: String,
    onHdLoginChange: (String) -> Unit,
    hdPassword: String,
    onHdPasswordChange: (String) -> Unit,
    hdCookie: String,
    onHdCookieChange: (String) -> Unit
) {
            if (selectedSection == "filmix") {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "ПОДКЛЮЧЕНИЕ FILMIX",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.2.sp
                            )
                            Text("Привязка устройства", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold)
                            Text(
                                "Jacket запросит одноразовый код. Открой страницу устройств Filmix, введи код и вернись сюда — подтверждение проверяется автоматически.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Статус", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        filmixStatus,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (filmixToken.isNotBlank()) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (!filmixCode.isNullOrBlank()) {
                                        Text(
                                            filmixCode.orEmpty(),
                                            style = MaterialTheme.typography.displayMedium,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 8.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            "Код временный. Не передавай его другим людям.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            if (isFilmixPairing) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                OutlinedButton(
                                    onClick = { viewModel.cancelFilmixPairing() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Отменить подключение") }
                            } else if (filmixToken.isBlank()) {
                                Button(
                                    onClick = { viewModel.startFilmixPairing() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Tv, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (filmixCode == null) "Получить код Filmix" else "Получить новый код")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.disconnectFilmix() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Отключить устройство")
                                }
                            }
                            if (!filmixCode.isNullOrBlank()) {
                                Button(
                                    onClick = { openExternal(context, FilmixDeviceAuth.ACTIVATION_URL) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Открыть страницу активации")
                                }
                            }
                            Text(
                                "Токен устройства хранится в зашифрованном виде через Android Keystore. Пароль Filmix приложению не нужен.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (selectedSection == "hdrezka") {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "АККАУНТ И СЕССИЯ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.2.sp
                            )
                            Text("HDRezka", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold)
                            Text(
                                "Укажи рабочее HTTPS-зеркало и данные своего аккаунта. После успешного входа Jacket сохранит Cookie-сессию локально в зашифрованном виде.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = hdMirror,
                                onValueChange = { onHdMirrorChange(it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Зеркало HDRezka") },
                                placeholder = { Text("https://standby-rezka.tv") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = hdLogin,
                                onValueChange = { onHdLoginChange(it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Логин или email") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = hdPassword,
                                onValueChange = { onHdPasswordChange(it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Пароль") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    viewModel.loginHdRezka(
                                        HdRezkaSettings(
                                            mirror = hdMirror,
                                            login = hdLogin,
                                            password = hdPassword
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isHdRezkaBusy && hdLogin.isNotBlank() && hdPassword.isNotBlank()
                            ) {
                                if (isHdRezkaBusy) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                } else {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (isHdRezkaBusy) "Подключение…" else "Войти и сохранить сессию")
                            }
                            Text(
                                hdRezkaStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hdRezkaSettings.connected) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("Ручная Cookie-сессия", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                value = hdCookie,
                                onValueChange = { onHdCookieChange(it.take(8192)) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Cookie / session") },
                                placeholder = { Text("dle_user_id=…; dle_password=…") },
                                visualTransformation = PasswordVisualTransformation(),
                                minLines = 2
                            )
                            Text(
                                "Ручной вариант — вставь Cookie после обычного входа в браузере. Не отправляй её никому: она даёт доступ к аккаунту.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = {
                                    viewModel.saveHdRezkaSettings(
                                        HdRezkaSettings(
                                            mirror = hdMirror,
                                            login = hdLogin,
                                            password = hdPassword,
                                            cookie = hdCookie
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isHdRezkaBusy && hdCookie.isNotBlank()
                            ) { Text("Сохранить Cookie-сессию") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.disconnectHdRezka()
                                        onHdCookieChange("")
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Удалить сессию") }
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            openExternal(context, HdRezkaLoginClient.validateMirror(hdMirror))
                                        }.onFailure {
                                            Toast.makeText(context, it.message ?: "Некорректное зеркало", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Открыть зеркало") }
                            }
                            Text(
                                "Если сайт требует CAPTCHA или Cloudflare, автоматический вход остановится. Jacket не обходит защиту; в таком случае используй Cookie из своего браузера.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

}
