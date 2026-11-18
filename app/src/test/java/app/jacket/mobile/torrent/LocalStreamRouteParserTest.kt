package app.jacket.mobile.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalStreamRouteParserTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun parsesValidRouteAndQuery() {
        assertEquals(
            LocalStreamRoute(hash, 12),
            LocalStreamRouteParser.parse("/stream/$hash/12?quality=auto")
        )
    }

    @Test
    fun normalizesUppercaseHash() {
        assertEquals(
            hash,
            LocalStreamRouteParser.parse("/stream/${hash.uppercase()}/0")?.infoHash
        )
    }

    @Test
    fun rejectsTraversalAndMalformedSegments() {
        assertNull(LocalStreamRouteParser.parse("/stream/$hash/../secret"))
        assertNull(LocalStreamRouteParser.parse("/stream/$hash/%2fetc"))
        assertNull(LocalStreamRouteParser.parse("/stream/$hash/one"))
        assertNull(LocalStreamRouteParser.parse("/other/$hash/0"))
        assertNull(LocalStreamRouteParser.parse("/stream/short/0"))
        assertNull(LocalStreamRouteParser.parse("/stream/$hash/0/extra"))
    }
}
