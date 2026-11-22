package app.jacket.mobile.lampac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LampacOutputLineBufferTest {
    @Test
    fun joinsSplitCredentialFieldsBeforeSanitization() {
        val sanitized = mutableListOf<String>()
        val buffer = LampacOutputLineBuffer { line ->
            sanitized += LampacLogSanitizer.sanitize(line)
        }

        buffer.append("Authorization: Bear".toByteArray(), "Authorization: Bear".toByteArray().size)
        buffer.append("er private-token\r\nnext line\n".toByteArray(), "er private-token\r\nnext line\n".toByteArray().size)
        buffer.finish()

        assertEquals(2, sanitized.size)
        assertFalse(sanitized.first().contains("private-token"))
        assertTrue(sanitized.last().contains("next line"))
    }

    @Test
    fun flushesFinalLineWithoutNewline() {
        val lines = mutableListOf<String>()
        val buffer = LampacOutputLineBuffer(onLine = lines::add)
        val chunk = "Lampac startup complete".toByteArray()

        buffer.append(chunk, chunk.size)
        assertTrue(lines.isEmpty())

        buffer.finish()
        assertEquals(listOf("Lampac startup complete"), lines)
    }

    @Test
    fun capsUntrustedLineLengthAndMarksTruncation() {
        val lines = mutableListOf<String>()
        val buffer = LampacOutputLineBuffer(maxLineBytes = 8, onLine = lines::add)
        val chunk = "1234567890\n".toByteArray()

        buffer.append(chunk, chunk.size)

        assertEquals(1, lines.size)
        assertEquals("12345678 [line truncated]", lines.single())
    }
}
