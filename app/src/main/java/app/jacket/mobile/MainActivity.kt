package app.jacket.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jacket.mobile.data.TorrentEntry
import app.jacket.mobile.torrent.TorrentDownloadService
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
        val sharedText = when (incoming?.action) {
            Intent.ACTION_VIEW -> incoming.dataString
            Intent.ACTION_SEND -> incoming.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim().orEmpty()

        val magnet = Regex("magnet:\\?[^\\s]+", RegexOption.IGNORE_CASE).find(sharedText)?.value
            ?: return
        val title = runCatching { Uri.parse(magnet).getQueryParameter("dn") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Magnet-загрузка"

        runCatching {
            TorrentDownloadService.add(applicationContext, magnet, title)
        }.onSuccess {
            Toast.makeText(this, "Торрент добавлен в Jacket", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Не удалось добавить magnet-ссылку", Toast.LENGTH_LONG).show()
        }
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
