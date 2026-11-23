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
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
            context.assets.open("lampa/$relative")
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
    fun appVersion(): String = "Lampa-\${BuildConfig.VERSION_NAME}-\${BuildConfig.VERSION_CODE}"

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
