package app.jacket.mobile.lampac

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LampacEmbeddedConfigTest {
    @Test
    fun configBindsOnlyToLoopbackAndUsesBridgePort() {
        val config = JSONObject(LampacEmbeddedConfig.buildJson())
        val listen = config.getJSONObject("listen")

        assertEquals("127.0.0.1", listen.getString("ip"))
        assertEquals("127.0.0.1", listen.getString("localhost"))
        assertEquals(9118, listen.getInt("port"))
        assertEquals("http", listen.getString("scheme"))
    }

    @Test
    fun browserAndHeavyTranscodingModulesAreDisabledForInitialAndroidProfile() {
        val config = JSONObject(LampacEmbeddedConfig.buildJson())
        assertFalse(config.getJSONObject("chromium").getBoolean("enable"))

        val skipped = config.getJSONObject("BaseModule").getJSONArray("SkipModules")
        val modules = (0 until skipped.length()).map { skipped.getString(it) }.toSet()

        assertTrue("Transcoding" in modules)
        assertTrue("GStreamer" in modules)
        assertTrue("TorrServer" in modules)
        assertTrue("CacheMedia" in modules)
        assertTrue("ForkPlayerXML" in modules)
        assertTrue("MsxNative" in modules)
        assertTrue("Potok" in modules)
        assertTrue("QRAuth" in modules)
        assertTrue("SeriesNotify" in modules)
        assertTrue("TelegramAuth" in modules)
        assertTrue("TelegramAuthBot" in modules)
        assertFalse("JacRed" in modules)
        assertEquals(".*", config.getJSONObject("BaseModule").getJSONArray("LoadModules").getString(0))
        val jacRed = config.getJSONObject("JacRed")
        assertEquals("jackett", jacRed.getString("typesearch"))
        assertFalse(jacRed.getBoolean("disableJackett"))
        val jackett = jacRed.getJSONObject("Jackett")
        assertEquals("query", jackett.getString("search_lang"))
        assertTrue(jackett.getJSONObject("Megapeer").getBoolean("enable"))
    }

    @Test
    fun installedConfigMigrationAddsGStreamerExclusionWithoutOverwritingCustomSettings() {
        val config = JSONObject(
            """{"listen":{"ip":"127.0.0.1","port":9123},"BaseModule":{"SkipModules":["Transcoding"]},"custom":{"marker":"keep"}}"""
        )

        assertTrue(LampacEmbeddedConfig.ensureGStreamerSkipped(config))
        assertFalse(LampacEmbeddedConfig.ensureGStreamerSkipped(config))

        assertEquals(9123, config.getJSONObject("listen").getInt("port"))
        assertEquals("keep", config.getJSONObject("custom").getString("marker"))
        val skipped = config.getJSONObject("BaseModule").getJSONArray("SkipModules")
        assertEquals(2, skipped.length())
        assertEquals("Transcoding", skipped.getString(0))
        assertEquals("GStreamer", skipped.getString(1))
    }

    @Test
    fun migrationEnablesJacRedAndPreservesOtherServerOptions() {
        val config = JSONObject(
            """{"BaseModule":{"SkipModules":["JacRed","Transcoding","GStreamer"]},"custom":{"keep":"yes"}}"""
        )

        assertTrue(LampacEmbeddedConfig.ensureAndroidModules(config))
        assertFalse(LampacEmbeddedConfig.ensureAndroidModules(config))
        val skip = config.getJSONObject("BaseModule").getJSONArray("SkipModules")
        val modules = (0 until skip.length()).map { skip.getString(it) }
        assertFalse(modules.any { it.equals("JacRed", true) })
        assertTrue("Transcoding" in modules)
        assertTrue("GStreamer" in modules)
        listOf("CacheMedia", "ForkPlayerXML", "MsxNative", "Potok", "QRAuth", "SeriesNotify", "TelegramAuth", "TelegramAuthBot")
            .forEach { module -> assertTrue("$module must stay disabled", module in modules) }
        assertEquals("yes", config.getJSONObject("custom").getString("keep"))
        assertEquals("jackett", config.getJSONObject("JacRed").getString("typesearch"))
        assertFalse(config.getJSONObject("JacRed").getBoolean("disableJackett"))
        assertTrue(
            config.getJSONObject("JacRed").getJSONObject("Jackett")
                .getJSONObject("Megapeer").getBoolean("enable")
        )
    }

    @Test
    fun keepsExplicitJacRedConfigurationWhileRemovingItsModuleExclusion() {
        val config = JSONObject(
            """{"BaseModule":{"SkipModules":["JacRed"]},"JacRed":{"typesearch":"webapi","webApiHost":"https://jacred.example","disableJackett":true}}"""
        )

        assertTrue(LampacEmbeddedConfig.ensureJacRedConfigured(config))
        val jacRed = config.getJSONObject("JacRed")
        assertEquals("webapi", jacRed.getString("typesearch"))
        assertTrue(jacRed.getBoolean("disableJackett"))
        assertEquals("https://jacred.example", jacRed.getString("webApiHost"))
        val skip = config.getJSONObject("BaseModule").getJSONArray("SkipModules")
        assertFalse((0 until skip.length()).any { skip.optString(it) == "JacRed" })
    }

    @Test
    fun migrationPreservesAnExplicitMegapeerDisabledChoice() {
        val config = JSONObject(
            """{"BaseModule":{"SkipModules":[]},"JacRed":{"Jackett":{"Megapeer":{"enable":false}}}}"""
        )
        LampacEmbeddedConfig.ensureAndroidModules(config)
        assertFalse(
            config.getJSONObject("JacRed").getJSONObject("Jackett")
                .getJSONObject("Megapeer").getBoolean("enable")
        )
    }

    @Test
    fun androidMigrationLoadsEnabledProvidersButKeepsUnsupportedModulesSkipped() {
        val config = JSONObject(
            """{"BaseModule":{"SkipModules":["JacRed"]},"Filmix":{"token":"private-token"},"Rezka":{"premium":true}}"""
        )

        assertTrue(LampacEmbeddedConfig.ensureAndroidModules(config))
        val base = config.getJSONObject("BaseModule")
        assertEquals(".*", base.getJSONArray("LoadModules").getString(0))
        val skipped = base.getJSONArray("SkipModules")
        val modules = (0 until skipped.length()).map { skipped.getString(it) }.toSet()
        listOf("DLNA", "GStreamer", "Sync", "TimeCode", "TorrServer", "Tracks", "Transcoding")
            .forEach { assertTrue("$it must remain excluded on Android", it in modules) }
        assertFalse("JacRed must be active for torrent sources", "JacRed" in modules)
        assertEquals("private-token", config.getJSONObject("Filmix").getString("token"))
        assertTrue(config.getJSONObject("Rezka").getBoolean("premium"))
        val listen = config.getJSONObject("listen")
        assertEquals("127.0.0.1", listen.getString("ip"))
        assertEquals(9118, listen.getInt("port"))
    }

    @Test
    fun importConfigKeepsProviderSettingsAndForcesLocalListener() {
        val imported = """
            {
              "listen":{"ip":"0.0.0.0","port":80,"scheme":"http"},
              "BaseModule":{"SkipModules":["JacRed","GStreamer"]},
              "Filmix":{"token":"filmix-private","priority":7},
              "Rezka":{"premium":true,"token":"rezka-private"},
              "JacRed":{"typesearch":"webapi","webApiHost":"https://jacred.example"},
              "custom":{"keep":"yes"}
            }
        """.trimIndent()

        val saved = JSONObject(LampacEmbeddedConfig.prepareImportedConfig(imported))
        val listen = saved.getJSONObject("listen")
        assertEquals("127.0.0.1", listen.getString("ip"))
        assertEquals("127.0.0.1", listen.getString("localhost"))
        assertEquals(9118, listen.getInt("port"))
        assertEquals("http", listen.getString("scheme"))
        assertEquals("filmix-private", saved.getJSONObject("Filmix").getString("token"))
        assertEquals(7, saved.getJSONObject("Filmix").getInt("priority"))
        assertTrue(saved.getJSONObject("Rezka").getBoolean("premium"))
        assertEquals("rezka-private", saved.getJSONObject("Rezka").getString("token"))
        assertEquals("webapi", saved.getJSONObject("JacRed").getString("typesearch"))
        assertEquals("https://jacred.example", saved.getJSONObject("JacRed").getString("webApiHost"))
        assertEquals("yes", saved.getJSONObject("custom").getString("keep"))
        val skipped = saved.getJSONObject("BaseModule").getJSONArray("SkipModules")
        assertFalse((0 until skipped.length()).any { skipped.optString(it) == "JacRed" })
        assertEquals(".*", saved.getJSONObject("BaseModule").getJSONArray("LoadModules").getString(0))
    }

    @Test
    fun invalidInitConfigIsRejectedBeforeItCanBeWritten() {
        val error = runCatching {
            LampacEmbeddedConfig.prepareImportedConfig("""{"listen":""")
        }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
    }

    @Test
    fun localBridgeUrlHasNoPublicHost() {
        assertEquals("http://127.0.0.1:9118", LampacEmbeddedConfig.BASE_URL)
        assertFalse(LampacEmbeddedConfig.BASE_URL.contains("0.0.0.0"))
    }
}
