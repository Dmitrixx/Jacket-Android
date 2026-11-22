package app.jacket.mobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.jacket.mobile.data.LocalNetwork
import app.jacket.mobile.torrent.TorrentDownloadService
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI

private const val LAMPA_LOCAL_ORIGIN = "http://127.0.0.1:9118"
private const val LAMPA_ASSET_PREFIX = "/app/"

@Composable
fun LampaWebViewScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPlay: (title: String, url: String, headers: Map<String, String>) -> Unit
) {
    val context = LocalContext.current
    val webView = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.userAgentString = settings.userAgentString + " Jacket-Lampa/1"
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY

            addJavascriptInterface(
                LampaAndroidBridge(context.applicationContext, onPlay, onBack),
                "AndroidJS"
            )

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest
                ): WebResourceResponse? = interceptLocalAsset(context, request)

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest
                ): Boolean {
                    val uri = request.url
                    if (isLocalLampaOrigin(uri)) return false

                    if (uri.scheme.equals("magnet", true)) {
                        handleTorrent(context, uri.toString(), "")
                        return true
                    }

                    if (uri.scheme.equals("https", true)) {
                        openExternal(context, uri)
                        return true
                    }

                    return true
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        Toast.makeText(context, "Не удалось открыть интерфейс Lampa", Toast.LENGTH_LONG).show()
                    }
                }
            }

            loadUrl("$LAMPA_LOCAL_ORIGIN/app/index.html")
        }
    }

    BackHandler(enabled = true) {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.removeJavascriptInterface("AndroidJS")
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxSize(),
        update = { it.requestFocus() }
    )
}

private fun interceptLocalAsset(context: Context, request: WebResourceRequest): WebResourceResponse? {
    val uri = request.url
    if (!isLocalLampaOrigin(uri)) return null
    val path = Uri.decode(uri.path.orEmpty())
    if (!path.startsWith(LAMPA_ASSET_PREFIX)) return null

    val relative = path.removePrefix(LAMPA_ASSET_PREFIX).ifBlank { "index.html" }
    val segments = relative.split('/')
    if (segments.any { it == ".." || it == "." }) {
        return errorResponse(403, "Forbidden")
    }

    val assetPath = "lampa/$relative"
    return try {
        val mime = mimeType(relative)
        WebResourceResponse(
            mime.first,
            mime.second,
            200,
            "OK",
            mapOf("Cache-Control" to "no-store"),
            context.assets.open(assetPath)
        )
    } catch (_: Exception) {
        errorResponse(404, "Not Found")
    }
}

private fun isLocalLampaOrigin(uri: Uri): Boolean =
    uri.scheme.equals("http", true) &&
        uri.host.equals("127.0.0.1", true) &&
        (uri.port == 9118 || uri.port == -1)

private fun mimeType(path: String): Pair<String, String?> = when (path.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> "text/html" to "UTF-8"
    "js", "mjs" -> "application/javascript" to "UTF-8"
    "css" -> "text/css" to "UTF-8"
    "json", "map" -> "application/json" to "UTF-8"
    "svg" -> "image/svg+xml" to null
    "png" -> "image/png" to null
    "jpg", "jpeg" -> "image/jpeg" to null
    "webp" -> "image/webp" to null
    "gif" -> "image/gif" to null
    "woff" -> "font/woff" to null
    "woff2" -> "font/woff2" to null
    "ttf" -> "font/ttf" to null
    "eot" -> "application/vnd.ms-fontobject" to null
    "mp3" -> "audio/mpeg" to null
    "mp4" -> "video/mp4" to null
    "webm" -> "video/webm" to null
    else -> "application/octet-stream" to null
}

private fun errorResponse(status: Int, reason: String) = WebResourceResponse(
    "text/plain",
    "UTF-8",
    status,
    reason,
    emptyMap(),
    ByteArrayInputStream(ByteArray(0))
)

