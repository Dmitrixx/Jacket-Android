package app.jacket.mobile.torrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStreamHostPolicyTest {
    @Test
    fun onlyAcceptsLoopbackHostAndBoundPort() {
        assertTrue(LocalStreamHostPolicy.accepts("127.0.0.1:8090", 8090))
        assertTrue(LocalStreamHostPolicy.accepts("127.0.0.1", 8090))
        assertFalse(LocalStreamHostPolicy.accepts("192.168.1.2:8090", 8090))
        assertFalse(LocalStreamHostPolicy.accepts("localhost:8090", 8090))
        assertFalse(LocalStreamHostPolicy.accepts("127.0.0.1:8091", 8090))
        assertFalse(LocalStreamHostPolicy.accepts("127.0.0.1:", 8090))
        assertFalse(LocalStreamHostPolicy.accepts(null, 8090))
    }
}
