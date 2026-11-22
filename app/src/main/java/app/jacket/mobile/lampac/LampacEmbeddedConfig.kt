package app.jacket.mobile.lampac

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Minimal local-only Lampac configuration based on the upstream example.init.conf.
 *
 * Browser automation, public listening and server-side media processing stay off
 * for the Android profile. The native GStreamer add-on is not packaged with the
 * Android rootfs; Jacket uses its own Media3 player and does not need that module.
 */
object LampacEmbeddedConfig {
    const val HOST = "127.0.0.1"
    const val PORT = 9118
    const val BASE_URL = "http://127.0.0.1:9118"

    // Preserve the upstream base.conf exclusions as init.conf arrays replace,
    // rather than append to, the base arrays during Lampac config merging.
    private val skippedModules = listOf(
        "Catalog",
        "CacheMedia",
        "DLNA",
        "ForkPlayerXML",
        "GStreamer",
        "MsxNative",
        "Potok",
        "QRAuth",
        "SeriesNotify",
        "Sync",
        "TelegramAuth",
        "TelegramAuthBot",
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
            JSONObject()
                .put("SkipModules", JSONArray(skippedModules))
                .put("LoadModules", JSONArray(listOf(".*")))
        )
        .put("chromium", JSONObject().put("enable", false))
        .put("openstat", JSONObject().put("enable", false))
        .put(
            "JacRed",
            JSONObject()
                .put("typesearch", "jackett")
                .put("disableJackett", false)
                .put(
                    "Jackett",
                    JSONObject()
                        .put("search_lang", "query")
                        .put("timeoutSeconds", 8)
                        .put("Megapeer", JSONObject().put("enable", true))
                )
        )
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

