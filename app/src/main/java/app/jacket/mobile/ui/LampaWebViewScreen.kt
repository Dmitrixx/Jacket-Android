package app.jacket.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.jacket.mobile.BuildConfig
import app.jacket.mobile.data.LocalNetwork
import app.jacket.mobile.torrent.TorrentDownloadService
import app.jacket.mobile.torrent.PlaybackProgressStore
import app.jacket.mobile.torrent.PlaybackSubtitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import java.io.ByteArrayInputStream
import java.net.URI

private const val LAMPA_LOCAL_ORIGIN = "http://127.0.0.1:9118"
private const val LAMPA_ASSET_PREFIX = "/app/"

data class LampaPlaybackRequest(
    val id: String,
    val title: String,
    val url: String,
    val progressKey: String,
    val initialPositionMs: Long,
    val subtitles: List<PlaybackSubtitle> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val isTorrent: Boolean = false
)

@Composable
fun LampaWebViewScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    playbackRequest: LampaPlaybackRequest? = null,
    onPlaybackCommandSent: (String) -> Unit = {},
    onPlaybackError: (String, String) -> Unit = { _, _ -> },
    onPlaybackClosed: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lampaPlayerOpen = remember { mutableStateOf(false) }
    val lampacStatus = remember { mutableStateOf("Lampac: ожидаем подключение") }
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val currentOnPlaybackClosed by rememberUpdatedState(onPlaybackClosed)
    val chromeClient = remember(context) {
        context.findActivity()?.let(::LampaFullscreenChromeClient)
    }
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
            // Do not add Lampa's "lampa_client" marker: it selects the native
            // Android player path. Without it Lampa uses its own WebView player.
            settings.userAgentString = settings.userAgentString + " Jacket-Lampa/1"
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY

            addJavascriptInterface(
                LampaAndroidBridge(
                    context.applicationContext,
                    onExit = { currentOnBack() },
                    onPlayerOpenChanged = { isOpen -> lampaPlayerOpen.value = isOpen },
                    onLampacStateChanged = { state -> lampacStatus.value = state },
                    onPlaybackError = { id, message -> currentOnPlaybackError(id, message) },
                    onPlaybackClosed = { id -> currentOnPlaybackClosed(id) }
                ),
                "AndroidJS"
            )

            webChromeClient = chromeClient ?: WebChromeClient()
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
                        if (uri.path.orEmpty().endsWith(".torrent", ignoreCase = true)) {
                            handleTorrent(context, uri.toString(), "")
                        } else {
                            openExternal(context, uri)
                        }
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

    LaunchedEffect(playbackRequest?.id) {
        val request = playbackRequest ?: return@LaunchedEffect
        val script = buildLampaPlaybackScript(request)
        for (attempt in 0 until 120) {
            val dispatched = runCatching {
                webView.evaluateJavascriptBoolean(script)
            }.getOrDefault(false)
            if (dispatched) {
                onPlaybackCommandSent(request.id)
                return@LaunchedEffect
            }
            delay(250)
        }
        onPlaybackError(request.id, "Интерфейс Lampa не успел подготовить плеер")
    }

    BackHandler(enabled = true) {
        when {
            chromeClient?.hideCustomView() == true -> Unit
            lampaPlayerOpen.value -> {
                webView.evaluateJavascript(
                    "if (window.Lampa && Lampa.Player && Lampa.Player.opened()) Lampa.Player.close();",
                    null
                )
                lampaPlayerOpen.value = false
            }
            webView.canGoBack() -> webView.goBack()
            else -> onBack()
        }
    }

    DisposableEffect(webView) {
        onDispose {
            chromeClient?.hideCustomView()
            lampaPlayerOpen.value = false
            webView.stopLoading()
            webView.removeJavascriptInterface("AndroidJS")
            webView.destroy()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
            update = { it.requestFocus() }
        )
        Text(
            text = lampacStatus.value,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 12.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelSmall
        )
    }
}


private fun buildLampaPlaybackScript(request: LampaPlaybackRequest): String {
    val subtitles = JSONArray().apply {
        request.subtitles.forEach { subtitle ->
            put(
                JSONObject()
                    .put("url", subtitle.url)
                    .put("label", subtitle.label)
                    .put("language", subtitle.language ?: "")
            )
        }
    }
    val data = JSONObject()
        .put("url", request.url)
        .put("title", request.title.ifBlank { "Видео" })
        .put("subtitles", subtitles)
        .put("headers", JSONObject(request.headers))
        .put("_jacketPlaybackId", request.id)
        .put("_jacketProgressKey", request.progressKey)
        .put(
            "timeline",
            JSONObject()
                .put("time", request.initialPositionMs.coerceAtLeast(0L) / 1000.0)
                .put("percent", if (request.initialPositionMs > 0L) 1 else 0)
        )

    return """
        (function(data) {
            if (!window.__JACKET_ANDROID_BRIDGE_READY__ ||
                !window.Lampa || !Lampa.Player ||
                typeof Lampa.Player.play !== 'function') return false;
            var playbackId = data._jacketPlaybackId;
            var progressKey = data._jacketProgressKey;
            delete data._jacketPlaybackId;
            delete data._jacketProgressKey;
            data.timeline = data.timeline || {};
            data.timeline.handler = function(percent, time, duration) {
                try {
                    if (window.AndroidJS && AndroidJS.savePlaybackProgress) {
                        AndroidJS.savePlaybackProgress(
                            progressKey,
                            Number(time) || 0,
                            Number(duration) || 0
                        );
                    }
                } catch (error) {
                    console.warn('Jacket: playback progress could not be saved', error);
                }
            };
            window.__JACKET_PENDING_PLAYBACK_ID__ = playbackId;
            window.__JACKET_ACTIVE_PLAYBACK_ID__ = null;
            window.__JACKET_PLAYBACK_ERROR_SENT__ = null;
            try {
                Lampa.Player.play(data);
                window.setTimeout(function() {
                    if (window.__JACKET_PENDING_PLAYBACK_ID__ === playbackId) {
                        window.__JACKET_PENDING_PLAYBACK_ID__ = null;
                        try {
                            AndroidJS.onJacketPlaybackError(
                                playbackId,
                                'Lampa не начала воспроизведение'
                            );
                        } catch (error) {}
                    }
                }, 15000);
                return true;
            } catch (error) {
                window.__JACKET_PENDING_PLAYBACK_ID__ = null;
                console.error('Jacket: could not start the Lampa player', error);
                return false;
            }
        })($data);
    """.trimIndent().replace("\$data", data.toString())
}

