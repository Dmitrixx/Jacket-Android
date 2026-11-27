package app.jacket.mobile.byedpi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ByeDpiArgumentParserTest {
    @Test
    fun prependsLoopbackProxyAddressAndPort() {
        val args = ByeDpiArgumentParser.build("--disorder 1 --timeout 3")

        assertEquals("ciadpi", args[0])
        assertEquals("--ip", args[1])
        assertEquals("127.0.0.1", args[2])
        assertEquals("--port", args[3])
        assertEquals("1080", args[4])
        assertEquals(listOf("--disorder", "1", "--timeout", "3"), args.drop(5))
    }

    @Test
    fun acceptsSeparateOptionValuesAndQuotedSniValues() {
        val args = ByeDpiArgumentParser.build("--fake 1 -n 'www.example.org' --hosts :example.org")

        assertTrue(args.contains("www.example.org"))
        assertTrue(args.contains(":example.org"))
    }

    @Test
    fun refusesUserDefinedProxyBindAddressAndPort() {
        listOf(
            "--ip 0.0.0.0",
            "--port 1234",
            "--ip=0.0.0.0",
            "--port=1234",
            "-i0.0.0.0",
            "-p1234"
        ).forEach { strategy ->
            val result = runCatching { ByeDpiArgumentParser.build(strategy) }
            assertTrue("Expected the proxy endpoint to be rejected: $strategy", result.isFailure)
        }
    }

    @Test
    fun refusesDaemonAndTransparentMode() {
        listOf("--daemon", "-D", "--transparent", "-E").forEach { strategy ->
            assertTrue(
                "Expected unsafe service option to be rejected: $strategy",
                runCatching { ByeDpiArgumentParser.build(strategy) }.isFailure
            )
        }
    }

    @Test
    fun refusesUnclosedQuotes() {
        assertTrue(
            runCatching { ByeDpiArgumentParser.build("--fake 1 -n 'unclosed") }.isFailure
        )
    }
}
