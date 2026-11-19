package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    @Test
    fun acceptsHttpsAndRejectsPublicCleartextHttp() {
        assertTrue(LocalNetwork.isAllowedUrl("https://indexer.example/api?q=x"))
        assertFalse(LocalNetwork.isAllowedUrl("http://indexer.example/api?q=x"))
        assertFalse(LocalNetwork.isAllowedUrl("http://8.8.8.8:9117/api"))
        assertFalse(LocalNetwork.isAllowedUrl("ftp://192.168.1.5/file"))
    }

    @Test
    fun acceptsPrivateAndLocalNetworkHosts() {
        listOf(
            "http://192.168.1.50:9117/api",
            "http://10.0.0.2:9117",
            "http://172.16.5.9:9117",
            "http://172.31.255.1",
            "http://127.0.0.1:9117",
            "http://169.254.10.1",
            "http://100.101.102.103:9117",
            "http://[::1]:9117/api",
            "http://[fd12:3456::1]:9117",
            "http://[fe80::1]:9117",
            "http://localhost:9117",
            "http://nas.local:9117",
            "http://jackett.lan"
        ).forEach { assertTrue(it, LocalNetwork.isAllowedUrl(it)) }
    }

    @Test
    fun rejectsHostsOutsideLocalRangesAndAmbiguousSingleLabels() {
        listOf(
            "http://172.15.0.1",
            "http://172.32.0.1",
            "http://192.169.0.1",
            "http://100.63.0.1",
            "http://100.128.0.1",
            "http://11.0.0.1",
            "http://nas:9117",
            "http://10.0.0.5.evil.example",
            "http://[2001:db8::1]"
        ).forEach { assertFalse(it, LocalNetwork.isAllowedUrl(it)) }
    }

    @Test
    fun credentialsAndInvalidSchemesAreRejected() {
        assertFalse(LocalNetwork.isAllowedUrl("https://user:pass@indexer.example/api"))
        assertFalse(LocalNetwork.isAllowedUrl("http://admin@192.168.1.5:9117"))
    }

}
