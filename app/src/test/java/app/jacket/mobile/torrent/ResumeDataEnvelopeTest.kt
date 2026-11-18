package app.jacket.mobile.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResumeDataEnvelopeTest {
    @Test
    fun roundTripsPayload() {
        val input = ByteArray(8192) { (it % 251).toByte() }
        assertArrayEquals(input, ResumeDataEnvelope.decode(ResumeDataEnvelope.encode(input)))
    }

    @Test
    fun acceptsLegacyUnwrappedResumeData() {
        val legacy = "d4:infoe".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(legacy, ResumeDataEnvelope.decode(legacy))
    }

    @Test
    fun rejectsChangedPayload() {
        val stored = ResumeDataEnvelope.encode("fast-resume".toByteArray())
        stored[stored.lastIndex] = (stored.last().toInt() xor 0x40).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            ResumeDataEnvelope.decode(stored)
        }
    }

    @Test
    fun rejectsTruncatedHeaderAndPayload() {
        val stored = ResumeDataEnvelope.encode("resume".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            ResumeDataEnvelope.decode(stored.copyOf(12))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResumeDataEnvelope.decode(stored.copyOf(stored.lastIndex))
        }
    }
}
