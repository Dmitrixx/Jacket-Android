package app.jacket.mobile.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalStreamRouteParserTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private val hash = "abcdef0123456789abcdef0123456789abcdef01"

    @Test
    fun parsesValidRouteAndQuery() {
        assertEquals(
            LocalStreamRoute(token, hash, 12),
            LocalStreamRouteParser.parse("/stream/$token/$hash/12?quality=auto")
        )
    }

    @Test
    fun normalizesUppercaseTokenAndHash() {
        val route = LocalStreamRouteParser.parse("/stream/${token.uppercase()}/${hash.uppercase()}/0")
        assertEquals(token, route?.token)
        assertEquals(hash, route?.infoHash)
    }

    @Test
    fun rejectsTraversalAndMalformedSegments() {
        assertNull(LocalStreamRouteParser.parse("/stream/$token/$hash/../secret"))
        assertNull(LocalStreamRouteParser.parse("/stream/$token/$hash/%2fetc"))
        assertNull(LocalStreamRouteParser.parse("/stream/short/$hash/0"))
        assertNull(LocalStreamRouteParser.parse("/stream/$token/short/0"))
        assertNull(LocalStreamRouteParser.parse("/stream/$token/$hash/one"))
        assertNull(LocalStreamRouteParser.parse("/other/$token/$hash/0"))
        assertNull(LocalStreamRouteParser.parse("/stream/$hash/0"))
        assertNull(LocalStreamRouteParser.parse("/stream/$token/$hash/0/extra"))
    }
}
