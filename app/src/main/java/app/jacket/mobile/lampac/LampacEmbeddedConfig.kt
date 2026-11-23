package app.jacket.mobile.lampac

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Android bootstrap defaults for the complete upstream Lampac server.
 *
 * No Android-specific module exclusions are written. The full release package,
 * upstream module manifests and the user's configuration determine capabilities.
 */
object LampacEmbeddedConfig {
    const val HOST = "127.0.0.1"
    const val PORT = 9118
    const val BASE_URL = "http://127.0.0.1:9118"

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
                .put("LoadModules", JSONArray(listOf(".*")))
                .put("SkipModules", JSONArray())
        )
        .put(
            "chromium",
            JSONObject()
                .put("enable", true)
                .put("executablePath", "/usr/bin/chromium")
        )
        .put("openstat", JSONObject().put("enable", true))
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
                .put("name", "Lampa")
                .put("spiderName", "Lampa")
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
     * Reapplies the loopback listener and unfiltered module selection while
     * preserving provider credentials, priorities and custom configuration.
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
        val modulesChanged = ensureFullModuleSet(config)
        val listenerChanged = ensureLocalListener(config)
        val browserChanged = ensureBrowserDefaults(config)
        val jacredChanged = ensureJacRedConfigured(config)
        if (!modulesChanged && !listenerChanged && !browserChanged && !jacredChanged) return target

        writeConfigAtomically(target, config.toString(2))
        return target
    }

    internal fun ensureAndroidModules(config: JSONObject): Boolean {
        val listenerChanged = ensureLocalListener(config)
        val modulesChanged = ensureFullModuleSet(config)
        val browserChanged = ensureBrowserDefaults(config)
        val jacredChanged = ensureJacRedConfigured(config)
        return listenerChanged || modulesChanged || browserChanged || jacredChanged
    }

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

    /** Make every shipped module eligible for loading; upstream manifest flags still apply. */
    internal fun ensureFullModuleSet(config: JSONObject): Boolean {
        val base = config.optJSONObject("BaseModule") ?: JSONObject().also {
            config.put("BaseModule", it)
        }
        val before = base.toString()
        base.put("LoadModules", JSONArray(listOf(".*")))
            .put("SkipModules", JSONArray())
        return before != base.toString()
    }

    /** Enable upstream browser integrations by default but retain explicit user choices. */
    internal fun ensureBrowserDefaults(config: JSONObject): Boolean {
        var changed = false
        val chromium = config.optJSONObject("chromium") ?: JSONObject().also {
            config.put("chromium", it)
            changed = true
        }
        if (!chromium.has("enable")) {
            chromium.put("enable", true)
            changed = true
        }
        if (chromium.optString("executablePath").isBlank()) {
            chromium.put("executablePath", "/usr/bin/chromium")
            changed = true
        }

        val openstat = config.optJSONObject("openstat") ?: JSONObject().also {
            config.put("openstat", it)
            changed = true
        }
        if (!openstat.has("enable")) {
            openstat.put("enable", true)
            changed = true
        }
        return changed
    }

    internal fun ensureJacRedConfigured(config: JSONObject): Boolean {
        var changed = false
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

    fun writeInitConfig(serverDirectory: File): File {
        if (!serverDirectory.exists() && !serverDirectory.mkdirs()) {
            throw IOException("Не удалось создать каталог Lampac: ${serverDirectory.absolutePath}")
        }
        require(serverDirectory.isDirectory) { "Путь Lampac не является каталогом" }
        val target = File(serverDirectory, "init.conf")
        writeConfigAtomically(target, buildJson())
        return target
    }

    internal fun prepareImportedConfig(content: String): String {
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
        return config.toString(2)
    }

    fun importInitConfig(serverDirectory: File, content: String): File {
        val normalizedConfig = prepareImportedConfig(content)
        if (!serverDirectory.exists() && !serverDirectory.mkdirs()) {
            throw IOException("Не удалось создать каталог Lampac: ${serverDirectory.absolutePath}")
        }
        require(serverDirectory.isDirectory) { "Путь Lampac не является каталогом" }
        val target = File(serverDirectory, "init.conf")
        writeConfigAtomically(target, normalizedConfig)
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
