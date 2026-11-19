package app.jacket.mobile.torrent

/** One piece deadline relative to the first requested piece. */
internal data class StreamPieceDeadline(
    val pieceIndex: Int,
    val deadlineMs: Int
)

/** Bounded, ordered piece window for a video byte-range request. */
internal data class StreamPiecePlan(
    val firstPiece: Int,
    val lastPiece: Int,
    val deadlines: List<StreamPieceDeadline>
)

/**
 * Maps a file-relative HTTP byte range to torrent piece indices.
 *
 * The requested piece gets the nearest deadline. Subsequent pieces receive
 * increasing deadlines, so libtorrent favours the playback position before
 * the forward buffer without enabling global sequential-download mode.
 */
internal object StreamPiecePlanner {
    fun plan(
        fileOffset: Long,
        fileSize: Long,
        pieceLength: Long,
        requestStart: Long,
        requestLength: Long,
        firstFilePiece: Int,
        lastFilePiece: Int,
        prefetchBytes: Long = DEFAULT_PREFETCH_BYTES,
        maxPieces: Int = DEFAULT_MAX_PIECES,
        deadlineStepMs: Int = DEFAULT_DEADLINE_STEP_MS
    ): StreamPiecePlan {
        require(fileOffset >= 0L) { "Отрицательное смещение файла" }
        require(fileSize > 0L) { "Пустой файл" }
        require(pieceLength > 0L) { "Некорректный размер части торрента" }
        require(requestStart >= 0L && requestLength > 0L) { "Некорректный HTTP-диапазон" }
        require(requestStart < fileSize && requestLength <= fileSize - requestStart) {
            "HTTP-диапазон выходит за пределы файла"
        }
        require(firstFilePiece >= 0 && lastFilePiece >= firstFilePiece) {
            "Некорректные границы файла в торренте"
        }
        require(prefetchBytes >= 0L && maxPieces > 0 && deadlineStepMs >= 0)

        val absoluteStart = Math.addExact(fileOffset, requestStart)
        val requestedEndExclusive = Math.addExact(requestStart, requestLength)
        val prefetchEndExclusive = if (prefetchBytes > fileSize - requestedEndExclusive) {
            fileSize
        } else {
            requestedEndExclusive + prefetchBytes
        }
        val absoluteEndInclusive = Math.addExact(
            fileOffset,
            prefetchEndExclusive - 1L
        )

        val first = (absoluteStart / pieceLength).toInt().coerceAtLeast(firstFilePiece)
        val last = minOf(
            (absoluteEndInclusive / pieceLength).toInt(),
            lastFilePiece,
            first + maxPieces - 1
        )
        require(first in firstFilePiece..lastFilePiece && last >= first) {
            "Не удалось сопоставить диапазон с частями торрента"
        }

        val deadlines = (first..last).mapIndexed { position, piece ->
            StreamPieceDeadline(
                pieceIndex = piece,
                deadlineMs = (position.toLong() * deadlineStepMs)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            )
        }
        return StreamPiecePlan(first, last, deadlines)
    }

    const val DEFAULT_PREFETCH_BYTES = 8L * 1024L * 1024L
    const val DEFAULT_MAX_PIECES = 32
    const val DEFAULT_DEADLINE_STEP_MS = 350
}
