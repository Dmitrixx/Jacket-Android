package app.jacket.mobile.lampac

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LampacEmbeddedConfigTest {
    @Test
    fun freshConfigLoadsEveryUpstreamModuleAndKeepsListenerPrivate() {
        val config = JSONObject(LampacEmbeddedConfig.buildJson())
        val base = config.getJSONObject("BaseModule")

        assertEquals("127.0.0.1", config.getJSONObject("listen").getString("ip"))
        assertEquals(9118, config.getJSONObject("listen").getInt("port"))
        assertEquals(listOf(".*"), base.getJSONArray("LoadModules").toStrings())
        assertEquals(0, base.getJSONArray("SkipModules").length())
        assertTrue(config.getJSONObject("chromium").getBoolean("enable"))
        assertEquals("/usr/bin/chromium", config.getJSONObject("chromium").getString("executablePath"))
        assertEquals(listOf("--no-sandbox"), config.getJSONObject("chromium").getJSONArray("Args").toStrings())
        assertTrue(config.getJSONObject("openstat").getBoolean("enable"))
        assertTrue(config.getJSONObject("gst").getBoolean("enable"))
    }

    @Test
    fun freshConfigRegistersTheDefaultLampacClientPlugins() {
        val plugins = JSONObject(LampacEmbeddedConfig.buildJson())
            .getJSONObject("LampaWeb")
            .getJSONObject("initPlugins")

        listOf(
            "pirate_store",
            "jacred",
            "dlna",
            "tracks",
            "catalog",
            "watch_together",
            "tmdbProxy",
            "cubProxy",
            "online",
            "sisi",
            "torrserver",
            "timecode"
        ).forEach { key -> assertTrue("$key should be enabled by default", plugins.getBoolean(key)) }
    }

    @Test
    fun missingClientPluginDefaultsAreAddedButExplicitFalseValuesArePreserved() {
        val config = JSONObject()
            .put(
                "LampaWeb",
                JSONObject().put(
                    "initPlugins",
                    JSONObject()
                        .put("online", false)
                        .put("jacred", true)
                )
            )

        assertTrue(LampacEmbeddedConfig.ensureDefaultClientPlugins(config))
        val plugins = config.getJSONObject("LampaWeb").getJSONObject("initPlugins")
        assertFalse(plugins.getBoolean("online"))
        assertTrue(plugins.getBoolean("jacred"))
        assertTrue(plugins.getBoolean("dlna"))
        assertTrue(plugins.getBoolean("tracks"))
        assertTrue(plugins.getBoolean("catalog"))
        assertTrue(plugins.getBoolean("watch_together"))
        assertTrue(plugins.getBoolean("tmdbProxy"))
        assertTrue(plugins.getBoolean("cubProxy"))
        assertTrue(plugins.getBoolean("pirate_store"))
        assertFalse(LampacEmbeddedConfig.ensureDefaultClientPlugins(config))
    }

    @Test
    fun defaultCustomPluginsExposeMusicAndGStreamerWithoutReplacingUserEntries() {
        val config = JSONObject(LampacEmbeddedConfig.buildJson())
        val plugins = config.getJSONObject("LampaWeb").getJSONArray("customPlugins")
        val urls = (0 until plugins.length()).map { plugins.getJSONObject(it).getString("url") }

        assertTrue("{localhost}/music.js" in urls)
        assertTrue("{localhost}/gst.js" in urls)
        assertTrue(LampacEmbeddedConfig.ensureDefaultCustomPlugins(config).not())
    }

    @Test
    fun customPluginDefaultsRespectExistingTokenRoutesAndPreserveOtherEntries() {
        val customPlugins = JSONArray()
            .put(JSONObject().put("url", "{localhost}/music/js/private-token").put("status", 1))
            .put(JSONObject().put("url", "https://example.invalid/custom.js").put("status", 1))
        val config = JSONObject().put(
            "LampaWeb",
            JSONObject().put("customPlugins", customPlugins)
        )

        assertTrue(LampacEmbeddedConfig.ensureDefaultCustomPlugins(config))
        val actual = config.getJSONObject("LampaWeb").getJSONArray("customPlugins")
        val urls = (0 until actual.length()).map { actual.getJSONObject(it).getString("url") }

        assertEquals(3, actual.length())
        assertTrue("{localhost}/music/js/private-token" in urls)
        assertTrue("https://example.invalid/custom.js" in urls)
        assertTrue("{localhost}/gst.js" in urls)
    }

    @Test
    fun gstreamerDefaultIsAddedButExplicitDisabledChoiceIsPreserved() {
        val config = JSONObject()
        assertTrue(LampacEmbeddedConfig.ensureGStreamerDefaults(config))
        assertTrue(config.getJSONObject("gst").getBoolean("enable"))

        val disabled = JSONObject().put("gst", JSONObject().put("enable", false))
        assertFalse(LampacEmbeddedConfig.ensureGStreamerDefaults(disabled))
        assertFalse(disabled.getJSONObject("gst").getBoolean("enable"))
    }

    @Test
    fun freshConfigEnablesProvidersAndUsesCurrentFilmixApiHost() {
        val config = JSONObject(LampacEmbeddedConfig.buildJson())
        val filmix = config.getJSONObject("Filmix")

        assertTrue(filmix.getBoolean("enable"))
        assertEquals("https://fxapp.biz", filmix.getString("host"))
        assertEquals(0, filmix.getInt("hidefreeStart"))
        assertEquals(0, filmix.getInt("hidefreeEnd"))
    }

    @Test
    fun providerDefaultsRepairFilmixWithoutChangingEarlierAladinAndHdvbSettings() {
        val config = JSONObject()
            .put("Aladin", JSONObject().put("enable", false).put("token", "aladin-token"))
            .put("HDVB", JSONObject().put("enable", true).put("token", "hdvb-token"))
            .put(
                "Filmix",
                JSONObject()
                    .put("enable", true)
                    .put("host", "http://filmixapp.cyou")
                    .put("token", "filmix-token")
                    .put("pro", true)
            )

        assertTrue(LampacEmbeddedConfig.ensureProviderDefaults(config))

        val aladin = config.getJSONObject("Aladin")
        val hdvb = config.getJSONObject("HDVB")
        val filmix = config.getJSONObject("Filmix")
        assertFalse(aladin.getBoolean("enable"))
        assertEquals("aladin-token", aladin.getString("token"))
        assertTrue(hdvb.getBoolean("enable"))
        assertEquals("hdvb-token", hdvb.getString("token"))
        assertEquals("https://fxapp.biz", filmix.getString("host"))
        assertEquals("filmix-token", filmix.getString("token"))
        assertTrue(filmix.getBoolean("pro"))
        assertEquals(0, filmix.getInt("hidefreeStart"))
        assertEquals(0, filmix.getInt("hidefreeEnd"))
        assertFalse(LampacEmbeddedConfig.ensureProviderDefaults(config))
    }

    @Test
    fun providerDefaultsPreserveCustomFilmixMirrorAndExplicitFreeWindow() {
        val config = JSONObject()
            .put(
                "Filmix",
                JSONObject()
                    .put("host", "https://my-filmix-mirror.example")
                    .put("hidefreeStart", 19)
                    .put("hidefreeEnd", 23)
                    .put("token", "custom-token")
            )

        LampacEmbeddedConfig.ensureProviderDefaults(config)
        val filmix = config.getJSONObject("Filmix")
        assertEquals("https://my-filmix-mirror.example", filmix.getString("host"))
        assertEquals(19, filmix.getInt("hidefreeStart"))
        assertEquals(23, filmix.getInt("hidefreeEnd"))
        assertEquals("custom-token", filmix.getString("token"))
    }

    @Test
    fun importedConfigPreservesModuleExclusionsAndProviderSettings() {
        val original = JSONObject()
            .put("listen", JSONObject().put("ip", "0.0.0.0").put("port", 9999))
            .put(
                "BaseModule",
                JSONObject()
                    .put("LoadModules", JSONArray(listOf("Filmix")))
                    .put("SkipModules", JSONArray(listOf("GStreamer", "DLNA", "TorrServer")))
            )
            .put("chromium", JSONObject()
                .put("enable", false)
                .put("Args", JSONArray(listOf("--disable-gpu"))))
            .put("Filmix", JSONObject().put("token", "preserve-token").put("priority", 8))
            .put("custom", JSONObject().put("keep", true))

        val normalized = JSONObject(LampacEmbeddedConfig.prepareImportedConfig(original.toString()))
        val base = normalized.getJSONObject("BaseModule")

        assertEquals("127.0.0.1", normalized.getJSONObject("listen").getString("ip"))
        assertEquals(9118, normalized.getJSONObject("listen").getInt("port"))
        assertEquals(listOf(".*"), base.getJSONArray("LoadModules").toStrings())
        assertEquals(listOf("GStreamer", "DLNA", "TorrServer"), base.getJSONArray("SkipModules").toStrings())
        assertFalse(normalized.getJSONObject("chromium").getBoolean("enable"))
        assertEquals(
            listOf("--disable-gpu", "--no-sandbox"),
            normalized.getJSONObject("chromium").getJSONArray("Args").toStrings()
        )
        assertEquals("preserve-token", normalized.getJSONObject("Filmix").getString("token"))
        assertEquals(8, normalized.getJSONObject("Filmix").getInt("priority"))
        assertTrue(normalized.getJSONObject("custom").getBoolean("keep"))
    }

    @Test
    fun migrationEnablesFullModuleSetWithoutChangingProviderOptions() {
        val config = JSONObject()
            .put(
                "BaseModule",
                JSONObject().put("SkipModules", JSONArray(listOf("DLNA", "GStreamer", "Catalog")))
            )
            .put("SomeProvider", JSONObject().put("enable", false))

        assertTrue(LampacEmbeddedConfig.ensureAndroidModules(config))
        val base = config.getJSONObject("BaseModule")
        assertEquals(listOf(".*"), base.getJSONArray("LoadModules").toStrings())
        assertEquals(listOf("DLNA", "GStreamer", "Catalog"), base.getJSONArray("SkipModules").toStrings())
        assertFalse(config.getJSONObject("SomeProvider").getBoolean("enable"))
        assertEquals(
            listOf("--no-sandbox"),
            config.getJSONObject("chromium").getJSONArray("Args").toStrings()
        )
        assertFalse(LampacEmbeddedConfig.ensureAndroidModules(config))
    }


    @Test
    fun moduleSelectionReplacesKnownGroupExclusionsAndPreservesCustomOnes() {
        val modules = listOf(
            LampacModuleInfo("Kinogo", "OnlineRUS", "module", true),
            LampacModuleInfo("Mirage", "OnlineRUS", "module", true),
            LampacModuleInfo("TorrServer", null, "module", true),
            LampacModuleInfo("FutureUnavailable", null, "module", false)
        )
        val original = JSONObject()
            .put(
                "BaseModule",
                JSONObject().put(
                    "SkipModules",
                    JSONArray(listOf("OnlineRUS", "TorrServer", "CustomExtension"))
                )
            )
            .put("Kinogo", JSONObject().put("token", "keep-me"))

        assertEquals(
            emptySet<String>(),
            LampacEmbeddedConfig.readEnabledModules(original.toString(), modules)
        )

        val updatedText = LampacEmbeddedConfig.prepareModuleSelection(
            content = original.toString(),
            enabledModuleIds = setOf("Kinogo"),
            modules = modules
        )
        val updated = JSONObject(updatedText)
        val skips = updated.getJSONObject("BaseModule").getJSONArray("SkipModules").toStrings()

        assertEquals(listOf("CustomExtension", "Mirage", "TorrServer"), skips)
        assertEquals(setOf("Kinogo"), LampacEmbeddedConfig.readEnabledModules(updatedText, modules))
        assertEquals("keep-me", updated.getJSONObject("Kinogo").getString("token"))
    }

    @Test
    fun selectAllRemovesOnlyKnownModuleExclusions() {
        val modules = listOf(
            LampacModuleInfo("Kinogo", "OnlineRUS", "module", true),
            LampacModuleInfo("TorrServer", null, "module", true)
        )
        val original = JSONObject()
            .put(
                "BaseModule",
                JSONObject().put(
                    "SkipModules",
                    JSONArray(listOf("OnlineRUS", "TorrServer", "CustomModule"))
                )
            )

        val updated = LampacEmbeddedConfig.prepareModuleSelection(
            original.toString(),
            setOf("Kinogo", "TorrServer"),
            modules
        )
        val config = JSONObject(updated)

        assertEquals(listOf("CustomModule"), config.getJSONObject("BaseModule").getJSONArray("SkipModules").toStrings())
        assertEquals(setOf("Kinogo", "TorrServer"), LampacEmbeddedConfig.readEnabledModules(updated, modules))
    }

    private fun JSONArray.toStrings(): List<String> =
        (0 until length()).map { getString(it) }
}
