package app.jacket.mobile.lampac

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LampacTrackerConfigTest {
    private val initial = """
        {
          "listen": { "ip": "127.0.0.1", "port": 9118 },
          "Filmix": { "enable": true, "host": "https://fxapp.biz" },
          "JacRed": {
            "typesearch": "jackett",
            "disableJackett": false,
            "Jackett": {
              "Rutracker": {
                "host": "https://rutracker.org", "enable": true,
                "cookie": "keep-this-cookie", "priority": "torrent",
                "login": { "u": "old-user", "p": "old-pass" }
              },
              "Toloka": { "host": "https://toloka.to", "enable": false },
              "Rutor": { "host": "https://rutor.info", "enable": true }
            }
          }
        }
    """.trimIndent()

    @Test
    fun readsSupportedTrackerCredentialsWithUpstreamDefaults() {
        val settings = LampacTrackerConfig.read(initial).associateBy { it.id }
        assertEquals("old-user", settings.getValue("Rutracker").username)
        assertEquals("old-pass", settings.getValue("Rutracker").password)
        assertFalse(settings.getValue("Toloka").enabled)
        assertTrue(settings.getValue("Kinozal").enabled)
        assertEquals("", settings.getValue("Kinozal").username)
        assertEquals(7, settings.size)
    }

    @Test
    fun updatesCredentialsAndPreservesOtherJacredAndLampacValues() {
        val settings = LampacTrackerConfig.read(initial).map { tracker ->
            when (tracker.id) {
                "Rutracker" -> tracker.copy(username = "new-user", password = "new-pass")
                "Toloka" -> tracker.copy(enabled = true, username = "toloka-user")
                else -> tracker
            }
        }
        val updated = JSONObject(LampacTrackerConfig.update(initial, settings))
        val jackett = updated.getJSONObject("JacRed").getJSONObject("Jackett")
        val rutracker = jackett.getJSONObject("Rutracker")
        val toloka = jackett.getJSONObject("Toloka")
        assertEquals("https://rutracker.org", rutracker.getString("host"))
        assertEquals("keep-this-cookie", rutracker.getString("cookie"))
        assertEquals("torrent", rutracker.getString("priority"))
        assertEquals("new-user", rutracker.getJSONObject("login").getString("u"))
        assertEquals("new-pass", rutracker.getJSONObject("login").getString("p"))
        assertTrue(toloka.getBoolean("enable"))
        assertEquals("toloka-user", toloka.getJSONObject("login").getString("u"))
        assertTrue(jackett.getJSONObject("Rutor").getBoolean("enable"))
        assertEquals(9118, updated.getJSONObject("listen").getInt("port"))
        assertEquals("https://fxapp.biz", updated.getJSONObject("Filmix").getString("host"))
    }

    @Test
    fun blankCredentialsAreRemovedWithoutRemovingTrackerOrCookie() {
        val settings = LampacTrackerConfig.read(initial).map { tracker ->
            if (tracker.id == "Rutracker") tracker.copy(username = "", password = "") else tracker
        }
        val rutracker = JSONObject(LampacTrackerConfig.update(initial, settings))
            .getJSONObject("JacRed").getJSONObject("Jackett").getJSONObject("Rutracker")
        assertFalse(rutracker.has("login"))
        assertEquals("keep-this-cookie", rutracker.getString("cookie"))
    }

    @Test
    fun rejectsUnknownTrackerIds() {
        val error = runCatching {
            LampacTrackerConfig.update(initial, listOf(LampacTrackerAuth("Unknown", true, "user", "pass")))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
