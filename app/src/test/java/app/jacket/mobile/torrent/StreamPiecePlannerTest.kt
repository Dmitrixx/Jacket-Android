package app.jacket.mobile.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPiecePlannerTest {
    @Test
    fun mapsUnalignedFileRangeToOrderedTorrentPieces() {
        val plan = StreamPiecePlanner.plan(
            fileOffset = 5_500_000L,
            fileSize = 20_000_000L,
            pieceLength = 1_000_000L,
            requestStart = 1_500_000L,
            requestLength = 500_000L,
            firstFilePiece = 5,
            lastFilePiece = 25,
            prefetchBytes = 2_000_000L
        )

        assertEquals(7, plan.firstPiece)
        assertEquals(9, plan.lastPiece)
        assertEquals(listOf(7, 8, 9), plan.deadlines.map { it.pieceIndex })
        assertEquals(listOf(0, 350, 700), plan.deadlines.map { it.deadlineMs })
    }

    @Test
    fun clampsPrefetchAtEndOfFile() {
        val plan = StreamPiecePlanner.plan(
            fileOffset = 4_096L,
            fileSize = 1_000L,
            pieceLength = 1_024L,
            requestStart = 900L,
            requestLength = 100L,
            firstFilePiece = 4,
            lastFilePiece = 4,
            prefetchBytes = 8_000_000L
        )

        assertEquals(4, plan.firstPiece)
        assertEquals(4, plan.lastPiece)
        assertEquals(listOf(4), plan.deadlines.map { it.pieceIndex })
    }

    @Test
    fun capsLookaheadPieceCount() {
        val plan = StreamPiecePlanner.plan(
            fileOffset = 0L,
            fileSize = 4_000_000L,
            pieceLength = 16_384L,
            requestStart = 0L,
            requestLength = 16_384L,
            firstFilePiece = 0,
            lastFilePiece = 243,
            prefetchBytes = 8_000_000L,
            maxPieces = 12
        )

        assertEquals(12, plan.deadlines.size)
        assertEquals(11, plan.lastPiece)
        assertTrue(plan.deadlines.zipWithNext().all { (a, b) -> a.deadlineMs < b.deadlineMs })
    }


    @Test
    fun schedulesTailPieceAfterTheInitialForwardWindow() {
        val plan = StreamPiecePlanner.plan(
            fileOffset = 0L,
            fileSize = 100L * 1_024L,
            pieceLength = 1_024L,
            requestStart = 0L,
            requestLength = 1_024L,
            firstFilePiece = 0,
            lastFilePiece = 99,
            prefetchBytes = 1_024L,
            maxPieces = 2,
            includeTailPiece = true
        )

        assertEquals(listOf(0, 1, 99), plan.deadlines.map { it.pieceIndex })
        assertEquals(listOf(0, 350, 1_400), plan.deadlines.map { it.deadlineMs })
        assertEquals(1, plan.lastPiece)
    }

    @Test
    fun doesNotDuplicateTailPieceWhenItIsAlreadyInForwardWindow() {
        val plan = StreamPiecePlanner.plan(
            fileOffset = 0L,
            fileSize = 2_048L,
            pieceLength = 1_024L,
            requestStart = 0L,
            requestLength = 2_048L,
            firstFilePiece = 0,
            lastFilePiece = 1,
            prefetchBytes = 0L,
            includeTailPiece = true
        )

        assertEquals(listOf(0, 1), plan.deadlines.map { it.pieceIndex })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRangeOutsideFile() {
        StreamPiecePlanner.plan(
            fileOffset = 0L,
            fileSize = 10L,
            pieceLength = 4L,
            requestStart = 8L,
            requestLength = 3L,
            firstFilePiece = 0,
            lastFilePiece = 2
        )
    }
}
