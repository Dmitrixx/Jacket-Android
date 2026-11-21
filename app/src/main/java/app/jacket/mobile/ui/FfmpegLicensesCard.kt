package app.jacket.mobile.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val FFMPEG_SOURCE_ARCHIVE =
    "https://repo1.maven.org/maven2/io/put/media3-ffmpeg-decoder/1.11.1+1/" +
        "media3-ffmpeg-decoder-1.11.1+1-ffmpeg-source.tar.xz"

@Composable
internal fun FfmpegLicensesCard() {
    val context = LocalContext.current
    var selectedDocument by rememberSaveable { mutableStateOf<String?>(null) }
    val documentText = remember(selectedDocument, context) {
        selectedDocument?.let { fileName ->
            runCatching {
                context.assets.open("licenses/ffmpeg/$fileName").bufferedReader().use { it.readText() }
            }.getOrElse { "Не удалось открыть файл лицензии: ${it.message}" }
        }.orEmpty()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Дополнительные аудиодекодеры", style = MaterialTheme.typography.titleMedium)
            Text(
                "Media3 использует FFmpeg как резервный аудиодекодер для AC-3, E-AC-3, DTS, MLP и TrueHD. " +
                    "Выбор дорожки и фактическая поддержка зависят от файла и устройства.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = { selectedDocument = "NOTICE" }, modifier = Modifier.fillMaxWidth()) {
                Text("Открыть NOTICE и сторонние уведомления")
            }
            OutlinedButton(
                onClick = { selectedDocument = "COPYING.LGPLv2.1" },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("GNU LGPL 2.1 — полный текст")
            }
            OutlinedButton(
                onClick = { selectedDocument = "LICENSE" },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apache License 2.0 — Media3 wrapper")
            }
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FFMPEG_SOURCE_ARCHIVE)))
                    }.onFailure {
                        Toast.makeText(context, "Не удалось открыть исходный архив", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Открыть соответствующий исходный код и инструкции сборки")
            }
            Text(
                "Исходный архив соответствует FFmpeg decoder 1.11.1+1 и содержит исходники FFmpeg, " +
                    "патч LGPL-only, JNI wrapper и инструкции для пересборки libffmpegJNI.so. " +
                    "Пользователь может заменить эту библиотеку совместимой собственной сборкой.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (selectedDocument != null) {
        AlertDialog(
            onDismissRequest = { selectedDocument = null },
            title = { Text(selectedDocument.orEmpty()) },
            text = {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            documentText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedDocument = null }) { Text("Закрыть") }
            }
        )
    }
}
