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
        assertTrue("TorrServer" in modules)
    }

    @Test
    fun localBridgeUrlHasNoPublicHost() {
        assertEquals("http://127.0.0.1:9118", LampacEmbeddedConfig.BASE_URL)
        assertFalse(LampacEmbeddedConfig.BASE_URL.contains("0.0.0.0"))
    }
}