    /**
     * Migrates an already-installed runtime without replacing unrelated settings.
     * GStreamer is an optional server-side transcoder with native GLib/GStreamer
     * dependencies; the Android runtime intentionally keeps it unloaded.
     */
    fun ensureAndroidRuntimeConfig(serverDirectory: File): File {
        if (!serverDirectory.exists() && !serverDirectory.mkdirs()) {
            throw IOException("Не удалось создать каталог Lampac: ${serverDirectory.absolutePath}")
        }
        require(serverDirectory.isDirectory) { "Путь Lampac не является каталогом" }

        val target = File(serverDirectory, "init.conf")
        if (!target.isFile) return writeInitConfig(serverDirectory)

        val config = try {
            JSONObject(target.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            throw IOException("Не удалось прочитать init.conf Lampac", error)
        }
        if (!ensureAndroidModules(config)) return target
        writeConfigAtomically(target, config.toString(2))
        return target
    }

    /** Adds only Android-specific module exclusions and required local JacRed defaults. */
    internal fun ensureAndroidModules(config: JSONObject): Boolean {
        var changed = ensureLocalListener(config)
        if (ensureAllModulesLoadedWhenUnspecified(config)) changed = true
        if (ensureGStreamerSkipped(config)) changed = true
        if (ensureUpstreamDefaultExclusions(config)) changed = true
        if (ensureAndroidOptionalModulesSkipped(config)) changed = true
        if (ensureJacRedConfigured(config)) changed = true
        return changed
    }

    /** Keep the embedded HTTP listener private even when a desktop init.conf is imported. */
    internal fun ensureLocalListener(config: JSONObject): Boolean {
        val listen = config.optJSONObject("listen") ?: JSONObject().also {
            config.put("listen", it)
        }
        val before = listen.toString()
        listen.put("version", true)
            .put("ip", HOST)
            .put("port", PORT)
            .put("scheme", "http")
            .put("localhost", HOST)
        return before != listen.toString()
    }

    /** Lampac loads enabled modules selected by LoadModules, less SkipModules. */
    internal fun ensureAllModulesLoadedWhenUnspecified(config: JSONObject): Boolean {
        val baseModule = config.optJSONObject("BaseModule") ?: JSONObject().also {
            config.put("BaseModule", it)
        }
        val loadModules = baseModule.optJSONArray("LoadModules")
        if (loadModules != null && loadModules.length() > 0) return false
        baseModule.put("LoadModules", JSONArray(listOf(".*")))
        return true
    }

    /** init.conf replaces BaseModule.SkipModules; restore all upstream base.conf exclusions. */
    internal fun ensureUpstreamDefaultExclusions(config: JSONObject): Boolean {
        val baseModule = config.optJSONObject("BaseModule") ?: JSONObject().also {
            config.put("BaseModule", it)
        }
        val skipped = baseModule.optJSONArray("SkipModules") ?: JSONArray().also {
            baseModule.put("SkipModules", it)
        }
        val required = listOf(
            "CacheMedia",
            "ForkPlayerXML",
            "MsxNative",
            "Potok",
            "QRAuth",
            "SeriesNotify",
            "TelegramAuth",
            "TelegramAuthBot"
        )
        var changed = false
        required.forEach { module ->
            if ((0 until skipped.length()).none { skipped.optString(it).equals(module, true) }) {
                skipped.put(module)
                changed = true
            }
        }
        return changed
    }

    /** Keep modules that rely on native services, privileged networking or extra ports off on Android. */
    internal fun ensureAndroidOptionalModulesSkipped(config: JSONObject): Boolean {
        val baseModule = config.optJSONObject("BaseModule") ?: JSONObject().also {
            config.put("BaseModule", it)
        }
        val skipped = baseModule.optJSONArray("SkipModules") ?: JSONArray().also {
            baseModule.put("SkipModules", it)
        }
        var changed = false
        skippedModules.forEach { module ->
            if ((0 until skipped.length()).none { skipped.optString(it).equals(module, true) }) {
                skipped.put(module)
                changed = true
            }
        }
        return changed
    }

    internal fun ensureJacRedConfigured(config: JSONObject): Boolean {
        var changed = false
        val baseModule = config.optJSONObject("BaseModule") ?: JSONObject().also {
            config.put("BaseModule", it)
            changed = true
        }
        val skipped = baseModule.optJSONArray("SkipModules") ?: JSONArray().also {
            baseModule.put("SkipModules", it)
            changed = true
        }
        for (index in skipped.length() - 1 downTo 0) {
            if (skipped.optString(index).equals("JacRed", ignoreCase = true)) {
                skipped.remove(index)
                changed = true
            }
        }

        val jacRed = config.optJSONObject("JacRed") ?: JSONObject().also {
            config.put("JacRed", it)
            changed = true
        }
        if (jacRed.optString("typesearch").isBlank()) {
            jacRed.put("typesearch", "jackett")
            changed = true
        }
        if (!jacRed.has("disableJackett")) {
            jacRed.put("disableJackett", false)
            changed = true
        }
        val jackett = jacRed.optJSONObject("Jackett") ?: JSONObject().also {
            jacRed.put("Jackett", it)
            changed = true
        }
        if (jackett.optString("search_lang").isBlank()) {
            jackett.put("search_lang", "query")
            changed = true
        }
        if (!jackett.has("timeoutSeconds")) {
            jackett.put("timeoutSeconds", 8)
            changed = true
        }
        // Megapeer is the only upstream JacRed adapter disabled by default.
        // Enable it on fresh/previously unconfigured embedded installs, but keep
        // an explicit user enable=false choice intact.
        val megapeer = jackett.optJSONObject("Megapeer") ?: JSONObject().also {
            jackett.put("Megapeer", it)
            changed = true
        }
        if (!megapeer.has("enable")) {
            megapeer.put("enable", true)
            changed = true
        }
        return changed
    }

    /** Adds the native GStreamer exclusion; Android uses its own Media3 playback engine. */
    internal fun ensureGStreamerSkipped(config: JSONObject): Boolean {
        val baseModule = config.optJSONObject("BaseModule") ?: JSONObject().also {
            config.put("BaseModule", it)
        }
        val skipped = baseModule.optJSONArray("SkipModules") ?: JSONArray().also {
            baseModule.put("SkipModules", it)
        }
        if ((0 until skipped.length()).any { skipped.optString(it) == "GStreamer" }) return false
        skipped.put("GStreamer")
        return true
    }

    /** Writes init.conf atomically, preserving the previous file if writing fails. */
    fun writeInitConfig(serverDirectory: File): File {
        if (!serverDirectory.exists() && !serverDirectory.mkdirs()) {
            throw IOException("Не удалось создать каталог Lampac: ${serverDirectory.absolutePath}")
        }
        require(serverDirectory.isDirectory) { "Путь Lampac не является каталогом" }

        val target = File(serverDirectory, "init.conf")
        writeConfigAtomically(target, buildJson())
        return target
    }

    /**
     * Imports a user-owned Lampac init.conf while enforcing the Android loopback-only
     * listener and the native modules that the embedded runtime cannot safely load.
     * Provider credentials, priorities, JacRed adapters and all other custom sections
     * are retained as supplied by the user.
     */
    fun importInitConfig(serverDirectory: File, content: String): File {
        val encoded = content.toByteArray(Charsets.UTF_8)
        require(encoded.isNotEmpty() && encoded.size <= MAX_INIT_CONFIG_BYTES) {
            "Файл init.conf должен быть непустым и не превышать 2 МиБ"
        }
        val config = try {
            JSONObject(content)
        } catch (error: Exception) {
            throw IOException("Файл init.conf не является корректным JSON", error)
        }
        ensureAndroidModules(config)
        if (!serverDirectory.exists() && !serverDirectory.mkdirs()) {
            throw IOException("Не удалось создать каталог Lampac: ${serverDirectory.absolutePath}")
        }
        require(serverDirectory.isDirectory) { "Путь Lampac не является каталогом" }
        val target = File(serverDirectory, "init.conf")
        writeConfigAtomically(target, config.toString(2))
        return target
    }

    const val MAX_INIT_CONFIG_BYTES = 2 * 1024 * 1024

    private fun writeConfigAtomically(target: File, content: String) {
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw IOException("Не удалось сохранить init.conf Lampac", error)
        }
    }
}
