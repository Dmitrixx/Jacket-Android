package app.jacket.mobile.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress

class JackettIndexerClientTest {

    @Test
    fun buildsDashboardLoginUrlAndPreservesReverseProxyPrefix() {
        assertEquals(
            "https://seedbox.example/wp-content/Jackett/UI/Dashboard",
            JackettIndexerClient.buildDashboardLoginUrl("https://seedbox.example/wp-content/Jackett")
        )
    }

    @Test
    fun extractsOnlyNonEmptyJackettSessionCookie() {
        val headers = mapOf(
            "Set-Cookie" to listOf(
                "OtherCookie=ignored; Path=/",
                "Jackett=session-123; Path=/; HttpOnly"
            )
        )
        assertEquals("Jackett=session-123", JackettIndexerClient.extractDashboardCookie(headers))
        assertEquals(
            null,
            JackettIndexerClient.extractDashboardCookie(
                mapOf("Set-Cookie" to listOf("OtherCookie=value; Path=/", "Jackett=; Path=/"))
            )
        )
    }

    @Test
    fun postsUrlEncodedPasswordAndReturnsOnlyJackettCookie() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requestMethod = ""
        var requestBody = ""
        server.createContext("/reverse/Jackett/UI/Dashboard") { exchange ->
            requestMethod = exchange.requestMethod
            requestBody = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            exchange.responseHeaders.add("Set-Cookie", "OtherCookie=ignored; Path=/")
            exchange.responseHeaders.add("Set-Cookie", "Jackett=session-123; Path=/; HttpOnly")
            exchange.responseHeaders.add("Location", "/reverse/Jackett/UI/Dashboard")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/reverse/Jackett"
            val cookie = JackettIndexerClient.loginDashboardCookie(base, "admin pass&word")

            assertEquals("Jackett=session-123", cookie)
            assertEquals("POST", requestMethod)
            assertEquals("password=admin+pass%26word", requestBody)
        } finally {
            server.stop(0)
        }
    }

    @Test(expected = IOException::class)
    fun rejectsRedirectWithoutJackettSessionCookie() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/Jackett/UI/Dashboard") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "OtherCookie=ignored; Path=/")
            exchange.responseHeaders.add("Location", "/Jackett/UI/Dashboard")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            JackettIndexerClient.loginDashboardCookie(
                "http://127.0.0.1:${server.address.port}/Jackett",
                "wrong-password"
            )
        } finally {
            server.stop(0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAdminPasswordOverConfiguredLimit() {
        JackettIndexerClient.loginDashboardCookie(
            "http://127.0.0.1:9117",
            "x".repeat(513)
        )
    }

    @Test
    fun buildsNativeCatalogueEndpointWithoutLosingReverseProxyPath() {
        assertEquals(
            "https://seedbox.example/wp-content/Jackett/api/v2.0/indexers",
            JackettIndexerClient.buildIndexersUrl("https://seedbox.example/wp-content/Jackett")
        )
    }

    @Test
    fun buildsAuthenticatedConfigEndpointWithValidatedIndexerId() {
        assertEquals(
            "https://seedbox.example/wp/Jackett/api/v2.0/indexers/rutracker/config",
            JackettIndexerClient.buildIndexerConfigUrl("https://seedbox.example/wp/Jackett", "rutracker")
        )
    }

    @Test
    fun buildsNativeIndexerTestEndpointWithoutLosingReverseProxyPath() {
        assertEquals(
            "https://seedbox.example/proxy/Jackett/api/v2.0/indexers/rutracker/test",
            JackettIndexerClient.buildIndexerTestUrl("https://seedbox.example/proxy/Jackett", "rutracker")
        )
    }

    @Test
    fun parsesPublicPrivateAndConfiguredIndexerRowsFromJackettRestJson() {
        val json = """
            [
              {
                "id":"rutracker",
                "name":"RuTracker",
                "description":"Private tracker with Russian releases",
                "site_link":"https://rutracker.org/",
                "alternativesitelinks":["https://rutracker.example/"],
                "language":"ru-RU",
                "type":"private",
                "configured":true,
                "last_error":"",
                "caps":[
                  {"ID":"2000","Name":"Movies"},
                  {"ID":"5000","Name":"TV"}
                ]
              },
              {
                "id":"public.example",
                "name":"Public Example",
                "description":"Public tracker",
                "site_link":"https://tracker.example/",
                "language":"en-US",
                "type":"public",
                "configured":false,
                "caps":[]
              }
            ]
        """.trimIndent()

        val entries = JackettIndexerClient.parseIndexersJson(json)

        assertEquals(2, entries.size)
        assertEquals("RuTracker", entries[0].name)
        assertTrue(entries[0].configured)
        assertTrue(entries[0].isPrivate)
        assertEquals(listOf("2000", "5000"), entries[0].categoryIds)
        assertEquals(listOf("Movies", "TV"), entries[0].categoryNames)
        assertEquals("https://rutracker.org/", entries[0].siteLink)
        assertEquals("https://rutracker.example/", entries[0].alternateSiteLinks.single())
        assertEquals("Public Example", entries[1].name)
        assertFalse(entries[1].configured)
        assertFalse(entries[1].isPrivate)
    }

    @Test
    fun redactsApiKeysAndConfiguredSecretsFromJackettLastError() {
        val json = """
            [{
              "id":"rutracker",
              "name":"RuTracker",
              "type":"private",
              "configured":true,
              "last_error":"Request failed at https://tracker.example/api?apikey=api-secret&token=session-token"
            }]
        """.trimIndent()

        val entry = JackettIndexerClient.parseIndexersJson(
            json,
            listOf("api-secret", "session-token")
        ).single()

        assertFalse(entry.lastError.orEmpty().contains("api-secret"))
        assertFalse(entry.lastError.orEmpty().contains("session-token"))
        assertFalse(entry.lastError.orEmpty().contains("tracker.example/api"))
    }

    @Test
    fun rejectsUnsafeIndexerSiteLinksAndMalformedIdentifiers() {
        val json = """
            [
              {
                "id":"local",
                "name":"Local test",
                "site_link":"http://example.com/admin",
                "type":"public",
                "configured":true
              },
              {
                "id":"../admin",
                "name":"Malformed identifier",
                "site_link":"https://tracker.example/"
              }
            ]
        """.trimIndent()

        val entries = JackettIndexerClient.parseIndexersJson(json)

        assertEquals(1, entries.size)
        assertEquals(null, entries.single().siteLink)
    }

    @Test
    fun parsesStringPasswordBooleanSelectCheckboxAndTagConfigurationFields() {
        val json = """
            [
              {"id":"sitelink","name":"Site Link","type":"inputstring","value":"https://tracker.example/"},
              {"id":"username","name":"Username","type":"inputstring","value":"user"},
              {"id":"password","name":"Password","type":"password","value":"|||%%PREVJACKPASSWD%%|||"},
              {"id":"freeleech","name":"Search freeleech only","type":"inputbool","value":true},
              {"id":"sort","name":"Sort requested from site","type":"inputselect","value":"created","options":{"created":"Created","seeders":"Seeders"}},
              {"id":"categories","name":"Categories","type":"inputcheckbox","values":["2000"],"options":{"2000":"Movies","5000":"TV"}},
              {"id":"tags","name":"Tags","type":"inputtags","value":"hd,4k","separator":",","whitelist":"hd,4k"}
            ]
        """.trimIndent()

        val fields = JackettIndexerClient.parseIndexerConfig(json)

        assertEquals(7, fields.size)
        assertEquals("https://tracker.example/", fields[0].value)
        assertEquals("user", fields[1].value)
        assertTrue(fields[2].isPreviousPassword)
        assertEquals("true", fields[3].value)
        assertEquals("Seeders", fields[4].options["seeders"])
        assertEquals(listOf("2000"), fields[5].values)
        assertEquals("hd,4k", fields[6].value)
    }

    @Test
    fun configPayloadPreservesPasswordMarkerAndUsesJackettValueTypes() {
        val fields = listOf(
            JackettConfigField("password", "password", "Password", JackettConfigField.PASSWORD_REPLACEMENT),
            JackettConfigField("freeleech", "inputbool", "Freeleech", "true"),
            JackettConfigField("sort", "inputselect", "Sort", "seeders"),
            JackettConfigField("categories", "inputcheckbox", "Categories", values = listOf("2000", "invalid"),
                options = mapOf("2000" to "Movies")),
            JackettConfigField("tags", "inputtags", "Tags", "hd,4k")
        )

        val payload = JackettIndexerClient.buildConfigPayload(fields)

        assertEquals(5, payload.length())
        assertEquals(JackettConfigField.PASSWORD_REPLACEMENT, payload.getJSONObject(0).getString("value"))
        assertTrue(payload.getJSONObject(1).getBoolean("value"))
        assertEquals("seeders", payload.getJSONObject(2).getString("value"))
        assertEquals(JSONArray(listOf("2000")).toString(), payload.getJSONObject(3).getJSONArray("values").toString())
        assertEquals("hd,4k", payload.getJSONObject(4).getString("value"))
    }

    @Test
    fun clearingMaskedPasswordInputPreservesPreviousServerPassword() {
        val field = JackettConfigField(
            id = "password",
            type = "password",
            name = "Password",
            value = "",
            originalValue = JackettConfigField.PASSWORD_REPLACEMENT
        )

        val payload = JackettIndexerClient.buildConfigPayload(listOf(field))

        assertEquals(
            JackettConfigField.PASSWORD_REPLACEMENT,
            payload.getJSONObject(0).getString("value")
        )
    }

    @Test
    fun replacingMaskedPasswordSubmitsNewValue() {
        val field = JackettConfigField(
            id = "password",
            type = "password",
            name = "Password",
            value = "new-secret",
            originalValue = JackettConfigField.PASSWORD_REPLACEMENT
        )

        val payload = JackettIndexerClient.buildConfigPayload(listOf(field))

        assertEquals("new-secret", payload.getJSONObject(0).getString("value"))
    }

    @Test
    fun parsedMaskedPasswordRemainsUnchangedAfterEditingFieldIsCleared() {
        val fields = JackettIndexerClient.parseIndexerConfig(
            """[{"id":"password","name":"Password","type":"password","value":"${JackettConfigField.PASSWORD_REPLACEMENT}"}]"""
        )
        val parsed = fields.single()
        assertEquals("", parsed.value)
        assertTrue(parsed.isPreviousPassword)

        val edited = parsed.copy(value = "new-secret")
        assertEquals("new-secret", edited.value)
        val clearedAfterEditing = edited.copy(value = "")
        assertTrue(clearedAfterEditing.isPreviousPassword)

        val payload = JackettIndexerClient.buildConfigPayload(listOf(clearedAfterEditing))
        assertEquals(
            JackettConfigField.PASSWORD_REPLACEMENT,
            payload.getJSONObject(0).getString("value")
        )
    }

    @Test
    fun buildsSpecificIndexerSearchConfigFromSavedJackettConnection() {
        val base = IndexerConfig(
            id = "jackett-1",
            name = "My Jackett",
            searchUrlTemplate = "https://seedbox.example/wp/Jackett/api/v2.0/indexers/all/results/torznab/api?t=search&q={query}",
            protocol = IndexerProtocol.JACKETT,
            apiKey = "private-key",
            cookie = "session=encrypted-locally"
        )
        val queryConfig = JackettIndexerClient.searchConfig(base, "rutracker")

        assertEquals("private-key", queryConfig.apiKey)
        assertEquals("session=encrypted-locally", queryConfig.cookie)
        assertEquals(
            "https://seedbox.example/wp/Jackett/api/v2.0/indexers/rutracker/results/torznab/api?t=search&q={query}",
            queryConfig.searchUrlTemplate
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDotSegmentIndexerIds() {
        JackettIndexerClient.buildIndexerConfigUrl("https://seedbox.example", "..")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalIndexerId() {
        JackettIndexerClient.buildIndexerConfigUrl("https://seedbox.example", "../admin")
    }
}
