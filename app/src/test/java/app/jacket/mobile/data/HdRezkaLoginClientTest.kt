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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnrelatedHost() {
        HdRezkaLoginClient.validateMirror("https://example.com")
    }
}
