package app.jacket.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.jacket.mobile.BuildConfig
import app.jacket.mobile.lampac.ExternalMediaProxy
import app.jacket.mobile.lampac.LampaWebAssetUpdater
import app.jacket.mobile.lampac.routeExternalMediaUrl
import java.io.ByteArrayInputStream

private const val LAMPA_ORIGIN = "http://127.0.0.1:9118"
private const val LAMPA_ASSET_PREFIX = "/app/"

@Composable
fun LampaWebViewScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val playerOpen = remember { mutableStateOf(false) }
    val lampacStatus = remember { mutableStateOf("Lampac: ожидание подключения") }
    val currentOnBack by rememberUpdatedState(onBack)
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
            settings.userAgentString = settings.userAgentString + " Lampa-Android/1"
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY

            addJavascriptInterface(
                LampaAndroidBridge(
                    context.applicationContext,
                    onExit = { currentOnBack() },
                    onPlayerOpenChanged = { playerOpen.value = it },
                    onLampacStateChanged = { lampacStatus.value = it }
                ),
                "AndroidJS"
            )

            webChromeClient = chromeClient ?: WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest
                ): WebResourceResponse? = interceptLampaAsset(context, request)

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest
                ): Boolean {
                    val uri = request.url
                    if (isLocalLampaOrigin(uri)) return false
                    openExternal(context, uri)
                    return true
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        Toast.makeText(
                            context,
                            "Не удалось открыть встроенный интерфейс Lampa",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            loadUrl("$LAMPA_ORIGIN/app/index.html")
        }
    }

    BackHandler(enabled = true) {
        when {
            chromeClient?.hideCustomView() == true -> Unit
            playerOpen.value -> {
                webView.evaluateJavascript(
                    "if (window.Lampa && Lampa.Player && Lampa.Player.opened()) Lampa.Player.close();",
                    null
                )
                playerOpen.value = false
            }
            webView.canGoBack() -> webView.goBack()
            else -> onBack()
        }
    }

    DisposableEffect(webView) {
        onDispose {
            chromeClient?.hideCustomView()
            playerOpen.value = false
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
                .align(Alignment.TopStart)
                .padding(top = 10.dp, start = 12.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun interceptLampaAsset(
    context: Context,
    request: WebResourceRequest
): WebResourceResponse? {
    val uri = request.url
    if (!isLocalLampaOrigin(uri)) return null
    val path = Uri.decode(uri.path.orEmpty())
    if (!path.startsWith(LAMPA_ASSET_PREFIX)) return null

    val relative = path.removePrefix(LAMPA_ASSET_PREFIX).ifBlank { "index.html" }
    val segments = relative.split('/')
    if (segments.any { it == ".." || it == "." || it.isBlank() }) {
        return textResponse(403, "Forbidden")
    }

    return try {
        val mime = lampaMimeType(relative)
        WebResourceResponse(
            mime.first,
            mime.second,
            200,
            "OK",
            mapOf("Cache-Control" to "no-store"),
            LampaWebAssetUpdater.openAsset(context, relative) ?: return textResponse(404, "Not Found")
        )
    } catch (_: Exception) {
        textResponse(404, "Not Found")
    }
}

private fun isLocalLampaOrigin(uri: Uri): Boolean =
    uri.scheme.equals("http", true) &&
        uri.host.equals("127.0.0.1", true) &&
        (uri.port == 9118 || uri.port == -1)

private fun lampaMimeType(path: String): Pair<String, String?> =
    when (path.substringAfterLast('.', "").lowercase()) {
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

private fun textResponse(status: Int, reason: String) = WebResourceResponse(
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
    private val onLampacStateChanged: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun appVersion(): String = "Lampa-${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}"

    @JavascriptInterface
    fun setPlayerOpen(isOpen: Boolean) {
        mainHandler.post { onPlayerOpenChanged(isOpen) }
    }

    @JavascriptInterface
    fun setLampacState(rawState: String?) {
        val state = when (rawState) {
            "connected" -> "Lampac: подключён"
            "connecting" -> "Lampac: подключение…"
            "unavailable" -> "Lampac: сервер не отвечает"
            else -> "Lampac: ожидание подключения"
        }
        mainHandler.post { onLampacStateChanged(state) }
    }

    @JavascriptInterface
    fun exit() {
        mainHandler.post { onExit() }
    }

    @JavascriptInterface
    fun openTorrentLink(rawLink: String?, rawExtra: String?) {
        openExternalLink(rawLink)
    }

    @JavascriptInterface
    fun openYoutube(rawUrl: String?) {
        openExternalLink(rawUrl)
    }

    /**
     * Opens a Lampa stream in Android's system video-app chooser.
     *
     * This method is called on WebView's JavaScript bridge thread. Intent creation and
     * package resolution are safe there; startActivity is posted to the main thread.
     * Returning false lets Lampa keep its built-in HTML5 player when the URL is not
     * eligible or no installed application accepts the video MIME type.
     */
    @JavascriptInterface
    fun openExternalPlayer(rawUrl: String?, rawData: String?): Boolean {
        val parsedUri = rawUrl?.let(::safeVideoUri) ?: return false
        val routedUrl = routeExternalMediaUrl(parsedUri.toString(), ExternalMediaProxy.port)
            ?: if (ExternalMediaProxy.start()) {
                routeExternalMediaUrl(parsedUri.toString(), ExternalMediaProxy.port)
            } else {
                null
            }
        val uri = routedUrl?.let { Uri.parse(it) } ?: run {
            mainHandler.post {
                Toast.makeText(
                    context,
                    "Не удалось запустить прокси внешнего плеера. Перезапустите Lampac.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return false
        }
        val payload = runCatching {
            JSONObject(rawData.orEmpty().ifBlank { "{}" })
        }.getOrDefault(JSONObject())
        val title = payload.optString("title").takeIf { it.isNotBlank() } ?: "Видео"

        val playerIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Some Android players inspect EXTRA_STREAM in addition to Intent.data.
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra("title", title)

            payload.optJSONObject("headers")?.let { headersObject ->
                val headers = ArrayList<String>()
                val keys = headersObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.equals("content-length", ignoreCase = true)) continue
                    headers += key
                    headers += headersObject.optString(key)
                }
                if (headers.isNotEmpty()) {
                    // Common convention used by Android video players such as MX Player.
                    putExtra("headers", headers.toTypedArray())
                }
            }

            val timelinePosition = payload.optJSONObject("timeline")
                ?.optDouble("time", 0.0)
                ?.takeIf { it > 0.0 }
                ?.let { (it * 1000.0).toLong() }
                ?: payload.optLong("position", 0L)
            if (timelinePosition > 0L) {
                putExtra("position", timelinePosition)
            }

            payload.optJSONArray("playlist")?.let { playlist ->
                val urls = ArrayList<String>()
                val titles = ArrayList<String>()
                for (index in 0 until playlist.length()) {
                    val item = playlist.optJSONObject(index) ?: continue
                    val itemUri = safeVideoUri(item.optString("url"))
                        ?.let { parsed ->
                            routeExternalMediaUrl(parsed.toString(), ExternalMediaProxy.port)?.let { Uri.parse(it) }
                        }
                        ?: continue
                    urls += itemUri.toString()
                    titles += item.optString("title").takeIf { it.isNotBlank() }
                        ?: "Видео ${urls.size}"
                }
                if (urls.size > 1) {
                    putExtra("media_uris", urls.toTypedArray())
                    putExtra("media_titles", titles.toTypedArray())
                }
            }

            payload.optJSONArray("subtitles")?.let { subtitles ->
                val subtitleUrls = ArrayList<String>()
                val subtitleLabels = ArrayList<String>()
                for (index in 0 until subtitles.length()) {
                    val item = subtitles.optJSONObject(index) ?: continue
                    val subtitleUrl = safeVideoUri(item.optString("url")) ?: continue
                    subtitleUrls += subtitleUrl.toString()
                    subtitleLabels += item.optString("label").takeIf { it.isNotBlank() }
                        ?: "Субтитры ${subtitleUrls.size}"
                }
                if (subtitleUrls.isNotEmpty()) {
                    putExtra("subtitle_uris", subtitleUrls.toTypedArray())
                    putExtra("subtitle_labels", subtitleLabels.toTypedArray())
                    putExtra("subtitle_uri", subtitleUrls.first())
                }
            }
        }

        val handlers = runCatching {
            context.packageManager.queryIntentActivities(playerIntent, 0)
        }.getOrDefault(emptyList())
        if (handlers.isEmpty()) return false

        val chooser = Intent.createChooser(playerIntent, "Выберите видеоплеер").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        mainHandler.post {
            try {
                context.startActivity(chooser)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    context,
                    "Не найдено приложение для воспроизведения видео",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    "Не удалось открыть внешний видеоплеер",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        return true
    }

    @JavascriptInterface
    fun clearDefaultPlayer() = Unit

    @JavascriptInterface
    fun getAdvertisingId(): String = ""

    private fun openExternalLink(raw: String?) {
        val uri = raw?.let(::safeExternalUri) ?: return
        mainHandler.post { openExternal(context, uri) }
    }
}

private fun safeExternalUri(raw: String): Uri? = runCatching {
    val uri = Uri.parse(raw.trim())
    when (uri.scheme?.lowercase()) {
        "https" -> require(!uri.host.isNullOrBlank() && uri.userInfo == null)
        "magnet" -> require(
            uri.schemeSpecificPart.contains("urn:btih:", true) ||
                uri.schemeSpecificPart.contains("urn:btmh:", true)
        )
        else -> error("Unsupported external URI scheme")
    }
    uri
}.getOrNull()

/** Only network media URLs are forwarded to a third-party video application. */
private fun safeVideoUri(raw: String): Uri? = runCatching {
    val value = raw.trim()
    require(value.isNotEmpty() && value.length <= 16_384)
    val uri = Uri.parse(value)
    require(uri.userInfo == null)
    require(!uri.host.isNullOrBlank())
    require(uri.scheme?.lowercase() in setOf("http", "https", "rtsp", "rtmp"))
    uri
}.getOrNull()

private fun openExternal(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "Не удалось открыть внешнюю ссылку", Toast.LENGTH_LONG).show()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

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
