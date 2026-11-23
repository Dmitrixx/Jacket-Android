package app.jacket.mobile.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStreamCorsPolicyTest {
    @Test
    fun embeddedLampaOriginCanReadByteRangeStreams() {
        val headers = LocalStreamCorsPolicy.responseHeaders(LocalStreamCorsPolicy.LAMPA_ORIGIN)

        assertTrue(
            headers.contains(
                "Access-Control-Allow-Origin: " + LocalStreamCorsPolicy.LAMPA_ORIGIN
            )
        )
        assertTrue(headers.contains("Access-Control-Allow-Methods: GET, HEAD, OPTIONS"))
        assertTrue(headers.contains("Access-Control-Allow-Headers: Range, Content-Type"))
        assertTrue(headers.contains("Access-Control-Expose-Headers: Accept-Ranges, Content-Length, Content-Range"))
    }

    @Test
    fun unrelatedOriginsDoNotReceiveCorsPermission() {
        assertEquals(emptyList<String>(), LocalStreamCorsPolicy.responseHeaders("https://example.com"))
        assertEquals(emptyList<String>(), LocalStreamCorsPolicy.responseHeaders(null))
    }
}
