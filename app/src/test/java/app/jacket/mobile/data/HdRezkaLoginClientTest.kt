package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HdRezkaLoginClientTest {
    @Test
    fun normalizesSecureMirror() {
        assertEquals("https://hdrezka.example", HdRezkaLoginClient.validateMirror("https://hdrezka.example/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInsecureMirror() {
        HdRezkaLoginClient.validateMirror("http://hdrezka.example")
    }

    @Test
    fun acceptsUserSuppliedRotatingMirrorHost() {
        assertEquals("https://rezkify.com", HdRezkaLoginClient.validateMirror("https://rezkify.com/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPrivateNetworkMirror() {
        HdRezkaLoginClient.validateMirror("https://192.168.1.5")
    }
}
