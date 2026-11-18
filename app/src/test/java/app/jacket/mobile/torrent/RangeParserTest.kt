package app.jacket.mobile.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RangeParserTest {
    @Test
    fun missingRangeMeansWholeRepresentation() {
        assertNull(RangeParser.parse(null, 1_000L))
    }

    @Test
    fun parsesInclusiveStartAndEnd() {
        val result = RangeParser.parse("bytes=100-199", 1_000L)
        assertEquals(100L, result?.start)
        assertEquals(199L, result?.endInclusive)
        assertEquals(100L, result?.length)
    }

    @Test
    fun parsesOpenEndedRange() {
        val result = RangeParser.parse("bytes=900-", 1_000L)
        assertEquals(900L, result?.start)
        assertEquals(999L, result?.endInclusive)
    }

    @Test
    fun parsesSuffixRange() {
        val result = RangeParser.parse("bytes=-128", 1_000L)
        assertEquals(872L, result?.start)
        assertEquals(999L, result?.endInclusive)
    }

    @Test
    fun clampsSuffixAndEndToFileLength() {
        assertEquals(ByteRange(0L, 99L), RangeParser.parse("bytes=-1000", 100L))
        assertEquals(ByteRange(40L, 99L), RangeParser.parse("bytes=40-999", 100L))
    }

    @Test
    fun rejectsUnsatisfiableRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            RangeParser.parse("bytes=1000-", 1_000L)
        }
    }

    @Test
    fun rejectsMultipartAndMalformedRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            RangeParser.parse("bytes=0-10,20-30", 1_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RangeParser.parse("bytes=abc-def", 1_000L)
        }
    }
}
