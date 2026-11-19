package app.jacket.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.jacket.mobile.data.IndexerConfig
import app.jacket.mobile.data.JackettUrl

/** Optional connection to a Jackett server managed by the user; no Jackett service is bundled. */
@Composable
fun JackettSetupCard(
    saved: IndexerConfig?,
    status: String?,
    onSave: (address: String, apiKey: String, indexer: String) -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit
) {
    val parsed = remember(saved?.searchUrlTemplate) {
        saved?.let { JackettUrl.parseTemplate(it.searchUrlTemplate) }
    }
    var address by remember(saved?.id, parsed?.baseUrl) { mutableStateOf(parsed?.baseUrl.orEmpty()) }
    var apiKey by remember(saved?.id, saved?.apiKey) { mutableStateOf(saved?.apiKey.orEmpty()) }
    var indexer by remember(saved?.id, parsed?.indexer) {
        mutableStateOf(parsed?.indexer ?: JackettUrl.DEFAULT_INDEXER)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("Jackett • дополнительный агрегатор", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Text(
                "Необязательная интеграция с вашим Jackett в домашней сети или на HTTPS-сервере. Один запрос ищет сразу по индексаторам, включённым в Jackett. Сам Jackett в APK не входит; остальные источники Jacket работают без него.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Адрес сервера") },
                placeholder = { Text("http://192.168.1.50:9117") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API-ключ Jackett") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = indexer,
                onValueChange = { indexer = it },
                label = { Text("Фильтр индексатора (all — все)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSave(address, apiKey, indexer) },
                enabled = address.isNotBlank() && apiKey.isNotBlank()
            ) { Text(if (saved == null) "Подключить и проверить" else "Сохранить и проверить") }
            if (saved != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTest) { Text("Проверить") }
                    OutlinedButton(onClick = onRemove) { Text("Отключить") }
                }
            }
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("Ошибка")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
