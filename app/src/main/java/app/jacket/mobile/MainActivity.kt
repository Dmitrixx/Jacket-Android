package app.jacket.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jacket.mobile.data.TorrentEntry
import app.jacket.mobile.torrent.TorrentDownloadService
import app.jacket.mobile.torrent.TorrentQueueStore
import java.io.File
import app.jacket.mobile.ui.JacketApp
import app.jacket.mobile.ui.MainViewModel
import app.jacket.mobile.ui.theme.JacketTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            JacketTheme {
                Surface(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: MainViewModel = viewModel()
                    JacketApp(viewModel = viewModel)
                }
            }
        }
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                4102
            )
        }
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        val sharedText = when (incoming.action) {
            Intent.ACTION_VIEW -> incoming.dataString
            Intent.ACTION_SEND -> incoming.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim().orEmpty()

        val magnet = Regex("magnet:\\?[^\\s]+", RegexOption.IGNORE_CASE).find(sharedText)?.value
        if (magnet != null) {
            val title = runCatching { Uri.parse(magnet).getQueryParameter("dn") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: "Magnet-загрузка"
            TorrentDownloadService.add(applicationContext, magnet, title)
            Toast.makeText(this, "Торрент добавлен в Jacket", Toast.LENGTH_SHORT).show()
            return
        }

        val sharedUri = when (incoming.action) {
            Intent.ACTION_VIEW -> incoming.data
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            else -> null
        }
        val remoteTorrent = sharedText.takeIf {
            runCatching { Uri.parse(it).scheme == "https" && Uri.parse(it).path.orEmpty().endsWith(".torrent", true) }.getOrDefault(false)
        }
        if (remoteTorrent != null) {
            TorrentDownloadService.addTorrentUrl(applicationContext, remoteTorrent, "Торрент-файл")
            Toast.makeText(this, "Загружаю .torrent в Jacket", Toast.LENGTH_SHORT).show()
            return
        }
        if (sharedUri != null) {
            importTorrentUri(sharedUri)
        }
    }

    private fun importTorrentUri(uri: Uri) {
        Thread {
            val result = runCatching {
                val directory = File(filesDir, "torrent-imports").apply { mkdirs() }
                val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                }?.takeIf { it.endsWith(".torrent", true) } ?: "shared.torrent"
                val target = File.createTempFile("incoming_", ".torrent", directory)
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Не удалось открыть файл" }
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= TorrentQueueStore.MAX_TORRENT_BYTES) { "Файл .torrent превышает 8 МиБ" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                target to name
            }
            runOnUiThread {
                result.onSuccess { (file, name) ->
                    TorrentDownloadService.addTorrentFile(applicationContext, file.absolutePath, name.removeSuffix(".torrent"))
                    Toast.makeText(this, ".torrent добавлен во встроенную очередь", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    Toast.makeText(this, "Не удалось импортировать .torrent: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}

fun openTorrent(context: Context, entry: TorrentEntry) {
    val target = entry.openUri
    if (target.isNullOrBlank()) {
        Toast.makeText(context, "Для этого результата нет magnet-ссылки или URL", Toast.LENGTH_LONG).show()
        return
    }

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent.createChooser(intent, "Открыть торрент через…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        copyToClipboard(context, target)
        Toast.makeText(context, "Подходящий торрент-клиент не найден. Ссылка скопирована.", Toast.LENGTH_LONG).show()
    }
}

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Magnet link", text))
    Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
}