private class LampaAndroidBridge(
    private val context: Context,
    private val onPlay: (String, String, Map<String, String>) -> Unit,
    private val onBack: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun appVersion(): String = "Jacket-2.8.10-15"

    @JavascriptInterface
    fun openPlayer(rawUrl: String?, rawData: String?) {
        val url = safeMediaUrl(rawUrl) ?: return notify("Ссылка Lampa не поддерживается")
        val data = runCatching { JSONObject(rawData.orEmpty()) }.getOrDefault(JSONObject())
        val card = data.optJSONObject("card") ?: data.optJSONObject("movie") ?: data
        val title = card.optString("title")
            .ifBlank { card.optString("name") }
            .ifBlank { data.optString("title") }
            .ifBlank { "Видео из Lampa" }
            .take(180)
        val headers = safeHeaders(data.optJSONObject("headers"))
        mainHandler.post { onPlay(title, url, headers) }
    }

    @JavascriptInterface
    fun openTorrentLink(rawLink: String?, rawExtra: String?) {
        val link = rawLink?.trim().orEmpty()
        val metadata = runCatching { JSONObject(rawExtra.orEmpty()) }.getOrDefault(JSONObject())
        val title = metadata.optString("title").ifBlank { "Раздача из Lampa" }.take(180)
        mainHandler.post { handleTorrent(context, link, title) }
    }

    @JavascriptInterface
    fun exit() {
        mainHandler.post { onBack() }
    }

    @JavascriptInterface
    fun openYoutube(rawUrl: String?) {
        rawUrl?.let { safeExternalUrl(it) }?.let { uri ->
            mainHandler.post { openExternal(context, uri) }
        }
    }

    @JavascriptInterface
    fun clearDefaultPlayer() = Unit

    @JavascriptInterface
    fun getAdvertisingId(): String = ""

    private fun notify(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}

private fun safeMediaUrl(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null
    val resolved = if (value.startsWith("/")) "$LAMPA_LOCAL_ORIGIN$value" else value
    return resolved.takeIf(LocalNetwork::isAllowedUrl)
}

private fun safeHeaders(source: JSONObject?): Map<String, String> {
    if (source == null) return emptyMap()
    val result = LinkedHashMap<String, String>()
    source.keys().forEach { key ->
        val name = when {
            key.equals("Referer", true) -> "Referer"
            key.equals("User-Agent", true) -> "User-Agent"
            else -> return@forEach
        }
        val value = source.optString(key).trim()
        if (value.isNotBlank() && value.length <= 1_000 &&
            value.none { it == '\r' || it == '\n' }
        ) result[name] = value
    }
    return result
}

private fun handleTorrent(context: Context, link: String, title: String) {
    if (link.startsWith("magnet:", true)) {
        val query = runCatching { URI(link).rawSchemeSpecificPart }.getOrNull().orEmpty()
        if (!query.contains("urn:btih:", true) && !query.contains("urn:btmh:", true)) {
            Toast.makeText(context, "Некорректная magnet-ссылка", Toast.LENGTH_LONG).show()
            return
        }
        TorrentDownloadService.add(context, link, title.ifBlank { "Magnet из Lampa" })
        Toast.makeText(context, "Торрент добавлен в очередь Jacket", Toast.LENGTH_SHORT).show()
        return
    }

    val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return
    val isTorrentFile = uri.path.orEmpty().endsWith(".torrent", true)
    if (isTorrentFile && LocalNetwork.isAllowedUrl(link)) {
        TorrentDownloadService.addTorrentUrl(context, link, title.ifBlank { "Торрент из Lampa" })
        Toast.makeText(context, "Торрент добавлен в очередь Jacket", Toast.LENGTH_SHORT).show()
    } else {
        safeExternalUrl(link)?.let { openExternal(context, it) }
    }
}

private fun safeExternalUrl(raw: String): Uri? = runCatching {
    val uri = Uri.parse(raw.trim())
    require(uri.scheme.equals("https", true))
    require(!uri.host.isNullOrBlank() && uri.userInfo == null)
    uri
}.getOrNull()

private fun openExternal(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_LONG).show()
    }
}
