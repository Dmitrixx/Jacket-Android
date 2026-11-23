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
        assertTrue(config.getJSONObject("openstat").getBoolean("enable"))
    }

    @Test
    fun importedConfigRemovesPreviousModuleExclusionsAndPreservesProviders() {
        val original = JSONObject()
            .put("listen", JSONObject().put("ip", "0.0.0.0").put("port", 9999))
            .put(
                "BaseModule",
                JSONObject()
                    .put("LoadModules", JSONArray(listOf("Filmix")))
                    .put("SkipModules", JSONArray(listOf("GStreamer", "DLNA", "TorrServer")))
            )
            .put("chromium", JSONObject().put("enable", false))
            .put("Filmix", JSONObject().put("token", "preserve-token").put("priority", 8))
            .put("custom", JSONObject().put("keep", true))

        val normalized = JSONObject(LampacEmbeddedConfig.prepareImportedConfig(original.toString()))
        val base = normalized.getJSONObject("BaseModule")

        assertEquals("127.0.0.1", normalized.getJSONObject("listen").getString("ip"))
        assertEquals(9118, normalized.getJSONObject("listen").getInt("port"))
        assertEquals(listOf(".*"), base.getJSONArray("LoadModules").toStrings())
        assertEquals(0, base.getJSONArray("SkipModules").length())
        assertFalse(normalized.getJSONObject("chromium").getBoolean("enable"))
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
        assertEquals(0, base.getJSONArray("SkipModules").length())
        assertFalse(config.getJSONObject("SomeProvider").getBoolean("enable"))
    }

    private fun JSONArray.toStrings(): List<String> =
        (0 until length()).map { getString(it) }
}
