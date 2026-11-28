package app.jacket.mobile.lampac

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Android bootstrap defaults for the complete upstream Lampac server.
 *
 * Android does not impose a hardcoded module exclusion list. The full release
 * package and upstream manifests define availability; user choices are saved
 * as exact exclusions in BaseModule.SkipModules.
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
                // Lampac runs as root inside PRoot; Chromium's namespace sandbox
                // cannot initialize reliably on Android, so use the upstream
                // documented no-sandbox flag for this isolated app-private runtime.
                .put("Args", JSONArray(listOf("--no-sandbox")))
        )
        .put("openstat", JSONObject().put("enable", true))
        .put("gst", JSONObject().put("enable", true))
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
        .put(
            "LampaWeb",
            JSONObject()
                .put("initPlugins", buildDefaultClientPlugins())
                .put("customPlugins", buildDefaultCustomPlugins())
        )
        .apply { ensureProviderDefaults(this) }
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

        LampacCoreModuleManifestPolicy.applyDefaultModuleManifestPolicy(serverDirectory)

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
        val clientPluginsChanged = ensureDefaultClientPlugins(config)
        val customPluginsChanged = ensureDefaultCustomPlugins(config)
        val gstChanged = ensureGStreamerDefaults(config)
        val providersChanged = ensureProviderDefaults(config)
        if (!modulesChanged && !listenerChanged && !browserChanged &&
            !jacredChanged && !clientPluginsChanged && !customPluginsChanged &&
            !gstChanged && !providersChanged
        ) return target

        writeConfigAtomically(target, config.toString(2))
        return target
    }

    internal fun ensureAndroidModules(config: JSONObject): Boolean {
        val listenerChanged = ensureLocalListener(config)
        val modulesChanged = ensureFullModuleSet(config)
        val browserChanged = ensureBrowserDefaults(config)
        val jacredChanged = ensureJacRedConfigured(config)
        val clientPluginsChanged = ensureDefaultClientPlugins(config)
        val customPluginsChanged = ensureDefaultCustomPlugins(config)
        val gstChanged = ensureGStreamerDefaults(config)
        val providersChanged = ensureProviderDefaults(config)
        return listenerChanged || modulesChanged || browserChanged ||
            jacredChanged || clientPluginsChanged || customPluginsChanged ||
            gstChanged || providersChanged
    }

    /**
     * Lampac's client plugins are not loaded by requesting /online.js directly.
     * /lampainit.js reads this list and registers every enabled plugin in Lampa.
     * These defaults mirror Lampac NextGen's own config/base.conf; explicit
     * per-user false values remain untouched.
     */
    private fun buildDefaultClientPlugins(): JSONObject = JSONObject()
        .put("pirate_store", true)
        .put("jacred", true)
        .put("dlna", true)
        .put("tracks", true)
        .put("catalog", true)
        .put("watch_together", true)
        .put("tmdbProxy", true)
        .put("cubProxy", true)
        .put("online", true)
        .put("sisi", true)
        .put("torrserver", true)
        .put("timecode", true)

    internal fun ensureDefaultClientPlugins(config: JSONObject): Boolean {
        val web = config.optJSONObject("LampaWeb") ?: JSONObject().also {
            config.put("LampaWeb", it)
        }
        val plugins = web.optJSONObject("initPlugins") ?: JSONObject().also {
            web.put("initPlugins", it)
        }
        val before = plugins.toString()
        val defaults = buildDefaultClientPlugins()
        defaults.keys().forEach { key ->
            if (!plugins.has(key)) plugins.put(key, defaults.getBoolean(key))
        }
        return before != plugins.toString()
    }

    private fun buildDefaultCustomPlugins(): JSONArray = JSONArray()
        .put(JSONObject().put("url", "{localhost}/gst.js").put("status", 1))

    /**
     * Add the GStreamer client entrypoint without duplicating a tokenized URL
     * or overwriting user-managed custom plugin entries. Remove only the Music
     * URL inserted by the previous Android default profile.
     */
    internal fun ensureDefaultCustomPlugins(config: JSONObject): Boolean {
        val web = config.optJSONObject("LampaWeb") ?: JSONObject().also {
            config.put("LampaWeb", it)
        }
        val plugins = web.optJSONArray("customPlugins") ?: JSONArray().also {
            web.put("customPlugins", it)
        }
        val before = plugins.toString()
        val retained = JSONArray()
        val musicRoutePattern = Regex(
            """(?:^|/)music(?:\\.js(?:[?#].*)?|/js(?:/.*)?$)""",
            RegexOption.IGNORE_CASE
        )
        (0 until plugins.length()).forEach { index ->
            val plugin = plugins.optJSONObject(index) ?: return@forEach
            val url = plugin.optString("url")
            if (!musicRoutePattern.containsMatchIn(url)) retained.put(plugin)
        }
        if (retained.toString() != plugins.toString()) {
            web.put("customPlugins", retained)
        }
        val activePlugins = web.getJSONArray("customPlugins")
        val defaults = listOf("{localhost}/gst.js" to "gst")
        defaults.forEach { (url, family) ->
            val familyPattern = Regex(
                """(?:^|/)""" + Regex.escape(family) + """(?:\.js(?:[?#].*)?|/js(?:/.*)?$)""",
                RegexOption.IGNORE_CASE
            )
            val alreadyConfigured = (0 until activePlugins.length()).any { index ->
                val existingUrl = activePlugins.optJSONObject(index)?.optString("url").orEmpty()
                familyPattern.containsMatchIn(existingUrl)
            }
            if (!alreadyConfigured) {
                activePlugins.put(JSONObject().put("url", url).put("status", 1))
            }
        }
        return before != activePlugins.toString()
    }

    internal fun ensureGStreamerDefaults(config: JSONObject): Boolean {
        val gst = config.optJSONObject("gst") ?: JSONObject().also {
            config.put("gst", it)
        }
        val before = gst.toString()
        if (!gst.has("enable")) gst.put("enable", true)
        return before != gst.toString()
    }
    /**
     * Provider defaults that need an Android-safe/current endpoint. Do not replace
     * user-supplied mirrors or credentials. Filmix's upstream default API host
     * is legacy; its current official Android support channel recommends fxapp.biz.
     * The upstream Filmix module hides its no-token source from 19:00 to 23:00 Kyiv
     * time unless hidefreeStart is zero, which made it disappear during evening use.
     */
    internal fun ensureProviderDefaults(config: JSONObject): Boolean {
        var changed = false

        val filmix = config.optJSONObject("Filmix") ?: JSONObject().also {
            config.put("Filmix", it)
            changed = true
        }
        if (!filmix.has("enable")) {
            filmix.put("enable", true)
            changed = true
        }

        val currentHost = filmix.optString("host").trim()
        if (currentHost.isBlank() ||
            currentHost.equals("http://filmixapp.cyou", ignoreCase = true) ||
            currentHost.equals("https://filmixapp.cyou", ignoreCase = true)
        ) {
            if (currentHost != "https://fxapp.biz") {
                filmix.put("host", "https://fxapp.biz")
                changed = true
            }
        }

        // Zero disables the upstream daily free-source hiding window.
        if (!filmix.has("hidefreeStart")) {
            filmix.put("hidefreeStart", 0)
            changed = true
        }
        if (!filmix.has("hidefreeEnd")) {
            filmix.put("hidefreeEnd", 0)
            changed = true
        }
        return changed
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

    /**
     * Make every shipped module eligible for loading without erasing the
     * user's explicit exclusions. manifest.json enable flags still apply.
     */
    internal fun ensureFullModuleSet(config: JSONObject): Boolean {
        val base = config.optJSONObject("BaseModule") ?: JSONObject().also {
            config.put("BaseModule", it)
        }
        val before = base.toString()
        base.put("LoadModules", JSONArray(listOf(".*")))
        if (base.optJSONArray("SkipModules") == null) {
            base.put("SkipModules", JSONArray())
        }
        return before != base.toString()
    }

    /** Returns manifest-enabled modules not excluded by their exact id or parent group. */
    fun readEnabledModules(content: String, modules: List<LampacModuleInfo>): Set<String> {
        val config = try {
            JSONObject(content)
        } catch (error: Exception) {
            throw IOException("Не удалось прочитать init.conf Lampac", error)
        }
        val skipModules = config.optJSONObject("BaseModule")
            ?.optJSONArray("SkipModules")
            ?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet() }
            .orEmpty()

        return modules.asSequence()
            .filter { it.enabledInManifest }
            .filter { it.id !in skipModules && (it.groupId == null || it.groupId !in skipModules) }
            .map { it.id }
            .toSet()
    }

    /**
     * Saves individual module choices in BaseModule.SkipModules.
     * Existing exclusions for modules discovered from the installed manifests
     * are replaced by exact module ids; unrelated custom exclusions remain.
     */
    fun prepareModuleSelection(
        content: String,
        enabledModuleIds: Set<String>,
        modules: List<LampacModuleInfo>
    ): String {
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
        val base = config.getJSONObject("BaseModule")
        val oldSkips = base.optJSONArray("SkipModules")
        val managedNames = modules.flatMap { listOfNotNull(it.id, it.groupId) }.toSet()
        val preservedCustomSkips = oldSkips
            ?.let { array ->
                (0 until array.length())
                    .mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                    .filterNot { it in managedNames }
            }
            .orEmpty()
        val selectedSkips = modules.asSequence()
            .filter { it.enabledInManifest && it.id !in enabledModuleIds }
            .map { it.id }
            .toList()

        base.put("SkipModules", JSONArray((preservedCustomSkips + selectedSkips).distinct()))
        return config.toString(2)
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

        // The PRoot server starts as root and Android does not provide the
        // Linux user-namespace setup Chromium's sandbox expects. Preserve every
        // user-supplied flag, but always retain the documented --no-sandbox flag.
        val browserArgs = chromium.optJSONArray("Args") ?: JSONArray().also {
            chromium.put("Args", it)
            changed = true
        }
        if ((0 until browserArgs.length()).none { browserArgs.optString(it) == "--no-sandbox" }) {
            browserArgs.put("--no-sandbox")
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