private suspend fun WebView.evaluateJavascriptBoolean(script: String): Boolean =
    suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { result ->
            if (continuation.isActive) continuation.resume(result == "true")
        }
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
    private val onExit: () -> Unit,
    private val onPlayerOpenChanged: (Boolean) -> Unit,
    private val onLampacStateChanged: (String) -> Unit,
    private val onPlaybackError: (String, String) -> Unit,
    private val onPlaybackClosed: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun appVersion(): String = "Jacket-${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}"

    @JavascriptInterface
    fun openTorrentLink(rawLink: String?, rawExtra: String?) {
        val link = rawLink?.trim().orEmpty()
        val metadata = runCatching { JSONObject(rawExtra.orEmpty()) }.getOrDefault(JSONObject())
        val title = metadata.optString("title").ifBlank { "Раздача из Lampa" }.take(180)
        mainHandler.post { handleTorrent(context, link, title) }
    }

    @JavascriptInterface
    fun setPlayerOpen(isOpen: Boolean) {
        mainHandler.post { onPlayerOpenChanged(isOpen) }
    }

    @JavascriptInterface
    fun setLampacState(rawState: String?) {
        val state = when (rawState) {
            "connected" -> "Lampac: плагин загружен"
            "connecting" -> "Lampac: подключение…"
            "unavailable" -> "Lampac: /online.js недоступен"
            else -> "Lampac: ожидаем подключение"
        }
        mainHandler.post { onLampacStateChanged(state) }
    }

    @JavascriptInterface
    fun exit() {
        mainHandler.post { onExit() }
    }

    @JavascriptInterface
    fun savePlaybackProgress(rawKey: String?, positionSeconds: Double, durationSeconds: Double) {
        val key = rawKey?.take(160)?.takeIf { it.isNotBlank() } ?: return
        fun toMillis(seconds: Double): Long =
            if (seconds.isFinite()) (seconds.coerceIn(0.0, 86_400_000.0) * 1000.0).toLong() else 0L
        val positionMs = toMillis(positionSeconds)
        val durationMs = toMillis(durationSeconds)
        mainHandler.post {
            runCatching { PlaybackProgressStore(context).save(key, positionMs, durationMs) }
        }
    }

    @JavascriptInterface
    fun onJacketPlaybackError(rawId: String?, rawMessage: String?) {
        val id = rawId?.take(80)?.takeIf { it.isNotBlank() } ?: return
        val message = rawMessage?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(180)
            ?.ifBlank { "Ошибка HTML5-плеера" } ?: "Ошибка HTML5-плеера"
        mainHandler.post { onPlaybackError(id, message) }
    }

    @JavascriptInterface
    fun onJacketPlaybackClosed(rawId: String?) {
        val id = rawId?.take(80)?.takeIf { it.isNotBlank() } ?: return
        mainHandler.post { onPlaybackClosed(id) }
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Lets Lampa's own HTML5 video element use Android WebView fullscreen mode.
 * The video stays in Lampa; no stream is sent to Jacket's Media3 player.
 */
private class LampaFullscreenChromeClient(
    private val activity: Activity
) : WebChromeClient() {
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var previousSystemUiVisibility: Int = 0

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        val fullscreenView = view ?: run {
            callback?.onCustomViewHidden()
            return
        }
        val decor = activity.window.decorView as? FrameLayout
        if (decor == null || customView != null) {
            callback?.onCustomViewHidden()
            return
        }

        previousSystemUiVisibility = decor.systemUiVisibility
        customView = fullscreenView
        customViewCallback = callback
        decor.addView(
            fullscreenView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        decor.systemUiVisibility = previousSystemUiVisibility or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        fullscreenView.requestFocus()
    }

    override fun onHideCustomView() {
        hideCustomView()
    }

    fun hideCustomView(): Boolean {
        val fullscreenView = customView ?: return false
        val decor = activity.window.decorView as? FrameLayout
        decor?.removeView(fullscreenView)
        decor?.systemUiVisibility = previousSystemUiVisibility
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        return true
    }
}
