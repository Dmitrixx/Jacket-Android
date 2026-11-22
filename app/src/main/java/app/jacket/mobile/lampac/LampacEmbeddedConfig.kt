package app.jacket.mobile.lampac

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Minimal local-only Lampac configuration based on the upstream example.init.conf.
 *
 * Browser automation, public listening and heavy transcoding modules stay off
 * for the initial Android profile. The existing Jacket bridge talks to loopback.
 */
object LampacEmbeddedConfig {
    const val HOST = "127.0.0.1"
    const val PORT = 9118
    const val BASE_URL = "http://127.0.0.1:9118"

    private val skippedModules = listOf(
        "Catalog",
        "DLNA",
        "JacRed",
        "Sync",
        "TimeCode",
        "TorrServer",
        "Tracks",
        "Transcoding",
        "WebLog"
    )

    fun buildJson(): String = JSONObject()
        .put(
            "listen",
            JSONObject()
                .put("version", true)
                .put("ip", HOST)
                .put("port", PORT)
                .put("scheme", "http")
                .put("localhost", HOST)
        )
        .put(
            "BaseModule",
            JSONObject().put("SkipModules", JSONArray(skippedModules))
        )
        .put("chromium", JSONObject().put("enable", false))
        .put("openstat", JSONObject().put("enable", false))
        .put(
            "online",
            JSONObject()
                .put("name", "Lampac NextGen")
                .put("spiderName", "Lampac NextGen")
                .put("version", true)
                .put("btn_priority_forced", true)
        )
        .put(
            "sisi",
            JSONObject()
                .put("lgbt", false)
                .put("spider", false)
                .put("NextHUB", true)
                .put("history", JSONObject().put("enable", false))
        )
        .toString(2)

    /** Writes init.conf atomically, preserving the previous file if writing fails. */
    fun writeInitConfig(serverDirectory: File): File {
        if (!serverDirectory.exists() && !serverDirectory.mkdirs()) {
            throw IOException("Не удалось создать каталог Lampac: ${serverDirectory.absolutePath}")
        }
        require(serverDirectory.isDirectory) { "Путь Lampac не является каталогом" }

        val target = File(serverDirectory, "init.conf")
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(buildJson().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw IOException("Не удалось сохранить init.conf Lampac", error)
        }
        return target
    }
}
